package com.liaoyi.tindertranslator.parser

import com.liaoyi.tindertranslator.data.AppLanguage
import com.liaoyi.tindertranslator.data.ReplyLanguage

object LanguageDetector {

    fun needsChineseTranslation(text: String): Boolean {
        return needsTranslation(text, AppLanguage.ZH)
    }

    /**
     * Detects the likely language of an incoming message so ReplyLanguage.AUTO
     * automatically replies in the match's own language (e.g., Thai -> Thai).
     */
    fun detectReplyLanguage(
        recentIncomingTexts: List<String>,
        fallbackUiLanguage: AppLanguage
    ): ReplyLanguage {
        var thai = 0
        var kana = 0
        var hangul = 0
        var cjk = 0
        var latin = 0

        for (raw in recentIncomingTexts.takeLast(3)) {
            var i = 0
            while (i < raw.length) {
                val cp = raw.codePointAt(i)
                i += Character.charCount(cp)
                when {
                    isThai(cp) -> thai++
                    isKana(cp) -> kana++
                    isHangul(cp) -> hangul++
                    isCjkIdeograph(cp) -> cjk++
                    isAsciiLatin(cp) || isExtendedLatin(cp) -> latin++
                }
            }
        }

        return when {
            thai >= 3 -> ReplyLanguage.TH
            kana >= 2 -> ReplyLanguage.JA
            hangul >= 2 -> ReplyLanguage.KO
            cjk >= 2 && fallbackUiLanguage != AppLanguage.ZH -> ReplyLanguage.ZH
            latin >= 3 -> ReplyLanguage.EN
            fallbackUiLanguage == AppLanguage.ZH -> ReplyLanguage.TH
            fallbackUiLanguage == AppLanguage.TH -> ReplyLanguage.ZH
            else -> ReplyLanguage.EN
        }
    }

    /**
     * Returns true when text appears to be in a language OTHER than [targetLanguage].
     * Skips pure numbers, pure emojis, pure punctuation, and messages already in [targetLanguage].
     */
    fun needsTranslation(text: String, targetLanguage: AppLanguage): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 2) return false

        var cjkCount = 0
        var kanaCount = 0
        var hangulCount = 0
        var thaiCount = 0
        var asciiLatinCount = 0
        var extendedLatinCount = 0
        var otherLetterCount = 0

        var i = 0
        while (i < trimmed.length) {
            val cp = trimmed.codePointAt(i)
            i += Character.charCount(cp)

            when {
                isCjkIdeograph(cp) -> cjkCount++
                isKana(cp) -> kanaCount++
                isHangul(cp) -> hangulCount++
                isThai(cp) -> thaiCount++
                isAsciiLatin(cp) -> asciiLatinCount++
                isExtendedLatin(cp) -> extendedLatinCount++
                Character.isLetter(cp) -> otherLetterCount++
            }
        }

        val totalLetters = cjkCount + kanaCount + hangulCount + thaiCount +
            asciiLatinCount + extendedLatinCount + otherLetterCount
        if (totalLetters == 0) return false

        return when (targetLanguage) {
            AppLanguage.ZH -> {
                // Keep Japanese (contains Kana) as translatable; skip predominantly Chinese text
                if (kanaCount > 0) return true
                val nonCjk = totalLetters - cjkCount
                if (nonCjk == 0) return false
                cjkCount.toFloat() / totalLetters < 0.45f
            }
            AppLanguage.TH -> {
                val nonThai = totalLetters - thaiCount
                if (nonThai == 0) return false
                thaiCount.toFloat() / totalLetters < 0.45f
            }
            AppLanguage.EN -> {
                val nonLatin = totalLetters - asciiLatinCount - extendedLatinCount
                if (nonLatin > 0) return true
                // Accented Latin (Spanish/French/German/Vietnamese) should still be translated to English
                extendedLatinCount > 0 || trimmed.contains('¿') || trimmed.contains('¡')
            }
            AppLanguage.JA -> {
                // Japanese text typically has Kana; pure Kanji (Chinese) without Kana should be translated
                if (kanaCount == 0) return true
                (kanaCount + cjkCount).toFloat() / totalLetters < 0.45f
            }
            AppLanguage.KO -> {
                val nonHangul = totalLetters - hangulCount
                if (nonHangul == 0) return false
                hangulCount.toFloat() / totalLetters < 0.45f
            }
        }
    }

    private fun isCjkIdeograph(cp: Int): Boolean {
        return (cp in 0x4E00..0x9FFF) ||
            (cp in 0x3400..0x4DBF) ||
            (cp in 0x20000..0x2A6DF) ||
            (cp in 0xF900..0xFAFF)
    }

    private fun isKana(cp: Int): Boolean {
        return (cp in 0x3040..0x309F) || (cp in 0x30A0..0x30FF)
    }

    private fun isHangul(cp: Int): Boolean {
        return (cp in 0xAC00..0xD7AF) || (cp in 0x1100..0x11FF) || (cp in 0x3130..0x318F)
    }

    private fun isThai(cp: Int): Boolean {
        return cp in 0x0E00..0x0E7F
    }

    private fun isAsciiLatin(cp: Int): Boolean {
        return (cp in 'A'.code..'Z'.code) || (cp in 'a'.code..'z'.code)
    }

    private fun isExtendedLatin(cp: Int): Boolean {
        return Character.isLetter(cp) && ((cp in 0x00C0..0x024F) || (cp in 0x1E00..0x1EFF))
    }
}
