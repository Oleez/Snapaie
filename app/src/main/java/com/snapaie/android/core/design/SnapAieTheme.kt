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

private val Scheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF88F0D0),
    secondary = Color(0xFFFFD66B),
    tertiary = Color(0xFFAFC7FF),
    background = Color(0xFF0E1415),
    surface = Color(0xFF151B1D),
    surfaceVariant = Color(0xFF263135),
    onPrimary = Color(0xFF06211A),
    onSecondary = Color(0xFF2A2100),
    onBackground = Color(0xFFEAF3EF),
    onSurface = Color(0xFFEAF3EF),
    onSurfaceVariant = Color(0xFFB9C9C4),
    outline = Color(0xFF50605F),
)

@Composable
fun SnapAieTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        typography = SnapTypography,
        content = content,
    )
}

@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    radius: Dp = 8.dp,
    blur: Dp = 18.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier = modifier
            .shadow(18.dp, shape, ambientColor = Color.Black.copy(alpha = 0.28f), spotColor = Color.Black.copy(alpha = 0.34f))
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.18f),
                        Color(0xFF88F0D0).copy(alpha = 0.07f),
                        Color.Black.copy(alpha = 0.08f),
                    ),
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.20f), shape),
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

fun Modifier.snapScreenBackground(): Modifier = background(
    Brush.linearGradient(
        colors = listOf(
            Color(0xFF0E1415),
            Color(0xFF16221E),
            Color(0xFF151A24),
            Color(0xFF211A1A),
        ),
    ),
)
