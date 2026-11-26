package com.arios.player.ui.remix

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.arios.player.Arios
import com.arios.player.R
import com.arios.player.artwork.artKey
import com.arios.player.data.MediaScanner
import com.arios.player.remix.*
import com.arios.player.ui.AppNav
import com.arios.player.ui.components.Artwork
import com.arios.player.ui.components.formatTime
import com.arios.player.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun RemixScreen(nav: AppNav, bottomPadding: Dp) {
    val s = Arios.remix
    val context = LocalContext.current
    val a by s.a.collectAsState()
    val b by s.b.collectAsState()
    val plan by s.plan.collectAsState()
    val work by s.work.collectAsState()
    val previewing by s.previewing.collectAsState()
    val projects by s.projects.collectAsState()
    var picking by remember { mutableStateOf<Char?>(null) }
    var saving by remember { mutableStateOf(false) }
    var fileFor by remember { mutableStateOf('A') }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()?.substringBeforeLast('.') ?: "Audio"
            s.setDeck(fileFor, DeckSource(uri, name, MediaScanner.UNKNOWN))
        }
    }

    // playhead of the preview (output seconds)
    var playhead by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(previewing) {
        while (previewing) { val f = s.previewFrame(); playhead = if (f >= 0) f / StereoPcm.RATE.toDouble() else null; delay(33) }
        playhead = null
    }
    DisposableEffect(Unit) { onDispose { s.stopPreview() } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = bottomPadding + 96.dp)) {
            // ---- header
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.remix), style = MaterialTheme.typography.headlineMedium.copy(brush = AriosColors.brand))
                    Text(stringResource(R.string.remix_sub), style = MaterialTheme.typography.bodySmall, color = AriosColors.SilverDim)
                }
                if (a.source != null || b.source != null) FilledTonalIconButton(onClick = { s.newRemix() }) { Icon(Icons.Filled.Add, stringResource(R.string.new_remix)) }
            }
            Spacer(Modifier.height(14.dp))

            // ---- decks
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                DeckCard(stringResource(R.string.deck_a), a, AriosColors.Mint, Modifier.weight(1f)) { picking = 'A' }
                Box(Modifier.padding(horizontal = 6.dp).size(40.dp).clip(CircleShape).glass(CircleShape).bounceClick { s.swap() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.SwapHoriz, stringResource(R.string.swap), tint = AriosColors.MintLight)
                }
                DeckCard(stringResource(R.string.deck_b), b, AriosColors.Teal, Modifier.weight(1f)) { picking = 'B' }
            }

            val p = plan
            val t = remember(p, a.analysis, b.analysis) { s.timing() }
            AnimatedVisibility(p != null && t != null, enter = fadeIn(tween(400)) + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                if (p != null && t != null) Column {
                    Compatibility(t)
                    Section(stringResource(R.string.timeline), trailing = stringResource(R.string.total_len, formatTime((t.total * 1000).toLong())))
                    Timeline(t, playhead, Modifier.padding(horizontal = 14.dp))
                    Section(stringResource(R.string.transition_zone), trailing = stringResource(R.string.beats_n, p.lengthBeats))
                    TransitionView(t, playhead?.let { (it - t.introLen) / t.duration }, Modifier.padding(horizontal = 14.dp))
                    Controls(t)
                }
            }
            if (a.source == null && b.source == null && projects.isEmpty()) {
                Text(stringResource(R.string.remix_empty_hint), Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center, color = AriosColors.SilverDim)
            }

            // ---- saved remixes
            if (projects.isNotEmpty()) {
                Section(stringResource(R.string.my_remixes))
                projects.forEachIndexed { i, pr ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).staggerIn(i).glass(RoundedCornerShape(18.dp))
                            .bounceClick { s.open(pr) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.GraphicEq, null, tint = AriosColors.Mint)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(pr.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${pr.a.title} × ${pr.b.title}", style = MaterialTheme.typography.bodySmall, color = AriosColors.SilverDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        pr.output?.let { out ->
                            IconButton(onClick = { playOutput(out, pr.name); nav.playerOpen = true }) { Icon(Icons.Filled.PlayArrow, stringResource(R.string.play)) }
                        }
                        IconButton(onClick = { s.deleteProject(pr.id) }) { Icon(Icons.Filled.Close, null, tint = AriosColors.SilverDim) }
                    }
                }
            }
        }

        // ---- action bar
        AnimatedVisibility(plan != null, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding + 6.dp),
            enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            Row(
                Modifier.padding(horizontal = 14.dp).fillMaxWidth().background(AriosColors.Black.copy(alpha = 0.85f), RoundedCornerShape(26.dp))
                    .glass(RoundedCornerShape(26.dp), glow = AriosColors.Mint.copy(alpha = 0.35f)).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                val w = work
                Button(onClick = { s.previewTransition() }, enabled = w == null || previewing, modifier = Modifier.weight(1.4f).height(50.dp)) {
                    when {
                        w?.first == "preview" -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = AriosColors.Black)
                        previewing -> { Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.stop)) }
                        else -> { Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.preview), maxLines = 1) }
                    }
                }
                FilledTonalButton(onClick = { s.reAuto() }, enabled = w == null, modifier = Modifier.height(50.dp)) {
                    Icon(Icons.Filled.AutoFixHigh, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.auto_mix))
                }
                FilledTonalButton(onClick = { saving = true }, enabled = w == null, modifier = Modifier.height(50.dp)) { Icon(Icons.Filled.Save, stringResource(R.string.save_remix)) }
            }
        }

        // ---- export progress
        val w = work
        if (w?.first == "export") {
            Box(Modifier.fillMaxSize().background(AriosColors.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                Column(Modifier.padding(32.dp).glass(RoundedCornerShape(28.dp), glow = AriosColors.Mint).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(progress = { w.second }, Modifier.size(72.dp), strokeWidth = 6.dp, color = AriosColors.Mint)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.exporting, (w.second * 100).roundToInt()), fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    picking?.let { which ->
        SongPickerSheet(onDismiss = { picking = null }, onSong = { song -> s.setDeck(which, DeckSource.of(song)); picking = null },
            onFile = { fileFor = which; picking = null; fileLauncher.launch(arrayOf("audio/*")) })
    }
    if (saving) {
        var name by remember { mutableStateOf(listOfNotNull(a.source?.title, b.source?.title).joinToString(" × ") + " (Flowfy Remix)") }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text(stringResource(R.string.save_remix)) },
            text = { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.remix_name)) }, singleLine = true) },
            confirmButton = {
                Button(onClick = {
                    saving = false
                    s.export(name.ifBlank { "Flowfy Remix" }) { uri ->
                        nav.snack = context.getString(if (uri != null) R.string.remix_saved else R.string.remix_failed)
                    }
                }) { Text(stringResource(R.string.save_remix)) }
            },
            dismissButton = { TextButton(onClick = { saving = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun playOutput(uri: String, name: String) {
    val lib = Arios.library.state.value
    val song = lib.songs.firstOrNull { it.uri.toString() == uri }
    if (song != null) { Arios.player.playSongs(listOf(song)); return }
    val u = Uri.parse(uri)
    Arios.player.playExternal(MediaItem.Builder().setMediaId("uri:$u").setUri(u)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(u).build())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(name).build()).build())
}

@Composable
private fun Section(title: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, color = AriosColors.SilverDim)
    }
}

// ---------------------------------------------------------------------------------------------- decks

@Composable
private fun DeckCard(label: String, deck: Deck, color: Color, modifier: Modifier, onPick: () -> Unit) {
    val src = deck.source
    val lib by Arios.library.state.collectAsState()
    val song = src?.songId?.let { lib.byId[it] }
    Column(
        modifier.height(212.dp).clip(RoundedCornerShape(24.dp))
            .then(if (src == null) Modifier.border(1.5.dp, color.copy(alpha = 0.5f), RoundedCornerShape(24.dp)) else Modifier)
            .glass(RoundedCornerShape(24.dp), glow = if (deck.status == DeckStatus.READY) color.copy(alpha = 0.35f) else null)
            .bounceClick(onClick = onPick).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (src == null) {
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(64.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, null, tint = color, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.pick_song), color = AriosColors.SilverDim)
            Spacer(Modifier.weight(1f))
            return@Column
        }
        Box(contentAlignment = Alignment.Center) {
            val spin = rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "r")
            Box(Modifier.size(78.dp).graphicsLayerRotate(if (deck.status == DeckStatus.ANALYSING) spin.value else 0f)) {
                Artwork(song?.artKey(), Modifier.fillMaxSize(), CircleShape)
            }
            Box(Modifier.size(14.dp).clip(CircleShape).background(AriosColors.Black).border(2.dp, color, CircleShape))
        }
        Spacer(Modifier.height(8.dp))
        Text(src.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(if (src.artist == MediaScanner.UNKNOWN) "—" else src.artist, style = MaterialTheme.typography.bodySmall, color = AriosColors.SilverDim,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        when (deck.status) {
            DeckStatus.ANALYSING -> {
                LinearProgressIndicator(progress = { deck.progress }, Modifier.fillMaxWidth().clip(CircleShape), color = color)
                Text(stringResource(R.string.analysing), style = MaterialTheme.typography.labelSmall, color = AriosColors.SilverDim, maxLines = 1)
            }
            DeckStatus.READY -> deck.analysis?.let { an ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tag("\u2066${an.bpm.roundToInt()} BPM\u2069", color)
                    Tag(an.camelot(), color)
                }
            }
            DeckStatus.ERROR -> Text(stringResource(R.string.analysis_failed), style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF8A80))
            else -> {}
        }
    }
}

private fun Modifier.graphicsLayerRotate(deg: Float) = this.graphicsLayer { rotationZ = deg }

@Composable
private fun Tag(text: String, color: Color) {
    Text(text, Modifier.clip(CircleShape).background(color.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
}

@Composable
private fun Compatibility(t: MixTiming) {
    val p = t.plan
    val dist = Keys.distance(t.a.key, t.a.minor, (t.b.key + p.shiftB + 12) % 12, t.b.minor)
    val pct = (t.ratio - 1) * 100
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp).glass(RoundedCornerShape(18.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.tempo_match, "%.1f".format(t.a.bpm), "%.1f".format(t.bBpm)), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(if (t.synced || !p.tempoSync) "%+.1f%%".format(pct) else stringResource(R.string.tempo_far),
                style = MaterialTheme.typography.labelSmall, color = if (abs(pct) <= 12) AriosColors.Mint else Color(0xFFFFB74D))
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.key_match, t.a.camelot(), t.b.camelot(p.shiftB)), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(if (dist <= 1) R.string.harmonic else R.string.clash), style = MaterialTheme.typography.labelSmall,
                color = if (dist <= 1) AriosColors.Mint else Color(0xFFFFB74D))
        }
    }
}

// ---------------------------------------------------------------------------------------------- timeline

/** Whole remix on one axis: A on top, B below, transition glowing where they overlap. Drag to move. */
@Composable
private fun Timeline(t: MixTiming, playhead: Double?, modifier: Modifier) {
    val s = Arios.remix
    val mint = AriosColors.Mint; val teal = AriosColors.Teal; val light = AriosColors.MintLight
    val glowA by rememberInfiniteTransition(label = "g").animateFloat(0.10f, 0.22f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "ga")
    var dragTotal by remember { mutableStateOf(0.0) }
    Box(modifier.fillMaxWidth().height(176.dp).glass(RoundedCornerShape(22.dp)).padding(horizontal = 10.dp, vertical = 12.dp)) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Ltr) {
            Canvas(
                Modifier.fillMaxSize().pointerInput(t.a, t.b) {
                    var lane = 0; var pxPerSec = 1.0; var applied = 0
                    detectDragGestures(
                        onDragStart = { o -> lane = if (o.y < size.height / 2) 0 else 1; pxPerSec = size.width / s.timing()!!.total; dragTotal = 0.0; applied = 0 },
                        onDragEnd = { dragTotal = 0.0 },
                    ) { _, d ->
                        dragTotal += d.x
                        val tm = s.timing() ?: return@detectDragGestures
                        // moves snap to whole bars (4 beats)
                        val beatPx = if (lane == 0) pxPerSec * tm.a.period else pxPerSec * tm.bPeriod
                        val bars = (dragTotal / (beatPx * 4)).toInt()
                        if (bars != applied) {
                            val delta = (bars - applied) * 4
                            applied = bars
                            if (lane == 0) s.update { it.copy(outBeat = it.outBeat + delta) } else s.update { it.copy(inBeat = it.inBeat - delta) }
                        }
                    }
                },
            ) {
                val total = t.total
                val x = { sec: Double -> (sec / total * size.width).toFloat() }
                val intro = t.introLen; val dur = t.duration
                val laneH = size.height * 0.40f
                val aMid = laneH / 2; val bMid = size.height - laneH / 2
                // transition zone
                drawRoundRect(Brush.horizontalGradient(listOf(mint.copy(alpha = glowA), teal.copy(alpha = glowA))), Offset(x(intro), 0f),
                    Size(x(intro + dur) - x(intro), size.height), CornerRadius(14f))
                val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                drawLine(light, Offset(x(intro), 0f), Offset(x(intro), size.height), 2f, pathEffect = dash)
                drawLine(light, Offset(x(intro + dur), 0f), Offset(x(intro + dur), size.height), 2f, pathEffect = dash)
                // waveforms
                val step = 3f
                var px = 0f
                while (px < size.width) {
                    val sec = px / size.width * total
                    if (sec <= intro + dur) {
                        val src = if (sec < intro) t.plan.aStart + sec else t.srcA(sec - intro)
                        val g = if (sec < intro) 1f else Mixer.gains(t.plan, (sec - intro) / dur, t.a.period / dur, 0.01).first.toFloat()
                        val h = peak(t.a, src) * laneH / 2 * (0.25f + 0.75f * g)
                        drawLine(mint.copy(alpha = 0.45f + 0.55f * g), Offset(px, aMid - h), Offset(px, aMid + h), 2f, StrokeCap.Round)
                    }
                    if (sec >= intro) {
                        val src = if (sec < intro + dur) t.srcB(sec - intro) else t.srcB(dur) + (sec - intro - dur)
                        val g = if (sec >= intro + dur) 1f else Mixer.gains(t.plan, (sec - intro) / dur, t.a.period / dur, 0.01).second.toFloat()
                        val h = peak(t.b, src) * laneH / 2 * (0.25f + 0.75f * g)
                        drawLine(teal.copy(alpha = 0.45f + 0.55f * g), Offset(px, bMid - h), Offset(px, bMid + h), 2f, StrokeCap.Round)
                    }
                    px += step
                }
                // drag handles
                drawCircle(mint, 11f, Offset(x(intro), aMid)); drawCircle(Color.Black, 5f, Offset(x(intro), aMid))
                drawCircle(teal, 11f, Offset(x(intro), bMid)); drawCircle(Color.Black, 5f, Offset(x(intro), bMid))
                // playhead
                playhead?.let { ph -> drawLine(Color.White, Offset(x(ph), 0f), Offset(x(ph), size.height), 3f) }
            }
        }
    }
}

private fun peak(an: TrackAnalysis, sec: Double): Float {
    if (an.peaks.isEmpty() || sec < 0 || sec > an.durationSec) return 0f
    val i = (sec / an.durationSec * an.peaks.size).toInt().coerceIn(0, an.peaks.size - 1)
    return an.peaks[i]
}

/** Zoom on the transition: both beat grids locked together, volume curves, bass swap. */
@Composable
private fun TransitionView(t: MixTiming, progress: Double?, modifier: Modifier) {
    val mint = AriosColors.Mint; val teal = AriosColors.Teal; val dim = AriosColors.SilverDim
    val p = t.plan
    Box(modifier.fillMaxWidth().height(150.dp).glass(RoundedCornerShape(22.dp)).padding(12.dp)) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Ltr) {
            Canvas(Modifier.fillMaxSize()) {
                val n = p.lengthBeats
                val w = size.width; val h = size.height
                // beat grid: A ticks on top, B ticks below — aligned when tempo sync is on
                for (i in 0..n) {
                    val xA = w * i / n
                    val bar = (p.outBeat + i - t.a.downbeat) % 4 == 0
                    drawLine(mint.copy(alpha = if (bar) 0.9f else 0.35f), Offset(xA, 0f), Offset(xA, if (bar) h * 0.16f else h * 0.09f), if (bar) 3f else 2f)
                    // where B's beat i really lands in output time
                    val tt = if (t.synced) i.toDouble() / n else ((t.bPeriod * i) / t.duration).coerceAtMost(1.0)
                    val xB = (w * tt).toFloat()
                    drawLine(teal.copy(alpha = if (bar) 0.9f else 0.35f), Offset(xB, h), Offset(xB, h - if (bar) h * 0.16f else h * 0.09f), if (bar) 3f else 2f)
                }
                // volume curves
                val pa = Path(); val pb = Path()
                val top = h * 0.2f; val span = h * 0.6f
                for (k in 0..100) {
                    val x = k / 100.0
                    val (ga, gb) = Mixer.gains(p, x, 1.0 / n, 0.004)
                    val px = (w * x).toFloat()
                    val ya = top + span * (1 - ga.toFloat()); val yb = top + span * (1 - gb.toFloat())
                    if (k == 0) { pa.moveTo(px, ya); pb.moveTo(px, yb) } else { pa.lineTo(px, ya); pb.lineTo(px, yb) }
                }
                drawPath(pa, mint, style = Stroke(5f, cap = StrokeCap.Round))
                drawPath(pb, teal, style = Stroke(5f, cap = StrokeCap.Round))
                if (p.bassSwap) {
                    val mid = (if (p.style == MixStyle.BLEND) 0.6f else 0.5f) * w
                    drawLine(dim, Offset(mid, top - 6f), Offset(mid, top + span + 6f), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                }
                progress?.let { pr -> if (pr in 0.0..1.0) drawLine(Color.White, Offset((w * pr).toFloat(), 0f), Offset((w * pr).toFloat(), h), 3f) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- controls

@Composable
private fun Controls(t: MixTiming) {
    val s = Arios.remix
    val p = t.plan
    Section(stringResource(R.string.style))
    Chips(
        listOf(MixStyle.SMOOTH to R.string.style_smooth, MixStyle.FILTER to R.string.style_filter, MixStyle.ECHO to R.string.style_echo,
            MixStyle.CUT to R.string.style_cut, MixStyle.BLEND to R.string.style_blend),
        p.style, listOf(Icons.Filled.Waves, Icons.Filled.FilterAlt, Icons.Filled.SurroundSound, Icons.Filled.ContentCut, Icons.Filled.Layers),
    ) { s.setStyle(it) }

    Section(stringResource(R.string.length))
    Chips(listOf(1, 2, 4, 8, 16, 32).map { it * 4 to 0 }, p.lengthBeats, label = { stringResource(R.string.bars, it / 4) }) { v -> s.update { it.copy(lengthBeats = v) } }

    if (p.style == MixStyle.SMOOTH || p.style == MixStyle.BLEND || p.style == MixStyle.FILTER) {
        Section(stringResource(R.string.curve))
        Chips(listOf(FadeCurve.LINEAR to R.string.curve_linear, FadeCurve.POWER to R.string.curve_power, FadeCurve.S to R.string.curve_s), p.curve) { c -> s.update { it.copy(curve = c) } }
    }

    // in / out points
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp).glass(RoundedCornerShape(22.dp)).padding(8.dp)) {
        PointRow(stringResource(R.string.out_point), AriosColors.Mint, "\u2066${formatTime((t.tA0 * 1000).toLong())}\u2069 • " + stringResource(R.string.bar_n, p.outBeat / 4 + 1),
            { d -> s.update { it.copy(outBeat = it.outBeat + d) } })
        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
        PointRow(stringResource(R.string.in_point), AriosColors.Teal, "\u2066${formatTime((t.tB0 * 1000).toLong())}\u2069 • " + stringResource(R.string.bar_n, p.inBeat / 4 + 1),
            { d -> s.update { it.copy(inBeat = it.inBeat + d) } })
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).glass(RoundedCornerShape(22.dp)).padding(vertical = 6.dp)) {
        ToggleRow(Icons.Filled.Speed, stringResource(R.string.tempo_sync), stringResource(R.string.tempo_sync_hint), p.tempoSync) { v -> s.update { it.copy(tempoSync = v) } }
        ToggleRow(Icons.Filled.GraphicEq, stringResource(R.string.bass_swap), stringResource(R.string.bass_swap_hint), p.bassSwap) { v -> s.update { it.copy(bassSwap = v) } }
        ToggleRow(Icons.Filled.VolumeUp, stringResource(R.string.auto_level), null, p.autoLevel) { v -> s.update { it.copy(autoLevel = v) } }
    }

    Section(stringResource(R.string.key_shift), trailing = "${t.b.keyName(p.shiftB)} • ${t.b.camelot(p.shiftB)}")
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalIconButton(onClick = { s.update { it.copy(shiftB = it.shiftB - 1) } }) { Icon(Icons.Filled.Remove, null) }
        Text(stringResource(R.string.semitones, p.shiftB), Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
        FilledTonalIconButton(onClick = { s.update { it.copy(shiftB = it.shiftB + 1) } }) { Icon(Icons.Filled.Add, null) }
        AssistChip(onClick = { s.update { it.copy(shiftB = Keys.bestShift(t.a, t.b)) } }, label = { Text(stringResource(R.string.auto)) },
            leadingIcon = { Icon(Icons.Filled.AutoFixHigh, null, Modifier.size(16.dp)) })
    }

    Section(stringResource(R.string.volume))
    SliderRow(stringResource(R.string.deck_a), "%+.0f dB".format(p.gainA), p.gainA, -12f..6f, AriosColors.Mint) { v -> s.update { it.copy(gainA = v.roundToInt().toFloat()) } }
    SliderRow(stringResource(R.string.deck_b), "%+.0f dB".format(p.gainB), p.gainB, -12f..6f, AriosColors.Teal) { v -> s.update { it.copy(gainB = v.roundToInt().toFloat()) } }

    Section(stringResource(R.string.start_end))
    val aMax = max(1.0, t.tA0 - 1).toFloat()
    SliderRow(stringResource(R.string.a_start), formatTime((p.aStart * 1000).toLong()), p.aStart.toFloat(), 0f..aMax, AriosColors.Mint) { v ->
        s.update { it.copy(aStart = (v / t.a.period).roundToInt() * t.a.period) }
    }
    SliderRow(stringResource(R.string.fade_in), "%.0fs".format(p.aFadeIn), p.aFadeIn.toFloat(), 0f..10f, AriosColors.Mint) { v -> s.update { it.copy(aFadeIn = v.roundToInt().toDouble()) } }
    val bMin = (t.srcB(t.duration) + 5).toFloat(); val bMax = t.b.durationSec.toFloat()
    if (bMax > bMin) {
        val cur = if (p.bEnd > 0) p.bEnd.toFloat().coerceIn(bMin, bMax) else bMax
        SliderRow(stringResource(R.string.b_end), formatTime((cur * 1000).toLong()), cur, bMin..bMax, AriosColors.Teal) { v ->
            s.update { it.copy(bEnd = if (v >= bMax - 0.5f) -1.0 else v.toDouble()) }
        }
    }
    SliderRow(stringResource(R.string.fade_out), "%.0fs".format(p.bFadeOut), p.bFadeOut.toFloat(), 0f..15f, AriosColors.Teal) { v -> s.update { it.copy(bFadeOut = v.roundToInt().toDouble()) } }
}

@Composable
private fun <T> Chips(
    options: List<Pair<T, Int>>, selected: T, icons: List<androidx.compose.ui.graphics.vector.ImageVector>? = null,
    label: @Composable (T) -> String = { v -> stringResource(options.first { it.first == v }.second) }, onPick: (T) -> Unit,
) {
    Row(Modifier.fillMaxWidth().horizontalScrollCompat().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, (v, _) ->
            val sel = v == selected
            val bg by animateColorAsState(if (sel) AriosColors.Mint else Color.White.copy(alpha = 0.06f), label = "c")
            Row(
                Modifier.clip(CircleShape).background(bg).then(if (sel) Modifier.glowHalo(false) else Modifier).bounceClick { onPick(v) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icons?.getOrNull(i)?.let { Icon(it, null, Modifier.size(17.dp), tint = if (sel) AriosColors.Black else AriosColors.MintLight); Spacer(Modifier.width(6.dp)) }
                Text(label(v), color = if (sel) AriosColors.Black else AriosColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun Modifier.horizontalScrollCompat() = this.horizontalScroll(rememberScrollState())

@Composable
private fun PointRow(title: String, color: Color, value: String, onMove: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(value, style = MaterialTheme.typography.labelSmall, color = AriosColors.SilverDim)
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row {
                SmallBtn("−4") { onMove(-4) }; SmallBtn("−1") { onMove(-1) }; SmallBtn("+1") { onMove(1) }; SmallBtn("+4") { onMove(4) }
            }
        }
    }
}

@Composable
private fun SmallBtn(text: String, onClick: () -> Unit) {
    Box(Modifier.padding(2.dp).size(42.dp, 36.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.07f)).bounceClick(onClick = onClick),
        contentAlignment = Alignment.Center) { Text(text, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
}

@Composable
private fun ToggleRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, hint: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().bounceClick { onChange(!value) }.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = AriosColors.MintLight)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, color = AriosColors.SilverDim)
        }
        Switch(value, onChange)
    }
}

@Composable
private fun SliderRow(title: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, color: Color, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row { Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge); Text("\u2066$value\u2069", style = MaterialTheme.typography.labelLarge, color = color) }
        Slider(v.coerceIn(range.start, range.endInclusive), onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color, inactiveTrackColor = color.copy(alpha = 0.18f)))
    }
}

// ---------------------------------------------------------------------------------------------- picker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SongPickerSheet(onDismiss: () -> Unit, onSong: (com.arios.player.data.Song) -> Unit, onFile: () -> Unit) {
    val lib by Arios.library.state.collectAsState()
    var q by remember { mutableStateOf("") }
    val list = remember(lib, q) {
        val n = com.arios.player.lyrics.LyricsMatcher.normalise(q)
        if (n.isBlank()) lib.songs else lib.songs.filter { com.arios.player.lyrics.LyricsMatcher.normalise("${it.title} ${it.artist}").contains(n) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AriosColors.Surface) {
        Column(Modifier.fillMaxHeight(0.85f)) {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true,
                placeholder = { Text(stringResource(R.string.search_library)) }, leadingIcon = { Icon(Icons.Filled.Search, null) }, shape = RoundedCornerShape(16.dp))
            Row(Modifier.fillMaxWidth().padding(16.dp, 10.dp).glass(RoundedCornerShape(16.dp)).bounceClick(onClick = onFile).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FolderOpen, null, tint = AriosColors.Mint); Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.from_file), fontWeight = FontWeight.SemiBold)
            }
            LazyColumn(Modifier.weight(1f)) {
                items(list, key = { it.id }) { song ->
                    Row(Modifier.fillMaxWidth().bounceClick { onSong(song) }.padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(song.artKey(), Modifier.size(46.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artist, style = MaterialTheme.typography.bodySmall, color = AriosColors.SilverDim, maxLines = 1)
                        }
                        Text(formatTime(song.durationMs), style = MaterialTheme.typography.labelSmall, color = AriosColors.SilverDim)
                    }
                }
            }
        }
    }
}
