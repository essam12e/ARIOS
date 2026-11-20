package com.arios.player.ui.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arios.player.Arios
import com.arios.player.R
import com.arios.player.artwork.artKey
import com.arios.player.data.*
import com.arios.player.ui.AppNav
import com.arios.player.ui.Screen
import com.arios.player.ui.components.*
import com.arios.player.ui.theme.AriosColors
import com.arios.player.ui.theme.bounceClick
import com.arios.player.ui.theme.glass
import com.arios.player.ui.theme.glowHalo
import com.arios.player.ui.theme.staggerIn
import androidx.compose.ui.draw.shadow
import kotlinx.coroutines.launch

/** Arch shape: the ARIOS signature for album covers. */
val ArchShape = RoundedCornerShape(topStartPercent = 50, topEndPercent = 50, bottomStartPercent = 14, bottomEndPercent = 14)

private data class TabDef(val title: Int, val icon: ImageVector)

private val tabs = listOf(
    TabDef(R.string.tab_folders, Icons.Filled.Folder),
    TabDef(R.string.tab_artists, Icons.Filled.Person),
    TabDef(R.string.tab_albums, Icons.Filled.Album),
    TabDef(R.string.tab_tracks, Icons.Filled.MusicNote),
    TabDef(R.string.tab_playlists, Icons.AutoMirrored.Filled.PlaylistPlay),
    TabDef(R.string.tab_favorites, Icons.Filled.Favorite),
)

@Composable
fun LibraryScreen(nav: AppNav, hasPermission: Boolean, requestPermission: () -> Unit, bottomPadding: Dp) {
    val lib by Arios.library.state.collectAsState()
    val prefsVersion by Arios.prefs.version.collectAsState()
    var tab by remember { mutableIntStateOf(Arios.prefs.libraryTab.coerceIn(0, tabs.lastIndex)) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        LibraryHeader(nav, lib, tab, prefsVersion)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tabs.forEachIndexed { i, t ->
                val sel = i == tab
                val selA by androidx.compose.animation.core.animateFloatAsState(if (sel) 1f else 0f, label = "tab")
                Row(
                    Modifier.staggerIn(i, 45)
                        .then(if (sel) Modifier.shadow(14.dp, CircleShape, ambientColor = AriosColors.Mint, spotColor = AriosColors.Mint) else Modifier)
                        .glass(CircleShape, fill = 0.06f)
                        .clip(CircleShape)
                        .background(AriosColors.brandHorizontal, alpha = selA)
                        .bounceClick { tab = i; Arios.prefs.libraryTab = i }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(t.icon, null, Modifier.size(18.dp), tint = if (sel) AriosColors.Black else AriosColors.SilverDim)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(t.title), color = if (sel) AriosColors.Black else AriosColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (!hasPermission) {
            EmptyState(Icons.Filled.LibraryMusic, stringResource(R.string.permission_title), stringResource(R.string.permission_text)) {
                Button(onClick = requestPermission) { Text(stringResource(R.string.grant)) }
            }
            return@Column
        }
        if (!lib.loaded) {
            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AriosColors.Silver) }
            return@Column
        }
        val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
        AnimatedContent(tab, transitionSpec = { com.arios.player.ui.theme.Motion.tab(targetState > initialState, rtl) }, label = "tab") { t ->
            Box(Modifier.fillMaxSize()) {
                when (t) {
                    0 -> FoldersTab(nav, lib.folders, bottomPadding)
                    1 -> ArtistsTab(nav, lib.artists, bottomPadding)
                    2 -> AlbumsTab(nav, lib.albums, bottomPadding, prefsVersion)
                    3 -> TracksTab(nav, lib.songs, bottomPadding, prefsVersion)
                    4 -> PlaylistsTab(nav, lib, bottomPadding)
                    else -> FavoritesTab(nav, lib, bottomPadding)
                }
            }
        }
    }
}

typealias Dp = androidx.compose.ui.unit.Dp

@Composable
private fun LibraryHeader(nav: AppNav, lib: LibraryState, tab: Int, @Suppress("UNUSED_PARAMETER") v: Int) {
    var sortOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.arios_logo), null, Modifier.size(46.dp).glowHalo(true, AriosColors.Mint.copy(alpha = 0.6f)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.library), style = MaterialTheme.typography.headlineMedium.copy(brush = AriosColors.brand))
            Text(stringResource(R.string.songs_count, lib.songs.size), style = MaterialTheme.typography.labelMedium, color = AriosColors.SilverDim)
        }
        if (tab == 2 || tab == 3) Box {
            FilledTonalIconButton(onClick = { sortOpen = true }) { Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.sort)) }
            SortMenu(sortOpen, tab == 2) { sortOpen = false }
        }
        if (tab == 2) FilledTonalIconButton(onClick = { Arios.prefs.albumGrid = !Arios.prefs.albumGrid }) {
            Icon(if (Arios.prefs.albumGrid) Icons.Filled.ViewList else Icons.Filled.GridView, null)
        }
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier.size(48.dp).glowHalo(true).clip(RoundedCornerShape(16.dp)).background(AriosColors.brand)
                .bounceClick { Arios.player.playSongs(lib.songs, shuffle = true); nav.playerOpen = true },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Shuffle, stringResource(R.string.shuffle_all), tint = AriosColors.Black) }
    }
}

@Composable
private fun SortMenu(open: Boolean, albums: Boolean, onDismiss: () -> Unit) {
    val p = Arios.prefs
    DropdownMenu(open, onDismiss) {
        val options = if (albums) listOf(0 to R.string.sort_title, 1 to R.string.sort_artist, 2 to R.string.sort_year)
        else listOf(SongSort.TITLE to R.string.sort_title, SongSort.ARTIST to R.string.sort_artist, SongSort.ALBUM to R.string.sort_album,
            SongSort.DATE to R.string.sort_date, SongSort.DURATION to R.string.sort_duration)
        val current = if (albums) p.albumSort else p.songSort
        options.forEach { (mode, label) ->
            DropdownMenuItem(
                text = { Text(stringResource(label)) },
                leadingIcon = { RadioButton(selected = current == mode, onClick = null) },
                onClick = { if (albums) p.albumSort = mode else p.songSort = mode; onDismiss() },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.sort_descending)) },
            leadingIcon = { Checkbox(checked = p.sortDescending, onCheckedChange = null) },
            onClick = { p.sortDescending = !p.sortDescending; onDismiss() },
        )
    }
}

/** List + alphabet strip on the physical left side. */
@Composable
private fun <T> IndexedList(
    items: List<T>,
    name: (T) -> String,
    showIndex: Boolean,
    bottomPadding: Dp,
    header: (@Composable () -> Unit)? = null,
    row: @Composable (Int, T) -> Unit,
) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val letters = remember(items, showIndex) {
        if (!showIndex) emptyList() else items.map { indexLetter(name(it)) }.distinct().sortedBy { letterWeight(it) }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().absolutePadding(left = if (letters.size > 1) 30.dp else 0.dp),
            contentPadding = PaddingValues(bottom = bottomPadding + 16.dp),
        ) {
            if (header != null) item { header() }
            itemsIndexed(items) { i, it -> row(i, it) }
        }
        AlphabetBar(
            letters,
            Modifier.align(AbsoluteAlignment.CenterLeft).padding(start = 4.dp, top = 8.dp, bottom = bottomPadding + 8.dp).fillMaxHeight(),
        ) { l ->
            val idx = items.indexOfFirst { indexLetter(name(it)) == l }
            if (idx >= 0) scope.launch { state.scrollToItem(idx + if (header != null) 1 else 0) }
        }
    }
}

@Composable
fun PlayShuffleHeader(songs: List<Song>, nav: AppNav) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.songs_count, songs.size), Modifier.weight(1f), color = AriosColors.SilverDim,
            style = MaterialTheme.typography.labelLarge)
        FilledTonalButton(onClick = { Arios.player.playSongs(songs, shuffle = true) }) {
            Icon(Icons.Filled.Shuffle, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.shuffle))
        }
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier.shadow(12.dp, CircleShape, ambientColor = AriosColors.Mint, spotColor = AriosColors.Mint).clip(CircleShape)
                .background(AriosColors.brandHorizontal).bounceClick { Arios.player.playSongs(songs); nav.playerOpen = true }
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp), tint = AriosColors.Black); Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.play), color = AriosColors.Black, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun SongList(nav: AppNav, songs: List<Song>, bottomPadding: Dp, showIndex: Boolean, header: (@Composable () -> Unit)? = null) {
    val player by Arios.player.state.collectAsState()
    val unknown = stringResource(R.string.unknown)
    IndexedList(songs, { it.title }, showIndex, bottomPadding, header = {
        Column { header?.invoke(); PlayShuffleHeader(songs, nav) }
    }) { i, s ->
        SongRow(s, s.id == player.songId, unknown, onClick = { Arios.player.playSongs(songs, i) }, onMore = { nav.menuSong = s },
            index = i, playing = player.isPlaying)
    }
}

@Composable
private fun TracksTab(nav: AppNav, songs: List<Song>, bottomPadding: Dp, v: Int) {
    val sorted = remember(songs, v) { songs.sortedBy(Arios.prefs.songSort, Arios.prefs.sortDescending) }
    if (songs.isEmpty()) { EmptyState(Icons.Filled.MusicOff, stringResource(R.string.no_songs), stringResource(R.string.no_songs_hint)); return }
    val showIndex = Arios.prefs.songSort == SongSort.TITLE
    SongList(nav, sorted, bottomPadding, showIndex)
}

@Composable
private fun AlbumsTab(nav: AppNav, albums: List<Album>, bottomPadding: Dp, v: Int) {
    val sorted = remember(albums, v) {
        val l = when (Arios.prefs.albumSort) {
            1 -> albums.sortedWith(compareBy(nameComparator) { it.artist })
            2 -> albums.sortedByDescending { it.year }
            else -> albums
        }
        if (Arios.prefs.sortDescending) l.reversed() else l
    }
    if (albums.isEmpty()) { EmptyState(Icons.Filled.Album, stringResource(R.string.no_songs)); return }
    val unknown = stringResource(R.string.unknown)
    val showIndex = Arios.prefs.albumSort == 0
    if (!Arios.prefs.albumGrid) {
        IndexedList(sorted, { it.title }, showIndex, bottomPadding) { _, a ->
            ListRow(a.cover, a.title, "${displayArtist(a.artist, unknown)} • ${a.songs.size}", ArchShape) { nav.push(Screen.AlbumDetail(a.id)) }
        }
        return
    }
    val state = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val letters = remember(sorted, showIndex) { if (showIndex) sorted.map { indexLetter(it.title) }.distinct().sortedBy { letterWeight(it) } else emptyList() }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            GridCells.Adaptive(160.dp), state = state,
            modifier = Modifier.fillMaxSize().absolutePadding(left = if (letters.size > 1) 30.dp else 0.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = bottomPadding + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(sorted, key = { _, a -> a.id }) { i, a -> AlbumCard(i, a, unknown, { nav.push(Screen.AlbumDetail(a.id)) }) { Arios.player.playSongs(a.songs) } }
        }
        AlphabetBar(letters, Modifier.align(AbsoluteAlignment.CenterLeft).padding(start = 4.dp, top = 8.dp, bottom = bottomPadding + 8.dp).fillMaxHeight()) { l ->
            val idx = sorted.indexOfFirst { indexLetter(it.title) == l }
            if (idx >= 0) scope.launch { state.scrollToItem(idx) }
        }
    }
}

@Composable
private fun AlbumCard(index: Int, a: Album, unknown: String, onClick: () -> Unit, onPlay: () -> Unit) {
    Column(
        Modifier.staggerIn(index, 50).glass(RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).bounceClick(onClick = onClick).padding(12.dp)
    ) {
        Box {
            Artwork(a.cover.artKey(), Modifier.fillMaxWidth().aspectRatio(1f), ArchShape, 40.dp)
            Box(
                Modifier.align(Alignment.TopEnd).size(38.dp).glowHalo(false).clip(CircleShape).background(AriosColors.brand).bounceClick(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.PlayArrow, null, Modifier.size(22.dp), tint = AriosColors.Black) }
        }
        Spacer(Modifier.height(10.dp))
        Text(a.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(displayArtist(a.artist, unknown), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = AriosColors.SilverDim)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("♪ ${a.songs.size}")
            if (a.year > 0) Pill("${a.year}", color = Color(0xFF2B2F36))
        }
    }
}

@Composable
fun ListRow(cover: Song?, title: String, subtitle: String, shape: androidx.compose.ui.graphics.Shape, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Box(Modifier.size(54.dp).glass(shape, fill = 0.08f), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = AriosColors.Mint)
        } else Artwork(cover?.artKey(), Modifier.size(54.dp), shape, 22.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = AriosColors.SilverDim)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = AriosColors.Steel, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ArtistsTab(nav: AppNav, artists: List<Artist>, bottomPadding: Dp) {
    val unknown = stringResource(R.string.unknown)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    IndexedList(artists, { it.name }, true, bottomPadding) { _, a ->
        ListRow(a.cover, displayArtist(a.name, unknown), ctx.getString(R.string.artist_meta, a.albums.size, a.songs.size), CircleShape) {
            nav.push(Screen.ArtistDetail(a.name))
        }
    }
}

@Composable
private fun FoldersTab(nav: AppNav, folders: List<Folder>, bottomPadding: Dp) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    IndexedList(folders, { it.name }, true, bottomPadding) { _, f ->
        ListRow(null, f.name, ctx.getString(R.string.folder_meta, f.songs.size, f.path.removePrefix("/storage/emulated/0")), RoundedCornerShape(14.dp), Icons.Filled.Folder) {
            nav.push(Screen.FolderDetail(f.path))
        }
    }
}

@Composable
private fun PlaylistsTab(nav: AppNav, lib: LibraryState, bottomPadding: Dp) {
    val playlists by Arios.userStore.playlists.collectAsState()
    var create by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val downloadedCount = lib.songs.count { it.folder.endsWith("/ARIOS") || it.path.contains("/Music/ARIOS/") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
        item {
            ListRow(null, stringResource(R.string.new_playlist), stringResource(R.string.new_playlist_hint), RoundedCornerShape(14.dp), Icons.Filled.Add) { create = true }
        }
        item {
            ListRow(null, stringResource(R.string.tab_favorites), ctx.getString(R.string.songs_count, Arios.userStore.favorites.value.size), RoundedCornerShape(14.dp), Icons.Filled.Favorite) {
                nav.push(Screen.Favorites)
            }
        }
        if (downloadedCount > 0) item {
            ListRow(null, stringResource(R.string.downloaded_songs), ctx.getString(R.string.songs_count, downloadedCount), RoundedCornerShape(14.dp), Icons.Filled.DownloadDone) {
                val f = lib.folders.firstOrNull { it.path.endsWith("/ARIOS") }
                if (f != null) nav.push(Screen.FolderDetail(f.path))
            }
        }
        items(playlists, key = { it.id }) { p ->
            val cover = p.songKeys.firstNotNullOfOrNull { lib.byKey[it] }
            ListRow(cover, p.name, ctx.getString(R.string.songs_count, p.songKeys.size), RoundedCornerShape(14.dp)) { nav.push(Screen.PlaylistDetail(p.id)) }
        }
    }
    if (create) TextInputDialog(stringResource(R.string.new_playlist), "", stringResource(R.string.create), { create = false }) {
        Arios.userStore.createPlaylist(it); create = false
    }
}

@Composable
private fun FavoritesTab(nav: AppNav, lib: LibraryState, bottomPadding: Dp) {
    val favs by Arios.userStore.favorites.collectAsState()
    val songs = remember(favs, lib) { lib.songs.filter { it.key in favs } }
    if (songs.isEmpty()) { EmptyState(Icons.Filled.FavoriteBorder, stringResource(R.string.no_favorites), stringResource(R.string.no_favorites_hint)); return }
    SongList(nav, songs, bottomPadding, true)
}
