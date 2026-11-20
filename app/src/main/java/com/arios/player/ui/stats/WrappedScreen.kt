package com.arios.player.ui.stats

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.togetherWith
import com.arios.player.Arios
import com.arios.player.R
import com.arios.player.artwork.artKey
import com.arios.player.data.Insights
import com.arios.player.share.ShareUtil
import com.arios.player.share.SummaryRenderer
import com.arios.player.ui.AppNav
import com.arios.player.ui.components.Artwork
import com.arios.player.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** "Your year in ARIOS" – story-style animated recap. */
@Composable
fun WrappedScreen(nav: AppNav) {
    val lib by Arios.library.state.collectAsState()
    val sum = remember { Insights.summary(Insights.Period.YEAR, lib) }
    val pages = 6
    var page by remember { mutableIntStateOf(0) }
    val progress = remember { Animatable(0f) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler { nav.wrapped = false }
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    LaunchedEffect(page) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(6500, easing = LinearEasing))
        if (page < pages - 1) page++
    }
    Box(
        Modifier.fillMaxSize().background(AriosColors.Black).pointerInput(rtl) {
            detectTapGestures { o ->
                val forward = if (rtl) o.x < size.width / 3 else o.x > size.width / 3
                page = if (forward) (page + 1).coerceAtMost(pages - 1) else (page - 1).coerceAtLeast(0)
            }
        }
    ) {
        AuroraBackground(intensity = 1.8f)
        // page content with a big, springy transition
        androidx.compose.animation.AnimatedContent(
            page,
            transitionSpec = {
                (androidx.compose.animation.fadeIn(tween(500)) + androidx.compose.animation.scaleIn(Motion.soft(), 0.85f)) togetherWith
                    (androidx.compose.animation.fadeOut(tween(250)) + androidx.compose.animation.scaleOut(tween(300), 1.1f))
            },
            label = "story",
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(top = 40.dp),
        ) { p ->
            Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                when (p) {
                    0 -> {
                        Text(stringResource(R.string.wrapped_intro), style = MaterialTheme.typography.displaySmall.copy(brush = AriosColors.brand), textAlign = TextAlign.Center, modifier = Modifier.staggerIn(0, 150))
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.wrapped_intro_sub), color = AriosColors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.staggerIn(1, 150))
                    }
                    1 -> {
                        val m by animateIntAsState(0, label = "x")
                        val target = (sum.totalMs / 60000).toInt()
                        var shown by remember { mutableIntStateOf(m) }
                        LaunchedEffect(Unit) { animate(0f, target.toFloat(), animationSpec = tween(2200, easing = Motion.EaseOutQuint)) { v, _ -> shown = v.toInt() } }
                        Text(stringResource(R.string.wrapped_minutes_pre), color = AriosColors.TextSecondary, modifier = Modifier.staggerIn(0, 120))
                        Text("%,d".format(shown), fontSize = 84.sp, fontWeight = FontWeight.Black, style = MaterialTheme.typography.displayLarge.copy(brush = AriosColors.brand))
                        Text(stringResource(R.string.wrapped_minutes_post), style = MaterialTheme.typography.titleLarge, modifier = Modifier.staggerIn(2, 120))
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.wrapped_hours, target / 60), color = AriosColors.SilverDim, modifier = Modifier.staggerIn(3, 120))
                    }
                    2 -> {
                        val a = sum.topArtists.firstOrNull()
                        Text(stringResource(R.string.wrapped_top_artist), color = AriosColors.TextSecondary, modifier = Modifier.staggerIn(0, 120))
                        Spacer(Modifier.height(20.dp))
                        val spin = rememberInfiniteTransition(label = "s").animateFloat(-3f, 3f, infiniteRepeatable(tween(3000), RepeatMode.Reverse), label = "r")
                        Box(Modifier.size(230.dp).staggerIn(1, 150).glowHalo(true).graphicsLayer { rotationZ = spin.value }) {
                            Artwork(a?.third?.artKey(), Modifier.fillMaxSize(), CircleShape, 60.dp)
                        }
                        Spacer(Modifier.height(20.dp))
                        Text(a?.first ?: "—", style = MaterialTheme.typography.headlineMedium.copy(brush = AriosColors.brand), textAlign = TextAlign.Center, modifier = Modifier.staggerIn(2, 150))
                        a?.let { Text(stringResource(R.string.plays_n, it.second), color = AriosColors.SilverDim, modifier = Modifier.staggerIn(3, 150)) }
                    }
                    3 -> {
                        Text(stringResource(R.string.wrapped_top_songs), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.staggerIn(0, 100))
                        Spacer(Modifier.height(18.dp))
                        sum.topSongs.take(5).forEachIndexed { i, (s, c) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).staggerIn(i + 1, 140).glass(RoundedCornerShape(18.dp), fill = 0.06f).padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}", fontSize = 28.sp, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge.copy(brush = AriosColors.brand), modifier = Modifier.width(36.dp))
                                Artwork(s.artKey(), Modifier.size(50.dp), RoundedCornerShape(12.dp), 18.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                    Text(s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = AriosColors.SilverDim, style = MaterialTheme.typography.bodySmall)
                                }
                                Text("$c×", color = AriosColors.Mint)
                            }
                        }
                    }
                    4 -> {
                        val peak = sum.perHour.indices.maxByOrNull { sum.perHour[it] } ?: 20
                        Text(stringResource(R.string.wrapped_clock), color = AriosColors.TextSecondary, modifier = Modifier.staggerIn(0, 120))
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(when (peak) { in 0..4 -> R.string.persona_night; in 5..10 -> R.string.persona_morning; in 11..16 -> R.string.persona_day; else -> R.string.persona_evening }),
                            style = MaterialTheme.typography.headlineMedium.copy(brush = AriosColors.brand), textAlign = TextAlign.Center, modifier = Modifier.staggerIn(1, 150))
                        Spacer(Modifier.height(10.dp))
                        Text(stringResource(R.string.wrapped_peak, peak), color = AriosColors.SilverDim, modifier = Modifier.staggerIn(2, 150))
                        Spacer(Modifier.height(18.dp))
                        Text(stringResource(R.string.wrapped_streak, sum.longestStreakDays), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.staggerIn(3, 150))
                    }
                    else -> {
                        Text(stringResource(R.string.wrapped_share_title), style = MaterialTheme.typography.headlineSmall.copy(brush = AriosColors.brand), textAlign = TextAlign.Center, modifier = Modifier.staggerIn(0, 120))
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    val bmp = SummaryRenderer(context).render(sum, context.getString(R.string.wrapped_card_title),
                                        context.getString(R.string.minutes_listened), context.getString(R.string.top_songs),
                                        com.arios.player.ui.theme.AriosColors.Mint.toArgbInt(), com.arios.player.ui.theme.AriosColors.MintLight.toArgbInt(),
                                        com.arios.player.ui.theme.AriosColors.Emerald.toArgbInt())
                                    ShareUtil.shareBitmap(context, bmp, "arios_year.png")
                                }
                            },
                            colors = ButtonDefaults.buttonColors(AriosColors.Mint, AriosColors.Black),
                            modifier = Modifier.staggerIn(1, 150).glowHalo(true),
                        ) { Icon(Icons.Filled.Share, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.share)) }
                    }
                }
            }
        }
        // story progress segments
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(pages) { i ->
                Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f))) {
                    val f = when { i < page -> 1f; i == page -> progress.value; else -> 0f }
                    Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(AriosColors.brandHorizontal))
                }
            }
        }
        IconButton(onClick = { nav.wrapped = false }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 18.dp)) {
            Icon(Icons.Filled.Close, null, tint = Color.White)
        }
    }
}

fun Color.toArgbInt(): Int = android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())

@Suppress("unused") private suspend fun pause(ms: Long) = delay(ms)
@Suppress("unused") private val unusedBrush: Brush? = null
@Suppress("unused") private val unusedShape = RoundedCornerShape(0.dp)
