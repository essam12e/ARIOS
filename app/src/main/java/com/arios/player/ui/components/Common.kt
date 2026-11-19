package com.arios.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.arios.player.artwork.ArtKey
import com.arios.player.artwork.artKey
import com.arios.player.data.MediaScanner
import com.arios.player.data.Song
import com.arios.player.ui.theme.AriosColors

@Composable
fun Artwork(key: ArtKey?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(10.dp), iconSize: Dp = 28.dp) {
    Box(modifier.clip(shape).background(AriosColors.SurfaceHigh), contentAlignment = Alignment.Center) {
        if (key == null) Placeholder(iconSize)
        else SubcomposeAsyncImage(
            model = key,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = { Placeholder(iconSize) },
            error = { Placeholder(iconSize) },
        )
    }
}

@Composable
private fun Placeholder(iconSize: Dp) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0xFF2A2D33), Color(0xFF15161A)))
        ),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.MusicNote, null, tint = AriosColors.Steel, modifier = Modifier.size(iconSize)) }
}

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600; val m = (total / 60) % 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun displayArtist(a: String, unknown: String) = if (a == MediaScanner.UNKNOWN) unknown else a

@Composable
fun SongRow(
    song: Song,
    isCurrent: Boolean,
    unknown: String,
    onClick: () -> Unit,
    onMore: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    showArt: Boolean = true,
    trailingExtra: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) leading()
        else if (showArt) Artwork(song.artKey(), Modifier.size(50.dp), RoundedCornerShape(8.dp), 20.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCurrent) {
                    Icon(Icons.Filled.GraphicEq, null, tint = AriosColors.Mint, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    color = if (isCurrent) AriosColors.Mint else MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                "${displayArtist(song.artist, unknown)} • ${formatTime(song.durationMs)}",
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailingExtra?.invoke()
        IconButton(onClick = onMore) { Icon(Icons.Filled.MoreVert, null, tint = AriosColors.SilverDim) }
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = AriosColors.SurfaceHighest) {
    Box(modifier.clip(CircleShape).background(color).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = AriosColors.Silver)
    }
}

@Composable
fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String? = null, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(84.dp).clip(CircleShape).background(AriosColors.SurfaceHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = AriosColors.SilverDim, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        if (action != null) { Spacer(Modifier.height(16.dp)); action() }
    }
}
