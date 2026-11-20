package com.arios.player.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.media3.session.MediaButtonReceiver
import com.arios.player.Arios
import com.arios.player.R
import com.arios.player.artwork.artKey
import com.arios.player.data.Song
import com.arios.player.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Large widget that shows the lyric line being sung right now. */
class AriosLyricsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val lib = Arios.library.state.value
        val song = Arios.prefs.savedQueue.getOrNull(Arios.prefs.savedIndex)?.let { lib.byId[it] }
        update(context, song, Arios.player.state.value.isPlaying, null, null)
    }

    companion object {
        private var lastArtFor: Long = -1
        private var lastArt: android.graphics.Bitmap? = null

        fun hasWidgets(context: Context): Boolean =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, AriosLyricsWidget::class.java)).isNotEmpty()

        private fun button(context: Context, keyCode: Int): PendingIntent = PendingIntent.getBroadcast(
            context, 100 + keyCode,
            Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(ComponentName(context, MediaButtonReceiver::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun update(context: Context, song: Song?, playing: Boolean, line: String?, next: String?) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, AriosLyricsWidget::class.java))
            if (ids.isEmpty()) return
            Arios.scope.launch(Dispatchers.IO) {
                val v = RemoteViews(app.packageName, R.layout.widget_lyrics)
                v.setTextViewText(R.id.wl_title, song?.title ?: app.getString(R.string.nothing_playing))
                v.setTextViewText(R.id.wl_artist, song?.artist ?: app.getString(R.string.app_name))
                v.setTextViewText(R.id.wl_line, line ?: if (song == null) "" else "♪")
                v.setTextViewText(R.id.wl_next, next ?: "")
                if (song != null && song.id != lastArtFor) {
                    lastArt = runCatching { Arios.artwork.loadBitmapBlocking(song.artKey(), 240) }.getOrNull()
                    lastArtFor = song.id
                }
                if (song != null && lastArt != null) v.setImageViewBitmap(R.id.wl_art, lastArt)
                else v.setImageViewResource(R.id.wl_art, R.drawable.arios_logo)
                v.setImageViewResource(R.id.wl_play, if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                v.setOnClickPendingIntent(R.id.wl_play, button(app, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                v.setOnClickPendingIntent(R.id.wl_next_btn, button(app, KeyEvent.KEYCODE_MEDIA_NEXT))
                v.setOnClickPendingIntent(R.id.wl_prev, button(app, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                v.setOnClickPendingIntent(R.id.wl_root, PendingIntent.getActivity(
                    app, 7, Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_LYRICS, true),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ))
                manager.updateAppWidget(ids, v)
            }
        }
    }
}
