package com.arios.player.ui.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.arios.player.Arios
import com.arios.player.R
import com.arios.player.ui.AppNav
import com.arios.player.ui.Screen
import com.arios.player.ui.components.EmptyState
import com.arios.player.ui.components.SongRow
import com.arios.player.ui.components.displayArtist
import com.arios.player.ui.library.ArchShape
import com.arios.player.ui.library.ListRow
import com.arios.player.ui.theme.AriosColors

private fun norm(s: String) = s.lowercase()
    .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي')

@Composable
fun SearchScreen(nav: AppNav, bottomPadding: androidx.compose.ui.unit.Dp) {
    val lib by Arios.library.state.collectAsState()
    val player by Arios.player.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val q = norm(query.trim())
    val songs = remember(q, lib) { if (q.isEmpty()) emptyList() else lib.songs.filter { norm(it.title).contains(q) || norm(it.artist).contains(q) || norm(it.album).contains(q) } }
    val albums = remember(q, lib) { if (q.isEmpty()) emptyList() else lib.albums.filter { norm(it.title).contains(q) || norm(it.artist).contains(q) }.take(8) }
    val artists = remember(q, lib) { if (q.isEmpty()) emptyList() else lib.artists.filter { norm(it.name).contains(q) }.take(8) }
    val unknown = stringResource(R.string.unknown)
    val ctx = androidx.compose.ui.platform.LocalContext.current

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(stringResource(R.string.search), Modifier.padding(start = 20.dp, top = 16.dp), style = MaterialTheme.typography.headlineMedium)
        TextField(
            query, { query = it },
            modifier = Modifier.fillMaxWidth().padding(16.dp).focusRequester(focus),
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, null) } },
            singleLine = true, shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = AriosColors.SurfaceHigh, unfocusedContainerColor = AriosColors.SurfaceHigh,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
        )
        if (q.isNotEmpty() && songs.isEmpty() && albums.isEmpty() && artists.isEmpty()) {
            EmptyState(Icons.Filled.SearchOff, stringResource(R.string.no_results)); return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
            if (artists.isNotEmpty()) {
                item { Header(stringResource(R.string.tab_artists)) }
                items(artists, key = { "ar" + it.name }) { a ->
                    ListRow(a.cover, displayArtist(a.name, unknown), ctx.getString(R.string.artist_meta, a.albums.size, a.songs.size), CircleShape) { nav.push(Screen.ArtistDetail(a.name)) }
                }
            }
            if (albums.isNotEmpty()) {
                item { Header(stringResource(R.string.tab_albums)) }
                items(albums, key = { "al" + it.id }) { a ->
                    ListRow(a.cover, a.title, displayArtist(a.artist, unknown), ArchShape) { nav.push(Screen.AlbumDetail(a.id)) }
                }
            }
            if (songs.isNotEmpty()) {
                item { Header(stringResource(R.string.tab_tracks)) }
                items(songs.size) { i ->
                    val s = songs[i]
                    SongRow(s, s.id == player.songId, unknown, onClick = { Arios.player.playSongs(songs, i) }, onMore = { nav.menuSong = s })
                }
            }
        }
    }
}

@Composable
private fun Header(t: String) = Text(t, Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium, color = AriosColors.SilverDim)
