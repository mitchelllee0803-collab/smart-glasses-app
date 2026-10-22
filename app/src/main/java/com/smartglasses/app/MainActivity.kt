package com.smartglasses.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.smartglasses.app.core.ui.theme.SmartGlassesTheme
import com.smartglasses.app.data.venus.GlassesSync
import com.smartglasses.app.navigation.AppNavigation
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var glassesSync: GlassesSync

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            SmartGlassesTheme { AppNavigation(glassesSync) }
        }
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
