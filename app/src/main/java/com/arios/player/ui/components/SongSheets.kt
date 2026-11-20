package com.arios.player.ui.components

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arios.player.Arios
import com.arios.player.R
import com.arios.player.artwork.artKey
import com.arios.player.data.Song
import com.arios.player.ui.AppNav
import com.arios.player.ui.Screen
import com.arios.player.ui.theme.AriosColors

@Composable
fun SheetItem(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = AriosColors.Silver)
        Spacer(Modifier.width(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongMenuSheet(nav: AppNav) {
    val song = nav.menuSong ?: return
    val context = LocalContext.current
    val favs by Arios.userStore.favorites.collectAsState()
    val fav = song.key in favs
    var info by remember { mutableStateOf(false) }
    val unknown = stringResource(R.string.unknown)
    ModalBottomSheet(onDismissRequest = { nav.menuSong = null }, containerColor = AriosColors.Surface) {
        Column(Modifier.navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(song.artKey(), Modifier.size(56.dp), RoundedCornerShape(8.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(displayArtist(song.artist, unknown), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = AriosColors.SurfaceHigh)
            fun close(block: () -> Unit) { block(); nav.menuSong = null }
            SheetItem(Icons.Filled.SkipNext, stringResource(R.string.play_next)) { close { Arios.player.playNext(listOf(song)); nav.snack = context.getString(R.string.added_next) } }
            SheetItem(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.add_to_queue)) { close { Arios.player.enqueue(listOf(song)); nav.snack = context.getString(R.string.added_queue) } }
            SheetItem(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.add_to_playlist)) { close { nav.addToPlaylist = listOf(song) } }
            SheetItem(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                stringResource(if (fav) R.string.remove_favorite else R.string.add_favorite)) { close { Arios.userStore.toggleFavorite(song.key) } }
            SheetItem(Icons.Filled.Album, stringResource(R.string.go_album)) { close { nav.push(Screen.AlbumDetail(song.albumId)) } }
            SheetItem(Icons.Filled.Person, stringResource(R.string.go_artist)) { close { nav.push(Screen.ArtistDetail(song.artist)) } }
            SheetItem(Icons.Filled.Lyrics, stringResource(R.string.edit_lyrics)) { close { nav.push(Screen.LyricsEditor(song.id)) } }
            SheetItem(Icons.Filled.Edit, stringResource(R.string.edit_tags)) { close { nav.push(Screen.TagEditor(song.id)) } }
            SheetItem(Icons.Filled.FormatQuote, stringResource(R.string.share_lyrics)) { close { nav.push(Screen.ShareLyrics(song.id)) } }
            SheetItem(Icons.Filled.Share, stringResource(R.string.share)) {
                close {
                    val i = Intent(Intent.ACTION_SEND).setType(song.mimeType).putExtra(Intent.EXTRA_STREAM, song.uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(i, null))
                }
            }
            SheetItem(Icons.Filled.Info, stringResource(R.string.details)) { info = true }
        }
    }
    if (info) {
        AlertDialog(
            onDismissRequest = { info = false },
            confirmButton = { TextButton(onClick = { info = false }) { Text(stringResource(R.string.ok)) } },
            title = { Text(song.title) },
            text = {
                Column {
                    listOf(
                        R.string.artist to displayArtist(song.artist, unknown),
                        R.string.album to song.album,
                        R.string.duration to formatTime(song.durationMs),
                        R.string.format to "${song.ext} • ${song.mimeType}",
                        R.string.size to "%.1f MB".format(song.size / 1048576f),
                        R.string.path to song.path,
                    ).forEach { (k, v) ->
                        Text(stringResource(k), style = MaterialTheme.typography.labelMedium, color = AriosColors.SilverDim)
                        Text(v, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.size(8.dp))
                    }
                }
            },
        )
    }
}

@Composable
fun AddToPlaylistDialog(nav: AppNav) {
    val songs = nav.addToPlaylist ?: return
    val playlists by Arios.userStore.playlists.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { nav.addToPlaylist = null },
        title = { Text(stringResource(R.string.add_to_playlist)) },
        text = {
            if (creating) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.playlist_name)) })
            } else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item { SheetItem(Icons.Filled.Add, stringResource(R.string.new_playlist)) { creating = true } }
                items(playlists, key = { it.id }) { p ->
                    SheetItem(Icons.AutoMirrored.Filled.QueueMusic, "${p.name}  (${p.songKeys.size})") {
                        Arios.userStore.addToPlaylist(p.id, songs.map { it.key })
                        nav.snack = context.getString(R.string.added_to, p.name)
                        nav.addToPlaylist = null
                    }
                }
            }
        },
        confirmButton = {
            if (creating) TextButton(enabled = name.isNotBlank(), onClick = {
                val p = Arios.userStore.createPlaylist(name.trim(), songs.map { it.key })
                nav.snack = context.getString(R.string.added_to, p.name)
                nav.addToPlaylist = null
            }) { Text(stringResource(R.string.create)) }
        },
        dismissButton = { TextButton(onClick = { nav.addToPlaylist = null }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun TextInputDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
