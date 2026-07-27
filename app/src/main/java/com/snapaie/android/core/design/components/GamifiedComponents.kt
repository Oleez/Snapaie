package com.snapaie.android.core.design.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.snapaie.android.core.design.DesignTokens
import com.snapaie.android.core.design.Motion
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Ambient rising bubbles — the extension's signature background motion
 * (.ae-ambient-bubbles): soft radial-gradient circles, 12-36dp, rising on
 * 11-24s linear loops. `mode`: on | slower | faster | off.
 */
@Composable
fun AmbientBubbles(
    modifier: Modifier = Modifier,
    mode: String = "on",
    bubbleColor: Color = Color.White,
    edgeColor: Color = Color(0xFF818CF8),
) {
    if (mode == "off") return
    val speedFactor = when (mode) {
        "slower" -> 2.05f
        "faster" -> 0.62f
        else -> 1f
    }
    data class Bubble(val x: Float, val size: Float, val durationMs: Int, val delayFraction: Float)
    val bubbles = remember {
        val rng = Random(42)
        List(7) {
            Bubble(
                x = rng.nextFloat() * 0.92f + 0.04f,
                size = rng.nextFloat() * 24f + 12f,
                durationMs = (rng.nextInt(11_000, 24_000)),
                delayFraction = rng.nextFloat(),
            )
        }
    }
    val transition = rememberInfiniteTransition(label = "bubbles")
    val progressions = bubbles.map { bubble ->
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween((bubble.durationMs * speedFactor).roundToInt(), easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
                initialStartOffset = androidx.compose.animation.core.StartOffset(
                    (bubble.durationMs * bubble.delayFraction).roundToInt(),
                ),
            ),
            label = "bubble",
        )
    }
    Canvas(modifier = modifier.fillMaxSize()) {
        bubbles.forEachIndexed { index, bubble ->
            val t = progressions[index].value
            val cy = size.height + 38f - t * (size.height + 120f)
            val alpha = when {
                t < 0.4f -> 0.35f + t * 0.5f
                else -> (0.55f * (1f - (t - 0.4f) / 0.6f)).coerceAtLeast(0f)
            }
            val radius = bubble.size.dp.toPx() / 2f * (1f + 0.15f * t)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        bubbleColor.copy(alpha = 0.48f * alpha),
                        edgeColor.copy(alpha = 0.14f * alpha),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * bubble.x, cy),
                    radius = radius * 1.4f,
                ),
                radius = radius,
                center = Offset(size.width * bubble.x, cy),
            )
        }
    }
}

/**
 * Animated XP bar: indigo->purple->pink->orange gradient with a moving shine,
 * springy width per the extension's overshoot easing.
 */
@Composable
fun XpBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp,
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = Motion.overshoot(450),
        label = "xpWidth",
    )
    val shine = rememberInfiniteTransition(label = "xpShine")
    val offset by shine.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing)),
        label = "xpShineOffset",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xA60F172A)),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            val width = size.width * animated
            if (width > 0f) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            DesignTokens.XpIndigo, DesignTokens.XpPurple,
                            DesignTokens.XpPink, DesignTokens.XpOrange, DesignTokens.XpIndigo,
                        ),
                        startX = -size.width + 2 * size.width * offset,
                        endX = size.width + 2 * size.width * offset,
                        tileMode = androidx.compose.ui.graphics.TileMode.Mirror,
                    ),
                    size = size.copy(width = width),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(999f, 999f),
                )
            }
        }
    }
}

/**
 * Knowledge-map strength ring (extension's .lockin-ring): circular arc showing
 * topic strength 0-100 with due-now/due-soon ring states.
 */
@Composable
fun StrengthRing(
    strength: Int,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    dueNow: Boolean = false,
    dueSoon: Boolean = false,
) {
    val sweep by animateFloatAsState(
        targetValue = strength.coerceIn(0, 100) / 100f * 360f,
        animationSpec = Motion.standard(500),
        label = "ring",
    )
    val ringColor = when {
        dueNow -> DesignTokens.DueRed
        dueSoon -> DesignTokens.DueAmber
        strength >= 78 -> DesignTokens.SuccessGreen
        strength >= 45 -> DesignTokens.XpIndigo
        else -> DesignTokens.XpPink
    }
    Canvas(modifier = modifier.height(size).fillMaxWidth()) {
        val strokeWidth = 5.dp.toPx()
        val diameter = minOf(this.size.width, this.size.height) - strokeWidth
        val topLeft = Offset(
            (this.size.width - diameter) / 2f,
            (this.size.height - diameter) / 2f,
        )
        drawArc(
            color = Color(0xFF33415A),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = androidx.compose.ui.geometry.Size(diameter, diameter),
            style = Stroke(strokeWidth, cap = StrokeCap.Round),
        )
        rotate(-90f, pivot = Offset(this.size.width / 2f, this.size.height / 2f)) {
            drawArc(
                color = ringColor,
                startAngle = 0f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = androidx.compose.ui.geometry.Size(diameter, diameter),
                style = Stroke(strokeWidth, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * Confetti overlay: 56 falling pieces in the extension's 8-color array.
 * Render only while [visible]; pieces fall 2.2-3.4s with rotation.
 */
@Composable
fun ConfettiOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    data class Piece(val x: Float, val color: Color, val durationMs: Int, val delayMs: Int, val drift: Float)
    val pieces = remember {
        val rng = Random(System.currentTimeMillis())
        List(56) {
            Piece(
                x = rng.nextFloat(),
                color = DesignTokens.ConfettiColors[rng.nextInt(DesignTokens.ConfettiColors.size)],
                durationMs = rng.nextInt(2200, 3400),
                delayMs = rng.nextInt(0, 500),
                drift = rng.nextFloat() * 0.2f - 0.1f,
            )
        }
    }
    val transition = rememberInfiniteTransition(label = "confetti")
    val fall = pieces.map { piece ->
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(piece.durationMs, delayMillis = piece.delayMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "confettiFall",
        )
    }
    Canvas(modifier = modifier.fillMaxSize()) {
        pieces.forEachIndexed { index, piece ->
            val t = fall[index].value
            if (t <= 0f) return@forEachIndexed
            val x = size.width * (piece.x + piece.drift * t)
            val y = -20f + t * (size.height + 40f)
            rotate(720f * t, pivot = Offset(x, y)) {
                drawRoundRect(
                    color = piece.color.copy(alpha = (1f - t * 0.4f).coerceIn(0f, 1f)),
                    topLeft = Offset(x, y),
                    size = androidx.compose.ui.geometry.Size(8.dp.toPx(), 12.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                )
            }
        }
    }
}
