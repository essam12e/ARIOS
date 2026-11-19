package com.arios.player.lyrics

data class LyricWord(val start: Long, val end: Long, val text: String)

data class LyricLine(
    val start: Long,
    val end: Long,
    val text: String,
    val words: List<LyricWord>,
    val translation: String? = null,
    /** true when [words] timings came from the file (enhanced LRC / YRC), false when estimated. */
    val realWordTiming: Boolean = false,
)

data class Lyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
    val source: String,
) {
    val hasTranslation: Boolean get() = lines.any { !it.translation.isNullOrBlank() }
    val hasWordTiming: Boolean get() = lines.any { it.realWordTiming }

    /** Index of the line active at [pos] (ms), -1 before the first line. */
    fun indexAt(pos: Long): Int {
        if (!synced || lines.isEmpty()) return -1
        var lo = 0; var hi = lines.size - 1; var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].start <= pos) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    companion object {
        fun isRtl(s: String): Boolean = s.any { it in '֐'..'ࣿ' || it in 'יִ'..'ﻼ' }
    }
}

object LrcParser {
    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val wordTag = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val offsetTag = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val metaTag = Regex("""^\[(ar|ti|al|by|re|ve|au|length|tool|#):.*]$""", RegexOption.IGNORE_CASE)
    private val yrcLine = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val yrcWord = Regex("""\((\d+),(\d+),\d+\)([^(]*)""")

    private fun ms(m: MatchResult): Long {
        val min = m.groupValues[1].toLong()
        val sec = m.groupValues[2].toLong()
        val frac = m.groupValues[3]
        val f = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
        return min * 60_000 + sec * 1000 + f
    }

    fun looksSynced(text: String) = timeTag.containsMatchIn(text) || text.lineSequence().any { yrcLine.matches(it.trim()) }

    /**
     * Parses LRC (simple, multi-timestamp, enhanced <word> tags), NetEase YRC and plain text.
     * Lines that share a timestamp are paired: the second one becomes the translation.
     * [translation] is an optional second LRC (e.g. NetEase tlyric) merged by timestamp.
     */
    fun parse(text: String, source: String, durationMs: Long = 0, translation: String? = null): Lyrics? {
        val raw = text.replace("\r", "").trim()
        if (raw.isEmpty()) return null
        if (raw.lineSequence().count { yrcLine.matches(it.trim()) } >= 3) {
            parseYrc(raw, source, durationMs)?.let { return mergeTranslation(it, translation) }
        }
        if (!timeTag.containsMatchIn(raw)) {
            val lines = raw.lines().map { it.trim() }.filter { !metaTag.matches(it) }
            return Lyrics(lines.map { LyricLine(0, 0, it, emptyList()) }, synced = false, source = source)
        }
        val offset = offsetTag.find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        data class Entry(val time: Long, val body: String, val order: Int)
        val entries = ArrayList<Entry>()
        var order = 0
        raw.lineSequence().forEach { line0 ->
            val line = line0.trim()
            if (line.isEmpty() || metaTag.matches(line) || offsetTag.matches(line)) return@forEach
            val stamps = ArrayList<Long>()
            var rest = line
            while (true) {
                val m = timeTag.find(rest) ?: break
                if (m.range.first != 0) break
                stamps += ms(m)
                rest = rest.substring(m.range.last + 1)
            }
            if (stamps.isEmpty()) return@forEach
            stamps.forEach { entries += Entry((it - offset).coerceAtLeast(0), rest, order++) }
        }
        if (entries.isEmpty()) return null
        entries.sortWith(compareBy({ it.time }, { it.order }))

        // pair entries with identical timestamps -> original + translation
        val grouped = ArrayList<Pair<Long, MutableList<String>>>()
        entries.forEach { e ->
            if (grouped.isNotEmpty() && grouped.last().first == e.time) grouped.last().second += e.body
            else grouped += e.time to mutableListOf(e.body)
        }
        val lines = ArrayList<LyricLine>()
        grouped.forEachIndexed { i, (time, bodies) ->
            val next = grouped.getOrNull(i + 1)?.first ?: (if (durationMs > time) durationMs else time + 6000)
            val main = bodies.first()
            val trans = bodies.drop(1).map { wordTag.replace(it, "").trim() }.filter { it.isNotEmpty() }.joinToString(" / ").ifEmpty { null }
            lines += buildLine(time, next, main, trans)
        }
        val result = Lyrics(lines.filterNot { it.text.isBlank() && it.translation == null && it.words.isEmpty() }
            .let { l -> if (l.isEmpty()) lines else l }, synced = true, source = source)
        return mergeTranslation(fixEmptyLines(result), translation)
    }

    /** Keeps instrumental (empty) lines as gaps but drops consecutive duplicates. */
    private fun fixEmptyLines(l: Lyrics): Lyrics {
        val out = ArrayList<LyricLine>()
        l.lines.forEach { line ->
            if (line.text.isBlank() && out.lastOrNull()?.text?.isBlank() == true) return@forEach
            out += line
        }
        while (out.isNotEmpty() && out.first().text.isBlank()) out.removeAt(0)
        return l.copy(lines = out)
    }

    private fun buildLine(start: Long, end: Long, body: String, translation: String?): LyricLine {
        val matches = wordTag.findAll(body).toList()
        if (matches.isNotEmpty()) {
            val words = ArrayList<LyricWord>()
            // text before the first tag belongs to the line start
            val pre = body.substring(0, matches.first().range.first)
            if (pre.isNotBlank()) words += LyricWord(start, ms(matches.first()), pre)
            matches.forEachIndexed { i, m ->
                val ws = ms(m)
                val textEnd = matches.getOrNull(i + 1)?.range?.first ?: body.length
                val t = body.substring(m.range.last + 1, textEnd)
                val we = matches.getOrNull(i + 1)?.let { ms(it) } ?: end
                if (t.isNotEmpty()) words += LyricWord(ws, we.coerceAtLeast(ws), t)
            }
            val text = words.joinToString("") { it.text }.replace(Regex("\\s+"), " ").trim()
            return LyricLine(start, end, text, normaliseWords(words), translation, realWordTiming = true)
        }
        val text = body.trim()
        return LyricLine(start, end, text, estimateWords(text, start, end), translation)
    }

    /** Splits text into words that keep their trailing space so they can be re-joined. */
    private fun normaliseWords(words: List<LyricWord>): List<LyricWord> {
        val out = ArrayList<LyricWord>()
        words.forEach { w ->
            val t = w.text.replace(Regex("\\s+"), " ")
            if (t.isBlank()) {
                if (out.isNotEmpty()) out[out.size - 1] = out.last().copy(text = out.last().text.trimEnd() + " ")
                return@forEach
            }
            if (out.isNotEmpty() && t.startsWith(" ") && !out.last().text.endsWith(" ")) {
                out[out.size - 1] = out.last().copy(text = out.last().text + " ")
            }
            out += w.copy(text = t.trimStart())
        }
        if (out.isNotEmpty()) out[out.size - 1] = out.last().copy(text = out.last().text.trimEnd())
        return out
    }

    /** Estimated word timings when the file only has line timestamps. */
    fun estimateWords(text: String, start: Long, end: Long): List<LyricWord> {
        val parts = Regex("\\S+\\s*").findAll(text).map { it.value }.toList()
        if (parts.isEmpty()) return emptyList()
        val chars = parts.sumOf { it.trim().length.coerceAtLeast(1) }
        val lineDur = (end - start).coerceAtLeast(300)
        val sung = minOf(lineDur, (chars * 95L + 500L).coerceAtLeast(600L))
        var t = start
        return parts.mapIndexed { i, p ->
            val d = sung * p.trim().length.coerceAtLeast(1) / chars
            val w = LyricWord(t, if (i == parts.lastIndex) start + sung else t + d, if (i == parts.lastIndex) p.trimEnd() else p)
            t += d; w
        }
    }

    private fun parseYrc(raw: String, source: String, durationMs: Long): Lyrics? {
        val lines = ArrayList<LyricLine>()
        raw.lineSequence().forEach { l ->
            val m = yrcLine.matchEntire(l.trim()) ?: return@forEach
            val start = m.groupValues[1].toLong()
            val dur = m.groupValues[2].toLong()
            val words = yrcWord.findAll(m.groupValues[3]).map {
                val ws = it.groupValues[1].toLong()
                LyricWord(ws, ws + it.groupValues[2].toLong(), it.groupValues[3])
            }.filter { it.text.isNotEmpty() }.toList()
            if (words.isEmpty()) return@forEach
            lines += LyricLine(start, start + dur, words.joinToString("") { it.text }.trim(), normaliseWords(words), realWordTiming = true)
        }
        if (lines.isEmpty()) return null
        lines.sortBy { it.start }
        // stretch each line's end to the next start (keeps highlight until the next line)
        val fixed = lines.mapIndexed { i, ln ->
            val next = lines.getOrNull(i + 1)?.start ?: maxOf(ln.end, durationMs)
            ln.copy(end = maxOf(ln.end, next))
        }
        return Lyrics(fixed, synced = true, source = source)
    }

    private fun mergeTranslation(l: Lyrics, translation: String?): Lyrics {
        if (translation.isNullOrBlank() || !l.synced) return l
        val t = parse(translation, l.source) ?: return l
        if (!t.synced) return l
        val byTime = t.lines.filter { it.text.isNotBlank() }
        if (byTime.isEmpty()) return l
        return l.copy(lines = l.lines.map { line ->
            if (line.translation != null || line.text.isBlank()) return@map line
            val match = byTime.minByOrNull { kotlin.math.abs(it.start - line.start) }
            if (match != null && kotlin.math.abs(match.start - line.start) <= 600 && match.text != line.text)
                line.copy(translation = match.text) else line
        })
    }
}

object LrcWriter {
    fun stamp(ms: Long): String {
        val m = ms / 60000; val s = (ms / 1000) % 60; val cs = (ms % 1000) / 10
        return "[%02d:%02d.%02d]".format(m, s, cs)
    }
    private fun wordStamp(ms: Long): String = stamp(ms).replace('[', '<').replace(']', '>')

    fun write(l: Lyrics, title: String? = null, artist: String? = null): String = buildString {
        if (!title.isNullOrBlank()) append("[ti:").append(title).append("]\n")
        if (!artist.isNullOrBlank()) append("[ar:").append(artist).append("]\n")
        append("[by:ARIOS]\n")
        l.lines.forEach { line ->
            if (!l.synced) { append(line.text).append('\n'); return@forEach }
            append(stamp(line.start))
            if (line.realWordTiming && line.words.isNotEmpty()) {
                line.words.forEach { w -> append(wordStamp(w.start)).append(w.text) }
            } else append(line.text)
            append('\n')
            if (!line.translation.isNullOrBlank()) append(stamp(line.start)).append(line.translation).append('\n')
        }
    }
}
