package com.arios.player.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.arios.player.Arios
import com.arios.player.R
import com.arios.player.data.LibraryState
import com.arios.player.data.Song
import com.arios.player.ui.MainActivity
import com.arios.player.widget.AriosWidget
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    lateinit var player: ExoPlayer
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null
    private var searchResults: List<Song> = emptyList()
    var engine: AudioEngine? = null
        private set

    override fun onCreate() {
        super.onCreate()
        val prefs = Arios.prefs
        // renderers with a PCM tap so the visualizer can see the music (no mic permission)
        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(Spectrum.processor))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
        }
        player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.shuffleModeEnabled = prefs.shuffle
        player.repeatMode = prefs.repeatMode
        player.playbackParameters = PlaybackParameters(prefs.playbackSpeed)
        player.addListener(listener)
        engine = AudioEngine(this, player, scope).also { it.attach() }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, Callback())
            .setSessionActivity(open)
            .setCustomLayout(customLayout(false))
            .build()
        instance = this
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            saveState(); stopSelf()
        }
    }

    override fun onDestroy() {
        saveState()
        engine?.release(); engine = null
        instance = null
        scope.cancel()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ state

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { saveState(); refreshExtras() }
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { saveState() }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refreshExtras()
            ticker?.cancel()
            if (isPlaying) ticker = scope.launch {
                while (isActive) { delay(5000); Arios.prefs.savedPosition = player.currentPosition }
            } else saveState()
        }
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { Arios.prefs.shuffle = shuffleModeEnabled }
        override fun onRepeatModeChanged(repeatMode: Int) { Arios.prefs.repeatMode = repeatMode }
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            Arios.prefs.playbackSpeed = playbackParameters.speed
            engine?.setSpeed(playbackParameters)
        }
        override fun onPlayerError(error: PlaybackException) {
            // skip unplayable/missing files instead of stopping
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem(); player.prepare(); player.play()
            }
        }
    }

    private fun saveState() {
        if (!::player.isInitialized) return
        val ids = (0 until player.mediaItemCount).mapNotNull { songIdOf(player.getMediaItemAt(it).mediaId) }
        if (ids.isEmpty()) return
        Arios.prefs.savedQueue = ids
        Arios.prefs.savedIndex = player.currentMediaItemIndex
        Arios.prefs.savedPosition = player.currentPosition
    }

    private fun currentSong(): Song? =
        player.currentMediaItem?.mediaId?.let(::songIdOf)?.let { Arios.library.state.value.byId[it] }

    /** Updates the favourite button in the notification and the home-screen widget. */
    fun refreshExtras() {
        val song = currentSong()
        val fav = song != null && Arios.userStore.isFavorite(song.key)
        session?.setCustomLayout(customLayout(fav))
        AriosWidget.updateAll(this, song, player.isPlaying)
    }

    private fun customLayout(fav: Boolean): ImmutableList<CommandButton> = ImmutableList.of(
        CommandButton.Builder()
            .setDisplayName(getString(if (fav) R.string.remove_favorite else R.string.add_favorite))
            .setIconResId(if (fav) R.drawable.ic_heart else R.drawable.ic_heart_outline)
            .setSessionCommand(SessionCommand(CMD_FAVORITE, Bundle.EMPTY))
            .build()
    )

    // ------------------------------------------------------------------ library tree (Android Auto)

    private fun children(lib: LibraryState, parentId: String): List<MediaItem> {
        fun s(id: Int): String = getString(id)
        return when {
            parentId == ROOT -> listOf(
                browsable(SONGS, s(R.string.tab_tracks), type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                browsable(ALBUMS, s(R.string.tab_albums), type = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                browsable(ARTISTS, s(R.string.tab_artists), type = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
                browsable(PLAYLISTS, s(R.string.tab_playlists), type = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                browsable(FAVORITES, s(R.string.tab_favorites), type = MediaMetadata.MEDIA_TYPE_PLAYLIST),
                browsable(FOLDERS, s(R.string.tab_folders)),
            )
            parentId == SONGS -> lib.songs.map { it.toMediaItem("$SONGS::${it.id}") }
            parentId == ALBUMS -> lib.albums.map {
                browsable("album:${it.id}", it.title, it.artist, artUri(it.cover), MediaMetadata.MEDIA_TYPE_ALBUM)
            }
            parentId == ARTISTS -> lib.artists.map {
                browsable("artist:${it.name}", it.name, "${it.songs.size}", artUri(it.cover), MediaMetadata.MEDIA_TYPE_ARTIST)
            }
            parentId == FOLDERS -> lib.folders.map { browsable("folder:${it.path}", it.name, "${it.songs.size}") }
            parentId == PLAYLISTS -> Arios.userStore.playlists.value.map {
                browsable("playlist:${it.id}", it.name, "${it.songKeys.size}", type = MediaMetadata.MEDIA_TYPE_PLAYLIST)
            }
            else -> songsOf(lib, parentId).map { it.toMediaItem("$parentId::${it.id}") }
        }
    }

    private fun songsOf(lib: LibraryState, parentId: String): List<Song> = when {
        parentId == SONGS -> lib.songs
        parentId == FAVORITES -> lib.songs.filter { Arios.userStore.isFavorite(it.key) }
        parentId.startsWith("album:") -> lib.albums.firstOrNull { it.id.toString() == parentId.removePrefix("album:") }?.songs.orEmpty()
        parentId.startsWith("artist:") -> lib.artists.firstOrNull { it.name == parentId.removePrefix("artist:") }?.songs.orEmpty()
        parentId.startsWith("folder:") -> lib.folders.firstOrNull { it.path == parentId.removePrefix("folder:") }?.songs.orEmpty()
        parentId.startsWith("playlist:") -> Arios.userStore.playlists.value.firstOrNull { it.id == parentId.removePrefix("playlist:") }
            ?.songKeys?.mapNotNull { lib.byKey[it] }.orEmpty()
        else -> emptyList()
    }

    private fun resolve(lib: LibraryState, item: MediaItem): MediaItem? {
        if (item.localConfiguration != null) return item
        val id = songIdOf(item.mediaId)
        val song = id?.let { lib.byId[it] }
        if (song != null) return song.toMediaItem(item.mediaId)
        val uri = item.requestMetadata.mediaUri ?: return null
        return item.buildUpon().setUri(uri).build()
    }

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_FAVORITE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.accept(commands, MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) { refreshExtras() }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_FAVORITE) {
                currentSong()?.let { Arios.userStore.toggleFavorite(it.key) }
                refreshExtras()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(browsable(ROOT, getString(R.string.app_name)), params))

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val lib = Arios.library.ensureLoaded()
            val all = children(lib, parentId)
            val from = (page * pageSize).coerceAtMost(all.size)
            val to = (from + pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(if (pageSize > 0) all.subList(from, to) else all), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val lib = Arios.library.ensureLoaded()
            val song = songIdOf(mediaId)?.let { lib.byId[it] }
            if (song != null) LibraryResult.ofItem(song.toMediaItem(mediaId), null)
            else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            val lib = Arios.library.ensureLoaded()
            val q = query.trim().lowercase()
            searchResults = lib.songs.filter {
                it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q)
            }
            session.notifySearchResultChanged(browser, query, searchResults.size, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val items = searchResults.map { it.toMediaItem() }
            val from = (page * pageSize).coerceAtMost(items.size)
            val to = (from + pageSize).coerceAtMost(items.size)
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, to)), params))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            val lib = Arios.library.ensureLoaded()
            mediaItems.mapNotNull { resolve(lib, it) }.toMutableList()
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
            startIndex: Int, startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val lib = Arios.library.ensureLoaded()
            // A single item picked from a list in Android Auto -> play the whole list from it
            val single = mediaItems.singleOrNull()
            if (single != null && single.mediaId.contains("::") && single.localConfiguration == null) {
                val parent = single.mediaId.substringBeforeLast("::")
                val list = songsOf(lib, parent)
                val id = songIdOf(single.mediaId)
                val idx = list.indexOfFirst { it.id == id }
                if (idx >= 0) return@future MediaSession.MediaItemsWithStartPosition(
                    list.map { it.toMediaItem("$parent::${it.id}") }, idx, startPositionMs,
                )
            }
            val resolved = mediaItems.mapNotNull { resolve(lib, it) }
            MediaSession.MediaItemsWithStartPosition(resolved, startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0)), startPositionMs)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val lib = Arios.library.ensureLoaded()
            val ids = Arios.prefs.savedQueue
            val items = ids.mapNotNull { lib.byId[it]?.toMediaItem() }
            val list = items.ifEmpty { lib.songs.take(200).map { it.toMediaItem() } }
            val idx = Arios.prefs.savedIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0))
            MediaSession.MediaItemsWithStartPosition(list, idx, if (items.isEmpty()) 0 else Arios.prefs.savedPosition)
        }
    }

    companion object {
        const val CMD_FAVORITE = "arios.favorite"
        const val ROOT = "root"
        const val SONGS = "songs"
        const val ALBUMS = "albums"
        const val ARTISTS = "artists"
        const val PLAYLISTS = "playlists"
        const val FAVORITES = "favorites"
        const val FOLDERS = "folders"

        @Volatile var instance: PlaybackService? = null
            private set
    }
}
