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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arios.player.Arios
import com.arios.player.R
import com.arios.player.lyrics.LrcParser
import com.arios.player.lyrics.Lyrics
import com.arios.player.lyrics.LyricsQuery
import com.arios.player.lyrics.ScoredCandidate
import com.arios.player.ui.AppNav
import com.arios.player.ui.components.Pill
import com.arios.player.ui.components.formatTime
import com.arios.player.ui.theme.AriosColors
import com.arios.player.ui.theme.lyricStyle
import kotlinx.coroutines.launch

/** Manual lyrics search: clean query, ranked results, preview before saving, .lrc import. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSearchSheet(nav: AppNav) {
    val song = nav.lyricsSearchFor ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auto = remember(song) { Arios.lyrics.queriesFor(song) }
    var title by remember(song) { mutableStateOf(auto.first().title) }
    var artist by remember(song) { mutableStateOf(auto.first().artist) }
    var loading by remember { mutableStateOf(false) }
    var results by remember(song) { mutableStateOf<List<ScoredCandidate>?>(null) }
    var showWeak by remember(song) { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Pair<ScoredCandidate, Lyrics?>?>(null) }
    var previewLoading by remember { mutableStateOf(false) }

    fun search() {
        loading = true
        scope.launch {
            val typed = LyricsQuery(title.trim(), artist.trim(), null, song.durationMs)
            val qs = (listOf(typed) + auto).distinct()
            results = Arios.lyrics.searchAll(qs)
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
        val all = results.orEmpty()
        val good = all.filter { it.score.title >= 0.5 && (it.score.durationDiff ?: 0.0) <= 20 }
        val weak = all - good.toSet()
        val shown = if (showWeak) good + weak else good
        LazyColumn(Modifier.fillMaxHeight(0.75f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (results != null && good.isEmpty() && !loading) item {
                Text(stringResource(R.string.no_match_hint), Modifier.padding(20.dp), color = AriosColors.SilverDim)
            }
            items(shown) { sc ->
                val c = sc.candidate
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !previewLoading) {
                        previewLoading = true
                        scope.launch {
                            preview = sc to runCatching { c.load() }.getOrNull()
                            previewLoading = false
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
                            if (sc.confident) Pill(stringResource(R.string.best_match), color = androidx.compose.ui.graphics.lerp(AriosColors.Emerald, Color.Black, 0.5f))
                            Pill(c.sourceName)
                            c.durationSec?.let { Pill(formatTime(it * 1000L)) }
                            if (c.syncedHint == true) Pill(stringResource(R.string.synced), color = androidx.compose.ui.graphics.lerp(AriosColors.Emerald, Color.Black, 0.7f))
                        }
                    }
                    Icon(Icons.Filled.Visibility, null, tint = AriosColors.SilverDim)
                }
            }
            if (weak.isNotEmpty() && !showWeak) item {
                TextButton(onClick = { showWeak = true }, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.show_weak_results, weak.size))
                }
            }
        }
    }
    if (previewLoading) Dialog { CircularProgressIndicator(color = AriosColors.Mint) }
    preview?.let { (sc, lyrics) ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(sc.candidate.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                if (lyrics == null || lyrics.lines.isEmpty()) Text(stringResource(R.string.no_lyrics))
                else Column {
                    Text("${sc.candidate.sourceName} • ${if (lyrics.synced) stringResource(R.string.synced) else stringResource(R.string.not_synced)}",
                        style = MaterialTheme.typography.labelMedium, color = AriosColors.Mint)
                    Spacer(Modifier.height(8.dp))
                    lyrics.lines.filter { it.text.isNotBlank() }.take(10).forEach {
                        Text(it.text, style = lyricStyle(15).copy(textDirection = TextDirection.Content), modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = lyrics != null && lyrics.lines.isNotEmpty(), onClick = {
                    scope.launch {
                        Arios.lyrics.save(song, lyrics!!, manual = false)
                        nav.snack = context.getString(R.string.lyrics_saved)
                        preview = null; nav.lyricsSearchFor = null
                    }
                }) { Text(stringResource(R.string.use_lyrics)) }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Dialog(content: @Composable () -> Unit) =
    androidx.compose.ui.window.Dialog(onDismissRequest = {}) { Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) { content() } }
