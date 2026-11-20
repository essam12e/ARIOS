package com.arios.player

import com.arios.player.lyrics.LinkLyrics
import com.arios.player.lyrics.LyricsMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkLyricsTest {
    @Test fun findsMusicLinks() {
        assertEquals("https://youtu.be/abc123", LinkLyrics.findLink("شوف هالأغنية https://youtu.be/abc123 روعة"))
        assertEquals("https://www.youtube.com/watch?v=x1&si=y", LinkLyrics.findLink("https://www.youtube.com/watch?v=x1&si=y"))
        assertEquals("https://music.youtube.com/watch?v=q", LinkLyrics.findLink("https://music.youtube.com/watch?v=q."))
        assertEquals("https://open.spotify.com/track/3n3P?si=1", LinkLyrics.findLink("Listen: https://open.spotify.com/track/3n3P?si=1"))
        assertEquals("https://play.anghami.com/song/123", LinkLyrics.findLink("https://play.anghami.com/song/123"))
        assertNull(LinkLyrics.findLink("https://example.com/song.mp3"))
        assertNull(LinkLyrics.findLink("no link here"))
    }

    @Test fun youtubeTitlesBecomeCleanQueries() {
        val p = LyricsMatcher.parse("Arsenik - Mayal | أرسينِك - ميال [Official Music Video]", "Arsenik")
        assertTrue(p.titles.any { LyricsMatcher.similarity(it, "Mayal") > 0.85 })
        val q = LyricsMatcher.parse("Adele - Hello (Official Music Video)", "AdeleVEVO".removeSuffix("VEVO"))
        assertEquals("Hello", q.titles.first())
    }
}
