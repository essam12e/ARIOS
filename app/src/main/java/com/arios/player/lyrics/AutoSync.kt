package com.arios.player.lyrics

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * On-device automatic lyric timing (no network, no AI model).
 *
 * 1. Vocal activity: vocals are usually mixed in the centre while instruments are spread
 *    across the stereo field, so per 20 ms frame we measure how much *centre-only*
 *    (mid minus side) energy sits in the voice band, weighted by how tonal it is
 *    (a voice is harmonic, a snare is noise-like).
 * 2. The vocal track is cut into phrases at its pauses.
 * 3. Lines are aligned to phrases with dynamic programming: a line may cover several
 *    phrases, a phrase may hold several short lines, and stray phrases (ad-libs, an
 *    instrument in the centre) can be skipped — all scored against the singing rate.
 */
object AutoSync {
    const val RATE = 11025          // analysis sample rate
    private const val N = 512       // FFT size (~46 ms)
    private const val HOP = 220     // ~20 ms
    val HOP_SEC = HOP / RATE.toDouble()

    class Pcm(val left: FloatArray, val right: FloatArray?, val sampleRate: Int)

    // ------------------------------------------------------------------ decoding

    /** Decodes [uri] to mono/stereo float PCM resampled (box filter) to [RATE]. */
    fun decode(context: Context, uri: Uri): Pcm? = runCatching {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: return null
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        val l = FloatList(); val r = FloatList()
        var accL = 0f; var accR = 0f; var accN = 0; var phase = 0.0
        val info = MediaCodec.BufferInfo()
        var inDone = false; var outDone = false
        while (!outDone) {
            if (!inDone) {
                val i = codec.dequeueInputBuffer(5_000)
                if (i >= 0) {
                    val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 5_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                sr = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                ch = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else if (o >= 0) {
                val sb = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).apply { position(info.offset); limit(info.offset + info.size) }.asShortBuffer()
                val step = RATE / sr.toDouble()
                while (sb.remaining() >= ch) {
                    val a = sb.get() / 32768f
                    val b = if (ch > 1) sb.get() / 32768f else a
                    repeat(ch - 2) { sb.get() }
                    accL += a; accR += b; accN++
                    phase += step
                    if (phase >= 1.0) { phase -= 1.0; l.add(accL / accN); r.add(accR / accN); accL = 0f; accR = 0f; accN = 0 }
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
            }
        }
        codec.stop(); codec.release(); ex.release()
        Pcm(l.toArray(), if (ch > 1) r.toArray() else null, RATE)
    }.getOrNull()

    private class FloatList {
        var a = FloatArray(1 shl 20); var n = 0
        fun add(v: Float) { if (n == a.size) a = a.copyOf(a.size * 2); a[n++] = v }
        fun toArray() = a.copyOf(n)
    }

    // ------------------------------------------------------------------ vocal activity

    /** Vocal-likelihood per hop (20 ms), smoothed, roughly 0..1. */
    fun vocalCurve(pcm: Pcm): FloatArray {
        val l = pcm.left; val r = pcm.right ?: pcm.left
        val stereo = pcm.right != null && sideEnergy(l, r) > 1e-4
        val frames = ((l.size - N) / HOP).coerceAtLeast(0)
        val win = FloatArray(N) { (0.5 - 0.5 * cos(2 * Math.PI * it / (N - 1))).toFloat() }
        val mr = FloatArray(N); val mi = FloatArray(N); val sr = FloatArray(N); val si = FloatArray(N)
        val lo = (250.0 * N / RATE).toInt(); val hi = (3500.0 * N / RATE).toInt()
        val env = FloatArray(frames); val tonal = FloatArray(frames)
        for (fIdx in 0 until frames) {
            val off = fIdx * HOP
            for (i in 0 until N) {
                val a = l[off + i]; val b = r[off + i]
                mr[i] = (a + b) * 0.5f * win[i]; mi[i] = 0f
                sr[i] = (a - b) * 0.5f * win[i]; si[i] = 0f
            }
            fft(mr, mi)
            if (stereo) fft(sr, si)
            var centre = 0.0; var logSum = 0.0; var linSum = 0.0; var cnt = 0
            for (k in lo..hi) {
                val m = (mr[k] * mr[k] + mi[k] * mi[k]).toDouble()
                val s = if (stereo) (sr[k] * sr[k] + si[k] * si[k]).toDouble() else 0.0
                val c = max(0.0, m - 1.6 * s)
                centre += c
                logSum += ln(m + 1e-12); linSum += m; cnt++
            }
            // spectral flatness: 0 = tonal (voice), 1 = noise (drums)
            val flat = if (linSum > 0) (kotlin.math.exp(logSum / cnt) / (linSum / cnt)).coerceIn(0.0, 1.0) else 1.0
            env[fIdx] = ln(1 + centre * 1e4).toFloat()
            tonal[fIdx] = ((1 - flat) * (1 - flat) * 3).coerceIn(0.0, 1.0).toFloat()
        }
        // syllabic modulation: singing/speech pulses at ~3–9 Hz, sustained instruments do not
        val modWin = (0.6 / HOP_SEC).toInt().coerceAtLeast(3)
        val trend = movingAvg(env, modWin)
        val d = FloatArray(frames) { env[it] - trend[it] }
        val a3 = movingAvg(d, 3); val a9 = movingAvg(d, 9)
        val bp = FloatArray(frames) { val v = a3[it] - a9[it]; v * v }
        val mod = movingAvg(bp, modWin).let { m -> FloatArray(frames) { kotlin.math.sqrt(m[it]) } }
        val en = normalise(env); val mn = normalise(mod)
        lastPlain = normalise(movingAvg(FloatArray(frames) { en[it] * tonal[it] }, 7))
        // clean audio (speech / a cappella) has real silences; music almost never does
        run {
            val srt = env.sortedArray()
            val p15 = srt.getOrElse((srt.size * 0.15).toInt()) { 0f }; val p60 = srt.getOrElse((srt.size * 0.60).toInt()) { 1f }
            lastClean = p60 > 0 && p15 / p60 < 0.6f
        }
        val raw = FloatArray(frames) { (en[it] * 0.4f + mn[it] * 0.6f) * tonal[it] }
        return normalise(movingAvg(raw, 7))
    }

    /** Energy-only curve from the last [vocalCurve] call (best for clean, unaccompanied audio). */
    @Volatile private var lastPlain: FloatArray = FloatArray(0)
    @Volatile private var lastClean = false

    private fun movingAvg(x: FloatArray, w: Int): FloatArray {
        val out = FloatArray(x.size); val h = w / 2; var s = 0.0
        val pre = DoubleArray(x.size + 1)
        for (i in x.indices) { s += x[i]; pre[i + 1] = s }
        for (i in x.indices) { val a = (i - h).coerceAtLeast(0); val b = (i + h + 1).coerceAtMost(x.size); out[i] = ((pre[b] - pre[a]) / (b - a)).toFloat() }
        return out
    }

    private fun normalise(x: FloatArray): FloatArray {
        if (x.isEmpty()) return x
        val sorted = x.sortedArray()
        val p10 = sorted[(sorted.size * 0.10).toInt()]
        val p97 = sorted[((sorted.size - 1) * 0.97).toInt()]
        val span = (p97 - p10).coerceAtLeast(1e-6f)
        return FloatArray(x.size) { ((x[it] - p10) / span).coerceIn(0f, 1f) }
    }

    private fun sideEnergy(l: FloatArray, r: FloatArray): Double {
        var s = 0.0; var i = 0
        while (i < l.size) { val d = (l[i] - r[i]).toDouble(); s += d * d; i += 50 }
        return s / (l.size / 50 + 1)
    }

    private fun smooth(x: FloatArray, radius: Int): FloatArray = FloatArray(x.size) { i ->
        var s = 0f; var c = 0
        for (j in (i - radius)..(i + radius)) if (j in x.indices) { s += x[j]; c++ }
        s / c
    }

    /** Vocal phrases as (startSec, endSec), split at pauses. */
    fun phrases(curve: FloatArray, threshold: Float = 0.32f, minGap: Double = 0.30, minLen: Double = 0.30): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var start = -1
        for (i in curve.indices) {
            val on = curve[i] > threshold
            if (on && start < 0) start = i
            if (!on && start >= 0) { out += start * HOP_SEC to i * HOP_SEC; start = -1 }
        }
        if (start >= 0) out += start * HOP_SEC to curve.size * HOP_SEC
        // merge short gaps, drop blips
        val merged = ArrayList<Pair<Double, Double>>()
        out.forEach { p ->
            val last = merged.lastOrNull()
            if (last != null && p.first - last.second < minGap) merged[merged.size - 1] = last.first to p.second else merged += p
        }
        return merged.filter { it.second - it.first >= minLen }
    }

    // ------------------------------------------------------------------ alignment

    /** Weight of a line ≈ how long it takes to sing (letters, Arabic or Latin). */
    fun weight(line: String): Double = line.count { it.isLetterOrDigit() }.coerceAtLeast(1).toDouble()

    /**
     * Returns the start time (s) of every line. [lines] must be non-blank.
     * DP over (line i, phrase j): a group of 1..3 lines ↔ a group of 1..4 phrases (not both > 1),
     * or skip phrase j. Cost = squared deviation from the song's singing rate.
     */
    fun align(lines: List<String>, phrases: List<Pair<Double, Double>>): List<Double>? = alignScored(lines, phrases)?.first

    fun alignScored(lines: List<String>, phrases: List<Pair<Double, Double>>): Pair<List<Double>, Double>? {
        if (lines.isEmpty() || phrases.isEmpty()) return null
        val w = lines.map(::weight)
        val dur = phrases.map { it.second - it.first }
        var rate = w.sum() / dur.sum()               // letters per second, refined below
        var result: Pair<List<Double>, Double>? = null
        repeat(3) {
            val n = lines.size; val m = phrases.size
            val INF = Double.MAX_VALUE / 4
            val dp = Array(n + 1) { DoubleArray(m + 1) { INF } }
            val bk = Array(n + 1) { arrayOfNulls<IntArray>(m + 1) }
            dp[0][0] = 0.0
            for (i in 0..n) for (j in 0..m) {
                val cur = dp[i][j]; if (cur >= INF) continue
                if (j < m) { // skip a phrase (instrument / ad-lib), costs its length
                    val c = cur + 0.9 * dur[j] + 0.4
                    if (c < dp[i][j + 1]) { dp[i][j + 1] = c; bk[i][j + 1] = intArrayOf(i, j, 0) }
                }
                if (i == n) continue
                for (k in i + 1..minOf(n, i + 3)) for (l in j + 1..minOf(m, j + 4)) {
                    if (k - i > 1 && l - j > 1) continue
                    val letters = w.subList(i, k).sum()
                    val span = phrases[l - 1].second - phrases[j].first
                    val expect = letters / rate
                    var c = (expect - span) * (expect - span) / max(span, 0.6)
                    // gaps inside a multi-phrase line should be short
                    for (q in j until l - 1) c += max(0.0, phrases[q + 1].first - phrases[q].second - 0.8) * 0.8
                    c += cur + 0.05 * ((k - i) + (l - j))
                    if (c < dp[k][l]) { dp[k][l] = c; bk[k][l] = intArrayOf(i, j, 1) }
                }
            }
            // allow trailing phrases to be skipped
            var bestJ = m; var best = INF
            for (j in 0..m) {
                val c = dp[n][j] + (j until m).sumOf { 0.9 * dur[it] + 0.4 }
                if (c < best) { best = c; bestJ = j }
            }
            if (best >= INF) return result
            val starts = DoubleArray(n)
            var i = n; var j = bestJ
            var usedLetters = 0.0; var usedTime = 0.0
            while (i > 0 || j > 0) {
                val b = bk[i][j] ?: break
                val (pi, pj, kind) = b.toList()
                if (kind == 1) {
                    val t0 = phrases[pj].first; val t1 = phrases[j - 1].second
                    usedLetters += w.subList(pi, i).sum(); usedTime += t1 - t0
                    // several lines in one phrase: split it by letters
                    val total = w.subList(pi, i).sum()
                    var acc = 0.0
                    for (q in pi until i) { starts[q] = t0 + (t1 - t0) * acc / total; acc += w[q] }
                }
                i = pi; j = pj
            }
            result = starts.toList() to best / lines.size
            if (usedTime > 0) rate = usedLetters / usedTime
        }
        return result
    }

    /** Builds synced lyrics from plain [lines] and the audio analysis. */
    fun toLrc(lines: List<String>, starts: List<Double>): String = buildString {
        lines.forEachIndexed { i, t -> append(LrcWriter.stamp((starts[i] * 1000).toLong())).append(t).append('\n') }
    }

    /** Full pipeline for a plain-lyrics song. Returns LRC text or null if it could not be timed. */
    fun sync(context: Context, uri: Uri, plain: Lyrics): String? {
        val lines = plain.lines.map { it.text.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return null
        val pcm = decode(context, uri) ?: return null
        return syncPcm(pcm, lines)
    }

    fun syncPcm(pcm: Pcm, lines: List<String>): String? {
        val curve = vocalCurve(pcm)
        val thresholds = listOf(0.10f, 0.14f, 0.18f, 0.22f, 0.27f, 0.33f, 0.40f)
        val best = if (lastClean) {
            // clean speech / a cappella: phrases are clear, pick the natural phrase count
            val target = lines.size * 1.4
            thresholds.map { phrases(lastPlain, it) }.filter { it.isNotEmpty() }
                .minByOrNull { abs(it.size - target) + if (it.size < lines.size / 2) 100.0 else 0.0 }
                ?.let { alignScored(lines, it) } ?: return null
        } else {
            // songs: keep the threshold whose alignment fits the lyric best
            thresholds.map { phrases(curve, it) }
                .filter { it.size >= (lines.size / 2).coerceAtLeast(1) }
                .mapNotNull { alignScored(lines, it) }
                .minByOrNull { it.second } ?: return null
        }
        return toLrc(lines, best.first)
    }

    // ------------------------------------------------------------------ FFT

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * Math.PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                val half = len / 2
                for (k in 0 until half) {
                    val xr = re[i + k + half] * cr - im[i + k + half] * ci
                    val xi = re[i + k + half] * ci + im[i + k + half] * cr
                    re[i + k + half] = re[i + k] - xr; im[i + k + half] = im[i + k] - xi
                    re[i + k] += xr; im[i + k] += xi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
