package com.cycler.app.net

import com.cycler.app.data.Geo
import com.cycler.app.data.Pt
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

private fun httpGet(url: String, timeoutMs: Int): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 15000
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", "Cycler/0.1 (personal Android app)")
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw IOException(body.trim().take(160).ifBlank { "Server error $code" })
        return body
    } finally {
        conn.disconnect()
    }
}

/**
 * Cycling routing by BRouter (https://brouter.de) — free, no key, built on
 * OpenStreetMap, returns elevation for every point of the route.
 */
object BRouter {
    class Result(val pts: List<Pt>, val distanceM: Double, val ascentM: Double, val timeS: Long)

    /** Bike types offered in Settings: BRouter profile id to label. */
    val profiles = listOf(
        "fastbike" to "Road bike",
        "trekking" to "Hybrid / touring",
        "gravel" to "Gravel",
        "mtb" to "Mountain bike",
    )

    /** Blocking. [alt] 0..3 asks for alternative routes between the same points. */
    fun route(points: List<Pt>, profile: String, alt: Int = 0): Result {
        require(points.size >= 2) { "Need at least two points" }
        val lonlats = points.joinToString("|") { String.format(Locale.US, "%.6f,%.6f", it.lon, it.lat) }
        val url = "https://brouter.de/brouter?lonlats=" + URLEncoder.encode(lonlats, "UTF-8") +
            "&profile=$profile&alternativeidx=$alt&format=geojson"
        val body = httpGet(url, 90000)
        try {
            val f = JSONObject(body).getJSONArray("features").getJSONObject(0)
            val props = f.getJSONObject("properties")
            val coords = f.getJSONObject("geometry").getJSONArray("coordinates")
            val pts = ArrayList<Pt>(coords.length())
            for (i in 0 until coords.length()) {
                val c = coords.getJSONArray(i)
                pts.add(Pt(c.getDouble(1), c.getDouble(0), if (c.length() > 2) c.getDouble(2) else Double.NaN))
            }
            val dist = props.optString("track-length").toDoubleOrNull() ?: Geo.length(pts)
            val asc = props.optString("filtered ascend").toDoubleOrNull() ?: Geo.ascent(pts)
            val time = props.optString("total-time").toDoubleOrNull()?.toLong() ?: (dist / 5.0).toLong()
            return Result(pts, dist, asc, time)
        } catch (e: org.json.JSONException) {
            throw IOException(body.trim().take(160).ifBlank { "No route found between these points" })
        }
    }
}

/** Place search by Photon (https://photon.komoot.io) — free, no key, OpenStreetMap data. */
object Photon {
    class Place(val label: String, val pt: Pt)

    fun search(query: String, near: Pt?): List<Place> {
        var url = "https://photon.komoot.io/api/?limit=6&q=" + URLEncoder.encode(query, "UTF-8")
        if (near != null) url += String.format(Locale.US, "&lat=%.4f&lon=%.4f", near.lat, near.lon)
        val feats = JSONObject(httpGet(url, 20000)).getJSONArray("features")
        val out = ArrayList<Place>()
        for (i in 0 until feats.length()) {
            val f = feats.getJSONObject(i)
            val p = f.getJSONObject("properties")
            val c = f.getJSONObject("geometry").getJSONArray("coordinates")
            val parts = listOf("name", "street", "city", "state", "country")
                .map { p.optString(it) }.filter { it.isNotBlank() }.distinct()
            if (parts.isEmpty()) continue
            out.add(Place(parts.joinToString(", "), Pt(c.getDouble(1), c.getDouble(0))))
        }
        return out
    }
}

/** Current weather by Open-Meteo (https://open-meteo.com) — free, no key. */
object Weather {
    class Now(
        val tempC: Double,
        val windKmh: Double,
        val gustKmh: Double,
        /** Direction the wind comes from, degrees. */
        val windFromDeg: Double,
        /** Highest chance of rain over the next three hours, percent. */
        val rainChance: Int,
        val code: Int,
    ) {
        val windFrom: String
            get() = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((windFromDeg % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]
        val summary: String
            get() = when (code) {
                0 -> "Clear"
                1, 2 -> "Partly cloudy"
                3 -> "Cloudy"
                45, 48 -> "Fog"
                in 51..57 -> "Drizzle"
                in 61..67, in 80..82 -> "Rain"
                in 71..77, 85, 86 -> "Snow"
                in 95..99 -> "Thunderstorm"
                else -> "—"
            }
    }

    fun now(at: Pt): Now {
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f" +
                "&current=temperature_2m,wind_speed_10m,wind_direction_10m,wind_gusts_10m,weather_code" +
                "&hourly=precipitation_probability&forecast_hours=3&timezone=auto",
            at.lat, at.lon,
        )
        val o = JSONObject(httpGet(url, 15000))
        val c = o.getJSONObject("current")
        val probs = o.optJSONObject("hourly")?.optJSONArray("precipitation_probability")
        var rain = 0
        if (probs != null) for (i in 0 until probs.length()) rain = maxOf(rain, probs.optInt(i, 0))
        return Now(
            c.getDouble("temperature_2m"), c.getDouble("wind_speed_10m"), c.optDouble("wind_gusts_10m", 0.0),
            c.optDouble("wind_direction_10m", 0.0), rain, c.optInt("weather_code", -1),
        )
    }
}
