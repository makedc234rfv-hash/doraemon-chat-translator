package com.liaoyi.tindertranslator.translator

import com.liaoyi.tindertranslator.data.AiProvider
import com.liaoyi.tindertranslator.data.AppLanguage
import com.liaoyi.tindertranslator.data.AppStrings
import com.liaoyi.tindertranslator.data.TranslationResult
import com.liaoyi.tindertranslator.data.TranslatorSettings
import com.liaoyi.tindertranslator.parser.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class AiTranslator(
    private val client: OkHttpClient = defaultClient(),
    private val baseUrlOverride: String? = null
) {

    suspend fun translateBatch(
        contextMessages: List<ChatMessage>,
        targetIds: Set<String>,
        settings: TranslatorSettings
    ): Result<Map<String, TranslationResult>> = withContext(Dispatchers.IO) {
        val textRes = AppStrings.of(settings.language)
        if (!settings.hasApiKey()) {
            return@withContext Result.failure(IllegalStateException(textRes.errMissingKey))
        }

        val targets = contextMessages.filter { it.id in targetIds }
        if (targets.isEmpty()) {
            return@withContext Result.success(emptyMap())
        }

        val indexedTargets = targets.mapIndexed { idx, msg -> idx to msg }
        val systemPrompt = buildSystemPrompt(settings.language, settings.explainSlang)
        val userPrompt = buildUserPrompt(contextMessages, indexedTargets)

        try {
            val rawText = when (settings.provider) {
                AiProvider.GEMINI -> callGemini(systemPrompt, userPrompt, settings)
                AiProvider.DEEPSEEK, AiProvider.OPENAI, AiProvider.CUSTOM ->
                    callOpenAiCompatible(systemPrompt, userPrompt, settings)
            }
            val parsed = parseTranslationJson(rawText, indexedTargets)
            if (parsed.isEmpty()) {
                Result.failure(IOException(textRes.errBadFormat))
            } else {
                Result.success(parsed)
            }
        } catch (e: ApiException) {
            Result.failure(e)
        } catch (e: SocketTimeoutException) {
            Result.failure(IOException(textRes.errTimeout, e))
        } catch (e: UnknownHostException) {
            Result.failure(IOException(textRes.errNetwork, e))
        } catch (e: Exception) {
            Result.failure(IOException(textRes.errGeneric(e.message ?: ""), e))
        }
    }

    suspend fun testConnection(settings: TranslatorSettings): Result<TranslationResult> {
        val sampleText = when (settings.language) {
            AppLanguage.EN -> "今晚有空一起出来喝一杯吗？"
            else -> "u down to grab drinks tn?"
        }
        val sample = ChatMessage(
            id = "test_sample",
            text = sampleText,
            isIncoming = true,
            topY = 300
        )
        return translateBatch(
            contextMessages = listOf(sample),
            targetIds = setOf(sample.id),
            settings = settings
        ).mapCatching { map ->
            map[sample.id] ?: throw IOException(AppStrings.of(settings.language).errBadFormat)
        }
    }

    private fun callGemini(
        systemPrompt: String,
        userPrompt: String,
        settings: TranslatorSettings
    ): String {
        val base = (baseUrlOverride ?: settings.effectiveBaseUrl()).trimEnd('/')
        val model = settings.effectiveModel().removePrefix("models/")
        val url = "$base/v1beta/models/$model:generateContent"

        val bodyJson = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemPrompt))
                )
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
                )
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.3)
                    .put("responseMimeType", "application/json")
            )

        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", settings.apiKey.trim())
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { resp ->
            val respBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw mapHttpError(resp.code, respBody, settings.language)
            }
            val root = JSONObject(respBody)
            val candidates = root.optJSONArray("candidates")
                ?: throw ApiException(AppStrings.of(settings.language).errBadFormat)
            if (candidates.length() == 0) {
                throw ApiException(AppStrings.of(settings.language).errBadFormat)
            }
            val parts = candidates.getJSONObject(0)
                .optJSONObject("content")
                ?.optJSONArray("parts")
                ?: throw ApiException(AppStrings.of(settings.language).errBadFormat)
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                sb.append(parts.getJSONObject(i).optString("text"))
            }
            return sb.toString().trim()
        }
    }

    private fun callOpenAiCompatible(
        systemPrompt: String,
        userPrompt: String,
        settings: TranslatorSettings
    ): String {
        val url = resolveOpenAiEndpoint(baseUrlOverride ?: settings.effectiveBaseUrl())

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", userPrompt))

        val bodyJson = JSONObject()
            .put("model", settings.effectiveModel())
            .put("temperature", 0.3)
            .put("messages", messages)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${settings.apiKey.trim()}")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { resp ->
            val respBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw mapHttpError(resp.code, respBody, settings.language)
            }
            val root = JSONObject(respBody)
            val choices = root.optJSONArray("choices")
                ?: throw ApiException(AppStrings.of(settings.language).errBadFormat)
            if (choices.length() == 0) {
                throw ApiException(AppStrings.of(settings.language).errBadFormat)
            }
            return choices.getJSONObject(0)
                .getJSONObject("message")
                .optString("content")
                .trim()
        }
    }

    internal fun resolveOpenAiEndpoint(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return when {
            trimmed.endsWith("/chat/completions") -> trimmed
            trimmed.endsWith("/v1") || trimmed.endsWith("/openai") -> "$trimmed/chat/completions"
            trimmed.contains("api.deepseek.com") -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }

    internal fun parseTranslationJson(
        rawText: String,
        indexedTargets: List<Pair<Int, ChatMessage>>
    ): Map<String, TranslationResult> {
        val cleaned = extractJsonBlock(rawText)
        val resultMap = LinkedHashMap<String, TranslationResult>()
        val targetByIndex = indexedTargets.toMap()

        runCatching {
            val root = JSONObject(cleaned)
            val arr = root.optJSONArray("translations")
                ?: root.optJSONArray("items")
                ?: return@runCatching
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val idx = item.optInt("id", i)
                val msg = targetByIndex[idx] ?: continue
                val translated = item.optString("translation").trim()
                    .ifEmpty { item.optString("zh").trim() }
                    .ifEmpty { item.optString("text").trim() }
                val note = item.optString("note").trim()
                if (translated.isNotEmpty()) {
                    resultMap[msg.id] = TranslationResult(
                        original = msg.text,
                        translated = translated,
                        note = note
                    )
                }
            }
        }

        if (resultMap.isEmpty() && indexedTargets.size == 1) {
            val fallbackText = rawText.trim().removePrefix("```").removeSuffix("```").trim()
            if (fallbackText.isNotEmpty() && !fallbackText.startsWith("{")) {
                val msg = indexedTargets.first().second
                resultMap[msg.id] = TranslationResult(msg.text, fallbackText, "")
            }
        }

        return resultMap
    }

    internal fun extractJsonBlock(raw: String): String {
        val trimmed = raw.trim()
        val fenceRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        val fenced = fenceRegex.find(trimmed)?.groupValues?.getOrNull(1)?.trim()
        if (!fenced.isNullOrEmpty()) return fenced

        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1)
        }
        return trimmed
    }

    internal fun buildSystemPrompt(language: AppLanguage, explainSlang: Boolean): String {
        val target = language.aiTargetName
        val noteRule = if (explainSlang) {
            "If a message contains slang, abbreviations, dating idioms, jokes, or playful/flirting tone, write a very short explanation in $target (under 24 words/chars) in the \"note\" field; otherwise leave \"note\" as \"\"."
        } else {
            "Always set \"note\" to an empty string \"\"."
        }
        return """
You are a natural, fluent dating-chat translator helping users understand foreign-language messages on Tinder.
Rules:
1. Translate each target message into natural, conversational $target fitting casual chat context (avoid stiff literal translations).
2. $noteRule
3. Output ONLY valid JSON in this exact structure, with no extra text:
{"translations":[{"id":0,"translation":"translated text","note":""}]}
        """.trimIndent()
    }

    private fun buildUserPrompt(
        contextMessages: List<ChatMessage>,
        indexedTargets: List<Pair<Int, ChatMessage>>
    ): String {
        val sb = StringBuilder()
        sb.append("Recent chat context:\n")
        for (msg in contextMessages) {
            val role = if (msg.isIncoming) "Match" else "Me"
            sb.append("[$role] ${msg.text}\n")
        }
        sb.append("\nTranslate these numbered messages (JSON only):\n")
        for ((idx, msg) in indexedTargets) {
            sb.append("id=$idx: ${msg.text}\n")
        }
        return sb.toString()
    }

    private fun mapHttpError(code: Int, body: String, language: AppLanguage): ApiException {
        val t = AppStrings.of(language)
        val msg = when (code) {
            400 -> t.errHttp400
            401, 403 -> t.errHttp401
            404 -> t.errHttp404
            429 -> t.errHttp429
            in 500..599 -> t.errHttp5xx(code)
            else -> t.errHttpOther(code)
        }
        return ApiException(msg, code, body.take(200))
    }

    class ApiException(
        message: String,
        val httpCode: Int = 0,
        val detail: String = ""
    ) : IOException(message)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .writeTimeout(12, TimeUnit.SECONDS)
                .build()
        }
    }
}
