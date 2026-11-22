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
)

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
