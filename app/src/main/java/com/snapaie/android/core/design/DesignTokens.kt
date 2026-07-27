package com.snapaie.android.core.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Design tokens merged from the extension's :root CSS variables and Forge Recall
 * (.lockin-*) system with SnapAE's mint identity.
 */
object DesignTokens {

    // SnapAE identity
    val Mint = Color(0xFF88F0D0)
    val Amber = Color(0xFFFFD66B)
    val Periwinkle = Color(0xFFAFC7FF)
    val DarkBackground = Color(0xFF0E1415)
    val DarkSurface = Color(0xFF151B1D)
    val DarkSurfaceVariant = Color(0xFF263135)

    // Extension accents
    val AccentBlue = Color(0xFF007AFF)
    val AccentBlueStrong = Color(0xFF0056B3)
    val PurpleA = Color(0xFF667EEA)
    val PurpleB = Color(0xFF764BA2)

    // CEFR level colors
    val CefrB2 = Color(0xFF4CAF50)
    val CefrC1 = Color(0xFF2196F3)
    val CefrC2 = Color(0xFF9C27B0)

    // Forge Recall dark-world palette
    val ForgeDeep = Color(0xFF0F172A)
    val ForgeMid = Color(0xFF1E1B4B)
    val ForgeBright = Color(0xFF312E81)
    val ForgeBorder = Color(0x738B5CF6)
    val ForgeText = Color(0xFFF8FAFC)
    val XpIndigo = Color(0xFF6366F1)
    val XpPurple = Color(0xFFA855F7)
    val XpPink = Color(0xFFEC4899)
    val XpOrange = Color(0xFFF97316)
    val DueRed = Color(0xFFEF4444)
    val DueAmber = Color(0xFFFBBF24)
    val SuccessGreen = Color(0xFF10B981)

    // Gradients
    val HeaderPurple = Brush.linearGradient(listOf(PurpleA, PurpleB))
    val ShareIndigoPink = Brush.linearGradient(listOf(Color(0xFF4F46E5), Color(0xFFDB2777)))
    val XpBarBrush = Brush.horizontalGradient(listOf(XpIndigo, XpPurple, XpPink, XpOrange, XpIndigo))
    val ForgeHero = Brush.linearGradient(
        colors = listOf(ForgeDeep, ForgeMid, ForgeBright),
        start = Offset.Zero,
        end = Offset.Infinite,
    )

    // Forge mode-button gradients (per extension .lockin-mode-btn)
    val RapidGradient = Brush.linearGradient(listOf(Color(0xFF6366F1), Color(0xFF4338CA)))
    val SurvivalGradient = Brush.linearGradient(listOf(Color(0xFFDC2626), Color(0xFF991B1B)))
    val FeynmanGradient = Brush.linearGradient(listOf(Color(0xFF9333EA), Color(0xFF6D28D9)))
    val SaveGradient = Brush.linearGradient(listOf(Color(0xFF0D9488), Color(0xFF0F766E)))
    val InterleaveGradient = Brush.linearGradient(listOf(Color(0xFFF59E0B), Color(0xFFD97706)))

    // Confetti (extension's 8-color array)
    val ConfettiColors = listOf(
        Color(0xFFF59E0B), Color(0xFFEF4444), Color(0xFF8B5CF6), Color(0xFF06B6D4),
        Color(0xFF10B981), Color(0xFFEC4899), Color(0xFFEAB308), Color(0xFF3B82F6),
    )

    // Radius scale (--ae-radius-*)
    val RadiusSm = 10.dp
    val RadiusMd = 14.dp
    val RadiusLg = 18.dp

    // Spacing scale (--ae-space-*)
    val Space1 = 4.dp
    val Space2 = 8.dp
    val Space3 = 12.dp
    val Space4 = 16.dp

    // Chat bubbles (iOS style per extension)
    val ChatUserBubble = Color(0xFF007AFF)
    val ChatAiBubbleLight = Color(0xFFE5E5EA)
    val ChatAiBubbleDark = Color(0xFF2A3235)
}
