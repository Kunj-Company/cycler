package com.cycler.app.analytics

import android.content.Context
import android.content.SharedPreferences
import com.cycler.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.util.Locale
import java.util.UUID
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Features counted in the anonymous weekly stats. The keys are the fixed list of the stats contract
 * (v1); call sites use these constants, never free text. A new key must match ^[a-z][a-z0-9_]{1,39}$.
 */
enum class Feature(val key: String) {
    EXPORT_GPX("export_gpx"), EXPORT_TCX("export_tcx"), EXPORT_FIT("export_fit"),
    SHARE_VIDEO("share_video"), SHARE_CARDS("share_cards"), SHARE_FILE("share_file"),
    PLAN_A_TO_B("plan_a_to_b"), PLAN_LOOP("plan_loop"), ROUTE_SAVED("route_saved"),
    ROUTE_IMPORT_GPX("route_import_gpx"), SEARCH("search"),
    /** Added after v1 of the contract: "Suggest routes near me" on the Rides tab's routes list. */
    ROUTE_SUGGEST("route_suggest"),
    LAP("lap"), PAUSE("pause"), WORKOUT("workout"), BIG_NUMBERS("big_numbers"),
    WIDGET_RIDE("widget_ride"), WIDGET_STATS("widget_stats"), WIDGET_WEEK("widget_week"), WIDGET_LAST_RIDE("widget_last_ride"),
    OFFLINE_MAP_DOWNLOAD("offline_map_download"), BACKUP_EXPORT("backup_export"), BACKUP_IMPORT("backup_import"),
    HEALTH_CONNECT_CONNECT("health_connect_connect"), BIKE_CARE("bike_care"), BIKE_ADDED("bike_added"),
    SEGMENTS_OPENED("segments_opened"),
    MAP_LOAD_FAILED("map_load_failed"), ROUTING_FAILED("routing_failed"), RIDE_RECOVERED("ride_recovered"),
}

/**
 * Anonymous usage stats, counted per ISO week on this phone and sent once the week is over (see
 * [StatsSender]). What is kept: a random install id, counts, kilometres and ~11 km cells where
 * finished rides started and ended. Never names, routes, exact places, times finer than a week,
 * search text or health data.
 *
 * Stored in its own preferences file, which is not part of Cycler's backup zip (that only carries
 * the keys in Prefs.SPEC) and not part of Android backup (turned off for everything).
 *
 * Every function is safe to call from any thread and never throws.
 */
object UsageStats {
    internal const val PREFS = "cycler_stats"
    private const val K_ID = "id"
    private const val K_ENABLED = "enabled"
    private const val K_NOTICE = "noticeSeen"
    private const val K_WEEKS = "weeks"

    /** Completed weeks kept waiting to be sent; older ones are dropped. */
    const val MAX_WEEKS = 8
    const val MAX_FEATURES = 64
    const val MAX_CELLS = 50
    private val KEY_RE = Regex("^[a-z][a-z0-9_]{1,39}$")

    /** Today in local time. Tests replace it. */
    @Volatile internal var today: () -> LocalDate = { LocalDate.now() }

    private val lock = Any()

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val appVersion: String
        get() = BuildConfig.VERSION_NAME + if (BuildConfig.DEBUG) "-debug" else ""

    fun isEnabled(ctx: Context): Boolean = runCatching { sp(ctx).getBoolean(K_ENABLED, true) }.getOrDefault(false)

    /** Off: stop counting and delete everything not yet sent. The install id is kept for when it is turned back on. */
    fun setEnabled(ctx: Context, on: Boolean) {
        runCatching {
            synchronized(lock) {
                val ed = sp(ctx).edit().putBoolean(K_ENABLED, on)
                if (!on) ed.remove(K_WEEKS)
                ed.commit()
            }
            if (on) StatsWork.schedule(ctx) else StatsWork.cancel(ctx)
        }
    }

    /** The random install id (UUID v4), made on first use. */
    fun installId(ctx: Context): String = synchronized(lock) {
        val p = sp(ctx)
        p.getString(K_ID, null)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().also {
            p.edit().putString(K_ID, it).commit()
        }
    }

    fun noticeSeen(ctx: Context): Boolean = runCatching { sp(ctx).getBoolean(K_NOTICE, false) }.getOrDefault(true)

    /** The rider tapped Continue on the stats notice. Only this turns counting and sending on. */
    fun setNoticeSeen(ctx: Context) {
        runCatching {
            sp(ctx).edit().putBoolean(K_NOTICE, true).commit()
            StatsWork.schedule(ctx)
        }
    }

    // ---- Counting ----

    fun track(ctx: Context, feature: Feature) = edit(ctx) { w ->
        val f = w.optJSONObject("f") ?: JSONObject().also { w.put("f", it) }
        f.put(feature.key, f.optInt(feature.key, 0) + 1)
    }

    /** The app was opened today. */
    fun markActive(ctx: Context) = edit(ctx) { w ->
        val bit = 1 shl (today().dayOfWeek.value - 1)
        w.put("days", w.optInt("days", 0) or bit)
    }

    fun rideStarted(ctx: Context) = edit(ctx) { w -> w.put("started", w.optInt("started", 0) + 1) }

    fun rideDiscarded(ctx: Context) = edit(ctx) { w -> w.put("discarded", w.optInt("discarded", 0) + 1) }

    /** A ride was saved: its distance, and the cells (not the points) it started and ended in. */
    fun rideFinished(ctx: Context, distanceM: Double, startLat: Double, startLon: Double, endLat: Double, endLon: Double) = edit(ctx) { w ->
        val km = (distanceM / 1000.0).coerceAtLeast(0.0).takeIf { it.isFinite() } ?: 0.0
        w.put("finished", w.optInt("finished", 0) + 1)
        w.put("km", w.optDouble("km", 0.0) + km)
        val cells = w.optJSONObject("cells") ?: JSONObject().also { w.put("cells", it) }
        fun add(lat: Double, lon: Double, kind: String) {
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return
            val key = "${cell(lat)},${cell(lon)},$kind"
            val c = cells.optJSONArray(key) ?: JSONArray().put(0).put(0.0)
            cells.put(key, JSONArray().put(c.optInt(0) + 1).put(c.optDouble(1, 0.0) + km))
        }
        add(startLat, startLon, "start")
        add(endLat, endLon, "end")
    }

    private fun edit(ctx: Context, change: (JSONObject) -> Unit) {
        runCatching {
            synchronized(lock) {
                val p = sp(ctx)
                // Nothing is counted until the rider has tapped Continue on the stats notice, nor while it is off.
                if (!p.getBoolean(K_ENABLED, true) || !p.getBoolean(K_NOTICE, false)) return
                val weeks = prune(readWeeks(p))
                val id = weekId(today())
                val w = weeks.optJSONObject(id) ?: JSONObject().also { weeks.put(id, it) }
                change(w)
                p.edit().putString(K_WEEKS, weeks.toString()).apply()
            }
        }
    }

    // ---- Weeks waiting to be sent ----

    private fun readWeeks(p: SharedPreferences): JSONObject =
        runCatching { JSONObject(p.getString(K_WEEKS, null) ?: "{}") }.getOrElse { JSONObject() }

    /** Drop weeks more than [MAX_WEEKS] before the current one (and anything unreadable). */
    private fun prune(weeks: JSONObject): JSONObject {
        val oldest = monday(today()).minusWeeks(MAX_WEEKS.toLong())
        for (k in weeks.keys().asSequence().toList()) {
            val m = weekMonday(k)
            if (m == null || m.isBefore(oldest)) weeks.remove(k)
        }
        return weeks
    }

    /** Weeks with something to send, oldest first. Only completed weeks unless [includeCurrent] (debug builds). */
    internal fun pendingWeeks(ctx: Context, includeCurrent: Boolean = false): List<String> = runCatching {
        synchronized(lock) {
            val p = sp(ctx)
            val weeks = prune(readWeeks(p))
            p.edit().putString(K_WEEKS, weeks.toString()).apply()
            val current = weekId(today())
            weeks.keys().asSequence().filter { if (includeCurrent) it <= current else it < current }.sorted().toList()
        }
    }.getOrDefault(emptyList())

    /** A copy of the stored counts for [week], or null. */
    internal fun weekData(ctx: Context, week: String): JSONObject? = runCatching {
        synchronized(lock) { readWeeks(sp(ctx)).optJSONObject(week)?.let { JSONObject(it.toString()) } }
    }.getOrNull()

    /** The random id of [week]'s area report: made once and kept, so a retry is recognised as the same report. */
    internal fun areaReportId(ctx: Context, week: String): String? = update(ctx, week) { w ->
        w.optString("areaId").ifBlank { UUID.randomUUID().toString().also { w.put("areaId", it) } }
    }

    internal fun markAreaDone(ctx: Context, week: String) { update(ctx, week) { it.put("areaDone", true) } }

    internal fun removeWeek(ctx: Context, week: String) {
        runCatching {
            synchronized(lock) {
                val p = sp(ctx)
                val weeks = readWeeks(p)
                weeks.remove(week)
                p.edit().putString(K_WEEKS, weeks.toString()).apply()
            }
        }
    }

    private fun <T> update(ctx: Context, week: String, change: (JSONObject) -> T): T? = runCatching {
        synchronized(lock) {
            val p = sp(ctx)
            val weeks = readWeeks(p)
            val w = weeks.optJSONObject(week) ?: return@synchronized null
            val r = change(w)
            p.edit().putString(K_WEEKS, weeks.toString()).apply()
            r
        }
    }.getOrNull()

    // ---- The two reports (exact shape of the stats contract) ----

    /** POST /v1/report for [week]. */
    internal fun buildReport(install: String, version: String, week: String, w: JSONObject): JSONObject {
        val features = JSONObject()
        w.optJSONObject("f")?.let { f ->
            f.keys().asSequence().filter { KEY_RE.matches(it) }
                .map { it to f.optInt(it, 0) }.filter { it.second > 0 }
                .sortedByDescending { it.second }.take(MAX_FEATURES)
                .sortedBy { it.first }
                .forEach { (k, n) -> features.put(k, n.coerceAtMost(10_000)) }
        }
        return JSONObject()
            .put("schema", 1)
            .put("install", install)
            .put("app_version", version)
            .put("week", week)
            .put("days_active", Integer.bitCount(w.optInt("days", 0) and 0x7f))
            .put("rides_started", w.optInt("started", 0).coerceIn(0, 500))
            .put("rides_finished", w.optInt("finished", 0).coerceIn(0, 500))
            .put("rides_discarded", w.optInt("discarded", 0).coerceIn(0, 500))
            .put("km", km1(w.optDouble("km", 0.0)))
            // Left out when nothing was used, as the server allows.
            .also { if (features.length() > 0) it.put("features", features) }
    }

    /** POST /v1/area for [week], or null when the week has no finished ride. Carries nothing that links it to the install. */
    internal fun buildArea(reportId: String, version: String, week: String, w: JSONObject): JSONObject? {
        if (w.optInt("finished", 0) <= 0) return null
        val raw = w.optJSONObject("cells") ?: return null
        val cells = raw.keys().asSequence().mapNotNull { key ->
            val p = key.split(',')
            val lat = p.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val lon = p.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            val kind = p.getOrNull(2)?.takeIf { it == "start" || it == "end" } ?: return@mapNotNull null
            val v = raw.optJSONArray(key) ?: return@mapNotNull null
            val rides = v.optInt(0, 0)
            if (rides <= 0) return@mapNotNull null
            Cell(lat, lon, kind, rides, v.optDouble(1, 0.0))
        }.sortedByDescending { it.rides }.take(MAX_CELLS)
            .sortedWith(compareBy({ it.kind != "start" }, { it.lat }, { it.lon })).toList()
        if (cells.isEmpty()) return null
        val arr = JSONArray()
        cells.forEach { c ->
            arr.put(
                JSONObject().put("lat", c.lat / 10.0).put("lon", c.lon / 10.0).put("kind", c.kind)
                    .put("rides", c.rides.coerceIn(1, 500)).put("km", km1(c.km)),
            )
        }
        return JSONObject()
            .put("schema", 1)
            .put("report", reportId)
            .put("app_version", version)
            .put("week", week)
            .put("cells", arr)
    }

    private class Cell(val lat: Int, val lon: Int, val kind: String, val rides: Int, val km: Double)

    private fun km1(km: Double): Double =
        if (!km.isFinite()) 0.0 else (km.coerceIn(0.0, 20_000.0) * 10).roundToLong() / 10.0

    // ---- Weeks and cells ----

    /** Tenths of a degree, rounded down: the south-west corner of a cell about 11 km across. */
    internal fun cell(v: Double): Int = floor(v * 10 + 1e-9).toInt()

    /** ISO-8601 week of [d], such as "2026-W48". */
    fun weekId(d: LocalDate): String =
        String.format(Locale.US, "%04d-W%02d", d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

    private fun monday(d: LocalDate): LocalDate = d.with(DayOfWeek.MONDAY)

    internal fun weekMonday(id: String): LocalDate? = runCatching {
        val m = Regex("^(\\d{4})-W(\\d{2})$").find(id) ?: return null
        val y = m.groupValues[1].toInt()
        val wk = m.groupValues[2].toLong()
        val d = LocalDate.of(y, 1, 4).with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, wk).with(DayOfWeek.MONDAY)
        if (weekId(d) != id) null else d
    }.getOrNull()

    /** For tests: forget everything, including the id. */
    internal fun resetForTests(ctx: Context) {
        synchronized(lock) { sp(ctx).edit().clear().commit() }
        today = { LocalDate.now() }
    }
}
