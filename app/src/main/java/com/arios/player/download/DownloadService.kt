package com.arios.player.download

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.arios.player.Arios
import com.arios.player.AriosApp
import com.arios.player.R
import com.arios.player.ui.MainActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Keeps downloads alive in the background and shows their progress. */
class DownloadService : LifecycleService() {
    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_PAUSE_ALL) Arios.downloader.pauseAll()
        if (!started) {
            started = true
            ServiceCompat.startForeground(
                this, NOTIF_ID, build(getString(R.string.downloading), "", 0, true),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
            lifecycleScope.launch {
                Arios.downloader.tasks.collectLatest { tasks ->
                    val active = tasks.filter { it.state == DlState.RUNNING || it.state == DlState.QUEUED }
                    if (active.isEmpty()) {
                        ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@collectLatest
                    }
                    val running = active.firstOrNull { it.state == DlState.RUNNING }
                    val title = getString(R.string.downloading_n, active.size)
                    val pct = ((running?.progress ?: 0f) * 100).toInt()
                    runCatching {
                        NotificationManagerCompat.from(this@DownloadService)
                            .notify(NOTIF_ID, build(title, running?.title ?: "", pct, running == null || running.total <= 0))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(title: String, text: String, progress: Int, indeterminate: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pause = PendingIntent.getService(
            this, 3, Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, AriosApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, indeterminate)
            .setContentIntent(open)
            .addAction(0, getString(R.string.pause_all), pause)
            .build()
    }

    companion object {
        const val NOTIF_ID = 4201
        const val ACTION_PAUSE_ALL = "arios.pause_all"
    }
}
