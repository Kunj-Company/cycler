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

    /** Set once the first load is done; every later change refreshes the home-screen widgets. */
    private var loaded = false

    private val _isLoaded = MutableStateFlow(false)
    /** False until the ride index has been read from disk (it is read on a background thread). */
    val isLoaded: StateFlow<Boolean> = _isLoaded
    private val loadedLatch = java.util.concurrent.CountDownLatch(1)

    /** Set while rides with raw GPS heights are being converted; see [convertHeightsLater]. Must exist before the first load runs. */
    private val converting = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        if (syncLoad) {
            publishIndex(loadIndex())
        } else {
            Thread({ publishIndex(loadIndex()); Widgets.updateAll(appContext) }, "ride-index").start()
        }
    }

    /** Blocks until the ride index has been loaded (for tests and background callers). */
    fun awaitLoaded() { loadedLatch.await() }

    private fun publishIndex(list: List<RouteMeta>) {
        _metas.value = list
        loaded = true
        _isLoaded.value = true
        loadedLatch.countDown()
        convertHeightsLater()
    }

    /** Reads every meta file. Safe to call from any thread; takes no lock. */
    private fun loadIndex(): List<RouteMeta> {
        val files = dir.listFiles { f -> f.name.endsWith(".meta.json") }.orEmpty()
        val list = files.mapNotNull { f ->
            runCatching {
                var m = metaFromJson(JSONObject(f.readText()))
                // A file whose id isn't a plain id could point outside the app's folders; ignore it.
                if (!isSafeId(m.id)) return@runCatching null
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
            }.getOrNull() ?: rebuildMeta(f)
        }
        return list.sortedByDescending { it.createdAt }
    }

    /**
     * A meta file that is empty or unreadable (for instance after power loss) must not hide the ride
     * while its points are still fine: rebuild the summary from the points.
     */
    private fun rebuildMeta(metaFile: File): RouteMeta? = runCatching {
        val id = metaFile.name.removeSuffix(".meta.json")
        if (!isSafeId(id)) return@runCatching null
        val pts = readPoints(id)
        if (pts.size < 2) return@runCatching null
        val timed = pts.first().t > 0
        val first = if (timed) pts.first().t else File(dir, "$id.pts.json").lastModified()
        val durS = if (timed && pts.last().t > pts.first().t) (pts.last().t - pts.first().t) / 1000 else 0L
        val m = RouteMeta(
            id, "Recovered ride", RouteType.RECORDED, first, Geo.length(pts), Geo.ascent(pts), durS,
            pts.first().lat, pts.first().lon, 0.0, durS, Geo.simplify(pts), 0,
            Geo.bestEfforts(pts), FORMAT, alt = Altitude.GPS,
        )
        writeSafely(metaFile, metaToJson(m).toString())
        m
    }.getOrNull()

    /** Re-reads everything from disk, replacing the in-memory index. */
    @Synchronized
    fun reload() {
        _metas.value = loadIndex()
        if (loaded) Widgets.updateAll(appContext) else loaded = true
        _isLoaded.value = true
        loadedLatch.countDown()
        convertHeightsLater()
    }

    /** Replaces one ride in the in-memory list (no disk reads) and refreshes the widgets. */
    private fun patch(meta: RouteMeta) {
        _metas.value = (_metas.value.filter { it.id != meta.id } + meta).sortedByDescending { it.createdAt }
        Widgets.updateAll(appContext)
    }

    /**
     * Rides recorded with raw GPS heights get heights above sea level, on a background thread
     * (the geoid lookup reads from disk). Android 14+ uses the system model; older phones use the
     * bundled coarse EGM96 grid (see [GeoidModel]).
     */
    private fun convertHeightsLater() {
        if (_metas.value.none { it.alt == Altitude.GPS }) return
        if (!converting.compareAndSet(false, true)) return
        Thread {
            try {
                val geoidAt = GeoidSource.open(appContext) ?: return@Thread
                var changed = false
                for (m in _metas.value.filter { it.alt == Altitude.GPS }) {
                    if (convertHeights(m.id, geoidAt)) changed = true
                }
                if (changed) reload()
            } finally {
                converting.set(false)
            }
        }.start()
    }

    /**
     * Converts one ride. The points file and the meta file cannot be replaced together, so before
     * touching either, a small `<id>.conv` note records a fingerprint of the raw points and of the
     * converted points. If the app is killed between the two writes, the next run sees that the
     * points already match the converted fingerprint and only finishes the meta, so heights are
     * never converted twice. (The note has no .json ending, so it is not part of backups.)
     */
    internal fun convertHeights(id: String, geoidAt: (Double, Double) -> Double?): Boolean {
        if (!isSafeId(id)) return false
        val ptsFile = File(dir, "$id.pts.json")
        val note = File(dir, "$id.conv")
        val (rawText, pts) = synchronized(this) {
            val t = runCatching { ptsFile.readText() }.getOrNull()
            t to readPoints(id)
        }
        if (rawText == null || pts.isEmpty()) return false
        val rawPrint = fingerprint(rawText)
        val saved = runCatching { note.readText().trim().split(' ') }.getOrNull()
        if (saved != null && saved.size == 2 && saved[1] == rawPrint) {
            // Interrupted after the points were converted: only the meta is left to update.
            return synchronized(this) {
                val now = _metas.value.firstOrNull { it.id == id }
                if (now != null && now.alt == Altitude.GPS) {
                    writeSafely(File(dir, "$id.meta.json"), metaToJson(now.copy(alt = Altitude.MSL)).toString())
                }
                note.delete()
                now != null
            }
        }
        val msl = Altitude.toMsl(pts, geoidAt) ?: return false
        val mslText = pointsToJson(msl)
        synchronized(this) {
            // Skip a ride that was deleted or changed while converting.
            val now = _metas.value.firstOrNull { it.id == id } ?: return false
            if (now.alt != Altitude.GPS) return false
            writeSafely(note, rawPrint + " " + fingerprint(mslText))
            writeSafely(ptsFile, mslText)
            writeSafely(File(dir, "$id.meta.json"), metaToJson(now.copy(alt = Altitude.MSL)).toString())
            note.delete()
        }
        return true
    }

    private fun fingerprint(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(12)
            .joinToString("") { "%02x".format(it) }

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
        alt: String = Altitude.MSL,
    ): String {
        val id = UUID.randomUUID().toString()
        val meta = RouteMeta(
            id, name.ifBlank { "Untitled" }, type, createdAt, distanceM, ascentM, durationS,
            pts.firstOrNull()?.lat ?: 0.0, pts.firstOrNull()?.lon ?: 0.0,
            maxSpeedMs, elapsedS, Geo.simplify(pts), stops,
            if (type == RouteType.RECORDED) Geo.bestEfforts(pts) else emptyMap(), FORMAT,
            laps = laps,
            alt = alt,
        )
        writeSafely(File(dir, "$id.pts.json"), pointsToJson(pts))
        writeSafely(File(dir, "$id.meta.json"), metaToJson(meta).toString())
        awaitLoaded()
        patch(meta)
        convertHeightsLater()
        return id
    }

    fun load(id: String): Route? {
        awaitLoaded()
        val meta = _metas.value.firstOrNull { it.id == id } ?: return null
        val pts = readPoints(id)
        return if (pts.isEmpty()) null else Route(meta, pts)
    }

    private fun pointsToJson(pts: List<Pt>): String {
        val arr = JSONArray()
        for (p in pts) {
            val a = JSONArray().put(p.lat).put(p.lon)
            a.put(if (p.ele.isNaN()) JSONObject.NULL else p.ele)
            a.put(p.t)
            if (p.brk) a.put(1)
            arr.put(a)
        }
        return arr.toString()
    }

    private fun readPoints(id: String): List<Pt> {
        if (!isSafeId(id)) return emptyList()
        val f = File(dir, "$id.pts.json")
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            val pts = ArrayList<Pt>(arr.length())
            for (i in 0 until arr.length()) {
                val a = arr.getJSONArray(i)
                val lat = a.getDouble(0)
                val lon = a.getDouble(1)
                // A stored point off the globe would crash the map; drop it.
                if (!Gpx.validCoord(lat, lon)) continue
                pts.add(Pt(lat, lon, a.optDouble(2, Double.NaN), a.optLong(3, 0L), a.optInt(4, 0) == 1))
            }
            pts
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun rename(id: String, name: String) {
        awaitLoaded()
        val meta = _metas.value.firstOrNull { it.id == id } ?: return
        val updated = meta.copy(name = name.ifBlank { meta.name })
        writeSafely(File(dir, "$id.meta.json"), metaToJson(updated).toString())
        patch(updated)
    }

    /** Save what the rider filled in after the ride. */
    @Synchronized
    fun updateDetails(id: String, name: String, notes: String, feeling: Int, tags: List<String>) {
        awaitLoaded()
        val meta = _metas.value.firstOrNull { it.id == id } ?: return
        val updated = meta.copy(
            name = name.ifBlank { meta.name }, notes = notes.trim(), feeling = feeling.coerceIn(0, 5),
            tags = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        )
        writeSafely(File(dir, "$id.meta.json"), metaToJson(updated).toString())
        patch(updated)
    }

    /** Say which bike a ride was on; null clears it (the ride then counts for the home bike). */
    @Synchronized
    fun setBike(id: String, bikeId: String?) {
        awaitLoaded()
        val meta = _metas.value.firstOrNull { it.id == id } ?: return
        if (meta.bikeId == bikeId) return
        val updated = meta.copy(bikeId = bikeId)
        writeSafely(File(dir, "$id.meta.json"), metaToJson(updated).toString())
        patch(updated)
    }

    /** Move every ride tagged with bike [from] to bike [to]; used only after the rider confirmed deleting a bike. Returns how many moved. */
    @Synchronized
    fun reassignBike(from: String, to: String): Int {
        awaitLoaded()
        val moving = _metas.value.filter { it.bikeId == from }
        moving.forEach { m ->
            val updated = m.copy(bikeId = to)
            writeSafely(File(dir, "${m.id}.meta.json"), metaToJson(updated).toString())
            patch(updated)
        }
        return moving.size
    }

    @Synchronized
    fun delete(id: String) {
        if (!isSafeId(id)) return
        File(dir, "$id.meta.json").delete()
        File(dir, "$id.pts.json").delete()
        File(dir, "$id.hr.json").delete()
        mediaDir(id).deleteRecursively()
        awaitLoaded()
        _metas.value = _metas.value.filter { it.id != id }
        Widgets.updateAll(appContext)
    }

    // --- Heart rate for a ride, from Health Connect: [epoch millis, bpm] pairs in <id>.hr.json ---

    fun heartRate(id: String): List<Pair<Long, Int>> = if (!isSafeId(id)) emptyList() else runCatching {
        val a = JSONArray(File(dir, "$id.hr.json").readText())
        (0 until a.length()).map { i -> a.getJSONArray(i).let { it.getLong(0) to it.getInt(1) } }
    }.getOrDefault(emptyList())

    @Synchronized
    fun saveHeartRate(id: String, samples: List<Pair<Long, Int>>) {
        if (!isSafeId(id)) return
        awaitLoaded()
        val meta = _metas.value.firstOrNull { it.id == id } ?: return
        if (samples.isEmpty()) return
        val a = JSONArray()
        samples.forEach { a.put(JSONArray().put(it.first).put(it.second)) }
        writeSafely(File(dir, "$id.hr.json"), a.toString())
        val updated = meta.copy(avgHr = samples.map { it.second }.average().toInt(), maxHr = samples.maxOf { it.second })
        writeSafely(File(dir, "$id.meta.json"), metaToJson(updated).toString())
        patch(updated)
    }

    // --- Segments: one JSON file each in segments/, holding the road and the efforts found so far ---

    private val segDir = File(dir.parentFile, "segments").apply { mkdirs() }
    private val _segTick = MutableStateFlow(0)
    val segTick: StateFlow<Int> = _segTick

    class SegmentFile(val seg: Segment, val efforts: List<Effort>, val checked: Set<String>)

    fun segments(): List<SegmentFile> = segDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
        runCatching {
            val o = JSONObject(f.readText())
            if (!isSafeId(o.getString("id"))) return@runCatching null
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
        if (!isSafeId(id)) return
        File(segDir, "$id.json").delete()
        _segTick.value++
    }

    /**
     * Look through any rides not yet checked against each segment and record the passes found.
     * Safe to call often; each ride is only read once per segment. Run it off the main thread.
     */
    @Synchronized
    fun refreshSegments(): List<SegmentFile> {
        awaitLoaded()
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

    /** The folder holding a ride's media. An id that isn't a plain id gets a folder that never exists. */
    fun mediaDir(id: String) = File(mediaRoot, if (isSafeId(id)) id else ".invalid")

    fun media(id: String): List<File> =
        mediaDir(id).listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }?.sortedBy { it.name } ?: emptyList()

    /** Copy a picked photo or video into the ride's folder. */
    fun addMedia(id: String, resolver: android.content.ContentResolver, uri: android.net.Uri): Boolean = isSafeId(id) && runCatching {
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
            awaitLoaded()
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

    /**
     * Bring back rides from a backup zip made by [exportAll]. Existing rides are kept. Returns how many
     * files were restored. Only entries with plain ids and names, inside their size limits, are accepted.
     */
    fun importAll(input: java.io.InputStream, onExtra: (name: String, text: String) -> Unit = { _, _ -> }): Int {
        val root = dir.parentFile ?: return 0
        val base = root.canonicalPath + File.separator
        var n = 0
        java.util.zip.ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) { zip.closeEntry(); continue }
                if (e.name.startsWith("settings/")) {
                    val bytes = readLimited(zip, MAX_SETTINGS_BYTES)
                    if (bytes != null) {
                        runCatching { onExtra(e.name.removePrefix("settings/"), bytes.toString(Charsets.UTF_8)) }
                        n++
                    }
                    zip.closeEntry()
                    continue
                }
                val limit = entryLimit(e.name)
                val target = File(root, e.name)
                // Only files that land inside the app's own folder are accepted.
                if (limit != null && target.canonicalPath.startsWith(base)) {
                    target.parentFile?.mkdirs()
                    if (copyLimited(zip, target, limit)) n++
                }
                zip.closeEntry()
            }
        }
        reload()
        _mediaTick.value++
        _segTick.value++
        return n
    }

    /** Reads at most [limit] bytes; null if the entry is bigger (the rest is left unread). */
    private fun readLimited(input: java.io.InputStream, limit: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            total += r
            if (total > limit) return null
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    /** Copies to [target] via a temporary file, counting bytes as they go; false (nothing kept) past [limit]. */
    private fun copyLimited(input: java.io.InputStream, target: File, limit: Long): Boolean {
        val tmp = File(target.parentFile, target.name + ".tmp")
        var ok = false
        try {
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) { ok = true; break }
                    total += r
                    if (total > limit) break
                    out.write(buf, 0, r)
                }
            }
            if (ok && !tmp.renameTo(target)) { target.delete(); ok = tmp.renameTo(target) }
        } catch (e: java.io.IOException) {
            ok = false
        }
        if (!ok) tmp.delete()
        return ok
    }

    /** What the recording file holds: the points plus the ride state that was written alongside them. */
    class Recording(
        val pts: List<Pt>, val movingMs: Long, val maxSpeedMs: Double, val stops: Int, val elapsedMs: Long,
        val laps: List<LapMark>, val paused: Boolean,
    )

    /**
     * Reads recording.csv. Point lines are `lat,lon,ele,t` and may carry `,brk,movingMs,maxSpeedMs,stops,elapsedMs`;
     * `#L`, `#P` and `#R` lines record laps, pauses and resumes. Old four-column files and half-written
     * lines are fine. Returns null when there is no file.
     */
    fun readRecording(): Recording? {
        if (!recordingFile.exists()) return null
        // Read line by line: a half-written last line must not cost the whole ride.
        val lines = runCatching { recordingFile.readLines() }.getOrDefault(emptyList())
        val pts = ArrayList<Pt>()
        val laps = ArrayList<LapMark>()
        var moving = -1L; var maxSpeed = 0.0; var stops = 0; var elapsed = -1L; var paused = false
        for (line in lines) {
            val p = line.split(',')
            if (line.startsWith("#")) {
                when (p[0]) {
                    "#L" -> {
                        val d = p.getOrNull(1)?.toDoubleOrNull(); val m = p.getOrNull(2)?.toLongOrNull(); val e = p.getOrNull(3)?.toLongOrNull()
                        if (d != null && m != null && e != null) laps.add(LapMark(d, m, e))
                    }
                    "#P" -> {
                        val m = p.getOrNull(2)?.toLongOrNull(); val e = p.getOrNull(3)?.toLongOrNull()
                        if (m != null && e != null) { moving = m; elapsed = e; paused = true }
                    }
                    "#R" -> paused = false
                }
                continue
            }
            val lat = p.getOrNull(0)?.toDoubleOrNull()
            val lon = p.getOrNull(1)?.toDoubleOrNull()
            val t = p.getOrNull(3)?.trim()?.toLongOrNull()
            if (lat == null || lon == null || t == null) continue
            val brk = p.getOrNull(4)?.trim() == "1"
            pts.add(Pt(lat, lon, p.getOrNull(2)?.toDoubleOrNull() ?: Double.NaN, t, brk))
            paused = false
            val m = p.getOrNull(5)?.toLongOrNull(); val mx = p.getOrNull(6)?.toDoubleOrNull()
            val st = p.getOrNull(7)?.toIntOrNull(); val e = p.getOrNull(8)?.trim()?.toLongOrNull()
            if (m != null && mx != null && st != null && e != null) { moving = m; maxSpeed = mx; stops = st; elapsed = e }
        }
        if (moving < 0) {
            // Old format: no state was written. Work moving time out from the points; a long stop is not riding.
            moving = 0
            for (i in 1 until pts.size) {
                val b = pts[i]
                if (b.brk) continue
                val dt = b.t - pts[i - 1].t
                if (dt > 0 && dt <= 10_000) { if (dist(pts[i - 1], b) / (dt / 1000.0) > 0.7 && !GpsJump.isJump(dist(pts[i - 1], b), dt)) moving += dt }
                else moving += GpsJump.gapMovingMs(dist(pts[i - 1], b), dt)
            }
            elapsed = moving
        }
        return Recording(pts, moving, maxSpeed, stops, elapsed, laps, paused)
    }

    private fun dist(a: Pt, b: Pt) = Geo.dist(a, b)

    /** Outcome of [recoverUnfinishedResult]. */
    sealed class Recovery {
        object None : Recovery()
        /** Saved as [id]; the recording was cut off at [interruptedAt] (epoch ms). */
        class Saved(val id: String, val interruptedAt: Long) : Recovery()
        /** The ride could not be written (storage full?); recording.csv was kept for another try. */
        object Failed : Recovery()
    }

    /** If the app died mid-ride, turn the leftover points into a saved ride. Never throws. */
    fun recoverUnfinished(): String? = (recoverUnfinishedResult() as? Recovery.Saved)?.id

    @Synchronized
    fun recoverUnfinishedResult(): Recovery {
        try {
            val rec = readRecording() ?: return Recovery.None
            val pts = rec.pts
            if (pts.size < 2) {
                recordingFile.delete()
                return Recovery.None
            }
            val distance = Geo.length(pts)
            val moving = rec.movingMs / 1000
            val elapsed = maxOf(rec.elapsedMs / 1000, moving)
            // As when a ride is finished: what was ridden after the last mark is the final lap.
            val last = rec.laps.lastOrNull()
            val laps = if (rec.laps.isEmpty()) rec.laps
            else if (distance - last!!.distanceM > 20.0) rec.laps + LapMark(distance, rec.movingMs, rec.elapsedMs) else rec.laps
            val name = appContext.getString(
                com.cycler.app.R.string.rec_interrupted_name,
                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(pts.first().t)),
            )
            val id = save(
                name, RouteType.RECORDED, pts, moving,
                distanceM = distance,
                ascentM = Geo.ascent(pts, 4.0, smooth = true), createdAt = pts.first().t, elapsedS = elapsed,
                maxSpeedMs = rec.maxSpeedMs, stops = rec.stops, laps = laps,
                alt = Altitude.GPS,
            )
            // Only now is it safe to drop the raw recording.
            recordingFile.delete()
            com.cycler.app.analytics.UsageStats.rideFinished(
                appContext, distance, pts.first().lat, pts.first().lon, pts.last().lat, pts.last().lon,
            )
            com.cycler.app.analytics.UsageStats.track(appContext, com.cycler.app.analytics.Feature.RIDE_RECOVERED)
            return Recovery.Saved(id, pts.last().t)
        } catch (e: Exception) {
            // Keep recording.csv: the next launch tries again.
            return Recovery.Failed
        }
    }

    /** Write to a temporary file and swap it in, so a kill mid-write can't leave a broken file. */
    private fun writeSafely(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        // Flush to disk before the rename, so a power cut cannot leave an empty file under the real name.
        java.io.FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray())
            out.flush()
            out.fd.sync()
        }
        val moved = runCatching {
            java.nio.file.Files.move(
                tmp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }.isSuccess
        if (!moved && !tmp.renameTo(target)) {
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
        .put("alt", m.alt)
        .also { o -> m.bikeId?.let { o.put("bikeId", it) } }
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
    ).let { m ->
        val alt = o.optString("alt", "")
        m.copy(
            alt = if (alt == Altitude.GPS || alt == Altitude.MSL) alt else Altitude.legacy(m.type, m.elapsedS),
            bikeId = o.optString("bikeId", "").ifBlank { null },
        )
    }

    companion object {
        private const val FORMAT = 2
        /** Ride and segment ids are UUIDs; anything else must never become part of a path. */
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,64}")
        private val ROUTE_FILE = Regex("[A-Za-z0-9_-]{1,64}\\.(meta|pts|hr)\\.json")
        private val SEGMENT_FILE = Regex("[A-Za-z0-9_-]{1,64}\\.json")
        private val MEDIA_FILE = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}")
        internal const val MAX_SETTINGS_BYTES = 1_000_000L
        internal const val MAX_JSON_BYTES = 50L * 1024 * 1024
        internal const val MAX_MEDIA_BYTES = 1024L * 1024 * 1024

        fun isSafeId(id: String?): Boolean = id != null && SAFE_ID.matches(id)

        /** The size limit for a backup entry that may be restored, or null if its path isn't acceptable. */
        internal fun entryLimit(name: String): Long? {
            val parts = name.split('/')
            return when (parts.firstOrNull()) {
                "routes" -> if (parts.size == 2 && ROUTE_FILE.matches(parts[1])) MAX_JSON_BYTES else null
                "segments" -> if (parts.size == 2 && SEGMENT_FILE.matches(parts[1])) MAX_JSON_BYTES else null
                "media" -> if (parts.size == 3 && isSafeId(parts[1]) && MEDIA_FILE.matches(parts[2])) MAX_MEDIA_BYTES else null
                else -> null
            }
        }
        @Volatile private var inst: RouteStore? = null
        /** For tests only: forget the shared instance so the next get() starts clean. */
        internal fun resetForTests() { inst = null; syncLoad = true }
        /** Tests (which call [resetForTests]) load the index synchronously so metas are visible right away. */
        @Volatile private var syncLoad = false

        fun get(ctx: Context): RouteStore =
            inst ?: synchronized(this) { inst ?: RouteStore(ctx.applicationContext).also { inst = it } }
    }
}

/** When to show the quiet "buy me a coffee" card: after a few rides, then at most once every two weeks. */
object SupportPrompt {
    /** The donate section of the project website, which links on to Buy Me a Coffee. */
    const val SITE_URL = "https://cycler.kunj.company/"
    const val URL = SITE_URL + "#donate"
    const val MIN_RIDES = 3
    const val EVERY_MS = 14L * 24 * 60 * 60 * 1000

    fun due(rideCount: Int, lastAskedAt: Long, now: Long): Boolean =
        rideCount >= MIN_RIDES && (lastAskedAt <= 0L || now - lastAskedAt >= EVERY_MS || now < lastAskedAt)
}

class Prefs(ctx: Context) {
    private val appContext = ctx.applicationContext
    private val sp = ctx.getSharedPreferences("cycler", Context.MODE_PRIVATE)

    var profile: String
        get() = sp.getString("profile", "trekking")!!.takeIf { it in PROFILES } ?: "trekking"
        set(v) = sp.edit().putString("profile", v).apply()
    var mapStyle: String
        get() = sp.getString("mapStyle", "liberty")!!.takeIf { it in MAP_STYLES } ?: "liberty"
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
        get() = sp.getInt("weeklyGoalKm", 50).coerceAtLeast(1)
        set(v) { sp.edit().putInt("weeklyGoalKm", v).apply(); Widgets.updateAll(appContext) }
    /** Whether the first-launch setup has been completed or skipped. */
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()
    /** When the support card was last shown (epoch ms; 0 = never). Device state, not part of backups. */
    var supportAskedAt: Long
        get() = sp.getLong("supportAskedAt", 0L)
        set(v) = sp.edit().putLong("supportAskedAt", v).apply()
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
    /** Experimental: the rider's bikes. Stored like the care list, and backed up in its own file (bikes.json). */
    var bikes: BikeList
        get() = BikeList.parse(sp.getString("bikes", null))
        set(v) = sp.edit().putString("bikes", v.encode()).apply()
    /**
     * The rider's preferences as JSON, for backups. Only keys in [SPEC] are written: no last
     * location (device state) and no care list (it has its own file in the backup).
     */
    fun exportJson(): String {
        val o = JSONObject()
        for ((key, spec) in SPEC) {
            if (!sp.contains(key)) continue
            runCatching {
                when (spec.type) {
                    'b' -> o.put(key, sp.getBoolean(key, false))
                    'i' -> o.put(key, sp.getInt(key, 0))
                    else -> o.put(key, sp.getString(key, ""))
                }
            }
        }
        return o.toString()
    }

    /** Apply preferences from [exportJson]. Unknown keys, wrong types and out-of-range values are ignored. Returns how many were applied. */
    fun importJson(text: String): Int {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return 0
        val ed = sp.edit()
        var n = 0
        for ((key, spec) in SPEC) {
            val v = o.opt(key) ?: continue
            when (spec.type) {
                'b' -> if (v is Boolean) { ed.putBoolean(key, v); n++ }
                'i' -> if (v is Int && v in spec.min..spec.max) { ed.putInt(key, v); n++ }
                else -> if (v is String && v.isNotBlank() && v.length <= spec.max && (spec.allowed == null || v in spec.allowed)) {
                    ed.putString(key, v); n++
                }
            }
        }
        ed.apply()
        if (n > 0) Widgets.updateAll(appContext)
        return n
    }

    /** One restorable preference: type 'b'oolean, 'i'nt (min..max) or 's'tring (not blank, up to max characters, optionally one of fixed values). */
    internal class Spec(val type: Char, val min: Int = 0, val max: Int = 0, val allowed: Set<String>? = null)

    companion object {
        /** BRouter profile ids the app can ask for (see Net.kt). */
        internal val PROFILES = setOf("fastbike", "trekking", "gravel", "mtb")
        /** Map style keys the app knows (see MapStyles in CyclerMap.kt). */
        internal val MAP_STYLES = setOf("liberty", "bright", "positron", "dark", "cyclosm")

        /** The only keys a backup carries or restores; everything else in the file is device or transient state. */
        internal val SPEC: Map<String, Spec> = mapOf(
            "profile" to Spec('s', max = 64, allowed = PROFILES),
            "mapStyle" to Spec('s', max = 64, allowed = MAP_STYLES),
            "keepScreenOn" to Spec('b'),
            "themeMode" to Spec('s', max = 16, allowed = setOf("system", "light", "dark")),
            "autoPause" to Spec('b'), "autoPauseLevel" to Spec('i', 0, 2),
            "offRouteAlert" to Spec('b'),
            "voice" to Spec('b'), "voiceEveryKm" to Spec('i', 1, 100), "turnCues" to Spec('b'),
            "weeklyGoalKm" to Spec('i', 1, 100_000), "advancedStats" to Spec('b'),
            "weightKg" to Spec('i', 0, 1000), "autoLapKm" to Spec('i', 0, 1000),
            "ridePages" to Spec('s', max = 1000), "customWorkout" to Spec('s', max = 200),
            "onboarded" to Spec('b'),
        )
    }

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
    /** The bike this job belongs to; null (jobs from before bikes existed) means the home bike. */
    val bikeId: String? = null,
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
                            o.optString("bikeId", "").ifBlank { null },
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
                    c.bikeId?.let { put("bikeId", it) }
                    put("history", org.json.JSONArray().apply {
                        c.history.forEach { h -> put(org.json.JSONObject().put("at", h.at).put("note", h.note)) }
                    })
                })
            }
            return arr.toString()
        }
    }
}
