package com.cycler.app.data

/**
 * GPS reports height above the WGS84 ellipsoid, a smooth mathematical Earth. Maps and people
 * mean height above sea level. The gap between the two (the geoid height) is about −90 m
 * around India and +50 m in parts of Europe, so raw GPS heights can read below sea level.
 *
 * Each ride says which kind of height its points carry: [GPS] for raw heights recorded by the
 * phone, [MSL] for heights above sea level (converted rides, GPX imports, planned routes).
 */
object Altitude {
    /** Raw GPS height above the ellipsoid, still to be converted. */
    const val GPS = "gps"
    /** Height above mean sea level. */
    const val MSL = "msl"

    /**
     * The height reference of a ride saved before rides recorded one. Only the app's own
     * recorder stores an elapsed time, so a recorded ride with one holds raw GPS heights;
     * a timed GPX import (saved without it) already holds heights above sea level.
     */
    fun legacy(type: RouteType, elapsedS: Long): String =
        if (type == RouteType.RECORDED && elapsedS > 0) GPS else MSL

    /**
     * Converts raw GPS heights to heights above sea level. [geoidAt] gives the geoid height
     * (ellipsoid minus sea level) at a point, or null when it can't be worked out. It changes
     * by well under a metre across a few kilometres, so it is looked up once per 0.1° cell.
     * Points without a height are kept as they are. Returns null if any lookup fails, so a
     * ride is never left half converted.
     */
    fun toMsl(pts: List<Pt>, geoidAt: (lat: Double, lon: Double) -> Double?): List<Pt>? {
        val cache = HashMap<Long, Double>()
        return pts.map { p ->
            if (p.ele.isNaN()) p
            else {
                val key = (Math.round(p.lat * 10) + 1000) * 10_000 + (Math.round(p.lon * 10) + 2000)
                val n = cache[key] ?: (geoidAt(p.lat, p.lon) ?: return null).also { cache[key] = it }
                p.copy(ele = p.ele - n)
            }
        }
    }
}
