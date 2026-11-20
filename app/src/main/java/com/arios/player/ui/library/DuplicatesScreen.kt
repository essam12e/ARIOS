package com.arios.player.ui.library

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
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
import com.arios.player.artwork.artKey
import com.arios.player.data.FileAccess
import com.arios.player.data.Insights
import com.arios.player.data.Song
import com.arios.player.ui.AppNav
import com.arios.player.ui.components.Artwork
import com.arios.player.ui.components.EmptyState
import com.arios.player.ui.components.Pill
import com.arios.player.ui.components.formatTime
import com.arios.player.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun DuplicatesScreen(nav: AppNav, bottomPadding: androidx.compose.ui.unit.Dp) {
    val lib by Arios.library.state.collectAsState()
    val groups = remember(lib) { Insights.duplicates(lib) }
    val selected = remember(groups) { mutableStateMapOf<Long, Boolean>().apply { groups.forEach { g -> g.others.forEach { put(it.id, true) } } } }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toDelete = groups.flatMap { it.others }.filter { selected[it.id] == true }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) { nav.snack = context.getString(R.string.deleted_n, toDelete.size); Arios.library.refresh() }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.duplicates), style = MaterialTheme.typography.headlineSmall.copy(brush = AriosColors.brand), modifier = Modifier.weight(1f))
        }
        if (groups.isEmpty()) { EmptyState(Icons.Filled.CheckCircle, stringResource(R.string.no_duplicates), stringResource(R.string.no_duplicates_hint)); return@Column }
        Text(stringResource(R.string.duplicates_hint, groups.size), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = AriosColors.SilverDim)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 90.dp + bottomPadding)) {
            itemsIndexed(groups) { i, g ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).staggerIn(i).glass(RoundedCornerShape(22.dp)).padding(12.dp)) {
                    DupRow(g.keep, keep = true, checked = false) {}
                    g.others.forEach { s -> DupRow(s, keep = false, checked = selected[s.id] == true) { selected[s.id] = it } }
                }
            }
        }
    }
    if (toDelete.isNotEmpty()) Box(Modifier.fillMaxSize().padding(bottom = bottomPadding + 12.dp), contentAlignment = Alignment.BottomCenter) {
        Button(
            onClick = {
                scope.launch {
                    val sender = FileAccess.delete(context, toDelete)
                    if (sender != null) launcher.launch(IntentSenderRequest.Builder(sender).build())
                    else { nav.snack = context.getString(R.string.deleted_n, toDelete.size); Arios.library.refresh() }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFFE5484D)),
            modifier = Modifier.glowHalo(true, androidx.compose.ui.graphics.Color(0xFFE5484D)),
        ) { Icon(Icons.Filled.Delete, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.delete_selected, toDelete.size)) }
    }
}

@Composable
private fun DupRow(s: Song, keep: Boolean, checked: Boolean, onCheck: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(s.artKey(), Modifier.size(46.dp), RoundedCornerShape(10.dp), 16.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (keep) FontWeight.SemiBold else FontWeight.Normal)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(s.ext); Pill(formatTime(s.durationMs)); Pill("%.1f MB".format(s.size / 1048576f))
                if (s.durationMs > 0) Pill("${s.size * 8 / s.durationMs} kbps")
            }
            Text(s.folder.substringAfterLast('/'), style = MaterialTheme.typography.labelSmall, color = AriosColors.SilverDim, maxLines = 1)
        }
        if (keep) Pill(stringResource(R.string.keep), color = AriosColors.Emerald)
        else Checkbox(checked, onCheck, colors = CheckboxDefaults.colors(checkedColor = androidx.compose.ui.graphics.Color(0xFFE5484D)))
    }
}

@Suppress("unused") private val icon = Icons.Filled.ContentCopy
