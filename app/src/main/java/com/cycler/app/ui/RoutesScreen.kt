package com.cycler.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.cycler.app.net.Overpass
import com.cycler.app.share.loadShareImage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cycler.app.MainVm
import com.cycler.app.Tab
import com.cycler.app.data.Difficulty
import com.cycler.app.data.Feeling
import com.cycler.app.data.Geo
import com.cycler.app.data.Gpx
import com.cycler.app.data.Pt
import com.cycler.app.data.Route
import com.cycler.app.data.RouteType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One line of a route list: outline, name, numbers. */
@Composable
fun RouteRow(
    thumb: List<Pt>, recorded: Boolean, title: String, stats: String, note: String?,
    difficulty: Difficulty, onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RouteThumb(thumb, Modifier.size(56.dp), if (recorded) MaterialTheme.colorScheme.onSurface else Cy.colors.accent)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stats, style = MaterialTheme.typography.bodyMedium.tabular, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                DifficultyTag(difficulty)
                if (note != null) {
                    Text(
                        note, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun RoutesScreen(vm: MainVm, myLoc: Pt?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val metas by vm.store.metas.collectAsState()

    val importGpx = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val id = withContext(Dispatchers.IO) {
                runCatching {
                    val (name, pts) = ctx.contentResolver.openInputStream(uri)!!.use { Gpx.read(it) }
                    if (pts.size < 2) return@runCatching null
                    val timed = pts.first().t > 0 && pts.last().t > pts.first().t
                    val dist = Geo.length(pts)
                    vm.store.save(
                        name ?: "Imported route",
                        if (timed) RouteType.RECORDED else RouteType.PLANNED,
                        pts,
                        if (timed) (pts.last().t - pts.first().t) / 1000 else (dist / 5.0).toLong(),
                        dist,
                        Geo.ascent(pts, 4.0, smooth = true),
                        if (timed) pts.first().t else System.currentTimeMillis(),
                    )
                }.getOrNull()
            }
            if (id != null) vm.detailId = id
            else Toast.makeText(ctx, "Couldn't read a route from that file", Toast.LENGTH_LONG).show()
        }
    }

    // Finished rides live in the Rides tab; this list is for routes to ride.
    val shown = metas.filter { it.type == RouteType.PLANNED }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item(key = "head") {
            Column {
                Spacer(Modifier.height(12.dp))
                CyButton(
                    when {
                        myLoc == null -> "Waiting for your location…"
                        vm.suggestBusy -> "Finding routes…"
                        vm.suggestions.isEmpty() -> "Suggest routes near me"
                        else -> "Suggest different routes"
                    },
                    { myLoc?.let { vm.suggestNearby(it) } },
                    Modifier.fillMaxWidth(), Btn.SECONDARY, Icons.Outlined.Explore,
                    enabled = myLoc != null && !vm.suggestBusy,
                )
                if (vm.suggestBusy) {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        color = Cy.colors.accent, trackColor = Cy.colors.subtle,
                    )
                    Text(
                        "Short, medium and long loops from where you are.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                    )
                }
                vm.suggestError?.let {
                    Text(
                        "Couldn't suggest routes: $it", color = Cy.colors.danger,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (vm.suggestions.isNotEmpty()) {
                    SectionLabel("Suggested from here", Modifier.padding(top = 22.dp, bottom = 2.dp))
                }
            }
        }
        itemsIndexed(vm.suggestions) { i, o ->
            val thumb = remember(o) { Geo.simplify(o.pts) }
            RouteRow(
                thumb, false, o.label.replaceFirstChar { it.uppercase() },
                "${Fmt.km(o.distanceM)} · ↑ ${o.ascentM.toInt()} m · ${Fmt.dur(o.timeS)}",
                "Tap to preview", Difficulty.of(o.distanceM, o.ascentM),
            ) { vm.openSuggestion(i) }
            Hairline()
        }
        item(key = "saved-head") {
            Column {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Saved routes", Modifier.weight(1f))
                    TextButton({ importGpx.launch(arrayOf("*/*")) }) {
                        Text("Import GPX", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (shown.isEmpty()) {
                    Text(
                        "No saved routes yet. Build one with A to B or Loop, or save a suggestion.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        items(shown, key = { it.id }) { m ->
            RouteRow(
                m.thumb, m.type == RouteType.RECORDED, m.name,
                "${Fmt.km(m.distanceM)} · ↑ ${m.ascentM.toInt()} m · ${Fmt.dur(m.durationS)}",
                Fmt.day(m.createdAt), m.difficulty,
            ) { vm.detailId = m.id }
            Hairline()
        }
    }
}

@Composable
fun RouteDetailScreen(vm: MainVm, id: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val inspection = androidx.compose.ui.platform.LocalInspectionMode.current
    val metas by vm.store.metas.collectAsState()
    val meta = metas.firstOrNull { it.id == id }
    val route by produceState<Route?>(null, id) { value = withContext(Dispatchers.IO) { vm.store.load(id) } }
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Traffic signals along the route, looked up once; null while loading or if the lookup fails.
    val signals by produceState<List<Pt>?>(null, route) {
        val r = route
        value = if (r == null || inspection) null
        else withContext(Dispatchers.IO) { runCatching { Overpass.signalsAlong(r.pts) }.getOrNull() }
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        val r = route
        if (uri == null || r == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri)!!.use { it.write(Gpx.write(r.copy(meta = meta ?: r.meta)).toByteArray()) }
                }.isSuccess
            }
            Toast.makeText(ctx, if (ok) "GPX saved" else "Couldn't save the file", Toast.LENGTH_SHORT).show()
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ vm.detailId = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Text(
                    meta?.name ?: "Route", style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                IconButton({
                    meta?.let { export.launch(it.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "route" } + ".gpx") }
                }) { Icon(Icons.Outlined.FileDownload, "Export GPX", Modifier.size(22.dp)) }
                IconButton({
                    if (meta?.type == RouteType.RECORDED) { vm.editIsNew = false; vm.editId = id } else rename = true
                }) { Icon(Icons.Outlined.Edit, "Edit", Modifier.size(20.dp)) }
                IconButton({ confirmDelete = true }) { Icon(Icons.Outlined.DeleteOutline, "Delete", Modifier.size(22.dp)) }
            }
            val r = route
            val recorded = meta?.type == RouteType.RECORDED
            val lines = remember(r) {
                listOfNotNull(r?.let { MapLine(it.pts, if (it.meta.type == RouteType.RECORDED) COLOR_TRACK else COLOR_ROUTE, 5f) })
            }
            val dots = remember(r, signals) {
                if (r == null || r.pts.isEmpty()) emptyList()
                else (signals ?: emptyList()).map { MapDot(it, "#E0A030", 4f) } +
                    listOf(MapDot(r.pts.first(), "#2F7D4F", 7f), MapDot(r.pts.last(), "#D4403A", 7f))
            }
            val camera = remember(r) { r?.let { CameraCmd(it.pts) } }
            CyclerMap(
                Modifier.weight(1f).fillMaxWidth(), vm.mapStyle, lines, dots, camera,
                initial = meta?.let { Pt(it.startLat, it.startLon) },
            )
            if (meta != null) {
                Sheet {
                    Column(
                        Modifier.heightIn(max = 370.dp).verticalScroll(rememberScrollState())
                            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Tag(if (recorded) "Ride" else "Planned")
                            DifficultyTag(meta.difficulty)
                            Feeling.emoji(meta.feeling)?.let { Text("$it ${Feeling.label(meta.feeling)}", style = MaterialTheme.typography.labelMedium) }
                            Text(
                                Fmt.date(meta.createdAt), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Metric("Distance", Fmt.kmShort(meta.distanceM), unit = "km")
                            Metric("Climb", "${meta.ascentM.toInt()}", unit = "m")
                            Metric(if (recorded) "Moving" else "Est. time", Fmt.dur(meta.durationS))
                            if (recorded && meta.durationS > 0) {
                                Metric("Avg", Fmt.kmh(meta.distanceM / meta.durationS), unit = "km/h")
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                            if (recorded && meta.maxSpeedMs > 0) Metric("Max", Fmt.kmh(meta.maxSpeedMs), unit = "km/h")
                            if (recorded && meta.elapsedS > meta.durationS) {
                                Metric("Stopped", Fmt.dur(meta.elapsedS - meta.durationS))
                            }
                            if (recorded && meta.stops > 0) Metric("Stops", "${meta.stops}")
                            Metric("Signals", signals?.size?.toString() ?: "…")
                        }

                        if (meta.laps.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            SectionLabel("Laps")
                            Spacer(Modifier.height(4.dp))
                            var prev = com.cycler.app.data.LapMark(0.0, 0, 0)
                            meta.laps.forEachIndexed { i, lap ->
                                val d = lap.distanceM - prev.distanceM
                                val t = lap.movingMs - prev.movingMs
                                prev = lap
                                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                                    Text("${i + 1}", style = MaterialTheme.typography.bodyMedium.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(32.dp))
                                    Text(Fmt.km(d), style = MaterialTheme.typography.titleSmall.tabular, modifier = Modifier.weight(1f))
                                    Text(Fmt.clock(t), style = MaterialTheme.typography.titleSmall.tabular, modifier = Modifier.weight(1f))
                                    Text(
                                        if (t > 0) Fmt.kmh(d / (t / 1000.0)) + " km/h" else "–",
                                        style = MaterialTheme.typography.titleSmall.tabular,
                                    )
                                }
                                Hairline()
                            }
                        }

                        // The full set of numbers, behind a switch so the page stays calm by default.
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.fillMaxWidth().clip(Radius).clickable { vm.advanced = !vm.advanced; vm.prefs.advancedStats = vm.advanced },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Advanced stats", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            CySwitch(vm.advanced) { vm.advanced = it; vm.prefs.advancedStats = it }
                        }
                        if (vm.advanced && r != null) AdvancedStats(vm, r)

                        // What the rider wrote afterwards.
                        if (meta.notes.isNotBlank() || meta.tags.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            if (meta.notes.isNotBlank()) Text(meta.notes, style = MaterialTheme.typography.bodyMedium)
                            if (meta.tags.isNotEmpty()) {
                                Row(
                                    Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) { meta.tags.forEach { Tag(it) } }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        MediaStrip(vm, id)
                        if (r != null) {
                            Spacer(Modifier.height(16.dp))
                            ElevationChart(r.pts)
                            if (recorded) {
                                val step = if (meta.distanceM >= 20_000) 5000.0 else 1000.0
                                val splits = remember(r) { Geo.splits(r.pts, step) }
                                if (splits.size >= 3) {
                                    Spacer(Modifier.height(16.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        SectionLabel("Speed, each ${(step / 1000).toInt()} km")
                                        Text(
                                            "best ${splits.max().toInt()} km/h",
                                            style = MaterialTheme.typography.labelMedium.tabular, color = Cy.colors.accent,
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    MiniBars(splits)
                                }
                            }
                        }
                    }
                    // The two main actions stay put while the details above scroll.
                    Hairline()
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CyButton(
                                if (recorded) "Ride again" else "Ride this route",
                                { vm.follow(r); vm.detailId = null; vm.tab = Tab.RIDE },
                                Modifier.weight(1f), icon = Icons.Outlined.PlayArrow, enabled = r != null,
                            )
                            CyButton(
                                "Share", { vm.shareId = id },
                                Modifier.weight(1f), Btn.SECONDARY, Icons.Outlined.IosShare, enabled = r != null,
                            )
                        }
                    }
                }
            }
        }
    }

    if (rename && meta != null) {
        NameDialog("Rename", meta.name, "Save", { rename = false; vm.store.rename(id, it) }, { rename = false })
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            shape = RadiusLarge,
            title = { Text("Delete this route?", style = MaterialTheme.typography.titleLarge) },
            text = { Text("It will be removed from this phone for good.") },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    if (vm.guide?.meta?.id == id) vm.follow(null)
                    vm.detailId = null
                    vm.store.delete(id)
                }) { Text("Delete", color = Cy.colors.danger) }
            },
            dismissButton = {
                TextButton({ confirmDelete = false }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            },
        )
    }
}

@Composable
private fun AdvancedStats(vm: MainVm, r: Route) {
    val meta = r.meta
    val st by produceState<com.cycler.app.data.RideStats?>(null, r) {
        value = withContext(Dispatchers.Default) { Geo.rideStats(r.pts, meta) }
    }
    val s = st ?: return
    val recorded = meta.type == RouteType.RECORDED
    fun kmh(v: Double) = if (v > 0) String.format(java.util.Locale.US, "%.1f km/h", v) else "–"
    fun clock(t: Long) = if (t > 0) java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(t)) else "–"
    fun short(sec: Long) = if (sec >= 60) Fmt.dur(sec) else "$sec s"

    if (recorded && s.hasTimes) {
        StatGroup("Speed", listOf(
            "Average, moving" to kmh(s.avgMovingKmh), "Average, with stops" to kmh(s.avgOverallKmh),
            "Top speed" to kmh(s.maxKmh), "Fastest kilometre" to kmh(s.fastestKmKmh),
            "Slowest kilometre" to kmh(s.slowestKmKmh),
        ))
        StatGroup("Time", listOf(
            "Started" to clock(s.startT), "Finished" to clock(s.endT),
            "Total time" to Fmt.dur(s.elapsedS), "Moving time" to Fmt.dur(s.movingS),
            "Stopped time" to short(s.stoppedS), "Stops" to "${s.stops}",
            "Longest stop" to if (s.longestStopS > 0) short(s.longestStopS) else "–",
        ))
    }
    if (s.hasElevation) {
        StatGroup("Elevation", listOf(
            "Climbed" to "${s.gainM.toInt()} m", "Descended" to "${s.lossM.toInt()} m",
            "Highest point" to "${s.highestM.toInt()} m", "Lowest point" to "${s.lowestM.toInt()} m",
            "Steepest climb" to String.format(java.util.Locale.US, "%.1f%%", s.steepestUpPct),
            "Steepest descent" to String.format(java.util.Locale.US, "%.1f%%", s.steepestDownPct),
            "Longest climb" to (s.longestClimb?.let { "${Fmt.km(it.lengthM)} at ${String.format(java.util.Locale.US, "%.1f", it.gradePct)}%" } ?: "None"),
        ))
    }
    if (recorded && s.hasTimes) {
        val efforts = meta.bests.entries.sortedBy { it.key }.map { "Best ${it.key} km" to Fmt.clock(it.value * 1000) }
        StatGroup("Effort", listOf(
            "Energy, estimated" to if (vm.weightKg > 0) "${s.kcal(vm.weightKg)} kcal" else "Set your weight in Settings",
        ) + efforts)

        Spacer(Modifier.height(16.dp))
        SectionLabel("Time at each speed, km/h")
        Spacer(Modifier.height(6.dp))
        val top = s.zones.max().coerceAtLeast(0.01)
        com.cycler.app.data.RideStats.ZONES.forEachIndexed { i, label ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodySmall.tabular, modifier = Modifier.width(72.dp))
                Box(Modifier.weight(1f).height(10.dp)) {
                    Box(
                        Modifier.fillMaxWidth((s.zones[i] / top).toFloat().coerceIn(0.01f, 1f)).height(10.dp).clip(Radius)
                            .background(if (s.zones[i] == s.zones.max()) Cy.colors.accent else MaterialTheme.colorScheme.onSurface),
                    )
                }
                Text(
                    "${(s.zones[i] * 100).toInt()}%", style = MaterialTheme.typography.bodySmall.tabular,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(44.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
    }
}

/** A titled list of label and value pairs, two to a row. */
@Composable
private fun StatGroup(title: String, items: List<Pair<String, String>>) {
    Spacer(Modifier.height(16.dp))
    SectionLabel(title)
    Spacer(Modifier.height(4.dp))
    items.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            pair.forEach { (label, value) ->
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.titleSmall.tabular)
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
        Hairline()
    }
}
