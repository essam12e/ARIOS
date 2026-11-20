package com.arios.player.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.arios.player.Arios
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

enum class DlState { QUEUED, RUNNING, PAUSED, DONE, FAILED }

data class DownloadTask(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val url: String,
    val ext: String,
    val quality: String,
    val artUrl: String?,
    val source: String,
    val state: DlState = DlState.QUEUED,
    val downloaded: Long = 0,
    val total: Long = 0,
    val error: String? = null,
    val resultUri: String? = null,
    val created: Long = System.currentTimeMillis(),
) {
    val progress: Float get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/**
 * Resumable HTTP downloader (Range requests). Partial data is kept in app storage so a paused or
 * interrupted download continues where it stopped; finished files go to Music/ARIOS.
 */
class Downloader(private val context: Context) {
    private val store = File(context.filesDir, "downloads.json")
    private val partDir = File(context.filesDir, "partial").apply { mkdirs() }
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks
    private val jobs = HashMap<String, Job>()
    private val maxParallel = 2

    init {
        load()
        // anything that was running when the app died is resumed as queued
        _tasks.value = _tasks.value.map { if (it.state == DlState.RUNNING) it.copy(state = DlState.QUEUED) else it }
    }

    private fun load() = runCatching {
        if (!store.exists()) return@runCatching
        val arr = JSONArray(store.readText())
        _tasks.value = List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            DownloadTask(
                id = o.getString("id"), title = o.getString("title"), artist = o.optString("artist"),
                album = o.optString("album"), url = o.getString("url"), ext = o.optString("ext", "mp3"),
                quality = o.optString("quality"), artUrl = o.optString("art").ifBlank { null },
                source = o.optString("source"), state = DlState.valueOf(o.optString("state", "QUEUED")),
                downloaded = o.optLong("downloaded"), total = o.optLong("total"),
                error = o.optString("error").ifBlank { null }, resultUri = o.optString("result").ifBlank { null },
                created = o.optLong("created"),
            )
        }
    }

    @Synchronized
    private fun persist() = runCatching {
        val arr = JSONArray()
        _tasks.value.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id); put("title", t.title); put("artist", t.artist); put("album", t.album)
                put("url", t.url); put("ext", t.ext); put("quality", t.quality); put("art", t.artUrl ?: "")
                put("source", t.source); put("state", t.state.name); put("downloaded", t.downloaded)
                put("total", t.total); put("error", t.error ?: ""); put("result", t.resultUri ?: "")
                put("created", t.created)
            })
        }
        store.writeText(arr.toString())
    }

    private fun update(id: String, persist: Boolean = true, f: (DownloadTask) -> DownloadTask) {
        synchronized(this) { _tasks.value = _tasks.value.map { if (it.id == id) f(it) else it } }
        if (persist) persist()
    }

    fun enqueue(title: String, artist: String, album: String, url: String, ext: String, quality: String, artUrl: String?, source: String) {
        if (_tasks.value.any { it.url == url && it.state != DlState.FAILED }) return
        val t = DownloadTask(UUID.randomUUID().toString(), title, artist, album, url, ext, quality, artUrl, source)
        synchronized(this) { _tasks.value = listOf(t) + _tasks.value }
        persist()
        schedule()
    }

    fun pause(id: String) {
        jobs.remove(id)?.cancel()
        update(id) { if (it.state == DlState.RUNNING || it.state == DlState.QUEUED) it.copy(state = DlState.PAUSED) else it }
        schedule()
    }

    fun resume(id: String) {
        update(id) { it.copy(state = DlState.QUEUED, error = null) }
        schedule()
    }

    fun pauseAll() = _tasks.value.filter { it.state == DlState.RUNNING || it.state == DlState.QUEUED }.forEach { pause(it.id) }
    fun resumeAll() = _tasks.value.filter { it.state == DlState.PAUSED || it.state == DlState.FAILED }.forEach { resume(it.id) }

    /** Removes the task; [deleteFile] also deletes the downloaded song from the device. */
    fun remove(id: String, deleteFile: Boolean) {
        jobs.remove(id)?.cancel()
        val t = _tasks.value.firstOrNull { it.id == id } ?: return
        File(partDir, "$id.part").delete()
        if (deleteFile && t.resultUri != null) {
            runCatching { context.contentResolver.delete(Uri.parse(t.resultUri), null, null) }
            Arios.library.refresh()
        }
        synchronized(this) { _tasks.value = _tasks.value.filterNot { it.id == id } }
        persist()
    }

    fun clearFinished() {
        synchronized(this) { _tasks.value = _tasks.value.filterNot { it.state == DlState.DONE } }
        persist()
    }

    @Synchronized
    fun schedule() {
        jobs.entries.removeAll { !it.value.isActive }
        val queued = _tasks.value.filter { it.state == DlState.QUEUED && it.id !in jobs }.sortedBy { it.created }
        val free = maxParallel - jobs.size
        queued.take(free.coerceAtLeast(0)).forEach { t ->
            jobs[t.id] = Arios.scope.launch(Dispatchers.IO) { run(t.id) }
        }
        if (_tasks.value.any { it.state == DlState.QUEUED || it.state == DlState.RUNNING }) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
        }
    }

    private suspend fun run(id: String) {
        val part = File(partDir, "$id.part")
        try {
            update(id) { it.copy(state = DlState.RUNNING, error = null) }
            val task = _tasks.value.first { it.id == id }
            var have = if (part.exists()) part.length() else 0L
            val req = Request.Builder().url(task.url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
            Arios.http.newCall(req).execute().use { resp ->
                if (resp.code == 416) { /* already complete */ }
                else {
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body ?: error("empty")
                    val append = have > 0 && resp.code == 206
                    if (!append) have = 0
                    val total = if (body.contentLength() > 0) have + body.contentLength() else 0L
                    update(id) { it.copy(total = total, downloaded = have) }
                    FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(64 * 1024)
                        val input = body.byteStream()
                        var last = 0L
                        while (true) {
                            if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw CancellationException()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            have += n
                            val now = System.currentTimeMillis()
                            if (now - last > 400) { last = now; update(id, persist = false) { it.copy(downloaded = have) } }
                        }
                    }
                }
            }
            finish(id, part)
        } catch (e: CancellationException) {
            persist()
        } catch (e: Exception) {
            update(id) { it.copy(state = DlState.FAILED, error = e.message ?: e.javaClass.simpleName) }
        } finally {
            synchronized(this) { jobs.remove(id) }
            delay(50)
            schedule()
        }
    }

    private suspend fun finish(id: String, part: File) {
        val t = _tasks.value.first { it.id == id }
        val name = MediaSaver.safeName(if (t.artist.isNotBlank()) "${t.artist} - ${t.title}" else t.title) + "." + t.ext
        val uri = part.inputStream().use { MediaSaver.save(context, name, MediaSaver.mimeFor(t.ext), it) }
            ?: error("save failed")
        part.delete()
        update(id) { it.copy(state = DlState.DONE, resultUri = uri.toString(), downloaded = it.total.coerceAtLeast(it.downloaded)) }
        // artwork + lyrics so the song is complete offline
        withContext(Dispatchers.IO) {
            val songId = runCatching { android.content.ContentUris.parseId(uri) }.getOrNull()
            if (songId != null && songId >= 0 && t.artUrl != null && !Arios.artwork.songFile(songId).exists()) {
                Arios.artwork.downloadTo(t.artUrl, Arios.artwork.songFile(songId))
            }
        }
        Arios.library.scan()
        val song = Arios.library.state.value.songs.firstOrNull { it.uri.toString() == uri.toString() || it.uri.lastPathSegment == uri.lastPathSegment }
        if (song != null) runCatching { Arios.lyrics.get(song, allowNetwork = true) }
    }
}
