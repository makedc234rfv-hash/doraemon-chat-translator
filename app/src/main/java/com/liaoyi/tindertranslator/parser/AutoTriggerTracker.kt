package com.liaoyi.tindertranslator.parser

import com.liaoyi.tindertranslator.data.AppLanguage

data class TriggerDecision(
    val shouldAutoPopup: Boolean,
    val displayMessages: List<ChatMessage>,
    val newIncomingCount: Int
)

/**
 * Tracks seen incoming foreign messages so that:
 * 1. Opening a chat with unseen foreign messages or receiving a brand-new foreign message triggers auto-popup.
 * 2. Collapsing the card stays collapsed while typing/reading old messages until a genuinely NEW message arrives.
 */
class AutoTriggerTracker {

    private val seenIncomingIds = LinkedHashSet<String>()
    var isCollapsedByUser: Boolean = false
        private set

    fun markCollapsedByUser() {
        isCollapsedByUser = true
    }

    fun markExpandedByUser() {
        isCollapsedByUser = false
    }

    fun resetSession() {
        seenIncomingIds.clear()
        isCollapsedByUser = false
    }

    fun evaluate(
        allMessages: List<ChatMessage>,
        showMyMessages: Boolean,
        autoPopupEnabled: Boolean,
        maxDisplayCount: Int,
        targetLanguage: AppLanguage = AppLanguage.ZH
    ): TriggerDecision {
        val foreignIncoming = allMessages.filter {
            it.isIncoming && LanguageDetector.needsTranslation(it.text, targetLanguage)
        }

        val displayCandidates = if (showMyMessages) {
            allMessages.filter { LanguageDetector.needsTranslation(it.text, targetLanguage) }
        } else {
            foreignIncoming
        }

        val displayMessages = if (displayCandidates.size > maxDisplayCount) {
            displayCandidates.takeLast(maxDisplayCount)
        } else {
            displayCandidates
        }

        val newIncoming = foreignIncoming.filter { it.id !in seenIncomingIds }
        for (msg in newIncoming) {
            seenIncomingIds.add(msg.id)
        }
        while (seenIncomingIds.size > 300) {
            val first = seenIncomingIds.firstOrNull() ?: break
            seenIncomingIds.remove(first)
        }

        val shouldAutoPopup = if (autoPopupEnabled && newIncoming.isNotEmpty()) {
            isCollapsedByUser = false
            true
        } else {
            false
        }

        return TriggerDecision(
            shouldAutoPopup = shouldAutoPopup,
            displayMessages = displayMessages,
            newIncomingCount = newIncoming.size
        )
    }
}
