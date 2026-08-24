package com.snapaie.android.core.design.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snapaie.android.core.design.LocalAccent

/**
 * The app's name, set to actually be seen.
 *
 * It used to be body-sized text in the same colour as everything around it, which meant the
 * product had no face at all. Here it is display-weight, filled with the accent gradient,
 * and the gradient drifts slowly so the header has a little life in it without becoming
 * something that competes with the content underneath.
 */
@Composable
fun BrandWordmark(
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    fontSize: Int = 34,
    animated: Boolean = true,
) {
    val accent = LocalAccent.current
    val transition = rememberInfiniteTransition(label = "wordmark")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7_000), RepeatMode.Reverse),
        label = "wordmarkShift",
    )
    val travel = if (animated) shift else 0.5f

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "snapaie",
            style = TextStyle(
                brush = Brush.linearGradient(
                    colors = listOf(accent.primary, accent.tertiary, accent.secondary, accent.primary),
                    start = Offset(travel * 260f - 130f, 0f),
                    end = Offset(travel * 260f + 430f, 90f),
                ),
                fontSize = fontSize.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1).sp,
            ),
        )
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The wordmark centred, for splash-like moments. */
@Composable
fun BrandLockup(modifier: Modifier = Modifier, tagline: String? = null) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandWordmark(fontSize = 42)
        }
        tagline?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
