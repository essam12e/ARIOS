package com.arios.player.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.arios.player.Arios
import com.arios.player.data.Song
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class QueueEntry(val index: Int, val mediaId: String, val songId: Long?, val title: String, val artist: String)

data class PlayerUiState(
    val connected: Boolean = false,
    val songId: Long? = null,
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val speed: Float = 1f,
    val queue: List<QueueEntry> = emptyList(),
    val index: Int = -1,
    val sleepEndsAt: Long = 0,
    val sleepEndOfTrack: Boolean = false,
    val hasMedia: Boolean = false,
)

/** UI-side bridge to [PlaybackService] through a MediaController. */
class PlayerController(private val context: Context) {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state
    private val pending = ArrayList<(MediaController) -> Unit>()

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: run { future = null; return@addListener }
            controller = c
            c.addListener(listener)
            refresh()
            pending.forEach { it(c) }; pending.clear()
            restoreIfEmpty(c)
        }, MoreExecutors.directExecutor())
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else { pending += block; connect() }
    }

    private fun restoreIfEmpty(c: MediaController) {
        if (c.mediaItemCount > 0) return
        Arios.scope.launch {
            val lib = Arios.library.state.first { it.loaded }
            val ids = Arios.prefs.savedQueue
            val items = ids.mapNotNull { lib.byId[it]?.toMediaItem() }
            if (items.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                if (c.mediaItemCount > 0) return@withContext
                val idx = Arios.prefs.savedIndex.coerceIn(0, items.size - 1)
                c.setMediaItems(items, idx, Arios.prefs.savedPosition)
                c.prepare()
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    private fun refresh() {
        val c = controller ?: return
        val item = c.currentMediaItem
        val queue = ArrayList<QueueEntry>(c.mediaItemCount)
        for (i in 0 until c.mediaItemCount) {
            val m = c.getMediaItemAt(i)
            queue += QueueEntry(i, m.mediaId, songIdOf(m.mediaId), m.mediaMetadata.title?.toString() ?: "", m.mediaMetadata.artist?.toString() ?: "")
        }
        _state.value = _state.value.copy(
            connected = true,
            songId = item?.mediaId?.let(::songIdOf),
            title = item?.mediaMetadata?.title?.toString() ?: "",
            artist = item?.mediaMetadata?.artist?.toString() ?: "",
            isPlaying = c.isPlaying,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            durationMs = c.duration.takeIf { it > 0 } ?: 0,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            speed = c.playbackParameters.speed,
            queue = queue,
            index = c.currentMediaItemIndex,
            hasMedia = item != null,
        )
    }

    /** Current playback position; cheap enough to call every frame. */
    fun position(): Long = testPosition ?: controller?.currentPosition ?: 0L

    /** Test hooks used by screenshot tests. */
    internal var testPosition: Long? = null
    internal fun setStateForTest(s: PlayerUiState) { _state.value = s }
    fun bufferedPosition(): Long = controller?.bufferedPosition ?: 0L

    fun playSongs(songs: List<Song>, start: Int = 0, shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        withController { c ->
            c.shuffleModeEnabled = shuffle
            val startIdx = if (shuffle) songs.indices.random() else start.coerceIn(0, songs.size - 1)
            c.setMediaItems(songs.map { it.toMediaItem() }, startIdx, 0)
            c.prepare(); c.play()
        }
    }

    fun playExternal(item: MediaItem) = withController { c ->
        c.setMediaItem(item); c.prepare(); c.play()
    }

    fun playNext(songs: List<Song>) = withController { c ->
        if (c.mediaItemCount == 0) { playSongs(songs); return@withController }
        c.addMediaItems(c.currentMediaItemIndex + 1, songs.map { it.toMediaItem() })
    }

    fun enqueue(songs: List<Song>) = withController { c ->
        if (c.mediaItemCount == 0) { playSongs(songs); return@withController }
        c.addMediaItems(songs.map { it.toMediaItem() })
    }

    fun toggle() = withController { c ->
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
            c.play()
        }
    }
    fun next() = withController { it.seekToNextMediaItem() }
    fun previous() = withController { c ->
        if (c.currentPosition > 4000 || !c.hasPreviousMediaItem()) c.seekTo(0) else c.seekToPreviousMediaItem()
    }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun seekBy(ms: Long) = withController { it.seekTo((it.currentPosition + ms).coerceAtLeast(0)) }
    fun setShuffle(on: Boolean) = withController { it.shuffleModeEnabled = on }
    fun cycleRepeat() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
    fun setSpeed(speed: Float) = withController { it.playbackParameters = PlaybackParameters(speed, 1f) }
    fun playAt(index: Int) = withController { c -> c.seekToDefaultPosition(index); c.play() }
    fun moveQueue(from: Int, to: Int) = withController { it.moveMediaItem(from, to) }
    fun removeFromQueue(index: Int) = withController { it.removeMediaItem(index) }
    fun clearQueueExceptCurrent() = withController { c ->
        val cur = c.currentMediaItemIndex
        if (cur < 0) return@withController
        if (cur + 1 < c.mediaItemCount) c.removeMediaItems(cur + 1, c.mediaItemCount)
        if (cur > 0) c.removeMediaItems(0, cur)
    }
    fun toggleFavoriteViaSession() = withController {
        it.sendCustomCommand(SessionCommand(PlaybackService.CMD_FAVORITE, Bundle.EMPTY), Bundle.EMPTY)
    }

    /** Refresh the replaced media items after a library rescan (e.g. new tags). */
    fun replaceItem(index: Int, item: MediaItem) = withController { it.replaceMediaItem(index, item) }

    fun audioSessionId(): Int = PlaybackService.instance?.player?.audioSessionId ?: 0

    // ---- sleep timer (runs inside the playback service, survives closing the app)
    fun setSleepTimer(minutes: Int) = withController { PlaybackService.instance?.engine?.setSleep(minutes) }
    fun setSleepEndOfTrack(on: Boolean) = withController { PlaybackService.instance?.engine?.setSleepEndOfTrack(on) }

    init {
        Arios.scope.launch { AudioState.sleepEndsAt.collect { v -> _state.value = _state.value.copy(sleepEndsAt = v) } }
        Arios.scope.launch { AudioState.sleepEndOfTrack.collect { v -> _state.value = _state.value.copy(sleepEndOfTrack = v) } }
    }
}
