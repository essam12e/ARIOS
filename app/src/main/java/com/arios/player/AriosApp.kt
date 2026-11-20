package com.arios.player

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.arios.player.artwork.ArtworkFetcher
import com.arios.player.artwork.ArtworkRepository
import com.arios.player.data.LibraryRepository
import com.arios.player.data.Prefs
import com.arios.player.data.UserStore
import com.arios.player.download.Downloader
import com.arios.player.lyrics.LyricsRepository
import com.arios.player.playback.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Simple service locator: every long-lived component of the app lives here. */
object Arios {
    lateinit var app: AriosApp
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                val b = req.newBuilder()
                if (req.header("User-Agent") == null) {
                    b.header("User-Agent", "ARIOS/${BuildConfigInfo.VERSION} (https://github.com/essam12e/ARIOS)")
                }
                chain.proceed(b.build())
            }
            .build()
    }
    lateinit var prefs: Prefs; private set
    lateinit var library: LibraryRepository; private set
    lateinit var userStore: UserStore; private set
    lateinit var lyrics: LyricsRepository; private set
    lateinit var artwork: ArtworkRepository; private set
    lateinit var downloader: Downloader; private set
    lateinit var player: PlayerController; private set

    internal fun init(a: AriosApp) {
        app = a
        prefs = Prefs(a)
        userStore = UserStore(a)
        library = LibraryRepository(a)
        lyrics = LyricsRepository(a)
        artwork = ArtworkRepository(a)
        downloader = Downloader(a)
        player = PlayerController(a)
    }
}

object BuildConfigInfo { const val VERSION = "1.1.0" }

class AriosApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Arios.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(ArtworkFetcher.Factory()) }
        .crossfade(true)
        .build()

    companion object {
        const val CHANNEL_DOWNLOADS = "arios_downloads"
    }
}
