package com.cycler.app.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.Instant
import java.util.Locale

object Gpx {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

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

    /** Returns the track name (may be null) and its points. Reads trkpt and rtept. */
    fun read(input: InputStream): Pair<String?, List<Pt>> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        val pts = ArrayList<Pt>()
        var name: String? = null
        var lat = 0.0
        var lon = 0.0
        var ele = Double.NaN
        var time = 0L
        var inPt = false
        var text = ""
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    text = ""
                    if (parser.name == "trkpt" || parser.name == "rtept") {
                        inPt = true
                        lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                        lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                        ele = Double.NaN
                        time = 0L
                    }
                }
                XmlPullParser.TEXT -> text += parser.text
                XmlPullParser.END_TAG -> when (parser.name) {
                    "ele" -> if (inPt) ele = text.trim().toDoubleOrNull() ?: Double.NaN
                    "time" -> if (inPt) time = runCatching { Instant.parse(text.trim()).toEpochMilli() }.getOrDefault(0L)
                    "name" -> if (!inPt && name == null && text.isNotBlank()) name = text.trim()
                    "trkpt", "rtept" -> { pts.add(Pt(lat, lon, ele, time)); inPt = false }
                }
            }
            ev = parser.next()
        }
        return name to pts
    }
}
