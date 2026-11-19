package com.arios.player.data

import android.net.Uri

data class Song(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val path: String,
    val folder: String,
    val dateAdded: Long,
    val track: Int,
    val year: Int,
    val mimeType: String,
    val size: Long,
) {
    /** Device-independent key used for favorites, playlists, lyrics and backups. */
    val key: String by lazy { songKey(title, artist, durationMs) }
    val ext: String get() = path.substringAfterLast('.', "").uppercase()
}

fun songKey(title: String, artist: String, durationMs: Long): String =
    "${title.trim().lowercase()}|${artist.trim().lowercase()}|${(durationMs + 500) / 1000}"

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val year: Int,
    val songs: List<Song>,
) { val cover: Song get() = songs.first() }

data class Artist(
    val name: String,
    val songs: List<Song>,
    val albums: List<Album>,
) { val cover: Song get() = songs.first() }

data class Folder(
    val path: String,
    val name: String,
    val songs: List<Song>,
)

data class Playlist(
    val id: String,
    val name: String,
    val songKeys: List<String>,
    val created: Long,
)

data class LibraryState(
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val loaded: Boolean = false,
    val scanning: Boolean = false,
) {
    val byId: Map<Long, Song> by lazy { songs.associateBy { it.id } }
    val byKey: Map<String, Song> by lazy {
        val m = HashMap<String, Song>()
        songs.forEach { m.putIfAbsent(it.key, it) }
        m
    }
}
