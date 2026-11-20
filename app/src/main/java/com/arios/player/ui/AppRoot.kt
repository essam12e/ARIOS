package com.arios.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arios.player.Arios
import com.arios.player.R
import com.arios.player.ui.components.AddToPlaylistDialog
import com.arios.player.ui.components.SongMenuSheet
import com.arios.player.ui.downloads.DownloadsScreen
import com.arios.player.ui.library.DetailScreen
import com.arios.player.ui.library.LibraryScreen
import com.arios.player.ui.player.*
import com.arios.player.ui.search.SearchScreen
import com.arios.player.ui.settings.SettingsScreen
import com.arios.player.ui.theme.AriosColors
import com.arios.player.ui.theme.Motion
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import com.arios.player.ui.theme.bounceClick
import com.arios.player.ui.theme.glass
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring

private data class NavTarget(val screen: Any, val depth: Int, val tab: Int)

@Composable
fun AppRoot(nav: AppNav, hasPermission: Boolean, requestPermission: () -> Unit) {
    val player by Arios.player.state.collectAsState()
    val snackHost = remember { SnackbarHostState() }
    LaunchedEffect(nav.snack) {
        nav.snack?.let { snackHost.showSnackbar(it); nav.snack = null }
    }
    BackHandler(enabled = nav.stack.isNotEmpty() || nav.tab != Tab.LIBRARY) { nav.back() }

    // lyrics-from-link progress → snackbar
    val linkProgress by Arios.linkLyrics.progress.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(linkProgress) {
        val p = linkProgress ?: return@LaunchedEffect
        nav.snack = when (p.status) {
            com.arios.player.lyrics.LinkLyrics.Status.WORKING -> ctx.getString(R.string.link_working)
            com.arios.player.lyrics.LinkLyrics.Status.FOUND -> ctx.getString(R.string.link_found, p.title ?: "")
            com.arios.player.lyrics.LinkLyrics.Status.NOT_FOUND -> ctx.getString(R.string.link_not_found, p.title ?: "")
            com.arios.player.lyrics.LinkLyrics.Status.FAILED -> ctx.getString(R.string.link_failed)
        }
        if (p.status != com.arios.player.lyrics.LinkLyrics.Status.WORKING) Arios.linkLyrics.clearProgress()
    }

    // live theme: accent / AMOLED / colours taken from the current cover
    val prefsV by Arios.prefs.version.collectAsState()
    val lib by Arios.library.state.collectAsState()
    val coverPalette = if (Arios.prefs.dynamicColor) rememberArtPalette(player.songId?.let { lib.byId[it] }) else null
    LaunchedEffect(prefsV, coverPalette?.base) {
        val accent = coverPalette?.let { com.arios.player.ui.theme.AriosColors.fromColor(it.base) }
            ?: (com.arios.player.ui.theme.ACCENTS.firstOrNull { it.id == Arios.prefs.accent } ?: com.arios.player.ui.theme.ACCENTS[0])
        com.arios.player.ui.theme.AriosColors.apply(accent, Arios.prefs.amoled)
    }

    val miniVisible = player.hasMedia
    val bottomPadding = (if (miniVisible) 76.dp else 0.dp) + 84.dp

    // depth effect: the app recedes (scale + dim + rounded) while the player rises over it
    val depth by animateFloatAsState(
        if (nav.playerOpen || nav.lyricsFull || nav.carMode || nav.wrapped) 1f else 0f,
        spring(dampingRatio = 0.9f, stiffness = 220f), label = "depth",
    )
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl

    Box(Modifier.fillMaxSize().background(AriosColors.Black)) {
        com.arios.player.ui.theme.AuroraBackground()
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val sc = 1f - 0.08f * depth
                scaleX = sc; scaleY = sc
                alpha = 1f - 0.55f * depth
                translationY = -24f * depth
                shape = RoundedCornerShape((32 * depth).dp)
                clip = depth > 0.01f
            }
        ) {
            val target = NavTarget(nav.stack.lastOrNull() ?: nav.tab, nav.stack.size, nav.tab.ordinal)
            AnimatedContent(
                targetState = target,
                transitionSpec = {
                    when {
                        targetState.depth != initialState.depth -> Motion.page(targetState.depth > initialState.depth, rtl)
                        targetState.tab != initialState.tab -> Motion.tab(targetState.tab > initialState.tab, rtl)
                        else -> Motion.page(true, rtl)
                    }.using(SizeTransform(clip = false))
                },
                contentKey = { it.screen },
                label = "screen",
            ) { t ->
                Box(Modifier.fillMaxSize()) {
                    when (val target2 = t.screen) {
                        is Screen.LyricsEditor -> LyricsEditorScreen(nav, target2.songId, bottomPadding)
                        is Screen.Sound -> com.arios.player.ui.audio.SoundScreen(nav, bottomPadding)
                        is Screen.Stats -> com.arios.player.ui.stats.StatsScreen(nav, bottomPadding)
                        is Screen.Duplicates -> com.arios.player.ui.library.DuplicatesScreen(nav, bottomPadding)
                        is Screen.LinkLyrics -> com.arios.player.ui.links.LinkLyricsScreen(nav, bottomPadding)
                        is Screen.TagEditor -> com.arios.player.ui.library.TagEditorScreen(nav, target2.songId, bottomPadding)
                        is Screen.ShareLyrics -> com.arios.player.ui.share.ShareLyricsScreen(nav, target2.songId, target2.line, bottomPadding)
                        is Screen -> DetailScreen(nav, target2, bottomPadding)
                        Tab.LIBRARY -> LibraryScreen(nav, hasPermission, requestPermission, bottomPadding)
                        Tab.SEARCH -> SearchScreen(nav, bottomPadding)
                        Tab.DOWNLOADS -> DownloadsScreen(nav, bottomPadding)
                        Tab.SETTINGS -> SettingsScreen(nav, bottomPadding)
                    }
                }
            }

            // bottom gradient + mini player + nav bar
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, AriosColors.Black.copy(alpha = 0.75f), AriosColors.Black.copy(alpha = 0.95f))))
                .navigationBarsPadding().padding(top = 18.dp)) {
                AnimatedVisibility(
                    miniVisible,
                    enter = slideInVertically(Motion.offsetSoft) { it } + Motion.fadeInSoft(),
                    exit = slideOutVertically { it } + Motion.fadeOutSoft(),
                ) { MiniPlayer(nav) }
                Spacer(Modifier.height(8.dp))
                BottomBar(nav)
                Spacer(Modifier.height(8.dp))
            }
        }

        SnackbarHost(snackHost, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding + 8.dp))

        AnimatedVisibility(
            nav.clipLink != null && !nav.playerOpen && !nav.lyricsFull,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp),
            enter = slideInVertically(Motion.offsetSoft) { -it } + Motion.fadeInSoft(),
            exit = slideOutVertically { -it } + Motion.fadeOutSoft(),
        ) {
            nav.clipLink?.let { link -> com.arios.player.ui.links.ClipboardLinkBanner(link) { nav.clipLink = null } }
        }

        AnimatedVisibility(nav.playerOpen, enter = Motion.playerEnter, exit = Motion.playerExit) { NowPlayingScreen(nav) }
        AnimatedVisibility(nav.lyricsFull, enter = Motion.lyricsEnter, exit = Motion.lyricsExit) { FullLyricsScreen(nav) }
        AnimatedVisibility(nav.carMode, enter = Motion.lyricsEnter, exit = Motion.lyricsExit) { CarModeScreen(nav) }
        AnimatedVisibility(nav.wrapped, enter = Motion.playerEnter, exit = Motion.playerExit) { com.arios.player.ui.stats.WrappedScreen(nav) }

        QueueSheet(nav)
        SongMenuSheet(nav)
        AddToPlaylistDialog(nav)
        LyricsSearchSheet(nav)
    }
}

@Composable
private fun BottomBar(nav: AppNav) {
    val tasks by Arios.downloader.tasks.collectAsState()
    val active = tasks.count { it.state == com.arios.player.download.DlState.RUNNING || it.state == com.arios.player.download.DlState.QUEUED }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).background(AriosColors.Surface, CircleShape).glass(CircleShape, glow = AriosColors.Mint.copy(alpha = 0.5f), fill = 0.06f).padding(6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        NavItem(Icons.Filled.LibraryMusic, stringResource(R.string.library), nav.tab == Tab.LIBRARY && nav.stack.isEmpty()) { nav.selectTab(Tab.LIBRARY) }
        NavItem(Icons.Filled.Search, stringResource(R.string.search), nav.tab == Tab.SEARCH && nav.stack.isEmpty()) { nav.selectTab(Tab.SEARCH) }
        NavItem(Icons.Filled.Download, stringResource(R.string.downloads), nav.tab == Tab.DOWNLOADS && nav.stack.isEmpty(), badge = active) { nav.selectTab(Tab.DOWNLOADS) }
        NavItem(Icons.Filled.Settings, stringResource(R.string.settings), nav.tab == Tab.SETTINGS && nav.stack.isEmpty()) { nav.selectTab(Tab.SETTINGS) }
    }
}

@Composable
private fun RowScope.NavItem(icon: ImageVector, label: String, selected: Boolean, badge: Int = 0, onClick: () -> Unit) {
    val alpha by animateFloatAsState(if (selected) 1f else 0f, label = "nav")
    val w by animateFloatAsState(if (selected) 1.6f else 1f, spring(dampingRatio = 0.6f, stiffness = 300f), label = "w")
    Row(
        Modifier.weight(w).clip(CircleShape).background(AriosColors.brandHorizontal, alpha = alpha).bounceClick(onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        BadgedBox(badge = { if (badge > 0) Badge(containerColor = AriosColors.Mint) { Text("$badge") } }) {
            Icon(icon, label, tint = if (selected) AriosColors.Black else AriosColors.SilverDim)
        }
        AnimatedVisibility(selected) {
            Text(label, Modifier.padding(start = 8.dp), color = AriosColors.Black, fontWeight = FontWeight.SemiBold, maxLines = 1,
                style = MaterialTheme.typography.labelLarge)
        }
    }
}
