package com.arios.player.ui.links

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arios.player.Arios
import com.arios.player.R
import com.arios.player.lyrics.LinkLyrics
import com.arios.player.ui.AppNav
import com.arios.player.ui.components.EmptyState
import com.arios.player.ui.theme.*

@Composable
fun LinkLyricsScreen(nav: AppNav, bottomPadding: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val entries by Arios.linkLyrics.entries.collectAsState()
    val progress by Arios.linkLyrics.progress.collectAsState()
    val syncing by Arios.lyrics.syncing.collectAsState()
    var text by remember { mutableStateOf("") }
    val working = progress?.status == LinkLyrics.Status.WORKING

    fun submit() {
        val link = LinkLyrics.findLink(text)
        if (link == null) { nav.snack = context.getString(R.string.link_invalid); return }
        Arios.linkLyrics.fetch(link); text = ""
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.link_lyrics), style = MaterialTheme.typography.headlineSmall.copy(brush = AriosColors.brand), modifier = Modifier.weight(1f))
        }
        Column(Modifier.padding(horizontal = 16.dp).glass(RoundedCornerShape(24.dp)).padding(16.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.link_paste_hint)) },
                leadingIcon = { Icon(Icons.Filled.Link, null) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                }) { Icon(Icons.Filled.ContentPaste, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.paste)) }
                Button(onClick = ::submit, enabled = text.isNotBlank() && !working, modifier = Modifier.weight(1f)) {
                    if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else { Icon(Icons.Filled.Lyrics, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.fetch_lyrics)) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.link_lyrics_info), style = MaterialTheme.typography.bodySmall, color = AriosColors.SilverDim)
        }
        if (entries.isEmpty()) {
            EmptyState(Icons.Filled.Link, stringResource(R.string.link_none), stringResource(R.string.link_lyrics_hint))
            return@Column
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 8.dp, bottom = bottomPadding + 16.dp)) {
            itemsIndexed(entries, key = { _, e -> e.id }) { i, e ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).staggerIn(i)
                        .glass(RoundedCornerShape(20.dp)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val icon = when {
                        e.lrc == null -> Icons.Filled.SearchOff
                        e.attachedKey != null -> Icons.Filled.CheckCircle
                        else -> Icons.Filled.HourglassTop
                    }
                    Icon(icon, null, tint = if (e.lrc == null) AriosColors.SilverDim else AriosColors.Mint)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (e.artist.isNotBlank()) Text(e.artist, style = MaterialTheme.typography.bodySmall, color = AriosColors.SilverDim, maxLines = 1)
                        val status = when {
                            e.lrc == null -> stringResource(R.string.link_not_found, e.title)
                            e.attachedKey != null && e.attachedKey in syncing -> stringResource(R.string.auto_syncing)
                            e.attachedKey != null -> stringResource(R.string.link_attached, e.attachedTitle ?: e.title) + " • " +
                                stringResource(if (e.synced) R.string.link_synced else R.string.link_plain)
                            else -> stringResource(R.string.link_waiting) + " • " + stringResource(if (e.synced) R.string.link_synced else R.string.link_plain)
                        }
                        Text(status, style = MaterialTheme.typography.labelSmall, color = AriosColors.Mint.copy(alpha = 0.85f))
                    }
                    if (e.lrc == null) IconButton(onClick = { Arios.linkLyrics.fetch(e.url) }) { Icon(Icons.Filled.Refresh, null) }
                    IconButton(onClick = { Arios.linkLyrics.remove(e.id) }) { Icon(Icons.Filled.Close, null, tint = AriosColors.SilverDim) }
                }
            }
        }
    }
}

/** Banner offering to fetch lyrics for a song link found in the clipboard. */
@Composable
fun ClipboardLinkBanner(link: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .shadow(18.dp, RoundedCornerShape(22.dp))
            .background(AriosColors.Black.copy(alpha = 0.94f), RoundedCornerShape(22.dp))
            .glass(RoundedCornerShape(22.dp)).padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Lyrics, null, tint = AriosColors.Mint)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.link_clip_found), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.link_clip_question), style = MaterialTheme.typography.bodySmall, color = AriosColors.SilverDim)
        }
        TextButton(onClick = { Arios.linkLyrics.markOffered(link); onDismiss() }) { Text(stringResource(R.string.no)) }
        Button(onClick = { Arios.linkLyrics.fetch(link); onDismiss() }) { Text(stringResource(R.string.fetch_lyrics)) }
    }
}
