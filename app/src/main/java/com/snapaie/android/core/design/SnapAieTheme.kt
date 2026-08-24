package com.snapaie.android.core.design

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithCache
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
    background = Color(0xFF121A1B),
    surface = Color(0xFF1A2223),
    surfaceVariant = Color(0xFF2A3639),
    surfaceContainerHigh = Color(0xFF243032),
    onPrimary = Color(0xFF06211A),
    onSecondary = Color(0xFF2A2100),
    onBackground = Color(0xFFEAF3EF),
    onSurface = Color(0xFFEAF3EF),
    onSurfaceVariant = Color(0xFFC3D3CE),
    outline = Color(0xFF6C7F7D),
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
    background = Color(0xFF141830),
    surface = Color(0xFF1E2338),
    surfaceVariant = Color(0xFF2F3550),
    surfaceContainerHigh = Color(0xFF29304A),
    onPrimary = Color(0xFF141838),
    onSecondary = Color(0xFF33101F),
    onBackground = Color(0xFFECEEFB),
    onSurface = Color(0xFFECEEFB),
    onSurfaceVariant = Color(0xFFC6CBE6),
    outline = Color(0xFF6E77A0),
)

/** The accent in force, so any component can tint itself without threading it through. */
val LocalAccent = staticCompositionLocalOf { AccentPalette.Mint }

@Composable
fun SnapAieTheme(
    mode: ThemeMode = ThemeMode.SnapDark,
    accent: AccentPalette = AccentPalette.Mint,
    textScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val base = when (mode) {
        ThemeMode.SnapDark -> SnapDarkScheme
        ThemeMode.Light -> LightScheme
        ThemeMode.Aurora -> AuroraScheme
    }

    // The accent replaces the hues but never the grounds. Backgrounds and text stay under
    // the theme's control so a bright accent cannot make the app unreadable.
    val dark = base.background.luminance() < 0.5f
    val scheme = base.copy(
        primary = accent.primary,
        secondary = accent.secondary,
        tertiary = accent.tertiary,
        onPrimary = if (dark) Color(0xFF0A1512) else contrastOn(accent.primary),
        onSecondary = if (dark) Color(0xFF1E1704) else contrastOn(accent.secondary),
    )

    // Animated so switching accent glides rather than snapping, which is most of why a
    // colour picker feels considered instead of like a debug toggle.
    val primary by animateColorAsState(scheme.primary, tween(420), label = "accentPrimary")
    val secondary by animateColorAsState(scheme.secondary, tween(420), label = "accentSecondary")
    val tertiary by animateColorAsState(scheme.tertiary, tween(420), label = "accentTertiary")

    CompositionLocalProvider(LocalAccent provides accent) {
        MaterialTheme(
            colorScheme = scheme.copy(primary = primary, secondary = secondary, tertiary = tertiary),
            typography = snapTypography(textScale),
            content = content,
        )
    }
}

/** Black or white, whichever stays legible on [color]. */
private fun contrastOn(color: Color): Color =
    if (color.luminance() > 0.55f) Color(0xFF14181C) else Color.White

@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    radius: Dp = DesignTokens.RadiusMd,
    blur: Dp = 18.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    /** Lifts the sheet: stronger highlight, deeper shadow. For things that should lead. */
    elevated: Boolean = false,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    val scheme = MaterialTheme.colorScheme
    val accent = LocalAccent.current
    val dark = scheme.background.luminance() < 0.5f

    Box(
        modifier = modifier
            .shadow(
                elevation = if (elevated) 30.dp else 18.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.30f),
                spotColor = if (dark) accent.primary.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.30f),
            )
            .clip(shape)
            // The body of the material: translucent, tinted by the accent, darkening down
            // the sheet so it reads as something with thickness rather than a flat panel.
            .background(
                Brush.verticalGradient(
                    if (dark) {
                        listOf(
                            Color.White.copy(alpha = if (elevated) 0.16f else 0.11f),
                            accent.primary.copy(alpha = 0.055f),
                            Color.Black.copy(alpha = 0.16f),
                        )
                    } else {
                        listOf(
                            Color.White.copy(alpha = 0.92f),
                            Color.White.copy(alpha = 0.72f),
                            accent.primary.copy(alpha = 0.07f),
                        )
                    },
                ),
            ),
    ) {
        // Blurred backdrop layer, so content behind the sheet reads as diffused.
        Box(
            Modifier
                .matchParentSize()
                .blur(blur)
                .background(Color.White.copy(alpha = if (dark) 0.03f else 0.05f)),
        )

        // Specular edge. Apple's glass reads as glass mostly because of this: a bright
        // catch along the top lip that fades before the bottom, so light appears to be
        // landing on a curved surface rather than being painted on a rectangle.
        Box(
            Modifier
                .matchParentSize()
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (dark) 0.55f else 0.95f),
                            Color.White.copy(alpha = if (dark) 0.12f else 0.40f),
                            Color.White.copy(alpha = 0.02f),
                        ),
                    ),
                    shape = shape,
                ),
        )

        // A short sheen across the top, the reflection of a light source above.
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (elevated) 56.dp else 40.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (dark) 0.10f else 0.34f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        // A raw Box does not set LocalContentColor the way Surface does, so any Text
        // inside falls back to black — invisible on a dark sheet. Providing it here is
        // what stops content having to remember to colour itself.
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
            Box(Modifier.fillMaxWidth().padding(contentPadding)) { content() }
        }
    }
}

/**
 * A page, not a card.
 *
 * Condensed prose is something to *read*, and reading wants the shape of a page: a bright
 * ground, generous margins, a serif face and a soft drop under the sheet. Presenting it in
 * the same translucent chrome as every control makes it look like output rather than
 * something written.
 */
@Composable
fun PaperSheet(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 22.dp, vertical = 26.dp),
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(DesignTokens.RadiusMd)
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // Warm off-white in light, a soft charcoal in dark: paper under lamplight either way,
    // never pure white burning out of a dark screen.
    val paper = if (dark) Color(0xFF1B1E1F) else Color(0xFFFCFBF7)
    // Ink chosen against the paper, not against the app background: this sheet is the one
    // surface whose ground does not follow the theme, so its text cannot either.
    val ink = if (dark) Color(0xFFE8EDEA) else Color(0xFF15191B)

    Box(
        modifier = modifier
            .shadow(22.dp, shape, ambientColor = Color.Black.copy(alpha = 0.24f))
            .clip(shape)
            .background(paper)
            .border(1.dp, Color.White.copy(alpha = if (dark) 0.06f else 0.85f), shape),
    ) {
        CompositionLocalProvider(LocalContentColor provides ink) {
            Box(Modifier.fillMaxWidth().padding(contentPadding)) { content() }
        }
    }
}

private fun Color.luminance(): Float = (0.299f * red + 0.587f * green + 0.114f * blue)

@Composable
fun Modifier.snapScreenBackground(): Modifier {
    val scheme = MaterialTheme.colorScheme
    val accent = LocalAccent.current
    val dark = scheme.background.luminance() < 0.5f

    // Two washes of the accent bled in from opposite corners over the base ground. A flat
    // fill is most of why an app reads as dated: there is nothing for the glass above it to
    // refract, so every sheet looks pasted on rather than floating over anything.
    val transition = rememberInfiniteTransition(label = "ground")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(18_000), RepeatMode.Reverse),
        label = "groundDrift",
    )

    return this
        .background(if (dark) scheme.background else Color(0xFFF6F7FB))
        // Washes sized to the surface rather than to fixed pixel offsets. With hard-coded
        // radii the middle of a tall screen fell outside every gradient and went flat
        // black, which is why long screens like Settings looked switched off.
        .drawWithCache {
            val warm = Brush.radialGradient(
                colors = listOf(
                    accent.primary.copy(alpha = if (dark) 0.26f else 0.22f),
                    Color.Transparent,
                ),
                center = Offset(size.width * (0.15f + drift * 0.25f), size.height * 0.06f),
                radius = size.maxDimension * 0.75f,
            )
            val cool = Brush.radialGradient(
                colors = listOf(
                    accent.tertiary.copy(alpha = if (dark) 0.22f else 0.20f),
                    Color.Transparent,
                ),
                center = Offset(size.width * (0.9f - drift * 0.2f), size.height * 0.62f),
                radius = size.maxDimension * 0.8f,
            )
            val lift = Brush.radialGradient(
                colors = listOf(
                    accent.secondary.copy(alpha = if (dark) 0.14f else 0.12f),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.45f, size.height * 1.02f),
                radius = size.maxDimension * 0.6f,
            )
            onDrawBehind {
                drawRect(warm)
                drawRect(cool)
                drawRect(lift)
            }
        }
}
