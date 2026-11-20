package com.arios.player.lyrics

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Real-audio check: a known voice track (centre) mixed into stereo music; the true start of every
 * line is known from the voice alone. Runs only when the local fixtures exist (not committed).
 */
class AutoSyncTest {
    private val lines = listOf(
        "إيش الفرق بين سبوتيفاي… وفلوفاي؟",
        "سبوتيفاي يبي منك حساب… واشتراك… وإنترنت.",
        "فلوفاي؟ ولا شي من هذا. لا حساب، لا اشتراك، ولا إعلان واحد.",
        "أغانيك اللي في جوالك… صار لها أخيراً مشغّل يليق فيها.",
        "شغّل أي أغنية… وشوف الكلمات تمشي مع الصوت، كلمة… بكلمة.",
        "عربي من اليمين لليسار، ومعها الترجمة.",
        "وإذا الكلمات مو موجودة؟ فلوفاي يدوّرها لك ويحفظها، وتشتغل حتى بدون نت.",
        "معادل صوت بإعدادات طرب وجهير. انتقال ناعم بين الأغاني. وضع السيارة.",
        "إحصائيات تعرف منها وش أكثر شي تسمعه.",
        "وتقدر تشارك كلمات أغنيتك المفضلة… فيديو بصوتها، ستوري جاهزة بضغطة.",
        "سبوتيفاي يشغّل لك موسيقاهم…",
        "فلوفاي يخلّي موسيقاك أنت… تحسها.",
        "فلوفاي. اسمع… وشوف كل كلمة.",
    )
    private val truth = listOf(0.0, 3.66, 7.78, 13.15, 17.45, 22.70, 25.88, 31.48, 37.07, 40.30, 45.56, 48.23, 51.57)

    private fun load(name: String, stereo: Boolean): AutoSync.Pcm? {
        val f = File("src/test/resources-local/$name")
        if (!f.exists()) return null
        val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return if (stereo) {
            val n = bb.remaining() / 2
            val l = FloatArray(n); val r = FloatArray(n)
            for (i in 0 until n) { l[i] = bb.get() / 32768f; r[i] = bb.get() / 32768f }
            AutoSync.Pcm(l, r, AutoSync.RATE)
        } else {
            val n = bb.remaining(); val l = FloatArray(n) { bb.get() / 32768f }
            AutoSync.Pcm(l, null, AutoSync.RATE)
        }
    }

    private fun run(name: String, stereo: Boolean): List<Double> {
        val pcm = load(name, stereo); assumeTrue(pcm != null)
        val lrc = AutoSync.syncPcm(pcm!!, lines)!!
        val l = LrcParser.parse(lrc, "t")!!
        val starts = l.lines.map { it.start / 1000.0 }
        val err = starts.zip(truth).map { (a, b) -> abs(a - b) }
        println("[$name] starts=" + starts.map { "%.2f".format(it) })
        println("[$name] errors=" + err.map { "%.2f".format(it) } + " mean=%.2f max=%.2f within1s=%d/%d".format(err.average(), err.max(), err.count { it <= 1.0 }, err.size))
        return err
    }

    @Test fun voiceOnly() { val e = run("voice.raw", false); assert(e.count { it <= 1.0 } >= 11 && e.average() < 0.5) }
    @Test fun realisticMix() { val e = run("mix_real.raw", true); assert(e.count { it <= 1.0 } >= 12 && e.average() < 0.4) }
    @Test fun hardMix() { val e = run("mix_hard.raw", true); assert(e.count { it <= 1.0 } >= 10 && e.average() < 0.7) }
}
