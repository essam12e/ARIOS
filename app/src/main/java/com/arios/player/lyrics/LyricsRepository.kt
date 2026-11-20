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

data class ScoredCandidate(val candidate: LyricsCandidate, val score: LyricsMatcher.Score) {
    val confident get() = LyricsMatcher.isConfident(score)
}

class LyricsRepository(private val context: Context) {
    private val dir = File(context.filesDir, "lyrics").apply { mkdirs() }
    private val memory = LruCache<String, Lyrics>(40)
    private val noEmbedded = HashSet<String>()

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    init {
        // v2: drop automatically fetched lyrics from v1 (its matching was too loose); keep manual edits
        val sp = context.getSharedPreferences("arios_prefs", Context.MODE_PRIVATE)
        if (sp.getInt("lyrics_cache_v", 1) < 2) {
            dir.listFiles()?.forEach { f ->
                if (f.extension == "none") f.delete()
                else if (f.extension == "lrc") {
                    val head = runCatching { f.bufferedReader().use { it.readLine() } }.getOrNull().orEmpty()
                    if (!head.startsWith("[re:Manual]") && !head.startsWith("[re:LRC]")) f.delete()
                }
            }
            sp.edit().putInt("lyrics_cache_v", 2).apply()
        }
    }

    private fun hash(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun fileFor(song: Song) = File(dir, hash(song.key) + ".lrc")
    private fun missFor(song: Song) = File(dir, hash(song.key) + ".miss")

    fun isCached(song: Song) = fileFor(song).exists()

    private fun readCache(song: Song): Pair<String, Lyrics>? {
        val f = fileFor(song)
        if (!f.exists()) return null
        val raw = f.readText()
        val src = Regex("""\[re:([^\]]+)]""").find(raw)?.groupValues?.get(1) ?: "ARIOS"
        return LrcParser.parse(raw, src, song.durationMs)?.let { src to it }
    }

    /** Local only: lyrics you saved/chose → lyrics embedded in the file → sidecar .lrc. */
    suspend fun getLocal(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        memory.get(song.key)?.let { return@withContext it }
        readCache(song)?.let { memory.put(song.key, it.second); return@withContext it.second }
        if (song.key !in noEmbedded) {
            val text = EmbeddedLyrics.read(context, song.uri)
            val l = text?.let { LrcParser.parse(it, EMBEDDED, song.durationMs) }
            if (l != null && l.lines.isNotEmpty()) { memory.put(song.key, l); return@withContext l }
            noEmbedded += song.key
        }
        runCatching {
            val side = File(song.path.substringBeforeLast('.') + ".lrc")
            if (side.canRead()) LrcParser.parse(side.readText(), "LRC", song.durationMs)?.let {
                memory.put(song.key, it); return@withContext it
            }
        }
        null
    }

    suspend fun get(song: Song, allowNetwork: Boolean = Arios.prefs.autoFetchLyrics): Lyrics? {
        getLocal(song)?.let { return it }
        if (!allowNetwork) return null
        val miss = missFor(song)
        if (miss.exists() && System.currentTimeMillis() - miss.lastModified() < 24L * 3600 * 1000) return null
        val best = withTimeoutOrNull(30_000) { autoFind(song) }
        if (best != null) { save(song, best, manual = false); return best }
        withContext(Dispatchers.IO) { runCatching { miss.writeText("") } }
        return null
    }

    fun queriesFor(song: Song): List<LyricsQuery> = LyricsMatcher.queries(
        song.title,
        song.artist.takeUnless { it == MediaScanner.UNKNOWN } ?: "",
        song.album.takeUnless { it == MediaScanner.UNKNOWN },
        song.durationMs,
    ).ifEmpty { listOf(LyricsQuery(song.title, "", null, song.durationMs)) }

    fun queryFor(song: Song): LyricsQuery = queriesFor(song).first()

    private suspend fun searchProvider(p: LyricsProvider, queries: List<LyricsQuery>): List<LyricsCandidate> = coroutineScope {
        queries.take(3).map { q -> async { withTimeoutOrNull(12_000) { runCatching { p.search(q) }.getOrNull() } ?: emptyList() } }
            .awaitAll().flatten()
            .distinctBy { "${it.sourceId}|${it.title.lowercase()}|${it.artist.lowercase()}|${it.durationSec}" }
    }

    private suspend fun autoFind(song: Song): Lyrics? {
        val queries = queriesFor(song)
        var plainFallback: Lyrics? = null
        for (p in ALL_PROVIDERS.filter { Arios.prefs.sourceEnabled(it.id) }) {
            val ranked = searchProvider(p, queries)
                .map { ScoredCandidate(it, LyricsMatcher.score(it, queries)) }
                .filter { it.confident }
                .sortedByDescending { it.score.total }
            for (c in ranked.take(3)) {
                val l = runCatching { c.candidate.load() }.getOrNull() ?: continue
                if (l.lines.size < 2) continue
                if (l.synced) return l
                if (plainFallback == null) plainFallback = l
            }
        }
        return plainFallback
    }

    /** Manual search across all enabled sources, best matches first. */
    suspend fun searchAll(queries: List<LyricsQuery>): List<ScoredCandidate> = coroutineScope {
        ALL_PROVIDERS.filter { Arios.prefs.sourceEnabled(it.id) }
            .map { p -> async { searchProvider(p, queries) } }
            .awaitAll().flatten()
            .map { ScoredCandidate(it, LyricsMatcher.score(it, queries)) }
            .sortedByDescending { it.score.total }
    }

    suspend fun save(song: Song, lyrics: Lyrics, manual: Boolean) {
        saveRaw(song, LrcWriter.write(lyrics, song.title, song.artist), if (manual) "Manual" else lyrics.source)
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
        fileFor(song).delete(); missFor(song).delete(); memory.remove(song.key); noEmbedded.remove(song.key); _revision.value++
    }

    fun allCachedFiles(): List<File> = dir.listFiles { f -> f.extension == "lrc" }?.toList() ?: emptyList()
    fun clearCache() { dir.listFiles()?.forEach { it.delete() }; memory.evictAll(); noEmbedded.clear(); _revision.value++ }

    companion object {
        const val EMBEDDED = "Embedded"
        fun cleanTitle(t: String): String = LyricsMatcher.clean(t).ifEmpty { t }
    }
}
