package com.smartglasses.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartglasses.app.data.security.AppLock
import com.smartglasses.app.data.settings.SettingsRepository
import com.smartglasses.app.data.settings.ThemePreferences
import com.smartglasses.app.domain.model.AppTextSize
import com.smartglasses.app.domain.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class PasswordChangeResult { CHANGED, WRONG_CURRENT }

/** App-wide settings (☰ → Settings); the glasses' own options are in Translation Settings. */
@HiltViewModel
class AppSettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val themePreferences: ThemePreferences,
    private val lock: AppLock,
) : ViewModel() {
    /** Null until loaded, so no default flashes as "selected". */
    val textSize: StateFlow<AppTextSize?> = repository.settings.map { it.appTextSize }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val theme: StateFlow<ThemeMode> = themePreferences.theme

    private val _passwordResult = MutableStateFlow<PasswordChangeResult?>(null)
    val passwordResult: StateFlow<PasswordChangeResult?> = _passwordResult.asStateFlow()

    fun setTextSize(size: AppTextSize) {
        viewModelScope.launch { repository.setAppTextSize(size) }
    }

    fun setTheme(mode: ThemeMode) = themePreferences.set(mode)

    fun changePassword(current: String, new: String) {
        viewModelScope.launch {
            _passwordResult.value = if (lock.changePassword(current, new)) PasswordChangeResult.CHANGED else PasswordChangeResult.WRONG_CURRENT
        }
    }

    fun clearPasswordResult() {
        _passwordResult.value = null
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
