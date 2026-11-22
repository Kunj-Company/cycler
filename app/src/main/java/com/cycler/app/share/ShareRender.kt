package com.cycler.app.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import com.cycler.app.R
import com.cycler.app.data.Geo
import com.cycler.app.data.Pt
import com.cycler.app.data.RouteMeta
import com.cycler.app.data.RouteType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/** Colours and type shared by the share card and the flyover video. */
class ShareStyle(ctx: Context?, val dark: Boolean) {
    val bg = if (dark) Color.parseColor("#121212") else Color.parseColor("#FFFFFF")
    val ground = if (dark) Color.parseColor("#1B1D21") else Color.parseColor("#ECEEE8")
    val gridLine = if (dark) Color.parseColor("#2A2D33") else Color.parseColor("#DADDD4")
    val ink = if (dark) Color.parseColor("#EDEDEB") else Color.parseColor("#1C1C1A")
    val muted = if (dark) Color.parseColor("#9B9A97") else Color.parseColor("#6F6E69")
    val accent = if (dark) Color.parseColor("#6C9BF5") else Color.parseColor("#2563EB")
    val track = Color.parseColor("#E5484D")
    val hair = if (dark) Color.parseColor("#2F2F2F") else Color.parseColor("#E5E5E2")

    private val base: Typeface = runCatching { ctx?.let { ResourcesCompat.getFont(it, R.font.inter) } }.getOrNull() ?: Typeface.DEFAULT
    val regular: Typeface = base
    val bold: Typeface =
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, 650, false) else Typeface.create(base, Typeface.BOLD)

    fun text(size: Float, color: Int, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) this@ShareStyle.bold else regular
        if (Build.VERSION.SDK_INT >= 26) fontFeatureSettings = "tnum"
    }
}

object ShareFmt {
    fun km(m: Double) = String.format(Locale.US, "%.1f", m / 1000)
    fun dur(s: Long): String {
        val h = s / 3600
        val m = (s % 3600) / 60
        return if (h > 0) String.format(Locale.US, "%d:%02d", h, m) else "$m"
    }
    fun durUnit(s: Long) = if (s >= 3600) "h" else "min"
    /** Always hours:minutes, for a clock that counts up through the video. */
    fun hm(s: Long): String = String.format(Locale.US, "%d:%02d", s / 3600, (s % 3600) / 60)
    fun date(t: Long): String = SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()).format(Date(t))
}

/** Load a photo (or a frame of a video) scaled down for drawing, turned the right way up. */
fun loadShareImage(file: File, maxSide: Int = 1280): Bitmap? = runCatching {
    if (file.name.endsWith(".mp4")) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            val frame = r.getFrameAtTime(500_000) ?: return@runCatching null
            val k = maxSide.toFloat() / max(frame.width, frame.height)
            if (k < 1f) Bitmap.createScaledBitmap(frame, (frame.width * k).toInt(), (frame.height * k).toInt(), true) else frame
        } finally {
            r.release()
        }
    } else {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        var sample = 1
        while (max(o.outWidth, o.outHeight) / sample > maxSide * 2) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@runCatching null
        val turn = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (turn == 0f) bmp
        else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(turn) }, true)
    }
}.getOrNull()

/** Draw [bmp] so it fills [dst], cropping the overflow, optionally zoomed in a little. */
fun drawCover(c: Canvas, bmp: Bitmap, dst: RectF, zoom: Float = 1f, alpha: Int = 255) {
    val s = max(dst.width() / bmp.width, dst.height() / bmp.height) * zoom
    val m = Matrix()
    m.setScale(s, s)
    m.postTranslate(dst.centerX() - bmp.width * s / 2, dst.centerY() - bmp.height * s / 2)
    c.save()
    c.clipRect(dst)
    c.drawBitmap(bmp, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { this.alpha = alpha })
    c.restore()
}

/** A map image laid flat as the ground of the flyover. */
class Ground(val bitmap: Bitmap, val centerLat: Double, val centerLon: Double, val metresPerPx: Double)

/**
 * The animated 3D flyover. A camera starts high above the whole route, drops in
 * behind the rider, follows the route — slowly at first, fast in the middle,
 * slowly again at the end — then pulls back out to show the whole ride.
 *
 * Everything is drawn with plain Canvas calls so the same code makes the
 * in-app preview and the exported video.
 */
class FlyoverScene(
    ctx: Context?,
    private val meta: RouteMeta,
    private val raw: List<Pt>,
    private val ground: Ground?,
    dark: Boolean,
) {
    private val st = ShareStyle(ctx, dark)

    // The route in local metres: x east, y north, z up (exaggerated), resampled evenly.
    private val n: Int
    private val xs: FloatArray
    private val ys: FloatArray
    private val zs: FloatArray
    private val eles: FloatArray
    private val gains: FloatArray
    private val lat0: Double
    private val lon0: Double
    private val kx: Double
    private val ky = 110540.0
    private val extent: Float
    private val totalM: Double

    val flyMs: Long
    /** The final wide view is held for a moment so the totals can be read. */
    val endMs = 1500L
    val introMs = 900L
    val durationMs: Long

    init {
        val pts = if (raw.size >= 2) raw else listOf(Pt(0.0, 0.0), Pt(0.0001, 0.0001))
        lat0 = (pts.minOf { it.lat } + pts.maxOf { it.lat }) / 2
        lon0 = (pts.minOf { it.lon } + pts.maxOf { it.lon }) / 2
        kx = 111320.0 * cos(Math.toRadians(lat0))
        val cum = Geo.cumulative(pts)
        totalM = cum.last().coerceAtLeast(1.0)
        n = (totalM / 12.0).toInt().coerceIn(120, 1400)
        xs = FloatArray(n); ys = FloatArray(n); zs = FloatArray(n); eles = FloatArray(n); gains = FloatArray(n)
        val known = pts.filter { !it.ele.isNaN() }
        val minEle = known.minOfOrNull { it.ele } ?: 0.0
        val relief = (known.maxOfOrNull { it.ele } ?: 0.0) - minEle
        var j = 0
        for (i in 0 until n) {
            val d = totalM * i / (n - 1)
            while (j < pts.size - 2 && cum[j + 1] < d) j++
            val span = (cum[j + 1] - cum[j]).coerceAtLeast(1e-6)
            val t = ((d - cum[j]) / span).coerceIn(0.0, 1.0)
            val a = pts[j]
            val b = pts[j + 1]
            xs[i] = ((a.lon + (b.lon - a.lon) * t - lon0) * kx).toFloat()
            ys[i] = ((a.lat + (b.lat - a.lat) * t - lat0) * ky).toFloat()
            val ea = if (a.ele.isNaN()) minEle else a.ele
            val eb = if (b.ele.isNaN()) minEle else b.ele
            eles[i] = (ea + (eb - ea) * t - minEle).toFloat()
        }
        // Smooth the elevation so the ribbon doesn't shiver, then note the climbing so far.
        val sm = FloatArray(n) { i ->
            var s = 0f; var c = 0
            for (q in max(0, i - 4)..min(n - 1, i + 4)) { s += eles[q]; c++ }
            s / c
        }
        var ext = 1f
        for (i in 0 until n) ext = max(ext, max(kotlin.math.abs(xs[i]), kotlin.math.abs(ys[i])))
        extent = ext
        // Hills are stretched so they read on screen, but never absurdly.
        val exag = if (relief < 1.0) 0f else (ext * 0.16f / relief.toFloat()).coerceIn(1f, 9f)
        var gain = 0f
        var ref = sm[0]
        for (i in 0 until n) {
            zs[i] = sm[i] * exag
            if (sm[i] - ref >= 2f) { gain += sm[i] - ref; ref = sm[i] } else if (ref - sm[i] >= 2f) ref = sm[i]
            gains[i] = gain
        }
        flyMs = (6500 + totalM / 1000 * 110).toLong().coerceIn(7000, 14000)
        durationMs = introMs + flyMs + endMs
    }

    private fun ease(u: Float): Float {
        val x = u.coerceIn(0f, 1f)
        return x * x * x * (x * (x * 6 - 15) + 10)
    }

    private fun smooth(u: Float): Float {
        val x = u.coerceIn(0f, 1f)
        return x * x * (3 - 2 * x)
    }

    private fun at(arr: FloatArray, p: Float): Float {
        val f = p.coerceIn(0f, 1f) * (n - 1)
        val i = f.toInt().coerceAtMost(n - 2)
        return arr[i] + (arr[i + 1] - arr[i]) * (f - i)
    }

    // Camera for the current frame.
    private var ex = 0f; private var ey = 0f; private var ez = 0f
    private var rx = 0f; private var ry = 0f; private var rz = 0f
    private var ux = 0f; private var uy = 0f; private var uz = 0f
    private var fx = 0f; private var fy = 0f; private var fz = 0f
    private var focal = 1f
    private var cxp = 0f; private var cyp = 0f

    private fun lookAt(eyeX: Float, eyeY: Float, eyeZ: Float, tx: Float, ty: Float, tz: Float) {
        ex = eyeX; ey = eyeY; ez = eyeZ
        var dx = tx - eyeX; var dy = ty - eyeY; var dz = tz - eyeZ
        val dl = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-3f)
        dx /= dl; dy /= dl; dz /= dl
        fx = dx; fy = dy; fz = dz
        // right = forward × up(0,0,1)
        var ax = fy; var ay = -fx
        val al = sqrt(ax * ax + ay * ay).coerceAtLeast(1e-3f)
        ax /= al; ay /= al
        rx = ax; ry = ay; rz = 0f
        // up = right × forward
        ux = ry * fz - rz * fy
        uy = rz * fx - rx * fz
        uz = rx * fy - ry * fx
    }

    /** Projects a point; returns its depth (≤ 0 means behind the camera) and writes x,y into [out]. */
    private fun project(x: Float, y: Float, z: Float, out: FloatArray, o: Int): Float {
        val vx = x - ex; val vy = y - ey; val vz = z - ez
        val depth = vx * fx + vy * fy + vz * fz
        if (depth <= 1f) return depth
        out[o] = cxp + (vx * rx + vy * ry + vz * rz) / depth * focal
        out[o + 1] = cyp - (vx * ux + vy * uy + vz * uz) / depth * focal
        return depth
    }

    private val tmp = FloatArray(4)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    fun draw(c: Canvas, w: Int, h: Int, tMs: Long) {
        val t = tMs.coerceIn(0, durationMs)
        drawFly(c, w, h, ((t - introMs).toFloat() / flyMs).coerceIn(0f, 1f), (t.toFloat() / introMs).coerceIn(0f, 1f))
    }

    private fun drawFly(c: Canvas, w: Int, h: Int, u: Float, intro: Float) {
        val p = ease(u)
        val px = at(xs, p); val py = at(ys, p); val pz = at(zs, p)

        // Heading: where the route is going over the next stretch, so the camera doesn't twitch at every bend.
        val a0 = (p - 0.035f).coerceIn(0f, 1f)
        val a1 = (p + 0.06f).coerceIn(0f, 1f)
        var hx = at(xs, a1) - at(xs, a0)
        var hy = at(ys, a1) - at(ys, a0)
        val hl = sqrt(hx * hx + hy * hy).coerceAtLeast(1e-3f)
        hx /= hl; hy /= hl

        // Two cameras: one high over the whole route, one chasing the rider. Blend in, then out.
        val blend = smooth(u / 0.2f) * (1f - smooth((u - 0.84f) / 0.16f))
        val ovEyeX = 0f; val ovEyeY = -extent * 2.7f; val ovEyeZ = extent * 3.5f
        val chase = (extent * 0.42f).coerceIn(260f, 2600f)
        val chEyeX = px - hx * chase; val chEyeY = py - hy * chase; val chEyeZ = pz + chase * 0.62f
        val chTx = px + hx * chase * 0.25f; val chTy = py + hy * chase * 0.25f
        fun mix(a: Float, b: Float) = a + (b - a) * blend
        lookAt(
            mix(ovEyeX, chEyeX), mix(ovEyeY, chEyeY), mix(ovEyeZ, chEyeZ),
            mix(0f, chTx), mix(extent * 0.05f, chTy), mix(0f, pz),
        )
        focal = (h / 2f) / tan(Math.toRadians(27.0)).toFloat()
        cxp = w / 2f
        cyp = h * 0.46f

        c.drawColor(st.ground)
        drawGround(c, w, h)

        // Distance haze towards the horizon.
        fill.shader = LinearGradient(0f, 0f, 0f, h * 0.42f, st.bg, st.bg and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h * 0.42f, fill)
        fill.shader = null

        val unit = w / 1080f
        val split = (p * (n - 1)).toInt().coerceIn(0, n - 1)

        // The road still to come, faint.
        line.color = if (st.dark) Color.argb(120, 255, 255, 255) else Color.argb(110, 28, 28, 26)
        line.strokeWidth = 5f * unit
        strokeRoute(c, split, n - 1, lift = true)

        // A translucent curtain under the ridden part shows the hills.
        fill.color = (st.track and 0x00FFFFFF) or 0x33000000
        val q = FloatArray(8)
        val stepC = max(1, split / 260)
        var i = 0
        while (i < split) {
            val k = min(split, i + stepC)
            val d1 = project(xs[i], ys[i], 0f, q, 0)
            val d2 = project(xs[i], ys[i], zs[i], q, 2)
            val d3 = project(xs[k], ys[k], zs[k], q, 4)
            val d4 = project(xs[k], ys[k], 0f, q, 6)
            if (d1 > 1f && d2 > 1f && d3 > 1f && d4 > 1f) {
                path.rewind()
                path.moveTo(q[0], q[1]); path.lineTo(q[2], q[3]); path.lineTo(q[4], q[5]); path.lineTo(q[6], q[7]); path.close()
                c.drawPath(path, fill)
            }
            i = k
        }

        // The ridden part, bold.
        line.color = st.track
        line.strokeWidth = 11f * unit
        strokeRoute(c, 0, split, lift = true, endX = px, endY = py, endZ = pz)

        // Start marker and the rider.
        if (project(xs[0], ys[0], zs[0], tmp, 0) > 1f) {
            fill.color = Color.WHITE; c.drawCircle(tmp[0], tmp[1], 13f * unit, fill)
            fill.color = st.ink; c.drawCircle(tmp[0], tmp[1], 8f * unit, fill)
        }
        val depth = project(px, py, pz, tmp, 0)
        if (depth > 1f) {
            val r = (focal * (extent * 0.012f).coerceIn(9f, 60f) / depth).coerceIn(12f * unit, 34f * unit)
            fill.color = (st.track and 0x00FFFFFF) or 0x44000000
            c.drawCircle(tmp[0], tmp[1], r * 2.1f, fill)
            fill.color = Color.WHITE; c.drawCircle(tmp[0], tmp[1], r * 1.25f, fill)
            fill.color = st.track; c.drawCircle(tmp[0], tmp[1], r * 0.85f, fill)
        }

        drawOverlay(c, w, h, p, intro)
    }

    private fun strokeRoute(
        c: Canvas, from: Int, to: Int, lift: Boolean,
        endX: Float = Float.NaN, endY: Float = 0f, endZ: Float = 0f,
    ) {
        if (to <= from && endX.isNaN()) return
        path.rewind()
        var pen = false
        val step = max(1, (to - from) / 500)
        var i = from
        while (true) {
            val d = project(xs[i], ys[i], if (lift) zs[i] else 0f, tmp, 0)
            if (d > 1f) {
                if (pen) path.lineTo(tmp[0], tmp[1]) else { path.moveTo(tmp[0], tmp[1]); pen = true }
            } else pen = false
            if (i >= to) break
            i = min(to, i + step)
        }
        if (!endX.isNaN() && project(endX, endY, endZ, tmp, 0) > 1f) {
            if (pen) path.lineTo(tmp[0], tmp[1]) else path.moveTo(tmp[0], tmp[1])
        }
        c.drawPath(path, line)
    }

    private val meshN = 44
    private val verts = FloatArray((meshN + 1) * (meshN + 1) * 2)
    private val depths = FloatArray((meshN + 1) * (meshN + 1))
    private val texPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private fun drawGround(c: Canvas, w: Int, h: Int) {
        val g = ground
        val half: Float
        val gx: Float
        val gy: Float
        if (g != null) {
            half = (g.bitmap.width / 2f * g.metresPerPx).toFloat()
            gx = ((g.centerLon - lon0) * kx).toFloat()
            gy = ((g.centerLat - lat0) * ky).toFloat()
        } else {
            half = extent * 3.2f; gx = 0f; gy = 0f
        }
        val cols = meshN + 1
        for (r in 0..meshN) for (q in 0..meshN) {
            val k = r * cols + q
            val x = gx - half + 2 * half * q / meshN
            val y = gy + half - 2 * half * r / meshN
            depths[k] = project(x, y, 0f, verts, k * 2)
        }
        if (g != null && fz < -0.02f) {
            // A flat map seen in perspective is one exact transform, so no mesh is needed:
            // take four ground points in front of the camera and map the image through them.
            val reach = -ez / fz
            val tx = ex + fx * reach
            val ty = ey + fy * reach
            val s = reach * 0.3f
            var hx = fx; var hy = fy
            val hl = sqrt(hx * hx + hy * hy).coerceAtLeast(1e-3f)
            hx /= hl; hy /= hl
            val src = FloatArray(8)
            val dst = FloatArray(8)
            var ok = true
            val corners = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f)
            for (k in 0 until 4) {
                val x = tx + (rx * corners[k * 2] + hx * corners[k * 2 + 1]) * s
                val y = ty + (ry * corners[k * 2] + hy * corners[k * 2 + 1]) * s
                if (project(x, y, 0f, dst, k * 2) <= 1f) ok = false
                src[k * 2] = (g.bitmap.width / 2f + (x - gx) / g.metresPerPx).toFloat()
                src[k * 2 + 1] = (g.bitmap.height / 2f - (y - gy) / g.metresPerPx).toFloat()
            }
            val m = Matrix()
            if (ok && m.setPolyToPoly(src, 0, dst, 0, 4)) {
                // Nothing on the ground can appear above the horizon; clip there so no stray pixels do.
                val horizon = cyp - focal * (hx * ux + hy * uy) / (hx * fx + hy * fy).coerceAtLeast(1e-3f)
                c.save()
                c.clipRect(0f, max(0f, horizon + 2f), w.toFloat(), h.toFloat())
                c.drawBitmap(g.bitmap, m, texPaint)
                c.restore()
            }
        } else {
            // No map: a plain grid still gives the eye a ground plane.
            line.color = st.gridLine
            line.strokeWidth = 2f * w / 1080f
            for (r in 0..meshN step 2) gridLine(c) { q -> r * cols + q }
            for (q in 0..meshN step 2) gridLine(c) { r -> r * cols + q }
        }
    }

    private inline fun gridLine(c: Canvas, index: (Int) -> Int) {
        path.rewind()
        var pen = false
        for (s in 0..meshN) {
            val k = index(s)
            if (depths[k] > 1f) {
                if (pen) path.lineTo(verts[k * 2], verts[k * 2 + 1]) else { path.moveTo(verts[k * 2], verts[k * 2 + 1]); pen = true }
            } else pen = false
        }
        c.drawPath(path, line)
    }

    private fun drawOverlay(c: Canvas, w: Int, h: Int, p: Float, intro: Float) {
        val u = w / 1080f
        val pad = 64f * u
        // Title, sliding in at the start.
        val a = (smooth(intro) * 255).toInt()
        val title = st.text(64f * u, st.ink, true).apply { alpha = a }
        var name = meta.name
        while (title.measureText(name) > w - pad * 2 && name.length > 4) name = name.dropLast(2)
        if (name != meta.name) name = name.trimEnd() + "…"
        val ty = 190f * u - (1 - smooth(intro)) * 30f * u
        c.drawText(name, pad, ty, title)
        c.drawText(ShareFmt.date(meta.createdAt), pad, ty + 52f * u, st.text(34f * u, st.muted).apply { alpha = a })

        // A panel of live numbers at the bottom.
        val panelTop = h - 430f * u
        fill.shader = LinearGradient(0f, panelTop - 160f * u, 0f, panelTop + 40f * u, st.bg and 0x00FFFFFF, st.bg, Shader.TileMode.CLAMP)
        c.drawRect(0f, panelTop - 160f * u, w.toFloat(), h.toFloat(), fill)
        fill.shader = null
        fill.color = st.bg
        c.drawRect(0f, panelTop + 40f * u, w.toFloat(), h.toFloat(), fill)

        val colW = (w - pad * 2) / 3
        val nums = listOf(
            Triple("DISTANCE", ShareFmt.km(meta.distanceM * p), "km"),
            Triple("CLIMB", (if (gains[n - 1] > 0) meta.ascentM * at(gains, p) / gains[n - 1] else 0.0).toInt().toString(), "m"),
            Triple("TIME", ShareFmt.hm((meta.durationS * p).toLong()), "h"),
        )
        nums.forEachIndexed { i, (label, value, unit) ->
            val x = pad + i * colW
            c.drawText(label, x, panelTop + 20f * u, st.text(26f * u, st.muted, true).apply { letterSpacing = 0.1f })
            val vp = st.text(76f * u, st.ink, true)
            c.drawText(value, x, panelTop + 104f * u, vp)
            c.drawText(unit, x + vp.measureText(value) + 10f * u, panelTop + 104f * u, st.text(32f * u, st.muted))
        }

        // Elevation profile with a marker for where the rider is.
        val top = panelTop + 160f * u
        val bottom = h - 130f * u
        val maxE = eles.max().coerceAtLeast(10f)
        path.rewind()
        path.moveTo(pad, bottom)
        val cols = 160
        for (k in 0..cols) {
            val f = k.toFloat() / cols
            path.lineTo(pad + (w - pad * 2) * f, bottom - at(eles, f) / maxE * (bottom - top))
        }
        path.lineTo(w - pad, bottom)
        path.close()
        fill.color = (st.accent and 0x00FFFFFF) or 0x26000000
        c.drawPath(path, fill)
        c.save()
        c.clipRect(pad, top - 4f, pad + (w - pad * 2) * p, bottom)
        fill.color = (st.track and 0x00FFFFFF) or 0x66000000
        c.drawPath(path, fill)
        c.restore()
        val mx = pad + (w - pad * 2) * p
        fill.color = st.track
        c.drawRect(mx - 2f * u, top - 6f * u, mx + 2f * u, bottom, fill)
        fill.color = st.hair
        c.drawRect(pad, bottom, w - pad, bottom + 2f * u, fill)

        c.drawText("Cycler", pad, h - 60f * u, st.text(28f * u, st.muted, true))
        if (ground != null) {
            val osm = st.text(22f * u, st.muted)
            val label = "Map © OpenStreetMap contributors"
            c.drawText(label, w - pad - osm.measureText(label), h - 60f * u, osm)
        }
    }
}
