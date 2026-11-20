package com.arios.player.share

import android.content.Context
import android.graphics.*
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.arios.player.Arios
import com.arios.player.R
import com.arios.player.artwork.artKey
import com.arios.player.data.Insights
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shareable "year in ARIOS" image. */
class SummaryRenderer(private val context: Context) {
    suspend fun render(sum: Insights.Summary, title: String, minutesLabel: String, topLabel: String, accent: Int, light: Int, deep: Int): Bitmap =
        withContext(Dispatchers.Default) {
            val w = 1080; val h = 1920
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val font = ResourcesCompat.getFont(context, R.font.plex_arabic_semibold) ?: Typeface.DEFAULT_BOLD
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            c.drawColor(LyricCardRenderer.blend(deep, Color.BLACK, 0.85f))
            fun blob(x: Float, y: Float, r: Float, col: Int, a: Int) {
                p.shader = RadialGradient(x, y, r, intArrayOf(LyricCardRenderer.withAlpha(col, a), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                c.drawCircle(x, y, r, p); p.shader = null
            }
            blob(200f, 300f, 1000f, accent, 110); blob(1000f, 1200f, 900f, deep, 140); blob(300f, 1800f, 800f, light, 50)
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; color = Color.WHITE }
            tp.textSize = 64f
            tp.shader = LinearGradient(0f, 0f, 900f, 0f, light, accent, Shader.TileMode.CLAMP)
            c.drawText(title, (w - tp.measureText(title)) / 2, 260f, tp); tp.shader = null
            tp.textSize = 44f; tp.color = 0xCCFFFFFF.toInt()
            c.drawText(minutesLabel, (w - tp.measureText(minutesLabel)) / 2, 420f, tp)
            tp.textSize = 200f; tp.color = Color.WHITE
            tp.shader = LinearGradient(0f, 450f, 0f, 650f, light, accent, Shader.TileMode.CLAMP)
            val m = "%,d".format(sum.totalMs / 60000)
            c.drawText(m, (w - tp.measureText(m)) / 2, 640f, tp); tp.shader = null
            tp.textSize = 52f; tp.color = Color.WHITE
            c.drawText(topLabel, 120f, 820f, tp)
            var y = 880f
            sum.topSongs.take(5).forEachIndexed { i, (s, _) ->
                val art = runCatching { Arios.artwork.loadBitmapBlocking(s.artKey(), 200) }.getOrNull()
                val r = RectF(120f, y, 260f, y + 140f)
                if (art != null) {
                    val save = c.save(); c.clipPath(Path().apply { addRoundRect(r, 28f, 28f, Path.Direction.CW) }); c.drawBitmap(art, null, r, p); c.restoreToCount(save)
                }
                tp.textSize = 70f; tp.shader = LinearGradient(0f, 0f, 200f, 0f, light, accent, Shader.TileMode.CLAMP)
                c.drawText("${i + 1}", 300f, y + 95f, tp); tp.shader = null
                tp.textSize = 46f; tp.color = Color.WHITE
                c.drawText(android.text.TextUtils.ellipsize(s.title, tp, 580f, android.text.TextUtils.TruncateAt.END).toString(), 390f, y + 68f, tp)
                tp.textSize = 34f; tp.color = 0xAAFFFFFF.toInt()
                c.drawText(android.text.TextUtils.ellipsize(s.artist, tp, 580f, android.text.TextUtils.TruncateAt.END).toString(), 390f, y + 118f, tp)
                y += 175f
            }
            val logo = BitmapFactory.decodeResource(context.resources, R.drawable.arios_emblem)
            logo?.let { c.drawBitmap(it, null, RectF(w / 2f - 110f, h - 290f, w / 2f + 110f, h - 290f + 220f * it.height / it.width), p) }
            bmp
        }
}
