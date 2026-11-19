package com.arios.player.artwork

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.arios.player.Arios
import com.arios.player.data.MediaScanner
import com.arios.player.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okio.Path.Companion.toOkioPath
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/** Key passed to Coil to load the cover of a song/album. */
data class ArtKey(
    val albumId: Long,
    val songUri: String,
    val artist: String,
    val album: String,
    val title: String,
)

fun Song.artKey() = ArtKey(albumId, uri.toString(), artist, album, title)

/**
 * Resolves album artwork: cached file → embedded picture → MediaStore thumbnail → online (iTunes Search).
 * Everything is cached on disk so it is available offline.
 */
class ArtworkRepository(private val context: Context) {
    private val dir = File(context.filesDir, "artwork").apply { mkdirs() }
    private val locks = ConcurrentHashMap<Long, Mutex>()

    fun cacheFile(albumId: Long) = File(dir, "a_$albumId.jpg")
    private fun missFile(albumId: Long) = File(dir, "a_$albumId.none")

    suspend fun resolve(key: ArtKey): File? = locks.getOrPut(key.albumId) { Mutex() }.withLock {
        withContext(Dispatchers.IO) {
            val out = cacheFile(key.albumId)
            if (out.exists() && out.length() > 0) return@withContext out
            if (fromEmbedded(key, out)) return@withContext out
            if (fromThumbnail(key, out)) return@withContext out
            val miss = missFile(key.albumId)
            if (Arios.prefs.autoFetchArtwork &&
                (!miss.exists() || System.currentTimeMillis() - miss.lastModified() > 5L * 24 * 3600 * 1000)
            ) {
                if (fromOnline(key, out)) return@withContext out
                runCatching { miss.writeText("") }
            }
            null
        }
    }

    private fun fromEmbedded(key: ArtKey, out: File): Boolean = runCatching {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, Uri.parse(key.songUri))
            val bytes = mmr.embeddedPicture ?: return false
            writeScaled(BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false, out)
        } finally { runCatching { mmr.release() } }
    }.getOrDefault(false)

    private fun fromThumbnail(key: ArtKey, out: File): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bmp = context.contentResolver.loadThumbnail(Uri.parse(key.songUri), Size(600, 600), null)
            writeScaled(bmp, out)
        } else {
            val uri = Uri.parse("content://media/external/audio/albumart/${key.albumId}")
            context.contentResolver.openInputStream(uri)?.use { s ->
                writeScaled(BitmapFactory.decodeStream(s) ?: return false, out)
            } ?: false
        }
    }.getOrDefault(false)

    private fun fromOnline(key: ArtKey, out: File): Boolean = runCatching {
        val artist = key.artist.takeUnless { it == MediaScanner.UNKNOWN } ?: ""
        val album = key.album.takeUnless { it == MediaScanner.UNKNOWN || it.equals("download", true) || it.equals("music", true) } ?: ""
        val (term, entity) = if (album.isNotBlank()) "$artist $album" to "album" else "$artist ${key.title}" to "song"
        if (term.isBlank()) return false
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", term.trim()).addQueryParameter("entity", entity)
            .addQueryParameter("limit", "1").build()
        val body = Arios.http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() } ?: return false
        val results = JSONObject(body).optJSONArray("results") ?: return false
        if (results.length() == 0) return false
        val art = results.getJSONObject(0).optString("artworkUrl100").ifBlank { return false }
            .replace("100x100bb", "600x600bb")
        downloadTo(art, out)
    }.getOrDefault(false)

    fun downloadTo(url: String, out: File): Boolean = runCatching {
        Arios.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return false
            val bytes = r.body?.bytes() ?: return false
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
            writeScaled(bmp, out)
        }
    }.getOrDefault(false)

    private fun writeScaled(src: Bitmap, out: File): Boolean {
        val max = 720
        val bmp = if (src.width > max || src.height > max) {
            val s = max.toFloat() / maxOf(src.width, src.height)
            Bitmap.createScaledBitmap(src, (src.width * s).toInt(), (src.height * s).toInt(), true)
        } else src
        val tmp = File(out.parentFile, out.name + ".tmp")
        FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return tmp.renameTo(out)
    }

    /** Synchronous helper for widgets/notifications (call off the main thread). */
    fun loadBitmapBlocking(key: ArtKey, size: Int): Bitmap? {
        val f = kotlinx.coroutines.runBlocking { resolve(key) } ?: return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, opts)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= size) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun clearMisses() { dir.listFiles { f -> f.extension == "none" }?.forEach { it.delete() } }
    fun clearAll() { dir.listFiles()?.forEach { it.delete() } }
}

class ArtworkFetcher(private val key: ArtKey) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val file = Arios.artwork.resolve(key) ?: throw IllegalStateException("no artwork")
        return SourceResult(
            source = ImageSource(file.toOkioPath(), okio.FileSystem.SYSTEM),
            mimeType = "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<ArtKey> {
        override fun create(data: ArtKey, options: Options, imageLoader: ImageLoader): Fetcher = ArtworkFetcher(data)
    }
}
