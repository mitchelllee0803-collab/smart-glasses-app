package com.smartglasses.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartglasses.app.data.settings.SettingsRepository
import com.smartglasses.app.domain.model.AppSettings
import com.smartglasses.app.domain.model.DisplaySettings
import com.smartglasses.app.domain.model.GlassesTextMode
import com.smartglasses.app.domain.model.MicrophoneSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Settings are stored at once; the Translate screen applies them to the glasses and the session. */
@HiltViewModel
class TranslationSettingsViewModel @Inject constructor(private val repository: SettingsRepository) : ViewModel() {
    /** Null until the stored settings are loaded, so no default flashes as "selected". */
    val settings: StateFlow<AppSettings?> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun setMicrophone(source: MicrophoneSource) {
        viewModelScope.launch { repository.setMicrophone(source) }
    }

    fun setTextMode(mode: GlassesTextMode) = updateDisplay { it.copy(textMode = mode) }

    fun setShowHistory(show: Boolean) = updateDisplay { it.copy(showHistory = show) }

    fun setFontSize(size: Int) = updateDisplay {
        it.copy(fontSize = size.coerceIn(DisplaySettings.MIN_FONT_SIZE, DisplaySettings.MAX_FONT_SIZE))
    }

    private fun updateDisplay(change: (DisplaySettings) -> DisplaySettings) {
        viewModelScope.launch { repository.setDisplay(change(repository.current().display)) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
