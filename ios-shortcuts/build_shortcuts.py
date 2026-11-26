#!/usr/bin/env python3
"""
Builds and Apple-signs the 3 机器猫 (Doraemon) iOS Shortcuts directly from Python:
  1. Doraemon-Gemini.shortcut         (Gemini Vision screenshot translator, recommended)
  2. Doraemon-OpenAI-Vision.shortcut  (OpenAI / compatible vision screenshot translator)
  3. Doraemon-Text-DeepSeek.shortcut  (DeepSeek / text-only OCR translator)

Generates Apple Shortcuts binary/XML plists conforming to Apple Shortcuts & Cherri v2.4.0
schema, validates them with validate_shortcuts.py (including live Mock API E2E tests),
and signs them for public iOS 15+ import via RoutineHub's macOS HubSign service
(yielding Apple AEA1 cryptographically signed .shortcut files).
"""

from __future__ import annotations

import argparse
import json
import plistlib
import re
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from typing import Any

OBJECT_REPLACE = "\ufffc"

COLORS = {
    "blue": 463140863,
    "purple": 3679049983,
    "teal": 431817727,
}

GLYPHS = {
    "cat": 59684,
    "chatBubble": 59414,
}

HUBSIGN_URL = "https://hubsign.routinehub.services/sign"


class OutputRef:
    """Reference to an action output UUID + name for Shortcuts magic variables."""

    def __init__(self, action_uuid: str, output_name: str) -> None:
        self.uuid = action_uuid
        self.name = output_name

    def as_attachment(self) -> dict[str, Any]:
        return {
            "WFSerializationType": "WFTextTokenAttachment",
            "Value": {
                "Type": "ActionOutput",
                "OutputUUID": self.uuid,
                "OutputName": self.name,
            },
        }

    def as_inline_value(self) -> dict[str, Any]:
        return {
            "Type": "ActionOutput",
            "OutputUUID": self.uuid,
            "OutputName": self.name,
        }


def make_text_token(template: str, refs: dict[str, OutputRef] | None = None) -> dict[str, Any]:
    """
    Build a WFTextTokenString with exact UTF-16 code-unit offsets for `{varName}` tokens.
    """
    refs = refs or {}
    pattern = re.compile(r"\{([A-Za-z_][A-Za-z0-9_]*)\}")

    out_parts: list[str] = []
    attachments: dict[str, dict[str, Any]] = {}
    utf16_offset = 0
    cursor = 0

    for match in pattern.finditer(template):
        literal = template[cursor : match.start()]
        if literal:
            out_parts.append(literal)
            utf16_offset += len(literal.encode("utf-16-le")) // 2

        var_name = match.group(1)
        if var_name not in refs:
            raise KeyError(f"Unknown template variable {{{var_name}}} in {template!r}")

        attachments[f"{{{utf16_offset}, 1}}"] = refs[var_name].as_inline_value()
        out_parts.append(OBJECT_REPLACE)
        utf16_offset += 1  # U+FFFC is 1 UTF-16 code unit
        cursor = match.end()

    tail = template[cursor:]
    if tail:
        out_parts.append(tail)

    value_dict: dict[str, Any] = {"string": "".join(out_parts)}
    if attachments:
        value_dict["attachmentsByRange"] = attachments

    return {
        "WFSerializationType": "WFTextTokenString",
        "Value": value_dict,
    }


def _dict_item(key: str | None, value: Any, refs: dict[str, OutputRef]) -> dict[str, Any]:
    item: dict[str, Any] = {}
    if key is not None:
        item["WFKey"] = make_text_token(key, refs)

    if isinstance(value, bool):
        item["WFItemType"] = 4
        item["WFValue"] = {
            "WFSerializationType": "WFNumberSubstitutableState",
            "Value": value,
        }
    elif isinstance(value, (int, float)):
        item["WFItemType"] = 3
        item["WFValue"] = make_text_token(str(value), refs)
    elif isinstance(value, str):
        item["WFItemType"] = 0
        item["WFValue"] = make_text_token(value, refs)
    elif isinstance(value, list):
        item["WFItemType"] = 2
        item["WFValue"] = {
            "WFSerializationType": "WFArrayParameterState",
            "Value": [_dict_item(None, sub, refs) for sub in value],
        }
    elif isinstance(value, dict):
        item["WFItemType"] = 1
        # Apple Shortcuts wraps nested dict values in two WFDictionaryFieldValue layers
        item["WFValue"] = {
            "WFSerializationType": "WFDictionaryFieldValue",
            "Value": {
                "WFSerializationType": "WFDictionaryFieldValue",
                "Value": {
                    "WFDictionaryFieldValueItems": [
                        _dict_item(k, v, refs) for k, v in value.items()
                    ]
                },
            },
        }
    else:
        raise TypeError(f"Unsupported dict item type: {type(value)}")
    return item


def make_wf_dictionary(mapping: dict[str, Any], refs: dict[str, OutputRef]) -> dict[str, Any]:
    return {
        "WFSerializationType": "WFDictionaryFieldValue",
        "Value": {
            "WFDictionaryFieldValueItems": [
                _dict_item(k, v, refs) for k, v in mapping.items()
            ]
        },
    }


class ShortcutBuilder:
    def __init__(self, seed_namespace: str, color: str, glyph: str) -> None:
        self._ns = uuid.uuid5(uuid.NAMESPACE_DNS, f"doraemon.tindertranslator.{seed_namespace}")
        self._counter = 0
        self.color = COLORS[color]
        self.glyph = GLYPHS[glyph]
        self.actions: list[dict[str, Any]] = []
        self.questions: list[dict[str, Any]] = []
        self.refs: dict[str, OutputRef] = {}

    def _next_uuid(self) -> str:
        self._counter += 1
        return str(uuid.uuid5(self._ns, str(self._counter))).upper()

    def _add(self, identifier: str, params: dict[str, Any], name: str | None = None) -> OutputRef:
        action_uuid = self._next_uuid()
        params_with_uuid = dict(params)
        params_with_uuid["UUID"] = action_uuid
        if name:
            params_with_uuid["CustomOutputName"] = name
        self.actions.append(
            {
                "WFWorkflowActionIdentifier": identifier,
                "WFWorkflowActionParameters": params_with_uuid,
            }
        )
        ref = OutputRef(action_uuid, name or identifier.rsplit(".", 1)[-1])
        if name:
            self.refs[name] = ref
        return ref

    def import_question(self, var_name: str, question_text: str, default_value: str) -> OutputRef:
        action_index = len(self.actions)
        ref = self._add(
            "is.workflow.actions.gettext",
            {"WFTextActionText": default_value},
            name=var_name,
        )
        self.questions.append(
            {
                "ActionIndex": action_index,
                "Category": "Parameter",
                "DefaultValue": default_value,
                "ParameterKey": "WFTextActionText",
                "Text": question_text,
            }
        )
        return ref

    def text(self, var_name: str, template: str) -> OutputRef:
        return self._add(
            "is.workflow.actions.gettext",
            {"WFTextActionText": make_text_token(template, self.refs)},
            name=var_name,
        )

    def alert(self, message_template: str, title: str) -> None:
        self._add(
            "is.workflow.actions.alert",
            {
                "WFAlertActionTitle": title,
                "WFAlertActionMessage": make_text_token(message_template, self.refs),
                "WFAlertActionCancelButtonShown": False,
            },
        )

    def take_screenshot(self, var_name: str) -> OutputRef:
        return self._add(
            "is.workflow.actions.takescreenshot",
            {
                "WFTakeScreenshotScreenshotType": "Full Screen",
                "WFTakeScreenshotMainMonitorOnly": False,
            },
            name=var_name,
        )

    def resize_longest_edge(self, var_name: str, image: OutputRef, length: str) -> OutputRef:
        return self._add(
            "is.workflow.actions.image.resize",
            {
                "WFImage": image.as_attachment(),
                "WFImageResizeKey": "Longest Edge",
                "WFImageResizeLength": length,
            },
            name=var_name,
        )

    def convert_to_jpeg(self, var_name: str, image: OutputRef, quality: float = 0.72) -> OutputRef:
        return self._add(
            "is.workflow.actions.image.convert",
            {
                "WFInput": image.as_attachment(),
                "WFImageFormat": "JPEG",
                "WFImageCompressionQuality": quality,
                "WFImagePreserveMetadata": False,
            },
            name=var_name,
        )

    def base64_encode(self, var_name: str, data: OutputRef) -> OutputRef:
        return self._add(
            "is.workflow.actions.base64encode",
            {
                "WFInput": data.as_attachment(),
                "WFEncodeMode": "Encode",
                "WFBase64LineBreakMode": "None",
            },
            name=var_name,
        )

    def replace_text(self, var_name: str, subject: OutputRef, find: str, replacement: str) -> OutputRef:
        return self._add(
            "is.workflow.actions.text.replace",
            {
                "WFInput": make_text_token("{" + subject.name + "}", self.refs),
                "WFReplaceTextFind": find,
                "WFReplaceTextReplace": replacement,
                "WFReplaceTextCaseSensitive": True,
                "WFReplaceTextRegularExpression": False,
            },
            name=var_name,
        )

    def extract_text_from_image(self, var_name: str, image: OutputRef) -> OutputRef:
        return self._add(
            "is.workflow.actions.extracttextfromimage",
            {"WFImage": image.as_attachment()},
            name=var_name,
        )

    def json_post(
        self,
        var_name: str,
        url_template: str,
        body: dict[str, Any],
        headers: dict[str, Any],
    ) -> OutputRef:
        return self._add(
            "is.workflow.actions.downloadurl",
            {
                "WFURL": make_text_token(url_template, self.refs),
                "WFHTTPMethod": "POST",
                "WFHTTPBodyType": "JSON",
                "WFJSONValues": make_wf_dictionary(body, self.refs),
                "WFHTTPHeaders": make_wf_dictionary(headers, self.refs),
            },
            name=var_name,
        )

    def get_dictionary(self, var_name: str, source: OutputRef) -> OutputRef:
        return self._add(
            "is.workflow.actions.detect.dictionary",
            {"WFInput": source.as_attachment()},
            name=var_name,
        )

    def get_value(self, var_name: str, dictionary: OutputRef, key: str) -> OutputRef:
        return self._add(
            "is.workflow.actions.getvalueforkey",
            {
                "WFInput": dictionary.as_attachment(),
                "WFDictionaryKey": key,
            },
            name=var_name,
        )

    def get_first_item(self, var_name: str, lst: OutputRef) -> OutputRef:
        return self._add(
            "is.workflow.actions.getitemfromlist",
            {
                "WFInput": lst.as_attachment(),
                "WFItemSpecifier": "First Item",
            },
            name=var_name,
        )

    def if_has_value(self, target: OutputRef) -> str:
        group_id = self._next_uuid()
        self.actions.append(
            {
                "WFWorkflowActionIdentifier": "is.workflow.actions.conditional",
                "WFWorkflowActionParameters": {
                    "GroupingIdentifier": group_id,
                    "WFControlFlowMode": 0,
                    "WFCondition": 100,  # Has Any Value
                    "WFInput": {
                        "Type": "Variable",
                        "Variable": target.as_attachment(),
                    },
                },
            }
        )
        return group_id

    def if_empty(self, target: OutputRef) -> str:
        group_id = self._next_uuid()
        self.actions.append(
            {
                "WFWorkflowActionIdentifier": "is.workflow.actions.conditional",
                "WFWorkflowActionParameters": {
                    "GroupingIdentifier": group_id,
                    "WFControlFlowMode": 0,
                    "WFCondition": 101,  # Does Not Have Any Value
                    "WFInput": {
                        "Type": "Variable",
                        "Variable": target.as_attachment(),
                    },
                },
            }
        )
        return group_id

    def otherwise(self, group_id: str) -> None:
        self.actions.append(
            {
                "WFWorkflowActionIdentifier": "is.workflow.actions.conditional",
                "WFWorkflowActionParameters": {
                    "GroupingIdentifier": group_id,
                    "WFControlFlowMode": 1,
                },
            }
        )

    def end_if(self, group_id: str) -> None:
        self.actions.append(
            {
                "WFWorkflowActionIdentifier": "is.workflow.actions.conditional",
                "WFWorkflowActionParameters": {
                    "GroupingIdentifier": group_id,
                    "WFControlFlowMode": 2,
                },
            }
        )

    def build_plist_dict(self) -> dict[str, Any]:
        return {
            "WFWorkflowClientVersion": "2106.0.3",
            "WFWorkflowMinimumClientVersion": 900,
            "WFWorkflowMinimumClientVersionString": "900",
            "WFWorkflowIcon": {
                "WFWorkflowIconStartColor": self.color,
                "WFWorkflowIconGlyphNumber": self.glyph,
            },
            "WFWorkflowImportQuestions": self.questions,
            "WFWorkflowInputContentItemClasses": [
                "WFStringContentItem",
                "WFImageContentItem",
            ],
            "WFWorkflowTypes": [],
            "WFWorkflowHasOutputFallback": False,
            "WFWorkflowHasShortcutInputVariables": False,
            "WFWorkflowActions": self.actions,
        }


VISION_PROMPT = (
    "You are a natural, fluent dating-chat translator for Tinder screenshots. "
    "Look at this screenshot and translate ONLY the match's latest 1 to 4 incoming foreign messages "
    "(left-side bubbles; ignore status bar, timestamps, UI buttons, and the user's own right-side messages) "
    "into natural conversational {targetLang}. Format each message clearly as:\n"
    "• Original -> Translation\n"
    "If there is slang, abbreviation, or flirting nuance, add a short 1-line note starting with 💡 in {targetLang}. "
    "If there are no foreign incoming messages visible, say so briefly in {targetLang}. "
    "Plain text only, concise for a mobile popup."
)


def build_gemini_shortcut() -> dict[str, Any]:
    b = ShortcutBuilder("Doraemon-Gemini", color="blue", glyph="cat")
    api_key = b.import_question("apiKey", "请粘贴你的 Gemini API Key / วาง Gemini API Key", "")
    b.import_question(
        "targetLang",
        "翻译目标语言 (简体中文 / ภาษาไทย / English / 日本語 / 한국어)",
        "简体中文",
    )
    b.import_question("modelName", "模型名称 / Model Name (默认 gemini-2.5-flash)", "gemini-2.5-flash")

    g_key = b.if_empty(api_key)
    b.alert(
        "请先在「快捷指令」里长按本指令 → 点「编辑」，填入你的 Gemini API Key。\n\n"
        "กรุณาแก้ไขคำสั่งลัดแล้ววาง Gemini API Key ก่อนใช้งาน",
        "🐱 机器猫提示",
    )
    b.otherwise(g_key)

    shot = b.take_screenshot("screenshot")
    resized = b.resize_longest_edge("resized", shot, "1080")
    jpeg = b.convert_to_jpeg("jpeg", resized, quality=0.72)
    raw_b64 = b.base64_encode("rawBase64", jpeg)
    no_lf = b.replace_text("b64NoLF", raw_b64, "\n", "")
    b.replace_text("cleanBase64", no_lf, "\r", "")
    b.text("prompt", VISION_PROMPT)
    b.text("endpoint", "https://generativelanguage.googleapis.com/v1beta/models/{modelName}:generateContent")

    resp = b.json_post(
        "response",
        "{endpoint}",
        body={
            "contents": [
                {
                    "role": "user",
                    "parts": [
                        {"text": "{prompt}"},
                        {
                            "inline_data": {
                                "mime_type": "image/jpeg",
                                "data": "{cleanBase64}",
                            }
                        },
                    ],
                }
            ],
            "generationConfig": {"temperature": 0.3},
        },
        headers={
            "x-goog-api-key": "{apiKey}",
            "Content-Type": "application/json",
        },
    )

    resp_dict = b.get_dictionary("respDict", resp)
    candidates = b.get_value("candidates", resp_dict, "candidates")
    g_cand = b.if_has_value(candidates)
    first_cand_raw = b.get_first_item("firstCandRaw", candidates)
    first_cand = b.get_dictionary("firstCand", first_cand_raw)
    content_raw = b.get_value("contentRaw", first_cand, "content")
    content = b.get_dictionary("content", content_raw)
    parts = b.get_value("parts", content, "parts")
    first_part_raw = b.get_first_item("firstPartRaw", parts)
    first_part = b.get_dictionary("firstPart", first_part_raw)
    translated = b.get_value("translated", first_part, "text")

    g_tr = b.if_has_value(translated)
    b.alert("{translated}", "🐱 机器猫翻译")
    b.otherwise(g_tr)
    b.alert("未获取到翻译内容，请稍后再试。\n\n{response}", "🐱 机器猫提示")
    b.end_if(g_tr)

    b.otherwise(g_cand)
    err_raw = b.get_value("errRaw", resp_dict, "error")
    g_err = b.if_has_value(err_raw)
    err_dict = b.get_dictionary("errDict", err_raw)
    b.get_value("errMsg", err_dict, "message")
    b.alert("翻译失败 / Translation failed:\n{errMsg}", "🐱 机器猫提示")
    b.otherwise(g_err)
    b.alert("请求失败，请检查网络或 Gemini API Key 是否正确。\n\n{response}", "🐱 机器猫提示")
    b.end_if(g_err)
    b.end_if(g_cand)
    b.end_if(g_key)

    return b.build_plist_dict()


def build_openai_vision_shortcut() -> dict[str, Any]:
    b = ShortcutBuilder("Doraemon-OpenAI-Vision", color="purple", glyph="cat")
    api_key = b.import_question("apiKey", "请粘贴 OpenAI 或兼容接口 API Key / วาง API Key", "")
    b.import_question(
        "targetLang",
        "翻译目标语言 (简体中文 / ภาษาไทย / English / 日本語 / 한국어)",
        "简体中文",
    )
    b.import_question(
        "apiEndpoint",
        "接口地址 / API Endpoint",
        "https://api.openai.com/v1/chat/completions",
    )
    b.import_question("modelName", "视觉模型名 / Vision Model", "gpt-4o-mini")

    g_key = b.if_empty(api_key)
    b.alert(
        "请先在「快捷指令」里长按本指令 → 点「编辑」，填入你的 API Key。\n\n"
        "กรุณาแก้ไขคำสั่งลัดแล้ววาง API Key ก่อนใช้งาน",
        "🐱 机器猫提示",
    )
    b.otherwise(g_key)

    shot = b.take_screenshot("screenshot")
    resized = b.resize_longest_edge("resized", shot, "1080")
    jpeg = b.convert_to_jpeg("jpeg", resized, quality=0.72)
    raw_b64 = b.base64_encode("rawBase64", jpeg)
    no_lf = b.replace_text("b64NoLF", raw_b64, "\n", "")
    b.replace_text("cleanBase64", no_lf, "\r", "")
    b.text("prompt", VISION_PROMPT)

    resp = b.json_post(
        "response",
        "{apiEndpoint}",
        body={
            "model": "{modelName}",
            "temperature": 0.3,
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": "{prompt}"},
                        {
                            "type": "image_url",
                            "image_url": {"url": "data:image/jpeg;base64,{cleanBase64}"},
                        },
                    ],
                }
            ],
        },
        headers={
            "Authorization": "Bearer {apiKey}",
            "Content-Type": "application/json",
        },
    )

    resp_dict = b.get_dictionary("respDict", resp)
    choices = b.get_value("choices", resp_dict, "choices")
    g_ch = b.if_has_value(choices)
    first_ch_raw = b.get_first_item("firstChoiceRaw", choices)
    first_ch = b.get_dictionary("firstChoice", first_ch_raw)
    msg_raw = b.get_value("msgRaw", first_ch, "message")
    msg = b.get_dictionary("msg", msg_raw)
    translated = b.get_value("translated", msg, "content")

    g_tr = b.if_has_value(translated)
    b.alert("{translated}", "🐱 机器猫翻译")
    b.otherwise(g_tr)
    b.alert("未获取到翻译内容，请稍后再试。\n\n{response}", "🐱 机器猫提示")
    b.end_if(g_tr)

    b.otherwise(g_ch)
    err_raw = b.get_value("errRaw", resp_dict, "error")
    g_err = b.if_has_value(err_raw)
    err_dict = b.get_dictionary("errDict", err_raw)
    b.get_value("errMsg", err_dict, "message")
    b.alert("翻译失败 / Translation failed:\n{errMsg}", "🐱 机器猫提示")
    b.otherwise(g_err)
    b.alert("请求失败，请检查网络、接口地址或 API Key。\n\n{response}", "🐱 机器猫提示")
    b.end_if(g_err)
    b.end_if(g_ch)
    b.end_if(g_key)

    return b.build_plist_dict()


def build_deepseek_text_shortcut() -> dict[str, Any]:
    b = ShortcutBuilder("Doraemon-Text-DeepSeek", color="teal", glyph="chatBubble")
    api_key = b.import_question("apiKey", "请粘贴 DeepSeek 或兼容接口 API Key / วาง API Key", "")
    b.import_question(
        "targetLang",
        "翻译目标语言 (简体中文 / ภาษาไทย / English / 日本語 / 한국어)",
        "简体中文",
    )
    b.import_question(
        "apiEndpoint",
        "接口地址 / API Endpoint",
        "https://api.deepseek.com/chat/completions",
    )
    b.import_question("modelName", "模型名称 / Model Name", "deepseek-chat")

    g_key = b.if_empty(api_key)
    b.alert(
        "请先在「快捷指令」里长按本指令 → 点「编辑」，填入你的 API Key。\n\n"
        "กรุณาแก้ไขคำสั่งลัดแล้ววาง API Key ก่อนใช้งาน",
        "🐱 机器猫提示",
    )
    b.otherwise(g_key)

    shot = b.take_screenshot("screenshot")
    extracted = b.extract_text_from_image("extracted", shot)
    g_ocr = b.if_empty(extracted)
    b.alert(
        "未在屏幕上识别到文字。若对方消息为泰语且当前系统版本无法离线识别，请改用「机器猫-截屏翻译(Gemini)」。",
        "🐱 机器猫提示",
    )
    b.otherwise(g_ocr)

    b.text(
        "sysPrompt",
        "You are a natural, fluent dating-chat translator for Tinder. "
        "The user provides OCR text from a Tinder chat screen. Ignore status bar time, battery, app headers, "
        "input placeholders, and already-in-target-language lines. Translate the latest 1 to 4 foreign chat messages "
        "into natural conversational {targetLang}. Format each as:\n"
        "• Original -> Translation\n"
        "If there is slang, abbreviation, or flirting nuance, add a short 1-line note starting with 💡 in {targetLang}. "
        "Plain text only.",
    )

    resp = b.json_post(
        "response",
        "{apiEndpoint}",
        body={
            "model": "{modelName}",
            "temperature": 0.3,
            "messages": [
                {"role": "system", "content": "{sysPrompt}"},
                {"role": "user", "content": "{extracted}"},
            ],
        },
        headers={
            "Authorization": "Bearer {apiKey}",
            "Content-Type": "application/json",
        },
    )

    resp_dict = b.get_dictionary("respDict", resp)
    choices = b.get_value("choices", resp_dict, "choices")
    g_ch = b.if_has_value(choices)
    first_ch_raw = b.get_first_item("firstChoiceRaw", choices)
    first_ch = b.get_dictionary("firstChoice", first_ch_raw)
    msg_raw = b.get_value("msgRaw", first_ch, "message")
    msg = b.get_dictionary("msg", msg_raw)
    translated = b.get_value("translated", msg, "content")

    g_tr = b.if_has_value(translated)
    b.alert("{translated}", "🐱 机器猫翻译")
    b.otherwise(g_tr)
    b.alert("未获取到翻译内容，请稍后再试。\n\n{response}", "🐱 机器猫提示")
    b.end_if(g_tr)

    b.otherwise(g_ch)
    err_raw = b.get_value("errRaw", resp_dict, "error")
    g_err = b.if_has_value(err_raw)
    err_dict = b.get_dictionary("errDict", err_raw)
    b.get_value("errMsg", err_dict, "message")
    b.alert("翻译失败 / Translation failed:\n{errMsg}", "🐱 机器猫提示")
    b.otherwise(g_err)
    b.alert("请求失败，请检查网络、接口地址或 API Key。\n\n{response}", "🐱 机器猫提示")
    b.end_if(g_err)
    b.end_if(g_ch)
    b.end_if(g_ocr)
    b.end_if(g_key)

    return b.build_plist_dict()


BUILDERS = {
    "Doraemon-Gemini": build_gemini_shortcut,
    "Doraemon-OpenAI-Vision": build_openai_vision_shortcut,
    "Doraemon-Text-DeepSeek": build_deepseek_text_shortcut,
}


def sign_via_hubsign(shortcut_name: str, plist_dict: dict[str, Any]) -> bytes:
    xml_bytes = plistlib.dumps(plist_dict, fmt=plistlib.FMT_XML)
    payload = json.dumps(
        {
            "shortcutName": shortcut_name,
            "shortcut": xml_bytes.decode("utf-8"),
        }
    ).encode("utf-8")

    req = urllib.request.Request(
        HUBSIGN_URL,
        data=payload,
        headers={
            "Content-Type": "application/json",
            "User-Agent": "cherri/v2.4.0",
        },
        method="POST",
    )
    for attempt in range(1, 4):
        try:
            with urllib.request.urlopen(req, timeout=45) as resp:
                body = resp.read()
                if resp.status == 200 and body[:4] == b"AEA1":
                    return body
                raise RuntimeError(
                    f"Unexpected HubSign response: status={resp.status}, header={body[:8]!r}"
                )
        except (urllib.error.URLError, RuntimeError) as exc:
            if attempt == 3:
                raise
            print(f"  HubSign retry {attempt}/3 after error: {exc}")
            time.sleep(3 * attempt)
    raise RuntimeError("HubSign failed after retries")


def main() -> int:
    parser = argparse.ArgumentParser(description="Build & Apple-sign 机器猫 iOS Shortcuts")
    parser.add_argument(
        "--unsigned-dir",
        type=Path,
        default=Path(__file__).parent / "build" / "unsigned",
    )
    parser.add_argument(
        "--signed-dir",
        type=Path,
        default=Path(__file__).parent / "dist",
    )
    parser.add_argument("--skip-sign", action="store_true", help="Only build unsigned binary plists")
    args = parser.parse_args()

    args.unsigned_dir.mkdir(parents=True, exist_ok=True)
    if not args.skip_sign:
        args.signed_dir.mkdir(parents=True, exist_ok=True)

    for name, builder_fn in BUILDERS.items():
        plist_dict = builder_fn()
        unsigned_path = args.unsigned_dir / f"{name}.shortcut"
        unsigned_bytes = plistlib.dumps(plist_dict, fmt=plistlib.FMT_BINARY)
        unsigned_path.write_bytes(unsigned_bytes)
        print(f"Built unsigned binary plist: {unsigned_path} ({len(unsigned_bytes)} bytes)")

        if not args.skip_sign:
            print(f"Signing {name} via Apple macOS HubSign service...")
            signed_bytes = sign_via_hubsign(name, plist_dict)
            signed_path = args.signed_dir / f"{name}.shortcut"
            signed_path.write_bytes(signed_bytes)
            print(f"Signed Apple AEA1 shortcut: {signed_path} ({len(signed_bytes)} bytes)")

    return 0


if __name__ == "__main__":
    sys.exit(main())
