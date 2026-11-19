package com.arios.player.lyrics

import android.util.Base64
import com.arios.player.Arios
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** A search hit from one lyrics source; [load] downloads the actual lyrics. */
class LyricsCandidate(
    val sourceId: String,
    val sourceName: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationSec: Int?,
    val syncedHint: Boolean?,
    val load: suspend () -> Lyrics?,
)

data class LyricsQuery(val title: String, val artist: String, val album: String?, val durationMs: Long)

interface LyricsProvider {
    val id: String
    val displayName: String
    suspend fun search(q: LyricsQuery): List<LyricsCandidate>
}

private suspend fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String? = withContext(Dispatchers.IO) {
    runCatching {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        Arios.http.newCall(b.build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull()
}

/** https://lrclib.net – open, free, synced lyrics database. */
object LrcLibProvider : LyricsProvider {
    override val id = "lrclib"
    override val displayName = "LRCLIB"

    private fun toCandidate(o: JSONObject, q: LyricsQuery): LyricsCandidate {
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        val dur = o.optDouble("duration", 0.0).toInt().takeIf { it > 0 }
        return LyricsCandidate(id, displayName, o.optString("trackName"), o.optString("artistName"),
            o.optString("albumName").ifBlank { null }, dur, synced != null) {
            (synced ?: plain)?.let { LrcParser.parse(it, displayName, q.durationMs) }
        }
    }

    override suspend fun search(q: LyricsQuery): List<LyricsCandidate> {
        val out = ArrayList<LyricsCandidate>()
        val get = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", q.title)
            .addQueryParameter("artist_name", q.artist)
            .apply { if (!q.album.isNullOrBlank()) addQueryParameter("album_name", q.album) }
            .addQueryParameter("duration", (q.durationMs / 1000).toString())
            .build().toString()
        httpGet(get)?.let { runCatching { out += toCandidate(JSONObject(it), q) } }
        val search = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", q.title)
            .apply { if (q.artist.isNotBlank()) addQueryParameter("artist_name", q.artist) }
            .build().toString()
        httpGet(search)?.let { body ->
            runCatching {
                val arr = JSONArray(body)
                for (i in 0 until minOf(arr.length(), 12)) out += toCandidate(arr.getJSONObject(i), q)
            }
        }
        if (out.isEmpty()) {
            val free = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", "${q.artist} ${q.title}".trim()).build().toString()
            httpGet(free)?.let { body ->
                runCatching {
                    val arr = JSONArray(body)
                    for (i in 0 until minOf(arr.length(), 12)) out += toCandidate(arr.getJSONObject(i), q)
                }
            }
        }
        return out
    }
}

/** NetEase Cloud Music public web API (synced lyrics, translations and word-level YRC). */
object NetEaseProvider : LyricsProvider {
    override val id = "netease"
    override val displayName = "NetEase"
    private val headers = mapOf(
        "Referer" to "https://music.163.com/",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
    )

    override suspend fun search(q: LyricsQuery): List<LyricsCandidate> = withContext(Dispatchers.IO) {
        val body = runCatching {
            val form = FormBody.Builder().add("s", "${q.title} ${q.artist}".trim()).add("type", "1")
                .add("limit", "10").add("offset", "0").build()
            val req = Request.Builder().url("https://music.163.com/api/search/get/web").post(form)
            headers.forEach { (k, v) -> req.header(k, v) }
            Arios.http.newCall(req.build()).execute().use { it.body?.string() }
        }.getOrNull() ?: return@withContext emptyList()
        val songs = runCatching { JSONObject(body).getJSONObject("result").getJSONArray("songs") }.getOrNull()
            ?: return@withContext emptyList()
        List(minOf(songs.length(), 10)) { i ->
            val s = songs.getJSONObject(i)
            val songId = s.getLong("id")
            val artists = s.optJSONArray("artists")?.let { a -> List(a.length()) { a.getJSONObject(it).optString("name") } }?.joinToString(", ") ?: ""
            val dur = (s.optLong("duration") / 1000).toInt().takeIf { it > 0 }
            LyricsCandidate(id, displayName, s.optString("name"), artists, s.optJSONObject("album")?.optString("name"), dur, null) {
                loadLyrics(songId, q.durationMs)
            }
        }
    }

    private suspend fun loadLyrics(songId: Long, durationMs: Long): Lyrics? {
        val txt = httpGet("https://music.163.com/api/song/lyric/v1?id=$songId&lv=-1&kv=-1&tv=-1&yv=-1&rv=-1&cp=false", headers)
            ?: httpGet("https://music.163.com/api/song/lyric?id=$songId&lv=-1&kv=-1&tv=-1", headers)
            ?: return null
        val o = runCatching { JSONObject(txt) }.getOrNull() ?: return null
        val lrc = o.optJSONObject("lrc")?.optString("lyric").orEmpty()
        val yrc = o.optJSONObject("yrc")?.optString("lyric").orEmpty()
        val tl = o.optJSONObject("tlyric")?.optString("lyric").orEmpty()
        if (yrc.isNotBlank()) LrcParser.parse(yrc, displayName, durationMs, tl)?.let { if (it.lines.size > 2) return it }
        if (lrc.isBlank()) return null
        return LrcParser.parse(lrc, displayName, durationMs, tl)
    }
}

/** QQ Music public web API. */
object QQMusicProvider : LyricsProvider {
    override val id = "qq"
    override val displayName = "QQ Music"
    private val headers = mapOf("Referer" to "https://y.qq.com/", "Origin" to "https://y.qq.com")

    override suspend fun search(q: LyricsQuery): List<LyricsCandidate> {
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp".toHttpUrl().newBuilder()
            .addQueryParameter("w", "${q.title} ${q.artist}".trim())
            .addQueryParameter("format", "json").addQueryParameter("p", "1").addQueryParameter("n", "10")
            .addQueryParameter("cr", "1").addQueryParameter("new_json", "1")
            .build().toString()
        val body = httpGet(url, headers) ?: return emptyList()
        val list = runCatching {
            JSONObject(body).getJSONObject("data").getJSONObject("song").getJSONArray("list")
        }.getOrNull() ?: return emptyList()
        return List(minOf(list.length(), 10)) { i ->
            val s = list.getJSONObject(i)
            val mid = s.optString("mid").ifEmpty { s.optString("songmid") }
            val singers = s.optJSONArray("singer")?.let { a -> List(a.length()) { a.getJSONObject(it).optString("name") } }?.joinToString(", ") ?: ""
            val dur = s.optInt("interval").takeIf { it > 0 }
            LyricsCandidate(id, displayName, s.optString("name").ifEmpty { s.optString("songname") }, singers,
                s.optJSONObject("album")?.optString("name"), dur, null) { load(mid, q.durationMs) }
        }
    }

    private suspend fun load(mid: String, durationMs: Long): Lyrics? {
        val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$mid&format=json&nobase64=0&g_tk=5381"
        val body = httpGet(url, headers) ?: return null
        val json = body.substringAfter('(', body).substringBeforeLast(')', body)
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        fun decode(field: String): String {
            val v = o.optString(field)
            if (v.isBlank()) return ""
            return runCatching { String(Base64.decode(v, Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault(v)
        }
        val lrc = decode("lyric").let { android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString() }
        val trans = decode("trans")
        if (lrc.isBlank()) return null
        return LrcParser.parse(lrc, displayName, durationMs, trans)
    }
}

/** lyrics.ovh – plain (unsynced) lyrics, used as last fallback. */
object LyricsOvhProvider : LyricsProvider {
    override val id = "ovh"
    override val displayName = "Lyrics.ovh"

    override suspend fun search(q: LyricsQuery): List<LyricsCandidate> {
        if (q.artist.isBlank()) return emptyList()
        val url = "https://api.lyrics.ovh/v1/".toHttpUrl().newBuilder()
            .addPathSegment(q.artist).addPathSegment(q.title).build().toString()
        val body = httpGet(url) ?: return emptyList()
        val text = runCatching { JSONObject(body).optString("lyrics") }.getOrNull()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val lyrics = LrcParser.parse(text.replace(Regex("\n{3,}"), "\n\n"), displayName, q.durationMs)
        return listOf(LyricsCandidate(id, displayName, q.title, q.artist, q.album, null, false) { lyrics })
    }
}

val ALL_PROVIDERS: List<LyricsProvider> = listOf(LrcLibProvider, NetEaseProvider, QQMusicProvider, LyricsOvhProvider)
