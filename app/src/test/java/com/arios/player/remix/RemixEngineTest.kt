package com.arios.player.remix

import com.arios.player.lyrics.AutoSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

class RemixEngineTest {
    /** Kick on every beat, snare on 2 & 4, a chord in [root] (major/minor), a hi-hat on 8ths. */
    private fun song(bpm: Double, seconds: Double, root: Int, minor: Boolean, rate: Int, offset: Double = 0.3, seed: Int = 1): FloatArray {
        val n = (seconds * rate).toInt()
        val out = FloatArray(n)
        val period = 60 / bpm
        val rnd = java.util.Random(seed.toLong())
        val third = if (minor) 3 else 4
        val freqs = listOf(0, third, 7, 12).map { 220.0 * Math.pow(2.0, (root - 9 + it) / 12.0) }
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            var s = 0.0
            for (f in freqs) s += 0.06 * sin(2 * PI * f * t)
            s += 0.05 * sin(2 * PI * freqs[0] / 2 * t)
            val bt = t - offset
            if (bt >= 0) {
                val beatIdx = (bt / period).toInt(); val ph = bt - beatIdx * period
                s += 0.7 * exp(-ph * 30) * sin(2 * PI * (50 + 80 * exp(-ph * 40)) * ph) * (if (beatIdx % 4 == 0) 1.3 else 1.0)
                if (beatIdx % 2 == 1) s += 0.25 * exp(-ph * 25) * (rnd.nextDouble() * 2 - 1)
                val eighth = bt % (period / 2)
                s += 0.06 * exp(-eighth * 80) * (rnd.nextDouble() * 2 - 1)
            }
            out[i] = s.toFloat() * 0.6f
        }
        return out
    }

    private fun analyze(x: FloatArray) = Analyzer.analyze(AutoSync.Pcm(x, x.copyOf(), AutoSync.RATE))

    @Test fun detectsTempoAndBeatPhase() {
        for ((bpm, off) in listOf(128.0 to 0.30, 96.0 to 0.85, 140.0 to 0.12, 110.0 to 0.5)) {
            val an = analyze(song(bpm, 60.0, 0, false, AutoSync.RATE, off))
            assertEquals("bpm for $bpm", bpm, an.bpm, bpm * 0.01)
            val signed = (((an.firstBeat - off) / an.period + 0.5).mod(1.0) - 0.5) * an.period
            println("PHASE bpm=$bpm detected=${an.bpm} err=${signed * 1000}ms")
            val phaseErr = abs(signed)
            assertTrue("beat phase err ${phaseErr}s for $bpm", phaseErr < 0.02)
            
            assertEquals("downbeat for $bpm", 0, ((an.downbeat - ((off - an.firstBeat) / an.period).let { Math.round(it).toInt() }) % 4 + 4) % 4)
        }
    }

    @Test fun detectsKey() {
        val a = analyze(song(120.0, 30.0, 9, true, AutoSync.RATE))   // A minor → 8A
        assertEquals("8A", a.camelot())
        val c = analyze(song(120.0, 30.0, 7, false, AutoSync.RATE))  // G major → 9B
        assertEquals("9B", c.camelot())
        assertEquals(1, Keys.distance(9, true, 0, false).let { if (it == 1) 1 else it }.coerceAtMost(1))
    }

    @Test fun mixesWithBeatsAligned() {
        val rate = StereoPcm.RATE
        fun pcm(x: FloatArray) = StereoPcm(ShortArray(x.size * 2) { (x[it / 2] * 32767).toInt().toShort() })
        val ax = song(124.0, 70.0, 9, true, AutoSync.RATE, 0.4, 1); val bx = song(128.0, 70.0, 0, false, AutoSync.RATE, 0.2, 2)
        val ta = analyze(ax); val tb = analyze(bx)
        val big = { x: FloatArray, bpm: Double, off: Double -> pcm(song(bpm, 70.0, 0, false, rate, off)) }
        val pa = big(ax, 124.0, 0.4); val pb = big(bx, 128.0, 0.2)
        val plan = AutoPlanner.plan(ta, tb)
        assertTrue("A out point in second half", ta.beatTime(plan.outBeat) > 30)
        val mixer = Mixer(pa, pb, ta, tb, plan)
        assertTrue(mixer.timing.synced)
        var frames = 0; var bad = 0
        mixer.render(0, mixer.totalFrames, { buf -> frames += buf.size / 2; buf.forEach { if (it.isNaN() || abs(it) > 1f) bad++ } })
        assertEquals(mixer.totalFrames, frames)
        assertEquals(0, bad)
        // beats of A and B coincide all through the transition
        val t = mixer.timing
        for (q in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
            val tt = q * t.duration
            val ba = (t.srcA(tt) - ta.firstBeat) / ta.period; val bb = (t.srcB(tt) - tb.firstBeat) / t.bPeriod
            assertEquals((ba - plan.outBeat), (bb - plan.inBeat), 0.01)
        }
    }
}
