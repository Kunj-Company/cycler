package com.cycler.app.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.cycler.app.data.Geo
import com.cycler.app.data.Pt
import com.cycler.app.data.RouteMeta
import com.cycler.app.data.RouteType
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** What the rider chose to put on the card. */
data class CardSpec(
    val title: String,
    val showDate: Boolean = true,
    val showRoute: Boolean = true,
    val showMark: Boolean = true,
    /** Keys from [CardStats.all], in display order; up to four. */
    val stats: List<String> = listOf("distance", "time", "climb", "avg"),
    /** Which of the ride's photos to use. */
    val photo: Int = 0,
    val w: Int = 1080,
    val h: Int = 1920,
)

enum class CardStyle(val label: String, val usesPhoto: Boolean = false, val usesMap: Boolean = false) {
    CLEAN("Clean"),
    PHOTO("Photo", usesPhoto = true),
    PHOTO_ROUTE("Photo and route", usesPhoto = true),
    MAP("Map", usesMap = true),
    BIG("Big number"),
    SPLIT("Split", usesPhoto = true),
    BOLD("Bold blue"),
    MAP_DARK("Night map", usesMap = true),
    COLLAGE("Collage", usesPhoto = true),
    ELEVATION("Elevation"),
    POLAROID("Polaroid", usesPhoto = true),
    CLEAN_DARK("Clean dark"),
    STICKER("Sticker (see-through)"),
}

object CardStats {
    val all = listOf(
        "distance" to "Distance", "time" to "Moving time", "climb" to "Climb", "avg" to "Avg speed",
        "max" to "Max speed", "elapsed" to "Elapsed", "stops" to "Stops",
    )

    /** Label, value and unit for one stat; null when the ride doesn't have it. */
    fun of(key: String, m: RouteMeta): Triple<String, String, String>? = when (key) {
        "distance" -> Triple("DISTANCE", ShareFmt.km(m.distanceM), "km")
        "time" -> Triple(if (m.type == RouteType.RECORDED) "MOVING TIME" else "EST. TIME", ShareFmt.dur(m.durationS), ShareFmt.durUnit(m.durationS))
        "climb" -> Triple("CLIMB", m.ascentM.toInt().toString(), "m")
        "avg" -> if (m.durationS > 0) Triple("AVG SPEED", String.format(Locale.US, "%.1f", m.distanceM / m.durationS * 3.6), "km/h") else null
        "max" -> if (m.maxSpeedMs > 0) Triple("MAX SPEED", String.format(Locale.US, "%.1f", m.maxSpeedMs * 3.6), "km/h") else null
        "elapsed" -> if (m.elapsedS > 0) Triple("ELAPSED", ShareFmt.dur(m.elapsedS), ShareFmt.durUnit(m.elapsedS)) else null
        "stops" -> if (m.type == RouteType.RECORDED) Triple("STOPS", m.stops.toString(), "") else null
        else -> null
    }
}

/** Draws share cards in a dozen styles from the same ride and the same choices. */
object Cards {
    private val INK = Color.parseColor("#1C1C1A")
    private val PAPER = Color.WHITE
    private val NIGHT = Color.parseColor("#121212")
    private val GREY = Color.parseColor("#6F6E69")
    private val GREY_DARK = Color.parseColor("#A3A29E")
    private val RED = Color.parseColor("#E5484D")
    private val BLUE = Color.parseColor("#2563EB")

    fun render(
        ctx: Context?, style: CardStyle, spec: CardSpec, meta: RouteMeta, pts: List<Pt>,
        photos: List<Bitmap>, map: Ground?, mapDark: Ground?, scale: Float = 1f,
    ): Bitmap {
        val w = (spec.w * scale).toInt()
        val h = (spec.h * scale).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val d = Draw(Canvas(bmp), ShareStyle(ctx, false), spec, meta, pts, photos, w, h)
        when (style) {
            CardStyle.CLEAN -> d.clean(PAPER, INK, GREY, RED)
            CardStyle.CLEAN_DARK -> d.clean(NIGHT, Color.parseColor("#EDEDEB"), GREY_DARK, RED)
            CardStyle.BOLD -> d.clean(BLUE, Color.WHITE, Color.parseColor("#C9DAFB"), Color.WHITE)
            CardStyle.PHOTO -> d.photo(bigRoute = false)
            CardStyle.PHOTO_ROUTE -> d.photo(bigRoute = true)
            CardStyle.SPLIT -> d.split(collage = false)
            CardStyle.COLLAGE -> d.split(collage = true)
            CardStyle.BIG -> d.big()
            CardStyle.MAP -> d.map(map, dark = false)
            CardStyle.MAP_DARK -> d.map(mapDark, dark = true)
            CardStyle.POLAROID -> d.polaroid()
            CardStyle.ELEVATION -> d.elevation()
            CardStyle.STICKER -> d.sticker()
        }
        return bmp
    }

    private class Draw(
        val c: Canvas, val st: ShareStyle, val spec: CardSpec, val meta: RouteMeta, val pts: List<Pt>,
        val photos: List<Bitmap>, val w: Int, val h: Int,
    ) {
        val u = w / 1080f
        val pad = 72f * u
        val stats = spec.stats.mapNotNull { CardStats.of(it, meta) }.take(4)
        val photo: Bitmap? get() = if (photos.isEmpty()) null else photos[((spec.photo % photos.size) + photos.size) % photos.size]

        fun text(size: Float, color: Int, bold: Boolean = false, shadow: Boolean = false) = st.text(size * u, color, bold).apply {
            if (shadow) setShadowLayer(10f * u, 0f, 2f * u, Color.argb(150, 0, 0, 0))
        }

        fun fit(s: String, p: Paint, width: Float): String {
            var out = s
            while (p.measureText(out) > width && out.length > 4) out = out.dropLast(2)
            return if (out != s) out.trimEnd() + "…" else out
        }

        /**
         * Title, date, numbers and the Cycler mark, stacked upwards from [bottom].
         * Returns the y of the top of the block so the caller knows what room is left.
         */
        fun info(
            left: Float, bottom: Float, width: Float, ink: Int, muted: Int,
            cols: Int = 2, shadow: Boolean = false, title: Boolean = true,
        ): Float {
            var y = bottom
            if (spec.showMark) {
                c.drawText("Cycler", left, y, text(30f, muted, true, shadow))
                y -= 84f * u
            }
            if (stats.isNotEmpty()) {
                val rows = ceil(stats.size / cols.toFloat()).toInt()
                val rowH = if (cols >= 3) 150f * u else 168f * u
                val top = y - rows * rowH + 60f * u
                val colW = width / cols
                val big = if (cols >= 4) 54f else if (cols == 3) 66f else 84f
                stats.forEachIndexed { i, (label, value, unit) ->
                    val x = left + (i % cols) * colW
                    val sy = top + (i / cols) * rowH
                    c.drawText(label, x, sy, text(if (cols >= 3) 22f else 27f, muted, true, shadow).apply { letterSpacing = 0.1f })
                    val vp = text(big, ink, true, shadow)
                    c.drawText(value, x, sy + big * 1.05f * u, vp)
                    if (unit.isNotEmpty()) {
                        c.drawText(unit, x + vp.measureText(value) + 10f * u, sy + big * 1.05f * u, text(big * 0.42f, muted, false, shadow))
                    }
                }
                y = top - 86f * u
            }
            if (title) {
                if (spec.showDate) {
                    c.drawText(ShareFmt.date(meta.createdAt), left, y, text(36f, muted, false, shadow))
                    y -= 62f * u
                }
                val tp = text(70f, ink, true, shadow)
                c.drawText(fit(spec.title, tp, width), left, y, tp)
                y -= 86f * u
            }
            return y
        }

        fun outline(box: RectF, color: Int, width: Float, ring: Int, shadow: Boolean = false) {
            if (!spec.showRoute || pts.size < 2 || box.height() < 60f || box.width() < 60f) return
            val minLat = pts.minOf { it.lat }; val maxLat = pts.maxOf { it.lat }
            val minLon = pts.minOf { it.lon }; val maxLon = pts.maxOf { it.lon }
            val kx = cos(Math.toRadians((minLat + maxLat) / 2))
            val rw = ((maxLon - minLon) * kx).coerceAtLeast(1e-6)
            val rh = (maxLat - minLat).coerceAtLeast(1e-6)
            val s = min(box.width() / rw, box.height() / rh)
            val ox = box.left + (box.width() - rw * s).toFloat() / 2
            val oy = box.top + (box.height() - rh * s).toFloat() / 2
            fun x(p: Pt) = (ox + (p.lon - minLon) * kx * s).toFloat()
            fun y(p: Pt) = (oy + (maxLat - p.lat) * s).toFloat()
            val step = max(1, pts.size / 700)
            val path = Path()
            pts.forEachIndexed { i, p ->
                if (i % step != 0 && i != pts.size - 1) return@forEachIndexed
                if (i == 0) path.moveTo(x(p), y(p)) else path.lineTo(x(p), y(p))
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = width; this.color = color
                strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                if (shadow) setShadowLayer(12f * u, 0f, 3f * u, Color.argb(140, 0, 0, 0))
            }
            c.drawPath(path, paint)
            val dot = Paint(Paint.ANTI_ALIAS_FLAG)
            for ((p, fill) in listOf(pts.first() to ring, pts.last() to color)) {
                dot.color = if (fill == ring) color else ring
                c.drawCircle(x(p), y(p), width * 1.7f, dot)
                dot.color = fill
                c.drawCircle(x(p), y(p), width * 1.05f, dot)
            }
        }

        /** The chosen photo filling [r], or a quiet placeholder asking for one. */
        fun picture(r: RectF) {
            val p = photo
            if (p != null) { drawCover(c, p, r); return }
            val paint = Paint()
            paint.shader = LinearGradient(r.left, r.top, r.right, r.bottom, Color.parseColor("#3A4252"), Color.parseColor("#1B1D22"), Shader.TileMode.CLAMP)
            c.drawRect(r, paint)
            val t = text(34f, Color.argb(200, 255, 255, 255))
            val msg = "Add a photo to this ride for this style"
            c.drawText(msg, r.centerX() - t.measureText(msg) / 2, r.centerY(), t)
        }

        fun shade(top: Float, strength: Int) {
            val p = Paint()
            p.shader = LinearGradient(0f, top, 0f, h.toFloat(), Color.TRANSPARENT, Color.argb(strength, 0, 0, 0), Shader.TileMode.CLAMP)
            c.drawRect(0f, top, w.toFloat(), h.toFloat(), p)
        }

        // --- The styles ---

        fun clean(bg: Int, ink: Int, muted: Int, line: Int) {
            c.drawColor(bg)
            val top = info(pad, h - 80f * u, w - pad * 2, ink, muted)
            outline(RectF(pad, 130f * u, w - pad, top - 70f * u), line, 10f * u, bg)
        }

        fun photo(bigRoute: Boolean) {
            picture(RectF(0f, 0f, w.toFloat(), h.toFloat()))
            if (bigRoute) c.drawColor(Color.argb(70, 0, 0, 0))
            shade(h * 0.40f, 225)
            val top = info(pad, h - 80f * u, w - pad * 2, Color.WHITE, Color.argb(215, 255, 255, 255), shadow = true)
            if (bigRoute) {
                outline(RectF(pad * 1.6f, h * 0.10f, w - pad * 1.6f, top - 90f * u), Color.WHITE, 12f * u, Color.argb(255, 20, 20, 20), shadow = true)
            } else {
                val s = 250f * u
                outline(RectF(w - pad - s, 110f * u, w - pad, 110f * u + s), Color.WHITE, 8f * u, Color.argb(255, 20, 20, 20), shadow = true)
            }
        }

        fun split(collage: Boolean) {
            val dark = collage
            val bg = if (dark) NIGHT else PAPER
            c.drawColor(bg)
            val ph = h * 0.50f
            val shots = if (collage) photos.take(4) else emptyList()
            if (shots.size >= 2) {
                val gap = 6f * u
                val cells = when (shots.size) {
                    2 -> listOf(RectF(0f, 0f, w / 2f - gap / 2, ph), RectF(w / 2f + gap / 2, 0f, w.toFloat(), ph))
                    3 -> listOf(
                        RectF(0f, 0f, w * 0.62f - gap / 2, ph),
                        RectF(w * 0.62f + gap / 2, 0f, w.toFloat(), ph / 2 - gap / 2),
                        RectF(w * 0.62f + gap / 2, ph / 2 + gap / 2, w.toFloat(), ph),
                    )
                    else -> listOf(
                        RectF(0f, 0f, w / 2f - gap / 2, ph / 2 - gap / 2), RectF(w / 2f + gap / 2, 0f, w.toFloat(), ph / 2 - gap / 2),
                        RectF(0f, ph / 2 + gap / 2, w / 2f - gap / 2, ph), RectF(w / 2f + gap / 2, ph / 2 + gap / 2, w.toFloat(), ph),
                    )
                }
                shots.forEachIndexed { i, b -> drawCover(c, b, cells[i]) }
            } else picture(RectF(0f, 0f, w.toFloat(), ph))
            info(pad, h - 80f * u, w - pad * 2, if (dark) Color.parseColor("#EDEDEB") else INK, if (dark) GREY_DARK else GREY)
            // The route sits in a round badge straddling the photo's edge.
            if (spec.showRoute) {
                val r = 150f * u
                val cx = w - pad - r
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
                c.drawCircle(cx, ph, r, fill)
                outline(RectF(cx - r * 0.62f, ph - r * 0.62f, cx + r * 0.62f, ph + r * 0.62f), RED, 7f * u, bg)
            }
        }

        fun big() {
            c.drawColor(NIGHT)
            val white = Color.parseColor("#F3F3F1")
            val first = stats.firstOrNull()
            var y = 190f * u
            val tp = text(54f, white, true)
            c.drawText(fit(spec.title, tp, w - pad * 2), pad, y, tp)
            if (spec.showDate) { y += 58f * u; c.drawText(ShareFmt.date(meta.createdAt), pad, y, text(34f, GREY_DARK)) }
            if (first != null) {
                val vp = text(330f, white, true)
                val by = y + 400f * u
                c.drawText(first.second, pad - 10f * u, by, vp)
                c.drawText("${first.third}  ${first.first.lowercase(Locale.US)}".trim(), pad, by + 80f * u, text(46f, GREY_DARK))
                y = by + 80f * u
            }
            val rest = stats.drop(1)
            var bottom = h - 80f * u
            if (spec.showMark) { c.drawText("Cycler", pad, bottom, text(30f, GREY_DARK, true)); bottom -= 84f * u }
            if (rest.isNotEmpty()) {
                val colW = (w - pad * 2) / rest.size
                val top = bottom - 110f * u
                rest.forEachIndexed { i, (label, value, unit) ->
                    val x = pad + i * colW
                    c.drawText(label, x, top, text(24f, GREY_DARK, true).apply { letterSpacing = 0.1f })
                    val vp = text(66f, white, true)
                    c.drawText(value, x, top + 76f * u, vp)
                    c.drawText(unit, x + vp.measureText(value) + 8f * u, top + 76f * u, text(28f, GREY_DARK))
                }
                bottom = top - 60f * u
            }
            outline(RectF(pad, y + 70f * u, w - pad, bottom - 30f * u), RED, 10f * u, NIGHT)
        }

        fun map(g: Ground?, dark: Boolean) {
            val bg = if (dark) NIGHT else PAPER
            val ink = if (dark) Color.parseColor("#EDEDEB") else INK
            val muted = if (dark) GREY_DARK else GREY
            c.drawColor(if (dark) Color.parseColor("#1B1D21") else Color.parseColor("#ECEEE8"))
            // The panel of numbers is drawn last; measure it first so the map can be centred in what's left.
            val panelBottom = h - 40f * u
            val panelTop = h - (if (spec.showMark) 84f else 0f) * u - ceil(max(stats.size, 1) / 2f) * 168f * u -
                (if (spec.showDate) 62f else 0f) * u - 86f * u - 150f * u
            val cy = panelTop / 2f
            if (g != null && pts.size >= 2) {
                val lat0 = g.centerLat
                val kx = 111320.0 * cos(Math.toRadians(lat0))
                val spanM = max(
                    (pts.maxOf { it.lat } - pts.minOf { it.lat }) * 110540.0,
                    (pts.maxOf { it.lon } - pts.minOf { it.lon }) * kx,
                ).coerceAtLeast(100.0)
                // Scale the map so the route spans most of the card's width, but never past what the image covers.
                var s = (w * 0.74f / (spanM / g.metresPerPx)).toFloat()
                s = max(s, max(w.toFloat(), panelTop) / g.bitmap.width * 1.02f)
                val m = Matrix()
                m.setScale(s, s)
                m.postTranslate(w / 2f - g.bitmap.width / 2f * s, cy - g.bitmap.height / 2f * s)
                c.drawBitmap(g.bitmap, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                if (spec.showRoute) {
                    fun x(p: Pt) = (w / 2f + (p.lon - g.centerLon) * kx / g.metresPerPx * s).toFloat()
                    fun y(p: Pt) = (cy - (p.lat - lat0) * 110540.0 / g.metresPerPx * s).toFloat()
                    val path = Path()
                    val step = max(1, pts.size / 900)
                    pts.forEachIndexed { i, p ->
                        if (i % step != 0 && i != pts.size - 1) return@forEachIndexed
                        if (i == 0) path.moveTo(x(p), y(p)) else path.lineTo(x(p), y(p))
                    }
                    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                    }
                    line.color = Color.WHITE; line.strokeWidth = 18f * u; c.drawPath(path, line)
                    line.color = RED; line.strokeWidth = 10f * u; c.drawPath(path, line)
                    val dot = Paint(Paint.ANTI_ALIAS_FLAG)
                    for ((p, col) in listOf(pts.first() to INK, pts.last() to RED)) {
                        dot.color = Color.WHITE; c.drawCircle(x(p), y(p), 17f * u, dot)
                        dot.color = col; c.drawCircle(x(p), y(p), 11f * u, dot)
                    }
                }
                val credit = text(22f, if (dark) Color.argb(200, 255, 255, 255) else Color.argb(200, 40, 40, 40))
                val label = "© OpenStreetMap contributors"
                c.drawText(label, w - pad * 0.6f - credit.measureText(label), panelTop - 18f * u, credit)
            } else {
                outline(RectF(pad, 130f * u, w - pad, panelTop - 70f * u), RED, 10f * u, bg)
            }
            val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
            c.drawRoundRect(RectF(40f * u, panelTop, w - 40f * u, panelBottom), 44f * u, 44f * u, panel)
            info(pad + 10f * u, panelBottom - 56f * u, w - pad * 2 - 20f * u, ink, muted)
        }

        fun polaroid() {
            c.drawColor(Color.parseColor("#EFEBE2"))
            val top = info(pad, h - 80f * u, w - pad * 2, INK, GREY, cols = if (stats.size > 2) stats.size else 2, title = false)
            val frame = RectF(pad * 1.2f, 110f * u, w - pad * 1.2f, top - 40f * u)
            c.save()
            c.rotate(-2.2f, frame.centerX(), frame.centerY())
            val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                setShadowLayer(34f * u, 0f, 14f * u, Color.argb(70, 0, 0, 0))
            }
            c.drawRect(frame, paper)
            val inset = 38f * u
            val foot = 190f * u
            picture(RectF(frame.left + inset, frame.top + inset, frame.right - inset, frame.bottom - foot))
            val tp = text(52f, INK, true)
            c.drawText(fit(spec.title, tp, frame.width() - inset * 2), frame.left + inset, frame.bottom - foot + 84f * u, tp)
            if (spec.showDate) c.drawText(ShareFmt.date(meta.createdAt), frame.left + inset, frame.bottom - foot + 138f * u, text(32f, GREY))
            val s = 130f * u
            outline(RectF(frame.right - inset - s, frame.bottom - foot + 30f * u, frame.right - inset, frame.bottom - foot + 30f * u + s), RED, 5f * u, Color.WHITE)
            c.restore()
        }

        fun elevation() {
            c.drawColor(PAPER)
            val top = info(pad, h - 80f * u, w - pad * 2, INK, GREY)
            // The profile runs edge to edge as the main picture.
            val cum = Geo.cumulative(pts)
            val prof = Geo.profile(pts, cum, max(20.0, (cum.lastOrNull() ?: 0.0) / 240))
            val box = RectF(0f, h * 0.20f, w.toFloat(), top - 90f * u)
            if (prof != null && prof.size >= 2 && box.height() > 80f) {
                val lo = prof.min(); val hi = prof.max()
                val span = (hi - lo).coerceAtLeast(15.0)
                val path = Path()
                path.moveTo(0f, box.bottom)
                prof.forEachIndexed { i, e ->
                    path.lineTo(w * i / (prof.size - 1f), (box.bottom - (e - lo) / span * box.height() * 0.92).toFloat())
                }
                path.lineTo(w.toFloat(), box.bottom)
                path.close()
                val fill = Paint(Paint.ANTI_ALIAS_FLAG)
                fill.shader = LinearGradient(0f, box.top, 0f, box.bottom, Color.parseColor("#2563EB"), Color.parseColor("#9DBBF7"), Shader.TileMode.CLAMP)
                c.drawPath(path, fill)
                c.drawText("${hi.toInt()} m", pad, box.top - 20f * u, text(34f, GREY, true))
                val lowLabel = "low ${lo.toInt()} m"
                val lp = text(30f, Color.WHITE, true)
                c.drawText(lowLabel, w - pad - lp.measureText(lowLabel), box.bottom - 24f * u, lp)
            } else {
                outline(RectF(pad, 130f * u, w - pad, top - 70f * u), RED, 10f * u, PAPER)
                return
            }
            val s = 210f * u
            outline(RectF(w - pad - s, 90f * u, w - pad, 90f * u + s), RED, 7f * u, PAPER)
        }

        fun sticker() {
            // Nothing behind it: drop it over your own photo or story.
            val white = Color.WHITE
            val top = info(pad, h - 80f * u, w - pad * 2, white, Color.argb(235, 255, 255, 255), shadow = true)
            outline(RectF(pad * 1.4f, h * 0.12f, w - pad * 1.4f, top - 90f * u), white, 12f * u, Color.argb(255, 20, 20, 20), shadow = true)
        }
    }
}
