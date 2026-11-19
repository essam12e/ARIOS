package com.arios.player.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.arios.player.Arios
import com.arios.player.data.Song
import com.arios.player.playback.PlayerUiState
import com.arios.player.playback.QueueEntry
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ar-w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val palettes = listOf(
        0xFF1E4D3A.toInt() to 0xFF8FD6B0.toInt(), 0xFF5A2A12.toInt() to 0xFFE2A14A.toInt(),
        0xFF1D2B5C.toInt() to 0xFF7EA3F2.toInt(), 0xFF4A1438.toInt() to 0xFFE07AB8.toInt(),
        0xFF3A3A3A.toInt() to 0xFFD0D0D0.toInt(), 0xFF0F3D4A.toInt() to 0xFF5FD0E0.toInt(),
    )

    private fun song(id: Long, title: String, artist: String, album: String, albumId: Long, dur: Long, folder: String = "/storage/emulated/0/Music") = Song(
        id, Uri.parse("content://media/external/audio/media/$id"), title, artist, album, albumId, dur,
        "$folder/$title.mp3", folder, 1_700_000_000L - id * 1000, id.toInt() % 12, 2020, "audio/mpeg", 5_000_000,
    )

    private val songs = listOf(
        song(1, "Runaway", "AURORA", "All My Demons Greeting Me as a Friend", 10, 248_000),
        song(2, "قارئة الفنجان", "عبد الحليم حافظ", "قارئة الفنجان", 11, 320_000),
        song(3, "Linger", "The Cranberries", "Everybody Else Is Doing It", 12, 274_000),
        song(4, "Fire In The Sky", "Anderson .Paak", "Malibu", 13, 202_000),
        song(5, "بعدك على بالي", "فيروز", "إلى عاصي", 14, 230_000),
        song(6, "Blinding Lights", "The Weeknd", "After Hours", 15, 200_000, "/storage/emulated/0/Music/ARIOS"),
        song(7, "Anima", "ReoNa", "ANIMA", 16, 255_000),
        song(8, "Experience", "Ludovico Einaudi", "In a Time Lapse", 17, 315_000),
        song(9, "Alors on danse", "Stromae", "Cheese", 18, 206_000),
        song(10, "Yellow", "Coldplay", "Parachutes", 19, 266_000),
        song(11, "Zombie", "The Cranberries", "No Need to Argue", 20, 306_000),
        song(12, "Mirrors", "Justin Timberlake", "The 20/20 Experience", 21, 484_000),
    )

    private val arabicLrc = """
        [00:10.00]مرّت الأيام وأنا على البال
        [00:10.00]Days went by and you were on my mind
        [00:15.00]بعدك على بالي يا قمر الحلوين
        [00:15.00]You are still on my mind, moon of the beautiful
        [00:21.00]يا زهرة تشرين يا دهب الغالي
        [00:21.00]O October flower, O precious gold
        [00:27.00]
        [00:31.00]بعدك على بالي يا حلو يا مغرور
        [00:31.00]Still on my mind, sweet proud one
        [00:37.00]يا حبق ومنتور على سطح العالي
        [00:43.00]النهاية
    """.trimIndent()

    @Before fun setup() {
        Arios.prefs.autoFetchLyrics = false
        Arios.prefs.autoFetchArtwork = false
        Arios.library.setForTest(songs)
        songs.forEachIndexed { i, s ->
            val (a, b) = palettes[i % palettes.size]
            val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawRect(0f, 0f, 400f, 400f, Paint().apply { shader = LinearGradient(0f, 0f, 400f, 400f, a, b, Shader.TileMode.CLAMP) })
            c.drawCircle(260f, 150f, 90f, Paint().apply { color = 0x55FFFFFF })
            FileOutputStream(Arios.artwork.cacheFile(s.albumId)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
        runBlocking { Arios.lyrics.saveRaw(songs[4], arabicLrc, "LRCLIB") }
        Arios.userStore.toggleFavorite(songs[4].key).also { if (!Arios.userStore.isFavorite(songs[4].key)) Arios.userStore.toggleFavorite(songs[4].key) }
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
        rule.setContent { AriosTheme { androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) { AppRoot(nav, true) {} } } }
        repeat(10) { rule.mainClock.advanceTimeBy(400); Thread.sleep(300); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
        rule.onRoot().captureRoboImage("build/screens/$name.png")
    }

    @Test fun artworkFetcherResolvesCachedFile() {
        val key = com.arios.player.artwork.ArtKey(10, songs[0].uri.toString(), "a", "b", "c")
        val res = runBlocking { com.arios.player.artwork.ArtworkFetcher(key).fetch() }
        org.junit.Assert.assertTrue(res is coil.fetch.SourceResult)
    }

    @Test fun library() {
        playing(songs[4], 17_200)
        Arios.prefs.libraryTab = 3
        shoot("1_library_tracks", AppNav())
    }

    @Test fun albums() {
        playing(songs[0], 60_000)
        Arios.prefs.libraryTab = 2
        shoot("2_library_albums", AppNav())
    }

    @Test fun nowPlaying() {
        playing(songs[4], 17_200)
        shoot("3_now_playing", AppNav().apply { playerOpen = true })
    }

    @Test fun fullLyrics() {
        playing(songs[4], 16_400)
        shoot("4_full_lyrics", AppNav().apply { lyricsFull = true })
    }
}
