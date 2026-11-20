package com.arios.player.data

import android.content.Context
import com.arios.player.Arios
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LibraryRepository(private val context: Context) {
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state
    private val mutex = Mutex()

    fun refresh() { Arios.scope.launch { scan() } }

    suspend fun ensureLoaded(): LibraryState {
        if (!_state.value.loaded) scan()
        return _state.value
    }

    suspend fun scan() = mutex.withLock {
        _state.value = _state.value.copy(scanning = true)
        val songs = withContext(Dispatchers.IO) {
            runCatching { MediaScanner.scan(context, Arios.prefs.minDurationSec * 1000L) }.getOrElse { emptyList() }
        }
        raw = songs
        _state.value = build(songs)
        // lyrics saved from links find their songs; plain lyrics get timed in the background
        Arios.scope.launch {
            runCatching { Arios.linkLyrics.attachAll(songs) }
            if (Arios.prefs.autoSyncLyrics) runCatching { Arios.lyrics.syncLibrary(_state.value.songs) }
        }
    }

    /** Every scanned song, including those in excluded folders. */
    @Volatile var raw: List<Song> = emptyList()
        private set

    /** Re-applies exclusions and tag edits without rescanning the device. */
    fun rebuild() { Arios.scope.launch { mutex.withLock { _state.value = build(raw) } } }

    /** Test hook: replace the library with [songs]. */
    internal fun setForTest(songs: List<Song>) { raw = songs; _state.value = build(songs) }

    private fun build(all: List<Song>): LibraryState {
        val excluded = Arios.prefs.excludedFolders
        val songs = all.filter { s -> excluded.none { s.folder == it || s.folder.startsWith("$it/") } }
            .map { Arios.tagOverrides.apply(it) }
        val sorted = songs.sortedWith(compareBy(nameComparator) { it.title })
        val albums = songs.groupBy { it.albumId }.map { (id, list) ->
            val tracks = list.sortedWith(compareBy<Song> { it.track }.thenBy(nameComparator) { it.title })
            val artist = list.groupingBy { it.artist }.eachCount().maxByOrNull { it.value }?.key ?: MediaScanner.UNKNOWN
            Album(id, list.first().album, artist, list.maxOf { it.year }, tracks)
        }.sortedWith(compareBy(nameComparator) { it.title })
        val artists = songs.groupBy { it.artist.lowercase() }.map { (_, list) ->
            val name = list.first().artist
            Artist(
                name = name,
                songs = list.sortedWith(compareBy(nameComparator) { it.title }),
                albums = albums.filter { a -> a.songs.any { it.artist.equals(name, true) } },
            )
        }.sortedWith(compareBy(nameComparator) { it.name })
        val folders = songs.groupBy { it.folder }.map { (path, list) ->
            Folder(path, path.substringAfterLast('/').ifEmpty { path }, list.sortedWith(compareBy(nameComparator) { it.title }))
        }.sortedWith(compareBy(nameComparator) { it.name })
        return LibraryState(sorted, albums, artists, folders, loaded = true, scanning = false)
    }
}
