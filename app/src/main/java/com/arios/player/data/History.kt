package com.arios.player.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PlayEvent(val key: String, val time: Long, val ms: Long, val artist: String, val title: String)

data class SongStat(val plays: Int, val lastPlayed: Long, val totalMs: Long)

/** Listening history: one event per real listen (≥30 s or half the song), kept on device only. */
class History(context: Context) {
    private val file = File(context.filesDir, "history.json")
    private val _events = MutableStateFlow<List<PlayEvent>>(emptyList())
    val events: StateFlow<List<PlayEvent>> = _events

    init {
        runCatching {
            val a = JSONArray(file.readText())
            _events.value = List(a.length()) { i ->
                val o = a.getJSONObject(i)
                PlayEvent(o.getString("k"), o.getLong("t"), o.getLong("m"), o.optString("a"), o.optString("n"))
            }
        }
    }

    @Synchronized
    fun add(e: PlayEvent) {
        _events.value = (_events.value + e).takeLast(30_000)
        persist()
    }

    private fun persist() {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JSONArray().apply {
                _events.value.forEach { put(JSONObject().put("k", it.key).put("t", it.time).put("m", it.ms).put("a", it.artist).put("n", it.title)) }
            }.toString())
            tmp.renameTo(file)
        }
    }

    @Synchronized
    fun replaceLast(e: PlayEvent) {
        val l = _events.value.toMutableList()
        val i = l.indexOfLast { it.key == e.key && it.time == e.time }
        if (i >= 0) { l[i] = e; _events.value = l; persist() }
    }

    fun stats(): Map<String, SongStat> {
        val m = HashMap<String, SongStat>()
        _events.value.forEach { e ->
            val s = m[e.key]
            m[e.key] = if (s == null) SongStat(1, e.time, e.ms) else SongStat(s.plays + 1, maxOf(s.lastPlayed, e.time), s.totalMs + e.ms)
        }
        return m
    }

    fun clear() { _events.value = emptyList(); file.delete() }

    fun toJson(): JSONArray = JSONArray().apply {
        _events.value.forEach { put(JSONObject().put("k", it.key).put("t", it.time).put("m", it.ms).put("a", it.artist).put("n", it.title)) }
    }
    fun fromJson(a: JSONArray) {
        _events.value = List(a.length()) { i ->
            val o = a.getJSONObject(i)
            PlayEvent(o.getString("k"), o.getLong("t"), o.getLong("m"), o.optString("a"), o.optString("n"))
        }
        persist()
    }
}

/** Saved positions for long audio (podcasts, audiobooks, lectures). */
class Positions(context: Context) {
    private val sp = context.getSharedPreferences("arios_positions", Context.MODE_PRIVATE)
    fun get(key: String): Long = sp.getLong(key, 0)
    fun set(key: String, ms: Long) { sp.edit().putLong(key, ms).apply() }
    fun clear(key: String) { sp.edit().remove(key).apply() }
}
