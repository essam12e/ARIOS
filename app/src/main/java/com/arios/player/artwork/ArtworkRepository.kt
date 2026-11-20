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
    val songId: Long = -1,
    val folderName: String = "",
)

fun Song.artKey() = ArtKey(albumId, uri.toString(), artist, album, title, id, folder.substringAfterLast('/'))

/**
 * Resolves album artwork: cached file → embedded picture → MediaStore thumbnail → online (iTunes Search).
 * Everything is cached on disk so it is available offline.
 */
class ArtworkRepository(private val context: Context) {
    private val dir = File(context.filesDir, "artwork").apply { mkdirs() }
    private val locks = ConcurrentHashMap<Long, Mutex>()

    init { migrate(context) }

    /** Album-level cover (only used for real, tagged albums). */
    fun cacheFile(albumId: Long) = File(dir, "a_$albumId.jpg")
    /** Per-song cover: every song keeps its own picture. */
    fun songFile(songId: Long) = File(dir, "s_$songId.jpg")
    private fun songMiss(songId: Long) = File(dir, "s_$songId.none")
    private fun albumMiss(albumId: Long) = File(dir, "a_$albumId.none")

    private val genericAlbums = setOf(
        "download", "downloads", "music", "audio", "audios", "sounds", "songs", "media", "recordings", "voice recorder",
        "whatsapp audio", "telegram audio", "snaptube", "bluetooth", "ringtones", "notifications", "unknown", "<unknown>", "arios",
    )

    /** True when MediaStore invented the album (no tag → folder name), so songs must not share art. */
    fun isGenericAlbum(key: ArtKey): Boolean {
        val a = key.album.trim().lowercase()
        return a.isEmpty() || a in genericAlbums || a == key.folderName.trim().lowercase() || a == MediaScanner.UNKNOWN
    }

    private fun fresh(f: File, days: Int) = f.exists() && System.currentTimeMillis() - f.lastModified() < days * 24L * 3600 * 1000

    suspend fun resolve(key: ArtKey): File? = locks.getOrPut(if (key.songId >= 0) key.songId else -key.albumId - 1) { Mutex() }.withLock {
        withContext(Dispatchers.IO) {
            val id = if (key.songId >= 0) key.songId else return@withContext legacyAlbum(key)
            val out = songFile(id)
            if (out.exists() && out.length() > 0) return@withContext out
            // 1. picture embedded in this exact file  2. MediaStore thumbnail of this file
            if (fromEmbedded(key, out)) return@withContext out
            // the system thumbnail may fall back to a shared folder image → only for real albums
            if (!isGenericAlbum(key) && fromThumbnail(key, out)) return@withContext out
            if (!Arios.prefs.autoFetchArtwork) return@withContext null
            // 3. online: the album cover for real albums, otherwise this song's own cover
            if (!isGenericAlbum(key)) {
                val album = cacheFile(key.albumId)
                if (album.exists()) return@withContext album
                if (!fresh(albumMiss(key.albumId), 5)) {
                    if (fromOnline(key, album, byAlbum = true)) return@withContext album
                    runCatching { albumMiss(key.albumId).writeText("") }
                }
            }
            if (fresh(songMiss(id), 5)) return@withContext null
            if (fromOnline(key, out, byAlbum = false)) return@withContext out
            runCatching { songMiss(id).writeText("") }
            null
        }
    }

    private fun legacyAlbum(key: ArtKey): File? {
        val out = cacheFile(key.albumId)
        if (out.exists()) return out
        return if (fromEmbedded(key, out) || fromThumbnail(key, out)) out else null
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

    private fun fromOnline(key: ArtKey, out: File, byAlbum: Boolean): Boolean = runCatching {
        val p = com.arios.player.lyrics.LyricsMatcher.parse(key.title, key.artist.takeUnless { it == MediaScanner.UNKNOWN } ?: "")
        val artist = p.artists.firstOrNull { !com.arios.player.lyrics.Lyrics.isRtl(it) } ?: p.artists.firstOrNull() ?: ""
        val title = p.titles.firstOrNull { !com.arios.player.lyrics.Lyrics.isRtl(it) } ?: p.titles.first()
        val (term, entity) = if (byAlbum) "$artist ${key.album}" to "album" else "$artist $title" to "song"
        if (term.isBlank()) return false
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", term.trim()).addQueryParameter("entity", entity)
            .addQueryParameter("limit", "5").build()
        val body = Arios.http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() } ?: return false
        val results = JSONObject(body).optJSONArray("results") ?: return false
        // only accept a result whose name really matches, never "the first thing found"
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val name = if (byAlbum) r.optString("collectionName") else r.optString("trackName")
            val wanted = if (byAlbum) key.album else title
            val sim = com.arios.player.lyrics.LyricsMatcher.similarity(com.arios.player.lyrics.LyricsMatcher.clean(name), wanted)
            val artSim = if (artist.isBlank()) 1.0 else com.arios.player.lyrics.LyricsMatcher.similarity(r.optString("artistName"), artist)
            if (sim < 0.7 || artSim < 0.5) continue
            val thumb = r.optString("artworkUrl100")
            if (thumb.isBlank()) continue
            val art = thumb.replace("100x100bb", "600x600bb")
            if (downloadTo(art, out)) return true
        }
        false
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

    /** v1 shared one cached cover per album id; drop those so songs get their own art. */
    fun migrate(context: Context) {
        val sp = context.getSharedPreferences("arios_prefs", Context.MODE_PRIVATE)
        if (sp.getInt("art_cache_v", 1) < 2) { clearAll(); sp.edit().putInt("art_cache_v", 2).apply() }
    }
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
