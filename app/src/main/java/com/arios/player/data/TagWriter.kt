package com.arios.player.data

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes tags back into audio files.
 * MP3: rebuilds the ID3v2 tag (keeps every frame it does not change, e.g. lyrics).
 * FLAC: rebuilds VORBIS_COMMENT (+ PICTURE) metadata blocks.
 * Returns the new file bytes, or null when the format is not supported.
 */
object TagWriter {
    data class Cover(val bytes: ByteArray, val mime: String, val width: Int, val height: Int)

    fun write(original: ByteArray, e: TagEdit, cover: Cover?): ByteArray? = when {
        original.size > 4 && original[0] == 'f'.code.toByte() && original[1] == 'L'.code.toByte() &&
            original[2] == 'a'.code.toByte() && original[3] == 'C'.code.toByte() -> flac(original, e, cover)
        isMp3(original) -> mp3(original, e, cover)
        else -> null
    }

    private fun isMp3(b: ByteArray): Boolean {
        if (b.size > 3 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()) return true
        return b.size > 2 && (b[0].toInt() and 0xff) == 0xff && (b[1].toInt() and 0xe0) == 0xe0
    }

    // ------------------------------------------------------------------ ID3v2
    private fun syncsafe(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0x7f) shl 21) or ((b[o + 1].toInt() and 0x7f) shl 14) or ((b[o + 2].toInt() and 0x7f) shl 7) or (b[o + 3].toInt() and 0x7f)
    private fun int32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)
    private fun putSyncsafe(n: Int) = byteArrayOf(((n shr 21) and 0x7f).toByte(), ((n shr 14) and 0x7f).toByte(), ((n shr 7) and 0x7f).toByte(), (n and 0x7f).toByte())
    private fun putInt(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())

    private class Frame(val id: String, val flags: ByteArray, val data: ByteArray)

    fun mp3(b: ByteArray, e: TagEdit, cover: Cover?): ByteArray {
        var major = 3
        val frames = ArrayList<Frame>()
        var audioStart = 0
        if (b.size > 10 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()) {
            major = b[3].toInt().coerceIn(3, 4)
            val flags = b[5].toInt()
            val size = syncsafe(b, 6)
            audioStart = 10 + size + if (flags and 0x10 != 0) 10 else 0
            if (b[3].toInt() >= 3 && flags and 0x80 == 0) {
                var p = 10
                if (flags and 0x40 != 0) p += if (major == 4) syncsafe(b, 10) else int32(b, 10) + 4
                val end = (10 + size).coerceAtMost(b.size)
                while (p + 10 <= end) {
                    val id = String(b, p, 4, Charsets.ISO_8859_1)
                    if (id[0].code == 0 || !id.all { it.isLetterOrDigit() }) break
                    val fs = if (major == 4) syncsafe(b, p + 4) else int32(b, p + 4)
                    if (fs <= 0 || p + 10 + fs > end) break
                    frames += Frame(id, b.copyOfRange(p + 8, p + 10), b.copyOfRange(p + 10, p + 10 + fs))
                    p += 10 + fs
                }
            } else if (b[3].toInt() < 3) major = 3
        }
        fun text(id: String, v: String?) {
            if (v == null) return
            frames.removeAll { it.id == id }
            if (v.isBlank()) return
            val data = if (major == 4) byteArrayOf(3) + v.toByteArray(Charsets.UTF_8)
            else byteArrayOf(1) + byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + v.toByteArray(Charsets.UTF_16LE)
            frames += Frame(id, byteArrayOf(0, 0), data)
        }
        text("TIT2", e.title); text("TPE1", e.artist); text("TALB", e.album)
        text(if (major == 4) "TDRC" else "TYER", e.year?.toString())
        if (major == 4) frames.removeAll { it.id == "TYER" } else frames.removeAll { it.id == "TDRC" && e.year != null }
        text("TRCK", e.track?.toString()); text("TCON", e.genre)
        if (cover != null) {
            frames.removeAll { it.id == "APIC" }
            val d = ByteArrayOutputStream()
            d.write(0); d.write(cover.mime.toByteArray(Charsets.ISO_8859_1)); d.write(0)
            d.write(3); d.write(0) // front cover, empty description
            d.write(cover.bytes)
            frames += Frame("APIC", byteArrayOf(0, 0), d.toByteArray())
        }
        val body = ByteArrayOutputStream()
        frames.forEach { f ->
            body.write(f.id.toByteArray(Charsets.ISO_8859_1))
            body.write(if (major == 4) putSyncsafe(f.data.size) else putInt(f.data.size))
            body.write(f.flags); body.write(f.data)
        }
        body.write(ByteArray(2048)) // padding for future edits
        val out = ByteArrayOutputStream(b.size + body.size())
        out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), major.toByte(), 0, 0))
        out.write(putSyncsafe(body.size()))
        out.write(body.toByteArray())
        out.write(b, audioStart.coerceAtMost(b.size), b.size - audioStart.coerceAtMost(b.size))
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ FLAC
    fun flac(b: ByteArray, e: TagEdit, cover: Cover?): ByteArray {
        data class Block(val type: Int, val data: ByteArray)
        val blocks = ArrayList<Block>()
        var p = 4
        while (p + 4 <= b.size) {
            val last = b[p].toInt() and 0x80 != 0
            val type = b[p].toInt() and 0x7f
            val len = ((b[p + 1].toInt() and 0xff) shl 16) or ((b[p + 2].toInt() and 0xff) shl 8) or (b[p + 3].toInt() and 0xff)
            blocks += Block(type, b.copyOfRange(p + 4, p + 4 + len))
            p += 4 + len
            if (last) break
        }
        val audioStart = p
        // existing comments
        val vendorAndComments = blocks.firstOrNull { it.type == 4 }?.data
        var vendor = "Flowfy"
        val comments = ArrayList<Pair<String, String>>()
        if (vendorAndComments != null) runCatching {
            val bb = ByteBuffer.wrap(vendorAndComments).order(ByteOrder.LITTLE_ENDIAN)
            val vl = bb.int; vendor = String(vendorAndComments, 4, vl, Charsets.UTF_8); bb.position(4 + vl)
            repeat(bb.int) {
                val l = bb.int
                val kv = String(vendorAndComments, bb.position(), l, Charsets.UTF_8); bb.position(bb.position() + l)
                comments += kv.substringBefore('=') to kv.substringAfter('=')
            }
        }
        fun set(k: String, v: String?) {
            if (v == null) return
            comments.removeAll { it.first.equals(k, true) }
            if (v.isNotBlank()) comments += k to v
        }
        set("TITLE", e.title); set("ARTIST", e.artist); set("ALBUM", e.album); set("DATE", e.year?.toString())
        set("TRACKNUMBER", e.track?.toString()); set("GENRE", e.genre)
        val vc = ByteArrayOutputStream()
        fun le(n: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()
        val vb = vendor.toByteArray(Charsets.UTF_8)
        vc.write(le(vb.size)); vc.write(vb); vc.write(le(comments.size))
        comments.forEach { (k, v) -> val x = "$k=$v".toByteArray(Charsets.UTF_8); vc.write(le(x.size)); vc.write(x) }

        val newBlocks = ArrayList<Block>()
        blocks.forEach { bl ->
            when {
                bl.type == 1 -> Unit // old padding dropped
                bl.type == 4 -> Unit
                bl.type == 6 && cover != null -> Unit
                else -> newBlocks += bl
            }
        }
        newBlocks.add(1.coerceAtMost(newBlocks.size), Block(4, vc.toByteArray()))
        if (cover != null) {
            val pic = ByteArrayOutputStream()
            fun be(n: Int) = putInt(n)
            val mime = cover.mime.toByteArray(Charsets.US_ASCII)
            pic.write(be(3)); pic.write(be(mime.size)); pic.write(mime); pic.write(be(0))
            pic.write(be(cover.width)); pic.write(be(cover.height)); pic.write(be(24)); pic.write(be(0))
            pic.write(be(cover.bytes.size)); pic.write(cover.bytes)
            newBlocks += Block(6, pic.toByteArray())
        }
        newBlocks += Block(1, ByteArray(4096))
        val out = ByteArrayOutputStream(b.size + 8192)
        out.write("fLaC".toByteArray(Charsets.US_ASCII))
        newBlocks.forEachIndexed { i, bl ->
            val last = i == newBlocks.lastIndex
            out.write((bl.type or if (last) 0x80 else 0))
            out.write((bl.data.size shr 16) and 0xff); out.write((bl.data.size shr 8) and 0xff); out.write(bl.data.size and 0xff)
            out.write(bl.data)
        }
        out.write(b, audioStart, b.size - audioStart)
        return out.toByteArray()
    }
}
