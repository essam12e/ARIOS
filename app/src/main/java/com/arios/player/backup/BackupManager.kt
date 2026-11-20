package com.arios.player.backup

import android.content.Context
import android.net.Uri
import com.arios.player.Arios
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Exports/imports playlists, favourites, settings and manually edited lyrics as one JSON file. */
object BackupManager {
    suspend fun export(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject()
            root.put("app", "ARIOS"); root.put("version", 1); root.put("created", System.currentTimeMillis())
            root.put("user", Arios.userStore.toJson())
            root.put("settings", JSONObject(Arios.prefs.exportMap()))
            root.put("history", Arios.history.toJson())
            val lyrics = JSONArray()
            Arios.lyrics.allCachedFiles().forEach { f ->
                val text = f.readText()
                if (text.startsWith("[re:Manual]")) lyrics.put(JSONObject().put("file", f.name).put("lrc", text))
            }
            root.put("lyrics", lyrics)
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(root.toString(2).toByteArray()) } ?: error("no stream")
            true
        }.getOrDefault(false)
    }

    suspend fun import(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("no stream")
            val root = JSONObject(text)
            require(root.optString("app") == "ARIOS")
            root.optJSONObject("user")?.let { Arios.userStore.replaceAll(it) }
            root.optJSONArray("history")?.let { Arios.history.fromJson(it) }
            root.optJSONObject("settings")?.let { s ->
                val map = HashMap<String, Any?>()
                s.keys().forEach { k -> map[k] = s.get(k) }
                Arios.prefs.importMap(map)
            }
            root.optJSONArray("lyrics")?.let { arr ->
                val dir = java.io.File(context.filesDir, "lyrics").apply { mkdirs() }
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val name = o.getString("file").substringAfterLast('/')
                    java.io.File(dir, name).writeText(o.getString("lrc"))
                }
            }
            true
        }.getOrDefault(false)
    }
}
