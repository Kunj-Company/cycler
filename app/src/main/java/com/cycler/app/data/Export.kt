package com.cycler.app.data

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Heart rate for each point of a ride: the nearest sample within 15 seconds, or 0. */
private fun heartRateAt(pts: List<Pt>, hr: List<Pair<Long, Int>>): IntArray {
    val out = IntArray(pts.size)
    if (hr.isEmpty()) return out
    var j = 0
    for (i in pts.indices) {
        val t = pts[i].t
        while (j + 1 < hr.size && kotlin.math.abs(hr[j + 1].first - t) <= kotlin.math.abs(hr[j].first - t)) j++
        if (kotlin.math.abs(hr[j].first - t) <= 15_000) out[i] = hr[j].second
    }
    return out
}

/** Garmin's Training Center XML: widely accepted, human-readable. For recorded rides. */
object Tcx {
    /**
     * An empty route gives a valid document with one lap and no trackpoints. A route whose points
     * carry no timestamps (a planned route or an old import) gets made-up ones, counted from the
     * route's creation time and spread over its duration, because TCX requires a time on every
     * trackpoint; a 1970 date would confuse the services that read it.
     */
    fun write(route: Route, hr: List<Pair<Long, Int>> = emptyList()): String {
        val pts = route.pts
        val m = route.meta
        val timed = pts.isNotEmpty() && pts.first().t > 0
        val startMs = if (timed) pts.first().t else m.createdAt.coerceAtLeast(0)
        val cum = Geo.cumulative(pts)
        val beats = heartRateAt(pts, if (timed) hr else emptyList())
        val total = cum.lastOrNull() ?: 0.0
        val spanMs = when {
            timed -> pts.last().t - pts.first().t
            m.durationS > 0 -> m.durationS * 1000
            else -> (pts.size - 1).coerceAtLeast(0) * 1000L
        }
        fun timeOf(i: Int): Long = when {
            timed -> pts[i].t
            total > 0 -> startMs + (spanMs * (cum[i] / total)).toLong()
            else -> startMs + i * 1000L
        }
        val start = Instant.ofEpochMilli(startMs).toString()
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<TrainingCenterDatabase xmlns=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2\">\n")
        sb.append(" <Activities>\n  <Activity Sport=\"Biking\">\n   <Id>").append(start).append("</Id>\n")
        sb.append("   <Lap StartTime=\"").append(start).append("\">\n")
        sb.append("    <TotalTimeSeconds>").append(if (m.durationS > 0) m.durationS else spanMs / 1000).append("</TotalTimeSeconds>\n")
        sb.append(String.format(Locale.US, "    <DistanceMeters>%.1f</DistanceMeters>\n", m.distanceM))
        if (m.maxSpeedMs > 0) sb.append(String.format(Locale.US, "    <MaximumSpeed>%.2f</MaximumSpeed>\n", m.maxSpeedMs))
        sb.append("    <Calories>0</Calories>\n")
        if (m.avgHr > 0) sb.append("    <AverageHeartRateBpm><Value>${m.avgHr}</Value></AverageHeartRateBpm>\n")
        if (m.maxHr > 0) sb.append("    <MaximumHeartRateBpm><Value>${m.maxHr}</Value></MaximumHeartRateBpm>\n")
        sb.append("    <Intensity>Active</Intensity>\n    <TriggerMethod>Manual</TriggerMethod>\n")
        if (pts.isNotEmpty()) {
            sb.append("    <Track>\n")
            pts.forEachIndexed { i, p ->
                sb.append("     <Trackpoint><Time>").append(Instant.ofEpochMilli(timeOf(i)).toString()).append("</Time>")
                sb.append(String.format(Locale.US, "<Position><LatitudeDegrees>%.7f</LatitudeDegrees><LongitudeDegrees>%.7f</LongitudeDegrees></Position>", p.lat, p.lon))
                if (!p.ele.isNaN()) sb.append(String.format(Locale.US, "<AltitudeMeters>%.1f</AltitudeMeters>", p.ele))
                sb.append(String.format(Locale.US, "<DistanceMeters>%.1f</DistanceMeters>", cum[i]))
                if (beats[i] > 0) sb.append("<HeartRateBpm><Value>${beats[i]}</Value></HeartRateBpm>")
                sb.append("</Trackpoint>\n")
            }
            sb.append("    </Track>\n")
        }
        sb.append("   </Lap>\n")
        sb.append("   <Notes>").append(xmlEscape(m.name)).append("</Notes>\n")
        sb.append("  </Activity>\n </Activities>\n</TrainingCenterDatabase>\n")
        return sb.toString()
    }
}

/**
 * Garmin's FIT activity file: the compact binary format that Strava, Garmin Connect,
 * TrainingPeaks and most coaching tools import. For recorded rides.
 *
 * Layout: a 14-byte header, then messages (each kind is described once by a
 * "definition" and then written as plain values), then a checksum.
 */
object Fit {
    /** FIT counts seconds from the last day of 1989. */
    private const val EPOCH = 631065600L

    // Base types: enum, uint8, uint16, sint32, uint32, uint32z.
    private const val ENUM = 0x00
    private const val U8 = 0x02
    private const val U16 = 0x84
    private const val S32 = 0x85
    private const val U32 = 0x86
    private const val U32Z = 0x8C

    private class Body {
        val b = ByteArrayOutputStream()
        fun u8(v: Int) { b.write(v and 0xFF) }
        fun u16(v: Int) { u8(v); u8(v shr 8) }
        fun u32(v: Long) { u8(v.toInt()); u8((v shr 8).toInt()); u8((v shr 16).toInt()); u8((v shr 24).toInt()) }

        /** Describe message [global] as local type [local]: a list of (field number, size, base type). */
        fun define(local: Int, global: Int, vararg fields: Triple<Int, Int, Int>) {
            u8(0x40 or local); u8(0); u8(0); u16(global); u8(fields.size)
            for ((num, size, type) in fields) { u8(num); u8(size); u8(type) }
        }
    }

    private fun ts(ms: Long) = (ms / 1000 - EPOCH).coerceAtLeast(0)

    private fun crc(bytes: ByteArray, len: Int = bytes.size): Int {
        val table = intArrayOf(
            0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
            0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
        )
        var c = 0
        for (i in 0 until len) {
            val byte = bytes[i].toInt() and 0xFF
            var tmp = table[c and 0xF]
            c = (c shr 4) and 0x0FFF
            c = c xor tmp xor table[byte and 0xF]
            tmp = table[c and 0xF]
            c = (c shr 4) and 0x0FFF
            c = c xor tmp xor table[(byte shr 4) and 0xF]
        }
        return c
    }

    fun write(route: Route, hr: List<Pair<Long, Int>>, out: OutputStream) {
        val pts = route.pts
        val m = route.meta
        require(pts.size >= 2 && pts.first().t > 0) { "FIT export needs a recorded ride" }
        val cum = Geo.cumulative(pts)
        val beats = heartRateAt(pts, hr)
        val startMs = pts.first().t
        val endMs = pts.last().t
        val elapsedMs = (endMs - startMs).coerceAtLeast(1000)
        val timerMs = if (m.durationS > 0) m.durationS * 1000 else elapsedMs
        val d = Body()

        // file_id: this is an activity, made by a "development" manufacturer.
        d.define(0, 0, Triple(0, 1, ENUM), Triple(1, 2, U16), Triple(2, 2, U16), Triple(3, 4, U32Z), Triple(4, 4, U32))
        d.u8(0); d.u8(4); d.u16(255); d.u16(1); d.u32(1); d.u32(ts(startMs))

        // event: timer start.
        d.define(5, 21, Triple(253, 4, U32), Triple(0, 1, ENUM), Triple(1, 1, ENUM))
        d.u8(5); d.u32(ts(startMs)); d.u8(0); d.u8(0)

        // record: one per point.
        d.define(1, 20, Triple(253, 4, U32), Triple(0, 4, S32), Triple(1, 4, S32), Triple(5, 4, U32), Triple(2, 2, U16), Triple(6, 2, U16), Triple(3, 1, U8))
        val semi = 2147483648.0 / 180.0
        pts.forEachIndexed { i, p ->
            val speed = if (i == 0) 0.0 else {
                val dt = (p.t - pts[i - 1].t) / 1000.0
                if (dt > 0 && dt < 30) (cum[i] - cum[i - 1]) / dt else 0.0
            }
            d.u8(1)
            d.u32(ts(p.t))
            d.u32((p.lat * semi).roundToLong() and 0xFFFFFFFFL)
            d.u32((p.lon * semi).roundToLong() and 0xFFFFFFFFL)
            d.u32((cum[i] * 100).roundToLong())
            d.u16(if (p.ele.isNaN()) 0xFFFF else ((p.ele + 500) * 5).roundToInt().coerceIn(0, 0xFFFE))
            d.u16((speed * 1000).roundToInt().coerceIn(0, 0xFFFE))
            d.u8(if (beats[i] in 1..254) beats[i] else 0xFF)
        }

        // event: timer stop.
        d.u8(5); d.u32(ts(endMs)); d.u8(0); d.u8(4)

        // lap: the rider's laps, or one lap for the whole ride.
        val marks = if (m.laps.isNotEmpty()) m.laps else listOf(LapMark(m.distanceM, timerMs, elapsedMs))
        d.define(2, 19, Triple(254, 2, U16), Triple(253, 4, U32), Triple(2, 4, U32), Triple(7, 4, U32), Triple(8, 4, U32), Triple(9, 4, U32), Triple(0, 1, ENUM), Triple(1, 1, ENUM))
        var prev = LapMark(0.0, 0, 0)
        marks.forEachIndexed { i, lap ->
            d.u8(2)
            d.u16(i)
            d.u32(ts(startMs + lap.elapsedMs))
            d.u32(ts(startMs + prev.elapsedMs))
            d.u32((lap.elapsedMs - prev.elapsedMs).coerceAtLeast(0))
            d.u32((lap.movingMs - prev.movingMs).coerceAtLeast(0))
            d.u32(((lap.distanceM - prev.distanceM) * 100).roundToLong().coerceAtLeast(0))
            d.u8(9); d.u8(1)
            prev = lap
        }

        // session: the ride's totals. Sport 2 is cycling.
        d.define(
            3, 18, Triple(254, 2, U16), Triple(253, 4, U32), Triple(2, 4, U32), Triple(7, 4, U32), Triple(8, 4, U32), Triple(9, 4, U32),
            Triple(22, 2, U16), Triple(14, 2, U16), Triple(15, 2, U16), Triple(25, 2, U16), Triple(26, 2, U16),
            Triple(0, 1, ENUM), Triple(1, 1, ENUM), Triple(5, 1, ENUM), Triple(6, 1, ENUM), Triple(16, 1, U8), Triple(17, 1, U8),
        )
        d.u8(3)
        d.u16(0); d.u32(ts(endMs)); d.u32(ts(startMs)); d.u32(elapsedMs); d.u32(timerMs)
        d.u32((m.distanceM * 100).roundToLong())
        d.u16(m.ascentM.roundToInt().coerceIn(0, 0xFFFE))
        d.u16((m.distanceM / (timerMs / 1000.0) * 1000).roundToInt().coerceIn(0, 0xFFFE))
        d.u16(if (m.maxSpeedMs > 0) (m.maxSpeedMs * 1000).roundToInt().coerceIn(0, 0xFFFE) else 0xFFFF)
        d.u16(0); d.u16(marks.size)
        d.u8(8); d.u8(1); d.u8(2); d.u8(0)
        d.u8(if (m.avgHr in 1..254) m.avgHr else 0xFF); d.u8(if (m.maxHr in 1..254) m.maxHr else 0xFF)

        // activity: closes the file.
        d.define(4, 34, Triple(253, 4, U32), Triple(0, 4, U32), Triple(1, 2, U16), Triple(2, 1, ENUM), Triple(3, 1, ENUM), Triple(4, 1, ENUM))
        d.u8(4); d.u32(ts(endMs)); d.u32(timerMs); d.u16(1); d.u8(0); d.u8(26); d.u8(1)

        val data = d.b.toByteArray()
        val h = Body()
        h.u8(14); h.u8(0x10); h.u16(2132); h.u32(data.size.toLong())
        h.b.write(".FIT".toByteArray(Charsets.US_ASCII))
        val head = h.b.toByteArray()
        h.u16(crc(head))
        val all = h.b.toByteArray() + data
        out.write(all)
        val c = crc(all)
        out.write(c and 0xFF); out.write((c shr 8) and 0xFF)
    }
}
