package com.arios.player.ui.player

import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.arios.player.Arios
import com.arios.player.data.Song
import com.arios.player.lyrics.LyricLine
import com.arios.player.lyrics.Lyrics
import com.arios.player.lyrics.LyricsState
import com.arios.player.ui.theme.lyricStyle
import com.arios.player.ui.theme.glideTo
import com.arios.player.ui.theme.Motion
import kotlinx.coroutines.delay

/** Loads lyrics for [song] (cache → online) and reloads when lyrics are edited. */
@Composable
fun rememberLyricsState(song: Song?): LyricsState {
    val rev by Arios.lyrics.revision.collectAsState()
    val state = remember { mutableStateOf<Pair<String?, LyricsState>>(null to LyricsState.Idle) }
    LaunchedEffect(song?.key, rev) {
        if (song == null) { state.value = null to LyricsState.Idle; return@LaunchedEffect }
        if (state.value.first != song.key) state.value = song.key to LyricsState.Loading
        val l = runCatching { Arios.lyrics.get(song) }.getOrNull()
        state.value = song.key to (l?.let { LyricsState.Ready(it) } ?: LyricsState.NotFound)
    }
    return if (state.value.first == song?.key) state.value.second else LyricsState.Loading
}

/** Player position updated every frame (only readers of the state recompose). */
@Composable
fun rememberFramePosition(): State<Long> {
    val pos = remember { mutableLongStateOf(Arios.player.position()) }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { pos.longValue = Arios.player.position() }
    }
    return pos
}

data class LyricColors(val active: Color, val inactive: Color, val past: Color, val glow: Color)

/**
 * Synced lyrics with word-by-word highlighting, auto-scroll and RTL support.
 * [offsetMs] > 0 shows lyrics later.
 */
@Composable
fun SyncedLyricsView(
    lyrics: Lyrics,
    offsetMs: Long,
    fontSize: Int,
    colors: LyricColors,
    modifier: Modifier = Modifier,
    center: Boolean = false,
    showTranslation: Boolean = true,
    wordByWord: Boolean = true,
    blurFar: Boolean = false,
    topFraction: Float = 0.3f,
    contentPadding: PaddingValues = PaddingValues(vertical = 24.dp),
    onSeek: (Long) -> Unit,
) {
    val rawPos = rememberFramePosition()
    val pos = remember(offsetMs) { derivedStateOf { rawPos.value - offsetMs } }
    val current by remember(lyrics) { derivedStateOf { lyrics.indexAt(pos.value) } }
    val listState = rememberLazyListState()
    var viewport by remember { mutableIntStateOf(0) }
    var userScrollAt by remember { mutableLongStateOf(0L) }
    var auto by remember { mutableStateOf(false) }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && !auto) userScrollAt = System.currentTimeMillis()
    }
    LaunchedEffect(current, viewport) {
        if (viewport == 0) return@LaunchedEffect
        val since = System.currentTimeMillis() - userScrollAt
        if (since < 3500) delay(3500 - since)
        auto = true
        runCatching {
            listState.glideTo(current.coerceAtLeast(0) + 1, (viewport * topFraction).toInt(), 750)
        }
        auto = false
    }

    val align = if (center) TextAlign.Center else TextAlign.Start
    LazyColumn(
        state = listState,
        modifier = modifier.onSizeChanged { viewport = it.height },
        contentPadding = contentPadding,
    ) {
        item { Spacer(Modifier.height((with(androidx.compose.ui.platform.LocalDensity.current) { (viewport * topFraction).toDp() }))) }
        itemsIndexed(lyrics.lines) { i, line ->
            val distance = i - current
            LyricLineItem(
                line = line,
                isActive = i == current,
                distance = distance,
                pos = pos,
                fontSize = fontSize,
                colors = colors,
                align = align,
                showTranslation = showTranslation,
                wordByWord = wordByWord,
                blur = blurFar && Build.VERSION.SDK_INT >= 31,
                onClick = { onSeek((line.start + offsetMs).coerceAtLeast(0)) },
            )
        }
        item { Spacer(Modifier.height(240.dp)) }
    }
}

@Composable
private fun LyricLineItem(
    line: LyricLine,
    isActive: Boolean,
    distance: Int,
    pos: State<Long>,
    fontSize: Int,
    colors: LyricColors,
    align: TextAlign,
    showTranslation: Boolean,
    wordByWord: Boolean,
    blur: Boolean,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(if (isActive) 1f else 0.94f, spring(dampingRatio = 0.7f, stiffness = 180f), label = "s")
    // highlight fades in/out (GSAP-style ease) instead of switching instantly
    val lit by animateFloatAsState(if (isActive) 1f else 0f, tween(if (isActive) 380 else 900, easing = Motion.EaseOutQuint), label = "lit")
    val alpha by animateFloatAsState(
        when { isActive -> 1f; distance < 0 -> 0.55f; kotlin.math.abs(distance) == 1 -> 0.7f; else -> 0.5f },
        tween(650, easing = Motion.EaseOutQuint), label = "a",
    )
    val blurRadius by animateDpAsState(if (blur && !isActive) (kotlin.math.abs(distance).coerceAtMost(4) * 0.8f).dp else 0.dp, label = "b")
    val origin = if (align == TextAlign.Center) 0.5f else if (Lyrics.isRtl(line.text)) 1f else 0f
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = (fontSize * 0.32f).dp)
            .graphicsLayer {
                scaleX = scale; scaleY = scale; this.alpha = alpha
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(origin, 0.5f)
            }
            .then(if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier),
    ) {
        if (line.text.isBlank()) {
            InstrumentalDots(isActive, line, pos, colors, align)
            return@Column
        }
        val style = lyricStyle(fontSize).copy(textAlign = align, textDirection = TextDirection.Content)
        if (isActive) {
            val text = if (wordByWord && line.words.isNotEmpty()) karaoke(line, pos.value, colors) else AnnotatedString(line.text)
            Text(text, style = style.copy(color = colors.active, shadow = Shadow(colors.glow, blurRadius = 18f)), modifier = Modifier.fillMaxWidth())
        } else {
            val base = if (distance < 0) colors.past else colors.inactive
            Text(line.text, style = style.copy(color = lerp(base, colors.active, lit),
                shadow = if (lit > 0.05f) Shadow(colors.glow.copy(alpha = colors.glow.alpha * lit), blurRadius = 18f) else null),
                modifier = Modifier.fillMaxWidth())
        }
        if (showTranslation && !line.translation.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                line.translation,
                style = lyricStyle((fontSize * 0.62f).toInt()).copy(textAlign = align, textDirection = TextDirection.Content,
                    color = if (isActive) colors.active.copy(alpha = 0.78f) else colors.inactive),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Builds the karaoke-coloured line: sung words bright, the current word filling up, rest dim. */
private fun karaoke(line: LyricLine, pos: Long, c: LyricColors): AnnotatedString = buildAnnotatedString {
    line.words.forEach { w ->
        val p = when {
            pos >= w.end -> 1f
            pos <= w.start -> 0f
            else -> ((pos - w.start).toFloat() / (w.end - w.start).coerceAtLeast(1)).coerceIn(0f, 1f)
        }
        val color = lerp(c.inactive, c.active, FastOutSlowInEasing.transform(p))
        val glow = p > 0f && p < 1f
        withStyle(SpanStyle(color = color, shadow = if (glow) Shadow(c.glow, blurRadius = 24f) else Shadow(Color.Transparent))) {
            append(w.text)
        }
    }
}

@Composable
private fun InstrumentalDots(active: Boolean, line: LyricLine, pos: State<Long>, colors: LyricColors, align: TextAlign) {
    val arrangement = when (align) { TextAlign.Center -> Arrangement.Center; else -> Arrangement.Start }
    Row(Modifier.fillMaxWidth().height(28.dp), horizontalArrangement = arrangement, verticalAlignment = Alignment.CenterVertically) {
        val progress = if (active) ((pos.value - line.start).toFloat() / (line.end - line.start).coerceAtLeast(1)).coerceIn(0f, 1f) else 0f
        val pulse = if (active) rememberInfiniteTransition(label = "p").animateFloat(0.85f, 1.15f,
            infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "p").value else 1f
        repeat(3) { i ->
            val lit = progress > (i + 1) / 3.5f
            Box(
                Modifier.padding(horizontal = 4.dp).size((10 * pulse).dp).clip(CircleShape)
                    .background(if (lit) colors.active else colors.inactive)
            )
        }
    }
}

/** Plain (unsynced) lyrics. */
@Composable
fun PlainLyricsView(lyrics: Lyrics, fontSize: Int, color: Color, modifier: Modifier = Modifier, center: Boolean = false, header: @Composable () -> Unit = {}) {
    LazyColumn(modifier, contentPadding = PaddingValues(24.dp)) {
        item { header() }
        itemsIndexed(lyrics.lines) { _, l ->
            Text(
                l.text.ifBlank { " " },
                style = lyricStyle(fontSize).copy(color = color, textDirection = TextDirection.Content,
                    textAlign = if (center) TextAlign.Center else TextAlign.Start),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        item { Spacer(Modifier.height(200.dp)) }
    }
}
