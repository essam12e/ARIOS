package com.arios.player.ui.player

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import com.arios.player.Arios
import com.arios.player.R
import com.arios.player.artwork.artKey
import com.arios.player.data.Song
import com.arios.player.playback.PlayerUiState
import com.arios.player.ui.components.formatTime
import com.arios.player.ui.theme.AriosColors
import kotlinx.coroutines.delay

/** Colours extracted from the album art (Spotify-like dynamic background). */
data class ArtPalette(val base: Color, val background: Color, val card: Color)

private val defaultPalette = ArtPalette(Color(0xFF3A3F47), Color(0xFF1A1C20), Color(0xFF2E3238))

@Composable
fun rememberArtPalette(song: Song?): ArtPalette {
    val ctx = LocalContext.current
    val target by produceState(defaultPalette, song?.albumId) {
        if (song == null) { value = defaultPalette; return@produceState }
        val req = ImageRequest.Builder(ctx).data(song.artKey()).allowHardware(false).size(160).build()
        val bmp = (ctx.imageLoader.execute(req).drawable as? BitmapDrawable)?.bitmap
        if (bmp == null) { value = defaultPalette; return@produceState }
        val p = Palette.from(bmp).maximumColorCount(16).generate()
        val sw = p.vibrantSwatch ?: p.darkVibrantSwatch ?: p.mutedSwatch ?: p.dominantSwatch
        val base = sw?.rgb?.let { Color(it) } ?: defaultPalette.base
        // keep everything dark enough for white text
        var card = base
        while (card.luminance() > 0.22f) card = lerp(card, Color.Black, 0.12f)
        val bg = lerp(base, Color.Black, 0.55f)
        value = ArtPalette(base, bg, card)
    }
    val base by animateColorAsState(target.base, tween(700), label = "b")
    val bg by animateColorAsState(target.background, tween(700), label = "bg")
    val card by animateColorAsState(target.card, tween(700), label = "c")
    return ArtPalette(base, bg, card)
}


/** Playback controls and timelines are never mirrored (Material guidance for RTL). */
@Composable
fun Ltr(content: @Composable () -> Unit) =
    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr, content = content)

/** Seek bar with elapsed/remaining labels; updates 4×/s. */
@Composable
fun SeekBar(state: PlayerUiState, modifier: Modifier = Modifier, color: Color = Color.White) {
    var pos by remember { mutableLongStateOf(Arios.player.position()) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(state.songId, state.isPlaying) {
        while (true) { pos = Arios.player.position(); delay(250) }
    }
    val dur = state.durationMs.coerceAtLeast(1)
    Ltr { Column(modifier) {
        Slider(
            value = dragging ?: (pos.toFloat() / dur).coerceIn(0f, 1f),
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { Arios.player.seekTo((it * dur).toLong()) }; dragging = null },
            colors = SliderDefaults.colors(
                thumbColor = color, activeTrackColor = color, inactiveTrackColor = color.copy(alpha = 0.22f),
            ),
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(formatTime(dragging?.let { (it * dur).toLong() } ?: pos), style = MaterialTheme.typography.labelMedium, color = color.copy(alpha = 0.7f))
            Spacer(Modifier.weight(1f))
            Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelMedium, color = color.copy(alpha = 0.7f))
        }
    } }
}

@Composable
fun TransportControls(state: PlayerUiState, modifier: Modifier = Modifier, big: Boolean = true) {
    val playSize = if (big) 76.dp else 58.dp
    Ltr { Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { Arios.player.setShuffle(!state.shuffle) }) {
            Icon(Icons.Filled.Shuffle, stringResource(R.string.shuffle), tint = if (state.shuffle) AriosColors.Mint else Color.White.copy(alpha = 0.85f))
        }
        IconButton(onClick = { Arios.player.previous() }, Modifier.size(56.dp)) {
            Icon(Icons.Filled.SkipPrevious, stringResource(R.string.previous), Modifier.size(40.dp), tint = Color.White)
        }
        Box(
            Modifier.size(playSize).clip(CircleShape).background(Color.White).clickable { Arios.player.toggle() },
            contentAlignment = Alignment.Center,
        ) {
            if (state.buffering && !state.isPlaying) CircularProgressIndicator(Modifier.size(28.dp), color = Color.Black, strokeWidth = 3.dp)
            else Icon(
                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                stringResource(if (state.isPlaying) R.string.pause else R.string.play),
                Modifier.size(playSize * 0.55f), tint = Color.Black,
            )
        }
        IconButton(onClick = { Arios.player.next() }, Modifier.size(56.dp)) {
            Icon(Icons.Filled.SkipNext, stringResource(R.string.next), Modifier.size(40.dp), tint = Color.White)
        }
        IconButton(onClick = { Arios.player.cycleRepeat() }) {
            Icon(
                if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                stringResource(R.string.repeat),
                tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) AriosColors.Mint else Color.White.copy(alpha = 0.85f),
            )
        }
    } }
}

/** Rounded action chip used under the title (favourite, lyrics, speed, playlist, queue…). */
@Composable
fun ActionChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String?, tint: Color = Color.White, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        if (label != null) { Spacer(Modifier.width(8.dp)); Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White) }
    }
}

val SPEEDS = listOf(0.5f, 0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f)

@Composable
fun SpeedDialog(current: Float, onDismiss: () -> Unit) {
    var v by remember { mutableFloatStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playback_speed)) },
        text = {
            Column {
                Text("%.2fx".format(v), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
                Slider(v, { v = (Math.round(it * 20) / 20f) }, valueRange = 0.25f..3f, onValueChangeFinished = { Arios.player.setSpeed(v) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                        FilterChip(selected = v == s, onClick = { v = s; Arios.player.setSpeed(s) }, label = { Text("${s}x") })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

@Composable
fun SleepDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sleep_timer)) },
        text = {
            Column {
                listOf(5, 10, 15, 30, 45, 60, 90).forEach { m ->
                    Text(stringResource(R.string.minutes_n, m), Modifier.fillMaxWidth().clickable { Arios.player.setSleepTimer(m); onDismiss() }.padding(12.dp))
                }
                if (Arios.player.state.value.sleepEndsAt > 0)
                    Text(stringResource(R.string.cancel_timer), Modifier.fillMaxWidth().clickable { Arios.player.setSleepTimer(0); onDismiss() }.padding(12.dp), color = AriosColors.Mint)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
