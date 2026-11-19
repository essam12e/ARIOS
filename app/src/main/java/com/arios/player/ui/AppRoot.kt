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

@Composable
fun AppRoot(nav: AppNav, hasPermission: Boolean, requestPermission: () -> Unit) {
    val player by Arios.player.state.collectAsState()
    val snackHost = remember { SnackbarHostState() }
    LaunchedEffect(nav.snack) {
        nav.snack?.let { snackHost.showSnackbar(it); nav.snack = null }
    }
    BackHandler(enabled = nav.stack.isNotEmpty() || nav.tab != Tab.LIBRARY) { nav.back() }

    val miniVisible = player.hasMedia
    val bottomPadding = (if (miniVisible) 76.dp else 0.dp) + 84.dp

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val top = nav.stack.lastOrNull()
        AnimatedContent(
            targetState = top ?: nav.tab,
            transitionSpec = { (fadeIn(tween(220)) + slideInVertically { it / 24 }) togetherWith fadeOut(tween(160)) },
            label = "screen",
        ) { target ->
            Box(Modifier.fillMaxSize()) {
                when (target) {
                    is Screen.LyricsEditor -> LyricsEditorScreen(nav, target.songId, bottomPadding)
                    is Screen -> DetailScreen(nav, target, bottomPadding)
                    Tab.LIBRARY -> LibraryScreen(nav, hasPermission, requestPermission, bottomPadding)
                    Tab.SEARCH -> SearchScreen(nav, bottomPadding)
                    Tab.DOWNLOADS -> DownloadsScreen(nav, bottomPadding)
                    Tab.SETTINGS -> SettingsScreen(nav, bottomPadding)
                }
            }
        }

        // bottom gradient + mini player + nav bar
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, AriosColors.Black.copy(alpha = 0.92f), AriosColors.Black)))
            .navigationBarsPadding().padding(top = 18.dp)) {
            MiniPlayer(nav)
            Spacer(Modifier.height(8.dp))
            BottomBar(nav)
            Spacer(Modifier.height(8.dp))
        }

        SnackbarHost(snackHost, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding + 8.dp))

        AnimatedVisibility(
            nav.playerOpen,
            enter = slideInVertically(tween(320)) { it } + fadeIn(),
            exit = slideOutVertically(tween(260)) { it } + fadeOut(),
        ) { NowPlayingScreen(nav) }

        AnimatedVisibility(
            nav.lyricsFull,
            enter = slideInVertically(tween(320)) { it } + fadeIn(),
            exit = slideOutVertically(tween(260)) { it } + fadeOut(),
        ) { FullLyricsScreen(nav) }

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
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(CircleShape).background(AriosColors.Surface).padding(6.dp),
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
    val bg by animateColorAsState(if (selected) AriosColors.Silver else Color.Transparent, label = "nav")
    Row(
        Modifier.weight(if (selected) 1.6f else 1f).clip(CircleShape).background(bg).clickable(onClick = onClick).padding(vertical = 12.dp),
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
