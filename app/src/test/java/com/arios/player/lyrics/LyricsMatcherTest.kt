package com.arios.player.lyrics

import org.junit.Assert.*
import org.junit.Test

class LyricsMatcherTest {
    private fun cand(title: String, artist: String, dur: Int?) =
        LyricsCandidate("x", "X", title, artist, null, dur, true) { null }

    @Test fun parsesYoutubeStyleBilingualTitle() {
        val p = LyricsMatcher.parse("Arsenik - Mayal | أرسينِك - ميال [ Official Music Video ]", "Arsenik - أرسينِك")
        assertTrue(p.titles.toString(), p.titles.contains("Mayal"))
        assertTrue(p.titles.toString(), p.titles.contains("ميال"))
        assertTrue(p.artists.contains("Arsenik"))
        val qs = LyricsMatcher.queries("Arsenik - Mayal | أرسينِك - ميال [ Official Music Video ]", "Arsenik - أرسينِك", null, 200_000)
        assertEquals("Mayal", qs[0].title)
        assertEquals("Arsenik", qs[0].artist)
        assertEquals("ميال", qs[1].title)
        assertEquals("ارسينك", LyricsMatcher.normalise(qs[1].artist))
    }

    @Test fun rejectsWrongSongWithArtistNameAsTitle() {
        val qs = LyricsMatcher.queries("Arsenik - Mayal | أرسينِك - ميال [Official Music Video]", "Arsenik - أرسينِك", null, 200_000)
        // the bad hits from the screenshot: songs *titled* "Arsenik" by other artists
        listOf(cand("Arsenik", "DJ Mo", 137), cand("Arsenik", "Tiakola", 207), cand("Arsenik", "Zakkum, Kasap", 226)).forEach {
            assertFalse(LyricsMatcher.isConfident(LyricsMatcher.score(it, qs)))
        }
        assertTrue(LyricsMatcher.isConfident(LyricsMatcher.score(cand("Mayal", "Arsenik", 201), qs)))
        assertTrue(LyricsMatcher.isConfident(LyricsMatcher.score(cand("ميال", "أرسينك", 199), qs)))
    }

    @Test fun durationMismatchRejected() {
        val qs = LyricsMatcher.queries("Runaway", "AURORA", null, 248_000)
        assertFalse(LyricsMatcher.isConfident(LyricsMatcher.score(cand("Runaway", "Kanye West", 547), qs)))
        assertTrue(LyricsMatcher.isConfident(LyricsMatcher.score(cand("Runaway", "AURORA", 249), qs)))
    }

    @Test fun simpleTags() {
        val qs = LyricsMatcher.queries("Linger (Official Video)", "The Cranberries", "Everybody Else", 274_000)
        assertEquals("Linger", qs[0].title)
        assertEquals("The Cranberries", qs[0].artist)
    }
}
