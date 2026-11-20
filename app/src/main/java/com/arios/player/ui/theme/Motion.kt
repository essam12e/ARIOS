package com.arios.player.ui.theme

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset

/**
 * ARIOS motion system: physics springs (no linear/abrupt tweens), GSAP-like
 * "power" easings for fades, and consistent enter/exit choreography.
 */
object Motion {
    /** easeOutQuint – the soft "power4.out" curve used for fades and scrolls. */
    val EaseOutQuint = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    val EaseInOutCubic = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

    fun <T> soft(visibility: T? = null) = spring(dampingRatio = 0.86f, stiffness = 260f, visibilityThreshold = visibility)
    fun <T> bouncy(visibility: T? = null) = spring(dampingRatio = 0.68f, stiffness = 320f, visibilityThreshold = visibility)
    val offsetSoft = spring(dampingRatio = 0.88f, stiffness = 240f, visibilityThreshold = IntOffset.VisibilityThreshold)
    val offsetSnappy = spring(dampingRatio = 0.9f, stiffness = 420f, visibilityThreshold = IntOffset.VisibilityThreshold)

    fun fadeInSoft(ms: Int = 420, delay: Int = 0) = fadeIn(tween(ms, delay, EaseOutQuint))
    fun fadeOutSoft(ms: Int = 260) = fadeOut(tween(ms, easing = EaseOutQuint))

    /** Now-playing sheet: rises from the mini player with a springy scale. */
    val playerEnter: EnterTransition =
        slideInVertically(offsetSoft) { it / 3 } + fadeInSoft(380) +
            scaleIn(soft(), initialScale = 0.88f, transformOrigin = TransformOrigin(0.5f, 1f))
    val playerExit: ExitTransition =
        slideOutVertically(tween(420, easing = EaseInOutCubic)) { it / 3 } + fadeOut(tween(340, easing = EaseInOutCubic)) +
            scaleOut(tween(420, easing = EaseInOutCubic), targetScale = 0.9f, transformOrigin = TransformOrigin(0.5f, 1f))

    /** Full-screen lyrics: blooms out of the lyrics card. */
    val lyricsEnter: EnterTransition =
        fadeInSoft(460) + scaleIn(soft(), initialScale = 0.82f, transformOrigin = TransformOrigin(0.5f, 0.85f))
    val lyricsExit: ExitTransition =
        fadeOut(tween(320, easing = EaseInOutCubic)) + scaleOut(tween(380, easing = EaseInOutCubic), targetScale = 0.86f, transformOrigin = TransformOrigin(0.5f, 0.85f))

    /** Page push/pop (depth) and tab switch (sideways) choreography. */
    fun page(forward: Boolean, rtl: Boolean): ContentTransform {
        val dir = (if (forward) 1 else -1) * (if (rtl) -1 else 1)
        return (slideInHorizontally(offsetSoft) { w -> dir * w / 4 } + fadeInSoft(420, 60) +
            scaleIn(soft(), initialScale = if (forward) 0.96f else 1.04f)) togetherWith
            (slideOutHorizontally(offsetSoft) { w -> -dir * w / 6 } + fadeOutSoft(240) +
                scaleOut(tween(380, easing = EaseOutQuint), targetScale = if (forward) 0.94f else 1.03f))
    }

    fun tab(forward: Boolean, rtl: Boolean): ContentTransform {
        val dir = (if (forward) 1 else -1) * (if (rtl) -1 else 1)
        return (slideInHorizontally(offsetSoft) { w -> dir * w / 10 } + fadeInSoft(380, 40)) togetherWith
            (slideOutHorizontally(offsetSoft) { w -> -dir * w / 14 } + fadeOutSoft(200))
    }
}

/**
 * Glides the list so item [index] rests [offsetPx] below the top, with an ease-out curve
 * instead of LazyList's jumpy default. Falls back to a jump + glide for far targets.
 */
suspend fun LazyListState.glideTo(index: Int, offsetPx: Int, durationMs: Int = 700) {
    val info = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (info != null) {
        val delta = (info.offset - offsetPx).toFloat()
        if (kotlin.math.abs(delta) > 1f) animateScrollBy(delta, tween(durationMs, easing = Motion.EaseOutQuint))
    } else {
        val near = if (index > firstVisibleItemIndex) (index - 3).coerceAtLeast(0) else index + 3
        scrollToItem(near.coerceAtMost(layoutInfo.totalItemsCount - 1).coerceAtLeast(0), -offsetPx)
        layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.let {
            animateScrollBy((it.offset - offsetPx).toFloat(), tween(durationMs, easing = Motion.EaseOutQuint))
        } ?: animateScrollToItem(index, -offsetPx)
    }
}
