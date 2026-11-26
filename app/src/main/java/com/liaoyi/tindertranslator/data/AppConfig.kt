package com.liaoyi.tindertranslator.data

enum class AppLanguage(
    val code: String,
    val nativeLabel: String,
    val aiTargetName: String,
    val pillSymbol: String
) {
    ZH(
        code = "zh",
        nativeLabel = "中文",
        aiTargetName = "简体中文",
        pillSymbol = "译"
    ),
    TH(
        code = "th",
        nativeLabel = "ไทย",
        aiTargetName = "ภาษาไทย (natural conversational Thai)",
        pillSymbol = "แปล"
    ),
    EN(
        code = "en",
        nativeLabel = "English",
        aiTargetName = "natural conversational English",
        pillSymbol = "Tr"
    ),
    JA(
        code = "ja",
        nativeLabel = "日本語",
        aiTargetName = "自然な日本語 (natural Japanese)",
        pillSymbol = "訳"
    ),
    KO(
        code = "ko",
        nativeLabel = "한국어",
        aiTargetName = "자연스러운 한국어 (natural Korean)",
        pillSymbol = "번"
    );

    companion object {
        fun fromCode(code: String?): AppLanguage {
            return entries.firstOrNull { it.code == code } ?: ZH
        }
    }
}

enum class OverlaySize(val level: Int, val heightRatio: Float, val fontScale: Float) {
    SMALL(0, 0.25f, 0.88f),
    MEDIUM(1, 0.33f, 1.0f),
    LARGE(2, 0.44f, 1.14f);

    fun next(): OverlaySize = when (this) {
        SMALL -> MEDIUM
        MEDIUM -> LARGE
        LARGE -> SMALL
    }

    companion object {
        fun fromLevel(level: Int): OverlaySize {
            return entries.firstOrNull { it.level == level } ?: MEDIUM
        }
    }
}

enum class AiProvider(
    val displayName: String,
    val defaultModel: String,
    val defaultBaseUrl: String
) {
    GEMINI(
        displayName = "Gemini",
        defaultModel = "gemini-2.5-flash",
        defaultBaseUrl = "https://generativelanguage.googleapis.com"
    ),
    DEEPSEEK(
        displayName = "DeepSeek",
        defaultModel = "deepseek-chat",
        defaultBaseUrl = "https://api.deepseek.com"
    ),
    OPENAI(
        displayName = "OpenAI",
        defaultModel = "gpt-4o-mini",
        defaultBaseUrl = "https://api.openai.com/v1"
    ),
    CUSTOM(
        displayName = "Custom",
        defaultModel = "gpt-4o-mini",
        defaultBaseUrl = "https://api.openai.com/v1"
    );

    companion object {
        fun fromName(name: String?): AiProvider {
            return entries.firstOrNull { it.name == name } ?: GEMINI
        }
    }
}

data class TranslatorSettings(
    val language: AppLanguage = AppLanguage.ZH,
    val provider: AiProvider = AiProvider.GEMINI,
    val apiKey: String = "",
    val model: String = AiProvider.GEMINI.defaultModel,
    val customBaseUrl: String = AiProvider.CUSTOM.defaultBaseUrl,
    val autoPopup: Boolean = true,
    val explainSlang: Boolean = true,
    val showMyMessages: Boolean = false,
    val overlaySize: OverlaySize = OverlaySize.MEDIUM,
    val maxMessages: Int = 5
) {
    fun effectiveModel(): String = model.trim().ifEmpty { provider.defaultModel }

    fun effectiveBaseUrl(): String = when (provider) {
        AiProvider.CUSTOM -> customBaseUrl.trim().ifEmpty { provider.defaultBaseUrl }
        else -> provider.defaultBaseUrl
    }

    fun hasApiKey(): Boolean = apiKey.trim().isNotEmpty()
}

data class TranslationResult(
    val original: String,
    val translated: String,
    val note: String = ""
) {
    val chinese: String get() = translated
}
