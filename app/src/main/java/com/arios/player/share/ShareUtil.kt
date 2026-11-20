package com.arios.player.share

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

object ShareUtil {
    fun dir(context: Context) = File(context.cacheDir, "share").apply { mkdirs() }

    fun shareFile(context: Context, file: File, mime: String, text: String? = null) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (text != null) i.putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareBitmap(context: Context, bmp: Bitmap, name: String = "arios_lyrics.png", text: String? = null) {
        val f = File(dir(context), name)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shareFile(context, f, "image/png", text)
    }

    /** Saves to Pictures/ARIOS (or Movies/ARIOS for video). */
    fun saveToGallery(context: Context, file: File, mime: String): Boolean = runCatching {
        val isVideo = mime.startsWith("video")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "ARIOS_${System.currentTimeMillis()}.${file.extension}")
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/ARIOS")
            }
            val col = if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = context.contentResolver.insert(col, values) ?: return false
            context.contentResolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
        } else {
            @Suppress("DEPRECATION")
            val d = File(Environment.getExternalStoragePublicDirectory(if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES), "ARIOS").apply { mkdirs() }
            file.copyTo(File(d, "ARIOS_${System.currentTimeMillis()}.${file.extension}"))
        }
        true
    }.getOrDefault(false)
}
