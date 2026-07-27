package com.snapaie.android.core.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Complete 15-slot M3 typography, scaled by the user's text-size setting (xs-xl). */
fun snapTypography(scale: Float = 1f): Typography {
    fun style(size: Int, line: Int, weight: FontWeight, spacing: Float = 0f) = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = weight,
        fontSize = (size * scale).sp,
        lineHeight = (line * scale).sp,
        letterSpacing = spacing.sp,
    )
    return Typography(
        displayLarge = style(56, 64, FontWeight.Bold),
        displayMedium = style(44, 52, FontWeight.Bold),
        displaySmall = style(34, 40, FontWeight.SemiBold),
        headlineLarge = style(32, 40, FontWeight.SemiBold),
        headlineMedium = style(28, 36, FontWeight.SemiBold),
        headlineSmall = style(24, 30, FontWeight.SemiBold),
        titleLarge = style(20, 26, FontWeight.SemiBold),
        titleMedium = style(16, 22, FontWeight.SemiBold),
        titleSmall = style(14, 20, FontWeight.SemiBold),
        bodyLarge = style(16, 24, FontWeight.Normal),
        bodyMedium = style(15, 22, FontWeight.Normal),
        bodySmall = style(13, 18, FontWeight.Normal),
        labelLarge = style(14, 18, FontWeight.SemiBold),
        labelMedium = style(12, 16, FontWeight.SemiBold, 0.4f),
        labelSmall = style(11, 14, FontWeight.SemiBold, 0.5f),
    )
}

val SnapTypography = snapTypography()
