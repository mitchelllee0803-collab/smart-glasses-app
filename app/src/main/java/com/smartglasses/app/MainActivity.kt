package com.smartglasses.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartglasses.app.core.locale.AppLocales
import com.smartglasses.app.core.ui.theme.DarkBackground
import com.smartglasses.app.core.ui.theme.Grey100
import com.smartglasses.app.core.ui.theme.SmartGlassesTheme
import com.smartglasses.app.data.security.AppLock
import com.smartglasses.app.data.settings.SettingsRepository
import com.smartglasses.app.data.settings.ThemePreferences
import com.smartglasses.app.data.venus.GlassesSync
import com.smartglasses.app.domain.model.AppTextSize
import com.smartglasses.app.domain.model.ThemeMode
import com.smartglasses.app.feature.lock.LockScreen
import com.smartglasses.app.navigation.AppNavigation
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.map

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var glassesSync: GlassesSync
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var themePreferences: ThemePreferences
    @Inject lateinit var appLock: AppLock

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocales.wrap(newBase)) // the app's language before Android 13
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyWindowTheme(themePreferences.theme.value) // before the first frame: no light flash
        setContent {
            val theme by themePreferences.theme.collectAsStateWithLifecycle()
            // The app's text size (Settings) applies to every screen.
            val textSize by remember { settings.settings.map { it.appTextSize } }
                .collectAsStateWithLifecycle(initialValue = AppTextSize.MEDIUM)
            val unlocked by appLock.unlocked.collectAsStateWithLifecycle()
            LaunchedEffect(theme) { applyWindowTheme(theme) }

            SmartGlassesTheme(darkTheme = theme == ThemeMode.DARK, textScale = textSize.scale) {
                // Nothing of the app (or its glasses navigation) runs until the password is entered.
                if (unlocked) AppNavigation(glassesSync) else LockScreen()
            }
        }
    }

    /** System bar icons and the window behind the app follow the app's theme, not the phone's. */
    private fun applyWindowTheme(theme: ThemeMode) {
        val dark = theme == ThemeMode.DARK
        val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        window.setBackgroundDrawable(ColorDrawable((if (dark) DarkBackground else Grey100).toArgb()))
    }

    override fun onStart() {
        super.onStart()
        glassesSync.setUiVisible(true)
    }

    override fun onStop() {
        glassesSync.setUiVisible(false)
        super.onStop()
    }
}
