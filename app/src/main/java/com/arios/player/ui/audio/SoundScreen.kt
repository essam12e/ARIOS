package com.arios.player.ui.audio

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arios.player.Arios
import com.arios.player.R
import com.arios.player.playback.AudioFx
import com.arios.player.playback.EqBandInfo
import com.arios.player.ui.AppNav
import com.arios.player.ui.theme.*
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private val FALLBACK_BANDS = listOf(60, 230, 910, 3600, 14000).map { EqBandInfo(it, -15f, 15f) }

@Composable
fun SoundScreen(nav: AppNav, bottomPadding: androidx.compose.ui.unit.Dp) {
    val s by AudioFx.settings.collectAsState()
    val deviceBands by AudioFx.bands.collectAsState()
    val bands = deviceBands.ifEmpty { FALLBACK_BANDS }
    @Suppress("UNUSED_VARIABLE") val v by Arios.prefs.version.collectAsState()
    val arabic = LocalConfiguration.current.locales[0].language == "ar"
    val gains = remember(s, bands) {
        if (s.bands.size == bands.size) s.bands else AudioFx.bandGains(s).ifEmpty { List(bands.size) { 0f } }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = bottomPadding + 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.sound), style = MaterialTheme.typography.headlineSmall.copy(brush = AriosColors.brand), modifier = Modifier.weight(1f))
            Switch(s.enabled, { AudioFx.update(s.copy(enabled = it)) }, colors = SwitchDefaults.colors(checkedTrackColor = AriosColors.Mint, checkedThumbColor = AriosColors.Black))
            Spacer(Modifier.width(12.dp))
        }

        // presets
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AudioFx.presets.forEach { p ->
                val sel = s.presetId == p.id && s.bands.isEmpty()
                Box(
                    Modifier.glass(CircleShape, fill = 0.06f).clip(CircleShape)
                        .background(AriosColors.brandHorizontal, alpha = if (sel) 1f else 0f)
                        .bounceClick { AudioFx.update(s.copy(enabled = true, presetId = p.id, bands = emptyList())) }
                        .padding(horizontal = 16.dp, vertical = 9.dp)
                ) { Text(if (arabic) p.nameAr else p.nameEn, color = if (sel) AriosColors.Black else AriosColors.TextPrimary, fontWeight = FontWeight.SemiBold) }
            }
            if (s.bands.isNotEmpty()) Box(Modifier.clip(CircleShape).background(AriosColors.brandHorizontal).padding(horizontal = 16.dp, vertical = 9.dp)) {
                Text(stringResource(R.string.custom), color = AriosColors.Black, fontWeight = FontWeight.SemiBold)
            }
        }

        // EQ curve
        com.arios.player.ui.player.Ltr { EqCurve(
            bands, gains, s.enabled,
            Modifier.fillMaxWidth().height(260.dp).padding(16.dp).glass(RoundedCornerShape(28.dp), glow = AriosColors.Mint.copy(alpha = 0.5f), fill = 0.05f).padding(vertical = 14.dp),
        ) { newGains -> AudioFx.update(s.copy(enabled = true, bands = newGains)) } }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { AudioFx.update(s.copy(presetId = "flat", bands = emptyList(), bass = 0, virtualizer = 0, loudness = 0)) }) { Text(stringResource(R.string.reset)) }
        }

        // knobs
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Knob(stringResource(R.string.bass), s.bass / 1000f, s.enabled) { AudioFx.update(s.copy(enabled = true, bass = (it * 1000).toInt())) }
            Knob(stringResource(R.string.surround), s.virtualizer / 1000f, s.enabled) { AudioFx.update(s.copy(enabled = true, virtualizer = (it * 1000).toInt())) }
            Knob(stringResource(R.string.loudness), s.loudness / 1500f, s.enabled) { AudioFx.update(s.copy(enabled = true, loudness = (it * 1500).toInt())) }
        }
        Text(stringResource(R.string.eq_hint), Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall, color = AriosColors.SilverDim)

        // playback options
        Text(stringResource(R.string.playback), Modifier.padding(start = 20.dp, top = 8.dp), style = MaterialTheme.typography.titleSmall, color = AriosColors.Mint)
        Column(Modifier.padding(16.dp).glass(RoundedCornerShape(24.dp)).padding(16.dp)) {
            val p = Arios.prefs
            var cf by remember { mutableFloatStateOf(p.crossfadeSec.toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.crossfade), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.crossfade_hint), style = MaterialTheme.typography.bodySmall, color = AriosColors.SilverDim)
                }
                Text(if (cf.toInt() == 0) stringResource(R.string.off) else stringResource(R.string.seconds_n, cf.toInt()), color = AriosColors.Mint)
            }
            Slider(cf, { cf = it }, valueRange = 0f..12f, steps = 11, onValueChangeFinished = { p.crossfadeSec = cf.toInt() })
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = AriosColors.SurfaceHighest)
            ToggleRow(stringResource(R.string.normalize), stringResource(R.string.normalize_hint), p.normalize) { p.normalize = it }
            ToggleRow(stringResource(R.string.resume_long), stringResource(R.string.resume_long_hint), p.resumeLong) { p.resumeLong = it }
            ToggleRow(stringResource(R.string.sleep_fade), stringResource(R.string.sleep_fade_hint), p.sleepFade) { p.sleepFade = it }
        }
    }
}

@Composable
private fun ToggleRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = AriosColors.SilverDim)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = AriosColors.Mint, checkedThumbColor = AriosColors.Black))
    }
}

/** Draggable, glowing EQ curve: drag any point up or down. */
@Composable
private fun EqCurve(bands: List<EqBandInfo>, gains: List<Float>, enabled: Boolean, modifier: Modifier, onChange: (List<Float>) -> Unit) {
    val live = remember(gains) { mutableStateListOf(*gains.toTypedArray()) }
    val animated = live.mapIndexed { i, g -> animateFloatAsState(g, spring(dampingRatio = 0.6f, stiffness = 400f), label = "g$i").value }
    val minDb = bands.minOf { it.minDb }; val maxDb = bands.maxOf { it.maxDb }
    val accent = AriosColors.Mint; val light = AriosColors.MintLight; val dim = AriosColors.Steel
    var dragging by remember { mutableIntStateOf(-1) }
    val alpha by animateFloatAsState(if (enabled) 1f else 0.35f, label = "en")
    val levels by rememberSpectrum()
    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 18.dp).pointerInput(bands) {
                fun idxFor(x: Float) = ((x / size.width) * bands.size).toInt().coerceIn(0, bands.size - 1)
                fun dbFor(y: Float) = (maxDb - (y / size.height) * (maxDb - minDb)).coerceIn(minDb, maxDb)
                detectDragGestures(
                    onDragStart = { o -> dragging = idxFor(o.x); live[dragging] = dbFor(o.y) },
                    onDragEnd = { dragging = -1; onChange(live.toList()) },
                    onDragCancel = { dragging = -1 },
                ) { ch, _ -> if (dragging >= 0) live[dragging] = (Math.round(dbFor(ch.position.y) * 2) / 2f) }
            }
        ) {
            val n = bands.size
            fun x(i: Int) = size.width * (i + 0.5f) / n
            fun y(db: Float) = size.height * (maxDb - db) / (maxDb - minDb)
            // live spectrum behind the curve
            val bw = size.width / levels.size
            levels.forEachIndexed { i, l ->
                drawRect(accent.copy(alpha = 0.10f * alpha), Offset(i * bw + 1, size.height * (1 - l)), androidx.compose.ui.geometry.Size(bw - 2, size.height * l))
            }
            // zero line + grid
            drawLine(dim.copy(alpha = 0.5f), Offset(0f, y(0f)), Offset(size.width, y(0f)), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
            for (i in 0 until n) drawLine(dim.copy(alpha = 0.25f), Offset(x(i), 0f), Offset(x(i), size.height), 1.5f)
            // smooth curve (Catmull-Rom → cubic)
            val pts = (0 until n).map { Offset(x(it), y(animated[it])) }
            val path = Path().apply {
                moveTo(0f, pts.first().y); lineTo(pts.first().x, pts.first().y)
                for (i in 0 until n - 1) {
                    val p0 = pts.getOrElse(i - 1) { pts[i] }; val p1 = pts[i]; val p2 = pts[i + 1]; val p3 = pts.getOrElse(i + 2) { p2 }
                    cubicTo(p1.x + (p2.x - p0.x) / 6, p1.y + (p2.y - p0.y) / 6, p2.x - (p3.x - p1.x) / 6, p2.y - (p3.y - p1.y) / 6, p2.x, p2.y)
                }
                lineTo(size.width, pts.last().y)
            }
            val fill = Path().apply { addPath(path); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
            drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = 0.45f * alpha), Color.Transparent)))
            drawPath(path, accent.copy(alpha = 0.35f * alpha), style = Stroke(16f))   // glow
            drawPath(path, Brush.horizontalGradient(listOf(light, accent)), alpha = alpha, style = Stroke(5f))
            pts.forEachIndexed { i, p ->
                val r = if (i == dragging) 18f else 12f
                drawCircle(accent.copy(alpha = 0.35f * alpha), r * 2, p)
                drawCircle(Color.White.copy(alpha = alpha), r, p)
                drawCircle(accent, r * 0.55f, p, alpha = alpha)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)) {
            bands.forEachIndexed { i, b ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(String.format(java.util.Locale.US, "%+.1f", live.getOrElse(i) { 0f }), fontSize = 11.sp, color = if (i == dragging) AriosColors.Mint else AriosColors.TextPrimary)
                    Text(if (b.centerHz >= 1000) "${b.centerHz / 1000}k" else "${b.centerHz}", fontSize = 10.sp, color = AriosColors.SilverDim)
                }
            }
        }
    }
}

/** Rotary knob: drag around it (or up/down) to change 0..1. */
@Composable
private fun Knob(label: String, value: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value) }
    val shown by animateFloatAsState(v, spring(dampingRatio = 0.7f, stiffness = 500f), label = "knob")
    val accent = AriosColors.Mint; val light = AriosColors.MintLight; val track = AriosColors.SurfaceHighest
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier.size(96.dp).glowHalo(enabled && v > 0.05f, accent.copy(alpha = 0.6f * v)).pointerInput(Unit) {
                detectDragGestures(onDragEnd = { onChange(v) }) { ch, drag ->
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val a = Math.toDegrees(atan2((ch.position.y - c.y).toDouble(), (ch.position.x - c.x).toDouble())).toFloat()
                    // 135° … 405° maps to 0 … 1
                    var deg = (a + 360) % 360
                    if (deg < 135) deg += 360
                    val byAngle = ((deg - 135) / 270f).coerceIn(0f, 1f)
                    v = if (kotlin.math.abs(drag.y) > kotlin.math.abs(drag.x) * 2) (v - drag.y / 400f).coerceIn(0f, 1f) else byAngle
                }
            }
        ) {
            val stroke = 10.dp.toPx()
            val inset = stroke
            val arcSize = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(track, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(Brush.sweepGradient(listOf(light, accent, light)), 135f, 270f * shown, false, Offset(inset, inset), arcSize,
                alpha = if (enabled) 1f else 0.4f, style = Stroke(stroke, cap = StrokeCap.Round))
            val ang = Math.toRadians((135 + 270 * shown).toDouble())
            val r = size.minDimension / 2 - inset * 2.6f
            drawCircle(Brush.radialGradient(listOf(AriosColors.SurfaceHighest, AriosColors.Surface)), size.minDimension / 2 - inset * 2)
            drawCircle(Color.White, 6.dp.toPx(), Offset(center.x + (r * cos(ang)).toFloat(), center.y + (r * sin(ang)).toFloat()))
        }
        Text("${(shown * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = AriosColors.Mint)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Suppress("unused") private val unusedPi = PI
