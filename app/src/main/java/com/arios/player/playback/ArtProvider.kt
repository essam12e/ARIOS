package com.arios.player.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.arios.player.Arios
import com.arios.player.artwork.artKey
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException

/** Serves cached album art as content:// URIs for the notification, Android Auto and widgets. */
class ArtProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val seg = uri.pathSegments
        val albumId = seg.getOrNull(1)?.toLongOrNull() ?: throw FileNotFoundException()
        val songId = seg.getOrNull(2)?.toLongOrNull()
        val cached = Arios.artwork.cacheFile(albumId)
        val file = if (cached.exists()) cached else runBlocking {
            val lib = Arios.library.ensureLoaded()
            val song = songId?.let { lib.byId[it] } ?: lib.songs.firstOrNull { it.albumId == albumId }
            song?.let { Arios.artwork.resolve(it.artKey()) }
        } ?: throw FileNotFoundException()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
