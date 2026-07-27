package com.snapaie.android.core.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Motion grammar ported from the extension: standard easing
 * cubic-bezier(0.4,0,0.2,1) for surfaces, overshoot cubic-bezier(0.34,1.56,0.64,1)
 * for gamified elements, 150/250/350ms duration scale.
 */
object Motion {
    val Standard = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    val Overshoot = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

    const val Fast = 150
    const val Medium = 250
    const val Slow = 350
    const val Reveal = 500

    fun <T> standard(duration: Int = Medium) = tween<T>(duration, easing = Standard)
    fun <T> overshoot(duration: Int = Slow) = tween<T>(duration, easing = Overshoot)

    val PressSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium,
    )
}
