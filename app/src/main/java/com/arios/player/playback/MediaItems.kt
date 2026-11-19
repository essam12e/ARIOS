package com.arios.player.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.arios.player.data.Song

const val ART_AUTHORITY = "com.arios.player.art"

fun artUri(song: Song): Uri = Uri.parse("content://$ART_AUTHORITY/album/${song.albumId}/${song.id}")

fun Song.toMediaItem(mediaId: String = "song:$id"): MediaItem = MediaItem.Builder()
    .setMediaId(mediaId)
    .setUri(uri)
    .setMimeType(mimeType.takeIf { it.startsWith("audio/") && it != "audio/*" })
    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setAlbumArtist(artist)
            .setArtworkUri(artUri(this))
            .setTrackNumber(track.takeIf { it > 0 })
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setDurationMs(durationMs)
            .build()
    ).build()

fun browsable(id: String, title: String, subtitle: String? = null, art: Uri? = null, type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED): MediaItem =
    MediaItem.Builder().setMediaId(id).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).setArtworkUri(art)
            .setIsBrowsable(true).setIsPlayable(false).setMediaType(type).build()
    ).build()

/** Extracts the MediaStore id from "song:12" or "album:3::12". */
fun songIdOf(mediaId: String): Long? = when {
    mediaId.contains("::") -> mediaId.substringAfterLast("::").toLongOrNull()
    mediaId.startsWith("song:") -> mediaId.removePrefix("song:").toLongOrNull()
    else -> mediaId.toLongOrNull()
}
