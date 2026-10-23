package com.smartglasses.app.data.settings

import android.content.Context
import com.smartglasses.app.domain.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Light / dark theme. Kept in SharedPreferences, not DataStore: it must be known synchronously
 * before the first frame, or the app would flash light before turning dark.
 */
@Singleton
class ThemePreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _theme = MutableStateFlow(read())
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    fun set(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _theme.value = mode
    }

    private fun read(): ThemeMode =
        prefs.getString(KEY_THEME, null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.LIGHT

    private companion object {
        const val PREFS = "ui"
        const val KEY_THEME = "theme"
    }
}
