package com.arios.player.download

import com.arios.player.Arios
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject

/**
 * Internet Archive (archive.org) – a legal library of public-domain, Creative Commons and
 * freely-distributable recordings (live concerts, netlabels, old 78s, audiobooks…).
 */
object ArchiveSource {
    data class Item(
        val identifier: String,
        val title: String,
        val creator: String,
        val downloads: Long,
        val license: String?,
    ) {
        val artUrl get() = "https://archive.org/services/img/$identifier"
        val pageUrl get() = "https://archive.org/details/$identifier"
    }

    data class FileOption(val name: String, val format: String, val size: Long, val url: String, val ext: String) {
        val quality: String get() = when {
            format.contains("flac", true) -> "FLAC"
            format.contains("vbr", true) -> "MP3 VBR"
            format.contains("320", true) -> "MP3 320"
            format.contains("256", true) -> "MP3 256"
            format.contains("128", true) -> "MP3 128"
            format.contains("64", true) -> "MP3 64"
            format.contains("ogg", true) -> "OGG"
            format.contains("wav", true) || format.contains("wave", true) -> "WAV"
            format.contains("mp4", true) || format.contains("m4a", true) || format.contains("aac", true) -> "M4A"
            format.contains("mp3", true) -> "MP3"
            else -> format
        }
    }

    data class Track(
        val title: String,
        val artist: String,
        val album: String,
        val trackNo: Int,
        val lengthSec: Int,
        val options: List<FileOption>,
    )

    data class Details(val item: Item, val tracks: List<Track>)

    private val audioFormats = listOf("mp3", "flac", "ogg vorbis", "wave", "aiff", "m4a", "aac", "opus", "apple lossless")

    private fun get(url: String): String? = runCatching {
        Arios.http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()

    suspend fun search(query: String, page: Int = 1): List<Item> = withContext(Dispatchers.IO) {
        val q = query.trim().replace("\"", " ")
        if (q.isEmpty()) return@withContext emptyList()
        val url = "https://archive.org/advancedsearch.php".toHttpUrl().newBuilder()
            .addQueryParameter("q", "($q) AND mediatype:(audio) AND -access-restricted-item:(true)")
            .addQueryParameter("fl[]", "identifier").addQueryParameter("fl[]", "title")
            .addQueryParameter("fl[]", "creator").addQueryParameter("fl[]", "downloads")
            .addQueryParameter("fl[]", "licenseurl")
            .addQueryParameter("sort[]", "downloads desc")
            .addQueryParameter("rows", "40").addQueryParameter("page", page.toString())
            .addQueryParameter("output", "json").build().toString()
        val body = get(url) ?: return@withContext emptyList()
        val docs = runCatching { JSONObject(body).getJSONObject("response").getJSONArray("docs") }.getOrNull() ?: return@withContext emptyList()
        List(docs.length()) { i ->
            val d = docs.getJSONObject(i)
            Item(
                identifier = d.getString("identifier"),
                title = d.opt("title").asText().ifBlank { d.getString("identifier") },
                creator = d.opt("creator").asText(),
                downloads = d.optLong("downloads"),
                license = d.optString("licenseurl").ifBlank { null },
            )
        }
    }

    private fun Any?.asText(): String = when (this) {
        null, JSONObject.NULL -> ""
        is org.json.JSONArray -> (0 until length()).joinToString(", ") { optString(it) }
        else -> toString()
    }

    private fun parseLength(s: String): Int {
        if (s.isBlank()) return 0
        if (s.contains(':')) {
            val p = s.split(':').mapNotNull { it.toDoubleOrNull() }
            return p.fold(0.0) { acc, v -> acc * 60 + v }.toInt()
        }
        return s.toDoubleOrNull()?.toInt() ?: 0
    }

    suspend fun details(item: Item): Details? = withContext(Dispatchers.IO) {
        val body = get("https://archive.org/metadata/${item.identifier}") ?: return@withContext null
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
        val meta = o.optJSONObject("metadata")
        val albumTitle = meta?.opt("title").asText().ifBlank { item.title }
        val creator = meta?.opt("creator").asText().ifBlank { item.creator }
        val license = meta?.optString("licenseurl")?.ifBlank { null } ?: item.license
        val files = o.optJSONArray("files") ?: return@withContext null
        data class F(val name: String, val format: String, val source: String, val original: String, val title: String,
                     val artist: String, val track: Int, val length: Int, val size: Long)
        val audio = ArrayList<F>()
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val format = f.optString("format")
            if (audioFormats.none { format.lowercase().contains(it) }) continue
            if (format.contains("spectrogram", true) || format.contains("png", true)) continue
            audio += F(
                name = f.getString("name"), format = format, source = f.optString("source"),
                original = f.optString("original"), title = f.optString("title"),
                artist = f.optString("creator").ifBlank { f.optString("artist") },
                track = f.optString("track").substringBefore('/').toIntOrNull() ?: 0,
                length = parseLength(f.optString("length")), size = f.optLong("size"),
            )
        }
        val groups = audio.groupBy { if (it.source == "derivative" && it.original.isNotBlank()) it.original else it.name }
        val tracks = groups.map { (key, list) ->
            val base = list.firstOrNull { it.name == key } ?: list.first()
            val titled = list.firstOrNull { it.title.isNotBlank() }
            Track(
                title = titled?.title ?: key.substringAfterLast('/').substringBeforeLast('.'),
                artist = list.firstOrNull { it.artist.isNotBlank() }?.artist ?: creator,
                album = albumTitle,
                trackNo = list.maxOf { it.track },
                lengthSec = list.maxOf { it.length },
                options = list.map {
                    val url = "https://archive.org/download/".toHttpUrl().newBuilder()
                        .addPathSegment(item.identifier).addPathSegments(it.name).build().toString()
                    FileOption(it.name, it.format, it.size, url, it.name.substringAfterLast('.', "mp3").lowercase())
                }.filter { it.ext in setOf("mp3", "flac", "ogg", "m4a", "opus", "wav", "aac") }
                    .sortedByDescending { qualityRank(it) },
            ).takeIf { base.name.isNotBlank() }
        }.filterNotNull().filter { it.options.isNotEmpty() }
            .sortedWith(compareBy<Track> { if (it.trackNo == 0) Int.MAX_VALUE else it.trackNo }.thenBy { it.title })
        Details(item.copy(title = albumTitle, creator = creator, license = license), tracks)
    }

    private fun qualityRank(f: FileOption): Int = when (f.quality) {
        "FLAC" -> 9; "WAV" -> 8; "MP3 320" -> 7; "MP3 VBR" -> 6; "MP3 256" -> 5; "M4A" -> 5; "OGG" -> 4; "MP3" -> 4
        "MP3 128" -> 3; "MP3 64" -> 1; else -> 2
    }
}
