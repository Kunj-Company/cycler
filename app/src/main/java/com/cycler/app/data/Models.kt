package com.cycler.app.data

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** A single point. [ele] is NaN when unknown, [t] is epoch millis or 0 when unknown. */
data class Pt(val lat: Double, val lon: Double, val ele: Double = Double.NaN, val t: Long = 0L)

enum class RouteType { RECORDED, PLANNED }

data class RouteMeta(
    val id: String,
    val name: String,
    val type: RouteType,
    val createdAt: Long,
    val distanceM: Double,
    val ascentM: Double,
    /** Moving time for recorded rides, estimated riding time for planned routes. Seconds. */
    val durationS: Long,
    val startLat: Double,
    val startLon: Double,
    val maxSpeedMs: Double = 0.0,
    /** Total time from start to finish, including stops. Seconds. 0 when unknown. */
    val elapsedS: Long = 0,
    /** A few dozen points for drawing a small outline of the route. */
    val thumb: List<Pt> = emptyList(),
) {
    val difficulty: Difficulty get() = Difficulty.of(distanceM, ascentM)
}

/** A rough effort label: every 40 m of climbing counts like one extra kilometre. */
enum class Difficulty(val label: String) {
    EASY("Easy"), MODERATE("Moderate"), HARD("Hard"), EPIC("Very hard");

    companion object {
        fun of(distanceM: Double, ascentM: Double): Difficulty {
            val effort = distanceM / 1000 + ascentM / 40
            return when {
                effort < 20 -> EASY
                effort < 50 -> MODERATE
                effort < 95 -> HARD
                else -> EPIC
            }
        }
    }
}

data class Route(val meta: RouteMeta, val pts: List<Pt>)

object Geo {
    private const val R = 6371000.0
    private fun rad(d: Double) = d * PI / 180.0
    private fun deg(r: Double) = r * 180.0 / PI

    fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = rad(lat2 - lat1)
        val dLon = rad(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(rad(lat1)) * cos(rad(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * R * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun dist(a: Pt, b: Pt) = dist(a.lat, a.lon, b.lat, b.lon)

    fun length(pts: List<Pt>): Double {
        var d = 0.0
        for (i in 1 until pts.size) d += dist(pts[i - 1], pts[i])
        return d
    }

    /** Point reached by travelling [distM] metres from (lat, lon) on [bearingDeg]. */
    fun destination(lat: Double, lon: Double, bearingDeg: Double, distM: Double): Pt {
        val br = rad(bearingDeg)
        val la1 = rad(lat)
        val lo1 = rad(lon)
        val ad = distM / R
        val la2 = asin(sin(la1) * cos(ad) + cos(la1) * sin(ad) * cos(br))
        val lo2 = lo1 + atan2(sin(br) * sin(ad) * cos(la1), cos(ad) - sin(la1) * sin(la2))
        return Pt(deg(la2), deg(lo2))
    }

    /**
     * Total climb in metres. Uses a hysteresis threshold so small bumps and
     * sensor noise are not counted. Set [smooth] for raw GPS altitude.
     */
    fun ascent(pts: List<Pt>, threshold: Double = 3.0, smooth: Boolean = false): Double {
        var eles = pts.map { it.ele }.filter { !it.isNaN() }
        if (eles.size < 2) return 0.0
        if (smooth) {
            val w = 4
            eles = eles.indices.map { i ->
                val from = maxOf(0, i - w)
                val to = minOf(eles.size - 1, i + w)
                var s = 0.0
                for (k in from..to) s += eles[k]
                s / (to - from + 1)
            }
        }
        var ref = eles[0]
        var gain = 0.0
        for (e in eles) {
            if (e - ref >= threshold) { gain += e - ref; ref = e }
            else if (ref - e >= threshold) ref = e
        }
        return gain
    }

    /** Up to [n] evenly spaced points, enough to sketch the shape of a route. */
    fun simplify(pts: List<Pt>, n: Int = 48): List<Pt> {
        if (pts.size <= n) return pts.map { Pt(it.lat, it.lon) }
        return (0 until n).map { i -> pts[i * (pts.size - 1) / (n - 1)].let { Pt(it.lat, it.lon) } }
    }

    /**
     * Where [p] sits relative to a route: distance from the route in metres and
     * how far along it the nearest spot is. [cum] holds the distance from the
     * start to each route point. Searches near [hint] first for speed.
     */
    fun nearestOnRoute(p: Pt, route: List<Pt>, cum: DoubleArray, hint: Int): Triple<Double, Double, Int> {
        fun scan(from: Int, to: Int): Triple<Double, Double, Int> {
            var best = Double.MAX_VALUE
            var along = 0.0
            var idx = from
            val kx = 111320.0 * cos(rad(p.lat))
            val ky = 110540.0
            for (i in from until to) {
                val a = route[i]
                val b = route[i + 1]
                val ax = (a.lon - p.lon) * kx
                val ay = (a.lat - p.lat) * ky
                val bx = (b.lon - p.lon) * kx
                val by = (b.lat - p.lat) * ky
                val dx = bx - ax
                val dy = by - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
                val cx = ax + t * dx
                val cy = ay + t * dy
                val d = sqrt(cx * cx + cy * cy)
                if (d < best) { best = d; along = cum[i] + t * (cum[i + 1] - cum[i]); idx = i }
            }
            return Triple(best, along, idx)
        }
        if (route.size < 2) return Triple(Double.MAX_VALUE, 0.0, 0)
        val last = route.size - 1
        val near = scan(maxOf(0, hint - 15), minOf(last, hint + 150))
        return if (near.first <= 60.0) near else scan(0, last)
    }

    fun cumulative(pts: List<Pt>): DoubleArray {
        val out = DoubleArray(pts.size)
        for (i in 1 until pts.size) out[i] = out[i - 1] + dist(pts[i - 1], pts[i])
        return out
    }

    /**
     * Waypoints for a round trip of roughly [targetM] metres that leaves [start]
     * towards [bearingDeg]. The points sit on a circle through the start; roads
     * wiggle, so the circle is made smaller than the target distance.
     */
    fun loopWaypoints(start: Pt, bearingDeg: Double, targetM: Double): List<Pt> {
        val wiggle = 1.3
        val radius = targetM / wiggle / (2 * PI)
        val center = destination(start.lat, start.lon, bearingDeg, radius)
        val back = bearingDeg + 180.0
        val mids = listOf(90.0, 180.0, 270.0).map {
            destination(center.lat, center.lon, back + it, radius)
        }
        return listOf(start) + mids + start
    }
}
