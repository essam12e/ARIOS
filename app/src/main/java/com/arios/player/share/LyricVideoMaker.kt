package com.arios.player.share

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Builds a short MP4 (H.264 + AAC): the lyric card animating word by word, with the matching
 * slice of the song as the soundtrack.
 */
class LyricVideoMaker(private val context: Context) {
    private class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)

    suspend fun make(
        songUri: Uri, spec: CardSpec, startMs: Long, endMs: Long, out: File, onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.Default) {
        val durMs = (endMs - startMs).coerceIn(2000, 30_000)
        val (audioFormat, audioSamples) = encodeAudio(songUri, startMs, startMs + durMs) { onProgress(it * 0.3f) }
        val renderer = LyricCardRenderer(context)
        val w = 720; val h = if (spec.aspect == CardAspect.STORY) 1280 else 720
        val fps = 30
        val frames = (durMs * fps / 1000).toInt()

        val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = enc.createInputSurface()
        enc.start()

        out.delete()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrack = -1; var audioTrack = -1; var started = false
        val info = MediaCodec.BufferInfo()
        var written = 0

        fun startMuxer(videoFmt: MediaFormat) {
            videoTrack = muxer.addTrack(videoFmt)
            if (audioFormat != null) audioTrack = muxer.addTrack(audioFormat)
            muxer.start(); started = true
            if (audioTrack >= 0) audioSamples.forEach { s ->
                val bi = MediaCodec.BufferInfo().apply { set(0, s.data.size, s.ptsUs, s.flags) }
                muxer.writeSampleData(audioTrack, ByteBuffer.wrap(s.data), bi)
            }
        }

        fun drain(endOfStream: Boolean, waitFor: Int) {
            val t0 = System.currentTimeMillis()
            while (true) {
                val idx = enc.dequeueOutputBuffer(info, 5_000)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream && (written >= waitFor || System.currentTimeMillis() - t0 > 300)) return
                        if (endOfStream && System.currentTimeMillis() - t0 > 3000) return
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> startMuxer(enc.outputFormat)
                    idx >= 0 -> {
                        val buf = enc.getOutputBuffer(idx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && started) {
                            // frames rendered faster than real time → assign exact timestamps ourselves
                            info.presentationTimeUs = written * 1_000_000L / fps
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            muxer.writeSampleData(videoTrack, buf, info)
                            written++
                        }
                        enc.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        try {
            for (f in 0 until frames) {
                coroutineContext.ensureActive()
                val tMs = startMs + f * 1000L / fps
                val canvas = surface.lockHardwareCanvas()
                try { renderer.draw(canvas, spec, w, h, tMs, f / frames.toFloat()) } finally { surface.unlockCanvasAndPost(canvas) }
                drain(false, f - 1)
                onProgress(0.3f + 0.7f * f / frames)
            }
            enc.signalEndOfInputStream()
            drain(true, frames)
        } finally {
            runCatching { enc.stop() }; enc.release(); surface.release()
            runCatching { if (started) muxer.stop() }; muxer.release()
        }
        onProgress(1f)
        out
    }

    /** Decodes [startMs, endMs) of the song to PCM (with fades) and re-encodes it as AAC. */
    private suspend fun encodeAudio(uri: Uri, startMs: Long, endMs: Long, onProgress: (Float) -> Unit): Pair<MediaFormat?, List<Sample>> {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return null to emptyList()
            ex.selectTrack(track)
            val inFmt = ex.getTrackFormat(track)
            ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val dec = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
            dec.configure(inFmt, null, null, 0); dec.start()
            var sr = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val pcm = java.io.ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inDone = false; var outDone = false
            while (!outDone) {
                coroutineContext.ensureActive()
                if (!inDone) {
                    val i = dec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                        if (n < 0 || ex.sampleTime > endMs * 1000 + 200_000) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    sr = dec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = dec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val buf = dec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val frameBytes = 2 * ch
                    val frameCount = info.size / frameBytes
                    val bytes = ByteArray(info.size); buf.get(bytes)
                    for (f in 0 until frameCount) {
                        val tUs = info.presentationTimeUs + f * 1_000_000L / sr
                        if (tUs < startMs * 1000 || tUs >= endMs * 1000) continue
                        // keep at most 2 channels
                        val take = minOf(ch, 2)
                        pcm.write(bytes, f * frameBytes, take * 2)
                    }
                    dec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    onProgress(((info.presentationTimeUs / 1000f - startMs) / (endMs - startMs)).coerceIn(0f, 1f) * 0.5f)
                }
            }
            dec.stop(); dec.release()
            val outCh = minOf(ch, 2)
            val data = pcm.toByteArray()
            applyFades(data, sr, outCh)
            return encodeAac(data, sr, outCh) { onProgress(0.5f + it * 0.5f) }
        } catch (e: Exception) {
            return null to emptyList() // video without sound rather than no video
        } finally { ex.release() }
    }

    private fun applyFades(pcm: ByteArray, sr: Int, ch: Int) {
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.nativeOrder())
        val frames = pcm.size / (2 * ch)
        val fade = (sr * 0.35).toInt()
        for (f in 0 until frames) {
            val g = when { f < fade -> f / fade.toFloat(); f > frames - fade -> (frames - f) / fade.toFloat(); else -> 1f }
            if (g >= 1f) continue
            for (c in 0 until ch) {
                val pos = (f * ch + c) * 2
                bb.putShort(pos, (bb.getShort(pos) * g).toInt().toShort())
            }
        }
    }

    private fun encodeAac(pcm: ByteArray, sr: Int, ch: Int, onProgress: (Float) -> Unit): Pair<MediaFormat?, List<Sample>> {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, ch).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); enc.start()
        val samples = ArrayList<Sample>()
        var outFmt: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        var pos = 0; var inDone = false; var outDone = false
        val bytesPerFrame = 2 * ch
        while (!outDone) {
            if (!inDone) {
                val i = enc.dequeueInputBuffer(5_000)
                if (i >= 0) {
                    val buf = enc.getInputBuffer(i)!!
                    val n = minOf(buf.capacity() / bytesPerFrame * bytesPerFrame, pcm.size - pos)
                    val pts = (pos / bytesPerFrame).toLong() * 1_000_000L / sr
                    if (n <= 0) { enc.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { buf.clear(); buf.put(pcm, pos, n); enc.queueInputBuffer(i, 0, n, pts, 0); pos += n }
                    onProgress(pos / pcm.size.toFloat().coerceAtLeast(1f))
                }
            }
            val o = enc.dequeueOutputBuffer(info, 5_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outFmt = enc.outputFormat
            else if (o >= 0) {
                val buf = enc.getOutputBuffer(o)!!
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val d = ByteArray(info.size); buf.position(info.offset); buf.get(d)
                    samples += Sample(d, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
                }
                enc.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
            }
        }
        enc.stop(); enc.release()
        return outFmt to samples
    }
}
