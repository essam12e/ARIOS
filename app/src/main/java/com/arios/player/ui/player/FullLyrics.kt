package com.arios.player.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arios.player.Arios
import com.arios.player.R
import com.arios.player.artwork.artKey
import com.arios.player.lyrics.LyricsState
import com.arios.player.ui.AppNav
import com.arios.player.ui.Screen
import com.arios.player.ui.components.Artwork
import com.arios.player.ui.components.displayArtist
import com.arios.player.ui.theme.AriosColors
import com.arios.player.ui.theme.glowHalo

@Composable
fun FullLyricsScreen(nav: AppNav) {
    val state by Arios.player.state.collectAsState()
    val song = currentSong(state)
    val palette = rememberArtPalette(song)
    val ls = rememberLyricsState(song)
    val offsets by Arios.userStore.lyricOffsets.collectAsState()
    @Suppress("UNUSED_VARIABLE") val v by Arios.prefs.version.collectAsState()
    val offset = offsets[song?.key] ?: 0L
    var syncPanel by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val unknown = stringResource(R.string.unknown)
    BackHandler { nav.lyricsFull = false }

    Box(Modifier.fillMaxSize().background(AriosColors.Black).clickable(enabled = false) {}) {
        com.arios.player.ui.theme.AuroraBackground(tint = palette.base, intensity = 1.6f)
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.lyricsFull = false }) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.close), Modifier.size(30.dp), tint = Color.White) }
                Artwork(song?.artKey(), Modifier.size(44.dp), RoundedCornerShape(8.dp), 18.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(song?.title ?: "", color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(displayArtist(song?.artist ?: "", unknown), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                val ready = ls as? LyricsState.Ready
                if (ready?.lyrics?.hasTranslation == true) IconButton(onClick = { Arios.prefs.showTranslation = !Arios.prefs.showTranslation }) {
                    Icon(Icons.Filled.Translate, stringResource(R.string.translation), tint = if (Arios.prefs.showTranslation) AriosColors.Mint else Color.White)
                }
                IconButton(onClick = { syncPanel = !syncPanel }) { Icon(Icons.Filled.Timer, stringResource(R.string.sync_offset), tint = if (offset != 0L) AriosColors.Mint else Color.White) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null, tint = Color.White) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text(stringResource(R.string.search_lyrics)) }, { menu = false; song?.let { nav.lyricsSearchFor = it } }, leadingIcon = { Icon(Icons.Filled.ManageSearch, null) })
                        DropdownMenuItem({ Text(stringResource(R.string.edit_lyrics)) }, { menu = false; song?.let { nav.push(Screen.LyricsEditor(it.id)) } }, leadingIcon = { Icon(Icons.Filled.EditNote, null) })
                        DropdownMenuItem({ Text(stringResource(R.string.text_bigger)) }, { Arios.prefs.lyricsFontSize = (Arios.prefs.lyricsFontSize + 2).coerceAtMost(48) }, leadingIcon = { Icon(Icons.Filled.TextIncrease, null) })
                        DropdownMenuItem({ Text(stringResource(R.string.text_smaller)) }, { Arios.prefs.lyricsFontSize = (Arios.prefs.lyricsFontSize - 2).coerceAtLeast(16) }, leadingIcon = { Icon(Icons.Filled.TextDecrease, null) })
                        DropdownMenuItem({ Text(stringResource(if (Arios.prefs.lyricsCenter) R.string.align_start else R.string.align_center)) }, { Arios.prefs.lyricsCenter = !Arios.prefs.lyricsCenter; menu = false }, leadingIcon = { Icon(Icons.Filled.FormatAlignCenter, null) })
                        DropdownMenuItem({ Text(stringResource(R.string.word_by_word)) }, { Arios.prefs.wordByWord = !Arios.prefs.wordByWord; menu = false },
                            leadingIcon = { Checkbox(Arios.prefs.wordByWord, null) })
                    }
                }
            }
            AnimatedVisibility(syncPanel && song != null) {
                OffsetPanel(offset) { song?.let { s -> Arios.userStore.setOffset(s.key, it) } }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LyricsBody(nav, song, ls, offset, Arios.prefs.lyricsFontSize, compact = false, blurFar = true)
            }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                SeekBar(state)
                Ltr { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { Arios.player.previous() }) { Icon(Icons.Filled.SkipPrevious, null, Modifier.size(34.dp), tint = Color.White) }
                    Spacer(Modifier.width(18.dp))
                    Box(Modifier.size(60.dp).glowHalo(state.isPlaying).clip(CircleShape).background(AriosColors.brand).clickable { Arios.player.toggle() }, contentAlignment = Alignment.Center) {
                        Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, Modifier.size(34.dp), tint = Color.Black)
                    }
                    Spacer(Modifier.width(18.dp))
                    IconButton(onClick = { Arios.player.next() }) { Icon(Icons.Filled.SkipNext, null, Modifier.size(34.dp), tint = Color.White) }
                } }
            }
        }
    }
}

private fun lerpBlack(c: Color) = androidx.compose.ui.graphics.lerp(c, Color.Black, 0.6f)

@Composable
private fun OffsetPanel(offset: Long, onChange: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp))
        .background(Color.Black.copy(alpha = 0.25f)).padding(12.dp)) {
        Text(stringResource(R.string.sync_offset_hint), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            listOf(-1000L, -100L).forEach { d -> OffsetBtn(d) { onChange(offset + d) } }
            Text("%+.1fs".format(offset / 1000f), color = Color.White, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.clickable { onChange(0) })
            listOf(100L, 1000L).forEach { d -> OffsetBtn(d) { onChange(offset + d) } }
        }
    }
}

@Composable
private fun OffsetBtn(d: Long, onClick: () -> Unit) {
    Box(Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text("%+.1f".format(d / 1000f), color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}
