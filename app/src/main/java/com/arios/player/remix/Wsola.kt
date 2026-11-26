package com.arios.player.remix

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** Interleaved stereo 16-bit PCM at [RATE]. */
class StereoPcm(val data: ShortArray) {
    val frames get() = data.size / 2
    fun l(i: Int) = if (i in 0 until frames) data[2 * i] / 32768f else 0f
    fun r(i: Int) = if (i in 0 until frames) data[2 * i + 1] / 32768f else 0f
    companion object { const val RATE = 44100 }
}

/**
 * WSOLA time-stretching (waveform-similarity overlap-add) driven by a time map,
 * so tempo can glide smoothly while beats stay exactly where the map puts them.
 */
object Wsola {
    private const val N = 2048
    private const val HS = N / 2
    private const val TOL = 384
    private val win = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / N)).toFloat() }

    /**
     * Renders [outFrames] frames: output frame o reads around source frame [map](o) (fractional).
     * Returns interleaved stereo floats.
     */
    fun render(src: StereoPcm, outFrames: Int, map: (Int) -> Double): FloatArray {
        val out = FloatArray(outFrames * 2 + N * 2)
        var prevStart = Int.MIN_VALUE
        var o = -HS
        while (o < outFrames) {
            val ideal = (map(max(0, o + HS)) - HS).toInt()
            val start = if (prevStart == Int.MIN_VALUE) ideal else bestStart(src, ideal, prevStart + HS)
            for (k in 0 until N) {
                val oi = o + k
                if (oi < 0 || oi >= outFrames) continue
                val w = win[k]; val s = start + k
                out[2 * oi] += src.l(s) * w; out[2 * oi + 1] += src.r(s) * w
            }
            prevStart = start
            o += HS
        }
        return out.copyOf(outFrames * 2)
    }

    /** Position near [ideal] whose waveform best continues the natural next segment at [natural]. */
    private fun bestStart(src: StereoPcm, ideal: Int, natural: Int): Int {
        if (kotlin.math.abs(ideal - natural) <= 2) return natural
        val len = HS; val step = 4
        var best = ideal; var bestC = Double.NEGATIVE_INFINITY
        var d = -TOL
        while (d <= TOL) {
            val c0 = ideal + d
            var c = 0.0; var k = 0
            while (k < len) {
                val x = src.l(c0 + k) + src.r(c0 + k); val y = src.l(natural + k) + src.r(natural + k)
                c += x * y; k += step
            }
            if (c > bestC) { bestC = c; best = c0 }
            d += 2
        }
        return best
    }

    /** Pitch shift by [semitones] keeping the duration (stretch, then resample). */
    fun pitchShift(src: StereoPcm, semitones: Int, progress: (Float) -> Unit = {}): StereoPcm {
        if (semitones == 0) return src
        val p = Math.pow(2.0, semitones / 12.0)
        val stretchedFrames = (src.frames * p).toInt()
        val chunk = StereoPcm.RATE * 20
        val stretched = FloatArray(stretchedFrames * 2)
        var done = 0
        while (done < stretchedFrames) {
            val n = min(chunk, stretchedFrames - done)
            val part = render(src, n) { o -> (done + o) / p }
            System.arraycopy(part, 0, stretched, done * 2, n * 2)
            done += n; progress(done.toFloat() / stretchedFrames)
        }
        val out = ShortArray(src.frames * 2)
        for (i in 0 until src.frames) {
            val pos = i * p; val i0 = pos.toInt(); val f = (pos - i0).toFloat()
            for (c in 0..1) {
                val a = if (2 * i0 + c < stretched.size) stretched[2 * i0 + c] else 0f
                val b = if (2 * (i0 + 1) + c < stretched.size) stretched[2 * (i0 + 1) + c] else 0f
                out[2 * i + c] = ((a + (b - a) * f).coerceIn(-1f, 1f) * 32767).toInt().toShort()
            }
        }
        return StereoPcm(out)
    }
}
