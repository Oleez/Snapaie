package com.snapae.android.core.design

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val SnapSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

private val Scheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF97E7D3),
    secondary = Color(0xFFF0C36A),
    tertiary = Color(0xFFB6C8FF),
    background = Color(0xFF101416),
    surface = Color(0xFF161D20),
    surfaceVariant = Color(0xFF263034),
    onPrimary = Color(0xFF06201A),
    onSecondary = Color(0xFF2A1A00),
    onBackground = Color(0xFFE8F0ED),
    onSurface = Color(0xFFE8F0ED),
    onSurfaceVariant = Color(0xFFC4D0CC),
)

@Composable
fun SnapAeTheme(content: @Composable () -> Unit) {
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
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.16f),
                        Color.White.copy(alpha = 0.07f),
                    ),
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .blur(blur)
                .background(Color.White.copy(alpha = 0.03f)),
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
            Color(0xFF101416),
            Color(0xFF17211F),
            Color(0xFF171C24),
        ),
    ),
)
