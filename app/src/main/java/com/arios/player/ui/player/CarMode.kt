package com.arios.player.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arios.player.Arios
import com.arios.player.lyrics.LyricsState
import com.arios.player.ui.AppNav
import com.arios.player.ui.components.displayArtist
import com.arios.player.ui.theme.*

/** Car / stage mode: huge controls and the current lyric line, screen kept on. */
@Composable
fun CarModeScreen(nav: AppNav) {
    val state by Arios.player.state.collectAsState()
    val song = currentSong(state)
    val ls = rememberLyricsState(song)
    val offsets by Arios.userStore.lyricOffsets.collectAsState()
    val pos by rememberFramePosition()
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    BackHandler { nav.carMode = false }
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp

    val lyrics = (ls as? LyricsState.Ready)?.lyrics
    val idx = lyrics?.indexAt(pos - (song?.let { offsets[it.key] } ?: 0L)) ?: -1
    val line = lyrics?.lines?.getOrNull(idx)?.text?.takeIf { it.isNotBlank() } ?: "♪"
    val next = lyrics?.lines?.getOrNull(idx + 1)?.text ?: ""

    Box(Modifier.fillMaxSize().background(AriosColors.Black)) {
        CoverBackdrop(song, dim = 0.7f)
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(song?.title ?: "", style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White)
                    Text(displayArtist(song?.artist ?: "", ""), style = MaterialTheme.typography.titleLarge, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
                }
                IconButton(onClick = { nav.carMode = false }, Modifier.size(64.dp)) { Icon(Icons.Filled.Close, null, Modifier.size(36.dp), tint = Color.White) }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                VisualizerRing(Modifier.fillMaxSize().padding(10.dp), AriosColors.Mint.copy(alpha = 0.5f))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimatedContent(line, transitionSpec = {
                        (slideInVertically(Motion.offsetSoft) { it / 2 } + Motion.fadeInSoft()) togetherWith (slideOutVertically(Motion.offsetSoft) { -it / 2 } + Motion.fadeOutSoft())
                    }, label = "carline") { t ->
                        Text(t, style = lyricStyle(if (landscape) 40 else 44).copy(textAlign = TextAlign.Center, textDirection = TextDirection.Content, color = Color.White,
                            shadow = androidx.compose.ui.graphics.Shadow(AriosColors.Mint.copy(alpha = 0.7f), blurRadius = 30f)), maxLines = 3)
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(next, style = lyricStyle(22).copy(textAlign = TextAlign.Center, textDirection = TextDirection.Content, color = Color.White.copy(alpha = 0.45f)), maxLines = 2)
                }
            }
            SeekBar(state)
            Ltr {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(96.dp).clip(CircleShape).glass(CircleShape).bounceClick { Arios.player.previous() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.SkipPrevious, null, Modifier.size(56.dp), tint = Color.White)
                    }
                    Box(Modifier.size(132.dp).glowHalo(state.isPlaying).clip(CircleShape).background(AriosColors.brand).bounceClick { Arios.player.toggle() }, contentAlignment = Alignment.Center) {
                        PlayPauseIcon(state.isPlaying, 72.dp)
                    }
                    Box(Modifier.size(96.dp).clip(CircleShape).glass(CircleShape).bounceClick { Arios.player.next() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.SkipNext, null, Modifier.size(56.dp), tint = Color.White)
                    }
                }
            }
        }
    }
}
