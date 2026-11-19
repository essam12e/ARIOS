package com.arios.player.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arios.player.Arios
import com.arios.player.R
import com.arios.player.artwork.artKey
import com.arios.player.ui.AppNav
import com.arios.player.ui.components.Artwork
import com.arios.player.ui.theme.AriosColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(nav: AppNav) {
    if (!nav.queueOpen) return
    val state by Arios.player.state.collectAsState()
    val lib by Arios.library.state.collectAsState()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (state.index - 1).coerceAtLeast(0))
    ModalBottomSheet(onDismissRequest = { nav.queueOpen = false }, containerColor = AriosColors.Surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.queue), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text("${state.index + 1}/${state.queue.size}", color = AriosColors.SilverDim)
            TextButton(onClick = { Arios.player.clearQueueExceptCurrent() }) { Text(stringResource(R.string.clear)) }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxHeight(0.85f), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(state.queue, key = { "${it.index}:${it.mediaId}" }) { q ->
                val song = q.songId?.let { lib.byId[it] }
                val current = q.index == state.index
                Row(
                    Modifier.fillMaxWidth().clickable { Arios.player.playAt(q.index) }.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(song?.artKey(), Modifier.size(46.dp), RoundedCornerShape(8.dp), 18.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(q.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (current) AriosColors.Mint else MaterialTheme.colorScheme.onSurface)
                        Text(q.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = AriosColors.SilverDim)
                    }
                    IconButton(enabled = q.index > 0, onClick = { Arios.player.moveQueue(q.index, q.index - 1) }) { Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.move_up)) }
                    IconButton(enabled = q.index < state.queue.size - 1, onClick = { Arios.player.moveQueue(q.index, q.index + 1) }) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.move_down)) }
                    IconButton(enabled = !current, onClick = { Arios.player.removeFromQueue(q.index) }) { Icon(Icons.Filled.Close, stringResource(R.string.remove)) }
                }
            }
        }
    }
}
