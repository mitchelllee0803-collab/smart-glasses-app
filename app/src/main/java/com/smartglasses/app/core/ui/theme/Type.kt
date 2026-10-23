package com.smartglasses.app.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * One text scale for every page (the dashboard's). Use these roles instead of custom sizes:
 * titleLarge = page title, titleMedium = card / section heading, titleSmall = list item name,
 * bodyLarge = body text, bodyMedium = secondary text (with AppTheme.colors.muted), labelLarge = buttons.
 */
internal val AppTypography = Typography().run {
    copy(
        titleLarge = titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontSize = 16.sp, lineHeight = 23.sp),
        bodyMedium = bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = labelLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    )
}

/** Small upper-case section heading ("FEATURES"). */
internal val SectionLabelStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
