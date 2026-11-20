package com.arios.player.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.arios.player.data.Song

sealed interface Screen {
    data class AlbumDetail(val albumId: Long) : Screen
    data class ArtistDetail(val name: String) : Screen
    data class FolderDetail(val path: String) : Screen
    data class PlaylistDetail(val id: String) : Screen
    data object Favorites : Screen
    data class LyricsEditor(val songId: Long) : Screen
    data object Sound : Screen
    data object Stats : Screen
    data object Duplicates : Screen
    data class TagEditor(val songId: Long) : Screen
    data class ShareLyrics(val songId: Long, val line: Int = -1) : Screen
    data class Smart(val list: com.arios.player.data.SmartList) : Screen
}

enum class Tab { LIBRARY, SEARCH, DOWNLOADS, SETTINGS }

/** All navigation + overlay state of the single activity. */
class AppNav {
    var tab by mutableStateOf(Tab.LIBRARY)
    val stack = mutableStateListOf<Screen>()
    var playerOpen by mutableStateOf(false)
    var lyricsFull by mutableStateOf(false)
    var queueOpen by mutableStateOf(false)
    var menuSong by mutableStateOf<Song?>(null)
    var addToPlaylist by mutableStateOf<List<Song>?>(null)
    var lyricsSearchFor by mutableStateOf<Song?>(null)
    var snack by mutableStateOf<String?>(null)
    var downloadsTab by mutableIntStateOf(0)
    var carMode by mutableStateOf(false)
    var wrapped by mutableStateOf(false)

    fun push(s: Screen) { stack += s; playerOpen = false; lyricsFull = false; carMode = false }
    fun back(): Boolean = when {
        wrapped -> { wrapped = false; true }
        carMode -> { carMode = false; true }
        lyricsFull -> { lyricsFull = false; true }
        queueOpen -> { queueOpen = false; true }
        playerOpen -> { playerOpen = false; true }
        stack.isNotEmpty() -> { stack.removeAt(stack.lastIndex); true }
        tab != Tab.LIBRARY -> { tab = Tab.LIBRARY; true }
        else -> false
    }
    fun selectTab(t: Tab) { tab = t; stack.clear() }
}
