package com.cycler.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.Image
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
    var filter by remember { mutableIntStateOf(0) }

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

    val shown = metas.filter {
        when (filter) {
            1 -> it.type == RouteType.RECORDED
            2 -> it.type == RouteType.PLANNED
            else -> true
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item(key = "head") {
            Column {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Routes", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    TextButton({ importGpx.launch(arrayOf("*/*")) }) {
                        Text("Import GPX", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(10.dp))
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
                SectionLabel("Saved", Modifier.padding(top = 22.dp, bottom = 10.dp))
                Segmented(listOf("All", "Rides", "Planned"), filter, { filter = it }, Modifier.fillMaxWidth())
                if (shown.isEmpty()) {
                    Text(
                        when (filter) {
                            1 -> "No rides recorded yet. Press Start on the Ride tab and your ride lands here."
                            2 -> "No planned routes yet. Build one in the Plan tab, or save a suggestion."
                            else -> "Nothing saved yet. Recorded rides and planned routes will appear here."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 20.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
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
    var removeMedia by remember { mutableStateOf<java.io.File?>(null) }
    val mediaTick by vm.store.mediaTick.collectAsState()
    val media = remember(id, mediaTick) { vm.store.media(id) }
    val thumbs by produceState(emptyList<Pair<java.io.File, android.graphics.Bitmap>>(), media) {
        value = withContext(Dispatchers.IO) { media.mapNotNull { f -> loadShareImage(f, 200)?.let { f to it } } }
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            withContext(Dispatchers.IO) { uris.forEach { vm.store.addMedia(id, ctx.contentResolver, it) } }
        }
    }
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
                IconButton({ rename = true }) { Icon(Icons.Outlined.Edit, "Rename", Modifier.size(20.dp)) }
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

                        // Photos and videos from the ride.
                        Spacer(Modifier.height(16.dp))
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                        ) {
                            thumbs.forEach { (file, bmp) ->
                                Box(Modifier.size(68.dp).clip(Radius).clickable { removeMedia = file }) {
                                    Image(
                                        bmp.asImageBitmap(), "Ride photo", contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    if (file.name.endsWith(".mp4")) {
                                        Icon(
                                            Icons.Outlined.PlayArrow, "Video", tint = androidx.compose.ui.graphics.Color.White,
                                            modifier = Modifier.align(Alignment.Center).size(28.dp),
                                        )
                                    }
                                }
                            }
                            Row(
                                Modifier.height(68.dp).clip(Radius).border(1.dp, Cy.colors.border, Radius)
                                    .clickable {
                                        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                                    }.padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (media.isEmpty()) "Add photos or videos" else "Add", style = MaterialTheme.typography.labelLarge)
                            }
                        }
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

    removeMedia?.let { f ->
        AlertDialog(
            onDismissRequest = { removeMedia = null },
            shape = RadiusLarge,
            title = { Text("Remove from this ride?", style = MaterialTheme.typography.titleLarge) },
            text = { Text("The copy kept with this ride is removed. Your gallery is not touched.") },
            confirmButton = {
                TextButton({ vm.store.removeMedia(f); removeMedia = null }) { Text("Remove", color = Cy.colors.danger) }
            },
            dismissButton = {
                TextButton({ removeMedia = null }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            },
        )
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
