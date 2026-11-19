package com.arios.player.lyrics

import android.content.Context
import android.util.LruCache
import com.arios.player.Arios
import com.arios.player.data.MediaScanner
import com.arios.player.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest

sealed interface LyricsState {
    data object Idle : LyricsState
    data object Loading : LyricsState
    data class Ready(val lyrics: Lyrics) : LyricsState
    data object NotFound : LyricsState
}

class LyricsRepository(private val context: Context) {
    private val dir = File(context.filesDir, "lyrics").apply { mkdirs() }
    private val memory = LruCache<String, Lyrics>(40)

    /** Bumped whenever lyrics for some song are saved/removed so screens reload. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    private fun hash(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun fileFor(song: Song) = File(dir, hash(song.key) + ".lrc")
    private fun missFor(song: Song) = File(dir, hash(song.key) + ".none")

    fun cachedRaw(song: Song): String? = fileFor(song).takeIf { it.exists() }?.readText()

    fun isCached(song: Song) = fileFor(song).exists()

    /** Local only: memory, ARIOS cache and a sidecar .lrc next to the audio file. */
    suspend fun getLocal(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        memory.get(song.key)?.let { return@withContext it }
        val cached = fileFor(song)
        if (cached.exists()) {
            val raw = cached.readText()
            val src = Regex("""\[re:([^\]]+)]""").find(raw)?.groupValues?.get(1) ?: "ARIOS"
            LrcParser.parse(raw, src, song.durationMs)?.let { memory.put(song.key, it); return@withContext it }
        }
        // sidecar file (works on devices/folders where the app can read it)
        runCatching {
            val side = File(song.path.substringBeforeLast('.') + ".lrc")
            if (side.canRead()) LrcParser.parse(side.readText(), "LRC", song.durationMs)?.let {
                memory.put(song.key, it); return@withContext it
            }
        }
        null
    }

    /** Local first, then online sources (if enabled). Results are cached on disk for offline use. */
    suspend fun get(song: Song, allowNetwork: Boolean = Arios.prefs.autoFetchLyrics): Lyrics? {
        getLocal(song)?.let { return it }
        if (!allowNetwork) return null
        val miss = missFor(song)
        if (miss.exists() && System.currentTimeMillis() - miss.lastModified() < 3L * 24 * 3600 * 1000) return null
        val best = withTimeoutOrNull(25_000) { autoFind(song) }
        if (best != null) {
            save(song, best, manual = false)
            return best
        }
        withContext(Dispatchers.IO) { runCatching { miss.writeText("") } }
        return null
    }

    fun queryFor(song: Song): LyricsQuery = LyricsQuery(
        title = cleanTitle(song.title),
        artist = song.artist.takeUnless { it == MediaScanner.UNKNOWN } ?: "",
        album = song.album.takeUnless { it == MediaScanner.UNKNOWN },
        durationMs = song.durationMs,
    )

    private suspend fun autoFind(song: Song): Lyrics? {
        val q = queryFor(song)
        var plainFallback: Lyrics? = null
        for (p in ALL_PROVIDERS.filter { Arios.prefs.sourceEnabled(it.id) }) {
            val candidates = runCatching { p.search(q) }.getOrDefault(emptyList())
            val ranked = candidates.sortedByDescending { score(it, q) }.filter { score(it, q) > 0.35 }
            for (c in ranked.take(3)) {
                val l = runCatching { c.load() }.getOrNull() ?: continue
                if (l.lines.isEmpty()) continue
                if (l.synced) return l
                if (plainFallback == null) plainFallback = l
            }
        }
        return plainFallback
    }

    /** Manual search across all enabled sources (used by the "search lyrics" sheet). */
    suspend fun searchAll(q: LyricsQuery): List<LyricsCandidate> = coroutineScope {
        ALL_PROVIDERS.filter { Arios.prefs.sourceEnabled(it.id) }.map { p ->
            async { withTimeoutOrNull(15_000) { runCatching { p.search(q) }.getOrNull() } ?: emptyList() }
        }.awaitAll().flatten().sortedByDescending { score(it, q) }
    }

    fun score(c: LyricsCandidate, q: LyricsQuery): Double {
        var s = 0.0
        s += similarity(cleanTitle(c.title), q.title) * 0.55
        if (q.artist.isNotBlank()) s += similarity(c.artist, q.artist) * 0.3 else s += 0.15
        val d = c.durationSec
        if (d != null && q.durationMs > 0) {
            val diff = kotlin.math.abs(d - q.durationMs / 1000.0)
            s += when { diff <= 2 -> 0.2; diff <= 5 -> 0.1; diff <= 15 -> 0.0; else -> -0.3 }
        }
        if (c.syncedHint == true) s += 0.05
        return s
    }

    private fun similarity(a: String, b: String): Double {
        val x = normalise(a); val y = normalise(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        if (x.contains(y) || y.contains(x)) return 0.85
        val ta = x.split(' ').toSet(); val tb = y.split(' ').toSet()
        return ta.intersect(tb).size.toDouble() / ta.union(tb).size
    }

    private fun normalise(s: String) = s.lowercase()
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    suspend fun save(song: Song, lyrics: Lyrics, manual: Boolean) {
        val text = LrcWriter.write(lyrics, song.title, song.artist)
        saveRaw(song, text, if (manual) "Manual" else lyrics.source)
    }

    suspend fun saveRaw(song: Song, lrc: String, source: String = "Manual") = withContext(Dispatchers.IO) {
        val body = lrc.lines().filterNot { it.startsWith("[re:") }.joinToString("\n")
        val text = "[re:$source]\n$body"
        fileFor(song).writeText(text)
        missFor(song).delete()
        memory.remove(song.key)
        LrcParser.parse(text, source, song.durationMs)?.let { memory.put(song.key, it) }
        _revision.value++
    }

    suspend fun delete(song: Song) = withContext(Dispatchers.IO) {
        fileFor(song).delete(); missFor(song).delete(); memory.remove(song.key); _revision.value++
    }

    fun allCachedFiles(): List<File> = dir.listFiles { f -> f.extension == "lrc" }?.toList() ?: emptyList()
    fun clearCache() { dir.listFiles()?.forEach { it.delete() }; memory.evictAll(); _revision.value++ }

    companion object {
        private val noise = listOf(
            Regex("""\s*[(\[][^)\]]*(official|video|audio|lyric|lyrics|visuali[sz]er|remaster(ed)?|hd|hq|4k|explicit|clean|live|version|edit)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE),
            Regex("""\s*[(\[]\s*(feat|ft)\.?[^)\]]*[)\]]""", RegexOption.IGNORE_CASE),
            Regex("""\s+(feat|ft)\.?\s+.*$""", RegexOption.IGNORE_CASE),
            Regex("""\s*-\s*(remaster(ed)?|official).*$""", RegexOption.IGNORE_CASE),
            Regex("""^\d{1,3}[.\s-]+"""),
        )
        fun cleanTitle(t: String): String {
            var s = t
            noise.forEach { s = it.replace(s, "") }
            return s.replace('_', ' ').trim().ifEmpty { t }
        }
    }
}
