package com.arios.player.remix

import android.content.Context
import android.net.Uri
import com.arios.player.Arios
import com.arios.player.data.Song
import com.arios.player.download.MediaSaver
import com.arios.player.lyrics.AutoSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class DeckSource(val uri: Uri, val title: String, val artist: String, val songId: Long? = null, val durationMs: Long = 0) {
    fun toJson() = JSONObject().apply { put("uri", uri.toString()); put("title", title); put("artist", artist); put("id", songId ?: -1L); put("dur", durationMs) }
    companion object {
        fun of(s: Song) = DeckSource(s.uri, s.title, s.artist, s.id, s.durationMs)
        fun fromJson(o: JSONObject) = DeckSource(Uri.parse(o.getString("uri")), o.optString("title"), o.optString("artist"),
            o.optLong("id", -1).takeIf { it >= 0 }, o.optLong("dur"))
    }
}

enum class DeckStatus { EMPTY, ANALYSING, READY, ERROR }
data class Deck(val source: DeckSource? = null, val analysis: TrackAnalysis? = null, val status: DeckStatus = DeckStatus.EMPTY, val progress: Float = 0f)

data class RemixProject(val id: String, val name: String, val a: DeckSource, val b: DeckSource, val plan: RemixPlan, val created: Long, val output: String?)

/** One remix being edited + saved remixes. Survives screen changes. */
class RemixSession(private val context: Context) {
    private val dir = File(context.filesDir, "remix").apply { mkdirs() }

    val a = MutableStateFlow(Deck())
    val b = MutableStateFlow(Deck())
    private val _plan = MutableStateFlow<RemixPlan?>(null)
    val plan: StateFlow<RemixPlan?> = _plan
    /** null = idle, 0..1 = rendering/saving. */
    val work = MutableStateFlow<Pair<String, Float>?>(null)
    val previewing = MutableStateFlow(false)
    val projects = MutableStateFlow<List<RemixProject>>(emptyList())
    var projectId: String? = null; private set
    private val preview = PreviewPlayer()
    var previewOffset = 0; private set   // output frame of the first preview sample
    private val pcmLock = Mutex()
    private val pcm = HashMap<String, StereoPcm>()
    private var jobs = HashMap<Char, Job>()

    init { loadProjects() }

    // ------------------------------------------------------------------ decks

    fun setDeck(which: Char, src: DeckSource) {
        val deck = if (which == 'A') a else b
        jobs[which]?.cancel()
        deck.value = Deck(src, null, DeckStatus.ANALYSING, 0f)
        _plan.value = null
        jobs[which] = Arios.scope.launch {
            val an = runCatching { analysis(src) { p -> deck.value = deck.value.copy(progress = p) } }.getOrNull()
            if (deck.value.source != src) return@launch
            deck.value = if (an != null) Deck(src, an, DeckStatus.READY, 1f) else Deck(src, null, DeckStatus.ERROR, 0f)
            autoPlan()
        }
    }

    fun clearDeck(which: Char) { (if (which == 'A') a else b).value = Deck(); _plan.value = null; projectId = null }

    fun swap() {
        val x = a.value; a.value = b.value; b.value = x
        _plan.value = null; autoPlan()
    }

    private fun key(uri: Uri) = MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }

    private suspend fun analysis(src: DeckSource, progress: (Float) -> Unit): TrackAnalysis = withContext(Dispatchers.Default) {
        val f = File(dir, "an_${key(src.uri)}.json")
        if (f.exists()) TrackAnalysis.fromJson(f.readText())?.let { return@withContext it }
        progress(0.1f)
        val p = AutoSync.decode(context, src.uri) ?: error("decode")
        progress(0.6f)
        val an = Analyzer.analyze(p)
        runCatching { f.writeText(an.toJson()) }
        an
    }

    // ------------------------------------------------------------------ plan

    private fun autoPlan(style: MixStyle = _plan.value?.style ?: MixStyle.SMOOTH) {
        val ta = a.value.analysis ?: return; val tb = b.value.analysis ?: return
        _plan.value = AutoPlanner.plan(ta, tb, style)
    }

    internal fun setPlanForTest(p: RemixPlan) { _plan.value = p }

    fun reAuto() { stopPreview(); autoPlan() }

    fun update(f: (RemixPlan) -> RemixPlan) {
        val p = _plan.value ?: return
        val ta = a.value.analysis ?: return; val tb = b.value.analysis ?: return
        var n = f(p)
        // keep everything inside the songs
        val bBeats = (tb.beats * MixTiming(ta, tb, n).octave).toInt()
        n = n.copy(
            lengthBeats = n.lengthBeats.coerceIn(2, 128),
            outBeat = n.outBeat.coerceIn(0, (ta.beats - n.lengthBeats - 1).coerceAtLeast(0)),
            inBeat = n.inBeat.coerceIn(0, (bBeats - n.lengthBeats - 1).coerceAtLeast(0)),
            shiftB = n.shiftB.coerceIn(-4, 4),
            gainA = n.gainA.coerceIn(-12f, 6f), gainB = n.gainB.coerceIn(-12f, 6f),
        )
        n = n.copy(aStart = n.aStart.coerceIn(0.0, (ta.beatTime(n.outBeat) - 1).coerceAtLeast(0.0)))
        _plan.value = n
    }

    fun setStyle(s: MixStyle) = update { p ->
        val len = when (s) {
            MixStyle.CUT -> 8
            MixStyle.BLEND -> maxOf(p.lengthBeats, 32)
            else -> if (p.style == MixStyle.CUT || p.style == MixStyle.BLEND) AutoPlanner.defaultLength(s) else p.lengthBeats
        }
        p.copy(style = s, lengthBeats = len)
    }

    fun timing(): MixTiming? {
        val ta = a.value.analysis ?: return null; val tb = b.value.analysis ?: return null
        return MixTiming(ta, tb, _plan.value ?: return null)
    }

    // ------------------------------------------------------------------ audio

    private suspend fun full(src: DeckSource, shift: Int = 0): StereoPcm = pcmLock.withLock {
        val k = "${src.uri}|$shift"
        pcm[k]?.let { return it }
        // keep memory low: only what the current two decks need
        val keep = setOfNotNull(a.value.source?.uri?.toString(), b.value.source?.uri?.toString())
        pcm.keys.filter { it.substringBeforeLast('|') !in keep }.forEach { pcm.remove(it) }
        if (shift != 0) pcm.keys.filter { it.startsWith("${src.uri}|") && it != "${src.uri}|0" }.forEach { pcm.remove(it) }
        val base = pcm["${src.uri}|0"] ?: withContext(Dispatchers.Default) { PcmDecoder.decode(context, src.uri) }.also { pcm["${src.uri}|0"] = it }
        if (shift == 0) return base
        val shifted = withContext(Dispatchers.Default) { Wsola.pitchShift(base, shift) { p -> work.value = work.value?.copy(second = 0.2f + p * 0.3f) } }
        pcm[k] = shifted
        shifted
    }

    private suspend fun mixer(): Mixer? {
        val sa = a.value.source ?: return null; val sb = b.value.source ?: return null
        val ta = a.value.analysis ?: return null; val tb = b.value.analysis ?: return null
        val p = _plan.value ?: return null
        val pa = full(sa); val pb = full(sb, p.shiftB)
        return Mixer(pa, pb, ta, tb, p)
    }

    /** Renders and plays the transition with [contextSec] of music around it. */
    fun previewTransition(contextSec: Double = 7.0) {
        if (previewing.value) { stopPreview(); return }
        Arios.scope.launch {
            work.value = "preview" to 0.05f
            val m = runCatching { mixer() }.getOrNull()
            if (m == null) { work.value = null; return@launch }
            val sr = StereoPcm.RATE
            val from = (m.transitionStart - (contextSec * sr).toInt()).coerceAtLeast(0)
            val to = (m.transitionStart + m.transFrames + (contextSec * sr).toInt()).coerceAtMost(m.totalFrames)
            val out = ShortArray((to - from) * 2); var n = 0
            withContext(Dispatchers.Default) {
                m.render(from, to, { buf -> for (v in buf) out[n++] = (v * 32767).toInt().toShort() }) { p -> work.value = "preview" to 0.5f + p * 0.5f }
            }
            work.value = null
            pauseMainPlayer()
            previewOffset = from
            previewing.value = true
            preview.play(out, from) { previewing.value = false }
        }
    }

    fun stopPreview() { preview.stop(); previewing.value = false }
    fun previewFrame() = preview.positionFrames()

    private fun pauseMainPlayer() {
        if (Arios.player.state.value.isPlaying) Arios.player.toggle()
    }

    /** Renders the whole remix to an .m4a in Music/Flowfy and adds it to the library. */
    fun export(name: String, onDone: (Uri?) -> Unit) {
        stopPreview()
        Arios.scope.launch {
            work.value = "export" to 0.02f
            val uri = runCatching {
                val m = mixer() ?: error("not ready")
                val tmp = File(context.cacheDir, "remix_${System.currentTimeMillis()}.m4a")
                withContext(Dispatchers.Default) {
                    val w = AacWriter(tmp)
                    try { m.render(0, m.totalFrames, { w.write(it) }) { p -> work.value = "export" to 0.5f + p * 0.45f } } finally { w.finish() }
                }
                val saved = tmp.inputStream().use { MediaSaver.save(context, MediaSaver.safeName(name) + ".m4a", "audio/mp4", it) }
                tmp.delete()
                saved
            }.getOrNull()
            work.value = null
            if (uri != null) { saveProject(name, uri.toString()); Arios.library.refresh() }
            onDone(uri)
        }
    }

    // ------------------------------------------------------------------ projects

    private val projectsFile = File(dir, "projects.json")

    private fun loadProjects() {
        projects.value = runCatching {
            val arr = JSONArray(projectsFile.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                RemixProject(o.getString("id"), o.getString("name"), DeckSource.fromJson(o.getJSONObject("a")), DeckSource.fromJson(o.getJSONObject("b")),
                    RemixPlan.fromJson(o.getJSONObject("plan")), o.optLong("created"), o.optString("out").takeIf { it.isNotEmpty() })
            }
        }.getOrDefault(emptyList())
    }

    private fun persist() {
        val arr = JSONArray()
        projects.value.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id); put("name", p.name); put("a", p.a.toJson()); put("b", p.b.toJson()); put("plan", p.plan.toJson())
                put("created", p.created); put("out", p.output ?: "")
            })
        }
        runCatching { projectsFile.writeText(arr.toString()) }
    }

    fun saveProject(name: String, output: String? = null) {
        val sa = a.value.source ?: return; val sb = b.value.source ?: return; val p = _plan.value ?: return
        val id = projectId ?: java.util.UUID.randomUUID().toString()
        projectId = id
        val old = projects.value.firstOrNull { it.id == id }
        val pr = RemixProject(id, name, sa, sb, p, old?.created ?: System.currentTimeMillis(), output ?: old?.output)
        projects.value = listOf(pr) + projects.value.filterNot { it.id == id }
        persist()
    }

    fun open(pr: RemixProject) {
        stopPreview()
        projectId = pr.id
        a.value = Deck(pr.a, null, DeckStatus.ANALYSING); b.value = Deck(pr.b, null, DeckStatus.ANALYSING)
        Arios.scope.launch {
            val ta = runCatching { analysis(pr.a) {} }.getOrNull(); val tb = runCatching { analysis(pr.b) {} }.getOrNull()
            a.value = if (ta != null) Deck(pr.a, ta, DeckStatus.READY, 1f) else Deck(pr.a, null, DeckStatus.ERROR)
            b.value = if (tb != null) Deck(pr.b, tb, DeckStatus.READY, 1f) else Deck(pr.b, null, DeckStatus.ERROR)
            _plan.value = if (ta != null && tb != null) pr.plan else null
        }
    }

    fun deleteProject(id: String) {
        projects.value = projects.value.filterNot { it.id == id }; persist()
        if (projectId == id) projectId = null
    }

    fun newRemix() { stopPreview(); projectId = null; a.value = Deck(); b.value = Deck(); _plan.value = null }
}
