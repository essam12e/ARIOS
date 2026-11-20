package com.arios.player.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.arios.player.Arios
import com.arios.player.data.PlayEvent
import com.arios.player.data.Song
import com.arios.player.widget.AriosLyricsWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Observable audio state shared with the UI. */
object AudioState {
    /** epoch ms when the sleep timer fires (0 = off) */
    val sleepEndsAt = MutableStateFlow(0L)
    /** stop when the current song ends */
    val sleepEndOfTrack = MutableStateFlow(false)
    val crossfading = MutableStateFlow(false)
    val audioSession = MutableStateFlow(0)
}

/**
 * Everything that happens "around" playback:
 * volume mixing (normalisation × crossfade × sleep fade), true crossfade with a second player,
 * sleep timer, resume for long audio, listening history and the live-lyrics widget.
 */
@OptIn(UnstableApi::class)
class AudioEngine(private val context: Context, private val player: ExoPlayer, private val scope: CoroutineScope) {
    private var rgFactor = 1f
    private var fadeFactor = 1f
    private var sleepFactor = 1f
    private var secondary: ExoPlayer? = null
    private var crossfadeStart = 0L
    private var crossfadeMs = 0L
    private var handoff = false
    private var ticker: Job? = null

    // history
    private var listenedMs = 0L
    private var counted = false
    private var lastTick = 0L
    private var currentKey: String? = null
    private var lastLyricIdx = -2

    val sleepEndsAt: StateFlow<Long> = AudioState.sleepEndsAt

    fun attach() {
        player.addListener(listener)
        AudioState.audioSession.value = player.audioSessionId
        AudioFx.attach(player.audioSessionId)
        onItemChanged()
        start()
    }

    fun release() {
        ticker?.cancel()
        flushListen()
        secondary?.release(); secondary = null
        AudioFx.detach(player.audioSessionId)
    }

    private fun song(item: MediaItem?): Song? = item?.mediaId?.let(::songIdOf)?.let { Arios.library.state.value.byId[it] }
    private fun current(): Song? = song(player.currentMediaItem)

    private fun applyVolume() { player.volume = (rgFactor * fadeFactor * sleepFactor).coerceIn(0f, 1f) }

    private val listener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            AudioState.audioSession.value = audioSessionId
            AudioFx.attach(audioSessionId)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            flushListen()
            if (AudioState.sleepEndOfTrack.value && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // safety net – pauseAtEndOfMediaItems normally stops us before this
                player.pause(); clearSleep()
            }
            if (crossfadeMs > 0 && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && secondary?.isPlaying == true) handOff()
            else cancelCrossfade()
            onItemChanged()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) {
                saveLongPosition()
                if (!handoff) secondary?.pause()
            } else if (crossfadeMs > 0 && !handoff) secondary?.play()
            lastTick = System.currentTimeMillis()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK && !handoff) cancelCrossfade()
        }
    }

    private fun onItemChanged() {
        val s = current()
        currentKey = s?.key
        listenedMs = 0; counted = false; lastLyricIdx = -2
        // normalisation
        rgFactor = if (Arios.prefs.normalize && s != null) Arios.loudness.factor(s) ?: 1f else 1f
        applyVolume()
        if (Arios.prefs.normalize && s != null) scope.launch {
            Arios.loudness.ensure(s)
            if (current()?.key == s.key) { rgFactor = Arios.loudness.factor(s) ?: 1f; applyVolume() }
            // warm up the next song too
            nextItem()?.let(::song)?.let { Arios.loudness.ensure(it) }
        }
        // resume long audio where it was left
        if (s != null && isLong(s) && Arios.prefs.resumeLong && !handoff) {
            val pos = Arios.positions.get(s.key)
            if (pos > 10_000 && pos < s.durationMs - 30_000) player.seekTo(pos)
        }
        refreshLyricWidget(force = true)
    }

    fun isLong(s: Song) = s.durationMs >= 20 * 60_000L

    private fun saveLongPosition() {
        val s = current() ?: return
        if (!isLong(s)) return
        val pos = player.currentPosition
        if (pos > s.durationMs - 30_000) Arios.positions.clear(s.key) else Arios.positions.set(s.key, pos)
    }

    // ------------------------------------------------------------------ ticker

    private fun start() {
        ticker?.cancel()
        ticker = scope.launch {
            var n = 0
            while (isActive) {
                delay(60)
                n++
                val now = System.currentTimeMillis()
                if (player.isPlaying) {
                    listenedMs += (now - lastTick).coerceIn(0, 1000)
                    maybeCount()
                    if (n % 80 == 0) saveLongPosition()
                }
                lastTick = now
                tickSleep(now)
                tickCrossfade(now)
                if (n % 8 == 0) refreshLyricWidget()
            }
        }
    }

    private fun maybeCount() {
        if (counted) return
        val s = current() ?: return
        if (listenedMs >= minOf(30_000L, s.durationMs / 2)) {
            counted = true
            Arios.history.add(PlayEvent(s.key, System.currentTimeMillis(), 0, s.artist, s.title))
        }
    }

    private fun flushListen() {
        // amend the last event with the real listening time
        val key = currentKey ?: return
        if (counted && listenedMs > 0) {
            val ev = Arios.history.events.value.lastOrNull { it.key == key } ?: return
            if (ev.ms == 0L) Arios.history.replaceLast(ev.copy(ms = listenedMs))
        }
    }

    // ------------------------------------------------------------------ sleep timer

    fun setSleep(minutes: Int) {
        AudioState.sleepEndOfTrack.value = false
        player.pauseAtEndOfMediaItems = false
        AudioState.sleepEndsAt.value = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0
        if (minutes <= 0) { sleepFactor = 1f; applyVolume() }
    }

    fun setSleepEndOfTrack(on: Boolean) {
        AudioState.sleepEndsAt.value = 0
        AudioState.sleepEndOfTrack.value = on
        player.pauseAtEndOfMediaItems = on
        if (on) cancelCrossfade()
    }

    private fun clearSleep() {
        AudioState.sleepEndsAt.value = 0
        AudioState.sleepEndOfTrack.value = false
        player.pauseAtEndOfMediaItems = false
        sleepFactor = 1f; applyVolume()
    }

    private fun tickSleep(now: Long) {
        if (AudioState.sleepEndOfTrack.value && !player.isPlaying && player.playbackState == Player.STATE_READY &&
            player.currentPosition >= player.duration - 500 && player.duration > 0
        ) {
            // paused at the end of the item: move to the next one, ready for tomorrow
            clearSleep()
            if (player.hasNextMediaItem()) player.seekToNextMediaItem()
            return
        }
        val end = AudioState.sleepEndsAt.value
        if (end == 0L) return
        val left = end - now
        val fadeMs = if (Arios.prefs.sleepFade) 30_000L else 0L
        if (left <= 0) {
            player.pause(); clearSleep()
        } else if (left < fadeMs) {
            // gentle fade-out over the last 30 s
            val t = left.toFloat() / fadeMs
            sleepFactor = t * t
            applyVolume()
        }
    }

    // ------------------------------------------------------------------ crossfade

    private fun nextItem(): MediaItem? {
        val i = player.nextMediaItemIndex
        return if (i == C.INDEX_UNSET) null else player.getMediaItemAt(i)
    }

    private fun tickCrossfade(now: Long) {
        val cfSec = Arios.prefs.crossfadeSec
        if (handoff) return
        if (crossfadeMs > 0) {
            // ramp: equal-power curves
            val p = ((now - crossfadeStart).toFloat() / crossfadeMs).coerceIn(0f, 1f)
            fadeFactor = cos(p * PI / 2).toFloat()
            applyVolume()
            secondary?.volume = (sin(p * PI / 2).toFloat() * secondaryRg).coerceIn(0f, 1f)
            return
        }
        if (cfSec <= 0 || !player.isPlaying || AudioState.sleepEndOfTrack.value) return
        if (player.repeatMode == Player.REPEAT_MODE_ONE) return
        val dur = player.duration
        if (dur <= 0 || dur < cfSec * 3000L) return
        val speed = player.playbackParameters.speed.coerceAtLeast(0.1f)
        val remaining = ((dur - player.currentPosition) / speed).toLong()
        if (remaining > cfSec * 1000L || remaining < 400) return
        val next = nextItem() ?: return
        startCrossfade(next, remaining)
    }

    private var secondaryRg = 1f

    private fun startCrossfade(next: MediaItem, durationMs: Long) {
        val sec = secondary ?: ExoPlayer.Builder(context)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            .build().also {
                secondary = it
                it.addListener(object : Player.Listener {
                    override fun onAudioSessionIdChanged(audioSessionId: Int) { AudioFx.attach(audioSessionId) }
                })
            }
        val s = song(next)
        secondaryRg = if (Arios.prefs.normalize && s != null) Arios.loudness.factor(s) ?: 1f else 1f
        sec.playbackParameters = player.playbackParameters
        sec.volume = 0f
        sec.setMediaItem(next)
        sec.prepare()
        sec.play()
        AudioFx.attach(sec.audioSessionId)
        crossfadeStart = System.currentTimeMillis()
        crossfadeMs = durationMs.coerceAtLeast(500)
        AudioState.crossfading.value = true
    }

    /** Main player just moved to the song the secondary is already playing: line them up and swap. */
    private fun handOff() {
        val sec = secondary ?: return cancelCrossfade()
        handoff = true
        fadeFactor = 0f; applyVolume()
        val target = sec.currentPosition + 120
        player.seekTo(target)
        scope.launch {
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 2500 && !(player.playbackState == Player.STATE_READY && player.isPlaying)) delay(15)
            delay(60)
            // quick 120 ms swap so the join is inaudible
            val steps = 6
            for (i in 1..steps) {
                val p = i / steps.toFloat()
                fadeFactor = p; applyVolume()
                sec.volume = ((1 - p) * secondaryRg).coerceAtLeast(0f)
                delay(20)
            }
            sec.stop(); sec.clearMediaItems()
            handoff = false
            crossfadeMs = 0
            AudioState.crossfading.value = false
            fadeFactor = 1f; applyVolume()
        }
    }

    private fun cancelCrossfade() {
        if (crossfadeMs == 0L && !handoff) return
        handoff = false
        crossfadeMs = 0
        secondary?.stop(); secondary?.clearMediaItems()
        AudioState.crossfading.value = false
        fadeFactor = 1f; applyVolume()
    }

    fun setSpeed(p: PlaybackParameters) { secondary?.playbackParameters = p }

    // ------------------------------------------------------------------ live lyrics widget

    private fun refreshLyricWidget(force: Boolean = false) {
        if (!AriosLyricsWidget.hasWidgets(context)) return
        val s = current()
        scope.launch {
            val lyrics = s?.let { runCatching { Arios.lyrics.getLocal(it) }.getOrNull() }
            val idx = lyrics?.indexAt(player.currentPosition - (s.key.let { Arios.userStore.offset(it) })) ?: -1
            if (!force && idx == lastLyricIdx) return@launch
            lastLyricIdx = idx
            val line = lyrics?.lines?.getOrNull(idx)?.text?.takeIf { it.isNotBlank() }
                ?: lyrics?.lines?.firstOrNull { !lyrics.synced }?.text
            val next = lyrics?.lines?.getOrNull(idx + 1)?.text
            AriosLyricsWidget.update(context, s, player.isPlaying, line, next)
        }
    }
}
