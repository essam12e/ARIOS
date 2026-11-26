package com.arios.player.remix

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteOrder
import kotlin.math.min

object PcmDecoder {
    /** Longest track the remix engine accepts (memory). */
    const val MAX_SECONDS = 12 * 60

    /** Decodes [uri] to interleaved 16-bit stereo at 44.1 kHz. */
    fun decode(context: Context, uri: Uri, progress: (Float) -> Unit = {}): StereoPcm {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: error("no audio track")
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        require(durUs <= MAX_SECONDS * 1_000_000L) { "too long" }
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        var buf = ShortArray(((durUs / 1e6 + 2) * sr * 2).toInt().coerceAtLeast(1 shl 16)); var n = 0
        val info = MediaCodec.BufferInfo()
        var inDone = false; var outDone = false
        try {
            while (!outDone) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val s = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (s < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(i, 0, s, ex.sampleTime, 0); ex.advance(); if (durUs > 0) progress((ex.sampleTime.toFloat() / durUs).coerceIn(0f, 1f)) }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    sr = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                } else if (o >= 0) {
                    val sb = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).apply { position(info.offset); limit(info.offset + info.size) }.asShortBuffer()
                    while (sb.remaining() >= ch) {
                        val l = sb.get(); val r = if (ch > 1) sb.get() else l
                        repeat(ch - 2) { sb.get() }
                        if (n + 2 > buf.size) buf = buf.copyOf(buf.size + buf.size / 2)
                        buf[n++] = l; buf[n++] = r
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
        } finally { runCatching { codec.stop() }; codec.release(); ex.release() }
        val pcm = buf.copyOf(n)
        return StereoPcm(if (sr == StereoPcm.RATE) pcm else resample(pcm, sr, StereoPcm.RATE))
    }

    private fun resample(x: ShortArray, from: Int, to: Int): ShortArray {
        val frames = x.size / 2
        val outFrames = (frames.toLong() * to / from).toInt()
        val out = ShortArray(outFrames * 2)
        val step = from.toDouble() / to
        for (i in 0 until outFrames) {
            val p = i * step; val i0 = p.toInt(); val f = p - i0
            for (c in 0..1) {
                val a = x.getOrElse(2 * i0 + c) { 0 }.toDouble(); val b = x.getOrElse(2 * (i0 + 1) + c) { 0 }.toDouble()
                out[2 * i + c] = (a + (b - a) * f).toInt().toShort()
            }
        }
        return out
    }
}

/** Streams float stereo chunks into an AAC .m4a file. */
class AacWriter(private val file: File, bitrate: Int = 256_000) {
    private val codec: MediaCodec
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var muxing = false
    private var framesIn = 0L
    private val info = MediaCodec.BufferInfo()

    init {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, StereoPcm.RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun write(samples: FloatArray) {
        var off = 0
        while (off < samples.size) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i >= 0) {
                val inBuf = codec.getInputBuffer(i)!!.order(ByteOrder.nativeOrder())
                inBuf.clear()
                val n = min((samples.size - off), inBuf.capacity() / 2) and 1.inv()
                val sb = inBuf.asShortBuffer()
                for (k in 0 until n) sb.put((samples[off + k].coerceIn(-1f, 1f) * 32767).toInt().toShort())
                val pts = framesIn * 1_000_000L / StereoPcm.RATE
                codec.queueInputBuffer(i, 0, n * 2, pts, 0)
                framesIn += n / 2; off += n
            }
            drain(false)
        }
    }

    fun finish() {
        var done = false
        while (!done) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i >= 0) { codec.queueInputBuffer(i, 0, 0, framesIn * 1_000_000L / StereoPcm.RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM); done = true }
            drain(false)
        }
        drain(true)
        runCatching { codec.stop() }; codec.release()
        if (muxing) muxer.stop()
        muxer.release()
    }

    private fun drain(eos: Boolean) {
        while (true) {
            val o = codec.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            when {
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!eos) return
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxing = true }
                o >= 0 -> {
                    val b = codec.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxing) { b.position(info.offset); b.limit(info.offset + info.size); muxer.writeSampleData(track, b, info) }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}

/** Plays rendered preview audio and reports the playhead (frames). */
class PreviewPlayer {
    @Volatile private var track: AudioTrack? = null
    @Volatile private var thread: Thread? = null
    @Volatile var startFrame = 0; private set

    val playing get() = track != null
    fun positionFrames(): Int = track?.let { runCatching { it.playbackHeadPosition }.getOrDefault(0) + startFrame } ?: -1

    fun play(samples: ShortArray, offsetFrames: Int = 0, onEnd: () -> Unit) {
        stop()
        startFrame = offsetFrames
        val minBuf = AudioTrack.getMinBufferSize(StereoPcm.RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(StereoPcm.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(minBuf, 1 shl 16)).setTransferMode(AudioTrack.MODE_STREAM).build()
        track = t
        t.play()
        thread = Thread {
            var off = 0
            while (off < samples.size && track === t) {
                val n = min(8192, samples.size - off)
                val w = t.write(samples, off, n)
                if (w <= 0) break
                off += w
            }
            // let the tail play out
            while (track === t && t.playbackHeadPosition < samples.size / 2) Thread.sleep(30)
            if (track === t) { stop(); onEnd() }
        }.apply { start() }
    }

    fun stop() {
        val t = track ?: return
        track = null
        runCatching { t.pause(); t.flush(); t.stop() }; t.release()
    }
}
