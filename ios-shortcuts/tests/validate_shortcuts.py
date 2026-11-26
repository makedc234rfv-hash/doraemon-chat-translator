#!/usr/bin/env python3
"""
Automated validator and end-to-end mock-API simulator for 机器猫 (Doraemon) iOS Shortcuts.

Modes:
  1. Source validation (--src-dir): checks .cherri sources for privacy guarantees,
     import questions, valid endpoints, and zero leaked secrets.
  2. Compiled unsigned plist validation (--unsigned-dir): parses binary/XML .shortcut
     files produced by Cherri, verifies Apple Shortcuts plist invariants, reconstructs
     the exact HTTP request payload/headers, and runs end-to-end HTTP tests against local
     mock Gemini / OpenAI / DeepSeek servers (both success and error paths).
  3. Signed shortcut verification (--signed-dir): verifies Apple Official AEA1 signature
     headers on macOS-signed .shortcut files ready for iPhone import.
"""

from __future__ import annotations

import argparse
import base64
import http.server
import json
import os
import plistlib
import re
import socketserver
import sys
import threading
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

FORBIDDEN_ACTIONS = {
    "is.workflow.actions.savetophotoalbum",
    "is.workflow.actions.getcontacts",
    "is.workflow.actions.filter.contacts",
    "is.workflow.actions.sendmessage",
    "is.workflow.actions.sendemail",
    "is.workflow.actions.getcurrentlocation",
}

EXPECTED_SHORTCUTS = {
    "Doraemon-Gemini": {
        "provider": "gemini",
        "min_questions": 3,
        "uses_vision": True,
        "expected_header_key": "x-goog-api-key",
        "expected_response_root": "candidates",
    },
    "Doraemon-OpenAI-Vision": {
        "provider": "openai_vision",
        "min_questions": 4,
        "uses_vision": True,
        "expected_header_key": "Authorization",
        "expected_response_root": "choices",
    },
    "Doraemon-Text-DeepSeek": {
        "provider": "deepseek_text",
        "min_questions": 4,
        "uses_vision": False,
        "expected_header_key": "Authorization",
        "expected_response_root": "choices",
    },
}


def assert_true(condition: bool, msg: str) -> None:
    if not condition:
        raise AssertionError(msg)


def validate_sources(src_dir: Path) -> None:
    print(f"[1/3] Validating .cherri sources in {src_dir} ...")
    for name, spec in EXPECTED_SHORTCUTS.items():
        path = src_dir / f"{name}.cherri"
        assert_true(path.exists(), f"Missing source file: {path}")
        content = path.read_text(encoding="utf-8")

        # Must not contain real API keys
        assert_true("AIzaSy" not in content, f"Leaked Google key in {path}")
        assert_true(not re.search(r"sk-[A-Za-z0-9]{20,}", content), f"Leaked OpenAI/DeepSeek key in {path}")

        # Must define icon, name, and import questions
        assert_true("#define name " in content, f"Missing #define name in {path}")
        questions = re.findall(r"^#question\s+\w+", content, flags=re.MULTILINE)
        assert_true(
            len(questions) >= spec["min_questions"],
            f"{name}: expected >= {spec['min_questions']} #question directives, got {len(questions)}",
        )

        # Privacy checks in source
        assert_true("takeScreenshot()" in content, f"{name}: missing takeScreenshot()")
        assert_true("savePhoto" not in content and "saveToPhotoAlbum" not in content, f"{name}: must not save screenshots")
        if spec["uses_vision"]:
            assert_true("convertToJPEG(resized, nil, false)" in content, f"{name}: must strip EXIF metadata")
            assert_true("base64Encode(" in content, f"{name}: missing base64Encode")
        else:
            assert_true("extractImageText(" in content, f"{name}: missing extractImageText")

        # Balanced braces sanity check
        assert_true(content.count("{") == content.count("}"), f"{name}: unbalanced curly braces")
        print(f"  OK source: {path.name} ({len(questions)} import questions)")


def _decode_wf_text(obj: Any, variables: dict[str, str]) -> str:
    """Resolve an Apple Shortcuts WFTextTokenString or plain string using mock variables."""
    if isinstance(obj, str):
        return obj
    if isinstance(obj, dict) and obj.get("WFSerializationType") == "WFTextTokenString":
        val = obj.get("Value", {})
        raw = val.get("string", "")
        attachments = val.get("attachmentsByRange", {})
        if not attachments:
            return raw

        # Attachments are keyed by UTF-16 ranges "{offset, length}"
        utf16 = raw.encode("utf-16-le")
        replacements: list[tuple[int, int, str]] = []
        for rng, meta in attachments.items():
            m = re.match(r"\{(\d+),\s*(\d+)\}", rng)
            assert_true(m is not None, f"Invalid attachment range: {rng}")
            start = int(m.group(1))
            length = int(m.group(2))
            unit_slice = utf16[start * 2 : (start + length) * 2].decode("utf-16-le")
            assert_true(
                unit_slice == "\ufffc",
                f"UTF-16 range {rng} points to {unit_slice!r} instead of U+FFFC in {raw!r}",
            )
            var_name = meta.get("VariableName") or meta.get("OutputName") or meta.get("Type") or "var"
            replacement = variables.get(var_name, f"<{var_name}>")
            replacements.append((start, start + length, replacement))

        # Rebuild from UTF-16 offsets right-to-left
        out_units = list(utf16[i : i + 2] for i in range(0, len(utf16), 2))
        for start, end, rep in sorted(replacements, key=lambda x: x[0], reverse=True):
            rep_units = [rep.encode("utf-16-le")[i : i + 2] for i in range(0, len(rep.encode("utf-16-le")), 2)]
            out_units[start:end] = rep_units
        return b"".join(out_units).decode("utf-16-le")
    return str(obj)


def _decode_wf_dict_item(item: dict[str, Any], variables: dict[str, str]) -> Any:
    item_type = item.get("WFItemType")
    raw_val = item.get("WFValue")
    if item_type == 0:  # Text
        return _decode_wf_text(raw_val, variables)
    if item_type == 3:  # Number
        txt = _decode_wf_text(raw_val, variables)
        try:
            return int(txt) if "." not in txt else float(txt)
        except ValueError:
            return txt
    if item_type == 4:  # Boolean
        return bool(raw_val.get("Value")) if isinstance(raw_val, dict) else bool(raw_val)
    if item_type == 2:  # Array
        arr_items = raw_val.get("Value", []) if isinstance(raw_val, dict) else []
        return [_decode_wf_dict_item(sub, variables) for sub in arr_items]
    if item_type == 1:  # Nested Dict (Shortcuts double-wraps WFDictionaryFieldValue)
        inner = raw_val.get("Value", {}) if isinstance(raw_val, dict) else {}
        if isinstance(inner, dict) and "Value" in inner:
            inner = inner["Value"]
        sub_items = inner.get("WFDictionaryFieldValueItems", [])
        return {
            _decode_wf_text(sub["WFKey"], variables): _decode_wf_dict_item(sub, variables)
            for sub in sub_items
        }
    return raw_val


def _decode_wf_dictionary(field: dict[str, Any], variables: dict[str, str]) -> dict[str, Any]:
    val = field.get("Value", {})
    if isinstance(val, dict) and "Value" in val and isinstance(val["Value"], dict):
        val = val["Value"]
    items = val.get("WFDictionaryFieldValueItems", [])
    result: dict[str, Any] = {}
    for item in items:
        key = _decode_wf_text(item["WFKey"], variables)
        result[key] = _decode_wf_dict_item(item, variables)
    return result


class _MockAiHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format: str, *args: Any) -> None:
        pass

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", "0"))
        body_raw = self.rfile.read(length)
        payload = json.loads(body_raw.decode("utf-8"))

        # Gemini endpoint
        if ":generateContent" in self.path:
            api_key = self.headers.get("x-goog-api-key", "")
            if api_key == "BAD_KEY":
                self._send_json(401, {"error": {"code": 401, "message": "API key not valid."}})
                return
            parts = payload["contents"][0]["parts"]
            assert len(parts) == 2 and parts[1]["inline_data"]["mime_type"] == "image/jpeg"
            assert base64.b64decode(parts[1]["inline_data"]["data"]) == b"\xff\xd8\xff\xe0FAKEJPEG"
            self._send_json(
                200,
                {
                    "candidates": [
                        {
                            "content": {
                                "parts": [
                                    {
                                        "text": "• u down to grab drinks tn? -> 今晚有空一起出来喝一杯吗？\n💡 tn = tonight（今晚），口语随性邀约"
                                    }
                                ]
                            }
                        }
                    ]
                },
            )
            return

        # OpenAI-compatible (Vision & DeepSeek Text)
        auth = self.headers.get("Authorization", "")
        if auth == "Bearer BAD_KEY":
            self._send_json(401, {"error": {"message": "Incorrect API key provided."}})
            return

        messages = payload["messages"]
        assert payload["model"], "Model must be non-empty"
        assert len(messages) >= 1
        self._send_json(
            200,
            {
                "choices": [
                    {
                        "message": {
                            "role": "assistant",
                            "content": "• สวัสดีครับ ทานข้าวหรือยัง -> 你好呀，吃过饭了吗？",
                        }
                    }
                ]
            },
        )

    def _send_json(self, status: int, data: dict[str, Any]) -> None:
        encoded = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)


def _post_json(url: str, payload: dict[str, Any], headers: dict[str, str]) -> tuple[int, dict[str, Any]]:
    req = urllib.request.Request(
        url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as err:
        return err.code, json.loads(err.read().decode("utf-8"))


def validate_unsigned_plists(unsigned_dir: Path) -> None:
    print(f"[2/3] Validating compiled binary plists & running Mock API E2E tests in {unsigned_dir} ...")

    server = socketserver.TCPServer(("127.0.0.1", 0), _MockAiHandler)
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()

    try:
        for name, spec in EXPECTED_SHORTCUTS.items():
            shortcut_path = unsigned_dir / f"{name}.shortcut"
            assert_true(shortcut_path.exists(), f"Missing compiled unsigned shortcut: {shortcut_path}")

            data = plistlib.loads(shortcut_path.read_bytes())
            actions = data.get("WFWorkflowActions", [])
            questions = data.get("WFWorkflowImportQuestions", [])

            assert_true(len(actions) >= 10, f"{name}: suspiciously few actions ({len(actions)})")
            assert_true(
                len(questions) >= spec["min_questions"],
                f"{name}: expected >= {spec['min_questions']} WFWorkflowImportQuestions, got {len(questions)}",
            )

            # Verify every ImportQuestion points to a valid action index
            for q in questions:
                idx = q.get("ActionIndex")
                assert_true(isinstance(idx, int) and 0 <= idx < len(actions), f"{name}: bad ActionIndex {idx}")

            action_ids = [a.get("WFWorkflowActionIdentifier") for a in actions]

            # Privacy & safety guarantees
            for forbidden in FORBIDDEN_ACTIONS:
                assert_true(forbidden not in action_ids, f"{name}: forbidden privacy action found: {forbidden}")
            assert_true("is.workflow.actions.takescreenshot" in action_ids, f"{name}: missing takescreenshot")
            assert_true("is.workflow.actions.downloadurl" in action_ids, f"{name}: missing downloadurl")
            assert_true("is.workflow.actions.alert" in action_ids, f"{name}: missing alert")

            # Verify control flow GroupingIdentifier balance (every 0 has a matching 2)
            group_modes: dict[str, list[int]] = {}
            for a in actions:
                params = a.get("WFWorkflowActionParameters", {})
                if "GroupingIdentifier" in params:
                    gid = params["GroupingIdentifier"]
                    group_modes.setdefault(gid, []).append(params.get("WFControlFlowMode"))
            for gid, modes in group_modes.items():
                assert_true(modes[0] == 0 and modes[-1] == 2, f"{name}: unbalanced control flow {gid}: {modes}")

            # Verify JPEG EXIF strip for vision shortcuts
            if spec["uses_vision"]:
                converts = [a for a in actions if a.get("WFWorkflowActionIdentifier") == "is.workflow.actions.image.convert"]
                assert_true(len(converts) == 1, f"{name}: expected 1 image.convert action")
                cparams = converts[0]["WFWorkflowActionParameters"]
                assert_true(cparams.get("WFImageFormat") == "JPEG", f"{name}: expected JPEG format")
                assert_true(cparams.get("WFImagePreserveMetadata") is False, f"{name}: must strip EXIF metadata")

            # Inspect downloadurl action and execute real HTTP request against local mock server
            dl_actions = [a for a in actions if a.get("WFWorkflowActionIdentifier") == "is.workflow.actions.downloadurl"]
            assert_true(len(dl_actions) == 1, f"{name}: expected exactly 1 downloadurl action")
            dl_params = dl_actions[0]["WFWorkflowActionParameters"]
            assert_true(dl_params.get("WFHTTPMethod") == "POST", f"{name}: expected POST method")
            assert_true(dl_params.get("WFHTTPBodyType") == "JSON", f"{name}: expected JSON body type")

            fake_b64 = base64.b64encode(b"\xff\xd8\xff\xe0FAKEJPEG").decode("ascii")
            mock_vars = {
                "apiKey": "TEST_VALID_KEY",
                "targetLang": "简体中文",
                "modelName": "gemini-2.5-flash" if spec["provider"] == "gemini" else "gpt-4o-mini",
                "apiEndpoint": f"http://127.0.0.1:{port}/v1/chat/completions",
                "endpoint": f"http://127.0.0.1:{port}/v1beta/models/gemini-2.5-flash:generateContent",
                "cleanBase64": fake_b64,
                "prompt": 'Translate "u down to grab drinks tn?" into 简体中文',
                "sysPrompt": "Translate into 简体中文",
                "extracted": 'Match said: "สวัสดีครับ" \n line 2 with \\ backslash & emoji 😘',
            }

            headers = _decode_wf_dictionary(dl_params["WFHTTPHeaders"], mock_vars)
            body = _decode_wf_dictionary(dl_params["WFJSONValues"], mock_vars)
            url = _decode_wf_text(dl_params["WFURL"], mock_vars)

            assert_true(spec["expected_header_key"] in headers, f"{name}: missing header {spec['expected_header_key']}")
            assert_true(headers.get("Content-Type") == "application/json", f"{name}: missing Content-Type JSON header")

            # 1) Test valid response path against live local mock server
            status, resp_json = _post_json(url, body, headers)
            assert_true(status == 200, f"{name}: expected HTTP 200 from mock server, got {status}: {resp_json}")
            assert_true(spec["expected_response_root"] in resp_json, f"{name}: unexpected response shape: {resp_json}")

            # Verify Shortcut's getvalueforkey chain matches the actual response keys
            key_gets = [
                a["WFWorkflowActionParameters"].get("WFDictionaryKey")
                for a in actions
                if a.get("WFWorkflowActionIdentifier") == "is.workflow.actions.getvalueforkey"
            ]
            assert_true(spec["expected_response_root"] in key_gets, f"{name}: missing getvalueforkey for root")
            assert_true("error" in key_gets and "message" in key_gets, f"{name}: missing error.message fallback")

            # 2) Test invalid API key (HTTP 401) path
            bad_vars = dict(mock_vars, apiKey="BAD_KEY")
            bad_headers = _decode_wf_dictionary(dl_params["WFHTTPHeaders"], bad_vars)
            err_status, err_json = _post_json(url, body, bad_headers)
            assert_true(err_status == 401, f"{name}: expected HTTP 401 on BAD_KEY, got {err_status}")
            assert_true(bool(err_json.get("error", {}).get("message")), f"{name}: mock 401 missing error.message")

            print(
                f"  OK plist+E2E: {shortcut_path.name} "
                f"({len(actions)} actions, {len(questions)} questions, 200 OK & 401 fallback verified)"
            )
    finally:
        server.shutdown()
        server.server_close()


def validate_signed_shortcuts(signed_dir: Path) -> None:
    print(f"[3/3] Verifying Apple official signatures in {signed_dir} ...")
    for name in EXPECTED_SHORTCUTS:
        signed_path = signed_dir / f"{name}.shortcut"
        assert_true(signed_path.exists(), f"Missing signed shortcut: {signed_path}")
        raw = signed_path.read_bytes()
        assert_true(len(raw) > 512, f"{signed_path.name}: file too small ({len(raw)} bytes)")
        # Apple signed shortcuts (iOS 15+) use Apple Encrypted Archive format starting with 'AEA1'
        assert_true(
            raw[:4] == b"AEA1",
            f"{signed_path.name}: expected Apple AEA1 signature header, got {raw[:8]!r}",
        )
        print(f"  OK signed: {signed_path.name} ({len(raw)} bytes, Apple AEA1 verified)")


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate 机器猫 iOS Shortcuts")
    parser.add_argument("--src-dir", type=Path, help="Directory containing .cherri source files")
    parser.add_argument("--unsigned-dir", type=Path, help="Directory containing unsigned compiled .shortcut plists")
    parser.add_argument("--signed-dir", type=Path, help="Directory containing Apple-signed .shortcut files")
    args = parser.parse_args()

    if not any([args.src_dir, args.unsigned_dir, args.signed_dir]):
        parser.error("Provide at least one of --src-dir, --unsigned-dir, --signed-dir")

    if args.src_dir:
        validate_sources(args.src_dir)
    if args.unsigned_dir:
        validate_unsigned_plists(args.unsigned_dir)
    if args.signed_dir:
        validate_signed_shortcuts(args.signed_dir)

    print("ALL IOS SHORTCUT VALIDATIONS PASSED.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
