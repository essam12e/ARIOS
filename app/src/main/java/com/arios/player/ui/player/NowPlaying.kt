package com.arios.player.ui.player

import android.content.Intent
import android.content.res.Configuration
import android.media.audiofx.AudioEffect
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arios.player.Arios
import com.arios.player.R
import com.arios.player.artwork.artKey
import com.arios.player.data.Song
import com.arios.player.lyrics.LyricsState
import com.arios.player.playback.PlayerUiState
import com.arios.player.ui.AppNav
import com.arios.player.ui.Screen
import com.arios.player.ui.components.Artwork
import com.arios.player.ui.components.displayArtist
import com.arios.player.ui.theme.AriosColors
import kotlinx.coroutines.launch

@Composable
fun currentSong(state: PlayerUiState): Song? {
    val lib by Arios.library.state.collectAsState()
    return state.songId?.let { lib.byId[it] }
}

@Composable
fun NowPlayingScreen(nav: AppNav) {
    val state by Arios.player.state.collectAsState()
    val song = currentSong(state)
    val palette = rememberArtPalette(song)
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    BackHandler { nav.playerOpen = false }

    Box(
        Modifier.fillMaxSize()
            .background(AriosColors.Black)
            .background(Brush.verticalGradient(listOf(palette.base.copy(alpha = 0.9f), palette.background, AriosColors.Black)))
            .clickable(enabled = false) {},
    ) {
        if (landscape) {
            Row(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    TopBar(nav, song)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CoverArt(song, Modifier.size(150.dp))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) { TitleBlock(state, song) }
                    }
                    SeekBar(state, Modifier.padding(top = 8.dp))
                    TransportControls(state, big = false)
                    ActionsRow(nav, state, song)
                }
                Spacer(Modifier.width(16.dp))
                Box(Modifier.weight(1f).fillMaxHeight().padding(vertical = 12.dp)) {
                    LyricsCard(nav, song, palette, Modifier.fillMaxSize(), expanded = true)
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).systemBarsPadding().padding(horizontal = 22.dp)
            ) {
                TopBar(nav, song)
                Spacer(Modifier.height(10.dp))
                CoverArt(song, Modifier.fillMaxWidth().aspectRatio(1f))
                Spacer(Modifier.height(24.dp))
                TitleBlock(state, song)
                Spacer(Modifier.height(14.dp))
                ActionsRow(nav, state, song)
                Spacer(Modifier.height(14.dp))
                SeekBar(state)
                Spacer(Modifier.height(4.dp))
                TransportControls(state)
                Spacer(Modifier.height(22.dp))
                LyricsCard(nav, song, palette, Modifier.fillMaxWidth().height(400.dp), expanded = false)
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun TopBar(nav: AppNav, song: Song?) {
    var menu by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(false) }
    var sleep by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val state by Arios.player.state.collectAsState()
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { nav.playerOpen = false }) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.close), Modifier.size(32.dp), tint = Color.White) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.playing_from), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
            Text(song?.album ?: "", style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null, tint = Color.White) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(stringResource(R.string.playback_speed) + "  (${state.speed}x)") }, { menu = false; speed = true }, leadingIcon = { Icon(Icons.Filled.Speed, null) })
                DropdownMenuItem({ Text(stringResource(R.string.sleep_timer)) }, { menu = false; sleep = true }, leadingIcon = { Icon(Icons.Filled.Bedtime, null) })
                DropdownMenuItem({ Text(stringResource(R.string.equalizer)) }, {
                    menu = false
                    val i = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                        .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, Arios.player.audioSessionId())
                        .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                        .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                    runCatching { context.startActivity(i) }.onFailure { nav.snack = context.getString(R.string.no_equalizer) }
                }, leadingIcon = { Icon(Icons.Filled.Equalizer, null) })
                if (song != null) {
                    DropdownMenuItem({ Text(stringResource(R.string.search_lyrics)) }, { menu = false; nav.lyricsSearchFor = song }, leadingIcon = { Icon(Icons.Filled.ManageSearch, null) })
                    DropdownMenuItem({ Text(stringResource(R.string.edit_lyrics)) }, { menu = false; nav.push(Screen.LyricsEditor(song.id)) }, leadingIcon = { Icon(Icons.Filled.EditNote, null) })
                    DropdownMenuItem({ Text(stringResource(R.string.go_album)) }, { menu = false; nav.push(Screen.AlbumDetail(song.albumId)) }, leadingIcon = { Icon(Icons.Filled.Album, null) })
                    DropdownMenuItem({ Text(stringResource(R.string.go_artist)) }, { menu = false; nav.push(Screen.ArtistDetail(song.artist)) }, leadingIcon = { Icon(Icons.Filled.Person, null) })
                    DropdownMenuItem({ Text(stringResource(R.string.details)) }, { menu = false; nav.menuSong = song }, leadingIcon = { Icon(Icons.Filled.Info, null) })
                }
            }
        }
    }
    if (speed) SpeedDialog(state.speed) { speed = false }
    if (sleep) SleepDialog { sleep = false }
}

@Composable
private fun CoverArt(song: Song?, modifier: Modifier) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val state by Arios.player.state.collectAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (state.isPlaying) 1f else 0.9f, label = "cover")
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    Box(
        modifier
            .graphicsLayer { translationX = offset.value; scaleX = scale; scaleY = scale; alpha = 1f - (kotlin.math.abs(offset.value) / 1200f) }
            .pointerInput(rtl) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val v = offset.value
                        scope.launch {
                            if (v < -180) { if (rtl) Arios.player.previous() else Arios.player.next() }
                            else if (v > 180) { if (rtl) Arios.player.next() else Arios.player.previous() }
                            offset.animateTo(0f)
                        }
                    },
                ) { _, d -> scope.launch { offset.snapTo(offset.value + d) } }
            }
            .shadow(24.dp, RoundedCornerShape(18.dp)),
    ) { Artwork(song?.artKey(), Modifier.fillMaxSize(), RoundedCornerShape(18.dp), 96.dp) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TitleBlock(state: PlayerUiState, song: Song?) {
    val favs by Arios.userStore.favorites.collectAsState()
    val fav = song != null && song.key in favs
    val unknown = stringResource(R.string.unknown)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                song?.title ?: state.title.ifEmpty { stringResource(R.string.nothing_playing) },
                style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1,
                modifier = Modifier.basicMarquee(),
            )
            Text(displayArtist(song?.artist ?: state.artist, unknown), style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { song?.let { Arios.userStore.toggleFavorite(it.key); com.arios.player.playback.PlaybackService.instance?.refreshExtras() } }) {
            Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, stringResource(R.string.add_favorite),
                tint = if (fav) AriosColors.Mint else Color.White, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun ActionsRow(nav: AppNav, state: PlayerUiState, song: Song?) {
    var speed by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionChip(Icons.Filled.Lyrics, stringResource(R.string.lyrics)) { nav.lyricsFull = true }
        ActionChip(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.queue)) { nav.queueOpen = true }
        ActionChip(Icons.Filled.Speed, "${state.speed}x") { speed = true }
        ActionChip(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.save)) { song?.let { nav.addToPlaylist = listOf(it) } }
        if (state.sleepEndsAt > 0) ActionChip(Icons.Filled.Bedtime, stringResource(R.string.timer_on), AriosColors.Mint) { Arios.player.setSleepTimer(0) }
    }
    if (speed) SpeedDialog(state.speed) { speed = false }
}

/** The coloured lyrics card under the controls (Spotify style); tap to open full-screen lyrics. */
@Composable
fun LyricsCard(nav: AppNav, song: Song?, palette: ArtPalette, modifier: Modifier, expanded: Boolean) {
    val ls = rememberLyricsState(song)
    val offsets by Arios.userStore.lyricOffsets.collectAsState()
    @Suppress("UNUSED_VARIABLE") val v by Arios.prefs.version.collectAsState()
    val prefs = Arios.prefs
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(palette.card)) {
        Row(Modifier.fillMaxWidth().clickable { nav.lyricsFull = true }.padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.lyrics), style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.weight(1f))
            (ls as? LyricsState.Ready)?.let {
                Text(it.lyrics.source, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
            }
            IconButton(onClick = { nav.lyricsFull = true }) { Icon(Icons.Filled.OpenInFull, stringResource(R.string.expand), tint = Color.White) }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LyricsBody(nav, song, ls, offsets[song?.key] ?: 0L, (prefs.lyricsFontSize * 0.82f).toInt(), compact = !expanded, onCard = true)
        }
    }
}

@Composable
fun LyricsBody(nav: AppNav, song: Song?, ls: LyricsState, offset: Long, fontSize: Int, compact: Boolean, onCard: Boolean = false, blurFar: Boolean = false) {
    val prefs = Arios.prefs
    val colors = LyricColors(
        active = Color.White,
        inactive = Color.White.copy(alpha = if (onCard) 0.45f else 0.38f),
        past = Color.White.copy(alpha = if (onCard) 0.6f else 0.5f),
        glow = Color.White.copy(alpha = 0.55f),
    )
    when (ls) {
        is LyricsState.Ready -> if (ls.lyrics.synced) SyncedLyricsView(
            ls.lyrics, offset, fontSize, colors, Modifier.fillMaxSize(),
            center = prefs.lyricsCenter, showTranslation = prefs.showTranslation, wordByWord = prefs.wordByWord,
            blurFar = blurFar, topFraction = if (compact) 0.18f else 0.32f,
            contentPadding = PaddingValues(vertical = 8.dp),
        ) { Arios.player.seekTo(it) }
        else PlainLyricsView(ls.lyrics, fontSize, Color.White, Modifier.fillMaxSize(), prefs.lyricsCenter) {
            Text(stringResource(R.string.not_synced_hint), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 12.dp))
        }
        LyricsState.Loading, LyricsState.Idle -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.finding_lyrics), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
            }
        }
        LyricsState.NotFound -> Column(
            Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.LyricsOff(), null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.no_lyrics), color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = { song?.let { nav.lyricsSearchFor = it } }) {
                    Icon(Icons.Filled.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.search))
                }
                FilledTonalButton(onClick = { song?.let { nav.push(Screen.LyricsEditor(it.id)) } }) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.add))
                }
            }
        }
    }
}

@Suppress("FunctionName")
private fun Icons.Filled.LyricsOff() = Icons.Filled.Lyrics
