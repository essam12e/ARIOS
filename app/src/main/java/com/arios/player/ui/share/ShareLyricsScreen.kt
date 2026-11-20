package com.arios.player.ui.share

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.arios.player.Arios
import com.arios.player.R
import com.arios.player.artwork.artKey
import com.arios.player.lyrics.Lyrics
import com.arios.player.share.*
import com.arios.player.ui.AppNav
import com.arios.player.ui.components.EmptyState
import com.arios.player.ui.player.rememberArtPalette
import com.arios.player.ui.stats.toArgbInt
import com.arios.player.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ShareLyricsScreen(nav: AppNav, songId: Long, initialLine: Int, bottomPadding: androidx.compose.ui.unit.Dp) {
    val lib by Arios.library.state.collectAsState()
    val song = lib.byId[songId] ?: run { LaunchedEffect(Unit) { nav.back() }; return }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lyrics by produceState<Lyrics?>(null, song.key) { value = Arios.lyrics.getLocal(song) }
    val palette = rememberArtPalette(song)
    val art by produceState<Bitmap?>(null, song.id) {
        value = withContext(Dispatchers.IO) { runCatching { Arios.artwork.loadBitmapBlocking(song.artKey(), 600) }.getOrNull() }
    }
    val chosen = remember { mutableStateListOf<Int>() }
    var style by remember { mutableStateOf(CardStyle.GLASS) }
    var aspect by remember { mutableStateOf(CardAspect.STORY) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var videoProgress by remember { mutableStateOf<Float?>(null) }
    var videoJob by remember { mutableStateOf<Job?>(null) }
    val renderer = remember { LyricCardRenderer(context) }

    LaunchedEffect(lyrics) {
        val l = lyrics ?: return@LaunchedEffect
        if (chosen.isEmpty()) {
            val start = if (initialLine >= 0) initialLine else l.lines.indexOfFirst { it.text.isNotBlank() }.coerceAtLeast(0)
            var i = start
            while (chosen.size < 2 && i < l.lines.size) { if (l.lines[i].text.isNotBlank()) chosen += i; i++ }
        }
    }

    fun spec(): CardSpec? {
        val l = lyrics ?: return null
        val lines = chosen.sorted().mapNotNull { l.lines.getOrNull(it) }
        if (lines.isEmpty()) return null
        return CardSpec(style, aspect, lines, song.title, song.artist, art,
            AriosColors.Mint.toArgbInt(), AriosColors.MintLight.toArgbInt(), palette.base.toArgbInt())
    }

    LaunchedEffect(chosen.toList(), style, aspect, art, lyrics, palette.base) {
        val sp = spec() ?: return@LaunchedEffect
        preview = withContext(Dispatchers.Default) { renderer.render(sp, 0.5f) }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { videoJob?.cancel(); nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.share_lyrics), style = MaterialTheme.typography.headlineSmall.copy(brush = AriosColors.brand), modifier = Modifier.weight(1f))
        }
        val l = lyrics
        if (l == null || l.lines.none { it.text.isNotBlank() }) { EmptyState(Icons.Filled.Lyrics, stringResource(R.string.no_lyrics)); return@Column }

        // live preview
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            AnimatedContent(preview, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) }, label = "pv") { b ->
                if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxHeight().aspectRatio(b.width / b.height.toFloat())
                    .glowHalo(true, AriosColors.Mint.copy(alpha = 0.35f)).clip(RoundedCornerShape(20.dp)))
            }
            videoProgress?.let { p ->
                Column(Modifier.glass(RoundedCornerShape(24.dp), fill = 0.5f, tint = AriosColors.Black).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator({ p }, color = AriosColors.Mint, modifier = Modifier.size(64.dp), strokeWidth = 6.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.making_video, (p * 100).toInt()))
                    TextButton(onClick = { videoJob?.cancel(); videoProgress = null }) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
        // styles
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(CardStyle.GLASS to R.string.style_glass, CardStyle.ART to R.string.style_art, CardStyle.AURORA to R.string.style_aurora, CardStyle.MINIMAL to R.string.style_minimal).forEach { (st, label) ->
                Chip(stringResource(label), st == style) { style = st }
            }
            Spacer(Modifier.width(8.dp))
            Chip(stringResource(R.string.aspect_story), aspect == CardAspect.STORY) { aspect = CardAspect.STORY }
            Chip(stringResource(R.string.aspect_square), aspect == CardAspect.SQUARE) { aspect = CardAspect.SQUARE }
        }
        // line picker
        Text(stringResource(R.string.pick_lines), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.labelLarge, color = AriosColors.SilverDim)
        LazyColumn(Modifier.fillMaxWidth().height(170.dp).padding(horizontal = 12.dp)) {
            itemsIndexed(l.lines) { i, line ->
                if (line.text.isBlank()) return@itemsIndexed
                val sel = i in chosen
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (sel) AriosColors.Mint.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent)
                    .bounceClick { if (sel) chosen.remove(i) else if (chosen.size < 4) chosen += i else nav.snack = context.getString(R.string.max_lines) }
                    .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (sel) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null, tint = if (sel) AriosColors.Mint else AriosColors.Steel, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(line.text, style = lyricStyle(15).copy(textDirection = TextDirection.Content), modifier = Modifier.weight(1f))
                }
            }
        }
        // actions
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).padding(bottom = bottomPadding), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Action(Icons.Filled.Image, stringResource(R.string.share_image), Modifier.weight(1f), primary = true) {
                val sp = spec() ?: return@Action
                scope.launch { val b = withContext(Dispatchers.Default) { renderer.render(sp) }; ShareUtil.shareBitmap(context, b) }
            }
            Action(Icons.Filled.Movie, stringResource(R.string.share_video), Modifier.weight(1f), primary = true, enabled = l.synced && videoProgress == null) {
                val sp = spec() ?: return@Action
                val lines = sp.lines
                val start = (lines.first().start - 700).coerceAtLeast(0)
                val end = lines.last().end.coerceAtMost(lines.last().start + 9000) + 600
                videoProgress = 0f
                videoJob = scope.launch {
                    runCatching {
                        val f = File(ShareUtil.dir(context), "arios_lyrics.mp4")
                        LyricVideoMaker(context).make(song.uri, sp, start, end, f) { videoProgress = it }
                        f
                    }.onSuccess { ShareUtil.shareFile(context, it, "video/mp4") }
                        .onFailure { if (it !is kotlinx.coroutines.CancellationException) nav.snack = context.getString(R.string.video_failed) }
                    videoProgress = null
                }
            }
            Action(Icons.Filled.SaveAlt, null, Modifier, primary = false) {
                val sp = spec() ?: return@Action
                scope.launch {
                    val f = File(ShareUtil.dir(context), "arios_card.png")
                    withContext(Dispatchers.Default) { f.outputStream().use { renderer.render(sp).compress(Bitmap.CompressFormat.PNG, 100, it) } }
                    nav.snack = context.getString(if (ShareUtil.saveToGallery(context, f, "image/png")) R.string.saved_gallery else R.string.backup_failed)
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.glass(CircleShape, fill = 0.06f).clip(CircleShape).background(AriosColors.brandHorizontal, alpha = if (selected) 1f else 0f)
        .bounceClick(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(text, color = if (selected) AriosColors.Black else AriosColors.TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Action(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String?, modifier: Modifier, primary: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier.then(if (primary) Modifier.glowHalo(false) else Modifier).clip(CircleShape)
            .then(if (primary) Modifier.background(AriosColors.brandHorizontal, alpha = if (enabled) 1f else 0.4f) else Modifier.glass(CircleShape))
            .bounceClick(enabled, onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, text, tint = if (primary) AriosColors.Black else AriosColors.TextPrimary)
        if (text != null) { Spacer(Modifier.width(8.dp)); Text(text, color = if (primary) AriosColors.Black else AriosColors.TextPrimary, fontWeight = FontWeight.SemiBold) }
    }
}
