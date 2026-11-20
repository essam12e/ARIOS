package com.arios.player.lyrics

import android.content.Context
import com.arios.player.Arios
import com.arios.player.data.MediaScanner
import com.arios.player.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * "Lyrics from a link": the user copies/shares a song link (YouTube, YouTube Music, Spotify, Anghami,
 * SoundCloud, Deezer, Apple Music…). Only the song's *name* is read from the link (official oEmbed /
 * page title) — no audio is downloaded. Lyrics are found, saved, and attached automatically to the
 * matching song as soon as it is in the library; plain lyrics are then timed automatically.
 */
class LinkLyrics(context: Context) {
    data class Entry(
        val id: String,
        val url: String,
        val title: String,
        val artist: String,
        val durationSec: Int?,
        val lrc: String?,          // null = not found (yet)
        val source: String,
        val synced: Boolean,
        val created: Long,
        val attachedKey: String?,  // song key it was attached to
        val attachedTitle: String?,
    )

    enum class Status { WORKING, FOUND, NOT_FOUND, FAILED }
    data class Progress(val url: String, val status: Status, val title: String? = null)

    private val file = File(context.filesDir, "link_lyrics.json")
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress
    private val mutex = Mutex()

    /** Links we already offered from the clipboard (so the banner shows once per link). */
    private val seen = context.getSharedPreferences("link_lyrics", Context.MODE_PRIVATE)

    init { load() }

    private fun load() {
        runCatching {
            val a = JSONArray(file.readText())
            _entries.value = List(a.length()) { i ->
                val o = a.getJSONObject(i)
                Entry(o.getString("id"), o.getString("url"), o.getString("title"), o.optString("artist"),
                    o.optInt("dur", 0).takeIf { it > 0 }, o.optString("lrc").takeIf { it.isNotEmpty() },
                    o.optString("source"), o.optBoolean("synced"), o.optLong("created"),
                    o.optString("key").takeIf { it.isNotEmpty() }, o.optString("attTitle").takeIf { it.isNotEmpty() })
            }
        }
    }

    private fun save() {
        val a = JSONArray()
        _entries.value.forEach { e ->
            a.put(JSONObject().apply {
                put("id", e.id); put("url", e.url); put("title", e.title); put("artist", e.artist)
                put("dur", e.durationSec ?: 0); put("lrc", e.lrc ?: ""); put("source", e.source); put("synced", e.synced)
                put("created", e.created); put("key", e.attachedKey ?: ""); put("attTitle", e.attachedTitle ?: "")
            })
        }
        runCatching { val tmp = File(file.parentFile, file.name + ".tmp"); tmp.writeText(a.toString()); tmp.renameTo(file) }
    }

    internal fun setForTest(list: List<Entry>) { _entries.value = list }

    fun remove(id: String) { _entries.value = _entries.value.filterNot { it.id == id }; save() }

    // ------------------------------------------------------------------ links

    companion object {
        private val URL_RX = Regex("""https?://[^\s<>"']+""")
        private val MUSIC_HOSTS = listOf(
            "youtube.com", "youtu.be", "music.youtube.com", "open.spotify.com", "spotify.link", "anghami.com",
            "play.anghami.com", "soundcloud.com", "deezer.com", "deezer.page.link", "music.apple.com", "tidal.com",
            "shazam.com", "music.amazon",
        )

        /** First music link in [text], or null. */
        fun findLink(text: String?): String? {
            if (text.isNullOrBlank()) return null
            return URL_RX.findAll(text).map { it.value.trimEnd('.', ',', ')', '»') }
                .firstOrNull { u -> MUSIC_HOSTS.any { h -> runCatching { u.toHttpUrl().host }.getOrNull()?.endsWith(h) == true } }
        }

        private val TITLE_NOISE = Regex("""(?i)\s*[-|–]\s*(youtube( music)?|spotify|anghami|أنغامي|soundcloud|deezer|apple music|shazam)\s*$""")
    }

    fun alreadyOffered(url: String) = seen.getBoolean(url, false) || _entries.value.any { it.url == url }
    fun markOffered(url: String) { seen.edit().putBoolean(url, true).apply() }

    private fun get(url: String): String? = runCatching {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
            .header("Accept-Language", "ar,en;q=0.8").build()
        Arios.http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull()

    private fun meta(html: String, prop: String): String? =
        Regex("""<meta[^>]+(?:property|name)=["']$prop["'][^>]+content=["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: Regex("""<meta[^>]+content=["']([^"']*)["'][^>]+(?:property|name)=["']$prop["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)

    private fun unescape(s: String) = android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()

    /** Reads the song name from the link: (title, artist, durationSec). Never downloads media. */
    fun resolve(url: String): Triple<String, String, Int?>? {
        val host = runCatching { url.toHttpUrl().host }.getOrNull() ?: return null
        // YouTube / YouTube Music: official oEmbed endpoint
        if (host.endsWith("youtube.com") || host.endsWith("youtu.be")) {
            val clean = url.replace("music.youtube.com", "www.youtube.com")
            val o = get("https://www.youtube.com/oembed?format=json&url=" + java.net.URLEncoder.encode(clean, "UTF-8"))
                ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
            val title = o.optString("title")
            val author = o.optString("author_name").replace(Regex("""(?i)\s*-\s*topic$|vevo$|\s*official$"""), "").trim()
            return Triple(title, author, null).takeIf { title.isNotBlank() }
        }
        // SoundCloud / Spotify: oEmbed, then page tags
        if (host.endsWith("soundcloud.com")) {
            val o = get("https://soundcloud.com/oembed?format=json&url=" + java.net.URLEncoder.encode(url, "UTF-8"))
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (o != null) {
                val t = o.optString("title"); val a = o.optString("author_name")
                // "Song by Artist"
                val m = Regex("""^(.*) by (.*)$""").find(t)
                return if (m != null) Triple(m.groupValues[1], m.groupValues[2], null) else Triple(t, a, null)
            }
        }
        val html = get(url) ?: return null
        val ogTitle = meta(html, "og:title")?.let(::unescape)
        val desc = meta(html, "og:description")?.let(::unescape) ?: ""
        val musician = meta(html, "music:musician_description")?.let(::unescape)
            ?: meta(html, "twitter:audio:artist_name")?.let(::unescape)
        val dur = meta(html, "music:duration")?.toIntOrNull()
        val pageTitle = Regex("""<title>([^<]*)</title>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let(::unescape)
        var title = (ogTitle ?: pageTitle ?: return null).replace(TITLE_NOISE, "").trim()
        var artist = musician ?: ""
        if (host.contains("spotify") && artist.isBlank()) {
            // og:description: "Artist · Song · Song · 2021"
            artist = desc.split('·').firstOrNull()?.trim().orEmpty()
            if (artist.equals(title, true)) artist = ""
        }
        if (artist.isBlank()) {
            // "Song - Artist" / "Song by Artist" / "Artist - Song" (resolved later by the matcher)
            Regex("""^(.*?) (?:by|من|لـ|-) (.*)$""").find(title)?.let { m ->
                if (host.contains("anghami") || host.contains("apple") || host.contains("deezer")) { title = m.groupValues[1]; artist = m.groupValues[2] }
            }
        }
        artist = artist.replace(Regex("""(?i)\s*on (apple music|deezer|anghami).*$"""), "").trim()
        return Triple(title, artist, dur).takeIf { title.isNotBlank() }
    }

    // ------------------------------------------------------------------ fetch

    /** Resolves the link, finds lyrics, stores them and attaches them to a matching library song. */
    fun fetch(url: String, onDone: ((Entry?) -> Unit)? = null) {
        markOffered(url)
        Arios.scope.launch {
            _progress.value = Progress(url, Status.WORKING)
            val entry = runCatching { withTimeoutOrNull(60_000) { fetchNow(url) } }.getOrNull()
            _progress.value = when {
                entry == null -> Progress(url, Status.FAILED)
                entry.lrc == null -> Progress(url, Status.NOT_FOUND, entry.title)
                else -> Progress(url, Status.FOUND, entry.attachedTitle ?: entry.title)
            }
            onDone?.invoke(entry)
        }
    }

    fun clearProgress() { _progress.value = null }

    private suspend fun fetchNow(url: String): Entry? {
        val (rawTitle, rawArtist, dur) = withContext(Dispatchers.IO) { resolve(url) } ?: return null
        val queries = LyricsMatcher.queries(rawTitle, rawArtist, null, (dur ?: 0) * 1000L)
            .ifEmpty { listOf(LyricsQuery(rawTitle, rawArtist, null, 0)) }
        val parsed = LyricsMatcher.parse(rawTitle, rawArtist)
        val title = parsed.titles.firstOrNull() ?: rawTitle
        val artist = parsed.artists.firstOrNull() ?: rawArtist
        var best: Lyrics? = null
        var bestDur: Int? = dur
        val hits = Arios.lyrics.searchAll(queries).filter { it.confident }.take(8)
        for (c in hits) {
            val l = runCatching { c.candidate.load() }.getOrNull() ?: continue
            if (l.lines.size < 2) continue
            if (best == null || (l.synced && !best.synced)) { best = l; bestDur = bestDur ?: c.candidate.durationSec }
            if (l.synced) break
        }
        var e = Entry(
            UUID.randomUUID().toString(), url, title, artist, bestDur,
            best?.let { LrcWriter.write(it, title, artist) }, best?.source ?: "", best?.synced == true,
            System.currentTimeMillis(), null, null,
        )
        mutex.withLock {
            _entries.value = listOf(e) + _entries.value.filterNot { it.url == url }
            save()
        }
        // attach right away if the song is already in the library
        attachAll(Arios.library.raw)
        e = _entries.value.firstOrNull { it.id == e.id } ?: e
        return e
    }

    // ------------------------------------------------------------------ matching

    private fun matches(e: Entry, s: Song): Boolean {
        val songTitles = LyricsMatcher.parse(s.title, s.artist.takeUnless { it == MediaScanner.UNKNOWN } ?: "")
        val t = songTitles.titles.maxOfOrNull { LyricsMatcher.similarity(it, e.title) } ?: 0.0
        if (t < 0.8) return false
        if (e.durationSec != null && s.durationMs > 0 && kotlin.math.abs(e.durationSec - s.durationMs / 1000.0) > 10) return false
        if (e.artist.isBlank() || s.artist == MediaScanner.UNKNOWN) return t >= 0.9
        val a = songTitles.artists.ifEmpty { listOf(s.artist) }.maxOf { LyricsMatcher.similarity(it, e.artist) }
        return a >= 0.6 || (t >= 0.95 && e.durationSec != null)
    }

    /** Entry whose song name matches [song] (used by the lyrics lookup). */
    fun findFor(song: Song): Entry? = _entries.value.firstOrNull { it.lrc != null && (it.attachedKey == song.key || matches(it, song)) }

    /** Gives every waiting link its song: saves the lyrics and times them automatically if needed. */
    suspend fun attachAll(songs: List<Song>) = mutex.withLock {
        var changed = false
        val list = _entries.value.toMutableList()
        for ((i, e) in list.withIndex()) {
            if (e.lrc == null || e.attachedKey != null) continue
            val s = songs.firstOrNull { matches(e, it) } ?: continue
            val existing = Arios.lyrics.getLocal(s)
            if (existing == null || (!existing.synced && e.synced)) {
                Arios.lyrics.saveRaw(s, e.lrc, if (e.synced) e.source.ifBlank { "Link" } else "Link")
                Arios.userStore.setOffset(s.key, 0)
            }
            list[i] = e.copy(attachedKey = s.key, attachedTitle = s.title)
            changed = true
            if (!e.synced) Arios.lyrics.getLocal(s)?.let { l -> if (!l.synced) Arios.lyrics.requestSync(s, l, allowNetwork = true, force = true) }
        }
        if (changed) { _entries.value = list; save() }
    }
}
