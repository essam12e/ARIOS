package com.arios.player.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.arios.player.Arios
import com.arios.player.ui.theme.AriosTheme

class MainActivity : AppCompatActivity() {
    private val nav = AppNav()
    private var hasPermission by mutableStateOf(false)
    private val handler = Handler(Looper.getMainLooper())
    private val rescan = Runnable { Arios.library.refresh() }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(rescan); handler.postDelayed(rescan, 1500)
        }
    }

    private val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        hasPermission = res[audioPermission] == true || granted()
        if (hasPermission) onPermissionGranted()
    }

    private fun granted() = ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val perms = mutableListOf(audioPermission)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT <= 28) perms += Manifest.permission.WRITE_EXTERNAL_STORAGE
        permLauncher.launch(perms.toTypedArray())
    }

    private fun onPermissionGranted() {
        Arios.library.refresh()
        runCatching { contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        hasPermission = granted()
        if (hasPermission) onPermissionGranted() else requestPermissions()
        Arios.player.connect()
        Arios.downloader.schedule()
        handleIntent(intent)
        setContent {
            AriosTheme {
                androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                    AppRoot(nav, hasPermission, ::requestPermissions)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) nav.playerOpen = true
        if (intent.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false)) { nav.selectTab(Tab.DOWNLOADS); nav.downloadsTab = 1 }
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            val uri = intent.data!!
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "audio"
            val item = MediaItem.Builder().setMediaId("uri:$uri").setUri(uri)
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(name).build()).build()
            playExternal(item)
            nav.playerOpen = true
        }
    }

    private fun playExternal(item: MediaItem) {
        Arios.player.playExternal(item)
    }

    override fun onDestroy() {
        runCatching { contentResolver.unregisterContentObserver(observer) }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
        const val EXTRA_OPEN_DOWNLOADS = "open_downloads"
    }
}
