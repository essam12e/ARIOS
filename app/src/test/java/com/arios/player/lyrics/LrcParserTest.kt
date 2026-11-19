package com.arios.player.lyrics

import org.junit.Assert.*
import org.junit.Test

class LrcParserTest {
    @Test fun simpleLrc() {
        val l = LrcParser.parse("[ti:Song]\n[00:01.00]Hello world\n[00:03.50]Second line\n", "t", 10_000)!!
        assertTrue(l.synced)
        assertEquals(2, l.lines.size)
        assertEquals(1000L, l.lines[0].start)
        assertEquals(3500L, l.lines[0].end)
        assertEquals("Hello world", l.lines[0].text)
        assertEquals(10_000L, l.lines[1].end)
        assertEquals(-1, l.indexAt(500))
        assertEquals(0, l.indexAt(2000))
        assertEquals(1, l.indexAt(9000))
        // estimated words cover the text
        assertEquals("Hello world", l.lines[0].words.joinToString("") { it.text })
        assertFalse(l.lines[0].realWordTiming)
    }

    @Test fun multiTimestampAndOffset() {
        val l = LrcParser.parse("[offset:500]\n[00:10.00][00:20.00]Chorus\n[00:15.00]Verse", "t")!!
        assertEquals(listOf(9500L, 14500L, 19500L), l.lines.map { it.start })
        assertEquals(listOf("Chorus", "Verse", "Chorus"), l.lines.map { it.text })
    }

    @Test fun translationPairing() {
        val l = LrcParser.parse("[00:01.00]I love you\n[00:01.00]أحبك\n[00:04.00]Next", "t")!!
        assertEquals(2, l.lines.size)
        assertEquals("أحبك", l.lines[0].translation)
        assertTrue(l.hasTranslation)
    }

    @Test fun separateTranslationMerge() {
        val l = LrcParser.parse("[00:01.00]Hello\n[00:05.00]World", "t", 0, "[00:01.05]مرحبا\n[00:05.00]عالم")!!
        assertEquals("مرحبا", l.lines[0].translation)
        assertEquals("عالم", l.lines[1].translation)
    }

    @Test fun enhancedWordTiming() {
        val l = LrcParser.parse("[00:01.00]<00:01.00>Hello <00:01.50>big <00:02.00>world\n[00:04.00]x", "t")!!
        val line = l.lines[0]
        assertTrue(line.realWordTiming)
        assertEquals("Hello big world", line.text)
        assertEquals(listOf(1000L, 1500L, 2000L), line.words.map { it.start })
        assertEquals("Hello big world", line.words.joinToString("") { it.text })
    }

    @Test fun yrc() {
        val yrc = """
            {"t":0,"c":[{"tx":"meta"}]}
            [1000,2000](1000,500,0)Hel(1500,500,0)lo (2000,1000,0)there
            [4000,1000](4000,1000,0)Bye
            [6000,1000](6000,1000,0)End
        """.trimIndent()
        val l = LrcParser.parse(yrc, "NetEase", 9000)!!
        assertTrue(l.synced)
        assertEquals(3, l.lines.size)
        assertEquals("Hello there", l.lines[0].text)
        assertTrue(l.lines[0].realWordTiming)
        assertEquals(4000L, l.lines[0].end)
    }

    @Test fun plainText() {
        val l = LrcParser.parse("line one\nline two", "t")!!
        assertFalse(l.synced)
        assertEquals(2, l.lines.size)
        assertEquals(-1, l.indexAt(1000))
    }

    @Test fun arabicRtl() {
        assertTrue(Lyrics.isRtl("قلبي يحبك"))
        assertFalse(Lyrics.isRtl("Runaway"))
    }

    @Test fun roundTrip() {
        val src = "[00:01.00]<00:01.00>Hi <00:01.40>you\n[00:01.00]مرحبا\n[00:03.20]Plain line"
        val a = LrcParser.parse(src, "t")!!
        val b = LrcParser.parse(LrcWriter.write(a, "T", "A"), "t")!!
        assertEquals(a.lines.map { it.text }, b.lines.map { it.text })
        assertEquals(a.lines.map { it.start }, b.lines.map { it.start })
        assertEquals(a.lines[0].translation, b.lines[0].translation)
        assertEquals(a.lines[0].words.map { it.start }, b.lines[0].words.map { it.start })
    }

    @Test fun cleanTitle() {
        assertEquals("Runaway", LyricsRepository.cleanTitle("Runaway (Official Video)"))
        assertEquals("Song", LyricsRepository.cleanTitle("Song feat. Someone"))
        assertEquals("Song", LyricsRepository.cleanTitle("03 - Song [Lyrics]"))
    }
}
