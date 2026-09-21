package com.lumina.studio.core.design.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut

/**
 * Shared Lumina motion specs.
 *
 * EditorScreen gates its tool-panel transitions on the `animationsEnabled`
 * setting; that gate should adopt [LuminaMotion.shortFadeIn] /
 * [LuminaMotion.shortFadeOut] (150ms) for fades and [LuminaMotion.mediumTween]
 * (250ms) for sheet/dialog enter-exit so every surface animates consistently.
 * Call sites are intentionally not rewired here — new and migrated surfaces
 * (AppBottomSheet, AppDialog, ProSlider bubble) consume these constants.
 */
object LuminaMotion {
    const val ShortMillis = 150
    const val MediumMillis = 250

    val StandardEasing: Easing = FastOutSlowInEasing
    val EmphasizedEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    fun <T> shortTween(): androidx.compose.animation.core.FiniteAnimationSpec<T> =
        tween(durationMillis = ShortMillis, easing = StandardEasing)

    fun <T> mediumTween(): androidx.compose.animation.core.FiniteAnimationSpec<T> =
        tween(durationMillis = MediumMillis, easing = StandardEasing)

    fun shortFadeIn() = fadeIn(animationSpec = tween(ShortMillis, easing = StandardEasing))

    fun shortFadeOut() = fadeOut(animationSpec = tween(ShortMillis, easing = StandardEasing))

    fun mediumFadeIn() = fadeIn(animationSpec = tween(MediumMillis, easing = StandardEasing))

    fun mediumFadeOut() = fadeOut(animationSpec = tween(MediumMillis, easing = StandardEasing))
}
