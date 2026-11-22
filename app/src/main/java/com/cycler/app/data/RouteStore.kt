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
            runCatching { metaFromJson(JSONObject(f.readText())) }.getOrNull()
        }
        _metas.value = list.sortedByDescending { it.createdAt }
    }

    @Synchronized
    fun save(
        name: String, type: RouteType, pts: List<Pt>, durationS: Long,
        distanceM: Double = Geo.length(pts),
        ascentM: Double = Geo.ascent(pts),
        createdAt: Long = System.currentTimeMillis(),
    ): String {
        val id = UUID.randomUUID().toString()
        val meta = RouteMeta(
            id, name.ifBlank { "Untitled" }, type, createdAt, distanceM, ascentM, durationS,
            pts.firstOrNull()?.lat ?: 0.0, pts.firstOrNull()?.lon ?: 0.0,
        )
        val arr = JSONArray()
        for (p in pts) {
            val a = JSONArray().put(p.lat).put(p.lon)
            a.put(if (p.ele.isNaN()) JSONObject.NULL else p.ele)
            a.put(p.t)
            arr.put(a)
        }
        File(dir, "$id.pts.json").writeText(arr.toString())
        File(dir, "$id.meta.json").writeText(metaToJson(meta).toString())
        reload()
        return id
    }

    fun load(id: String): Route? {
        val meta = _metas.value.firstOrNull { it.id == id } ?: return null
        val f = File(dir, "$id.pts.json")
        if (!f.exists()) return null
        return runCatching {
            val arr = JSONArray(f.readText())
            val pts = ArrayList<Pt>(arr.length())
            for (i in 0 until arr.length()) {
                val a = arr.getJSONArray(i)
                pts.add(Pt(a.getDouble(0), a.getDouble(1), a.optDouble(2, Double.NaN), a.optLong(3, 0L)))
            }
            Route(meta, pts)
        }.getOrNull()
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val meta = _metas.value.firstOrNull { it.id == id } ?: return
        File(dir, "$id.meta.json").writeText(metaToJson(meta.copy(name = name.ifBlank { meta.name })).toString())
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
        val pts = runCatching {
            recordingFile.readLines().mapNotNull { line ->
                val p = line.split(',')
                if (p.size < 4) null
                else Pt(p[0].toDouble(), p[1].toDouble(), p[2].toDoubleOrNull() ?: Double.NaN, p[3].toLong())
            }
        }.getOrDefault(emptyList())
        recordingFile.delete()
        if (pts.size < 2) return null
        val dur = ((pts.last().t - pts.first().t) / 1000).coerceAtLeast(0)
        return save(
            "Recovered ride", RouteType.RECORDED, pts, dur,
            ascentM = Geo.ascent(pts, 4.0, smooth = true), createdAt = pts.first().t,
        )
    }

    private fun metaToJson(m: RouteMeta) = JSONObject()
        .put("id", m.id).put("name", m.name).put("type", m.type.name)
        .put("createdAt", m.createdAt).put("distanceM", m.distanceM)
        .put("ascentM", m.ascentM).put("durationS", m.durationS)
        .put("startLat", m.startLat).put("startLon", m.startLon)

    private fun metaFromJson(o: JSONObject) = RouteMeta(
        o.getString("id"), o.getString("name"), RouteType.valueOf(o.getString("type")),
        o.getLong("createdAt"), o.getDouble("distanceM"), o.getDouble("ascentM"),
        o.getLong("durationS"), o.optDouble("startLat", 0.0), o.optDouble("startLon", 0.0),
    )

    companion object {
        @Volatile private var inst: RouteStore? = null
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
    var lastLoc: Pt?
        get() = if (sp.contains("lastLat")) Pt(sp.getFloat("lastLat", 0f).toDouble(), sp.getFloat("lastLon", 0f).toDouble()) else null
        set(v) {
            if (v != null) sp.edit().putFloat("lastLat", v.lat.toFloat()).putFloat("lastLon", v.lon.toFloat()).apply()
        }
}
