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

enum class ReplyLanguage(val code: String, val aiName: String, val shortLabel: String) {
    AUTO("auto", "", "Auto"),
    TH("th", "natural, warm everyday Thai (ภาษาไทย)", "ไทย"),
    EN("en", "natural casual English", "EN"),
    ZH("zh", "自然口语化的简体中文", "中文"),
    JA("ja", "自然で親しみやすい日本語", "日本語"),
    KO("ko", "자연스럽고 친근한 한국어", "한국어");

    fun next(): ReplyLanguage {
        val list = entries
        return list[(list.indexOf(this) + 1) % list.size]
    }

    companion object {
        fun fromCode(code: String?): ReplyLanguage {
            return entries.firstOrNull { it.code == code } ?: AUTO
        }
    }
}

enum class ReplyGender(val code: String) {
    MALE("male"),
    FEMALE("female"),
    NEUTRAL("neutral");

    companion object {
        fun fromCode(code: String?): ReplyGender {
            return entries.firstOrNull { it.code == code } ?: MALE
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
    val replyLanguage: ReplyLanguage = ReplyLanguage.AUTO,
    val replyGender: ReplyGender = ReplyGender.MALE,
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

data class ReplyOption(
    val styleLabel: String,
    val text: String,
    val backTranslation: String
)

data class ReplyPolishResult(
    val sourceInput: String,
    val targetLanguage: ReplyLanguage,
    val options: List<ReplyOption>
)
