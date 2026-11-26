package com.arios.player.remix

import com.arios.player.lyrics.AutoSync
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What the remix engine knows about a track: a steady beat grid, musical key and per-beat energy/vocals.
 * Beat times: firstBeat + i * period (seconds). Bars start at beats where (i - downbeat) % 4 == 0.
 */
data class TrackAnalysis(
    val durationSec: Double,
    val bpm: Double,
    val firstBeat: Double,
    val downbeat: Int,          // 0..3: index of the first bar start in the grid
    val key: Int,               // pitch class 0 = C
    val minor: Boolean,
    val beatEnergy: FloatArray, // 0..1 per beat
    val beatVocal: FloatArray,  // 0..1 per beat
    val peaks: FloatArray,      // waveform, 0..1, PEAKS buckets
    val loudness: Float,        // mean RMS (linear)
) {
    val period get() = 60.0 / bpm
    val beats get() = beatEnergy.size
    fun beatTime(i: Int) = firstBeat + i * period
    fun beatAt(sec: Double) = ((sec - firstBeat) / period)
    fun isBarStart(i: Int) = ((i - downbeat) % 4 + 4) % 4 == 0
    fun camelot(shift: Int = 0) = Keys.camelot((key + shift + 120) % 12, minor)
    fun keyName(shift: Int = 0) = Keys.name((key + shift + 120) % 12, minor)

    fun toJson(): String = org.json.JSONObject().apply {
        put("v", VERSION); put("dur", durationSec); put("bpm", bpm); put("fb", firstBeat); put("db", downbeat)
        put("key", key); put("minor", minor); put("loud", loudness.toDouble())
        put("e", floats(beatEnergy)); put("vo", floats(beatVocal)); put("pk", floats(peaks))
    }.toString()

    companion object {
        const val VERSION = 1
        const val PEAKS = 1200
        private fun floats(a: FloatArray) = a.joinToString(",") { "%.3f".format(java.util.Locale.US, it) }
        private fun parse(s: String) = if (s.isBlank()) FloatArray(0) else s.split(',').map { it.toFloat() }.toFloatArray()
        fun fromJson(s: String): TrackAnalysis? = runCatching {
            val o = org.json.JSONObject(s)
            if (o.optInt("v") != VERSION) return null
            TrackAnalysis(o.getDouble("dur"), o.getDouble("bpm"), o.getDouble("fb"), o.getInt("db"), o.getInt("key"), o.getBoolean("minor"),
                parse(o.getString("e")), parse(o.getString("vo")), parse(o.getString("pk")), o.getDouble("loud").toFloat())
        }.getOrNull()
    }
}

object Keys {
    private val NAMES = arrayOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
    fun name(k: Int, minor: Boolean) = NAMES[k] + if (minor) "m" else ""
    /** Camelot wheel position, e.g. "8A" (A minor) / "8B" (C major). */
    fun camelotNumber(k: Int, minor: Boolean): Int { val major = if (minor) (k + 3) % 12 else k; return (major * 7 % 12 + 7) % 12 + 1 }
    fun camelot(k: Int, minor: Boolean) = "${camelotNumber(k, minor)}${if (minor) "A" else "B"}"
    /** 0 = same, 1 = neighbour on the wheel (harmonic), 2+ = clash. */
    fun distance(k1: Int, m1: Boolean, k2: Int, m2: Boolean): Int {
        val a = camelotNumber(k1, m1); val b = camelotNumber(k2, m2)
        val d = min((a - b + 12) % 12, (b - a + 12) % 12)
        return if (m1 == m2) d else d + 1
    }
    /** Smallest pitch shift (semitones, −3..3) that makes B harmonic with A. */
    fun bestShift(a: TrackAnalysis, b: TrackAnalysis): Int =
        (-3..3).minByOrNull { s -> distance(a.key, a.minor, (b.key + s + 12) % 12, b.minor) * 10 + abs(s) } ?: 0
}

object Analyzer {
    private const val N = 512
    private const val HOP = 128

    /** Analyses decoded audio (stereo or mono at [AutoSync.RATE]). */
    fun analyze(pcm: AutoSync.Pcm): TrackAnalysis {
        val sr = AutoSync.RATE.toDouble()
        val l = pcm.left; val r = pcm.right ?: pcm.left
        val mono = FloatArray(l.size) { (l[it] + r[it]) * 0.5f }
        val dur = mono.size / sr
        val fps = sr / HOP

        // ---- onset envelopes (full band and bass) from spectral flux
        val frames = max(0, (mono.size - N) / HOP)
        val win = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / (N - 1))).toFloat() }
        val re = FloatArray(N); val im = FloatArray(N)
        var prev = FloatArray(N / 2); var cur = FloatArray(N / 2)
        val onset = FloatArray(frames); val bass = FloatArray(frames)
        val bassBins = (160.0 * N / sr).toInt()
        for (f in 0 until frames) {
            val off = f * HOP
            for (i in 0 until N) { re[i] = mono[off + i] * win[i]; im[i] = 0f }
            Fft.fft(re, im)
            var flux = 0f; var bf = 0f
            for (k in 1 until N / 2) {
                val m = ln(1f + 100f * sqrt(re[k] * re[k] + im[k] * im[k]))
                cur[k] = m
                val d = m - prev[k]
                if (d > 0) { flux += d; if (k <= bassBins) bf += d }
            }
            onset[f] = flux; bass[f] = bf
            val t = prev; prev = cur; cur = t
        }
        val env = detrend(onset, (fps * 1.0).toInt())
        val benv = detrend(bass, (fps * 1.0).toInt())

        // ---- tempo: autocorrelation with a prior around 120 BPM
        val minLag = (fps * 60 / 190).toInt(); val maxLag = (fps * 60 / 65).toInt()
        var bestLag = 0.0; var bestScore = -1.0
        val acf = DoubleArray(maxLag + 2)
        for (lag in minLag..min(maxLag + 1, env.size - 1)) {
            var s = 0.0
            for (i in 0 until env.size - lag) s += env[i] * env[i + lag]
            acf[lag] = s / (env.size - lag)
        }
        for (lag in minLag + 1..min(maxLag, env.size - 2)) {
            val bpm = 60 * fps / lag
            val prior = exp(-0.5 * (log2(bpm / 120.0) / 0.9).let { it * it })
            val sc = acf[lag] * prior
            if (sc > bestScore && acf[lag] >= acf[lag - 1] && acf[lag] >= acf[lag + 1]) {
                bestScore = sc
                val a = acf[lag - 1]; val b = acf[lag]; val c = acf[lag + 1]
                val den = a - 2 * b + c
                bestLag = lag + if (den != 0.0) (0.5 * (a - c) / den).coerceIn(-0.5, 0.5) else 0.0
            }
        }
        if (bestLag <= 0) bestLag = fps * 60 / 120

        // ---- grid: refine period + phase by maximising the onset comb over the whole track
        var bestP = bestLag; var bestPhase = 0.0; var bestComb = -1.0
        val steps = 60
        for (si in -steps..steps) {
            val p = bestLag * (1 + si * 0.0004)
            var ph = 0.0
            while (ph < p) {
                val c = comb(env, p, ph)
                if (c > bestComb) { bestComb = c; bestP = p; bestPhase = ph }
                ph += 0.5
            }
        }
        // fine phase
        run {
            var ph = bestPhase - 0.5
            while (ph <= bestPhase + 0.5) { val c = comb(env, bestP, ph); if (c > bestComb) { bestComb = c; bestPhase = ph }; ph += 0.05 }
        }
        val bpm = 60 * fps / bestP
        val period = 60 / bpm
        // the onset frame t covers samples [t*HOP, t*HOP+N): its centre is the event time
        var firstBeat = (bestPhase * HOP + N / 2.0) / sr
        while (firstBeat - period >= 0) firstBeat -= period
        val beats = max(1, ((dur - firstBeat) / period).toInt())

        // ---- downbeat: the beat phase with the strongest bass onsets
        val db = (0 until 4).maxByOrNull { d ->
            var s = 0.0; var i = d
            while (i < beats) { s += sampleAt(benv, (firstBeat + i * period) * fps - N / 2.0 / HOP); i += 4 }
            s
        } ?: 0

        // ---- per-beat energy + vocals
        val energy = FloatArray(beats); var loudSum = 0.0
        for (b in 0 until beats) {
            val s0 = ((firstBeat + b * period) * sr).toInt().coerceIn(0, mono.size); val s1 = ((firstBeat + (b + 1) * period) * sr).toInt().coerceIn(s0, mono.size)
            var e = 0.0; for (i in s0 until s1) e += mono[i] * mono[i]
            energy[b] = sqrt(e / max(1, s1 - s0)).toFloat(); loudSum += energy[b]
        }
        val loud = (loudSum / beats).toFloat()
        val emax = energy.sortedArray().let { it[(it.size * 0.95).toInt().coerceAtMost(it.size - 1)] }.coerceAtLeast(1e-6f)
        for (b in energy.indices) energy[b] = (energy[b] / emax).coerceIn(0f, 1f)
        val voc = AutoSync.vocalCurve(pcm)
        val vocal = FloatArray(beats) { b ->
            val a = ((firstBeat + b * period) / AutoSync.HOP_SEC).toInt().coerceIn(0, voc.size); val e = ((firstBeat + (b + 1) * period) / AutoSync.HOP_SEC).toInt().coerceIn(a, voc.size)
            if (e > a) { var s = 0f; for (i in a until e) s += voc[i]; s / (e - a) } else 0f
        }

        // ---- key from a chroma profile
        val (key, minor) = key(mono, sr)

        // ---- waveform peaks
        val peaks = FloatArray(TrackAnalysis.PEAKS)
        val per = max(1, mono.size / TrackAnalysis.PEAKS)
        for (p in peaks.indices) { var m = 0f; val s = p * per; for (i in s until min(mono.size, s + per)) m = max(m, abs(mono[i])); peaks[p] = m }
        val pmax = peaks.maxOrNull()?.coerceAtLeast(1e-6f) ?: 1f
        for (p in peaks.indices) peaks[p] = sqrt(peaks[p] / pmax)

        return TrackAnalysis(dur, bpm, firstBeat, db, key, minor, energy, vocal, peaks, loud)
    }

    private fun detrend(x: FloatArray, w: Int): FloatArray {
        val pre = DoubleArray(x.size + 1); for (i in x.indices) pre[i + 1] = pre[i] + x[i]
        val out = FloatArray(x.size); val h = w / 2
        for (i in x.indices) { val a = max(0, i - h); val b = min(x.size, i + h + 1); out[i] = max(0f, x[i] - ((pre[b] - pre[a]) / (b - a)).toFloat()) }
        val m = out.maxOrNull()?.coerceAtLeast(1e-9f) ?: 1f
        for (i in out.indices) out[i] /= m
        return out
    }

    private fun sampleAt(x: FloatArray, pos: Double): Double {
        val i = pos.roundToInt()
        var m = 0f
        for (j in i - 1..i + 1) if (j in x.indices) m = max(m, x[j])
        return m.toDouble()
    }

    private fun comb(env: FloatArray, p: Double, phase: Double): Double {
        var s = 0.0; var t = phase; var n = 0
        while (t < env.size - 1) { s += sampleAt(env, t); t += p; n++ }
        return if (n == 0) 0.0 else s / n
    }

    private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    fun key(mono: FloatArray, sr: Double): Pair<Int, Boolean> {
        val n = 4096; val hop = 4096
        val chroma = DoubleArray(12)
        val re = FloatArray(n); val im = FloatArray(n)
        val win = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / (n - 1))).toFloat() }
        val pc = IntArray(n / 2) { k ->
            val f = k * sr / n
            if (f < 60 || f > 2000) -1 else ((12 * log2(f / 440.0)).roundToInt() + 9 + 1200) % 12
        }
        var off = 0
        while (off + n <= mono.size) {
            for (i in 0 until n) { re[i] = mono[off + i] * win[i]; im[i] = 0f }
            Fft.fft(re, im)
            for (k in 1 until n / 2) if (pc[k] >= 0) chroma[pc[k]] += sqrt(sqrt((re[k] * re[k] + im[k] * im[k]).toDouble()))
            off += hop
        }
        var best = 0; var bestMinor = false; var bestR = -2.0
        for (k in 0 until 12) for (m in listOf(false, true)) {
            val prof = if (m) MINOR else MAJOR
            val r = pearson(DoubleArray(12) { chroma[(it + k) % 12] }, prof)
            if (r > bestR) { bestR = r; best = k; bestMinor = m }
        }
        return best to bestMinor
    }

    private fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average(); val mb = b.average()
        var n = 0.0; var da = 0.0; var dbb = 0.0
        for (i in a.indices) { n += (a[i] - ma) * (b[i] - mb); da += (a[i] - ma) * (a[i] - ma); dbb += (b[i] - mb) * (b[i] - mb) }
        return if (da == 0.0 || dbb == 0.0) 0.0 else n / sqrt(da * dbb)
    }
}

object Fft {
    fun fft(re: FloatArray, im: FloatArray) {
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
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0; val half = len / 2
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until half) {
                    val a = i + k; val b = a + half
                    val xr = re[b] * cr - im[b] * ci; val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
