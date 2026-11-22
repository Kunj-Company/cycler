package com.cycler.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cycler.app.MainVm
import com.cycler.app.data.Geo
import com.cycler.app.data.Route
import com.cycler.app.data.RouteStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Segments with their efforts, re-checked against any rides added since last time. */
@Composable
fun rememberSegments(vm: MainVm): List<RouteStore.SegmentFile> {
    val tick by vm.store.segTick.collectAsState()
    val metas by vm.store.metas.collectAsState()
    val list by produceState(emptyList<RouteStore.SegmentFile>(), tick, metas) {
        value = withContext(Dispatchers.IO) { vm.store.refreshSegments() }
    }
    return list
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, top = 2.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** Every segment, with its length, best time and number of attempts. */
@Composable
fun SegmentsScreen(vm: MainVm) {
    val segs = rememberSegments(vm)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopBar("Segments", { vm.showSegments = false })
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                Text(
                    "A segment is a stretch of road you want timed. Mark it once and every ride over it, past or future, " +
                        "is timed and ranked against your best.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (segs.isEmpty()) {
                    Text(
                        "No segments yet. Open one of your rides and tap \"Create a segment\".",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 18.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    CyButton("Go to my rides", { vm.showSegments = false; vm.tab = com.cycler.app.Tab.RIDES }, kind = Btn.SECONDARY)
                }
                Spacer(Modifier.height(8.dp))
                segs.forEach { sf ->
                    val best = sf.efforts.minByOrNull { it.seconds }
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.segmentId = sf.seg.id }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RouteThumb(Geo.simplify(sf.seg.pts, 32), Modifier.size(52.dp), Cy.colors.accent)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(sf.seg.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${Fmt.km(sf.seg.lengthM)} · ${sf.efforts.size} attempt${if (sf.efforts.size == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodySmall.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (best != null) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(Fmt.clock(best.seconds * 1000), style = MaterialTheme.typography.titleMedium.tabular)
                                Text("best", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Hairline()
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** One segment on the map with every attempt, fastest first. */
@Composable
fun SegmentDetailScreen(vm: MainVm, id: String) {
    val segs = rememberSegments(vm)
    val metas by vm.store.metas.collectAsState()
    val sf = segs.firstOrNull { it.seg.id == id }
    var confirmDelete by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopBar(sf?.seg?.name ?: "Segment", { vm.segmentId = null }) {
                IconButton({ confirmDelete = true }) { Icon(Icons.Outlined.DeleteOutline, "Delete segment", Modifier.size(22.dp)) }
            }
            if (sf == null) return@Column
            val lines = remember(sf.seg.id) { listOf(MapLine(sf.seg.pts, COLOR_ROUTE, 6f)) }
            val dots = remember(sf.seg.id) { listOf(MapDot(sf.seg.pts.first(), "#2F7D4F", 7f), MapDot(sf.seg.pts.last(), "#D4403A", 7f)) }
            val camera = remember(sf.seg.id) { CameraCmd(sf.seg.pts) }
            CyclerMap(Modifier.weight(1f).fillMaxWidth(), vm.mapStyle, lines, dots, camera, initial = sf.seg.pts.first())
            Sheet {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp)) {
                    val ranked = sf.efforts.sortedBy { it.seconds }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric("Length", Fmt.kmShort(sf.seg.lengthM), unit = "km")
                        Metric("Attempts", "${ranked.size}")
                        Metric("Best", ranked.firstOrNull()?.let { Fmt.clock(it.seconds * 1000) } ?: "–")
                        Metric("Best speed", ranked.firstOrNull()?.let { Fmt.kmh(sf.seg.lengthM / it.seconds) } ?: "–", unit = "km/h")
                    }
                    Spacer(Modifier.height(18.dp))
                    SectionLabel("Your attempts, fastest first")
                    if (ranked.isEmpty()) {
                        Text(
                            "None of your recorded rides pass over this segment from start to end.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    val latest = sf.efforts.maxByOrNull { it.startT }
                    ranked.forEachIndexed { i, e ->
                        val ride = metas.firstOrNull { it.id == e.rideId }
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                vm.segmentId = null; vm.showSegments = false; vm.detailId = e.rideId
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${i + 1}", style = MaterialTheme.typography.titleMedium.tabular, color = if (i == 0) Cy.colors.accent else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(32.dp))
                            Column(Modifier.weight(1f)) {
                                Text(Fmt.clock(e.seconds * 1000) + "  ·  " + Fmt.kmh(sf.seg.lengthM / e.seconds) + " km/h", style = MaterialTheme.typography.titleSmall.tabular)
                                Text(
                                    "${Fmt.day(e.startT)} · ${ride?.name ?: "Ride"}", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (i == 0) Tag("Best", Cy.colors.accent, filled = true)
                            else if (e == latest) Tag("Latest")
                        }
                        Hairline()
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            shape = RadiusLarge,
            title = { Text("Delete this segment?", style = MaterialTheme.typography.titleLarge) },
            text = { Text("Its times are removed too. Your rides are not touched.") },
            confirmButton = {
                TextButton({ confirmDelete = false; vm.segmentId = null; vm.store.deleteSegment(id) }) { Text("Delete", color = Cy.colors.danger) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        )
    }
}

/** Cut a segment out of a ride by dragging the two ends along it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentCreateScreen(vm: MainVm, rideId: String) {
    val scope = rememberCoroutineScope()
    val route by produceState<Route?>(null, rideId) { value = withContext(Dispatchers.IO) { vm.store.load(rideId) } }
    var range by remember { mutableStateOf(0.25f..0.5f) }
    var name by remember { mutableStateOf("") }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            TopBar("New segment", { vm.segmentFromRide = null })
            val r = route
            if (r == null || r.pts.size < 2) return@Column
            val cum = remember(r) { Geo.cumulative(r.pts) }
            val total = cum.last()
            // The slider works in distance along the ride; turn its two ends into point indexes.
            fun indexAt(f: Float): Int {
                val d = total * f
                val i = cum.indexOfFirst { it >= d }
                return if (i < 0) r.pts.size - 1 else i
            }
            val a = indexAt(range.start)
            val b = indexAt(range.endInclusive).coerceAtLeast(a + 1).coerceAtMost(r.pts.size - 1)
            val piece = r.pts.subList(a, b + 1)
            val len = cum[b] - cum[a]
            val lines = remember(r, a, b) { listOf(MapLine(r.pts, COLOR_ALT, 4f, 0.7f), MapLine(piece, COLOR_ROUTE, 7f)) }
            val dots = remember(a, b) { listOf(MapDot(r.pts[a], "#2F7D4F", 8f), MapDot(r.pts[b], "#D4403A", 8f)) }
            val camera = remember(r) { CameraCmd(r.pts) }
            CyclerMap(Modifier.weight(1f).fillMaxWidth(), vm.mapStyle, lines, dots, camera, initial = r.pts.first())
            Sheet {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            SectionLabel("Drag the two ends along your ride")
                            Text(
                                "From ${Fmt.km(cum[a])} to ${Fmt.km(cum[b])}", style = MaterialTheme.typography.bodySmall.tabular,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(Fmt.km(len), style = MaterialTheme.typography.headlineSmall.tabular)
                    }
                    RangeSlider(
                        value = range, onValueChange = { range = it },
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = Cy.colors.accent,
                            inactiveTrackColor = Cy.colors.border,
                        ),
                    )
                    OutlinedTextField(
                        name, { name = it }, singleLine = true, shape = Radius, label = { Text("Name, e.g. Nandi climb") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (len < 200) {
                        Text(
                            "A segment needs to be at least 200 m long.", style = MaterialTheme.typography.bodySmall,
                            color = Cy.colors.warn, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    CyButton(
                        "Save segment",
                        {
                            scope.launch {
                                val id = withContext(Dispatchers.IO) {
                                    // Thin the points a little: the shape matters, not every GPS fix.
                                    val step = piece.size / 400 + 1
                                    val slim = piece.filterIndexed { i, _ -> i % step == 0 || i == piece.size - 1 }
                                    vm.store.addSegment(name.trim().ifBlank { "Segment · ${Fmt.km(len)}" }, slim).also { vm.store.refreshSegments() }
                                }
                                vm.segmentFromRide = null
                                vm.detailId = null
                                vm.showSegments = true
                                vm.segmentId = id
                            }
                        },
                        Modifier.fillMaxWidth(), enabled = len >= 200, height = 52.dp,
                    )
                }
            }
        }
    }
}
