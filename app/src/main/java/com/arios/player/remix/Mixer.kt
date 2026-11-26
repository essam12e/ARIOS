package com.arios.player.remix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** RBJ biquad, stereo, coefficients can change every block. */
class Biquad {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
    private val z1 = DoubleArray(2); private val z2 = DoubleArray(2)

    fun lowPass(f: Double, q: Double = 0.707) = set(f, q, true)
    fun highPass(f: Double, q: Double = 0.707) = set(f, q, false)

    private fun set(f: Double, q: Double, low: Boolean) {
        val w = 2 * PI * f.coerceIn(10.0, StereoPcm.RATE * 0.45) / StereoPcm.RATE
        val alpha = sin(w) / (2 * q); val c = cos(w); val a0 = 1 + alpha
        if (low) { b0 = (1 - c) / 2 / a0; b1 = (1 - c) / a0; b2 = b0 }
        else { b0 = (1 + c) / 2 / a0; b1 = -(1 + c) / a0; b2 = b0 }
        a1 = -2 * c / a0; a2 = (1 - alpha) / a0
    }

    fun process(x: Float, ch: Int): Float {
        val y = b0 * x + z1[ch]
        z1[ch] = b1 * x - a1 * y + z2[ch]
        z2[ch] = b2 * x - a2 * y
        return y.toFloat()
    }
}

/**
 * Renders a remix: [A intro] → [transition] → [B rest]. Output is produced in chunks so a whole
 * remix never sits in memory as floats.
 */
class Mixer(
    private val a: StereoPcm, private val b: StereoPcm,
    private val ta: TrackAnalysis, private val tb: TrackAnalysis,
    val plan: RemixPlan,
) {
    val timing = MixTiming(ta, tb, plan)
    private val sr = StereoPcm.RATE.toDouble()
    private val gA = db(plan.gainA)
    private val gB = db(plan.gainB) * if (plan.autoLevel && tb.loudness > 0 && ta.loudness > 0)
        (ta.loudness / tb.loudness).coerceIn(0.5f, 2f) else 1f

    private fun db(v: Float) = 10f.pow(v / 20f)

    val introFrames = (timing.introLen * sr).toInt()
    val transFrames = (timing.duration * sr).toInt()
    val outroFrames = (timing.outroLen * sr).toInt()
    val totalFrames get() = introFrames + transFrames + outroFrames

    /** Output frame where the transition starts (for the UI and previews). */
    val transitionStart get() = introFrames

    /** Calls [sink] with interleaved stereo float chunks for output frames [from, to). */
    fun render(from: Int, to: Int, sink: (FloatArray) -> Unit, progress: (Float) -> Unit = {}) {
        val chunk = 8192
        var pos = from
        // ---- intro: plain A (with optional fade in)
        val aStartF = (plan.aStart * sr).toInt()
        val fadeIn = (plan.aFadeIn * sr).toInt()
        while (pos < min(to, introFrames)) {
            val n = min(chunk, min(to, introFrames) - pos)
            val buf = FloatArray(n * 2)
            for (i in 0 until n) {
                val o = pos + i
                val g = gA * (if (fadeIn > 0 && o < fadeIn) (o.toFloat() / fadeIn).let { it * it } else 1f)
                buf[2 * i] = limit(a.l(aStartF + o) * g); buf[2 * i + 1] = limit(a.r(aStartF + o) * g)
            }
            sink(buf); pos += n; progress((pos - from).toFloat() / (to - from))
        }
        // ---- transition
        if (pos < min(to, introFrames + transFrames)) {
            val t0 = pos - introFrames; val t1 = min(to, introFrames + transFrames) - introFrames
            val mixed = transition(t0, t1)
            var k = 0
            while (k < mixed.size) {
                val n = min(chunk * 2, mixed.size - k)
                sink(mixed.copyOfRange(k, k + n)); k += n
            }
            pos += t1 - t0; progress((pos - from).toFloat() / (to - from))
        }
        // ---- outro: plain B (with fade out at the end)
        val bStartF = (timing.srcB(timing.duration) * sr).toInt()
        val fadeOut = (plan.bFadeOut * sr).toInt()
        val endO = introFrames + transFrames + outroFrames
        while (pos < min(to, endO)) {
            val n = min(chunk, min(to, endO) - pos)
            val buf = FloatArray(n * 2)
            for (i in 0 until n) {
                val o = pos + i - introFrames - transFrames
                val left = outroFrames - o
                val g = gB * (if (fadeOut > 0 && left < fadeOut) (left.toFloat() / fadeOut).coerceIn(0f, 1f).let { it * it } else 1f)
                buf[2 * i] = limit(b.l(bStartF + o) * g); buf[2 * i + 1] = limit(b.r(bStartF + o) * g)
            }
            sink(buf); pos += n; progress((pos - from).toFloat() / (to - from))
        }
    }

    /** Transition frames [t0, t1) relative to its start. */
    private fun transition(t0: Int, t1: Int): FloatArray {
        val n = transFrames
        // render the whole transition for each deck (WSOLA needs continuity), then slice
        val aw = if (timing.synced) Wsola.render(a, n) { o -> timing.srcA(o / sr) * sr } else slice(a, (timing.tA0 * sr).toInt(), n)
        val bw = if (timing.synced) Wsola.render(b, n) { o -> timing.srcB(o / sr) * sr } else slice(b, (timing.tB0 * sr).toInt(), n)
        val out = FloatArray(n * 2)
        val lpA = Biquad(); val lpB = Biquad(); val fxA = Biquad(); val fxB = Biquad()
        lpA.lowPass(160.0); lpB.lowPass(160.0)
        val beat = 60.0 / ta.bpm * sr
        val echoLen = (beat * 0.75).toInt().coerceAtLeast(1)
        val echo = FloatArray(echoLen * 2); var ei = 0
        val swapRamp = (beat / n).coerceAtMost(0.2)   // bass swap takes one beat
        val block = 128
        var i = 0
        while (i < n) {
            val x = (i + block / 2).toDouble() / n
            // per-block filter settings
            when (plan.style) {
                MixStyle.FILTER -> {
                    fxA.highPass(20.0 * (3000.0 / 20.0).pow(x))
                    fxB.lowPass(250.0 * (20000.0 / 250.0).pow(min(1.0, x / 0.7)))
                }
                else -> {}
            }
            for (k in i until min(n, i + block)) {
                val xx = k.toDouble() / n
                val (ga, gb) = gains(xx, k)
                // bass swap: the incoming bass waits, then the two basses trade places on a beat
                val kill = if (!plan.bassSwap) 0.0 to 0.0 else {
                    val mid = if (plan.style == MixStyle.BLEND) 0.6 else 0.5
                    val sw = ((xx - (mid - swapRamp / 2)) / swapRamp).coerceIn(0.0, 1.0)
                    sw to (1 - sw)
                }
                for (c in 0..1) {
                    var av = aw[2 * k + c] * gA
                    var bv = bw[2 * k + c] * gB
                    val lowA = lpA.process(av, c); val lowB = lpB.process(bv, c)
                    av -= (lowA * kill.first).toFloat(); bv -= (lowB * kill.second).toFloat()
                    if (plan.style == MixStyle.FILTER) { av = fxA.process(av, c); bv = fxB.process(bv, c) }
                    var s = av * ga.toFloat() + bv * gb.toFloat()
                    if (plan.style == MixStyle.ECHO) {
                        // feed A into the echo only during the beat before the drop, then let it ring out
                        val feed = if (xx in (0.5 - beat / n)..0.5) av else 0f
                        val e = echo[2 * ei + c]
                        echo[2 * ei + c] = feed + e * 0.55f
                        if (xx >= 0.5) s += e * 0.8f
                    }
                    out[2 * k + c] = limit(s)
                }
                if (plan.style == MixStyle.ECHO) ei = (ei + 1) % echoLen
            }
            i += block
        }
        return out.copyOfRange(t0 * 2, t1 * 2)
    }

    private fun slice(src: StereoPcm, start: Int, n: Int) = FloatArray(n * 2) { val f = start + it / 2; if (it % 2 == 0) src.l(f) else src.r(f) }

    private fun gains(x: Double, @Suppress("UNUSED_PARAMETER") frame: Int) =
        gains(plan, x, 60.0 / ta.bpm * sr / transFrames, 0.010 * sr / transFrames)

    companion object {
    /** Volume of A and B at position x (0..1) of the transition; [beat] and [ramp] are fractions of it. */
    fun gains(plan: RemixPlan, x: Double, beat: Double, ramp: Double): Pair<Double, Double> {
        fun curveIn(t: Double): Double {
            val c = t.coerceIn(0.0, 1.0)
            return when (plan.curve) { FadeCurve.LINEAR -> c; FadeCurve.POWER -> sin(c * PI / 2); FadeCurve.S -> c * c * (3 - 2 * c) }
        }
        fun curveOut(t: Double) = curveIn(1 - t)
        return when (plan.style) {
            MixStyle.SMOOTH -> curveOut(x) to curveIn(x)
            MixStyle.FILTER -> curveOut(((x - 0.75) / 0.25)) to curveIn(x / 0.25)
            MixStyle.ECHO -> (if (x < 0.5) 1.0 else 0.0) to curveIn((x - 0.5) / beat)
            MixStyle.CUT -> (1 - ((x - 0.5) / ramp + 0.5).coerceIn(0.0, 1.0)) to ((x - 0.5) / ramp + 0.5).coerceIn(0.0, 1.0)
            MixStyle.BLEND -> curveOut((x - 0.6) / 0.4) to curveIn(x / 0.3)
        }
    }
    }

    private fun limit(s: Float): Float {
        val t = 0.89f
        val a = abs(s)
        return if (a <= t) s else Math.copySign(t + (1 - t) * tanh((a - t) / (1 - t)), s)
    }
}
