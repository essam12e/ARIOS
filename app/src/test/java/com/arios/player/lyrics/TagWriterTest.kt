package com.arios.player.lyrics

import com.arios.player.data.TagEdit
import com.arios.player.data.TagWriter
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TagWriterTest {
    private val audio = ByteArray(5000) { (it * 7 % 251).toByte() }.also { it[0] = 0xFF.toByte(); it[1] = 0xFB.toByte() }

    private fun id3WithLyrics(): ByteArray {
        val uslt = ByteArrayOutputStream().apply {
            write(3); write("ara".toByteArray()); write(0); write("[00:01.00]مرحبا".toByteArray(Charsets.UTF_8))
        }.toByteArray()
        val tit2 = byteArrayOf(0) + "Old".toByteArray()
        fun frame(id: String, d: ByteArray) = id.toByteArray() + byteArrayOf(0, 0, (d.size shr 8).toByte(), d.size.toByte(), 0, 0) + d
        val body = frame("TIT2", tit2) + frame("USLT", uslt)
        val size = body.size
        val header = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            ((size shr 21) and 0x7f).toByte(), ((size shr 14) and 0x7f).toByte(), ((size shr 7) and 0x7f).toByte(), (size and 0x7f).toByte())
        return header + body + audio
    }

    private fun lyricsOf(bytes: ByteArray): String? {
        val f = File.createTempFile("tagtest", ".bin"); f.writeBytes(bytes)
        return RandomAccessFile(f, "r").channel.use { EmbeddedLyrics.readChannel(it) }.also { f.delete() }
    }

    @Test fun mp3KeepsLyricsAndAudio() {
        val out = TagWriter.write(id3WithLyrics(), TagEdit(title = "ميال", artist = "Arsenik", year = 2024), null)!!
        assertTrue(out.copyOfRange(out.size - audio.size, out.size).contentEquals(audio))
        assertEquals("[00:01.00]مرحبا", lyricsOf(out)?.trim())
        val s = String(out, Charsets.ISO_8859_1)
        assertTrue(s.contains("TIT2")); assertTrue(s.contains("TPE1")); assertTrue(s.contains("TYER"))
        // title stored as UTF-16 with BOM
        val idx = s.indexOf("TIT2")
        val text = String(out, idx + 13, 8, Charsets.UTF_16LE)
        assertEquals("ميال", text)
    }

    @Test fun mp3WithoutTagGetsOne() {
        val out = TagWriter.write(audio, TagEdit(title = "T"), TagWriter.Cover(byteArrayOf(1, 2, 3), "image/jpeg", 1, 1))!!
        assertEquals('I'.code.toByte(), out[0])
        assertTrue(out.copyOfRange(out.size - audio.size, out.size).contentEquals(audio))
        assertTrue(String(out, Charsets.ISO_8859_1).contains("APIC"))
    }

    @Test fun flacRewritesComments() {
        fun le(n: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()
        val vendor = "ref".toByteArray()
        val c1 = "TITLE=Old".toByteArray(); val c2 = "LYRICS=[00:02.00]hi".toByteArray()
        val vc = le(vendor.size) + vendor + le(2) + le(c1.size) + c1 + le(c2.size) + c2
        val streaminfo = ByteArray(34) { 1 }
        val flac = "fLaC".toByteArray() +
            byteArrayOf(0, 0, 0, 34) + streaminfo +
            byteArrayOf((0x80 or 4).toByte(), 0, (vc.size shr 8).toByte(), vc.size.toByte()) + vc + audio
        val out = TagWriter.write(flac, TagEdit(title = "New", artist = "فيروز"), TagWriter.Cover(byteArrayOf(9, 9), "image/png", 2, 2))!!
        assertTrue(out.copyOfRange(out.size - audio.size, out.size).contentEquals(audio))
        assertEquals("[00:02.00]hi", lyricsOf(out))
        val s = String(out, Charsets.UTF_8)
        assertTrue(s.contains("TITLE=New")); assertFalse(s.contains("TITLE=Old")); assertTrue(s.contains("ARTIST=فيروز"))
        // STREAMINFO must stay the first block
        assertEquals(0, out[4].toInt() and 0x7f)
    }

    @Test fun unknownFormatReturnsNull() {
        assertNull(TagWriter.write("OggS....".toByteArray() + audio, TagEdit(title = "x"), null))
    }
}
