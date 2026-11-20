package com.arios.player.ui.library

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.arios.player.Arios
import com.arios.player.R
import com.arios.player.artwork.artKey
import com.arios.player.data.EditResult
import com.arios.player.data.FileAccess
import com.arios.player.data.MediaScanner
import com.arios.player.data.TagEdit
import com.arios.player.lyrics.LyricsMatcher
import com.arios.player.ui.AppNav
import com.arios.player.ui.components.Artwork
import com.arios.player.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun TagEditorScreen(nav: AppNav, songId: Long, bottomPadding: androidx.compose.ui.unit.Dp) {
    val lib by Arios.library.state.collectAsState()
    val song = lib.byId[songId] ?: run { LaunchedEffect(Unit) { nav.back() }; return }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(song.title) }
    var artist by remember { mutableStateOf(song.artist.takeUnless { it == MediaScanner.UNKNOWN } ?: "") }
    var album by remember { mutableStateOf(song.album.takeUnless { it == MediaScanner.UNKNOWN } ?: "") }
    var year by remember { mutableStateOf(song.year.takeIf { it > 0 }?.toString() ?: "") }
    var track by remember { mutableStateOf(song.track.takeIf { it > 0 }?.toString() ?: "") }
    var genre by remember { mutableStateOf(Arios.tagOverrides.get(song.id)?.genre ?: "") }
    var cover by remember { mutableStateOf<Uri?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun edit() = TagEdit(title.trim(), artist.trim(), album.trim(), year.toIntOrNull(), track.toIntOrNull(), genre.trim().ifBlank { null })

    fun report(r: EditResult) {
        nav.snack = when (r) {
            EditResult.WrittenToFile -> context.getString(R.string.tags_written)
            EditResult.SavedInApp -> context.getString(R.string.tags_saved_app)
            is EditResult.Failed -> context.getString(R.string.tags_failed, r.message)
            is EditResult.NeedsPermission -> null
        }
        if (r !is EditResult.NeedsPermission) nav.back()
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        scope.launch {
            if (res.resultCode == Activity.RESULT_OK) report(FileAccess.applyEdit(context, song, edit(), cover))
            else { saving = false; nav.snack = context.getString(R.string.tags_saved_app); nav.back() }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) cover = uri }

    fun save() {
        saving = true
        scope.launch {
            when (val r = FileAccess.applyEdit(context, song, edit(), cover)) {
                is EditResult.NeedsPermission -> permission.launch(IntentSenderRequest.Builder(r.sender).build())
                else -> report(r)
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = bottomPadding + 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.edit_tags), style = MaterialTheme.typography.headlineSmall.copy(brush = AriosColors.brand), modifier = Modifier.weight(1f))
            Button(onClick = { save() }, enabled = !saving && title.isNotBlank(), colors = ButtonDefaults.buttonColors(AriosColors.Mint, AriosColors.Black)) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = AriosColors.Black) else Text(stringResource(R.string.save))
            }
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.align(Alignment.CenterHorizontally).padding(16.dp).size(200.dp).staggerIn(0).bounceClick {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }) {
            GlowBehind(Modifier.fillMaxSize(), AriosColors.Mint) {
                if (cover != null) AsyncImage(cover, null, Modifier.fillMaxSize().clip(RoundedCornerShape(26.dp)), contentScale = ContentScale.Crop)
                else Artwork(song.artKey(), Modifier.fillMaxSize(), RoundedCornerShape(26.dp), 60.dp)
            }
            Box(Modifier.align(Alignment.BottomEnd).padding(10.dp).size(44.dp).clip(CircleShape).glass(CircleShape, fill = 0.25f), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.AddPhotoAlternate, stringResource(R.string.change_cover), tint = AriosColors.TextPrimary)
            }
        }
        // one-tap clean-up of YouTube-style names
        val parsed = remember(title, artist) { LyricsMatcher.parse(title, artist) }
        val suggestTitle = parsed.titles.firstOrNull()
        val suggestArtist = parsed.artists.firstOrNull()
        if (suggestTitle != null && (suggestTitle != title.trim() || (suggestArtist != null && suggestArtist != artist.trim()))) {
            AssistChip(
                onClick = { title = suggestTitle; if (suggestArtist != null) artist = suggestArtist },
                label = { Text(stringResource(R.string.clean_names, suggestTitle, suggestArtist ?: "")) },
                leadingIcon = { Icon(Icons.Filled.AutoFixHigh, null, tint = AriosColors.Mint) },
                modifier = Modifier.padding(horizontal = 16.dp).staggerIn(1),
            )
        }
        Column(Modifier.padding(16.dp).staggerIn(2).glass(RoundedCornerShape(24.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(stringResource(R.string.title), title) { title = it }
            Field(stringResource(R.string.artist), artist) { artist = it }
            Field(stringResource(R.string.album), album) { album = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(stringResource(R.string.year), year, Modifier.weight(1f), KeyboardType.Number) { year = it.filter(Char::isDigit).take(4) }
                Field(stringResource(R.string.track_no), track, Modifier.weight(1f), KeyboardType.Number) { track = it.filter(Char::isDigit).take(3) }
            }
            Field(stringResource(R.string.genre), genre) { genre = it }
        }
        Text(stringResource(R.string.tags_hint, song.ext), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = AriosColors.SilverDim)
    }
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), type: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, modifier, label = { Text(label) }, singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
        keyboardOptions = KeyboardOptions(keyboardType = type), shape = RoundedCornerShape(16.dp))
}
