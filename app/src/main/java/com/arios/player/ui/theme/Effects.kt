package com.arios.player.ui.theme

import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/**
 * Living "aurora" backdrop: soft emerald / mint / teal light blobs drifting slowly over the
 * deep green-black of the logo. [tint] mixes in the colour of the current album art.
 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, tint: Color? = null, intensity: Float = 1f) {
    val level = if (AriosColors.amoled) intensity * 0.45f else intensity
    val t = rememberInfiniteTransition(label = "aurora")
    val phase by t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(22000, easing = LinearEasing)), label = "ph")
    val c1 = tint?.let { lerp(it, AriosColors.Mint, 0.25f) } ?: AriosColors.Mint
    val c2 = tint?.let { lerp(it, AriosColors.Emerald, 0.5f) } ?: AriosColors.Emerald
    val c3 = AriosColors.Teal
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(lerp(AriosColors.Emerald, Color.Black, if (AriosColors.amoled) 0.93f else 0.84f), AriosColors.Background, AriosColors.Black)))
        fun blob(c: Color, cx: Float, cy: Float, r: Float, a: Float) = drawCircle(
            Brush.radialGradient(listOf(c.copy(alpha = a * level), Color.Transparent), center = Offset(cx, cy), radius = r),
            radius = r, center = Offset(cx, cy),
        )
        val w = size.width; val h = size.height
        blob(c1, w * (0.2f + 0.15f * sin(phase)), h * (0.12f + 0.06f * cos(phase * 2)), w * 0.9f, 0.22f)
        blob(c2, w * (0.85f + 0.1f * cos(phase)), h * (0.45f + 0.08f * sin(phase)), w * 0.8f, 0.18f)
        blob(c3, w * (0.35f + 0.2f * cos(phase + 1.3f)), h * (0.88f + 0.05f * sin(phase * 1.5f)), w * 0.85f, 0.14f)
    }
}

/** Frosted glass surface with a gradient rim and an optional emerald glow. */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(24.dp),
    glow: Color? = null,
    glowRadius: Dp = 18.dp,
    fill: Float = 0.07f,
    tint: Color = Color.White,
): Modifier = this
    .then(if (glow != null) Modifier.softGlow(shape, glow, glowRadius) else Modifier)
    .background(Brush.verticalGradient(listOf(tint.copy(alpha = fill + 0.04f), tint.copy(alpha = fill * 0.5f))), shape)
    .border(
        1.dp,
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.28f), AriosColors.Mint.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f))),
        shape,
    )

/** Soft pulsing halo behind round buttons (e.g. play) while [active]. */
fun Modifier.glowHalo(active: Boolean, color: Color = AriosColors.Mint): Modifier = composed {
    val t = rememberInfiniteTransition(label = "halo")
    val pulse by t.animateFloat(0.75f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p")
    val strength by animateFloatAsState(if (active) 1f else 0.35f, tween(600), label = "s")
    drawBehind {
        val r = size.minDimension / 2f * (1.25f + 0.25f * pulse * strength)
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.55f * strength), Color.Transparent), center = center, radius = r), radius = r)
    }
}

/** Springy press feedback (scale down on touch, bounce back). */
fun Modifier.bounceClick(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(dampingRatio = 0.45f, stiffness = 600f), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(source, indication = null, enabled = enabled, onClick = onClick)
}

/** Staggered entrance (fade + rise + slight scale), GSAP "stagger" style. */
fun Modifier.staggerIn(index: Int, step: Long = 35L): Modifier = composed {
    if (index > 14) return@composed this
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * step)
        p.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 220f))
    }
    graphicsLayer {
        alpha = p.value.coerceIn(0f, 1f)
        translationY = (1f - p.value) * 46f
        val s = 0.96f + 0.04f * p.value
        scaleX = s; scaleY = s
    }
}

/** Animated equalizer bars shown next to the song that is playing. */
@Composable
fun EqualizerBars(playing: Boolean, modifier: Modifier = Modifier, color: Color = AriosColors.Mint) {
    val t = rememberInfiniteTransition(label = "eq")
    val bars = listOf(520, 380, 640).map { d ->
        t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(d, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b$d")
    }
    Canvas(modifier) {
        val bw = size.width / 5f
        bars.forEachIndexed { i, b ->
            val hgt = size.height * (if (playing) b.value else 0.3f)
            drawRoundRect(
                color, topLeft = Offset(bw * (i * 2), size.height - hgt),
                size = androidx.compose.ui.geometry.Size(bw, hgt),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw / 2, bw / 2),
            )
        }
    }
}

/** Blurred glow copy behind an element (used under album art). */
@Composable
fun GlowBehind(modifier: Modifier, color: Color, content: @Composable () -> Unit) {
    Box(modifier) {
        if (Build.VERSION.SDK_INT >= 31) Box(
            Modifier.fillMaxSize().graphicsLayer { scaleX = 0.92f; scaleY = 0.92f; translationY = 28f }
                .blur(48.dp).background(color.copy(alpha = 0.55f), RoundedCornerShape(28.dp))
        )
        content()
    }
}

/**
 * Soft outer glow drawn as stacked, fading outlines of [shape] – no shadow/offscreen layer,
 * so content inside never gets darkened boxes.
 */
fun Modifier.softGlow(shape: Shape, color: Color, radius: Dp): Modifier = drawBehind {
    val steps = 6
    val r = radius.toPx()
    for (i in steps downTo 1) {
        val grow = r * i / steps
        val outline = shape.createOutline(
            androidx.compose.ui.geometry.Size(size.width + grow * 2, size.height + grow * 2), layoutDirection, this,
        )
        translate(-grow, -grow + r * 0.15f) {
            drawOutline(outline, color.copy(alpha = color.alpha * 0.07f * (1f - (i - 1f) / steps)))
        }
    }
}
