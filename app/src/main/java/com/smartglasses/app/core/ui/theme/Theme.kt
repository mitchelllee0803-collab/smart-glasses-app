package com.smartglasses.app.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

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
fun SmartGlassesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** The app's text-size setting, multiplied with the phone's own font scale (all text is in sp). */
    textScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalExtendedColors provides if (darkTheme) DarkExtendedColors else LightExtendedColors,
        LocalDensity provides Density(density.density, density.fontScale * textScale),
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
        ) {
            // Text and icons placed directly on the page background (top bars, headings) would
            // otherwise use Compose's default content colour, which is black in both themes.
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground, content = content)
        }
    }
}

object AppTheme {
    val colors: ExtendedColors
        @Composable get() = LocalExtendedColors.current
}
