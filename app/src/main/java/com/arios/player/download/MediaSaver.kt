package com.arios.player.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.InputStream
import kotlin.coroutines.resume

/** Writes audio files into the public "Music/ARIOS" folder so they appear in every player. */
object MediaSaver {
    const val FOLDER = "Flowfy"

    fun safeName(s: String) = s.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_").trim().take(120).ifEmpty { "track" }

    fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a", "aac", "mp4" -> "audio/mp4"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wav" -> "audio/x-wav"
        else -> "audio/*"
    }

    /** Copies [input] into Music/ARIOS/[displayName] and returns the MediaStore uri. */
    suspend fun save(context: Context, displayName: String, mime: String, input: InputStream): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$FOLDER")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: return null
            try {
                resolver.openOutputStream(uri)?.use { out -> input.copyTo(out, 64 * 1024) } ?: error("no stream")
                values.clear(); values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null); null
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER).apply { mkdirs() }
            var file = File(dir, displayName)
            var n = 1
            while (file.exists()) file = File(dir, displayName.substringBeforeLast('.') + " ($n)." + displayName.substringAfterLast('.')).also { n++ }
            file.outputStream().use { input.copyTo(it, 64 * 1024) }
            scanFile(context, file.path, mime)
        }
    }

    private suspend fun scanFile(context: Context, path: String, mime: String): Uri? = suspendCancellableCoroutine { cont ->
        MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(mime)) { _, uri -> cont.resume(uri) }
    }

    fun albumIdOf(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.ALBUM_ID), null, null, null)?.use {
            if (it.moveToFirst()) it.getLong(0) else null
        }
    }.getOrNull()
}
