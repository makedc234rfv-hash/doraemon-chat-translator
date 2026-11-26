package com.liaoyi.tindertranslator.parser

import com.liaoyi.tindertranslator.data.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageParserTest {

    private val screenW = 1080
    private val screenH = 2400

    @Test
    fun `detects chat screen vs swipe deck and matches list`() {
        val swipeDeckNodes = listOf(
            ScreenNode(text = "Emma 25", left = 40, top = 1600, right = 450, bottom = 1690),
            ScreenNode(text = "2 miles away", left = 40, top = 1710, right = 380, bottom = 1770),
            ScreenNode(text = "Love traveling and matcha", left = 40, top = 1800, right = 880, bottom = 1870)
        )
        assertFalse(ChatMessageParser.isChatScreen(swipeDeckNodes, screenW, screenH))

        val matchesListNodes = listOf(
            ScreenNode(text = "Search matches", className = "android.widget.EditText", left = 40, top = 210, right = 1040, bottom = 330, isEditable = true),
            ScreenNode(text = "Emma", left = 200, top = 500, right = 400, bottom = 560)
        )
        assertFalse(ChatMessageParser.isChatScreen(matchesListNodes, screenW, screenH))

        val chatNodes = listOf(
            ScreenNode(text = "Type a message", className = "android.widget.EditText", left = 40, top = 2120, right = 880, bottom = 2260, isEditable = true)
        )
        assertTrue(ChatMessageParser.isChatScreen(chatNodes, screenW, screenH))

        val chatWithKeyboardNodes = listOf(
            ScreenNode(text = "Type a message", className = "android.widget.EditText", left = 40, top = 1280, right = 880, bottom = 1400, isEditable = true)
        )
        assertTrue(ChatMessageParser.isChatScreen(chatWithKeyboardNodes, screenW, screenH))
    }

    @Test
    fun `extracts incoming and outgoing messages including top message near header`() {
        val nodes = listOf(
            // Header
            ScreenNode(text = "Emma", viewId = "com.tinder:id/match_name", left = 180, top = 110, right = 340, bottom = 170),
            // Toolbar subtitle
            ScreenNode(text = "Active 2h ago", viewId = "com.tinder:id/chat_toolbar_subtitle", left = 180, top = 210, right = 420, bottom = 250),
            // First incoming message right near top of viewport (reproduces Pixel screenshot case)
            ScreenNode(text = "Hey! Your dog in that second pic is literally stealing the show haha", left = 45, top = 235, right = 840, bottom = 365),
            // Duplicate wrapper text node at almost same Y
            ScreenNode(text = "Hey! Your dog in that second pic is literally stealing the show haha", left = 42, top = 240, right = 842, bottom = 368),
            // Centered match banner
            ScreenNode(text = "You matched with Emma on 11/25", left = 240, top = 180, right = 840, bottom = 220),
            // Outgoing message (flush right)
            ScreenNode(text = "Thanks! Where are you from?", left = 360, top = 600, right = 1035, bottom = 680),
            // Delivery status
            ScreenNode(text = "Sent", left = 950, top = 690, right = 1035, bottom = 730),
            // Incoming Thai message
            ScreenNode(text = "สวัสดีค่ะ วันศุกร์นี้ว่างไปกินข้าวไหมคะ?", left = 120, top = 770, right = 860, bottom = 870),
            // Pure emoji message
            ScreenNode(text = "😂😂🔥", left = 150, top = 950, right = 320, bottom = 1020),
            // Bottom composer + buttons
            ScreenNode(text = "GIF", className = "android.widget.Button", left = 30, top = 2130, right = 130, bottom = 2230),
            ScreenNode(text = "Type a message", className = "android.widget.EditText", left = 150, top = 2120, right = 860, bottom = 2250, isEditable = true),
            ScreenNode(text = "Send", className = "android.widget.Button", left = 880, top = 2130, right = 1040, bottom = 2230)
        )

        val messages = ChatMessageParser.extractMessages(nodes, screenW, screenH)
        assertEquals(3, messages.size)

        assertEquals("Hey! Your dog in that second pic is literally stealing the show haha", messages[0].text)
        assertTrue(messages[0].isIncoming)

        assertEquals("Thanks! Where are you from?", messages[1].text)
        assertFalse(messages[1].isIncoming)

        assertEquals("สวัสดีค่ะ วันศุกร์นี้ว่างไปกินข้าวไหมคะ?", messages[2].text)
        assertTrue(messages[2].isIncoming)
    }

    @Test
    fun `language detector handles multi-target languages properly`() {
        // Target = Chinese
        assertTrue(LanguageDetector.needsTranslation("u down to grab drinks tn?", AppLanguage.ZH))
        assertTrue(LanguageDetector.needsTranslation("สวัสดีค่ะ สบายดีไหม", AppLanguage.ZH))
        assertTrue(LanguageDetector.needsTranslation("今週末ひまですか？", AppLanguage.ZH))
        assertTrue(LanguageDetector.needsTranslation("오늘 저녁에 시간 있어?", AppLanguage.ZH))
        assertFalse(LanguageDetector.needsTranslation("你好，很高兴认识你", AppLanguage.ZH))
        assertFalse(LanguageDetector.needsTranslation("明天晚上一起吃 sushi 吗", AppLanguage.ZH))

        // Target = Thai
        assertFalse(LanguageDetector.needsTranslation("สวัสดีค่ะ วันนี้เป็นยังไงบ้าง", AppLanguage.TH))
        assertTrue(LanguageDetector.needsTranslation("你好呀，周末有空一起喝咖啡吗？", AppLanguage.TH))
        assertTrue(LanguageDetector.needsTranslation("u down to grab drinks tn?", AppLanguage.TH))

        // Target = English
        assertFalse(LanguageDetector.needsTranslation("Hey, how was your weekend?", AppLanguage.EN))
        assertTrue(LanguageDetector.needsTranslation("你好呀，周末有空吗？", AppLanguage.EN))
        assertTrue(LanguageDetector.needsTranslation("สวัสดีค่ะ สบายดีไหมคะ?", AppLanguage.EN))
        assertTrue(LanguageDetector.needsTranslation("Hola, ¿qué tal?", AppLanguage.EN))

        // Noise
        assertFalse(LanguageDetector.needsTranslation("123456", AppLanguage.ZH))
        assertFalse(LanguageDetector.needsTranslation("😂🔥❤️", AppLanguage.TH))
        assertFalse(LanguageDetector.needsTranslation("a", AppLanguage.ZH))
    }
}
