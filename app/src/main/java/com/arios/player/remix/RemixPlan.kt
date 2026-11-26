package com.arios.player.remix

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

enum class MixStyle { SMOOTH, FILTER, ECHO, CUT, BLEND }
enum class FadeCurve { LINEAR, POWER, S }

/** Everything the user can tune; the auto-planner fills sensible values. */
data class RemixPlan(
    val outBeat: Int,            // beat of A where the transition starts
    val inBeat: Int,             // beat of B that lands on A's outBeat
    val lengthBeats: Int,        // transition length
    val style: MixStyle = MixStyle.SMOOTH,
    val curve: FadeCurve = FadeCurve.POWER,
    val tempoSync: Boolean = true,
    val bassSwap: Boolean = true,
    val shiftB: Int = 0,         // semitones
    val gainA: Float = 0f,       // dB
    val gainB: Float = 0f,       // dB (on top of auto level)
    val autoLevel: Boolean = true,
    val aStart: Double = 0.0,    // seconds: where the remix starts inside A
    val aFadeIn: Double = 0.0,
    val bEnd: Double = -1.0,     // seconds inside B (-1 = to the end)
    val bFadeOut: Double = 3.0,
) {
    fun toJson() = org.json.JSONObject().apply {
        put("out", outBeat); put("in", inBeat); put("len", lengthBeats); put("style", style.name); put("curve", curve.name)
        put("sync", tempoSync); put("bass", bassSwap); put("shift", shiftB); put("ga", gainA.toDouble()); put("gb", gainB.toDouble())
        put("lvl", autoLevel); put("as", aStart); put("afi", aFadeIn); put("be", bEnd); put("bfo", bFadeOut)
    }

    companion object {
        fun fromJson(o: org.json.JSONObject) = RemixPlan(
            o.getInt("out"), o.getInt("in"), o.getInt("len"),
            runCatching { MixStyle.valueOf(o.getString("style")) }.getOrDefault(MixStyle.SMOOTH),
            runCatching { FadeCurve.valueOf(o.getString("curve")) }.getOrDefault(FadeCurve.POWER),
            o.optBoolean("sync", true), o.optBoolean("bass", true), o.optInt("shift"), o.optDouble("ga").toFloat(), o.optDouble("gb").toFloat(),
            o.optBoolean("lvl", true), o.optDouble("as"), o.optDouble("afi"), o.optDouble("be", -1.0), o.optDouble("bfo", 3.0),
        )
    }
}

/** Derived timing of a plan: tempo factor, transition duration, time maps. */
class MixTiming(val a: TrackAnalysis, val b: TrackAnalysis, val plan: RemixPlan) {
    /** B's beat grid doubled/halved when its tempo was detected an octave away from A. */
    val octave: Double = listOf(0.5, 1.0, 2.0).minByOrNull { abs(ln(b.bpm * it / a.bpm)) }!!
    val bBpm = b.bpm * octave
    val bPeriod = 60.0 / bBpm
    val ratio = bBpm / a.bpm
    /** Tempos are matched only when the change is small enough to sound natural. */
    val synced = plan.tempoSync && abs(ratio - 1) <= 0.12
    val tA0 = a.beatTime(plan.outBeat)
    val tB0 = b.firstBeat + plan.inBeat * bPeriod
    /** Transition length in output seconds. */
    val duration: Double = if (synced) 120.0 * plan.lengthBeats / (a.bpm + bBpm) else plan.lengthBeats * a.period

    private fun beatsElapsed(t: Double): Double =
        if (synced) (a.bpm * t + (bBpm - a.bpm) * t * t / (2 * duration)) / 60.0 else t / a.period

    /** Source time in A for output time t (0..duration) of the transition. */
    fun srcA(t: Double) = if (synced) tA0 + beatsElapsed(t) * a.period else tA0 + t
    fun srcB(t: Double) = if (synced) tB0 + beatsElapsed(t) * bPeriod else tB0 + t

    val bEnd get() = if (plan.bEnd > 0) min(plan.bEnd, b.durationSec) else b.durationSec
    /** Output length of each part (seconds). */
    val introLen get() = max(0.0, tA0 - plan.aStart)
    val outroLen get() = max(0.0, bEnd - srcB(duration))
    val total get() = introLen + duration + outroLen
}

object AutoPlanner {
    private fun mean(x: FloatArray, from: Int, to: Int): Float {
        val a = from.coerceIn(0, x.size); val b = to.coerceIn(a, x.size)
        if (b <= a) return 0f
        var s = 0f; for (i in a until b) s += x[i]; return s / (b - a)
    }

    fun defaultLength(style: MixStyle) = when (style) {
        MixStyle.CUT -> 8; MixStyle.ECHO -> 16; MixStyle.BLEND -> 64; else -> 32
    }

    /** Picks the out point of A, the in point of B, key shift and length — the "auto mix". */
    fun plan(a: TrackAnalysis, b: TrackAnalysis, style: MixStyle = MixStyle.SMOOTH, keep: RemixPlan? = null): RemixPlan {
        val probe = MixTiming(a, b, RemixPlan(0, 0, 4))
        var len = keep?.lengthBeats ?: defaultLength(style)
        if (!probe.synced && keep == null && style != MixStyle.CUT) len = min(len, 16)
        len = min(len, max(4, a.beats / 3))

        // A: a bar-aligned phrase in the last part of the song with little singing during the overlap
        val phraseA = 16
        var bestOut = -1; var bestScore = Double.NEGATIVE_INFINITY
        var i = a.downbeat
        while (i + len < a.beats - 1) {
            val pos = i.toDouble() / a.beats
            if (pos >= 0.45) {
                val voc = mean(a.beatVocal, i, i + len)
                val nrg = mean(a.beatEnergy, i, i + len)
                val after = mean(a.beatEnergy, i + len, a.beats)
                var s = -voc * 3.0 + pos * 1.2 + (if ((i - a.downbeat) % phraseA == 0) 0.6 else 0.0) - abs(nrg - 0.6) * 0.5
                if (after < 0.25) s += 0.4   // the song really winds down after the window
                if (s > bestScore) { bestScore = s; bestOut = i }
            }
            i += 4
        }
        if (bestOut < 0) bestOut = max(a.downbeat, ((a.beats - len - 2) / 4) * 4 + a.downbeat).coerceAtLeast(0)

        // B: an early bar-aligned point whose overlap is instrumental and whose vocals start right after
        val bScale = probe.octave
        val bBeats = (b.beats * bScale).toInt()
        fun bv(from: Int, to: Int) = mean(b.beatVocal, (from / bScale).toInt(), (to / bScale).toInt())
        val bDown = (b.downbeat * bScale).toInt()
        var bestIn = bDown; bestScore = Double.NEGATIVE_INFINITY
        var j = bDown
        while (j + len < bBeats * 0.4) {
            val s = -bv(j, j + len) * 3.0 + bv(j + len, j + len + 16) * 1.0 - j.toDouble() / bBeats * 1.5 +
                (if ((j - bDown) % 16 == 0) 0.4 else 0.0)
            if (s > bestScore) { bestScore = s; bestIn = j }
            j += 4
        }

        val base = keep ?: RemixPlan(bestOut, bestIn, len, style)
        return base.copy(
            outBeat = bestOut, inBeat = bestIn, lengthBeats = len, style = style,
            shiftB = if (keep == null) Keys.bestShift(a, b) else base.shiftB,
        )
    }
}
