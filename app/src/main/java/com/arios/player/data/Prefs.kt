package com.arios.player.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Small typed wrapper around SharedPreferences with observable settings. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("arios_prefs", Context.MODE_PRIVATE)

    private val _version = MutableStateFlow(0)
    /** Bumps whenever a setting changes so Compose can recompose. */
    val version: StateFlow<Int> = _version

    private fun bump() { _version.value++ }

    var lyricsFontSize: Int
        get() = sp.getInt("lyrics_font", 26)
        set(v) { sp.edit().putInt("lyrics_font", v).apply(); bump() }
    var showTranslation: Boolean
        get() = sp.getBoolean("show_translation", true)
        set(v) { sp.edit().putBoolean("show_translation", v).apply(); bump() }
    var autoFetchLyrics: Boolean
        get() = sp.getBoolean("auto_lyrics", true)
        set(v) { sp.edit().putBoolean("auto_lyrics", v).apply(); bump() }
    var autoFetchArtwork: Boolean
        get() = sp.getBoolean("auto_art", true)
        set(v) { sp.edit().putBoolean("auto_art", v).apply(); bump() }
    var autoSyncLyrics: Boolean
        get() = sp.getBoolean("auto_sync", true)
        set(v) { sp.edit().putBoolean("auto_sync", v).apply(); bump() }
    var lyricsCenter: Boolean
        get() = sp.getBoolean("lyrics_center", false)
        set(v) { sp.edit().putBoolean("lyrics_center", v).apply(); bump() }
    var wordByWord: Boolean
        get() = sp.getBoolean("word_by_word", true)
        set(v) { sp.edit().putBoolean("word_by_word", v).apply(); bump() }
    var minDurationSec: Int
        get() = sp.getInt("min_duration", 10)
        set(v) { sp.edit().putInt("min_duration", v).apply(); bump() }
    var playbackSpeed: Float
        get() = sp.getFloat("speed", 1f)
        set(v) { sp.edit().putFloat("speed", v).apply(); bump() }
    var libraryTab: Int
        get() = sp.getInt("library_tab", 3)
        set(v) { sp.edit().putInt("library_tab", v).apply() }
    var songSort: Int
        get() = sp.getInt("song_sort", 0)
        set(v) { sp.edit().putInt("song_sort", v).apply(); bump() }
    var albumSort: Int
        get() = sp.getInt("album_sort", 0)
        set(v) { sp.edit().putInt("album_sort", v).apply(); bump() }
    var sortDescending: Boolean
        get() = sp.getBoolean("sort_desc", false)
        set(v) { sp.edit().putBoolean("sort_desc", v).apply(); bump() }
    var albumGrid: Boolean
        get() = sp.getBoolean("album_grid", true)
        set(v) { sp.edit().putBoolean("album_grid", v).apply(); bump() }

    // ---- audio
    var crossfadeSec: Int
        get() = sp.getInt("crossfade", 0)
        set(v) { sp.edit().putInt("crossfade", v).apply(); bump() }
    var normalize: Boolean
        get() = sp.getBoolean("normalize", false)
        set(v) { sp.edit().putBoolean("normalize", v).apply(); bump() }
    var resumeLong: Boolean
        get() = sp.getBoolean("resume_long", true)
        set(v) { sp.edit().putBoolean("resume_long", v).apply(); bump() }
    var eqJson: String
        get() = sp.getString("eq", "") ?: ""
        set(v) { sp.edit().putString("eq", v).apply(); bump() }
    var sleepFade: Boolean
        get() = sp.getBoolean("sleep_fade", true)
        set(v) { sp.edit().putBoolean("sleep_fade", v).apply(); bump() }

    // ---- library
    var excludedFolders: Set<String>
        get() = sp.getStringSet("excluded", emptySet())?.toSet() ?: emptySet()
        set(v) { sp.edit().putStringSet("excluded", v).apply(); bump() }

    // ---- look
    var accent: Int
        get() = sp.getInt("accent", 6)
        set(v) { sp.edit().putInt("accent", v).apply(); bump() }
    var amoled: Boolean
        get() = sp.getBoolean("amoled", false)
        set(v) { sp.edit().putBoolean("amoled", v).apply(); bump() }
    var dynamicColor: Boolean
        get() = sp.getBoolean("dynamic_color", false)
        set(v) { sp.edit().putBoolean("dynamic_color", v).apply(); bump() }
    var visualizer: Boolean
        get() = sp.getBoolean("visualizer", true)
        set(v) { sp.edit().putBoolean("visualizer", v).apply(); bump() }

    fun sourceEnabled(id: String): Boolean = sp.getBoolean("src_$id", true)
    fun setSourceEnabled(id: String, v: Boolean) { sp.edit().putBoolean("src_$id", v).apply(); bump() }

    // ---- persisted playback queue (for resume + widget + Android Auto resumption)
    var savedQueue: List<Long>
        get() = sp.getString("queue", "")!!.split(',').mapNotNull { it.toLongOrNull() }
        set(v) { sp.edit().putString("queue", v.joinToString(",")).apply() }
    var savedIndex: Int
        get() = sp.getInt("queue_index", 0)
        set(v) { sp.edit().putInt("queue_index", v).apply() }
    var savedPosition: Long
        get() = sp.getLong("queue_pos", 0)
        set(v) { sp.edit().putLong("queue_pos", v).apply() }
    var shuffle: Boolean
        get() = sp.getBoolean("shuffle", false)
        set(v) { sp.edit().putBoolean("shuffle", v).apply() }
    var repeatMode: Int
        get() = sp.getInt("repeat", 0)
        set(v) { sp.edit().putInt("repeat", v).apply() }

    fun exportMap(): Map<String, Any?> = sp.all.filterKeys { !it.startsWith("queue") }
    fun importMap(map: Map<String, Any?>) {
        val e = sp.edit()
        map.forEach { (k, v) ->
            when (v) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is Double -> e.putFloat(k, v.toFloat())
                is String -> e.putString(k, v)
            }
        }
        e.apply(); bump()
    }
}
