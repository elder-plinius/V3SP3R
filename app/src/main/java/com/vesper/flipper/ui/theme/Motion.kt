package com.vesper.flipper.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Vesper motion tokens.
 *
 * Rule of thumb: navigation 220ms, content reveal 320ms, press 90ms,
 * and every motion is interruptible. Infinite loops are reserved for
 * "scanning" and "connected", and are paused when off-screen.
 */
object VesperMotion {
    const val instant = 90
    const val quick = 150
    const val standardMs = 220
    const val emphasized = 320
    const val slow = 600

    val standardEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val enterEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val exitEasing: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    fun <T> quick(): FiniteAnimationSpec<T> = tween(quick, easing = standardEasing)
    fun <T> standard(): FiniteAnimationSpec<T> = tween(standardMs, easing = standardEasing)
    fun <T> emphasized(): FiniteAnimationSpec<T> = tween(emphasized, easing = enterEasing)

    val press: FiniteAnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = 900f
    )
    val settle: FiniteAnimationSpec<Float> = spring(
        dampingRatio = 0.75f,
        stiffness = 350f
    )
}

/**
 * Spacing scale. No other dp values for layout gaps.
 */
object VesperSpace {
    val xxs: Dp = 4.dp
    val xs: Dp = 8.dp
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val lg: Dp = 24.dp
    val xl: Dp = 32.dp
    val xxl: Dp = 48.dp
    val xxxl: Dp = 64.dp

    /** Screen gutter for phone layouts. */
    val gutter: Dp = 20.dp

    /** Card inner padding. */
    val cardPadding: Dp = 16.dp

    /** Minimum touch target for any interactive element. */
    val touchTarget: Dp = 48.dp
}
