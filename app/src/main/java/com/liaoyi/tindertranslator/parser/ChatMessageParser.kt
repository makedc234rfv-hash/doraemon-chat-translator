package com.liaoyi.tindertranslator.parser

import kotlin.math.abs

object ChatMessageParser {

    private val IGNORED_EXACT_LABELS = setOf(
        "send", "gif", "gifs", "type a message", "type a message...", "say something nice",
        "say something nice...", "message", "sticker", "stickers", "music", "bitmoji",
        "sent", "read", "delivered", "sending", "sending...", "seen", "liked", "like",
        "today", "yesterday", "just now", "now", "active now", "recently active",
        "safety", "safety center", "report", "unmatch", "block", "back", "more",
        "video", "video call", "call", "camera", "gallery", "photo", "boost",
        "super like", "rewind", "nope", "contact card", "share my date",
        "发送", "输入消息", "输入消息...", "已读", "已发送", "刚刚", "今天", "昨天", "返回", "更多",
        "举报", "解除配对", "安全中心"
    )

    private val TIME_OR_DATE_REGEX = Regex(
        pattern = "^(" +
            "\\d{1,2}:\\d{2}(\\s?[ap]\\.?m\\.?)?" +
            "|\\d{1,2}[/-]\\d{1,2}([/-]\\d{2,4})?" +
            "|(mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)(day)?(\\s+\\d{1,2}:\\d{2}(\\s?[ap]m)?)?" +
            "|(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\s+\\d{1,2}(,?\\s+\\d{4})?(\\s+\\d{1,2}:\\d{2}(\\s?[ap]m)?)?" +
            "|(today|yesterday)\\s+\\d{1,2}:\\d{2}(\\s?[ap]m)?" +
            "|\\d+\\s?(m|min|mins|h|hr|hrs|d|w)\\s?(ago)?" +
            "|\\d+\\s?(分钟前|小时前|天前)" +
            ")$",
        option = RegexOption.IGNORE_CASE
    )

    private val SYSTEM_BANNER_PREFIXES = listOf(
        "you matched with ",
        "matched with ",
        "you and ",
        "keep it respectful",
        "verify your photo",
        "verified",
        "protect your privacy",
        "never send money",
        "你与",
        "你们在"
    )

    private val CHAT_INPUT_VIEW_ID_HINTS = listOf(
        "chat_input", "message_input", "compose", "input_message", "chat_edit",
        "msg_input", "chat_text", "input_box", "send_button", "chat_send"
    )

    private val CHAT_PLACEHOLDER_HINTS = listOf(
        "type a message", "send a message", "write a message", "say something nice",
        "message...", "输入消息", "发消息", "发条消息", "说点什么", "写点什么",
        "พิมพ์ข้อความ", "ส่งข้อความ"
    )

    private val CHAT_ACTION_HINTS = setOf("send", "gif", "gifs", "发送", "ส่ง")

    private val HEADER_VIEW_ID_HINTS = listOf(
        "toolbar", "action_bar", "header", "match_name", "chat_title", "mock_header", "btnback", "btnsimulate"
    )

    /**
     * Checks whether the current Tinder screen is a chat conversation screen
     * (and NOT the swipe deck, profile card, or top-search matches list).
     */
    fun isChatScreen(nodes: List<ScreenNode>, screenWidth: Int, screenHeight: Int): Boolean {
        if (nodes.isEmpty() || screenWidth <= 0 || screenHeight <= 0) return false
        return findComposerTopY(nodes, screenHeight) != null
    }

    /**
     * Extracts chat messages visible on screen, ordered top-to-bottom.
     * When [allowFallbackViewport] is true (manual user tap on bubble), extracts visible
     * foreign text even if the chat input box was temporarily not found.
     */
    fun extractMessages(
        nodes: List<ScreenNode>,
        screenWidth: Int,
        screenHeight: Int,
        maxMessages: Int = 8,
        allowFallbackViewport: Boolean = false
    ): List<ChatMessage> {
        if (nodes.isEmpty() || screenWidth <= 0 || screenHeight <= 0) return emptyList()

        val composerTop = findComposerTopY(nodes, screenHeight)
            ?: if (allowFallbackViewport) (screenHeight * 0.88f).toInt() else return emptyList()
        val headerBottom = (screenHeight * 0.085f).toInt()
        val maxMessageBottom = composerTop + 6

        val candidates = mutableListOf<Candidate>()
        for (node in nodes) {
            if (node.isEditable) continue
            if (node.width <= 0 || node.height <= 0) continue
            if (node.bottom <= headerBottom || node.bottom > maxMessageBottom) continue
            // Skip oversized container nodes that span almost the whole chat viewport
            if (node.height > screenHeight * 0.65f) continue
            val idLower = node.viewId.lowercase()
            if (HEADER_VIEW_ID_HINTS.any { idLower.contains(it) }) continue

            val cleaned = normalizeText(node.text)
            if (cleaned.isEmpty()) continue
            if (isUiNoise(cleaned, node)) continue

            val leftMargin = node.left.coerceAtLeast(0)
            val rightMargin = (screenWidth - node.right).coerceAtLeast(0)

            // Filter centered system notices / date separators
            if (isCenteredSystemNotice(leftMargin, rightMargin, screenWidth, node.width)) {
                continue
            }

            // Classify outgoing (me, flush right) vs incoming (match, left-aligned)
            val isOutgoing = rightMargin <= screenWidth * 0.085f &&
                leftMargin > screenWidth * 0.14f &&
                leftMargin > rightMargin * 1.4f

            candidates.add(
                Candidate(
                    text = cleaned,
                    isIncoming = !isOutgoing,
                    topY = node.top,
                    bottomY = node.bottom,
                    leftX = node.left
                )
            )
        }

        if (candidates.isEmpty()) return emptyList()

        // Sort top-to-bottom, then left-to-right
        candidates.sortWith(compareBy<Candidate> { it.topY }.thenBy { it.leftX })

        // Deduplicate identical text at nearly overlapping Y positions
        val deduped = mutableListOf<Candidate>()
        for (c in candidates) {
            val duplicate = deduped.any { existing ->
                existing.text == c.text && abs(existing.topY - c.topY) < 28
            }
            if (!duplicate) {
                deduped.add(c)
            }
        }

        val sliced = if (deduped.size > maxMessages) {
            deduped.takeLast(maxMessages)
        } else {
            deduped
        }

        return sliced.map { c ->
            ChatMessage(
                id = buildMessageId(c.text, c.isIncoming),
                text = c.text,
                isIncoming = c.isIncoming,
                topY = c.topY
            )
        }
    }

    internal fun findComposerTopY(nodes: List<ScreenNode>, screenHeight: Int): Int? {
        val minComposerTop = (screenHeight * 0.22f).toInt()
        val bottomActionTop = (screenHeight * 0.45f).toInt()
        var bestTop: Int? = null
        var actionBarTop: Int? = null

        for (node in nodes) {
            if (node.height <= 0) continue
            val looksEditable = node.isEditable || node.className.endsWith("EditText")
            val viewIdLower = node.viewId.lowercase()
            val textLower = node.text.trim().lowercase()
            val matchesHint = CHAT_INPUT_VIEW_ID_HINTS.any { viewIdLower.contains(it) } ||
                CHAT_PLACEHOLDER_HINTS.any { textLower == it || textLower.startsWith(it) }

            if ((looksEditable || matchesHint) && node.top >= minComposerTop) {
                if (bestTop == null || node.top < bestTop) {
                    bestTop = node.top
                }
            } else if (textLower in CHAT_ACTION_HINTS && node.top >= bottomActionTop) {
                if (actionBarTop == null || node.top < actionBarTop) {
                    actionBarTop = node.top
                }
            }
        }
        return bestTop ?: actionBarTop
    }

    private fun isUiNoise(text: String, node: ScreenNode): Boolean {
        val lower = text.lowercase()
        if (lower in IGNORED_EXACT_LABELS) return true
        if (TIME_OR_DATE_REGEX.matches(lower)) return true
        if (SYSTEM_BANNER_PREFIXES.any { lower.startsWith(it) }) return true

        // Skip buttons with tiny 1-word action labels inside known button classes
        if (node.className.endsWith("Button") && text.length <= 14 && !text.contains(' ')) {
            return true
        }

        // Must contain at least one letter (skip pure numbers, emojis, symbols)
        val hasLetter = text.any { Character.isLetter(it) }
        if (!hasLetter) return true

        return false
    }

    private fun isCenteredSystemNotice(
        leftMargin: Int,
        rightMargin: Int,
        screenWidth: Int,
        nodeWidth: Int
    ): Boolean {
        if (nodeWidth <= 0) return true
        // Narrow centered pills/headers like "Yesterday", "You matched with Emma"
        val marginDiff = abs(leftMargin - rightMargin)
        return leftMargin > screenWidth * 0.14f &&
            rightMargin > screenWidth * 0.14f &&
            marginDiff < screenWidth * 0.06f
    }

    private fun normalizeText(raw: String): String {
        return raw.trim().replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n")
    }

    fun buildMessageId(text: String, isIncoming: Boolean): String {
        val normalized = text.trim().lowercase().replace(Regex("\\s+"), " ")
        return "${if (isIncoming) "IN" else "OUT"}:${normalized.hashCode()}:${normalized.length}"
    }

    private data class Candidate(
        val text: String,
        val isIncoming: Boolean,
        val topY: Int,
        val bottomY: Int,
        val leftX: Int
    )
}
