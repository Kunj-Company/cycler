package com.cycler.app.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.Instant
import java.util.Locale

object Gpx {
    private fun esc(s: String) = xmlEscape(s)

    fun write(route: Route): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Cycler\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk>\n    <name>").append(esc(route.meta.name)).append("</name>\n    <trkseg>\n")
        for (p in route.pts) {
            sb.append(String.format(Locale.US, "      <trkpt lat=\"%.6f\" lon=\"%.6f\">", p.lat, p.lon))
            if (!p.ele.isNaN()) sb.append(String.format(Locale.US, "<ele>%.1f</ele>", p.ele))
            if (p.t > 0) sb.append("<time>").append(Instant.ofEpochMilli(p.t).toString()).append("</time>")
            sb.append("</trkpt>\n")
        }
        sb.append("    </trkseg>\n  </trk>\n</gpx>\n")
        return sb.toString()
    }

    /** Most points one imported file may hold; a longer file is refused rather than read into memory. */
    const val MAX_POINTS = 500_000

    /** What reading a GPX file found: the name, the usable points, and how many were left out. */
    class ReadResult(
        val name: String?,
        val pts: List<Pt>,
        /** Points dropped because their latitude or longitude was missing, not a number or out of range. */
        val skipped: Int,
        /** The file held more than [MAX_POINTS] points; [pts] is empty then. */
        val tooLarge: Boolean = false,
    )

    /** Returns the track name (may be null) and its points. Reads trkpt and rtept. Points with unusable coordinates are left out; see [readWithReport]. */
    fun read(input: InputStream): Pair<String?, List<Pt>> = readWithReport(input).let { it.name to it.pts }

    /** Like [read], but also says how many points were skipped, so the caller can tell the rider. */
    fun readWithReport(input: InputStream): ReadResult {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        val pts = ArrayList<Pt>()
        var skipped = 0
        var name: String? = null
        var lat = Double.NaN
        var lon = Double.NaN
        var ele = Double.NaN
        var time = 0L
        var inPt = false
        var text = ""
        var seen = 0
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    text = ""
                    if (parser.name == "trkpt" || parser.name == "rtept") {
                        inPt = true
                        lat = parser.getAttributeValue(null, "lat")?.trim()?.toDoubleOrNull() ?: Double.NaN
                        lon = parser.getAttributeValue(null, "lon")?.trim()?.toDoubleOrNull() ?: Double.NaN
                        ele = Double.NaN
                        time = 0L
                    }
                }
                XmlPullParser.TEXT -> if (text.length < 256) text += parser.text
                XmlPullParser.END_TAG -> when (parser.name) {
                    "ele" -> if (inPt) ele = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: Double.NaN
                    "time" -> if (inPt) time = runCatching { Instant.parse(text.trim()).toEpochMilli() }.getOrDefault(0L)
                    "name" -> if (!inPt && name == null && text.isNotBlank()) name = text.trim()
                    "trkpt", "rtept" -> {
                        if (++seen > MAX_POINTS) return ReadResult(name, emptyList(), 0, tooLarge = true)
                        if (validCoord(lat, lon)) pts.add(Pt(lat, lon, ele, time)) else skipped++
                        inPt = false
                    }
                }
            }
            ev = parser.next()
        }
        return ReadResult(name, pts, skipped)
    }

    /** Whether [lat] and [lon] are finite and on the globe. */
    fun validCoord(lat: Double, lon: Double): Boolean =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
}

/**
 * Escapes [s] for XML 1.0 text, after dropping the characters XML 1.0 cannot carry at all:
 * control characters other than tab, newline and carriage return, unpaired surrogates, and
 * U+FFFE and U+FFFF.
 */
fun xmlEscape(s: String): String {
    val sb = StringBuilder(s.length + 8)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '&' -> sb.append("&amp;")
            c == '<' -> sb.append("&lt;")
            c == '>' -> sb.append("&gt;")
            c == '\t' || c == '\n' || c == '\r' -> sb.append(c)
            c < ' ' || c == '\uFFFE' || c == '\uFFFF' -> {}
            Character.isHighSurrogate(c) ->
                if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) { sb.append(c).append(s[i + 1]); i++ }
            Character.isLowSurrogate(c) -> {}
            else -> sb.append(c)
        }
        i++
    }
    return sb.toString()
}
