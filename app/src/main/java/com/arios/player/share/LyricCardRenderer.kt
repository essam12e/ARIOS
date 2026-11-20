package com.arios.player.share

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import androidx.core.content.res.ResourcesCompat
import com.arios.player.R
import com.arios.player.lyrics.LyricLine
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class CardStyle { GLASS, ART, AURORA, MINIMAL }
enum class CardAspect(val w: Int, val h: Int) { STORY(1080, 1920), SQUARE(1080, 1080) }

data class CardSpec(
    val style: CardStyle,
    val aspect: CardAspect,
    val lines: List<LyricLine>,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val accent: Int,
    val accentLight: Int,
    val tint: Int,
)

/** Draws lyric cards with plain Android Canvas so the exact same art is used for images and video frames. */
class LyricCardRenderer(context: Context) {
    private val font: Typeface = ResourcesCompat.getFont(context, R.font.plex_arabic_semibold) ?: Typeface.DEFAULT_BOLD
    private val logo: Bitmap? = BitmapFactory.decodeResource(context.resources, R.drawable.arios_emblem)
    private var blurCache: Pair<Bitmap, Bitmap>? = null

    fun render(spec: CardSpec, scale: Float = 1f): Bitmap {
        val w = (spec.aspect.w * scale).toInt(); val h = (spec.aspect.h * scale).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), spec, w, h, null, 0f)
        return bmp
    }

    private fun blurred(src: Bitmap): Bitmap {
        blurCache?.let { if (it.first === src) return it.second }
        // cheap but smooth blur: downscale twice then upscale with filtering
        val tiny = Bitmap.createScaledBitmap(src, 18, 18, true)
        val mid = Bitmap.createScaledBitmap(tiny, 72, 72, true)
        val out = Bitmap.createScaledBitmap(mid, 360, 360, true)
        blurCache = src to out
        return out
    }

    /**
     * [timeMs] null → static card (all lines lit). Otherwise karaoke state at that time.
     * [t] 0..1 progress of the clip (for subtle motion in video).
     */
    fun draw(c: Canvas, spec: CardSpec, w: Int, h: Int, timeMs: Long?, t: Float) {
        val s = w / 1080f
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val bgDark = blend(spec.tint, Color.BLACK, 0.78f)
        // ---------- background
        c.drawColor(bgDark)
        when (spec.style) {
            CardStyle.GLASS, CardStyle.ART -> spec.art?.let { art ->
                val b = blurred(art)
                val zoom = 1.25f + 0.08f * t
                val side = maxOf(w, h) * zoom
                val dx = (w - side) / 2 + 20 * s * sin(t * 6.28f)
                val dy = (h - side) / 2 + 30 * s * cos(t * 6.28f)
                p.alpha = if (spec.style == CardStyle.ART) 255 else 220
                c.drawBitmap(b, null, RectF(dx, dy, dx + side, dy + side), p)
                p.alpha = 255
            }
            CardStyle.AURORA -> {
                fun blob(cx: Float, cy: Float, r: Float, col: Int, a: Int) {
                    p.shader = RadialGradient(cx, cy, r, intArrayOf(withAlpha(col, a), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                    c.drawCircle(cx, cy, r, p); p.shader = null
                }
                blob(w * (0.2f + 0.1f * sin(t * 6.28f)), h * 0.15f, w * 1.0f, spec.accent, 120)
                blob(w * 0.95f, h * (0.55f + 0.05f * cos(t * 6.28f)), w * 0.9f, spec.tint, 110)
                blob(w * 0.3f, h * 0.95f, w * 0.9f, spec.accentLight, 60)
            }
            CardStyle.MINIMAL -> {
                p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), blend(spec.tint, Color.BLACK, 0.55f), Color.BLACK, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
            }
        }
        // legibility gradient
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0x55000000, 0x22000000, 0xAA000000.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null

        // ---------- card
        val margin = 70 * s
        val cardTop = if (spec.aspect == CardAspect.STORY) h * 0.2f else 90 * s
        val cardBottom = if (spec.aspect == CardAspect.STORY) h * 0.8f else h - 150 * s
        val card = RectF(margin, cardTop, w - margin, cardBottom)
        val radius = 56 * s
        if (spec.style != CardStyle.ART) {
            // outer glow
            p.color = withAlpha(spec.accent, 70)
            p.maskFilter = BlurMaskFilter(60 * s, BlurMaskFilter.Blur.NORMAL)
            c.drawRoundRect(card, radius, radius, p)
            p.maskFilter = null
            // glass fill
            p.shader = LinearGradient(0f, card.top, 0f, card.bottom, intArrayOf(withAlpha(blend(spec.tint, Color.BLACK, 0.4f), 210), withAlpha(blend(spec.tint, Color.BLACK, 0.75f), 225)), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(card, radius, radius, p); p.shader = null
            // rim
            p.style = Paint.Style.STROKE; p.strokeWidth = 3 * s
            p.shader = LinearGradient(card.left, card.top, card.right, card.bottom, intArrayOf(0x66FFFFFF, withAlpha(spec.accent, 120), 0x10FFFFFF), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(card, radius, radius, p); p.shader = null; p.style = Paint.Style.FILL
        }

        // header: cover + title + artist
        val pad = 56 * s
        val coverSize = 150 * s
        val headerTop = card.top + pad
        spec.art?.let { art ->
            val r = RectF(card.left + pad, headerTop, card.left + pad + coverSize, headerTop + coverSize)
            val save = c.save()
            val path = Path().apply { addRoundRect(r, 26 * s, 26 * s, Path.Direction.CW) }
            c.clipPath(path)
            c.drawBitmap(art, null, r, p)
            c.restoreToCount(save)
        }
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; color = Color.WHITE; textSize = 46 * s }
        val textLeft = card.left + pad + (if (spec.art != null) coverSize + 34 * s else 0f)
        val textW = (card.right - pad - textLeft).toInt()
        drawLayout(c, ellipsize(spec.title, tp, textW), tp, textLeft, headerTop + 18 * s, textW, Layout.Alignment.ALIGN_NORMAL)
        tp.textSize = 36 * s; tp.color = withAlpha(Color.WHITE, 170)
        drawLayout(c, ellipsize(spec.artist, tp, textW), tp, textLeft, headerTop + 84 * s, textW, Layout.Alignment.ALIGN_NORMAL)

        // lyrics block, vertically centred in the remaining card space
        val lyricTop = headerTop + coverSize + 60 * s
        val lyricBottom = card.bottom - 150 * s
        val lw = (card.width() - pad * 2).toInt()
        var size = 74 * s
        var layouts: List<StaticLayout>
        val active = timeMs?.let { tm -> spec.lines.indexOfLast { it.start <= tm } } ?: -1
        while (true) {
            tp.textSize = size
            layouts = spec.lines.mapIndexed { i, line -> buildLine(line, tp, lw, timeMs, i, active, spec) }
            val total = layouts.sumOf { it.height } + (layouts.size - 1) * 34 * s
            if (total <= lyricBottom - lyricTop || size < 38 * s) break
            size *= 0.92f
        }
        val total = layouts.sumOf { it.height } + (layouts.size - 1) * 34 * s
        var y = lyricTop + ((lyricBottom - lyricTop) - total) / 2
        layouts.forEachIndexed { i, l ->
            val save = c.save()
            c.translate(card.left + pad, y)
            // soft glow behind the active line
            if (timeMs == null || i == active) {
                tp.setShadowLayer(26 * s, 0f, 0f, withAlpha(spec.accent, 150))
            }
            l.draw(c)
            tp.clearShadowLayer()
            c.restoreToCount(save)
            y += l.height + 34 * s
        }

        // footer: brand
        val footY = card.bottom - 92 * s
        val lsz = 64 * s
        logo?.let { c.drawBitmap(it, null, RectF(card.left + pad, footY - 6 * s, card.left + pad + lsz * it.width / it.height, footY - 6 * s + lsz), p) }
        tp.textSize = 34 * s; tp.color = withAlpha(Color.WHITE, 200)
        tp.shader = LinearGradient(0f, 0f, 400 * s, 0f, spec.accentLight, spec.accent, Shader.TileMode.CLAMP)
        val brand = "Flowfy · فلوفاي"
        c.drawText(brand, card.right - pad - tp.measureText(brand), footY + 44 * s, tp)
        tp.shader = null

        // progress line (video only)
        if (timeMs != null) {
            p.color = withAlpha(Color.WHITE, 40)
            val bar = RectF(card.left + pad, card.bottom - 30 * s, card.right - pad, card.bottom - 24 * s)
            c.drawRoundRect(bar, 4 * s, 4 * s, p)
            p.shader = LinearGradient(bar.left, 0f, bar.right, 0f, spec.accentLight, spec.accent, Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * t, bar.bottom), 4 * s, 4 * s, p)
            p.shader = null
        }
    }

    private fun buildLine(line: LyricLine, tp: TextPaint, width: Int, timeMs: Long?, i: Int, active: Int, spec: CardSpec): StaticLayout {
        val text = SpannableStringBuilder()
        val dim = withAlpha(Color.WHITE, 95)
        if (timeMs == null) {
            text.append(line.text)
            text.setSpan(ForegroundColorSpan(Color.WHITE), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else if (i != active) {
            text.append(line.text)
            text.setSpan(ForegroundColorSpan(if (i < active) withAlpha(Color.WHITE, 150) else dim), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            val words = line.words.ifEmpty { listOf(com.arios.player.lyrics.LyricWord(line.start, line.end, line.text)) }
            words.forEach { wd ->
                val st = text.length
                text.append(wd.text)
                val k = when { timeMs >= wd.end -> 1f; timeMs <= wd.start -> 0f; else -> (timeMs - wd.start).toFloat() / (wd.end - wd.start).coerceAtLeast(1) }
                val col = if (k >= 1f) Color.WHITE else blend(dim, spec.accentLight, k)
                text.setSpan(ForegroundColorSpan(col), st, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (!line.translation.isNullOrBlank()) {
            val st = text.length
            text.append("\n").append(line.translation)
            text.setSpan(ForegroundColorSpan(withAlpha(Color.WHITE, 150)), st, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(android.text.style.RelativeSizeSpan(0.6f), st, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, tp, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setLineSpacing(0f, 1.12f)
            .build()
    }

    private fun drawLayout(c: Canvas, text: CharSequence, tp: TextPaint, x: Float, y: Float, w: Int, align: Layout.Alignment) {
        val l = StaticLayout.Builder.obtain(text, 0, text.length, tp, w).setAlignment(align)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR).setMaxLines(1).build()
        val save = c.save(); c.translate(x, y); l.draw(c); c.restoreToCount(save)
    }

    private fun ellipsize(s: String, tp: TextPaint, w: Int): CharSequence =
        android.text.TextUtils.ellipsize(s, tp, w.toFloat(), android.text.TextUtils.TruncateAt.END)

    companion object {
        fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)
        fun blend(a: Int, b: Int, k: Float): Int {
            val t = k.coerceIn(0f, 1f)
            fun ch(sh: Int) = (((a shr sh) and 0xff) * (1 - t) + ((b shr sh) and 0xff) * t).toInt()
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        @Suppress("unused") private fun m(a: Float, b: Float) = min(a, b)
    }
}
