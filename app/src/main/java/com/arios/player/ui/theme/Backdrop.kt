package com.arios.player.ui.theme

import android.os.Build
import androidx.annotation.OptIn
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.arios.player.Arios
import com.arios.player.artwork.artKey
import com.arios.player.data.Song
import com.arios.player.playback.Spectrum
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full-screen backdrop made of the album cover: heavily blurred, slowly zooming and drifting
 * (Ken-Burns), crossfading when the song changes, with a dark gradient for legibility.
 */
@Composable
fun CoverBackdrop(song: Song?, modifier: Modifier = Modifier, dim: Float = 0.55f) {
    val t = rememberInfiniteTransition(label = "kb")
    val phase by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(36000, easing = LinearEasing)), label = "kbp")
    val ctx = LocalContext.current
    Box(modifier.fillMaxSize().background(AriosColors.Black)) {
        Crossfade(song, animationSpec = tween(900, easing = Motion.EaseOutQuint), label = "backdrop") { s ->
            if (s != null) AsyncImage(
                model = ImageRequest.Builder(ctx).data(s.artKey()).size(if (Build.VERSION.SDK_INT >= 31) 300 else 48).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer {
                        val z = 1.35f + 0.12f * sin(phase)
                        scaleX = z; scaleY = z
                        translationX = size.width * 0.06f * cos(phase)
                        translationY = size.height * 0.04f * sin(phase * 2)
                        rotationZ = 4f * sin(phase)
                        alpha = 0.95f
                    }
                    .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(70.dp) else Modifier),
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            Color.Black.copy(alpha = dim * 0.6f), Color.Black.copy(alpha = dim), Color.Black.copy(alpha = (dim + 0.3f).coerceAtMost(0.95f)),
        ))))
    }
}

/** Reads the live spectrum every frame. */
@OptIn(UnstableApi::class)
@Composable
fun rememberSpectrum(): State<FloatArray> {
    val s = remember { mutableStateOf(FloatArray(Spectrum.BANDS)) }
    val enabled = Arios.prefs.visualizer
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        while (true) withFrameMillis { s.value = Spectrum.liveLevels() }
    }
    return s
}

@OptIn(UnstableApi::class)
@Composable
fun rememberEnergy(): State<Float> {
    val s = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { while (true) withFrameMillis { s.floatValue = Spectrum.liveEnergy() } }
    return s
}

/** Mirrored glowing spectrum bars (used under the cover and at the bottom of the lyrics). */
@Composable
fun VisualizerBars(modifier: Modifier, color: Color = AriosColors.Mint, mirrored: Boolean = true, bars: Int = 32) {
    if (!Arios.prefs.visualizer) return
    val levels by rememberSpectrum()
    Canvas(modifier) {
        val n = bars.coerceAtMost(levels.size)
        val gap = size.width / n * 0.35f
        val w = size.width / n - gap
        val mid = if (mirrored) size.height / 2 else size.height
        for (i in 0 until n) {
            // symmetric layout: lows in the centre
            val band = if (mirrored) kotlin.math.abs(i - n / 2) * 2 else i
            val v = levels[band.coerceIn(0, levels.size - 1)]
            val h = (mid * (0.06f + 0.94f * v)).coerceAtLeast(2f)
            val x = i * (w + gap) + gap / 2
            val brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.25f)), startY = mid - h, endY = mid + h)
            drawRoundRect(brush, Offset(x, mid - h), Size(w, if (mirrored) h * 2 else h), CornerRadius(w / 2, w / 2))
        }
    }
}

/** Circular spectrum ring (car mode / around the play button). */
@Composable
fun VisualizerRing(modifier: Modifier, color: Color = AriosColors.Mint) {
    if (!Arios.prefs.visualizer) return
    val levels by rememberSpectrum()
    val t = rememberInfiniteTransition(label = "ring")
    val rot by t.animateFloat(0f, 360f, infiniteRepeatable(tween(40000, easing = LinearEasing)), label = "rot")
    Canvas(modifier) {
        val r = size.minDimension / 2 * 0.72f
        val n = 64
        rotate(rot) {
            for (i in 0 until n) {
                val band = (if (i < n / 2) i else n - 1 - i) * levels.size / (n / 2)
                val v = levels[band.coerceIn(0, levels.size - 1)]
                val a = (i / n.toFloat()) * 2 * PI
                val len = size.minDimension / 2 * 0.26f * (0.12f + v)
                val s = Offset(center.x + (r * cos(a)).toFloat(), center.y + (r * sin(a)).toFloat())
                val e = Offset(center.x + ((r + len) * cos(a)).toFloat(), center.y + ((r + len) * sin(a)).toFloat())
                drawLine(color.copy(alpha = 0.35f + 0.65f * v), s, e, strokeWidth = 5f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
    }
}
