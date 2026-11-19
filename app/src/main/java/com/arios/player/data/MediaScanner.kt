package com.arios.player.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore

object MediaScanner {
    fun scan(context: Context, minDurationMs: Long): List<Song> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) projection += MediaStore.Audio.Media.RELATIVE_PATH
        val songs = ArrayList<Song>()
        val selection = "${MediaStore.Audio.Media.DURATION} >= ?"
        val args = arrayOf(minDurationMs.toString())
        context.contentResolver.query(collection, projection.toTypedArray(), selection, args, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val iDate = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val iYear = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val iName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val iRel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH) else -1
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val path = c.getString(iData) ?: ""
                val rel = if (iRel >= 0) c.getString(iRel) else null
                val displayName = c.getString(iName) ?: ""
                val folder = when {
                    path.contains('/') -> path.substringBeforeLast('/')
                    !rel.isNullOrEmpty() -> rel.trimEnd('/')
                    else -> "/"
                }
                var title = c.getString(iTitle)?.takeIf { it.isNotBlank() } ?: displayName.substringBeforeLast('.')
                var artist = c.getString(iArtist)?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING } ?: ""
                // "Artist - Title.mp3" files without tags
                if (artist.isEmpty() && title.contains(" - ")) {
                    artist = title.substringBefore(" - ").trim()
                    title = title.substringAfter(" - ").trim()
                }
                songs += Song(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    title = title,
                    artist = artist.ifEmpty { UNKNOWN },
                    album = c.getString(iAlbum)?.takeIf { it.isNotBlank() } ?: UNKNOWN,
                    albumId = c.getLong(iAlbumId),
                    durationMs = c.getLong(iDur),
                    path = path.ifEmpty { (rel ?: "") + displayName },
                    folder = folder,
                    dateAdded = c.getLong(iDate),
                    track = c.getInt(iTrack) % 1000,
                    year = c.getInt(iYear),
                    mimeType = c.getString(iMime) ?: "audio/*",
                    size = c.getLong(iSize),
                )
            }
        }
        return songs
    }

    const val UNKNOWN = "<unknown>"
}
