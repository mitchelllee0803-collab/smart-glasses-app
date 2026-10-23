package com.smartglasses.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.smartglasses.app.domain.model.AppSettings
import com.smartglasses.app.domain.model.AppTextSize
import com.smartglasses.app.domain.model.DisplaySettings
import com.smartglasses.app.domain.model.GlassesTextMode
import com.smartglasses.app.domain.model.Language
import com.smartglasses.app.domain.model.MicrophoneSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Singleton
class SettingsRepository @Inject constructor(private val store: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = store.data.map { p ->
        val defaults = AppSettings()
        AppSettings(
            source = Language.fromCode(p[SOURCE]) ?: defaults.source,
            target = Language.fromCode(p[TARGET]) ?: defaults.target,
            display = DisplaySettings(
                textMode = p[TEXT_MODE]?.let { runCatching { GlassesTextMode.valueOf(it) }.getOrNull() } ?: defaults.display.textMode,
                showHistory = p[SHOW_HISTORY] ?: defaults.display.showHistory,
                fontSize = (p[FONT_SIZE] ?: defaults.display.fontSize)
                    .coerceIn(DisplaySettings.MIN_FONT_SIZE, DisplaySettings.MAX_FONT_SIZE),
                liveTranslation = p[LIVE_TRANSLATION] ?: defaults.display.liveTranslation,
            ),
            microphone = p[MICROPHONE]?.let { runCatching { MicrophoneSource.valueOf(it) }.getOrNull() } ?: defaults.microphone,
            aiTranslation = p[AI_TRANSLATION] ?: defaults.aiTranslation,
            appTextSize = p[APP_TEXT_SIZE]?.let { runCatching { AppTextSize.valueOf(it) }.getOrNull() } ?: defaults.appTextSize,
            lastGlassesAddress = p[LAST_GLASSES],
            touchpadPanel = p[TOUCHPAD_PANEL] ?: defaults.touchpadPanel,
        )
    }.distinctUntilChanged()

    suspend fun current(): AppSettings = settings.first()

    suspend fun setLanguages(source: Language, target: Language) = store.edit {
        it[SOURCE] = source.engineCode
        it[TARGET] = target.engineCode
    }

    suspend fun setDisplay(display: DisplaySettings) = store.edit {
        it[TEXT_MODE] = display.textMode.name
        it[SHOW_HISTORY] = display.showHistory
        it[FONT_SIZE] = display.fontSize
        it[LIVE_TRANSLATION] = display.liveTranslation
    }

    suspend fun setMicrophone(source: MicrophoneSource) = store.edit { it[MICROPHONE] = source.name }

    suspend fun setAiTranslation(enabled: Boolean) = store.edit { it[AI_TRANSLATION] = enabled }

    suspend fun setAppTextSize(size: AppTextSize) = store.edit { it[APP_TEXT_SIZE] = size.name }

    suspend fun setLastGlasses(address: String) = store.edit { it[LAST_GLASSES] = address }

    suspend fun setTouchpadPanel(shown: Boolean) = store.edit { it[TOUCHPAD_PANEL] = shown }

    private companion object {
        val SOURCE = stringPreferencesKey("source_language")
        val TARGET = stringPreferencesKey("target_language")
        val TEXT_MODE = stringPreferencesKey("glasses_text_mode")
        val SHOW_HISTORY = booleanPreferencesKey("glasses_show_history")
        val FONT_SIZE = intPreferencesKey("glasses_font_size")
        val LIVE_TRANSLATION = booleanPreferencesKey("live_translation")
        val MICROPHONE = stringPreferencesKey("microphone_source")
        val AI_TRANSLATION = booleanPreferencesKey("ai_translation")
        val APP_TEXT_SIZE = stringPreferencesKey("app_text_size")
        val LAST_GLASSES = stringPreferencesKey("last_glasses_address")
        val TOUCHPAD_PANEL = booleanPreferencesKey("touchpad_panel")
    }
}
