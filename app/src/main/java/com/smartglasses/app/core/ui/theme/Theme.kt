package com.smartglasses.app.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Blue600,
    onPrimary = Color.White,
    primaryContainer = Blue50,
    onPrimaryContainer = Blue600,
    background = Grey100,
    onBackground = Navy900,
    surface = Color.White,
    onSurface = Navy900,
    surfaceVariant = Grey100,
    onSurfaceVariant = Grey500,
    surfaceContainerLow = Color.White,
    surfaceContainerHigh = Color.White,
    error = Red600,
)

private val DarkColors = darkColorScheme(
    primary = Blue300,
    onPrimary = Navy900,
    primaryContainer = Color(0xFF1E3550),
    onPrimaryContainer = Blue300,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceHigh,
    onSurfaceVariant = DarkMuted,
    surfaceContainerLow = DarkSurface,
    surfaceContainerHigh = DarkSurfaceHigh,
    error = Color(0xFFFF6B5E),
)

@Composable
fun SmartGlassesTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalExtendedColors provides if (darkTheme) DarkExtendedColors else LightExtendedColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            content = content,
        )
    }
}

object AppTheme {
    val colors: ExtendedColors
        @Composable get() = LocalExtendedColors.current
}
