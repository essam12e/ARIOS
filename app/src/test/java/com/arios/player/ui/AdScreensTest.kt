package com.arios.player.ui

import android.graphics.*
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.arios.player.Arios
import com.arios.player.data.PlayEvent
import com.arios.player.data.Song
import com.arios.player.playback.AudioFx
import com.arios.player.playback.EqSettings
import com.arios.player.playback.PlayerUiState
import com.arios.player.playback.QueueEntry
import com.arios.player.playback.Spectrum
import com.arios.player.ui.theme.AriosTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.FileOutputStream
import kotlin.math.sin

/** High-resolution, fictional-content screenshots used for the Flowfy promo video. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ar-w393dp-h852dp-xxhdpi")
class AdScreensTest {
    @get:Rule val rule = createComposeRule()

    private val looks = listOf(
        intArrayOf(0xFF2F4FE0.toInt(), 0xFF4DB2FF.toInt(), 0xFFFF8A4C.toInt()),
        intArrayOf(0xFFFF8A4C.toInt(), 0xFFFFD36E.toInt(), 0xFF1B1B3A.toInt()),
        intArrayOf(0xFF0B0B14.toInt(), 0xFF9B7CFF.toInt(), 0xFF4DB2FF.toInt()),
        intArrayOf(0xFF0E6B5C.toInt(), 0xFF2EE6A0.toInt(), 0xFFF4F1E8.toInt()),
        intArrayOf(0xFFC21E62.toInt(), 0xFFFF5C9A.toInt(), 0xFF140814.toInt()),
        intArrayOf(0xFFF4F1E8.toInt(), 0xFF2F4FE0.toInt(), 0xFFFF5C3D.toInt()),
    )

    private fun song(id: Long, title: String, artist: String, album: String, dur: Long, folder: String = "/storage/emulated/0/Music") = Song(
        id, Uri.parse("content://media/external/audio/media/$id"), title, artist, album, id + 100, dur,
        "$folder/$title.mp3", folder, 1_700_000_000L - id * 1000, id.toInt(), 2025, "audio/mpeg", 6_000_000,
    )

    private val songs = listOf(
        song(1, "ليل المدينة", "نور", "ليل المدينة", 222_000),
        song(2, "Golden Hour Drive", "Mira Vale", "Golden Hour", 201_000),
        song(3, "سما", "ريم", "سما", 236_000),
        song(4, "Echoes", "The Lanterns", "Lights", 247_000),
        song(5, "قمر الشتا", "سلمى", "شتا", 214_000),
        song(6, "Neon Rain", "Kairo Nights", "Neon", 189_000),
        song(7, "حكاية", "فهد", "حكاية", 262_000),
        song(8, "Ocean Lines", "Lumen", "Tides", 230_000),
        song(9, "مسافر", "يوسف", "طريق", 241_000),
        song(10, "Paper Planes", "Ada Noor", "Paper", 198_000),
        song(11, "نبض", "لينا", "نبض", 207_000),
        song(12, "Slow Motion", "Velvet Sky", "Slow", 254_000),
    )

    private val lrc = """
        [00:10.00]<00:10.00>الليل <00:10.70>يمشي <00:11.40>في <00:11.80>شوارع <00:12.80>المدينة
        [00:10.00]The night walks the city streets
        [00:15.00]<00:15.00>وأنا <00:15.60>أغني <00:16.30>لك <00:16.70>بصوتٍ <00:17.60>ما <00:18.00>يهدأ
        [00:15.00]And I sing to you with a voice that never rests
        [00:21.00]<00:21.00>كل <00:21.40>ضوء <00:22.00>في <00:22.30>السما <00:23.10>يحكي <00:23.90>حكايتنا
        [00:21.00]Every light in the sky tells our story
        [00:27.00]
        [00:31.00]<00:31.00>خلّ <00:31.50>الكلام <00:32.30>يمشي <00:33.00>معي <00:33.60>كلمة <00:34.40>بكلمة
        [00:31.00]Let the words walk with me, word by word
        [00:37.00]<00:37.00>اسمع <00:37.90>وشوف <00:38.60>كل <00:39.10>كلمة
        [00:37.00]Listen, and see every word
        [00:43.00]
    """.trimIndent()

    /** Abstract two-tone covers (bold arcs and rings) – no real artwork used. */
    private fun cover(i: Int): Bitmap {
        val (bg, a, b) = looks[i % looks.size].let { Triple(it[0], it[1], it[2]) }
        val s = 600f
        val bmp = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(bg)
        when (i % 4) {
            0 -> { // concentric rings
                p.style = Paint.Style.STROKE
                for (k in 0 until 9) { p.color = if (k % 2 == 0) a else b; p.strokeWidth = 26f; c.drawCircle(s * 0.62f, s * 0.58f, 40f + k * 48f, p) }
            }
            1 -> { // big sun + horizon waves
                p.color = a; c.drawCircle(s * 0.5f, s * 0.42f, s * 0.3f, p)
                p.color = b
                for (k in 0 until 6) {
                    val path = Path(); val y = s * 0.62f + k * 34f
                    path.moveTo(0f, y); var x = 0f
                    while (x <= s) { path.lineTo(x, y + 12f * sin(x / 40f + k)); x += 10f }
                    path.lineTo(s, s); path.lineTo(0f, s); path.close()
                    p.alpha = 255 - k * 25; c.drawPath(path, p)
                }
                p.alpha = 255
            }
            2 -> { // diagonal stripes + circle cut
                p.shader = LinearGradient(0f, 0f, s, s, a, b, Shader.TileMode.CLAMP)
                c.drawCircle(s * 0.4f, s * 0.6f, s * 0.38f, p); p.shader = null
                p.color = bg; p.strokeWidth = 18f
                for (k in -10..10) c.drawLine(k * 50f, 0f, k * 50f + s, s, p)
            }
            else -> { // swirl
                p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
                for (k in 0 until 14) {
                    p.color = if (k % 2 == 0) a else b; p.strokeWidth = 22f
                    c.drawArc(RectF(s * 0.5f - 30f - k * 22f, s * 0.5f - 30f - k * 22f, s * 0.5f + 30f + k * 22f, s * 0.5f + 30f + k * 22f), k * 31f, 240f, false, p)
                }
            }
        }
        return bmp
    }

    @Before fun setup() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        coil.Coil.setImageLoader(
            coil.ImageLoader.Builder(ctx).components { add(com.arios.player.artwork.ArtworkFetcher.Factory()) }
                .allowHardware(false).crossfade(false).build()
        )
        Arios.prefs.autoFetchLyrics = false
        Arios.prefs.autoFetchArtwork = false
        Arios.prefs.accent = 6
        Arios.library.setForTest(songs)
        songs.forEachIndexed { i, s -> FileOutputStream(Arios.artwork.songFile(s.id)).use { cover(i).compress(Bitmap.CompressFormat.PNG, 100, it) } }
        runBlocking { Arios.lyrics.saveRaw(songs[0], lrc, "LRCLIB") }
        if (!Arios.userStore.isFavorite(songs[0].key)) Arios.userStore.toggleFavorite(songs[0].key)
        val lv = FloatArray(Spectrum.BANDS) { k -> (0.85f - k * 0.018f + 0.18f * sin(k * 1.3f)).coerceIn(0.08f, 1f) }
        Spectrum.setForTest(lv, 0.7f)
    }

    private fun playing(s: Song, pos: Long) {
        Arios.player.testPosition = pos
        Arios.player.setStateForTest(PlayerUiState(
            connected = true, songId = s.id, title = s.title, artist = s.artist, isPlaying = true,
            durationMs = s.durationMs, queue = songs.mapIndexed { i, x -> QueueEntry(i, "song:${x.id}", x.id, x.title, x.artist) },
            index = songs.indexOf(s), hasMedia = true,
        ))
    }

    private fun shoot(name: String, nav: AppNav) {
        rule.mainClock.autoAdvance = false
        rule.setContent { AriosTheme { androidx.compose.material3.Surface(color = androidx.compose.ui.graphics.Color.Transparent) { AppRoot(nav, true) {} } } }
        repeat(14) { rule.mainClock.advanceTimeBy(300); Thread.sleep(200); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
        rule.onRoot().captureRoboImage("build/ad/$name.png")
    }

    private fun seed() {
        val now = System.currentTimeMillis(); val r = java.util.Random(3)
        for (d in 0 until 30) for (k in 0 until 4 + r.nextInt(7)) {
            val s = songs[if (r.nextFloat() < 0.35f) 0 else r.nextInt(songs.size)]
            val t = now - d * 86_400_000L - (r.nextInt(6) + 18) * 3_600_000L
            Arios.history.add(PlayEvent(s.key, t, s.durationMs, s.artist, s.title))
        }
    }

    @Test fun a_nowPlaying() { playing(songs[0], 16_400); shoot("a_now_playing", AppNav().apply { playerOpen = true }) }
    @Test fun b_lyrics() { playing(songs[0], 16_400); shoot("b_lyrics", AppNav().apply { lyricsFull = true }) }
    @Test fun c_library() { playing(songs[0], 16_400); Arios.prefs.libraryTab = 3; shoot("c_library", AppNav()) }
    @Test fun d_albums() { playing(songs[1], 50_000); Arios.prefs.libraryTab = 2; shoot("d_albums", AppNav()) }
    @Test fun e_sound() {
        playing(songs[0], 16_400)
        AudioFx.update(EqSettings(enabled = true, presetId = "tarab", bass = 620, virtualizer = 420, loudness = 560))
        Arios.prefs.crossfadeSec = 6
        shoot("e_sound", AppNav().apply { stack += Screen.Sound })
    }
    @Test fun f_car() { playing(songs[0], 16_400); shoot("f_car", AppNav().apply { carMode = true }) }
    @Test fun g_stats() { seed(); playing(songs[0], 16_400); shoot("g_stats", AppNav().apply { stack += Screen.Stats }) }
    @Test fun h_share() { playing(songs[0], 16_400); shoot("h_share", AppNav().apply { stack += Screen.ShareLyrics(songs[0].id, 6) }) }
    @Test fun i_settings() { playing(songs[0], 16_400); shoot("i_settings", AppNav().apply { selectTab(Tab.SETTINGS) }) }

    @Test fun j_card() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val l = runBlocking { Arios.lyrics.getLocal(songs[0]) }!!
        val r = com.arios.player.share.LyricCardRenderer(ctx)
        val spec = com.arios.player.share.CardSpec(com.arios.player.share.CardStyle.GLASS, com.arios.player.share.CardAspect.STORY,
            l.lines.filter { it.text.isNotBlank() }.subList(3, 5), songs[0].title, songs[0].artist, cover(0),
            0xFF4DB2FF.toInt(), 0xFFDDEFFF.toInt(), 0xFF2F4FE0.toInt())
        java.io.File("build/ad").mkdirs()
        FileOutputStream("build/ad/j_card.png").use { r.render(spec).compress(Bitmap.CompressFormat.PNG, 100, it) }
        for ((i, t) in listOf(31_200L, 32_600L, 33_900L, 34_900L).withIndex()) {
            val b = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
            r.draw(Canvas(b), spec, 720, 1280, t, i / 4f)
            FileOutputStream("build/ad/j_card_t$i.png").use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun k_lyricsClip() {
        playing(songs[0], 14_200)
        val nav = AppNav().apply { lyricsFull = true }
        rule.mainClock.autoAdvance = false
        rule.setContent { AriosTheme { androidx.compose.material3.Surface(color = androidx.compose.ui.graphics.Color.Transparent) { AppRoot(nav, true) {} } } }
        repeat(10) { rule.mainClock.advanceTimeBy(300); Thread.sleep(150); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
        java.io.File("build/ad/clip_lyrics").mkdirs()
        for (f in 0 until 150) { // 5 s at 30 fps, 14.2 s → 19.2 s of the song
            Arios.player.testPosition = 14_200L + f * 1000L / 30
            rule.mainClock.advanceTimeBy(33)
            rule.onRoot().captureRoboImage("build/ad/clip_lyrics/f_" + (1000 + f) + ".png")
        }
    }

    @Test fun l_cardClip() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val l = runBlocking { Arios.lyrics.getLocal(songs[0]) }!!
        val r = com.arios.player.share.LyricCardRenderer(ctx)
        val spec = com.arios.player.share.CardSpec(com.arios.player.share.CardStyle.GLASS, com.arios.player.share.CardAspect.STORY,
            l.lines.filter { it.text.isNotBlank() }.subList(3, 5), songs[0].title, songs[0].artist, cover(0),
            0xFF4DB2FF.toInt(), 0xFFDDEFFF.toInt(), 0xFF2F4FE0.toInt())
        java.io.File("build/ad/clip_card").mkdirs()
        for (f in 0 until 270) { // 9 s
            val b = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
            r.draw(Canvas(b), spec, 720, 1280, 30_300L + f * 1000L / 30, f / 270f)
            FileOutputStream("build/ad/clip_card/f_" + (1000 + f) + ".png").use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
