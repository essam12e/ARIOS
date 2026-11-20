package com.arios.player.lyrics

/**
 * Turns messy file titles ("Arsenik - Mayal | أرسينِك - ميال [Official Music Video]")
 * into clean search queries and scores search hits strictly (title + artist + duration).
 */
object LyricsMatcher {
    private val tashkeel = Regex("[ً-ٰٟـۖ-ۭ]")
    private val bracketNoise = Regex("""[(\[{【「]([^)\]}】」]*)[)\]}】」]""")
    private val noiseWords = Regex(
        """(?i)\b(official|video|audio|lyrics?|lyric video|visuali[sz]er|remaster(ed)?|hd|hq|4k|8k|explicit|clean|live|version|edit|mv|m/v|music video|prod\.?|kalimat|كلمات|فيديو|حصري|حصريا|official music video|exclusive|cover|clip|radio edit|extended|original mix)\b"""
    )
    private val featRx = Regex("""(?i)\s*[(\[]?\s*(feat|ft|featuring|with)\.?\s+[^)\]]*[)\]]?""")
    private val trackNo = Regex("""^\s*\d{1,3}\s*[.\-_)]\s*""")
    private val ext = Regex("""(?i)\.(mp3|m4a|flac|ogg|opus|wav|aac|webm)$""")

    fun stripTashkeel(s: String) = tashkeel.replace(s, "")

    fun normalise(s: String): String = stripTashkeel(s).lowercase()
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
        .replace('ة', 'ه').replace('ى', 'ي').replace('ؤ', 'و').replace('ئ', 'ي')
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Removes bracketed noise, feat., track numbers, extensions, emojis. */
    fun clean(t: String): String {
        var s = ext.replace(t, "")
        s = bracketNoise.replace(s) { m -> if (noiseWords.containsMatchIn(m.groupValues[1]) || m.groupValues[1].isBlank()) " " else m.value }
        s = featRx.replace(s, " ")
        s = trackNo.replace(s, "")
        s = s.replace(Regex("[\\p{So}\\p{Cn}]"), " ")
        s = s.replace('_', ' ').replace(Regex("\\s+"), " ").trim(' ', '-', '|', '•', '–', '—', '/', ':')
        return s
    }

    private fun splitParts(s: String): List<String> =
        s.split(Regex("""\s+[|｜/•]\s+|\s*[|｜]\s*""")).map { it.trim() }.filter { it.isNotEmpty() }

    private fun splitArtists(a: String): List<String> =
        a.split(Regex("""\s+-\s+|\s*[|｜/,&،]\s*|\s+x\s+|\s+(?i:feat\.?|ft\.?)\s+"""))
            .map { clean(it) }.filter { it.isNotBlank() }

    private fun sameName(a: String, b: String): Boolean {
        val x = normalise(a); val y = normalise(b)
        return x.isNotEmpty() && y.isNotEmpty() && (x == y || x.contains(y) || y.contains(x))
    }

    data class Parsed(val titles: List<String>, val artists: List<String>)

    /** Extracts title and artist variants (e.g. Latin + Arabic) from tags/file name. */
    fun parse(rawTitle: String, rawArtist: String): Parsed {
        val artists = LinkedHashSet<String>()
        splitArtists(rawArtist).forEach { artists += it }
        val titles = LinkedHashSet<String>()
        for (part in splitParts(clean(rawTitle))) {
            val dash = part.split(Regex("""\s+[-–—]\s+"""), limit = 2)
            if (dash.size == 2) {
                val (a, b) = dash[0].trim() to dash[1].trim()
                when {
                    artists.any { sameName(it, a) } -> titles += b
                    artists.any { sameName(it, b) } -> titles += a
                    else -> { titles += b; artists += a }
                }
            } else titles += part
        }
        // drop artist names that leaked into titles
        val cleanedTitles = titles.map { t ->
            var x = t
            artists.forEach { a -> if (a.length > 2) x = x.replace(a, "", ignoreCase = true) }
            clean(x).ifBlank { t }
        }.filter { it.isNotBlank() }.distinct()
        return Parsed(cleanedTitles.ifEmpty { listOf(clean(rawTitle)) }, artists.toList())
    }

    /** Ordered list of queries to try (Latin first, then Arabic, then title only). */
    fun queries(title: String, artist: String, album: String?, durationMs: Long): List<LyricsQuery> {
        val p = parse(title, artist)
        val out = LinkedHashSet<LyricsQuery>()
        val latinT = p.titles.filterNot { Lyrics.isRtl(it) }
        val rtlT = p.titles.filter { Lyrics.isRtl(it) }
        val latinA = p.artists.filterNot { Lyrics.isRtl(it) }
        val rtlA = p.artists.filter { Lyrics.isRtl(it) }
        fun add(t: String?, a: String?) { if (!t.isNullOrBlank()) out += LyricsQuery(t, a ?: "", album, durationMs) }
        add(latinT.firstOrNull(), latinA.firstOrNull() ?: p.artists.firstOrNull())
        add(rtlT.firstOrNull()?.let(::stripTashkeel), (rtlA.firstOrNull() ?: p.artists.firstOrNull())?.let(::stripTashkeel))
        add(p.titles.first(), p.artists.firstOrNull())
        p.titles.forEach { add(stripTashkeel(it), "") }
        return out.toList().take(4)
    }

    private fun bigrams(s: String): List<String> = s.replace(" ", "").windowed(2)

    fun similarity(a: String, b: String): Double {
        val x = normalise(a); val y = normalise(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val shorter = minOf(x.length, y.length).toDouble(); val longer = maxOf(x.length, y.length)
        if ((x.contains(y) || y.contains(x)) && shorter / longer >= 0.5) return 0.9
        val ta = x.split(' ').toSet(); val tb = y.split(' ').toSet()
        val jac = ta.intersect(tb).size.toDouble() / ta.union(tb).size
        val ba = bigrams(x); val bb = bigrams(y).toMutableList()
        var hit = 0
        ba.forEach { if (bb.remove(it)) hit++ }
        val dice = if (ba.isEmpty() || bigrams(y).isEmpty()) 0.0 else 2.0 * hit / (ba.size + bigrams(y).size)
        return maxOf(jac, dice)
    }

    data class Score(val total: Double, val title: Double, val artist: Double, val durationDiff: Double?)

    fun score(c: LyricsCandidate, queries: List<LyricsQuery>): Score {
        val title = queries.maxOf { q -> similarity(clean(c.title), q.title) }
        val artistsQ = queries.map { it.artist }.filter { it.isNotBlank() }
        val artist = if (artistsQ.isEmpty()) 0.5 else artistsQ.maxOf { q ->
            splitArtists(c.artist).ifEmpty { listOf(c.artist) }.maxOf { similarity(it, q) }
        }
        val dur = queries.first().durationMs
        val diff = if (c.durationSec != null && dur > 0) kotlin.math.abs(c.durationSec - dur / 1000.0) else null
        var total = title * 0.6 + artist * 0.3
        total += when {
            diff == null -> 0.0
            diff <= 2 -> 0.2
            diff <= 5 -> 0.1
            diff <= 12 -> -0.1
            else -> -0.5
        }
        if (c.syncedHint == true) total += 0.03
        return Score(total, title, artist, diff)
    }

    /** Strict check used for automatic selection: wrong lyrics are worse than none. */
    fun isConfident(s: Score): Boolean {
        if (s.title < 0.6) return false
        if (s.durationDiff != null && s.durationDiff > 12) return false
        if (s.durationDiff != null && s.durationDiff <= 3 && s.title >= 0.6) return true
        return s.title >= 0.75 && s.artist >= 0.5
    }
}
