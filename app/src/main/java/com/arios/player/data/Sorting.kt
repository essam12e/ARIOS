package com.arios.player.data

import java.text.Normalizer

/** Normalised first letter used by the alphabet side bar (Latin, Arabic and '#'). */
fun indexLetter(s: String): String {
    val t = s.trim().trimStart('(', '[', '"', '\'', '.', '-', '_')
    if (t.isEmpty()) return "#"
    val ch = t[0]
    return when {
        ch in 'a'..'z' || ch in 'A'..'Z' -> ch.uppercaseChar().toString()
        ch in "أإآٱ" -> "ا"
        ch == 'ة' -> "ه"
        ch == 'ى' || ch == 'ئ' -> "ي"
        ch == 'ؤ' -> "و"
        ch in 'ء'..'ي' -> ch.toString()
        ch.isLetter() -> {
            val base = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)[0]
            if (base in 'a'..'z' || base in 'A'..'Z') base.uppercaseChar().toString() else "#"
        }
        else -> "#"
    }
}

private const val ARABIC_ORDER = "ابتثجحخدذرزسشصضطظعغفقكلمنهوي"

/** Sort weight so that Latin comes first, then Arabic (in alphabet order), then the rest. */
fun letterWeight(letter: String): Int = when {
    letter == "#" -> 1000
    letter[0] in 'A'..'Z' -> letter[0] - 'A'
    else -> 100 + ARABIC_ORDER.indexOf(letter[0]).coerceAtLeast(0)
}

val nameComparator: Comparator<String> = Comparator { a, b ->
    val la = indexLetter(a); val lb = indexLetter(b)
    val w = letterWeight(la).compareTo(letterWeight(lb))
    if (w != 0) w else a.lowercase().compareTo(b.lowercase())
}

object SongSort {
    const val TITLE = 0
    const val ARTIST = 1
    const val DATE = 2
    const val DURATION = 3
    const val ALBUM = 4
}

fun List<Song>.sortedBy(mode: Int, desc: Boolean): List<Song> {
    val c: Comparator<Song> = when (mode) {
        SongSort.ARTIST -> compareBy(nameComparator) { it: Song -> it.artist }.thenBy(nameComparator) { it.title }
        SongSort.DATE -> compareByDescending { it.dateAdded }
        SongSort.DURATION -> compareBy { it.durationMs }
        SongSort.ALBUM -> compareBy(nameComparator) { it: Song -> it.album }.thenBy { it.track }
        else -> compareBy(nameComparator) { it.title }
    }
    val sorted = sortedWith(c)
    return if (desc) sorted.reversed() else sorted
}
