package com.arios.player.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arios.player.Arios
import com.arios.player.R
import com.arios.player.artwork.artKey
import com.arios.player.ui.AppNav
import com.arios.player.ui.components.Artwork
import com.arios.player.ui.components.displayArtist
import com.arios.player.ui.library.ArchShape
import com.arios.player.ui.theme.AriosColors
import com.arios.player.ui.theme.bounceClick
import com.arios.player.ui.theme.glass
import com.arios.player.ui.theme.glowHalo
import kotlinx.coroutines.delay

@Composable
fun MiniPlayer(nav: AppNav, modifier: Modifier = Modifier) {
    val state by Arios.player.state.collectAsState()
    if (!state.hasMedia) return
    val song = currentSong(state)
    val palette = rememberArtPalette(song)
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.songId, state.isPlaying) {
        while (true) {
            progress = if (state.durationMs > 0) (Arios.player.position().toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
            delay(500)
        }
    }
    val unknown = stringResource(R.string.unknown)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .background(androidx.compose.ui.graphics.lerp(palette.card, AriosColors.Black, 0.45f), RoundedCornerShape(24.dp))
            .glass(RoundedCornerShape(24.dp), glow = palette.base, glowRadius = 20.dp, fill = 0.12f, tint = androidx.compose.ui.graphics.lerp(palette.base, Color.White, 0.3f))
            .clip(RoundedCornerShape(24.dp))
            .bounceClick { nav.playerOpen = true }
            .pointerInput(Unit) { detectVerticalDragGestures { _, d -> if (d < -12) nav.playerOpen = true } },
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(song?.artKey(), Modifier.size(48.dp), ArchShape, 18.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song?.title ?: state.title, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(displayArtist(song?.artist ?: state.artist, unknown), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Ltr { Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { Arios.player.previous() }) { Icon(Icons.Filled.SkipPrevious, stringResource(R.string.previous), tint = Color.White) }
            Box(Modifier.size(46.dp).glowHalo(state.isPlaying).clip(CircleShape).background(AriosColors.brand).bounceClick { Arios.player.toggle() }, contentAlignment = Alignment.Center) {
                Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(R.string.play), tint = Color.Black)
            }
            IconButton(onClick = { Arios.player.next() }) { Icon(Icons.Filled.SkipNext, stringResource(R.string.next), tint = Color.White) }
            } }
        }
        Ltr { Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 6.dp).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f))) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(AriosColors.brandHorizontal))
        } }
    }
}
