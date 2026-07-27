package com.snapaie.android.core.design

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val SnapSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

enum class ThemeMode { SnapDark, Light, Aurora;
    companion object {
        fun fromStored(value: String): ThemeMode = entries.firstOrNull { it.name == value } ?: SnapDark
    }
}

private val SnapDarkScheme: ColorScheme = darkColorScheme(
    primary = DesignTokens.Mint,
    secondary = DesignTokens.Amber,
    tertiary = DesignTokens.Periwinkle,
    background = DesignTokens.DarkBackground,
    surface = DesignTokens.DarkSurface,
    surfaceVariant = DesignTokens.DarkSurfaceVariant,
    onPrimary = Color(0xFF06211A),
    onSecondary = Color(0xFF2A2100),
    onBackground = Color(0xFFEAF3EF),
    onSurface = Color(0xFFEAF3EF),
    onSurfaceVariant = Color(0xFFB9C9C4),
    outline = Color(0xFF50605F),
)

/** Light theme built from the extension's tokens (accent #007AFF, purple headers). */
private val LightScheme: ColorScheme = lightColorScheme(
    primary = DesignTokens.AccentBlue,
    secondary = DesignTokens.PurpleA,
    tertiary = DesignTokens.PurpleB,
    background = Color(0xFFF7F8FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDEFF7),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF1D2530),
    onSurface = Color(0xFF1D2530),
    onSurfaceVariant = Color(0xFF5B6470),
    outline = Color(0xFFC9CEDC),
)

/** Aurora: accent-dark variant blending the extension's indigo/violet with deep slate. */
private val AuroraScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFA5B4FC),
    secondary = DesignTokens.XpPink,
    tertiary = DesignTokens.Amber,
    background = Color(0xFF0F1222),
    surface = Color(0xFF181C30),
    surfaceVariant = Color(0xFF272C45),
    onPrimary = Color(0xFF141838),
    onSecondary = Color(0xFF33101F),
    onBackground = Color(0xFFECEEFB),
    onSurface = Color(0xFFECEEFB),
    onSurfaceVariant = Color(0xFFB9BEDA),
    outline = Color(0xFF525A7E),
)

@Composable
fun SnapAieTheme(
    mode: ThemeMode = ThemeMode.SnapDark,
    textScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val scheme = when (mode) {
        ThemeMode.SnapDark -> SnapDarkScheme
        ThemeMode.Light -> LightScheme
        ThemeMode.Aurora -> AuroraScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = snapTypography(textScale),
        content = content,
    )
}

@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    radius: Dp = DesignTokens.RadiusMd,
    blur: Dp = 18.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Box(
        modifier = modifier
            .shadow(18.dp, shape, ambientColor = Color.Black.copy(alpha = 0.28f), spotColor = Color.Black.copy(alpha = 0.34f))
            .clip(shape)
            .background(
                if (dark) {
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
                            Color.Black.copy(alpha = 0.08f),
                        ),
                    )
                } else {
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.75f),
                            Color.White.copy(alpha = 0.55f),
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                        ),
                    )
                },
            )
            .border(1.dp, Color.White.copy(alpha = if (dark) 0.20f else 0.55f), shape),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .blur(blur)
                .background(Color.White.copy(alpha = 0.035f)),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
        ) {
            content()
        }
    }
}

private fun Color.luminance(): Float = (0.299f * red + 0.587f * green + 0.114f * blue)

@Composable
fun Modifier.snapScreenBackground(): Modifier {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    return background(
        if (dark) {
            Brush.linearGradient(
                colors = listOf(
                    scheme.background,
                    scheme.background.copy(green = (scheme.background.green + 0.05f).coerceAtMost(1f)),
                    scheme.surface,
                    scheme.background,
                ),
            )
        } else {
            Brush.linearGradient(
                colors = listOf(
                    Color(0xFFE1EFFF),
                    Color(0xFFF9FBFF),
                    Color(0xFFEEE1FF),
                ),
            )
        },
    )
}
