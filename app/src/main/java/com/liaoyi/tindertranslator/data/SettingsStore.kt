package com.liaoyi.tindertranslator.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val cachePrefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE)

    private val memoryCache = object : LinkedHashMap<String, TranslationResult>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TranslationResult>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    init {
        loadCacheFromDisk()
    }

    fun loadSettings(): TranslatorSettings {
        val provider = AiProvider.fromName(prefs.getString(KEY_PROVIDER, AiProvider.GEMINI.name))
        val language = AppLanguage.fromCode(prefs.getString(KEY_LANGUAGE, AppLanguage.ZH.code))
        val overlaySize = OverlaySize.fromLevel(prefs.getInt(KEY_OVERLAY_SIZE, OverlaySize.MEDIUM.level))
        val replyLanguage = ReplyLanguage.fromCode(prefs.getString(KEY_REPLY_LANGUAGE, ReplyLanguage.AUTO.code))
        val replyGender = ReplyGender.fromCode(prefs.getString(KEY_REPLY_GENDER, ReplyGender.MALE.code))
        return TranslatorSettings(
            language = language,
            provider = provider,
            apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
            model = prefs.getString(KEY_MODEL, provider.defaultModel) ?: provider.defaultModel,
            customBaseUrl = prefs.getString(KEY_CUSTOM_URL, AiProvider.CUSTOM.defaultBaseUrl)
                ?: AiProvider.CUSTOM.defaultBaseUrl,
            autoPopup = prefs.getBoolean(KEY_AUTO_POPUP, true),
            explainSlang = prefs.getBoolean(KEY_EXPLAIN_SLANG, true),
            showMyMessages = prefs.getBoolean(KEY_SHOW_MY_MESSAGES, false),
            overlaySize = overlaySize,
            replyLanguage = replyLanguage,
            replyGender = replyGender,
            maxMessages = prefs.getInt(KEY_MAX_MESSAGES, 5).coerceIn(3, 8)
        )
    }

    fun saveSettings(settings: TranslatorSettings) {
        prefs.edit()
            .putString(KEY_LANGUAGE, settings.language.code)
            .putString(KEY_PROVIDER, settings.provider.name)
            .putString(KEY_API_KEY, settings.apiKey.trim())
            .putString(KEY_MODEL, settings.model.trim())
            .putString(KEY_CUSTOM_URL, settings.customBaseUrl.trim())
            .putBoolean(KEY_AUTO_POPUP, settings.autoPopup)
            .putBoolean(KEY_EXPLAIN_SLANG, settings.explainSlang)
            .putBoolean(KEY_SHOW_MY_MESSAGES, settings.showMyMessages)
            .putInt(KEY_OVERLAY_SIZE, settings.overlaySize.level)
            .putString(KEY_REPLY_LANGUAGE, settings.replyLanguage.code)
            .putString(KEY_REPLY_GENDER, settings.replyGender.code)
            .putInt(KEY_MAX_MESSAGES, settings.maxMessages.coerceIn(3, 8))
            .apply()
    }

    fun setAutoPopup(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_POPUP, enabled).apply()
    }

    fun setOverlaySize(size: OverlaySize) {
        prefs.edit().putInt(KEY_OVERLAY_SIZE, size.level).apply()
    }

    fun setReplyLanguage(lang: ReplyLanguage) {
        prefs.edit().putString(KEY_REPLY_LANGUAGE, lang.code).apply()
    }

    @Synchronized
    fun getCachedTranslation(
        originalText: String,
        language: AppLanguage,
        explainSlang: Boolean
    ): TranslationResult? {
        return memoryCache[cacheKey(originalText, language, explainSlang)]
    }

    @Synchronized
    fun putCachedTranslation(
        result: TranslationResult,
        language: AppLanguage,
        explainSlang: Boolean
    ) {
        memoryCache[cacheKey(result.original, language, explainSlang)] = result
        persistCacheToDisk()
    }

    @Synchronized
    fun clearCache() {
        memoryCache.clear()
        cachePrefs.edit().remove(KEY_CACHE_JSON).apply()
    }

    private fun cacheKey(text: String, language: AppLanguage, explainSlang: Boolean): String {
        val norm = text.trim().replace(Regex("\\s+"), " ")
        return "${language.code}|${if (explainSlang) "1" else "0"}|$norm"
    }

    private fun loadCacheFromDisk() {
        val raw = cachePrefs.getString(KEY_CACHE_JSON, null) ?: return
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val key = obj.optString("k")
                val orig = obj.optString("o")
                val tr = obj.optString("t").ifEmpty { obj.optString("z") }
                val note = obj.optString("n")
                if (key.isNotEmpty() && orig.isNotEmpty() && tr.isNotEmpty()) {
                    memoryCache[key] = TranslationResult(orig, tr, note)
                }
            }
        }
    }

    private fun persistCacheToDisk() {
        runCatching {
            val array = JSONArray()
            for ((k, v) in memoryCache) {
                val obj = JSONObject()
                    .put("k", k)
                    .put("o", v.original)
                    .put("t", v.translated)
                    .put("n", v.note)
                array.put(obj)
            }
            cachePrefs.edit().putString(KEY_CACHE_JSON, array.toString()).apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "liaoyi_settings"
        private const val CACHE_PREFS_NAME = "liaoyi_cache"
        private const val KEY_LANGUAGE = "app_language"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_CUSTOM_URL = "custom_url"
        private const val KEY_AUTO_POPUP = "auto_popup"
        private const val KEY_EXPLAIN_SLANG = "explain_slang"
        private const val KEY_SHOW_MY_MESSAGES = "show_my_messages"
        private const val KEY_OVERLAY_SIZE = "overlay_size"
        private const val KEY_REPLY_LANGUAGE = "reply_language"
        private const val KEY_REPLY_GENDER = "reply_gender"
        private const val KEY_MAX_MESSAGES = "max_messages"
        private const val KEY_CACHE_JSON = "cache_json"
        private const val MAX_CACHE_SIZE = 200
    }
}
