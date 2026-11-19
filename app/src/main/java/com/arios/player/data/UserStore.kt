package com.arios.player.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Persists playlists, favourites and per-song lyric offsets as a small JSON file. */
class UserStore(context: Context) {
    private val file = File(context.filesDir, "user_store.json")

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists
    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites
    private val _offsets = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lyricOffsets: StateFlow<Map<String, Long>> = _offsets

    init { load() }

    @Synchronized
    private fun load() {
        if (!file.exists()) return
        runCatching { fromJson(JSONObject(file.readText())) }
    }

    fun fromJson(o: JSONObject) {
        val pls = ArrayList<Playlist>()
        o.optJSONArray("playlists")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val keys = p.getJSONArray("songs").let { a -> List(a.length()) { a.getString(it) } }
                pls += Playlist(p.getString("id"), p.getString("name"), keys, p.optLong("created"))
            }
        }
        _playlists.value = pls
        _favorites.value = o.optJSONArray("favorites")?.let { a -> List(a.length()) { a.getString(it) }.toSet() } ?: emptySet()
        val off = HashMap<String, Long>()
        o.optJSONObject("offsets")?.let { j -> j.keys().forEach { k -> off[k] = j.getLong(k) } }
        _offsets.value = off
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("playlists", JSONArray().apply {
            _playlists.value.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id); put("name", p.name); put("created", p.created)
                    put("songs", JSONArray(p.songKeys))
                })
            }
        })
        put("favorites", JSONArray(_favorites.value.toList()))
        put("offsets", JSONObject().apply { _offsets.value.forEach { (k, v) -> put(k, v) } })
    }

    @Synchronized
    private fun save() {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(toJson().toString())
            tmp.renameTo(file)
        }
    }

    fun replaceAll(o: JSONObject) { fromJson(o); save() }

    // ---- favourites
    fun isFavorite(key: String) = key in _favorites.value
    fun toggleFavorite(key: String) {
        _favorites.value = if (key in _favorites.value) _favorites.value - key else _favorites.value + key
        save()
    }

    // ---- playlists
    fun createPlaylist(name: String, keys: List<String> = emptyList()): Playlist {
        val p = Playlist(UUID.randomUUID().toString(), name, keys.distinct(), System.currentTimeMillis())
        _playlists.value = _playlists.value + p
        save(); return p
    }
    fun renamePlaylist(id: String, name: String) = update(id) { it.copy(name = name) }
    fun deletePlaylist(id: String) { _playlists.value = _playlists.value.filterNot { it.id == id }; save() }
    fun addToPlaylist(id: String, keys: List<String>) = update(id) { p -> p.copy(songKeys = (p.songKeys + keys).distinct()) }
    fun removeFromPlaylist(id: String, key: String) = update(id) { p -> p.copy(songKeys = p.songKeys - key) }
    fun moveInPlaylist(id: String, from: Int, to: Int) = update(id) { p ->
        val l = p.songKeys.toMutableList()
        if (from in l.indices && to in l.indices) l.add(to, l.removeAt(from))
        p.copy(songKeys = l)
    }
    private fun update(id: String, f: (Playlist) -> Playlist) {
        _playlists.value = _playlists.value.map { if (it.id == id) f(it) else it }
        save()
    }

    // ---- lyric offsets (ms, positive = lyrics later)
    fun offset(key: String): Long = _offsets.value[key] ?: 0L
    fun setOffset(key: String, ms: Long) {
        _offsets.value = if (ms == 0L) _offsets.value - key else _offsets.value + (key to ms)
        save()
    }
}
