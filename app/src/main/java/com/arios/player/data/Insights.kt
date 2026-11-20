package com.arios.player.data

import com.arios.player.Arios
import com.arios.player.lyrics.LyricsMatcher
import java.util.Calendar

/** Auto-generated playlists. */
enum class SmartList { TOP, RECENT_PLAYED, RECENT_ADDED, FORGOTTEN, NEVER_PLAYED, LONG_AUDIO }

object Insights {
    fun smart(list: SmartList, lib: LibraryState): List<Song> {
        val stats = Arios.history.stats()
        val now = System.currentTimeMillis()
        val day = 24 * 3600 * 1000L
        return when (list) {
            SmartList.TOP -> lib.songs.filter { (stats[it.key]?.plays ?: 0) > 0 }
                .sortedByDescending { stats[it.key]?.plays ?: 0 }.take(25)
            SmartList.RECENT_PLAYED -> Arios.history.events.value.asReversed().map { it.key }.distinct()
                .mapNotNull { lib.byKey[it] }.take(50)
            SmartList.RECENT_ADDED -> lib.songs.filter { now / 1000 - it.dateAdded < 30 * 24 * 3600 }
                .sortedByDescending { it.dateAdded }.take(100)
            SmartList.FORGOTTEN -> lib.songs.filter { s -> stats[s.key]?.let { it.plays >= 3 && now - it.lastPlayed > 30 * day } == true }
                .sortedByDescending { stats[it.key]?.plays ?: 0 }
            SmartList.NEVER_PLAYED -> lib.songs.filter { stats[it.key] == null }.shuffled(java.util.Random(now / day))
            SmartList.LONG_AUDIO -> lib.songs.filter { it.durationMs >= 20 * 60_000L }.sortedByDescending { it.dateAdded }
        }
    }

    // ------------------------------------------------------------------ duplicates

    data class DupGroup(val keep: Song, val others: List<Song>)

    private fun quality(s: Song): Double {
        val lossless = s.ext in setOf("FLAC", "WAV", "ALAC", "AIFF")
        val kbps = if (s.durationMs > 0) s.size * 8.0 / s.durationMs else 0.0
        return (if (lossless) 10_000.0 else 0.0) + kbps
    }

    fun duplicates(lib: LibraryState): List<DupGroup> {
        val groups = lib.songs.groupBy {
            LyricsMatcher.normalise(LyricsMatcher.clean(it.title)) + "|" + LyricsMatcher.normalise(it.artist)
        }.values.filter { it.size > 1 }
        val out = ArrayList<DupGroup>()
        groups.forEach { g ->
            // split by duration (±3 s) so live/remix versions are not merged
            val clusters = ArrayList<MutableList<Song>>()
            g.sortedBy { it.durationMs }.forEach { s ->
                val c = clusters.lastOrNull()
                if (c != null && s.durationMs - c.first().durationMs <= 3000) c += s else clusters += mutableListOf(s)
            }
            clusters.filter { it.size > 1 }.forEach { c ->
                val keep = c.maxBy { quality(it) }
                out += DupGroup(keep, c - keep)
            }
        }
        return out.sortedBy { it.keep.title }
    }

    // ------------------------------------------------------------------ stats

    enum class Period(val days: Int) { WEEK(7), MONTH(30), YEAR(365), ALL(100000) }

    data class Summary(
        val totalMs: Long,
        val plays: Int,
        val uniqueSongs: Int,
        val topSongs: List<Pair<Song, Int>>,
        val topArtists: List<Triple<String, Int, Song?>>,
        val perDay: List<Pair<Long, Long>>,   // day start → ms
        val perHour: IntArray,
        val longestStreakDays: Int,
    )

    fun summary(period: Period, lib: LibraryState): Summary {
        val now = System.currentTimeMillis()
        val day = 24 * 3600 * 1000L
        val from = now - period.days * day
        val ev = Arios.history.events.value.filter { it.time >= from }
        val ms = ev.sumOf { e -> if (e.ms > 0) e.ms else lib.byKey[e.key]?.durationMs ?: 180_000L }
        val songCounts = ev.groupingBy { it.key }.eachCount()
        val topSongs = songCounts.entries.sortedByDescending { it.value }.mapNotNull { (k, c) -> lib.byKey[k]?.let { it to c } }.take(10)
        val artistCounts = ev.groupingBy { it.artist }.eachCount()
        val topArtists = artistCounts.entries.filter { it.key.isNotBlank() && it.key != MediaScanner.UNKNOWN }
            .sortedByDescending { it.value }.take(5)
            .map { (a, c) -> Triple(a, c, lib.songs.firstOrNull { it.artist == a }) }
        val cal = Calendar.getInstance()
        fun dayStart(t: Long): Long { cal.timeInMillis = t; cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0); return cal.timeInMillis }
        val days = minOf(period.days, 30)
        val today = dayStart(now)
        val byDay = ev.groupBy { dayStart(it.time) }.mapValues { (_, l) -> l.sumOf { e -> if (e.ms > 0) e.ms else 180_000L } }
        val perDay = (days - 1 downTo 0).map { i -> val d = today - i * day; d to (byDay[d] ?: 0L) }
        val perHour = IntArray(24)
        ev.forEach { cal.timeInMillis = it.time; perHour[cal.get(Calendar.HOUR_OF_DAY)]++ }
        // streak of consecutive days with listening
        val activeDays = Arios.history.events.value.map { dayStart(it.time) }.toSortedSet()
        var best = 0; var cur = 0; var prev = -1L
        activeDays.forEach { d -> cur = if (prev >= 0 && d - prev in (day - 3600_000)..(day + 3600_000)) cur + 1 else 1; best = maxOf(best, cur); prev = d }
        return Summary(ms, ev.size, songCounts.size, topSongs, topArtists, perDay, perHour, best)
    }
}
