package com.arios.player.ui.player

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arios.player.Arios
import com.arios.player.R
import com.arios.player.lyrics.LrcParser
import com.arios.player.lyrics.LyricsCandidate
import com.arios.player.ui.AppNav
import com.arios.player.ui.components.Pill
import com.arios.player.ui.components.formatTime
import com.arios.player.ui.theme.AriosColors
import kotlinx.coroutines.launch

/** Manual lyrics search across LRCLIB / NetEase / QQ Music / Lyrics.ovh + import of .lrc files. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSearchSheet(nav: AppNav) {
    val song = nav.lyricsSearchFor ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val q0 = remember(song) { Arios.lyrics.queryFor(song) }
    var title by remember(song) { mutableStateOf(q0.title) }
    var artist by remember(song) { mutableStateOf(q0.artist) }
    var loading by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf<LyricsCandidate?>(null) }
    var results by remember(song) { mutableStateOf<List<LyricsCandidate>?>(null) }

    fun search() {
        loading = true
        scope.launch {
            results = Arios.lyrics.searchAll(q0.copy(title = title, artist = artist))
            loading = false
        }
    }
    LaunchedEffect(song) { search() }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            if (text != null && LrcParser.parse(text, "LRC", song.durationMs) != null) {
                Arios.lyrics.saveRaw(song, text, "LRC")
                nav.snack = context.getString(R.string.lyrics_saved)
                nav.lyricsSearchFor = null
            } else nav.snack = context.getString(R.string.invalid_lrc)
        }
    }

    ModalBottomSheet(onDismissRequest = { nav.lyricsSearchFor = null }, containerColor = AriosColors.Surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.search_lyrics), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(artist, { artist = it }, label = { Text(stringResource(R.string.artist)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { search() }, enabled = !loading) { Icon(Icons.Filled.Search, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.search)) }
                OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) { Icon(Icons.Filled.FileOpen, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.import_lrc)) }
                if (Arios.lyrics.isCached(song)) TextButton(onClick = {
                    scope.launch { Arios.lyrics.delete(song); nav.lyricsSearchFor = null }
                }) { Text(stringResource(R.string.remove)) }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = AriosColors.Mint)
        LazyColumn(Modifier.fillMaxHeight(0.75f), contentPadding = PaddingValues(bottom = 24.dp)) {
            val list = results
            if (list != null && list.isEmpty() && !loading) item {
                Text(stringResource(R.string.no_results), Modifier.padding(20.dp), color = AriosColors.SilverDim)
            }
            items(list ?: emptyList()) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = applying == null) {
                        applying = c
                        scope.launch {
                            val l = runCatching { c.load() }.getOrNull()
                            if (l != null && l.lines.isNotEmpty()) {
                                Arios.lyrics.save(song, l, manual = false)
                                nav.snack = context.getString(R.string.lyrics_saved)
                                nav.lyricsSearchFor = null
                            } else nav.snack = context.getString(R.string.no_lyrics)
                            applying = null
                        }
                    }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        Text(listOfNotNull(c.artist, c.album).joinToString(" • "), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = AriosColors.SilverDim)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill(c.sourceName)
                            c.durationSec?.let { Pill(formatTime(it * 1000L)) }
                            if (c.syncedHint == true) Pill(stringResource(R.string.synced), color = androidx.compose.ui.graphics.Color(0xFF1E3A2E))
                        }
                    }
                    if (applying == c) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Download, null, tint = AriosColors.SilverDim)
                }
            }
        }
    }
}
