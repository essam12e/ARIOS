package com.arios.player.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arios.player.Arios
import com.arios.player.R
import com.arios.player.artwork.artKey
import com.arios.player.data.Song
import com.arios.player.ui.AppNav
import com.arios.player.ui.Screen
import com.arios.player.ui.components.*
import com.arios.player.ui.theme.AriosColors

@Composable
fun DetailScreen(nav: AppNav, screen: Screen, bottomPadding: Dp) {
    val lib by Arios.library.state.collectAsState()
    val playlists by Arios.userStore.playlists.collectAsState()
    val favs by Arios.userStore.favorites.collectAsState()
    val unknown = stringResource(R.string.unknown)
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    when (screen) {
        is Screen.AlbumDetail -> {
            val a = lib.albums.firstOrNull { it.id == screen.albumId } ?: return Missing(nav)
            DetailScaffold(nav, a.title, "${displayArtist(a.artist, unknown)}${if (a.year > 0) " • ${a.year}" else ""}", a.cover, ArchShape, a.songs, bottomPadding)
        }
        is Screen.ArtistDetail -> {
            val ar = lib.artists.firstOrNull { it.name.equals(screen.name, true) } ?: return Missing(nav)
            DetailScaffold(nav, displayArtist(ar.name, unknown), stringResource(R.string.artist_meta, ar.albums.size, ar.songs.size), ar.cover, CircleShape, ar.songs, bottomPadding) {
                if (ar.albums.size > 1 || (ar.albums.size == 1 && ar.albums[0].songs.size < ar.songs.size)) {
                    Text(stringResource(R.string.tab_albums), Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.titleMedium)
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(ar.albums, key = { it.id }) { al ->
                            Column(Modifier.width(120.dp).clip(RoundedCornerShape(16.dp)).clickableNoRipple { nav.push(Screen.AlbumDetail(al.id)) }) {
                                Artwork(al.cover.artKey(), Modifier.size(120.dp), ArchShape)
                                Spacer(Modifier.height(6.dp))
                                Text(al.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
        is Screen.FolderDetail -> {
            val f = lib.folders.firstOrNull { it.path == screen.path } ?: return Missing(nav)
            DetailScaffold(nav, f.name, f.path, f.songs.firstOrNull(), RoundedCornerShape(24.dp), f.songs, bottomPadding)
        }
        is Screen.Favorites -> {
            val songs = lib.songs.filter { it.key in favs }
            DetailScaffold(nav, stringResource(R.string.tab_favorites), stringResource(R.string.songs_count, songs.size), songs.firstOrNull(), RoundedCornerShape(24.dp), songs, bottomPadding, icon = Icons.Filled.Favorite)
        }
        is Screen.PlaylistDetail -> {
            val p = playlists.firstOrNull { it.id == screen.id } ?: return Missing(nav)
            val songs = p.songKeys.mapNotNull { lib.byKey[it] }
            DetailScaffold(
                nav, p.name, stringResource(R.string.songs_count, songs.size), songs.firstOrNull(), RoundedCornerShape(24.dp), songs, bottomPadding,
                actions = {
                    IconButton(onClick = { rename = true }) { Icon(Icons.Filled.Edit, stringResource(R.string.rename)) }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                },
                rowExtra = { s ->
                    IconButton(onClick = { Arios.userStore.removeFromPlaylist(p.id, s.key) }) {
                        Icon(Icons.Filled.RemoveCircleOutline, stringResource(R.string.remove), tint = AriosColors.Steel)
                    }
                },
            )
            if (rename) TextInputDialog(stringResource(R.string.rename), p.name, stringResource(R.string.save), { rename = false }) {
                Arios.userStore.renamePlaylist(p.id, it); rename = false
            }
            if (confirmDelete) AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text(stringResource(R.string.delete_playlist_q, p.name)) },
                confirmButton = { TextButton(onClick = { Arios.userStore.deletePlaylist(p.id); confirmDelete = false; nav.back() }) { Text(stringResource(R.string.delete)) } },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        is Screen.LyricsEditor -> Unit
    }
}

@Composable
private fun Missing(nav: AppNav) {
    LaunchedEffect(Unit) { nav.back() }
}

fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
}

@Composable
private fun DetailScaffold(
    nav: AppNav,
    title: String,
    subtitle: String,
    cover: Song?,
    shape: Shape,
    songs: List<Song>,
    bottomPadding: Dp,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    actions: @Composable RowScope.() -> Unit = {},
    rowExtra: (@Composable (Song) -> Unit)? = null,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val player by Arios.player.state.collectAsState()
    val unknown = stringResource(R.string.unknown)
    Box(Modifier.fillMaxSize()) {
        // blurred cover backdrop
        if (cover != null) Artwork(
            cover.artKey(), Modifier.fillMaxWidth().height(360.dp).blur(60.dp).background(Color.Black), RoundedCornerShape(0.dp)
        )
        Box(Modifier.fillMaxWidth().height(361.dp).background(
            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Transparent))
        ))
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding + 16.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                        Spacer(Modifier.weight(1f))
                        actions()
                    }
                    if (icon != null && cover == null) Box(Modifier.size(200.dp).clip(shape).background(AriosColors.SurfaceHigh), contentAlignment = Alignment.Center) {
                        Icon(icon, null, Modifier.size(80.dp), tint = AriosColors.Silver)
                    } else com.arios.player.ui.theme.GlowBehind(Modifier.size(220.dp), AriosColors.Mint) {
                        Artwork(cover?.artKey(), Modifier.fillMaxSize(), shape, 64.dp)
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = AriosColors.SilverDim, textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                    Spacer(Modifier.height(4.dp))
                }
                Column { extra() }
                if (songs.isNotEmpty()) PlayShuffleHeader(songs, nav)
                else EmptyState(Icons.Filled.MusicOff, stringResource(R.string.empty_list))
            }
            itemsIndexed(songs) { i, s ->
                SongRow(s, s.id == player.songId, unknown, onClick = { Arios.player.playSongs(songs, i) }, onMore = { nav.menuSong = s },
                    trailingExtra = rowExtra?.let { { it(s) } }, index = i, playing = player.isPlaying)
            }
        }
    }
}
