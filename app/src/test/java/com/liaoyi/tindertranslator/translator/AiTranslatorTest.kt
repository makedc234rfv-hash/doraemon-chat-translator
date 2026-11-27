package com.liaoyi.tindertranslator.translator

import com.liaoyi.tindertranslator.data.AiProvider
import com.liaoyi.tindertranslator.data.AppLanguage
import com.liaoyi.tindertranslator.data.AppStrings
import com.liaoyi.tindertranslator.data.OverlaySize
import com.liaoyi.tindertranslator.data.ReplyGender
import com.liaoyi.tindertranslator.data.ReplyLanguage
import com.liaoyi.tindertranslator.data.TranslatorSettings
import com.liaoyi.tindertranslator.parser.ChatMessage
import com.liaoyi.tindertranslator.parser.LanguageDetector
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AiTranslatorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `gemini provider translates into Thai and parses translation field`() = runTest {
        val mockJson = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "{\"translations\":[{\"id\":0,\"translation\":\"คืนนี้ว่างไปหาอะไรดื่มด้วยกันไหม?\",\"note\":\"down to=สะดวก/อยากไป, tn=คืนนี้\"}]}"
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(mockJson))

        val translator = AiTranslator(baseUrlOverride = server.url("/").toString())
        val settings = TranslatorSettings(
            language = AppLanguage.TH,
            provider = AiProvider.GEMINI,
            apiKey = "AIza-test-key",
            model = "gemini-2.5-flash",
            explainSlang = true
        )

        val result = translator.testConnection(settings)
        assertTrue(result.isSuccess)
        val tr = result.getOrThrow()
        assertEquals("คืนนี้ว่างไปหาอะไรดื่มด้วยกันไหม?", tr.translated)
        assertTrue(tr.note.contains("คืนนี้"))

        val recorded = server.takeRequest()
        assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", recorded.path)
        assertEquals("AIza-test-key", recorded.getHeader("x-goog-api-key"))
        assertTrue(recorded.body.readUtf8().contains("ภาษาไทย"))
    }

    @Test
    fun `openai compatible provider handles markdown fenced json and legacy zh key`() = runTest {
        val mockJson = """
            {
              "choices": [
                {
                  "message": {
                    "content": "```json\n{\"translations\":[{\"id\":0,\"zh\":\"我其实不太喜欢尬聊，你最近干过最随性的事是什么？\",\"note\":\"lowkey=其实/有点，tbh=说实话\"}]}\n```"
                  }
                }
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(mockJson))

        val translator = AiTranslator(baseUrlOverride = server.url("/").toString())
        val settings = TranslatorSettings(
            language = AppLanguage.ZH,
            provider = AiProvider.DEEPSEEK,
            apiKey = "sk-deepseek-test",
            model = "deepseek-chat"
        )
        val msg = ChatMessage("m1", "Lowkey tired of small talk tbh, what's the most spontaneous thing you've done lately?", true, 500)

        val res = translator.translateBatch(listOf(msg), setOf("m1"), settings)
        assertTrue(res.isSuccess)
        val tr = res.getOrThrow()["m1"]!!
        assertEquals("我其实不太喜欢尬聊，你最近干过最随性的事是什么？", tr.translated)
        assertTrue(tr.note.contains("lowkey"))

        val recorded = server.takeRequest()
        assertEquals("Bearer sk-deepseek-test", recorded.getHeader("Authorization"))
    }

    @Test
    fun `maps 401 unauthorized into localized errors`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"invalid key\"}"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"invalid key\"}"))

        val translator = AiTranslator(baseUrlOverride = server.url("/").toString())

        val zhRes = translator.testConnection(
            TranslatorSettings(language = AppLanguage.ZH, provider = AiProvider.OPENAI, apiKey = "bad")
        )
        assertTrue(zhRes.isFailure)
        assertTrue(zhRes.exceptionOrNull()!!.message!!.contains("密钥无效"))

        val thRes = translator.testConnection(
            TranslatorSettings(language = AppLanguage.TH, provider = AiProvider.OPENAI, apiKey = "bad")
        )
        assertTrue(thRes.isFailure)
        assertTrue(thRes.exceptionOrNull()!!.message!!.contains("คีย์ AI ไม่ถูกต้อง"))
    }

    @Test
    fun `resolves openai compatible base urls and supports all languages and sizes`() {
        val translator = AiTranslator()
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            translator.resolveOpenAiEndpoint("https://api.openai.com/v1")
        )
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            translator.resolveOpenAiEndpoint("https://api.deepseek.com")
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            translator.resolveOpenAiEndpoint("https://generativelanguage.googleapis.com/v1beta/openai")
        )

        for (lang in AppLanguage.entries) {
            val s = AppStrings.of(lang)
            assertTrue(s.appTitle.isNotEmpty())
            assertTrue(s.safetyBody.isNotEmpty())
            assertTrue(s.btnSaveAndTest.isNotEmpty())
        }

        assertEquals(OverlaySize.MEDIUM, OverlaySize.SMALL.next())
        assertEquals(OverlaySize.LARGE, OverlaySize.MEDIUM.next())
        assertEquals(OverlaySize.SMALL, OverlaySize.LARGE.next())
    }

    @Test
    fun `polishReply auto-detects Thai match language and respects male vs female Thai particles`() = runTest {
        val mockJson = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "{\"options\":[{\"style\":\"自然地道\",\"text\":\"เพิ่งเลิกงานเลย เหนื่อยนิดหน่อย คืนนี้ทำไรอยู่ครับ\",\"back\":\"刚下班有点累，你今晚干嘛呢\"},{\"style\":\"轻松暖一点\",\"text\":\"เพิ่งเลิกงานเลยครับ แอบเหนื่อยนิดนึง คืนนี้ว่างคุยกันมั้ย\",\"back\":\"刚下班有点小累，今晚有空聊会儿吗\"}]}"
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(mockJson))

        val translator = AiTranslator(baseUrlOverride = server.url("/").toString())
        val context = listOf(
            ChatMessage("in1", "สวัสดีค่ะ วันนี้ทำงานเหนื่อยไหมคะ?", isIncoming = true, topY = 400)
        )
        val settings = TranslatorSettings(
            language = AppLanguage.ZH,
            provider = AiProvider.GEMINI,
            apiKey = "AIza-test",
            replyLanguage = ReplyLanguage.AUTO,
            replyGender = ReplyGender.MALE
        )

        val result = translator.polishReply("我刚刚下班很累，你晚上做什么", context, settings)
        assertTrue(result.isSuccess)
        val polished = result.getOrThrow()
        assertEquals(ReplyLanguage.TH, polished.targetLanguage)
        assertEquals(2, polished.options.size)
        assertTrue(polished.options[0].text.contains("ครับ"))
        assertEquals("刚下班有点累，你今晚干嘛呢", polished.options[0].backTranslation)

        val recorded = server.takeRequest().body.readUtf8()
        assertTrue(recorded.contains("NEVER use ค่ะ/คะ"))

        // Verify female Thai prompt switches particles properly
        val femalePrompt = translator.buildReplyPolishSystemPrompt(
            ReplyLanguage.TH,
            AppLanguage.TH,
            ReplyGender.FEMALE
        )
        assertTrue(femalePrompt.contains("NEVER use ครับ"))

        // Verify auto-detection for English / Japanese / Thai
        assertEquals(
            ReplyLanguage.TH,
            LanguageDetector.detectReplyLanguage(listOf("สวัสดีค่ะ สบายดีไหม"), AppLanguage.ZH)
        )
        assertEquals(
            ReplyLanguage.EN,
            LanguageDetector.detectReplyLanguage(listOf("Hey how was your day?"), AppLanguage.ZH)
        )
        assertEquals(
            ReplyLanguage.JA,
            LanguageDetector.detectReplyLanguage(listOf("今週末ひまですか？"), AppLanguage.ZH)
        )
    }
}
