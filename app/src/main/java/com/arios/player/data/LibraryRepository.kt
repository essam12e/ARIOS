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
        _state.value = build(songs)
    }

    /** Test hook: replace the library with [songs]. */
    internal fun setForTest(songs: List<Song>) { _state.value = build(songs) }

    private fun build(songs: List<Song>): LibraryState {
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
