package com.arios.player.playback

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.arios.player.data.Song
import com.arios.player.lyrics.EmbeddedLyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Volume normalisation: uses the file's ReplayGain tag when present, otherwise measures the
 * song's loudness once (decodes up to ~2.5 min) and caches the result.
 */
class Loudness(private val context: Context) {
    private val file = File(context.filesDir, "loudness.json")
    private val cache = HashMap<String, Float>()

    init { runCatching { val o = JSONObject(file.readText()); o.keys().forEach { cache[it] = o.getDouble(it).toFloat() } } }

    private fun persist() = runCatching {
        file.writeText(JSONObject().apply { synchronized(cache) { cache.forEach { (k, v) -> put(k, v.toDouble()) } } }.toString())
    }

    fun cachedGainDb(song: Song): Float? = synchronized(cache) { cache[song.key] }

    /** Linear volume factor (≤ 1) for [song]; null if not known yet. */
    fun factor(song: Song): Float? = cachedGainDb(song)?.let { 10f.pow(it.coerceAtMost(0f) / 20f) }

    suspend fun ensure(song: Song): Float = withContext(Dispatchers.Default) {
        cachedGainDb(song)?.let { return@withContext it }
        val gain = EmbeddedLyrics.replayGain(context, song.uri)?.let { it - 6f } // RG targets 89 dB; we target a bit quieter so we only ever attenuate
            ?: measure(song)?.let { TARGET_DB - it }
            ?: 0f
        val g = gain.coerceIn(-14f, 0f)
        synchronized(cache) { cache[song.key] = g }
        persist()
        g
    }

    /** Average loudness (dBFS, gated RMS) of the first ~150 s. */
    private fun measure(song: Song): Float? = runCatching {
        val ex = MediaExtractor()
        ex.setDataSource(context, song.uri, null)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        val info = MediaCodec.BufferInfo()
        val blocks = ArrayList<Double>()
        var sum = 0.0; var count = 0
        val sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        val blockSize = sr * ch * 2 / 5 // 400 ms
        var inputDone = false; var outDone = false
        val limitUs = 150_000_000L
        while (!outDone) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val buf = codec.getInputBuffer(i)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0 || ex.sampleTime > limitUs) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o >= 0) {
                val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                buf.position(info.offset); buf.limit(info.offset + info.size)
                val sb = buf.asShortBuffer()
                while (sb.hasRemaining()) {
                    val v = sb.get() / 32768.0
                    sum += v * v; count++
                    if (count >= blockSize) { blocks += sum / count; sum = 0.0; count = 0 }
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
            }
        }
        codec.stop(); codec.release(); ex.release()
        if (blocks.isEmpty()) return null
        // gate out silence, then average the louder half (close to how humans judge loudness)
        val loud = blocks.filter { it > 1e-5 }.sortedDescending()
        if (loud.isEmpty()) return null
        val top = loud.take((loud.size / 2).coerceAtLeast(1))
        (10 * log10(top.average())).toFloat().let { if (it.isNaN()) null else it }
    }.getOrNull()

    companion object { const val TARGET_DB = -17f }
}

@Suppress("unused") private fun rms(x: Double) = sqrt(x)
