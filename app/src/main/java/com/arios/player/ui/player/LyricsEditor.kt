package com.arios.player.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.arios.player.Arios
import com.arios.player.R
import com.arios.player.lyrics.LrcParser
import com.arios.player.lyrics.LrcWriter
import com.arios.player.ui.AppNav
import com.arios.player.ui.components.formatTime
import com.arios.player.ui.theme.AriosColors
import com.arios.player.ui.theme.lyricStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private class EditLine(text: String, time: Long?) {
    var text by mutableStateOf(text)
    var time by mutableStateOf(time)
}

/**
 * Lyrics editor: write/paste lyrics, then tap "Sync" while the song plays to stamp every line.
 * Saved as LRC in the ARIOS lyrics cache (works offline, included in backups).
 */
@Composable
fun LyricsEditorScreen(nav: AppNav, songId: Long, bottomPadding: androidx.compose.ui.unit.Dp) {
    val lib by Arios.library.state.collectAsState()
    val song = lib.byId[songId] ?: run { LaunchedEffect(Unit) { nav.back() }; return }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableIntStateOf(0) } // 0 = text, 1 = sync
    var raw by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf<EditLine>() }
    var cursor by remember { mutableIntStateOf(0) }
    val player by Arios.player.state.collectAsState()

    LaunchedEffect(songId) {
        val l = Arios.lyrics.getLocal(song)
        raw = if (l != null) {
            if (l.synced) LrcWriter.write(l).lines().filterNot { it.startsWith("[by:") }.joinToString("\n")
            else l.lines.joinToString("\n") { it.text }
        } else ""
    }

    fun textToLines() {
        val parsed = LrcParser.parse(raw, "edit", song.durationMs)
        lines.clear()
        if (parsed != null && parsed.synced) {
            parsed.lines.forEach { lines += EditLine(it.text, it.start); it.translation?.let { t -> lines += EditLine(t, it.start) } }
        } else raw.lines().map { it.trim() }.forEach { lines += EditLine(it, null) }
        while (lines.isNotEmpty() && lines.last().text.isBlank()) lines.removeAt(lines.lastIndex)
        cursor = lines.indexOfFirst { it.time == null }.let { if (it < 0) 0 else it }
    }
    fun linesToText(): String = lines.joinToString("\n") { l -> (l.time?.let { LrcWriter.stamp(it) } ?: "") + l.text }

    fun save() = scope.launch {
        val text = if (mode == 1) linesToText() else raw
        if (text.isBlank()) { Arios.lyrics.delete(song) } else Arios.lyrics.saveRaw(song, text, "Manual")
        Arios.userStore.setOffset(song.key, 0)
        nav.snack = context.getString(R.string.lyrics_saved)
        nav.back()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.edit_lyrics), style = MaterialTheme.typography.titleLarge)
                Text(song.title, style = MaterialTheme.typography.bodySmall, color = AriosColors.SilverDim, maxLines = 1)
            }
            Button(onClick = { save() }, colors = ButtonDefaults.buttonColors(AriosColors.Silver, AriosColors.Black)) { Text(stringResource(R.string.save)) }
            Spacer(Modifier.width(8.dp))
        }
        TabRow(mode, containerColor = MaterialTheme.colorScheme.background) {
            Tab(mode == 0, { if (mode == 1) raw = linesToText(); mode = 0 }, text = { Text(stringResource(R.string.mode_text)) })
            Tab(mode == 1, { textToLines(); mode = 1 }, text = { Text(stringResource(R.string.mode_sync)) })
        }
        if (mode == 0) {
            Text(stringResource(R.string.editor_hint), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = AriosColors.SilverDim)
            OutlinedTextField(
                raw, { raw = it },
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp),
                textStyle = lyricStyle(16).copy(textDirection = TextDirection.Content),
                placeholder = { Text(stringResource(R.string.paste_lyrics)) },
            )
            Spacer(Modifier.height(bottomPadding))
        } else SyncPane(song.id == player.songId, lines, cursor, { cursor = it }, bottomPadding, onPlayThis = {
            Arios.player.playSongs(listOf(song))
        })
    }
}

@Composable
private fun ColumnScope.SyncPane(
    isCurrent: Boolean,
    lines: MutableList<EditLine>,
    cursor: Int,
    setCursor: (Int) -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onPlayThis: () -> Unit,
) {
    val state by Arios.player.state.collectAsState()
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { pos = Arios.player.position(); delay(50) } }
    val listState = rememberLazyListState()
    LaunchedEffect(cursor) { if (cursor in lines.indices) listState.animateScrollToItem((cursor - 2).coerceAtLeast(0)) }

    if (!isCurrent) {
        Row(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(14.dp)).background(AriosColors.SurfaceHigh).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sync_play_first), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onPlayThis) { Text(stringResource(R.string.play)) }
        }
    }
    Text(stringResource(R.string.sync_hint), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = AriosColors.SilverDim)
    LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
        itemsIndexed(lines) { i, l ->
            val sel = i == cursor
            Row(
                Modifier.fillMaxWidth().background(if (sel) AriosColors.SurfaceHighest else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { setCursor(i) }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(l.time?.let { formatMs(it) } ?: "--:--.--", style = MaterialTheme.typography.labelMedium,
                    color = if (l.time != null) AriosColors.Mint else AriosColors.Steel, modifier = Modifier.width(72.dp)
                        .clickable(enabled = l.time != null) { Arios.player.seekTo(l.time ?: 0) })
                Text(l.text.ifBlank { "♪" }, Modifier.weight(1f), style = lyricStyle(16).copy(textDirection = TextDirection.Content),
                    color = if (sel) AriosColors.TextPrimary else AriosColors.TextSecondary)
                if (sel && l.time != null) {
                    IconButton(onClick = { l.time = (l.time!! - 100).coerceAtLeast(0) }, Modifier.size(36.dp)) { Icon(Icons.Filled.Remove, null) }
                    IconButton(onClick = { l.time = l.time!! + 100 }, Modifier.size(36.dp)) { Icon(Icons.Filled.Add, null) }
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth().background(AriosColors.Surface).padding(horizontal = 16.dp, vertical = 10.dp).padding(bottom = bottomPadding)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(pos), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(64.dp))
            IconButton(onClick = { Arios.player.seekBy(-3000) }) { Icon(Icons.Filled.Replay, null) }
            IconButton(onClick = { Arios.player.toggle() }) { Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null) }
            IconButton(onClick = { setCursor((cursor - 1).coerceAtLeast(0)) }) { Icon(Icons.Filled.Undo, stringResource(R.string.previous)) }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.clip(CircleShape).background(AriosColors.Mint).clickable(enabled = cursor in lines.indices) {
                    lines[cursor].time = Arios.player.position()
                    if (cursor < lines.size - 1) setCursor(cursor + 1)
                }.padding(horizontal = 26.dp, vertical = 14.dp),
            ) { Text(stringResource(R.string.tap_sync), color = AriosColors.Black, style = MaterialTheme.typography.titleMedium) }
        }
    }
}

private fun formatMs(ms: Long) = LrcWriter.stamp(ms).trim('[', ']')
