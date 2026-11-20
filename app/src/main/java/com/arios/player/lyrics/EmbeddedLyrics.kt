package com.arios.player.lyrics

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.Charset

/**
 * Reads lyrics stored inside the audio file itself:
 * MP3 (ID3v2 USLT / SYLT), FLAC & OGG/Opus (Vorbis LYRICS comment) and M4A/MP4 (©lyr).
 */
object EmbeddedLyrics {
    /** REPLAYGAIN_TRACK_GAIN found by the last parse (dB), see [replayGain]. */
    private val lastGain = ThreadLocal<Float?>()

    /** ReplayGain track gain stored in the file's tags, in dB (null if absent). */
    fun replayGain(context: Context, uri: Uri): Float? {
        lastGain.set(null)
        read(context, uri)
        return lastGain.get()
    }

    private fun noteGain(key: String, value: String) {
        if (key.equals("REPLAYGAIN_TRACK_GAIN", true) && lastGain.get() == null)
            lastGain.set(value.replace("dB", "", true).trim().replace(',', '.').toFloatOrNull())
    }

    fun read(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch -> readChannel(ch) }
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun readChannel(ch: FileChannel): String? {
        val head = ByteBuffer.allocate(12)
        ch.position(0); ch.read(head); head.flip()
        if (head.remaining() < 8) return null
        val b = ByteArray(head.remaining()).also { head.get(it) }
        return when {
            b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte() -> id3(ch)
            String(b, 0, 4, Charsets.ISO_8859_1) == "fLaC" -> flac(ch)
            String(b, 0, 4, Charsets.ISO_8859_1) == "OggS" -> ogg(ch)
            String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> mp4(ch)
            else -> null
        }
    }

    private fun readBytes(ch: FileChannel, pos: Long, len: Int): ByteArray {
        val buf = ByteBuffer.allocate(len)
        ch.position(pos)
        while (buf.hasRemaining()) if (ch.read(buf) < 0) break
        return buf.array().copyOf(buf.position())
    }

    // ---------------------------------------------------------------- ID3v2
    private fun syncsafe(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0x7f) shl 21) or ((b[o + 1].toInt() and 0x7f) shl 14) or ((b[o + 2].toInt() and 0x7f) shl 7) or (b[o + 3].toInt() and 0x7f)
    private fun int32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)

    private fun charset(enc: Int): Charset = when (enc) {
        1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1
    }

    /** Index just after a terminator for [enc] starting at [from]. */
    private fun skipTerminated(b: ByteArray, from: Int, end: Int, enc: Int): Int {
        var i = from
        if (enc == 1 || enc == 2) {
            while (i + 1 < end) { if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) return i + 2; i += 2 }
        } else {
            while (i < end) { if (b[i].toInt() == 0) return i + 1; i++ }
        }
        return end
    }

    private fun id3(ch: FileChannel): String? {
        val h = readBytes(ch, 0, 10)
        val major = h[3].toInt()
        if (major < 3) return null
        val flags = h[5].toInt()
        val size = syncsafe(h, 6).coerceAtMost(32 * 1024 * 1024)
        var tag = readBytes(ch, 10, size)
        if (flags and 0x80 != 0 && major == 3) tag = unsync(tag)
        var p = 0
        if (flags and 0x40 != 0) p = if (major == 4) syncsafe(tag, 0) else int32(tag, 0) + 4
        var unsynced: String? = null
        var synced: String? = null
        while (p + 10 <= tag.size) {
            val id = String(tag, p, 4, Charsets.ISO_8859_1)
            if (id[0].code == 0) break
            val fsize = if (major == 4) syncsafe(tag, p + 4) else int32(tag, p + 4)
            val start = p + 10
            val end = (start + fsize).coerceAtMost(tag.size)
            if (fsize <= 0 || start >= tag.size) break
            when (id) {
                "TXXX" -> if (end - start > 2) {
                    val enc = tag[start].toInt()
                    val descEnd = skipTerminated(tag, start + 1, end, enc)
                    val termLen = if (enc == 1 || enc == 2) 2 else 1
                    val desc = String(tag, start + 1, (descEnd - termLen - start - 1).coerceAtLeast(0), charset(enc)).trim('\uFEFF', '\u0000')
                    val value = String(tag, descEnd, (end - descEnd).coerceAtLeast(0), charset(enc)).trim('\uFEFF', '\u0000', ' ')
                    noteGain(desc, value)
                }
                "USLT" -> if (unsynced == null && end - start > 5) {
                    val enc = tag[start].toInt()
                    val textStart = skipTerminated(tag, start + 4, end, enc)
                    unsynced = String(tag, textStart, end - textStart, charset(enc)).trim('\u0000', '﻿', ' ', '\n')
                }
                "SYLT" -> if (synced == null && end - start > 7) synced = sylt(tag, start, end)
            }
            p = end
        }
        return synced?.takeIf { it.isNotBlank() } ?: unsynced
    }

    private fun unsync(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    /** SYLT → LRC text (only millisecond timestamps). */
    private fun sylt(b: ByteArray, start: Int, end: Int): String? {
        val enc = b[start].toInt()
        val format = b[start + 4].toInt()
        if (format != 2) return null
        var p = skipTerminated(b, start + 6, end, enc)
        val sb = StringBuilder()
        while (p < end) {
            val te = skipTerminated(b, p, end, enc)
            val termLen = if (enc == 1 || enc == 2) 2 else 1
            val text = String(b, p, (te - termLen - p).coerceAtLeast(0), charset(enc)).trim('﻿', '\n', '\r')
            if (te + 4 > end) break
            val ms = int32(b, te).toLong()
            sb.append(LrcWriter.stamp(ms)).append(text).append('\n')
            p = te + 4
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- Vorbis comments
    private fun vorbisComments(b: ByteArray, offset: Int): String? {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var p = offset
        val vendor = bb.getInt(p); p += 4 + vendor
        if (p + 4 > b.size) return null
        val n = bb.getInt(p); p += 4
        var plain: String? = null
        var synced: String? = null
        repeat(n) {
            if (p + 4 > b.size) return synced ?: plain
            val len = bb.getInt(p); p += 4
            if (len < 0 || p + len > b.size) return synced ?: plain
            val kv = String(b, p, len, Charsets.UTF_8); p += len
            val key = kv.substringBefore('=').uppercase()
            val v = kv.substringAfter('=')
            noteGain(key, v)
            if (key == "SYNCEDLYRICS" || (key in setOf("LYRICS", "UNSYNCEDLYRICS") && LrcParser.looksSynced(v))) synced = synced ?: v
            if (key in setOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS") && plain == null) plain = v
        }
        return synced ?: plain
    }

    private fun flac(ch: FileChannel): String? {
        var pos = 4L
        repeat(64) {
            val h = readBytes(ch, pos, 4)
            if (h.size < 4) return null
            val last = h[0].toInt() and 0x80 != 0
            val type = h[0].toInt() and 0x7f
            val len = ((h[1].toInt() and 0xff) shl 16) or ((h[2].toInt() and 0xff) shl 8) or (h[3].toInt() and 0xff)
            if (type == 4) return vorbisComments(readBytes(ch, pos + 4, len), 0)
            pos += 4 + len
            if (last) return null
        }
        return null
    }

    private fun ogg(ch: FileChannel): String? {
        // Re-assemble the first packets (comment header may span pages)
        val out = java.io.ByteArrayOutputStream()
        var pos = 0L
        repeat(40) {
            val h = readBytes(ch, pos, 27)
            if (h.size < 27 || String(h, 0, 4, Charsets.ISO_8859_1) != "OggS") return@repeat
            val segs = h[26].toInt() and 0xff
            val table = readBytes(ch, pos + 27, segs)
            val body = table.sumOf { it.toInt() and 0xff }
            out.write(readBytes(ch, pos + 27 + segs, body))
            pos += 27 + segs + body
            if (out.size() > 4 * 1024 * 1024) return@repeat
        }
        val data = out.toByteArray()
        val markers = listOf("\u0003vorbis".toByteArray(Charsets.ISO_8859_1), "OpusTags".toByteArray(Charsets.ISO_8859_1))
        for (m in markers) {
            val i = indexOf(data, m)
            if (i >= 0) return vorbisComments(data, i + m.size)
        }
        return null
    }

    private fun indexOf(data: ByteArray, m: ByteArray): Int {
        outer@ for (i in 0..data.size - m.size) {
            for (j in m.indices) if (data[i + j] != m[j]) continue@outer
            return i
        }
        return -1
    }

    // ---------------------------------------------------------------- MP4 / M4A
    private fun mp4(ch: FileChannel): String? {
        val path = listOf("moov", "udta", "meta", "ilst", "©lyr", "data")
        return findAtom(ch, 0, ch.size(), path, 0)
    }

    private fun findAtom(ch: FileChannel, start: Long, end: Long, path: List<String>, depth: Int): String? {
        var pos = start
        while (pos + 8 <= end) {
            val h = readBytes(ch, pos, 16)
            if (h.size < 8) return null
            var size = int32(h, 0).toLong() and 0xffffffffL
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var header = 8L
            if (size == 1L && h.size >= 16) { size = ByteBuffer.wrap(h, 8, 8).long; header = 16 }
            if (size == 0L) size = end - pos
            if (size < header) return null
            if (type == path[depth]) {
                if (depth == path.lastIndex) {
                    val payload = readBytes(ch, pos + header + 8, (size - header - 8).toInt().coerceIn(0, 4 * 1024 * 1024))
                    return String(payload, Charsets.UTF_8)
                }
                val childStart = pos + header + if (type == "meta") 4 else 0
                return findAtom(ch, childStart, pos + size, path, depth + 1)
            }
            pos += size
        }
        return null
    }
}
