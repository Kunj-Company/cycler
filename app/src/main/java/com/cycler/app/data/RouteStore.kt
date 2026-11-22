package com.cycler.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import com.cycler.app.widget.Widgets

/**
 * Everything is stored as plain JSON files inside the app's private folder:
 * routes/<id>.meta.json (summary) and routes/<id>.pts.json (the points).
 */
class RouteStore private constructor(ctx: Context) {
    private val appContext = ctx.applicationContext
    private val dir = File(ctx.filesDir, "routes").apply { mkdirs() }
    /** Points of a ride in progress, appended as they arrive so a crash loses nothing. */
    val recordingFile = File(ctx.filesDir, "recording.csv")

    private val _metas = MutableStateFlow<List<RouteMeta>>(emptyList())
    val metas: StateFlow<List<RouteMeta>> = _metas

    /** Set once the first load is done; every later reload (a save, rename, delete or restore) refreshes the home-screen widgets. */
    private var loaded = false

    init { reload() }

    @Synchronized
    fun reload() {
        val list = dir.listFiles { f -> f.name.endsWith(".meta.json") }.orEmpty().mapNotNull { f ->
            runCatching {
                var m = metaFromJson(JSONObject(f.readText()))
                if (m.thumb.isEmpty() || m.v < FORMAT) {
                    // Saved by an older version: fill in the outline and best efforts once.
                    val pts = readPoints(m.id)
                    if (pts.size >= 2) {
                        m = m.copy(
                            thumb = Geo.simplify(pts),
                            bests = if (m.type == RouteType.RECORDED) Geo.bestEfforts(pts) else emptyMap(),
                            v = FORMAT,
                        )
                        writeSafely(f, metaToJson(m).toString())
                    }
                }
                m
            }.getOrNull()
        }
        _metas.value = list.sortedByDescending { it.createdAt }
        if (loaded) Widgets.updateAll(appContext) else loaded = true
    }

    @Synchronized
    fun save(
        name: String, type: RouteType, pts: List<Pt>, durationS: Long,
        distanceM: Double = Geo.length(pts),
        ascentM: Double = Geo.ascent(pts),
        createdAt: Long = System.currentTimeMillis(),
        maxSpeedMs: Double = 0.0,
        elapsedS: Long = 0,
        stops: Int = 0,
        laps: List<LapMark> = emptyList(),
    ): String {
        val id = UUID.randomUUID().toString()
        val meta = RouteMeta(
            id, name.ifBlank { "Untitled" }, type, createdAt, distanceM, ascentM, durationS,
            pts.firstOrNull()?.lat ?: 0.0, pts.firstOrNull()?.lon ?: 0.0,
            maxSpeedMs, elapsedS, Geo.simplify(pts), stops,
            if (type == RouteType.RECORDED) Geo.bestEfforts(pts) else emptyMap(), FORMAT,
            laps = laps,
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

    /** Save what the rider filled in after the ride. */
    @Synchronized
    fun updateDetails(id: String, name: String, notes: String, feeling: Int, tags: List<String>) {
        val meta = _metas.value.firstOrNull { it.id == id } ?: return
        val updated = meta.copy(
            name = name.ifBlank { meta.name }, notes = notes.trim(), feeling = feeling.coerceIn(0, 5),
            tags = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        )
        writeSafely(File(dir, "$id.meta.json"), metaToJson(updated).toString())
        reload()
    }

    @Synchronized
    fun delete(id: String) {
        File(dir, "$id.meta.json").delete()
        File(dir, "$id.pts.json").delete()
        File(dir, "$id.hr.json").delete()
        mediaDir(id).deleteRecursively()
        reload()
    }

    // --- Heart rate for a ride, from Health Connect: [epoch millis, bpm] pairs in <id>.hr.json ---

    fun heartRate(id: String): List<Pair<Long, Int>> = runCatching {
        val a = JSONArray(File(dir, "$id.hr.json").readText())
        (0 until a.length()).map { i -> a.getJSONArray(i).let { it.getLong(0) to it.getInt(1) } }
    }.getOrDefault(emptyList())

    @Synchronized
    fun saveHeartRate(id: String, samples: List<Pair<Long, Int>>) {
        val meta = _metas.value.firstOrNull { it.id == id } ?: return
        if (samples.isEmpty()) return
        val a = JSONArray()
        samples.forEach { a.put(JSONArray().put(it.first).put(it.second)) }
        writeSafely(File(dir, "$id.hr.json"), a.toString())
        val updated = meta.copy(avgHr = samples.map { it.second }.average().toInt(), maxHr = samples.maxOf { it.second })
        writeSafely(File(dir, "$id.meta.json"), metaToJson(updated).toString())
        reload()
    }

    // --- Segments: one JSON file each in segments/, holding the road and the efforts found so far ---

    private val segDir = File(dir.parentFile, "segments").apply { mkdirs() }
    private val _segTick = MutableStateFlow(0)
    val segTick: StateFlow<Int> = _segTick

    class SegmentFile(val seg: Segment, val efforts: List<Effort>, val checked: Set<String>)

    fun segments(): List<SegmentFile> = segDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
        runCatching {
            val o = JSONObject(f.readText())
            val p = o.getJSONArray("pts")
            val pts = (0 until p.length()).map { i -> p.getJSONArray(i).let { Pt(it.getDouble(0), it.getDouble(1)) } }
            val e = o.optJSONArray("efforts") ?: JSONArray()
            val c = o.optJSONArray("checked") ?: JSONArray()
            SegmentFile(
                Segment(o.getString("id"), o.getString("name"), pts, o.getDouble("lengthM"), o.optLong("createdAt")),
                (0 until e.length()).map { i -> e.getJSONArray(i).let { Effort(it.getString(0), it.getLong(1), it.getLong(2)) } },
                (0 until c.length()).map { c.getString(it) }.toSet(),
            )
        }.getOrNull()
    }.sortedByDescending { it.seg.createdAt }

    private fun writeSegment(sf: SegmentFile) {
        val o = JSONObject().put("id", sf.seg.id).put("name", sf.seg.name).put("lengthM", sf.seg.lengthM)
            .put("createdAt", sf.seg.createdAt)
            .put("pts", JSONArray().also { a -> sf.seg.pts.forEach { a.put(JSONArray().put(it.lat).put(it.lon)) } })
            .put("efforts", JSONArray().also { a -> sf.efforts.forEach { a.put(JSONArray().put(it.rideId).put(it.startT).put(it.seconds)) } })
            .put("checked", JSONArray(sf.checked))
        writeSafely(File(segDir, "${sf.seg.id}.json"), o.toString())
    }

    @Synchronized
    fun addSegment(name: String, pts: List<Pt>): String {
        val id = UUID.randomUUID().toString()
        val slim = pts.map { Pt(it.lat, it.lon) }
        writeSegment(SegmentFile(Segment(id, name.ifBlank { "Segment" }, slim, Geo.length(slim), System.currentTimeMillis()), emptyList(), emptySet()))
        _segTick.value++
        return id
    }

    @Synchronized
    fun deleteSegment(id: String) {
        File(segDir, "$id.json").delete()
        _segTick.value++
    }

    /**
     * Look through any rides not yet checked against each segment and record the passes found.
     * Safe to call often; each ride is only read once per segment. Run it off the main thread.
     */
    @Synchronized
    fun refreshSegments(): List<SegmentFile> {
        val rides = _metas.value.filter { it.type == RouteType.RECORDED }
        val ids = rides.map { it.id }.toSet()
        var changed = false
        val cache = HashMap<String, List<Pt>>()
        val out = segments().map { sf ->
            val todo = rides.filter { it.id !in sf.checked }
            // Efforts from rides that have since been deleted are dropped.
            val kept = sf.efforts.filter { it.rideId in ids }
            if (todo.isEmpty() && kept.size == sf.efforts.size) return@map sf
            val found = ArrayList(kept)
            for (m in todo) {
                val pts = cache.getOrPut(m.id) { readPoints(m.id) }
                if (pts.size < 2 || pts.first().t <= 0) continue
                val cum = Geo.cumulative(pts)
                for ((a, b) in Geo.matchSegment(pts, cum, sf.seg.pts, sf.seg.lengthM)) {
                    val secs = (pts[b].t - pts[a].t) / 1000
                    if (secs > 0) found.add(Effort(m.id, pts[a].t, secs))
                }
            }
            changed = true
            SegmentFile(sf.seg, found, sf.checked.filter { it in ids }.toSet() + todo.map { it.id }).also { writeSegment(it) }
        }
        if (changed) _segTick.value++
        return out
    }

    // Photos and videos attached to a ride live in media/<ride id>/.
    private val mediaRoot = File(dir.parentFile, "media")
    private val _mediaTick = MutableStateFlow(0)
    /** Changes whenever any ride's media changes, so lists can refresh. */
    val mediaTick: StateFlow<Int> = _mediaTick

    fun mediaDir(id: String) = File(mediaRoot, id)

    fun media(id: String): List<File> =
        mediaDir(id).listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }?.sortedBy { it.name } ?: emptyList()

    /** Copy a picked photo or video into the ride's folder. */
    fun addMedia(id: String, resolver: android.content.ContentResolver, uri: android.net.Uri): Boolean = runCatching {
        val video = resolver.getType(uri)?.startsWith("video") == true
        val d = mediaDir(id).apply { mkdirs() }
        val target = File(d, "${System.currentTimeMillis()}_${(0..999).random()}." + if (video) "mp4" else "jpg")
        resolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        _mediaTick.value++
        true
    }.getOrDefault(false)

    fun removeMedia(file: File) {
        file.delete()
        _mediaTick.value++
    }

    /**
     * Everything the app holds, as one zip: the ride and route files, attached photos and
     * videos, and a GPX copy of every route that other apps can open.
     */
    fun exportAll(out: java.io.OutputStream, extras: Map<String, String> = emptyMap()): Int {
        var count = 0
        java.util.zip.ZipOutputStream(out).use { zip ->
            fun add(name: String, bytes: ByteArray) {
                zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            // Small things kept outside the ride files, such as the bike care list.
            extras.forEach { (name, text) -> add("settings/$name", text.toByteArray()) }
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.forEach { add("routes/${it.name}", it.readBytes()) }
            segDir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.forEach { add("segments/${it.name}", it.readBytes()) }
            mediaRoot.listFiles()?.filter { it.isDirectory }?.forEach { d ->
                d.listFiles()?.filter { it.isFile }?.forEach { f ->
                    zip.putNextEntry(java.util.zip.ZipEntry("media/${d.name}/${f.name}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            _metas.value.forEach { m ->
                load(m.id)?.let { r ->
                    val safe = m.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "route" }
                    add("gpx/$safe-${m.id.take(6)}.gpx", Gpx.write(r).toByteArray())
                    count++
                }
            }
        }
        return count
    }

    /** Bring back rides from a backup zip made by [exportAll]. Existing rides are kept. Returns how many files were restored. */
    fun importAll(input: java.io.InputStream, onExtra: (name: String, text: String) -> Unit = { _, _ -> }): Int {
        val root = dir.parentFile ?: return 0
        val base = root.canonicalPath + File.separator
        var n = 0
        java.util.zip.ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory && e.name.startsWith("settings/") && e.size < 1_000_000) {
                    val text = zip.readBytes().toString(Charsets.UTF_8)
                    runCatching { onExtra(e.name.removePrefix("settings/"), text) }
                    n++
                    zip.closeEntry()
                    continue
                }
                val wanted = e.name.startsWith("routes/") || e.name.startsWith("media/") || e.name.startsWith("segments/")
                val target = File(root, e.name)
                // Only files that land inside the app's own folder are accepted.
                if (!e.isDirectory && wanted && target.canonicalPath.startsWith(base)) {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                    n++
                }
                zip.closeEntry()
            }
        }
        reload()
        _mediaTick.value++
        _segTick.value++
        return n
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
        .put("stops", m.stops).put("v", m.v)
        .put("notes", m.notes).put("feeling", m.feeling).put("tags", JSONArray(m.tags))
        .put("avgHr", m.avgHr).put("maxHr", m.maxHr)
        .put("laps", JSONArray().also { a -> m.laps.forEach { a.put(JSONArray().put(it.distanceM).put(it.movingMs).put(it.elapsedMs)) } })
        .put("bests", JSONObject().also { o -> m.bests.forEach { (k, s) -> o.put(k.toString(), s) } })

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
        o.optInt("stops", 0),
        runCatching {
            o.optJSONObject("bests")?.let { b -> b.keys().asSequence().associate { it.toInt() to b.getLong(it) } }
        }.getOrNull() ?: emptyMap(),
        o.optInt("v", 0),
        o.optString("notes", ""), o.optInt("feeling", 0),
        o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList(),
        runCatching {
            o.optJSONArray("laps")?.let { a ->
                (0 until a.length()).map { i -> a.getJSONArray(i).let { LapMark(it.getDouble(0), it.getLong(1), it.getLong(2)) } }
            }
        }.getOrNull() ?: emptyList(),
        o.optInt("avgHr", 0), o.optInt("maxHr", 0),
    )

    companion object {
        private const val FORMAT = 2
        @Volatile private var inst: RouteStore? = null
        /** For tests only: forget the shared instance so the next get() starts clean. */
        internal fun resetForTests() { inst = null }

        fun get(ctx: Context): RouteStore =
            inst ?: synchronized(this) { inst ?: RouteStore(ctx.applicationContext).also { inst = it } }
    }
}

class Prefs(ctx: Context) {
    private val appContext = ctx.applicationContext
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
        set(v) { sp.edit().putInt("weeklyGoalKm", v).apply(); Widgets.updateAll(appContext) }
    /** Whether the first-launch setup has been completed or skipped. */
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()
    /** Whether ride pages open with the detailed numbers showing. */
    var advancedStats: Boolean
        get() = sp.getBoolean("advancedStats", false)
        set(v) = sp.edit().putBoolean("advancedStats", v).apply()
    /** Rider weight in kilograms, for the calorie estimate. 0 means not set. */
    var weightKg: Int
        get() = sp.getInt("weightKg", 0)
        set(v) = sp.edit().putInt("weightKg", v).apply()
    var turnCues: Boolean
        get() = sp.getBoolean("turnCues", true)
        set(v) = sp.edit().putBoolean("turnCues", v).apply()
    /** Speak an update every this many kilometres. */
    var voiceEveryKm: Int
        get() = sp.getInt("voiceEveryKm", 1)
        set(v) = sp.edit().putInt("voiceEveryKm", v).apply()
    /** How quickly auto-pause kicks in: 0 relaxed, 1 normal, 2 quick. */
    var autoPauseLevel: Int
        get() = sp.getInt("autoPauseLevel", 1)
        set(v) = sp.edit().putInt("autoPauseLevel", v).apply()
    /** Mark a lap automatically every this many kilometres; 0 means off. */
    var autoLapKm: Int
        get() = sp.getInt("autoLapKm", 0)
        set(v) = sp.edit().putInt("autoLapKm", v).apply()
    /** The pages of the big-number ride screen: field keys joined by commas, pages by "|". */
    var ridePages: String
        get() = sp.getString("ridePages", "speed,distance,moving|avg,max,climb,elapsed|lapDistance,lapTime,lapAvg,clock")!!
        set(v) = sp.edit().putString("ridePages", v).apply()
    /** The rider's own interval workout: warm-up min, repeats, work s, rest s, cool-down min. */
    var customWorkout: String
        get() = sp.getString("customWorkout", "10,4,180,120,10")!!
        set(v) = sp.edit().putString("customWorkout", v).apply()
    /** Bike jobs being tracked: interval, when last done and a short history. Reads the old "name|km|at;" format too. */
    var care: List<CareItem>
        get() = CareItem.parse(sp.getString("care", null))
        set(v) = sp.edit().putString("care", CareItem.encode(v)).apply()
    var lastLoc: Pt?
        get() = if (sp.contains("lastLat")) Pt(sp.getFloat("lastLat", 0f).toDouble(), sp.getFloat("lastLon", 0f).toDouble()) else null
        set(v) {
            if (v != null) sp.edit().putFloat("lastLat", v.lat.toFloat()).putFloat("lastLon", v.lon.toFloat()).apply()
        }
}

/** One time a job was done, with an optional note. */
data class CareDone(val at: Long, val note: String = "")

/**
 * A bike job that comes round every [everyKm] kilometres (0 for none) and/or every [everyDays]
 * days (0 for none). [resetAt] is when it was last done; 0 means never, so every ride counts.
 * [resetAt] always equals the newest entry in [history] when there is one.
 */
data class CareItem(
    val name: String,
    val everyKm: Int,
    val resetAt: Long,
    val everyDays: Int = 0,
    val note: String = "",
    val history: List<CareDone> = emptyList(),
    val id: String = java.util.UUID.randomUUID().toString(),
) {
    /** Record the job as done at [at]; only the newest [MAX_HISTORY] entries are kept. */
    fun markDone(at: Long, note: String = ""): CareItem {
        val h = (history + CareDone(at, note.trim())).sortedByDescending { it.at }.take(MAX_HISTORY)
        return copy(history = h, resetAt = h.first().at)
    }

    /** Forget one history entry; the last-done date falls back to the next newest one. */
    fun removeDone(entry: CareDone): CareItem {
        val h = history - entry
        return copy(history = h, resetAt = h.maxOfOrNull { it.at } ?: 0L)
    }

    companion object {
        const val MAX_HISTORY = 20

        fun defaults() = listOf(
            CareItem("Lube the chain", 300, 0), CareItem("Check tyres and brake pads", 1000, 0),
            CareItem("Full service", 2500, 0), CareItem("Replace chain", 4000, 0),
        )

        /** Reads either the JSON written by this version or the old `name|everyKm|resetAt` rows joined by `;`. */
        fun parse(raw: String?): List<CareItem> {
            if (raw == null) return defaults()
            if (raw.trimStart().startsWith("[")) {
                runCatching {
                    val arr = org.json.JSONArray(raw)
                    return (0 until arr.length()).mapNotNull { i ->
                        val o = arr.getJSONObject(i)
                        val name = o.optString("name")
                        if (name.isBlank()) return@mapNotNull null
                        val hist = o.optJSONArray("history")
                        val history = if (hist == null) emptyList() else (0 until hist.length()).map {
                            val h = hist.getJSONObject(it)
                            CareDone(h.optLong("at"), h.optString("note"))
                        }
                        CareItem(
                            name, o.optInt("everyKm"), o.optLong("resetAt"), o.optInt("everyDays"), o.optString("note"),
                            history, o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                        )
                    }
                }
            }
            return raw.split(';').mapNotNull { row ->
                val p = row.split('|')
                val km = p.getOrNull(1)?.toIntOrNull()
                val at = p.getOrNull(2)?.toLongOrNull()
                if (p[0].isBlank() || km == null || at == null) null
                else CareItem(p[0], km, at, history = if (at > 0) listOf(CareDone(at)) else emptyList())
            }
        }

        fun encode(items: List<CareItem>): String {
            val arr = org.json.JSONArray()
            items.forEach { c ->
                arr.put(org.json.JSONObject().apply {
                    put("id", c.id); put("name", c.name); put("everyKm", c.everyKm); put("everyDays", c.everyDays)
                    put("resetAt", c.resetAt); put("note", c.note)
                    put("history", org.json.JSONArray().apply {
                        c.history.forEach { h -> put(org.json.JSONObject().put("at", h.at).put("note", h.note)) }
                    })
                })
            }
            return arr.toString()
        }
    }
}
