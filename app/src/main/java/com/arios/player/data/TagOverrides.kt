package com.arios.player.data

import android.content.Context
import org.json.JSONObject
import java.io.File

data class TagEdit(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val year: Int? = null,
    val track: Int? = null,
    val genre: String? = null,
)

/** Tag edits kept by ARIOS (applied on top of MediaStore, also for formats we cannot write). */
class TagOverrides(context: Context) {
    private val file = File(context.filesDir, "tag_overrides.json")
    private val map = HashMap<Long, TagEdit>()

    init {
        runCatching {
            val o = JSONObject(file.readText())
            o.keys().forEach { k ->
                val e = o.getJSONObject(k)
                map[k.toLong()] = TagEdit(
                    e.optString("title").ifBlank { null }, e.optString("artist").ifBlank { null },
                    e.optString("album").ifBlank { null }, e.optInt("year", 0).takeIf { it > 0 },
                    e.optInt("track", 0).takeIf { it > 0 }, e.optString("genre").ifBlank { null },
                )
            }
        }
    }

    fun get(id: Long): TagEdit? = synchronized(map) { map[id] }

    fun set(id: Long, e: TagEdit) {
        synchronized(map) { map[id] = e }
        runCatching {
            file.writeText(JSONObject().apply {
                synchronized(map) {
                    map.forEach { (k, v) ->
                        put(k.toString(), JSONObject().put("title", v.title ?: "").put("artist", v.artist ?: "")
                            .put("album", v.album ?: "").put("year", v.year ?: 0).put("track", v.track ?: 0).put("genre", v.genre ?: ""))
                    }
                }
            }.toString())
        }
    }

    fun apply(s: Song): Song {
        val e = get(s.id) ?: return s
        return s.copy(
            title = e.title ?: s.title, artist = e.artist ?: s.artist, album = e.album ?: s.album,
            year = e.year ?: s.year, track = e.track ?: s.track,
        )
    }
}
