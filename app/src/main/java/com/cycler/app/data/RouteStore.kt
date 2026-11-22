package com.cycler.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Everything is stored as plain JSON files inside the app's private folder:
 * routes/<id>.meta.json (summary) and routes/<id>.pts.json (the points).
 */
class RouteStore private constructor(ctx: Context) {
    private val dir = File(ctx.filesDir, "routes").apply { mkdirs() }
    /** Points of a ride in progress, appended as they arrive so a crash loses nothing. */
    val recordingFile = File(ctx.filesDir, "recording.csv")

    private val _metas = MutableStateFlow<List<RouteMeta>>(emptyList())
    val metas: StateFlow<List<RouteMeta>> = _metas

    init { reload() }

    @Synchronized
    fun reload() {
        val list = dir.listFiles { f -> f.name.endsWith(".meta.json") }.orEmpty().mapNotNull { f ->
            runCatching {
                var m = metaFromJson(JSONObject(f.readText()))
                if (m.thumb.isEmpty()) {
                    // Routes saved by an older version: add the outline once.
                    val pts = readPoints(m.id)
                    if (pts.size >= 2) {
                        m = m.copy(thumb = Geo.simplify(pts))
                        writeSafely(f, metaToJson(m).toString())
                    }
                }
                m
            }.getOrNull()
        }
        _metas.value = list.sortedByDescending { it.createdAt }
    }

    @Synchronized
    fun save(
        name: String, type: RouteType, pts: List<Pt>, durationS: Long,
        distanceM: Double = Geo.length(pts),
        ascentM: Double = Geo.ascent(pts),
        createdAt: Long = System.currentTimeMillis(),
        maxSpeedMs: Double = 0.0,
        elapsedS: Long = 0,
    ): String {
        val id = UUID.randomUUID().toString()
        val meta = RouteMeta(
            id, name.ifBlank { "Untitled" }, type, createdAt, distanceM, ascentM, durationS,
            pts.firstOrNull()?.lat ?: 0.0, pts.firstOrNull()?.lon ?: 0.0,
            maxSpeedMs, elapsedS, Geo.simplify(pts),
        )
        val arr = JSONArray()
        for (p in pts) {
            val a = JSONArray().put(p.lat).put(p.lon)
            a.put(if (p.ele.isNaN()) JSONObject.NULL else p.ele)
            a.put(p.t)
            arr.put(a)
        }
        writeSafely(File(dir, "$id.pts.json"), arr.toString())
        writeSafely(File(dir, "$id.meta.json"), metaToJson(meta).toString())
        reload()
        return id
    }

    fun load(id: String): Route? {
        val meta = _metas.value.firstOrNull { it.id == id } ?: return null
        val pts = readPoints(id)
        return if (pts.isEmpty()) null else Route(meta, pts)
    }

    private fun readPoints(id: String): List<Pt> {
        val f = File(dir, "$id.pts.json")
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            val pts = ArrayList<Pt>(arr.length())
            for (i in 0 until arr.length()) {
                val a = arr.getJSONArray(i)
                pts.add(Pt(a.getDouble(0), a.getDouble(1), a.optDouble(2, Double.NaN), a.optLong(3, 0L)))
            }
            pts
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val meta = _metas.value.firstOrNull { it.id == id } ?: return
        writeSafely(File(dir, "$id.meta.json"), metaToJson(meta.copy(name = name.ifBlank { meta.name })).toString())
        reload()
    }

    @Synchronized
    fun delete(id: String) {
        File(dir, "$id.meta.json").delete()
        File(dir, "$id.pts.json").delete()
        reload()
    }

    /** If the app died mid-ride, turn the leftover points into a saved ride. */
    fun recoverUnfinished(): String? {
        if (!recordingFile.exists()) return null
        // Read line by line: a half-written last line must not cost the whole ride.
        val pts = runCatching { recordingFile.readLines() }.getOrDefault(emptyList()).mapNotNull { line ->
            val p = line.split(',')
            val lat = p.getOrNull(0)?.toDoubleOrNull()
            val lon = p.getOrNull(1)?.toDoubleOrNull()
            val t = p.getOrNull(3)?.trim()?.toLongOrNull()
            if (lat == null || lon == null || t == null) null
            else Pt(lat, lon, p.getOrNull(2)?.toDoubleOrNull() ?: Double.NaN, t)
        }
        if (pts.size < 2) {
            recordingFile.delete()
            return null
        }
        val dur = ((pts.last().t - pts.first().t) / 1000).coerceAtLeast(0)
        val id = save(
            "Recovered ride", RouteType.RECORDED, pts, dur,
            ascentM = Geo.ascent(pts, 4.0, smooth = true), createdAt = pts.first().t, elapsedS = dur,
        )
        // Only now is it safe to drop the raw recording.
        recordingFile.delete()
        return id
    }

    /** Write to a temporary file and swap it in, so a kill mid-write can't leave a broken file. */
    private fun writeSafely(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }

    private fun metaToJson(m: RouteMeta) = JSONObject()
        .put("id", m.id).put("name", m.name).put("type", m.type.name)
        .put("createdAt", m.createdAt).put("distanceM", m.distanceM)
        .put("ascentM", m.ascentM).put("durationS", m.durationS)
        .put("startLat", m.startLat).put("startLon", m.startLon)
        .put("maxSpeedMs", m.maxSpeedMs).put("elapsedS", m.elapsedS)
        .put("thumb", JSONArray().also { arr -> m.thumb.forEach { arr.put(JSONArray().put(it.lat).put(it.lon)) } })

    private fun metaFromJson(o: JSONObject) = RouteMeta(
        o.getString("id"), o.getString("name"), RouteType.valueOf(o.getString("type")),
        o.getLong("createdAt"), o.getDouble("distanceM"), o.getDouble("ascentM"),
        o.getLong("durationS"), o.optDouble("startLat", 0.0), o.optDouble("startLon", 0.0),
        o.optDouble("maxSpeedMs", 0.0), o.optLong("elapsedS", 0L),
        runCatching {
            o.optJSONArray("thumb")?.let { arr ->
                (0 until arr.length()).map { i -> arr.getJSONArray(i).let { Pt(it.getDouble(0), it.getDouble(1)) } }
            }
        }.getOrNull() ?: emptyList(),
    )

    companion object {
        @Volatile private var inst: RouteStore? = null
        /** For tests only: forget the shared instance so the next get() starts clean. */
        internal fun resetForTests() { inst = null }

        fun get(ctx: Context): RouteStore =
            inst ?: synchronized(this) { inst ?: RouteStore(ctx.applicationContext).also { inst = it } }
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("cycler", Context.MODE_PRIVATE)

    var profile: String
        get() = sp.getString("profile", "trekking")!!
        set(v) = sp.edit().putString("profile", v).apply()
    var mapStyle: String
        get() = sp.getString("mapStyle", "liberty")!!
        set(v) = sp.edit().putString("mapStyle", v).apply()
    var keepScreenOn: Boolean
        get() = sp.getBoolean("keepScreenOn", true)
        set(v) = sp.edit().putBoolean("keepScreenOn", v).apply()
    /** "system", "light" or "dark". */
    var themeMode: String
        get() = sp.getString("themeMode", "system")!!
        set(v) = sp.edit().putString("themeMode", v).apply()
    var autoPause: Boolean
        get() = sp.getBoolean("autoPause", true)
        set(v) = sp.edit().putBoolean("autoPause", v).apply()
    var offRouteAlert: Boolean
        get() = sp.getBoolean("offRouteAlert", true)
        set(v) = sp.edit().putBoolean("offRouteAlert", v).apply()
    var voice: Boolean
        get() = sp.getBoolean("voice", false)
        set(v) = sp.edit().putBoolean("voice", v).apply()
    var weeklyGoalKm: Int
        get() = sp.getInt("weeklyGoalKm", 50)
        set(v) = sp.edit().putInt("weeklyGoalKm", v).apply()
    var lastLoc: Pt?
        get() = if (sp.contains("lastLat")) Pt(sp.getFloat("lastLat", 0f).toDouble(), sp.getFloat("lastLon", 0f).toDouble()) else null
        set(v) {
            if (v != null) sp.edit().putFloat("lastLat", v.lat.toFloat()).putFloat("lastLon", v.lon.toFloat()).apply()
        }
}
