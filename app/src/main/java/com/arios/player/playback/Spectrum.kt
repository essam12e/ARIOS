package com.arios.player.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-time spectrum of what ARIOS is playing, read from the decoded PCM inside ExoPlayer
 * (no microphone / RECORD_AUDIO permission needed). UI reads [levels] every frame.
 */
@UnstableApi
object Spectrum : TeeAudioProcessor.AudioBufferSink {
    const val BANDS = 32
    private const val N = 1024
    private val window = FloatArray(N) { (0.5 - 0.5 * cos(2 * Math.PI * it / (N - 1))).toFloat() }
    private val ring = FloatArray(N)
    private var ringPos = 0
    private var filled = 0
    private var sampleRate = 44100
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val out = FloatArray(BANDS)

    /** Smoothed band levels 0..1 (written on the audio thread, read on the UI thread). */
    @Volatile var levels: FloatArray = FloatArray(BANDS)
        private set
    /** Overall loudness 0..1 – drives glow/pulse effects. */
    @Volatile var energy: Float = 0f
        private set
    @Volatile var lastUpdate: Long = 0
        private set

    val processor: TeeAudioProcessor by lazy { TeeAudioProcessor(this) }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = sampleRateHz; channels = channelCount.coerceAtLeast(1); this.encoding = encoding
        filled = 0; ringPos = 0
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val frames = b.remaining() / (2 * channels)
                for (f in 0 until frames) {
                    var s = 0f
                    for (c in 0 until channels) s += b.short.toFloat()
                    push(s / channels / 32768f)
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                val frames = b.remaining() / (4 * channels)
                for (f in 0 until frames) {
                    var s = 0f
                    for (c in 0 until channels) s += b.float
                    push(s / channels)
                }
            }
            else -> return
        }
    }

    private var sinceFft = 0
    private fun push(v: Float) {
        ring[ringPos] = v
        ringPos = (ringPos + 1) % N
        if (filled < N) filled++
        if (++sinceFft >= N / 2 && filled == N) { sinceFft = 0; analyse() }
    }

    private fun analyse() {
        var rms = 0f
        for (i in 0 until N) {
            val v = ring[(ringPos + i) % N]
            rms += v * v
            re[i] = v * window[i]; im[i] = 0f
        }
        fft()
        val nyq = sampleRate / 2f
        val minHz = 40.0; val maxHz = minOf(16000.0, nyq.toDouble())
        for (band in 0 until BANDS) {
            val f0 = minHz * (maxHz / minHz).pow(band / BANDS.toDouble())
            val f1 = minHz * (maxHz / minHz).pow((band + 1) / BANDS.toDouble())
            val i0 = (f0 / nyq * (N / 2)).toInt().coerceIn(1, N / 2 - 1)
            val i1 = max(i0 + 1, (f1 / nyq * (N / 2)).toInt().coerceIn(1, N / 2))
            var m = 0f
            for (i in i0 until i1) m = max(m, sqrt(re[i] * re[i] + im[i] * im[i]))
            val db = 20 * log10(m + 1e-6f)
            val v = ((db + 10f) / 55f).coerceIn(0f, 1f) // ~ -65..-10 dB → 0..1
            out[band] = if (v > out[band]) out[band] * 0.35f + v * 0.65f else out[band] * 0.82f + v * 0.18f
        }
        levels = out.copyOf()
        val e = (sqrt(rms / N) * 3.2f).coerceIn(0f, 1f)
        energy = if (e > energy) energy * 0.4f + e * 0.6f else energy * 0.85f + e * 0.15f
        lastUpdate = System.currentTimeMillis()
    }

    /** In-place radix-2 FFT on [re]/[im]. */
    private fun fft() {
        var j = 0
        for (i in 1 until N) {
            var bit = N shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= N) {
            val ang = -2 * Math.PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < N) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar; im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar; im[i + k] += ai
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Test hook: freeze a spectrum (used to render screenshots). */
    internal fun setForTest(l: FloatArray, e: Float) { levels = l; energy = e; lastUpdate = Long.MAX_VALUE / 4 }

    /** Levels that decay to silence when nothing has been analysed recently (paused). */
    fun liveLevels(): FloatArray {
        val age = System.currentTimeMillis() - lastUpdate
        if (age < 250) return levels
        val k = (1f - (age - 250) / 600f).coerceIn(0f, 1f)
        return FloatArray(BANDS) { levels[it] * k }
    }
    fun liveEnergy(): Float {
        val age = System.currentTimeMillis() - lastUpdate
        return if (age < 250) energy else energy * (1f - (age - 250) / 600f).coerceIn(0f, 1f)
    }

    @Suppress("unused") private fun ln2(x: Double) = ln(x) / ln(2.0)
}
