package com.smartglasses.app.domain.model

import androidx.compose.runtime.Immutable

/**
 * Languages of the offline engine (English, Korean, Chinese Simplified / Traditional).
 * [label] is the language's own name: shown in the app and sent to the glasses as language hint.
 */
enum class Language(val engineCode: String, val label: String) {
    ENGLISH("en", "English"),
    KOREAN("ko", "한국어"),
    CHINESE_SIMPLIFIED("zh-Hans", "简体中文"),
    CHINESE_TRADITIONAL("zh-Hant", "繁體中文");

    companion object {
        fun fromCode(code: String?): Language? = entries.firstOrNull { it.engineCode == code }
    }
}

/** One spoken phrase: live text while it is spoken, then the final text and its translation. */
@Immutable
data class TranscriptLine(
    val id: Long,
    val original: String,
    val translation: String = "",
    val isFinal: Boolean = false,
    val translating: Boolean = false,
)

enum class GlassesTextMode { TRANSLATION_ONLY, ORIGINAL_AND_TRANSLATION }

enum class ThemeMode { LIGHT, DARK }

/** Text size of the app's own screens (not the glasses), on top of the phone's font size. */
enum class AppTextSize(val scale: Float) { SMALL(0.9f), MEDIUM(1.0f), LARGE(1.15f) }

/** Where speech is picked up. The glasses' microphone reaches the phone over Bluetooth hands-free (HFP). */
enum class MicrophoneSource { GLASSES, PHONE }

@Immutable
data class DisplaySettings(
    val textMode: GlassesTextMode = GlassesTextMode.ORIGINAL_AND_TRANSLATION,
    /** Earlier lines stay visible above the current one. */
    val showHistory: Boolean = true,
    /** VenusSDK FontConfig "weight" (font size); the guide recommends 30. */
    val fontSize: Int = DEFAULT_FONT_SIZE,
    /** Also translate the phrase while it is being spoken (otherwise only at the pause). */
    val liveTranslation: Boolean = true,
) {
    companion object {
        const val DEFAULT_FONT_SIZE = 30
        const val MIN_FONT_SIZE = 20
        const val MAX_FONT_SIZE = 40
    }
}

@Immutable
data class AppSettings(
    val source: Language = Language.ENGLISH,
    val target: Language = Language.KOREAN,
    val display: DisplaySettings = DisplaySettings(),
    val microphone: MicrophoneSource = MicrophoneSource.GLASSES,
    /** Translate finished sentences with the on-device AI model (Hy-MT2) when it is installed. */
    val aiTranslation: Boolean = true,
    val appTextSize: AppTextSize = AppTextSize.MEDIUM,
    val lastGlassesAddress: String? = null,
    /** The floating touchpad (👆 on the dashboard), shown while glasses are connected. */
    val touchpadPanel: Boolean = false,
)
