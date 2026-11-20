package com.arios.player.ui.stats

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arios.player.Arios
import com.arios.player.R
import com.arios.player.artwork.artKey
import com.arios.player.data.Insights
import com.arios.player.ui.AppNav
import com.arios.player.ui.Screen
import com.arios.player.ui.components.Artwork
import com.arios.player.ui.components.EmptyState
import com.arios.player.ui.theme.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun StatsScreen(nav: AppNav, bottomPadding: androidx.compose.ui.unit.Dp) {
    val lib by Arios.library.state.collectAsState()
    val events by Arios.history.events.collectAsState()
    var period by remember { mutableStateOf(Insights.Period.MONTH) }
    val sum = remember(period, events, lib) { Insights.summary(period, lib) }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = bottomPadding + 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.stats), style = MaterialTheme.typography.headlineSmall.copy(brush = AriosColors.brand), modifier = Modifier.weight(1f))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Insights.Period.WEEK to R.string.period_week, Insights.Period.MONTH to R.string.period_month,
                Insights.Period.YEAR to R.string.period_year, Insights.Period.ALL to R.string.period_all).forEach { (p, l) ->
                val sel = p == period
                Box(Modifier.glass(CircleShape, fill = 0.06f).clip(CircleShape).background(AriosColors.brandHorizontal, alpha = if (sel) 1f else 0f)
                    .bounceClick { period = p }.padding(horizontal = 18.dp, vertical = 9.dp)) {
                    Text(stringResource(l), color = if (sel) AriosColors.Black else AriosColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (events.isEmpty()) {
            EmptyState(androidx.compose.material.icons.Icons.Filled.AutoAwesome, stringResource(R.string.stats_empty), stringResource(R.string.stats_empty_hint))
            return@Column
        }

        // hero
        val minutes by animateIntAsState((sum.totalMs / 60000).toInt(), tween(1400, easing = Motion.EaseOutQuint), label = "min")
        Column(
            Modifier.fillMaxWidth().padding(16.dp).staggerIn(0).glass(RoundedCornerShape(30.dp), glow = AriosColors.Mint.copy(alpha = 0.6f), fill = 0.06f).padding(22.dp),
        ) {
            Text(stringResource(R.string.minutes_listened), color = AriosColors.SilverDim)
            Text("%,d".format(minutes), fontSize = 64.sp, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.displaySmall.copy(brush = AriosColors.brand))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(stringResource(R.string.plays), "%d".format(sum.plays), Modifier.weight(1f))
                StatTile(stringResource(R.string.songs), "%d".format(sum.uniqueSongs), Modifier.weight(1f))
                StatTile(stringResource(R.string.streak), stringResource(R.string.days_n, sum.longestStreakDays), Modifier.weight(1f))
            }
        }
        // wrapped
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).staggerIn(1).clip(RoundedCornerShape(24.dp)).background(
                Brush.linearGradient(listOf(AriosColors.Emerald, AriosColors.Teal, AriosColors.Mint))
            ).bounceClick { nav.wrapped = true }.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.AutoAwesome, null, tint = AriosColors.Black, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.wrapped_title), color = AriosColors.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.wrapped_sub), color = AriosColors.Black.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
            }
        }

        Section(stringResource(R.string.daily_listening), 2) { DayBars(sum.perDay, Modifier.fillMaxWidth().height(150.dp)) }
        Section(stringResource(R.string.listening_clock), 3) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { HourClock(sum.perHour, Modifier.size(230.dp)) }
            val peak = sum.perHour.indices.maxBy { sum.perHour[it] }
            Text(stringResource(when (peak) { in 0..4 -> R.string.persona_night; in 5..10 -> R.string.persona_morning; in 11..16 -> R.string.persona_day; else -> R.string.persona_evening }),
                Modifier.fillMaxWidth().padding(top = 8.dp), color = AriosColors.Mint, style = MaterialTheme.typography.titleSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        Section(stringResource(R.string.top_songs), 4) {
            val max = sum.topSongs.firstOrNull()?.second ?: 1
            sum.topSongs.forEachIndexed { i, (s, c) ->
                val grow by animateFloatAsState(c / max.toFloat(), tween(900, 200 + i * 70, Motion.EaseOutQuint), label = "g")
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).bounceClick { Arios.player.playSongs(sum.topSongs.map { it.first }, i) }, verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", Modifier.width(28.dp), fontWeight = FontWeight.Bold, color = if (i < 3) AriosColors.Mint else AriosColors.SilverDim)
                    Artwork(s.artKey(), Modifier.size(44.dp), RoundedCornerShape(10.dp), 16.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.padding(top = 4.dp).fillMaxWidth(grow.coerceAtLeast(0.04f)).height(5.dp).clip(CircleShape).background(AriosColors.brandHorizontal))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.plays_n, c), style = MaterialTheme.typography.labelMedium, color = AriosColors.SilverDim)
                }
            }
        }
        if (sum.topArtists.isNotEmpty()) Section(stringResource(R.string.top_artists), 5) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                sum.topArtists.forEachIndexed { i, (a, c, cover) ->
                    Column(Modifier.width(100.dp).staggerIn(i, 80).bounceClick { nav.push(Screen.ArtistDetail(a)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(96.dp).glowHalo(i == 0)) { Artwork(cover?.artKey(), Modifier.fillMaxSize(), CircleShape, 30.dp) }
                        Spacer(Modifier.height(6.dp))
                        Text(a, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.plays_n, c), style = MaterialTheme.typography.labelSmall, color = AriosColors.SilverDim)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Column(modifier.glass(RoundedCornerShape(18.dp), fill = 0.05f).padding(12.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = AriosColors.TextPrimary, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = AriosColors.SilverDim)
    }
}

@Composable
private fun Section(title: String, index: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp).staggerIn(index).glass(RoundedCornerShape(26.dp), fill = 0.05f).padding(18.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
        content()
    }
}

/** Bars that grow up one after another (GSAP-style stagger). */
@Composable
private fun DayBars(days: List<Pair<Long, Long>>, modifier: Modifier) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(days) { p.snapTo(0f); p.animateTo(1f, tween(1200, easing = Motion.EaseOutQuint)) }
    val max = (days.maxOfOrNull { it.second } ?: 1L).coerceAtLeast(1L)
    val a = AriosColors.Mint; val l = AriosColors.MintLight; val track = AriosColors.SurfaceHighest
    Canvas(modifier) {
        val n = days.size
        val slot = size.width / n
        val w = slot * 0.62f
        days.forEachIndexed { i, (_, ms) ->
            val local = ((p.value * (n + 6) - i) / 6f).coerceIn(0f, 1f)
            val h = size.height * (ms / max.toFloat()) * local
            val x = i * slot + (slot - w) / 2
            drawRoundRect(track, Offset(x, 0f), Size(w, size.height), CornerRadius(w / 2, w / 2), alpha = 0.5f)
            if (h > 0) drawRoundRect(Brush.verticalGradient(listOf(l, a), startY = size.height - h, endY = size.height), Offset(x, size.height - h), Size(w, h), CornerRadius(w / 2, w / 2))
        }
    }
}

/** 24-hour radial chart of when you listen. */
@Composable
private fun HourClock(hours: IntArray, modifier: Modifier) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(hours.toList()) { p.snapTo(0f); p.animateTo(1f, tween(1400, easing = Motion.EaseOutQuint)) }
    val max = (hours.maxOrNull() ?: 1).coerceAtLeast(1)
    val a = AriosColors.Mint; val dim = AriosColors.SurfaceHighest; val txt = AriosColors.SilverDim
    Canvas(modifier) {
        val r0 = size.minDimension * 0.22f
        val r1 = size.minDimension * 0.48f
        drawCircle(dim, r0, style = Stroke(2f))
        for (h in 0 until 24) {
            val ang = (h / 24f) * 2 * PI - PI / 2
            val v = hours[h] / max.toFloat() * p.value
            val len = r0 + (r1 - r0) * (0.06f + 0.94f * v)
            val s = Offset(center.x + (r0 * cos(ang)).toFloat(), center.y + (r0 * sin(ang)).toFloat())
            val e = Offset(center.x + (len * cos(ang)).toFloat(), center.y + (len * sin(ang)).toFloat())
            drawLine(a.copy(alpha = 0.25f + 0.75f * v), s, e, strokeWidth = 14f, cap = StrokeCap.Round)
        }
        drawCircle(Brush.radialGradient(listOf(a.copy(alpha = 0.35f), Color.Transparent), center, r0), r0)
        @Suppress("UNUSED_VARIABLE") val unused = txt
    }
}
