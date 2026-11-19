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

/** Home-screen widget: cover, title, artist and transport controls. */
class AriosWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val lib = Arios.library.state.value
        val song = Arios.prefs.savedQueue.getOrNull(Arios.prefs.savedIndex)?.let { lib.byId[it] }
        val playing = Arios.player.state.value.isPlaying
        updateAll(context, song, playing)
        if (!lib.loaded) Arios.scope.launch {
            val l = Arios.library.ensureLoaded()
            val s = Arios.prefs.savedQueue.getOrNull(Arios.prefs.savedIndex)?.let { l.byId[it] }
            updateAll(context, s, playing)
        }
    }

    companion object {
        private fun mediaButton(context: Context, keyCode: Int): PendingIntent {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(ComponentName(context, MediaButtonReceiver::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            return PendingIntent.getBroadcast(context, keyCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        fun updateAll(context: Context, song: Song?, playing: Boolean) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, AriosWidget::class.java))
            if (ids.isEmpty()) return
            Arios.scope.launch(Dispatchers.IO) {
                val views = RemoteViews(app.packageName, R.layout.widget_player)
                views.setTextViewText(R.id.widget_title, song?.title ?: app.getString(R.string.nothing_playing))
                views.setTextViewText(R.id.widget_artist, song?.artist ?: app.getString(R.string.app_name))
                val bmp = song?.let { runCatching { Arios.artwork.loadBitmapBlocking(it.artKey(), 300) }.getOrNull() }
                if (bmp != null) views.setImageViewBitmap(R.id.widget_art, bmp)
                else views.setImageViewResource(R.id.widget_art, R.drawable.arios_logo)
                views.setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                views.setOnClickPendingIntent(R.id.widget_play, mediaButton(app, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                views.setOnClickPendingIntent(R.id.widget_next, mediaButton(app, KeyEvent.KEYCODE_MEDIA_NEXT))
                views.setOnClickPendingIntent(R.id.widget_prev, mediaButton(app, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                val open = PendingIntent.getActivity(
                    app, 1, Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                views.setOnClickPendingIntent(R.id.widget_root, open)
                manager.updateAppWidget(ids, views)
            }
        }
    }
}
