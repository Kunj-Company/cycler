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
    /** How many times the rider came to a stop during a recorded ride. */
    val stops: Int = 0,
    /** Fastest time in seconds over set distances within this ride, keyed by kilometres. */
    val bests: Map<Int, Long> = emptyMap(),
    /** Storage format version, used to fill in fields added by later app versions. */
    val v: Int = 0,
    /** What the rider wrote about the ride. */
    val notes: String = "",
    /** How the ride felt, 1 (rough) to 5 (great); 0 when not set. */
    val feeling: Int = 0,
    val tags: List<String> = emptyList(),
) {
    val difficulty: Difficulty get() = Difficulty.of(distanceM, ascentM)
}

/** The five answers to "How did it feel?", worst to best. */
object Feeling {
    val all = listOf("😫" to "Rough", "😕" to "Tough", "🙂" to "Okay", "😄" to "Good", "🤩" to "Great")
    fun emoji(level: Int) = all.getOrNull(level - 1)?.first
    fun label(level: Int) = all.getOrNull(level - 1)?.second
    val suggestedTags = listOf("Commute", "Workout", "Long ride", "Leisure", "Hills", "Group ride", "Solo", "Recovery")
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

/** A bend sharp enough to call out while following a route. Negative angles turn left. */
class Turn(val alongM: Double, val angle: Double) {
    val left: Boolean get() = angle < 0
    val label: String
        get() = when {
            kotlin.math.abs(angle) >= 150 -> "U-turn"
            kotlin.math.abs(angle) >= 100 -> if (left) "Sharp left" else "Sharp right"
            else -> if (left) "Turn left" else "Turn right"
        }
}

/** A sustained uphill stretch of a route. */
class Climb(val startM: Double, val endM: Double, val gainM: Double) {
    val lengthM: Double get() = endM - startM
    val gradePct: Double get() = if (lengthM > 0) gainM / lengthM * 100 else 0.0
}

/** Everything that can be worked out about a recorded ride from its points. */
class RideStats(
    val avgMovingKmh: Double, val avgOverallKmh: Double, val maxKmh: Double,
    val fastestKmKmh: Double, val slowestKmKmh: Double,
    val startT: Long, val endT: Long, val elapsedS: Long, val movingS: Long,
    val stops: Int, val stoppedS: Long, val longestStopS: Long,
    val gainM: Double, val lossM: Double, val highestM: Double, val lowestM: Double,
    val steepestUpPct: Double, val steepestDownPct: Double, val longestClimb: Climb?,
    /** Share of moving time in each speed band of [ZONES], adding up to 1. */
    val zones: List<Double>,
    val hasTimes: Boolean, val hasElevation: Boolean,
) {
    /** A rough energy estimate from speed and rider weight; real effort depends on wind, hills and bike. */
    fun kcal(weightKg: Int): Int {
        val met = when {
            avgMovingKmh < 16 -> 4.0
            avgMovingKmh < 19 -> 6.8
            avgMovingKmh < 22 -> 8.0
            avgMovingKmh < 25 -> 10.0
            avgMovingKmh < 30 -> 12.0
            else -> 15.8
        }
        return (met * weightKg * movingS / 3600.0).toInt()
    }

    companion object {
        val ZONES = listOf("Under 10", "10–15", "15–20", "20–25", "25–30", "Over 30")
    }
}

object Geo {
    /** Elevation every [step] metres along a route, lightly smoothed. Null without elevation data. */
    fun profile(route: List<Pt>, cum: DoubleArray, step: Double = 50.0): DoubleArray? {
        val total = cum.lastOrNull() ?: return null
        if (route.size < 2 || total < step * 2 || route.any { it.ele.isNaN() }) return null
        val m = (total / step).toInt() + 1
        val raw = DoubleArray(m)
        var j = 0
        for (k in 0 until m) {
            val d = k * step
            while (j < route.size - 2 && cum[j + 1] < d) j++
            val span = (cum[j + 1] - cum[j]).coerceAtLeast(1e-6)
            val t = ((d - cum[j]) / span).coerceIn(0.0, 1.0)
            raw[k] = route[j].ele + t * (route[j + 1].ele - route[j].ele)
        }
        return DoubleArray(m) { k ->
            var s = 0.0
            var c = 0
            for (q in maxOf(0, k - 2)..minOf(m - 1, k + 2)) { s += raw[q]; c++ }
            s / c
        }
    }

    /** The detailed numbers for a ride's "Advanced" section. Works on rides recorded by any version. */
    fun rideStats(pts: List<Pt>, meta: RouteMeta): RideStats {
        val cum = cumulative(pts)
        val timed = pts.size >= 2 && pts.first().t > 0 && pts.last().t > pts.first().t
        var movingMs = 0L
        var stops = 0
        var stoppedMs = 0L
        var longest = 0L
        val zoneMs = LongArray(RideStats.ZONES.size)
        // Fastest sustained speed: best over any stretch of about ten seconds, so one bad GPS fix can't set it.
        var maxKmh = 0.0
        var w = 0
        if (timed) for (i in 1 until pts.size) {
            val dt = pts[i].t - pts[i - 1].t
            val d = cum[i] - cum[i - 1]
            if (dt <= 0) continue
            val v = d / (dt / 1000.0)
            if (dt >= 10_000 && v < 1.0) {
                stops++; stoppedMs += dt; if (dt > longest) longest = dt
            } else if (dt < 60_000) {
                // Anything that isn't a stop counts as riding; very long gaps (a manual pause) are left out.
                movingMs += dt
                val kmh = v * 3.6
                val z = when { kmh < 10 -> 0; kmh < 15 -> 1; kmh < 20 -> 2; kmh < 25 -> 3; kmh < 30 -> 4; else -> 5 }
                zoneMs[z] += dt
            }
            while (pts[i].t - pts[w + 1].t >= 10_000 && w + 1 < i) w++
            val span = pts[i].t - pts[w].t
            if (span in 8_000..20_000) maxKmh = maxOf(maxKmh, (cum[i] - cum[w]) / (span / 1000.0) * 3.6)
        }
        val elapsed = if (timed) (pts.last().t - pts.first().t) / 1000 else meta.elapsedS
        val moving = if (meta.durationS > 0) meta.durationS else movingMs / 1000
        val km = splits(pts, 1000.0)
        val prof = profile(pts, cum)
        var up = 0.0
        var down = 0.0
        if (prof != null) for (k in 0 until prof.size - 2) {
            val g = (prof[k + 2] - prof[k]) / 100.0 * 100.0
            if (g > up) up = g
            if (-g > down) down = -g
        }
        val eles = pts.map { it.ele }.filter { !it.isNaN() }
        val zoneTotal = zoneMs.sum().coerceAtLeast(1)
        return RideStats(
            avgMovingKmh = if (moving > 0) meta.distanceM / moving * 3.6 else 0.0,
            avgOverallKmh = if (elapsed > 0) meta.distanceM / elapsed * 3.6 else 0.0,
            maxKmh = if (meta.maxSpeedMs > 0) meta.maxSpeedMs * 3.6 else maxKmh,
            fastestKmKmh = km.maxOrNull() ?: 0.0, slowestKmKmh = km.minOrNull() ?: 0.0,
            startT = if (timed) pts.first().t else meta.createdAt, endT = if (timed) pts.last().t else 0L,
            elapsedS = elapsed, movingS = moving,
            stops = maxOf(stops, meta.stops), stoppedS = if (timed) maxOf(stoppedMs / 1000, elapsed - moving) else 0L,
            longestStopS = longest / 1000,
            gainM = meta.ascentM,
            lossM = ascent(pts.map { it.copy(ele = -it.ele) }, 4.0, smooth = meta.type == RouteType.RECORDED),
            highestM = eles.maxOrNull() ?: 0.0, lowestM = eles.minOrNull() ?: 0.0,
            steepestUpPct = up, steepestDownPct = down,
            longestClimb = climbs(pts, cum).maxByOrNull { it.lengthM },
            zones = zoneMs.map { it.toDouble() / zoneTotal },
            hasTimes = timed, hasElevation = eles.size >= 2,
        )
    }

    /** Distances, in kilometres, for which best efforts are kept. */
    val EFFORTS = listOf(1, 5, 10, 20, 40)

    /**
     * Turns along a route, found from its shape: the direction 25 m before a point
     * against the direction 25 m after it. Bends of 50 degrees or more count.
     */
    fun turns(route: List<Pt>, cum: DoubleArray): List<Turn> {
        val out = ArrayList<Turn>()
        val n = route.size
        if (n < 3) return out
        var back = 0
        var fwd = 2
        for (i in 1 until n - 1) {
            while (back < i - 1 && cum[i] - cum[back + 1] >= 25.0) back++
            if (fwd < i + 1) fwd = i + 1
            while (fwd < n - 1 && cum[fwd] - cum[i] < 25.0) fwd++
            if (cum[i] - cum[back] < 8.0 || cum[fwd] - cum[i] < 8.0) continue
            val d = ((bearing(route[i], route[fwd]) - bearing(route[back], route[i]) + 540.0) % 360.0) - 180.0
            if (kotlin.math.abs(d) < 50.0) continue
            val last = out.lastOrNull()
            if (last != null && cum[i] - last.alongM < 40.0) {
                // The same corner seen from the next point: keep the sharper reading.
                if (kotlin.math.abs(d) > kotlin.math.abs(last.angle)) out[out.size - 1] = Turn(cum[i], d)
            } else out.add(Turn(cum[i], d))
        }
        return out
    }

    /** Climbs of at least 20 m gain over 300 m or more, averaging 2% or steeper. */
    fun climbs(route: List<Pt>, cum: DoubleArray): List<Climb> {
        val total = cum.lastOrNull() ?: return emptyList()
        if (total < 300.0) return emptyList()
        val step = 50.0
        val e = profile(route, cum, step) ?: return emptyList()
        val m = e.size
        val out = ArrayList<Climb>()
        fun record(lo: Int, hi: Int) {
            val gain = e[hi] - e[lo]
            val len = (hi - lo) * step
            if (gain >= 20.0 && len >= 300.0 && gain / len >= 0.02) out.add(Climb(lo * step, hi * step, gain))
        }
        var lo = 0
        var hi = 0
        for (k in 1 until m) {
            if (hi == lo && e[k] <= e[lo]) { lo = k; hi = k }
            else if (e[k] > e[hi]) hi = k
            else if (e[hi] - e[k] > 10.0) { record(lo, hi); lo = k; hi = k }
        }
        record(lo, hi)
        return out
    }

    /** Fastest time, in seconds, to cover [targetM] anywhere within a timed ride. Null if the ride is shorter. */
    fun bestEffort(pts: List<Pt>, cum: DoubleArray, targetM: Double): Long? {
        if (pts.size < 2 || pts.first().t <= 0 || cum.last() < targetM) return null
        var best = Long.MAX_VALUE
        var i = 0
        for (j in 1 until pts.size) {
            if (cum[j] - cum[i] < targetM) continue
            while (cum[j] - cum[i + 1] >= targetM) i++
            val dt = pts[j].t - pts[i].t
            if (dt in 1 until best) best = dt
        }
        return if (best == Long.MAX_VALUE) null else best / 1000
    }

    fun bestEfforts(pts: List<Pt>): Map<Int, Long> {
        if (pts.size < 2 || pts.first().t <= 0) return emptyMap()
        val cum = cumulative(pts)
        val out = LinkedHashMap<Int, Long>()
        for (km in EFFORTS) bestEffort(pts, cum, km * 1000.0)?.let { if (it > 0) out[km] = it }
        return out
    }

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

    /** Compass bearing from [a] to [b], degrees. */
    fun bearing(a: Pt, b: Pt): Double {
        val la1 = rad(a.lat)
        val la2 = rad(b.lat)
        val dLon = rad(b.lon - a.lon)
        val y = sin(dLon) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
        return (deg(atan2(y, x)) + 360) % 360
    }

    /**
     * Average speed in km/h for each [stepM] of a recorded ride, from the
     * points' timestamps. Empty when the ride has no usable times.
     */
    fun splits(pts: List<Pt>, stepM: Double): List<Double> {
        if (pts.size < 2 || pts.first().t <= 0) return emptyList()
        val out = ArrayList<Double>()
        var segDist = 0.0
        var segStart = pts.first().t
        for (i in 1 until pts.size) {
            segDist += dist(pts[i - 1], pts[i])
            if (segDist >= stepM) {
                val dt = (pts[i].t - segStart) / 1000.0
                if (dt > 0) out.add(segDist / dt * 3.6)
                segDist = 0.0
                segStart = pts[i].t
            }
        }
        return out
    }

    /** Up to [n] evenly spaced points, enough to sketch the shape of a route. */
    fun simplify(pts: List<Pt>, n: Int = 48): List<Pt> {
        if (pts.size <= n) return pts.map { Pt(it.lat, it.lon) }
        return (0 until n).map { i -> pts[i * (pts.size - 1) / (n - 1)].let { Pt(it.lat, it.lon) } }
    }

    /**
     * Where [p] sits relative to a route: distance from the route in metres, how
     * far along it the nearest spot is, and that segment's index. [cum] holds the
     * distance from the start to each route point.
     *
     * [done] is how far the rider has already come. Spots a little behind to two
     * kilometres ahead of that are preferred, so a loop that ends where it starts,
     * or a route that goes out and back on one road, doesn't make progress jump.
     * If nothing there is close, the nearest spot anywhere on the route is used.
     */
    fun nearestOnRoute(p: Pt, route: List<Pt>, cum: DoubleArray, done: Double): Triple<Double, Double, Int> {
        if (route.size < 2) return Triple(Double.MAX_VALUE, 0.0, 0)
        val kx = 111320.0 * cos(rad(p.lat))
        val ky = 110540.0
        var best = Double.MAX_VALUE; var bestAlong = 0.0; var bestIdx = 0
        var win = Double.MAX_VALUE; var winAlong = 0.0; var winIdx = 0
        for (i in 0 until route.size - 1) {
            val a = route[i]
            val b = route[i + 1]
            val ax = (a.lon - p.lon) * kx
            val ay = (a.lat - p.lat) * ky
            val dx = (b.lon - p.lon) * kx - ax
            val dy = (b.lat - p.lat) * ky - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val cx = ax + t * dx
            val cy = ay + t * dy
            val d = sqrt(cx * cx + cy * cy)
            val along = cum[i] + t * (cum[i + 1] - cum[i])
            if (d < best) { best = d; bestAlong = along; bestIdx = i }
            if (along >= done - 300.0 && along <= done + 2000.0 && d < win) { win = d; winAlong = along; winIdx = i }
        }
        return if (win <= 60.0) Triple(win, winAlong, winIdx) else Triple(best, bestAlong, bestIdx)
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
