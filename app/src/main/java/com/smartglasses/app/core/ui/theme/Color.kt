package com.smartglasses.app.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Palette sampled from the UX reference screens.
internal val Blue600 = Color(0xFF1976D2)
internal val Blue300 = Color(0xFF6AAEF0)
internal val Blue50 = Color(0xFFE3F0FC)
internal val Navy900 = Color(0xFF1C1B2E)
internal val Grey500 = Color(0xFF8E9199)
internal val Grey100 = Color(0xFFF1F3F8)
internal val Green600 = Color(0xFF34A853)
internal val Red600 = Color(0xFFD93025)
internal val Indigo600 = Color(0xFF3F51B5)
internal val Indigo50 = Color(0xFFE8EAF6)

internal val DarkBackground = Color(0xFF111318)
internal val DarkSurface = Color(0xFF1C1F26)
internal val DarkSurfaceHigh = Color(0xFF262A33)
internal val DarkOnSurface = Color(0xFFE6E7EC)
internal val DarkMuted = Color(0xFF9A9DA6)

/** Colours the design uses beyond the Material 3 scheme. */
@Immutable
data class ExtendedColors(
    val success: Color,
    val muted: Color,
    val iconCircle: Color,
    val translateTile: Color,
    val translateIcon: Color,
    val listening: Color,
    /** Grey behind the message bubbles of a conversation. */
    val chatBackground: Color,
    val danger: Color,
)

internal val LightExtendedColors = ExtendedColors(
    success = Green600,
    muted = Grey500,
    iconCircle = Blue50,
    translateTile = Indigo50,
    translateIcon = Indigo600,
    listening = Red600,
    chatBackground = Color(0xFFE3E6EB),
    danger = Color(0xFFF21B1B),
)

internal val DarkExtendedColors = ExtendedColors(
    success = Color(0xFF5BC07A),
    muted = DarkMuted,
    iconCircle = Color(0xFF1E3550),
    translateTile = Color(0xFF2A2F55),
    translateIcon = Color(0xFFA9B4FF),
    listening = Color(0xFFFF6B5E),
    chatBackground = Color(0xFF0B0D11),
    danger = Color(0xFFFF5449),
)

val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }
