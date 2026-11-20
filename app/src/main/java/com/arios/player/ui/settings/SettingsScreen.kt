package com.arios.player.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.arios.player.Arios
import com.arios.player.BuildConfigInfo
import com.arios.player.R
import com.arios.player.backup.BackupManager
import com.arios.player.download.MediaSaver
import com.arios.player.lyrics.ALL_PROVIDERS
import com.arios.player.ui.AppNav
import com.arios.player.ui.theme.AriosColors
import com.arios.player.ui.theme.bounceClick
import com.arios.player.ui.theme.glowHalo
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(nav: AppNav, bottomPadding: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    @Suppress("UNUSED_VARIABLE") val v by Arios.prefs.version.collectAsState()
    val p = Arios.prefs
    var langDialog by remember { mutableStateOf(false) }
    var excludeDialog by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) scope.launch {
            nav.snack = context.getString(if (BackupManager.export(context, uri)) R.string.backup_done else R.string.backup_failed)
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            nav.snack = context.getString(if (BackupManager.import(context, uri)) R.string.restore_done else R.string.backup_failed)
        }
    }
    val musicImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            var ok = 0
            uris.forEach { u ->
                runCatching {
                    val name = queryName(context, u) ?: "track.mp3"
                    val mime = context.contentResolver.getType(u) ?: MediaSaver.mimeFor(name.substringAfterLast('.'))
                    context.contentResolver.openInputStream(u)?.use { MediaSaver.save(context, name, mime, it) }?.let { ok++ }
                }
            }
            Arios.library.scan()
            importing = false
            nav.snack = context.getString(R.string.imported_n, ok)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = bottomPadding + 16.dp)) {
        Text(stringResource(R.string.settings), Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.headlineMedium)

        Section(stringResource(R.string.appearance))
        AccentPicker()
        Switch(Icons.Filled.DarkMode, stringResource(R.string.amoled), stringResource(R.string.amoled_hint), p.amoled) { p.amoled = it }
        Switch(Icons.Filled.Palette, stringResource(R.string.dynamic_color), stringResource(R.string.dynamic_color_hint), p.dynamicColor) { p.dynamicColor = it }
        Switch(Icons.Filled.GraphicEq, stringResource(R.string.visualizer), stringResource(R.string.visualizer_hint), p.visualizer) { p.visualizer = it }

        Section(stringResource(R.string.sound))
        Item(Icons.Filled.Equalizer, stringResource(R.string.sound_settings), stringResource(R.string.sound_settings_hint)) { nav.push(com.arios.player.ui.Screen.Sound) }

        Section(stringResource(R.string.general))
        Item(Icons.Filled.Language, stringResource(R.string.language), currentLanguage(context)) { langDialog = true }
        Item(Icons.Filled.Refresh, stringResource(R.string.rescan), stringResource(R.string.rescan_hint)) {
            Arios.library.refresh(); nav.snack = context.getString(R.string.scanning)
        }
        Item(Icons.Filled.FileUpload, stringResource(R.string.import_music), stringResource(R.string.import_music_hint)) {
            musicImport.launch(arrayOf("audio/*"))
        }
        if (importing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = AriosColors.Mint)
        SliderItem(Icons.Filled.Timelapse, stringResource(R.string.min_duration), "${p.minDurationSec}s", p.minDurationSec.toFloat(), 0f..60f) {
            p.minDurationSec = it.toInt()
        }

        Item(Icons.Filled.FolderOff, stringResource(R.string.exclude_folders), stringResource(R.string.exclude_folders_hint, p.excludedFolders.size)) { excludeDialog = true }
        Item(Icons.Filled.ContentCopy, stringResource(R.string.duplicates), stringResource(R.string.duplicates_settings_hint)) { nav.push(com.arios.player.ui.Screen.Duplicates) }
        Item(Icons.Filled.Insights, stringResource(R.string.stats), stringResource(R.string.stats_settings_hint)) { nav.push(com.arios.player.ui.Screen.Stats) }
        Item(Icons.Filled.HistoryToggleOff, stringResource(R.string.clear_history), null) { Arios.history.clear(); nav.snack = context.getString(R.string.done) }

        Section(stringResource(R.string.lyrics))
        Switch(Icons.Filled.CloudDownload, stringResource(R.string.auto_lyrics), stringResource(R.string.auto_lyrics_hint), p.autoFetchLyrics) { p.autoFetchLyrics = it }
        Switch(Icons.Filled.Translate, stringResource(R.string.show_translation), null, p.showTranslation) { p.showTranslation = it }
        Switch(Icons.Filled.AutoFixHigh, stringResource(R.string.auto_sync_setting), stringResource(R.string.auto_sync_setting_hint), p.autoSyncLyrics) { p.autoSyncLyrics = it }
        Switch(Icons.Filled.Gesture, stringResource(R.string.word_by_word), stringResource(R.string.word_by_word_hint), p.wordByWord) { p.wordByWord = it }
        Switch(Icons.Filled.FormatAlignCenter, stringResource(R.string.align_center), null, p.lyricsCenter) { p.lyricsCenter = it }
        SliderItem(Icons.Filled.FormatSize, stringResource(R.string.lyrics_size), "${p.lyricsFontSize}", p.lyricsFontSize.toFloat(), 16f..44f) {
            p.lyricsFontSize = it.toInt()
        }
        Text(stringResource(R.string.lyrics_sources), Modifier.padding(start = 20.dp, top = 8.dp), style = MaterialTheme.typography.labelLarge, color = AriosColors.SilverDim)
        ALL_PROVIDERS.forEach { prov ->
            Switch(Icons.Filled.Source, prov.displayName, null, p.sourceEnabled(prov.id)) { p.setSourceEnabled(prov.id, it) }
        }
        Item(Icons.Filled.DeleteSweep, stringResource(R.string.clear_lyrics_cache), null) {
            Arios.lyrics.clearCache(); nav.snack = context.getString(R.string.done)
        }

        Section(stringResource(R.string.artwork))
        Switch(Icons.Filled.Image, stringResource(R.string.auto_artwork), stringResource(R.string.auto_artwork_hint), p.autoFetchArtwork) {
            p.autoFetchArtwork = it; if (it) Arios.artwork.clearMisses()
        }
        Item(Icons.Filled.HideImage, stringResource(R.string.clear_artwork_cache), null) {
            Arios.artwork.clearAll(); nav.snack = context.getString(R.string.done)
        }

        Section(stringResource(R.string.backup))
        Item(Icons.Filled.Backup, stringResource(R.string.backup_export), stringResource(R.string.backup_export_hint)) {
            exportLauncher.launch("arios-backup.json")
        }
        Item(Icons.Filled.Restore, stringResource(R.string.backup_import), null) { importLauncher.launch(arrayOf("application/json", "*/*")) }

        Section(stringResource(R.string.about))
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.arios_logo), null, Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.version_x, BuildConfigInfo.VERSION), color = AriosColors.SilverDim)
                Text(stringResource(R.string.developed_by), style = MaterialTheme.typography.bodyMedium, color = AriosColors.TextPrimary)
                val ctx = LocalContext.current
                Row(
                    Modifier.padding(top = 6.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color(0xFF25D366).copy(alpha = 0.16f))
                        .clickable {
                            runCatching {
                                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://wa.me/966531098278")))
                            }
                        }.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Chat, null, tint = Color(0xFF25D366), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("+966 53 109 8278", style = MaterialTheme.typography.labelLarge, color = Color(0xFF25D366),
                        modifier = Modifier, textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                }
            }
        }
    }

    if (excludeDialog) ExcludeDialog { excludeDialog = false }
    if (langDialog) AlertDialog(
        onDismissRequest = { langDialog = false },
        title = { Text(stringResource(R.string.language)) },
        text = {
            Column {
                listOf("" to R.string.lang_system, "ar" to R.string.lang_ar, "en" to R.string.lang_en).forEach { (tag, label) ->
                    Row(Modifier.fillMaxWidth().clickable {
                        langDialog = false
                        AppCompatDelegate.setApplicationLocales(if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))
                    }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = AppCompatDelegate.getApplicationLocales().toLanguageTags() == tag, onClick = null)
                        Spacer(Modifier.width(12.dp)); Text(stringResource(label))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { langDialog = false }) { Text(stringResource(R.string.close)) } },
    )
}

private fun queryName(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
}.getOrNull()

@Composable
private fun currentLanguage(context: android.content.Context): String {
    val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    return when {
        tags.startsWith("ar") -> stringResource(R.string.lang_ar)
        tags.startsWith("en") -> stringResource(R.string.lang_en)
        else -> stringResource(R.string.lang_system)
    }
}

@Composable
private fun Section(t: String) {
    Text(t, Modifier.padding(start = 20.dp, top = 22.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = AriosColors.Mint)
}

@Composable
private fun Item(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = AriosColors.Silver)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AriosColors.SilverDim)
        }
    }
}

@Composable
private fun Switch(icon: ImageVector, title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = AriosColors.Silver)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AriosColors.SilverDim)
        }
        androidx.compose.material3.Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = AriosColors.Mint, checkedThumbColor = AriosColors.Black))
    }
}

@Composable
private fun SliderItem(icon: ImageVector, title: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, onDone: (Float) -> Unit) {
    var v by remember(current) { mutableFloatStateOf(current) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = AriosColors.Silver)
            Spacer(Modifier.width(18.dp))
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(if (v.toInt() == current.toInt()) value else v.toInt().toString(), color = AriosColors.SilverDim)
        }
        Slider(v, { v = it }, valueRange = range, onValueChangeFinished = { onDone(v) }, modifier = Modifier.padding(start = 40.dp))
    }
}

@Composable
private fun AccentPicker() {
    val p = Arios.prefs
    val arabic = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "ar"
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        com.arios.player.ui.theme.ACCENTS.forEach { a ->
            val sel = p.accent == a.id && !p.dynamicColor
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
                Box(
                    Modifier.size(58.dp)
                        .then(if (sel) Modifier.glowHalo(true, a.main) else Modifier)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(a.light, a.main, a.deep)))
                        .bounceClick { p.accent = a.id; p.dynamicColor = false },
                    contentAlignment = Alignment.Center,
                ) { if (sel) Icon(Icons.Filled.Check, null, tint = androidx.compose.ui.graphics.Color.Black) }
                Spacer(Modifier.height(6.dp))
                Text(if (arabic) a.nameAr else a.nameEn, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = if (sel) AriosColors.Mint else AriosColors.SilverDim)
            }
        }
    }
}

@Composable
private fun ExcludeDialog(onDismiss: () -> Unit) {
    val folders = remember { Arios.library.raw.map { it.folder }.distinct().sorted() }
    val excluded = remember { mutableStateListOf(*Arios.prefs.excludedFolders.toTypedArray()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.exclude_folders)) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(folders.size) { i ->
                    val f = folders[i]
                    val off = f in excluded
                    Row(Modifier.fillMaxWidth().clickable { if (off) excluded.remove(f) else excluded.add(f) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(!off, { if (off) excluded.remove(f) else excluded.add(f) })
                        Column {
                            Text(f.substringAfterLast('/'), style = MaterialTheme.typography.bodyLarge)
                            Text(f.removePrefix("/storage/emulated/0"), style = MaterialTheme.typography.labelSmall, color = AriosColors.SilverDim, maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { Arios.prefs.excludedFolders = excluded.toSet(); Arios.library.rebuild(); onDismiss() }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
