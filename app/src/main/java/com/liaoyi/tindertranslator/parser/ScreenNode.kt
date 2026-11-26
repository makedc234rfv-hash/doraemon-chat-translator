package com.liaoyi.tindertranslator.parser

data class ScreenNode(
    val text: String,
    val className: String = "",
    val viewId: String = "",
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val isEditable: Boolean = false,
    val isClickable: Boolean = false
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

data class ChatMessage(
    val id: String,
    val text: String,
    val isIncoming: Boolean,
    val topY: Int
)
