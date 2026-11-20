package com.arios.player.data

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.arios.player.Arios
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

sealed interface EditResult {
    data object WrittenToFile : EditResult
    data object SavedInApp : EditResult
    data class NeedsPermission(val sender: IntentSender) : EditResult
    data class Failed(val message: String) : EditResult
}

/** Write/delete access to audio files the app does not own (Android 10+ scoped storage). */
object FileAccess {
    fun writeRequest(context: Context, uris: List<Uri>): IntentSender? =
        if (Build.VERSION.SDK_INT >= 30) MediaStore.createWriteRequest(context.contentResolver, uris).intentSender else null

    fun deleteRequest(context: Context, uris: List<Uri>): IntentSender? =
        if (Build.VERSION.SDK_INT >= 30) MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender else null

    /** Deletes directly where allowed; returns an IntentSender when the user must confirm. */
    suspend fun delete(context: Context, songs: List<Song>): IntentSender? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= 30) return@withContext deleteRequest(context, songs.map { it.uri })
        for (s in songs) {
            try { context.contentResolver.delete(s.uri, null, null) }
            catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException) return@withContext e.userAction.actionIntent.intentSender
            }
        }
        null
    }

    private suspend fun scan(context: Context, path: String) = suspendCancellableCoroutine { c ->
        MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, _ -> c.resume(Unit) }
    }

    /** Applies a tag edit (and optional new cover) to [song]. Call again after a NeedsPermission was granted. */
    suspend fun applyEdit(context: Context, song: Song, edit: TagEdit, coverUri: Uri?): EditResult = withContext(Dispatchers.IO) {
        val cover = coverUri?.let { loadCover(context, it) }
        // always keep the edit in ARIOS so the library updates immediately
        Arios.tagOverrides.set(song.id, edit)
        cover?.let { c ->
            runCatching { Arios.artwork.songFile(song.id).writeBytes(c.bytes) }
            runCatching { coil.Coil.imageLoader(context).memoryCache?.clear() }
        }
        migrateKeys(song, edit)
        val original = runCatching { context.contentResolver.openInputStream(song.uri)?.use { it.readBytes() } }.getOrNull()
            ?: return@withContext EditResult.SavedInApp.also { Arios.library.rebuild() }
        val updated = com.arios.player.data.TagWriter.write(original, edit, cover)
            ?: return@withContext EditResult.SavedInApp.also { Arios.library.rebuild() }
        try {
            context.contentResolver.openOutputStream(song.uri, "wt")?.use { it.write(updated) } ?: error("no stream")
        } catch (e: SecurityException) {
            Arios.library.rebuild()
            if (Build.VERSION.SDK_INT >= 30) return@withContext EditResult.NeedsPermission(writeRequest(context, listOf(song.uri))!!)
            if (Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException) return@withContext EditResult.NeedsPermission(e.userAction.actionIntent.intentSender)
            return@withContext EditResult.SavedInApp
        } catch (e: Exception) {
            // put the original back if anything went wrong mid-write
            runCatching { context.contentResolver.openOutputStream(song.uri, "wt")?.use { it.write(original) } }
            Arios.library.rebuild()
            return@withContext EditResult.Failed(e.message ?: "error")
        }
        if (song.path.startsWith("/")) runCatching { scan(context, song.path) }
        Arios.library.scan()
        EditResult.WrittenToFile
    }

    private fun migrateKeys(song: Song, e: TagEdit) {
        val newKey = songKey(e.title ?: song.title, e.artist ?: song.artist, song.durationMs)
        if (newKey == song.key) return
        Arios.userStore.renameKey(song.key, newKey)
        Arios.lyrics.renameKey(song.key, newKey)
    }

    private fun loadCover(context: Context, uri: Uri): TagWriter.Cover? = runCatching {
        val src = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
        val max = 800
        val bmp = if (src.width > max || src.height > max) {
            val s = max.toFloat() / maxOf(src.width, src.height)
            Bitmap.createScaledBitmap(src, (src.width * s).toInt(), (src.height * s).toInt(), true)
        } else src
        val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        TagWriter.Cover(out.toByteArray(), "image/jpeg", bmp.width, bmp.height)
    }.getOrNull()
}
