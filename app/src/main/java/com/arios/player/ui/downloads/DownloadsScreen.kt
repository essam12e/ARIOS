package com.arios.player.ui.downloads

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.arios.player.Arios
import com.arios.player.R
import com.arios.player.download.ArchiveSource
import com.arios.player.download.DlState
import com.arios.player.download.DownloadTask
import com.arios.player.ui.AppNav
import com.arios.player.ui.components.EmptyState
import com.arios.player.ui.components.Pill
import com.arios.player.ui.components.formatTime
import com.arios.player.ui.library.ArchShape
import com.arios.player.ui.theme.AriosColors
import kotlinx.coroutines.launch

@Composable
fun DownloadsScreen(nav: AppNav, bottomPadding: androidx.compose.ui.unit.Dp) {
    val tasks by Arios.downloader.tasks.collectAsState()
    val active = tasks.count { it.state == DlState.RUNNING || it.state == DlState.QUEUED }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(stringResource(R.string.downloads), Modifier.padding(start = 20.dp, top = 16.dp), style = MaterialTheme.typography.headlineMedium)
        TabRow(nav.downloadsTab, containerColor = androidx.compose.ui.graphics.Color.Transparent, modifier = Modifier.padding(top = 8.dp)) {
            Tab(nav.downloadsTab == 0, { nav.downloadsTab = 0 }, text = { Text(stringResource(R.string.discover)) }, icon = { Icon(Icons.Filled.TravelExplore, null) })
            Tab(nav.downloadsTab == 1, { nav.downloadsTab = 1 }, text = {
                Text(stringResource(R.string.my_downloads) + if (active > 0) " ($active)" else "")
            }, icon = { Icon(Icons.Filled.DownloadForOffline, null) })
        }
        if (nav.downloadsTab == 0) DiscoverPane(nav, bottomPadding) else TasksPane(tasks, bottomPadding)
    }
}

@Composable
private fun DiscoverPane(nav: AppNav, bottomPadding: androidx.compose.ui.unit.Dp) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<ArchiveSource.Item>?>(null) }
    var opened by remember { mutableStateOf<ArchiveSource.Item?>(null) }
    var linkDialog by remember { mutableStateOf(false) }
    fun search(q: String = query) {
        if (q.isBlank()) return
        loading = true
        scope.launch { results = ArchiveSource.search(q); loading = false }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            placeholder = { Text(stringResource(R.string.archive_search_hint)) }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) }, shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
            trailingIcon = { IconButton(onClick = { search() }) { Icon(Icons.Filled.ArrowForward, null) } },
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { linkDialog = true }, label = { Text(stringResource(R.string.download_link)) }, leadingIcon = { Icon(Icons.Filled.Link, null, Modifier.size(18.dp)) })
            listOf("jazz", "classical", "Grateful Dead", "oud", "lofi", "piano", "78rpm").forEach { s ->
                SuggestionChip(onClick = { query = s; search(s) }, label = { Text(s) })
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp), color = AriosColors.Mint)
        val list = results
        when {
            list == null -> EmptyState(Icons.Filled.LibraryMusic, stringResource(R.string.discover_title), stringResource(R.string.discover_text))
            list.isEmpty() && !loading -> EmptyState(Icons.Filled.SearchOff, stringResource(R.string.no_results))
            else -> LazyColumn(contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
                items(list, key = { it.identifier }) { item ->
                    Row(Modifier.fillMaxWidth().clickable { opened = item }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(item.artUrl, null, Modifier.size(58.dp).clip(ArchShape).background(AriosColors.SurfaceHigh), contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            Text(item.creator, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = AriosColors.SilverDim)
                        }
                        Icon(Icons.Filled.ChevronRight, null, tint = AriosColors.Steel)
                    }
                }
            }
        }
    }
    opened?.let { ItemSheet(nav, it) { opened = null } }
    if (linkDialog) LinkDialog(nav) { linkDialog = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemSheet(nav: AppNav, item: ArchiveSource.Item, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val details by produceState<ArchiveSource.Details?>(null, item) { value = ArchiveSource.details(item) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(item) { kotlinx.coroutines.delay(20000); if (details == null) failed = true }
    val tasks by Arios.downloader.tasks.collectAsState()
    val queuedUrls = tasks.map { it.url }.toSet()
    var format by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AriosColors.Surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(item.artUrl, null, Modifier.size(84.dp).clip(ArchShape), contentScale = ContentScale.Crop)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(details?.item?.title ?: item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(details?.item?.creator ?: item.creator, color = AriosColors.SilverDim, maxLines = 1)
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.pageUrl))) }, contentPadding = PaddingValues(0.dp)) {
                    Text("archive.org", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        val lic = details?.item?.license
        Text(
            if (lic != null) stringResource(R.string.license_x, lic) else stringResource(R.string.license_archive),
            Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = AriosColors.SilverDim,
        )
        val d = details
        if (d == null) {
            if (failed) Text(stringResource(R.string.load_failed), Modifier.padding(20.dp))
            else LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp), color = AriosColors.Mint)
            Spacer(Modifier.height(120.dp))
            return@ModalBottomSheet
        }
        val formats = d.tracks.flatMap { t -> t.options.map { it.quality } }.distinct()
        val chosen = format ?: formats.firstOrNull { it.startsWith("MP3") } ?: formats.firstOrNull()
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            formats.forEach { f -> FilterChip(selected = f == chosen, onClick = { format = f }, label = { Text(f) }) }
        }
        fun enqueue(t: ArchiveSource.Track) {
            val opt = t.options.firstOrNull { it.quality == chosen } ?: t.options.first()
            Arios.downloader.enqueue(t.title, t.artist, t.album, opt.url, opt.ext, opt.quality, item.artUrl, "Internet Archive")
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.songs_count, d.tracks.size), Modifier.weight(1f), color = AriosColors.SilverDim)
            Button(onClick = { d.tracks.forEach(::enqueue); nav.snack = context.getString(R.string.download_started); nav.downloadsTab = 1; onDismiss() },
                colors = ButtonDefaults.buttonColors(AriosColors.Silver, AriosColors.Black)) {
                Icon(Icons.Filled.Download, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.download_all))
            }
        }
        LazyColumn(Modifier.fillMaxHeight(0.7f), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(d.tracks) { t ->
                val opt = t.options.firstOrNull { it.quality == chosen } ?: t.options.first()
                val queued = opt.url in queuedUrls
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill(opt.quality)
                            if (t.lengthSec > 0) Pill(formatTime(t.lengthSec * 1000L))
                            if (opt.size > 0) Pill("%.1f MB".format(opt.size / 1048576f))
                        }
                    }
                    IconButton(enabled = !queued, onClick = { enqueue(t); nav.snack = context.getString(R.string.download_started) }) {
                        Icon(if (queued) Icons.Filled.DownloadDone else Icons.Filled.Download, stringResource(R.string.download),
                            tint = if (queued) AriosColors.Mint else AriosColors.Silver)
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkDialog(nav: AppNav, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var url by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.download_link)) },
        text = {
            Column {
                Text(stringResource(R.string.download_link_hint), style = MaterialTheme.typography.bodySmall, color = AriosColors.SilverDim)
                OutlinedTextField(url, { url = it }, label = { Text("URL") }, singleLine = true)
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.title)) }, singleLine = true)
                OutlinedTextField(artist, { artist = it }, label = { Text(stringResource(R.string.artist)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = url.startsWith("http"), onClick = {
                val path = Uri.parse(url).lastPathSegment ?: "track.mp3"
                val ext = path.substringAfterLast('.', "mp3").lowercase().takeIf { it.length in 2..4 } ?: "mp3"
                Arios.downloader.enqueue(title.ifBlank { path.substringBeforeLast('.') }, artist, "", url.trim(), ext, ext.uppercase(), null, "Link")
                nav.snack = context.getString(R.string.download_started); nav.downloadsTab = 1; onDismiss()
            }) { Text(stringResource(R.string.download)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun TasksPane(tasks: List<DownloadTask>, bottomPadding: androidx.compose.ui.unit.Dp) {
    var confirm by remember { mutableStateOf<DownloadTask?>(null) }
    if (tasks.isEmpty()) { EmptyState(Icons.Filled.DownloadForOffline, stringResource(R.string.no_downloads), stringResource(R.string.no_downloads_hint)); return }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { Arios.downloader.pauseAll() }) { Text(stringResource(R.string.pause_all)) }
            TextButton(onClick = { Arios.downloader.resumeAll() }) { Text(stringResource(R.string.resume_all)) }
            TextButton(onClick = { Arios.downloader.clearFinished() }) { Text(stringResource(R.string.clear_finished)) }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
            items(tasks, key = { it.id }) { t ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(AriosColors.Surface).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            Text(listOf(t.artist, t.quality, t.source).filter { it.isNotBlank() }.joinToString(" • "), maxLines = 1,
                                style = MaterialTheme.typography.bodySmall, color = AriosColors.SilverDim)
                        }
                        when (t.state) {
                            DlState.RUNNING, DlState.QUEUED -> IconButton(onClick = { Arios.downloader.pause(t.id) }) { Icon(Icons.Filled.Pause, stringResource(R.string.pause)) }
                            DlState.PAUSED, DlState.FAILED -> IconButton(onClick = { Arios.downloader.resume(t.id) }) { Icon(Icons.Filled.PlayArrow, stringResource(R.string.resume)) }
                            DlState.DONE -> Icon(Icons.Filled.CheckCircle, null, tint = AriosColors.Mint, modifier = Modifier.padding(12.dp))
                        }
                        IconButton(onClick = { if (t.state == DlState.DONE) confirm = t else Arios.downloader.remove(t.id, false) }) {
                            Icon(Icons.Filled.Delete, stringResource(R.string.delete), tint = AriosColors.SilverDim)
                        }
                    }
                    if (t.state != DlState.DONE) {
                        Spacer(Modifier.height(6.dp))
                        if (t.state == DlState.RUNNING && t.total <= 0) LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = AriosColors.Mint)
                        else LinearProgressIndicator({ t.progress }, Modifier.fillMaxWidth().clip(CircleShape), color = if (t.state == DlState.FAILED) Color(0xFFE57373) else AriosColors.Mint)
                        Spacer(Modifier.height(4.dp))
                        val label = when (t.state) {
                            DlState.QUEUED -> stringResource(R.string.state_queued)
                            DlState.PAUSED -> stringResource(R.string.state_paused)
                            DlState.FAILED -> stringResource(R.string.state_failed, t.error ?: "")
                            else -> ""
                        }
                        Text(
                            "%.1f / %.1f MB  %s".format(t.downloaded / 1048576f, t.total / 1048576f, label),
                            style = MaterialTheme.typography.labelSmall, color = AriosColors.SilverDim,
                        )
                    }
                }
            }
        }
    }
    confirm?.let { t ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(t.title) },
            text = { Text(stringResource(R.string.delete_download_q)) },
            confirmButton = { TextButton(onClick = { Arios.downloader.remove(t.id, true); confirm = null }) { Text(stringResource(R.string.delete_file)) } },
            dismissButton = { TextButton(onClick = { Arios.downloader.remove(t.id, false); confirm = null }) { Text(stringResource(R.string.remove_from_list)) } },
        )
    }
}
