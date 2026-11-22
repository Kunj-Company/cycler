package com.cycler.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.cycler.app.data.Pt
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pictures for the widgets, drawn on a Canvas. They stay small (a few hundred KB at most) because a
 * RemoteViews bitmap is limited in size. The background is transparent and the colours are mid-tones
 * that read on both the light and the dark widget surface, since the system gives no callback to
 * redraw them when dark mode flips.
 */
object WidgetRender {
    private const val ACCENT = 0xFF4A7DF0.toInt()
    private const val ACCENT_SOFT = 0x884A7DF0.toInt()
    private const val EMPTY_DAY = 0x449A9A95
    private const val LABEL = 0xFF85857F.toInt()

    /** The route's outline, like the thumbnail in the app. Null when there is no outline to draw. */
    fun routeBitmap(pts: List<Pt>, size: Int = 240): Bitmap? {
        val ok = pts.filter { !it.lat.isNaN() && !it.lon.isNaN() }
        if (ok.size < 2) return null
        val minLat = ok.minOf { it.lat }; val maxLat = ok.maxOf { it.lat }
        val minLon = ok.minOf { it.lon }; val maxLon = ok.maxOf { it.lon }
        val kx = cos(Math.toRadians((minLat + maxLat) / 2))
        val w = ((maxLon - minLon) * kx).coerceAtLeast(1e-6)
        val h = (maxLat - minLat).coerceAtLeast(1e-6)
        val pad = size * 0.09f
        val scale = min((size - 2 * pad) / w, (size - 2 * pad) / h)
        val ox = (size - w * scale) / 2
        val oy = (size - h * scale) / 2
        fun x(p: Pt) = (ox + (p.lon - minLon) * kx * scale).toFloat()
        fun y(p: Pt) = (oy + (maxLat - p.lat) * scale).toFloat()

        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT; style = Paint.Style.STROKE; strokeWidth = size * 0.03f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        ok.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p), y(p)) else path.lineTo(x(p), y(p)) }
        canvas.drawPath(path, line)
        val r = size * 0.04f
        canvas.drawCircle(x(ok.first()), y(ok.first()), r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT })
        canvas.drawCircle(x(ok.last()), y(ok.last()), r, line.apply { strokeWidth = size * 0.02f })
        return bmp
    }

    /** One bar per day (distance in metres, oldest first) with a one-letter weekday under each. Today is the last bar. */
    fun weekBars(valuesM: List<Double>, labels: List<String>, width: Int = 400, height: Int = 240): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val n = max(valuesM.size, 1)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LABEL; textAlign = Paint.Align.CENTER; textSize = height * 0.115f
        }
        val labelH = textPaint.textSize * 1.5f
        val valueH = textPaint.textSize * 1.4f
        val slot = width.toFloat() / n
        val barW = slot * 0.56f
        val base = height - labelH
        val top = valueH
        val maxKm = max(5.0, valuesM.maxOrNull()?.div(1000) ?: 0.0)
        val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        val small = Paint(textPaint).apply { textSize = height * 0.095f }
        valuesM.forEachIndexed { i, m ->
            val cx = slot * i + slot / 2
            val km = m / 1000
            val hgt = if (km <= 0.0) height * 0.018f else max(height * 0.018f, ((base - top) * (km / maxKm)).toFloat())
            bar.color = when {
                km <= 0.0 -> EMPTY_DAY
                i == n - 1 -> ACCENT
                else -> ACCENT_SOFT
            }
            val rect = RectF(cx - barW / 2, base - hgt, cx + barW / 2, base)
            val rad = barW * 0.22f
            canvas.drawRoundRect(rect, rad, rad, bar)
            if (km >= 0.5) canvas.drawText(
                if (km >= 10) km.roundToInt().toString() else String.format(java.util.Locale.US, "%.1f", km),
                cx, rect.top - small.textSize * 0.4f, small,
            )
            labels.getOrNull(i)?.let { canvas.drawText(it, cx, height - labelH * 0.25f, textPaint) }
        }
        return bmp
    }
}
