package com.cycler.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cycler.app.MainVm
import com.cycler.app.Pick
import com.cycler.app.PlanMode
import com.cycler.app.Stop
import com.cycler.app.Tab
import com.cycler.app.data.Geo
import com.cycler.app.data.Pt
import com.cycler.app.data.Route
import com.cycler.app.data.RouteMeta
import com.cycler.app.data.RouteType
import com.cycler.app.net.Photon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val GREEN = "#2E7D32"
private const val RED = "#D32F2F"
private const val ORANGE = "#FB8C00"

@Composable
fun PlanScreen(vm: MainVm, myLoc: Pt?) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var camera by remember { mutableStateOf<CameraCmd?>(null) }
    var centred by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var places by remember { mutableStateOf(listOf<Photon.Place>()) }
    var searching by remember { mutableStateOf(false) }
    var searchNote by remember { mutableStateOf<String?>(null) }
    var pastRoute by remember { mutableStateOf<Route?>(null) }
    var showSave by remember { mutableStateOf(false) }
    val metas by vm.store.metas.collectAsState()

    val mode = vm.planMode
    val option = vm.options.getOrNull(vm.selected)
    // In loop mode the only thing to pick is the start.
    val pick = if (mode == PlanMode.LOOP) Pick.START else vm.pick
    val startPt = vm.start?.pt ?: myLoc
    val abPoints = listOfNotNull(startPt) + vm.vias.map { it.pt } + listOfNotNull(vm.end?.pt)

    if (mode == PlanMode.SAVED) {
        // Saved and suggested routes: a plain list, no map.
        Column(Modifier.fillMaxSize()) {
            Segmented(
                PlanMode.entries.map { it.label }, PlanMode.entries.indexOf(mode),
                { i -> vm.clearPlan(); vm.planMode = PlanMode.entries[i] },
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            )
            RoutesScreen(vm, myLoc)
        }
        return
    }

    LaunchedEffect(myLoc) {
        if (!centred && myLoc != null && vm.options.isEmpty() && vm.end == null && vm.start == null) {
            camera = CameraCmd(listOf(myLoc), 13.5)
            centred = true
        }
    }
    LaunchedEffect(vm.options, vm.selected) {
        vm.options.getOrNull(vm.selected)?.let { camera = CameraCmd(it.pts); centred = true }
    }

    fun setPoint(p: Pt, label: String) {
        if (mode == PlanMode.SAVED) return
        val stop = Stop(p, label)
        when (pick) {
            Pick.START -> { vm.start = stop; if (mode == PlanMode.AB && vm.end == null) vm.pick = Pick.END }
            Pick.END -> vm.end = stop
            Pick.VIA -> vm.vias = vm.vias + stop
        }
        vm.options = emptyList()
        vm.error = null
    }

    fun runSearch() {
        val q = query.trim()
        if (q.length < 2 || searching) return
        focus.clearFocus()
        searching = true
        searchNote = null
        scope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { Photon.search(q, myLoc ?: vm.prefs.lastLoc) } }
            places = res.getOrDefault(emptyList())
            searchNote = when {
                res.isFailure -> "Search failed. Check your connection."
                places.isEmpty() -> "Nothing found for \"$q\""
                else -> null
            }
            searching = false
        }
    }

    val lines = remember(vm.options, vm.selected, pastRoute, mode) {
        buildList {
            if (mode == PlanMode.SAVED) {
                pastRoute?.let { add(MapLine(it.pts, COLOR_GUIDE, 5f)) }
            } else {
                vm.options.forEachIndexed { i, o -> if (i != vm.selected) add(MapLine(o.pts, COLOR_ALT, 4f)) }
                vm.options.getOrNull(vm.selected)?.let { add(MapLine(it.pts, COLOR_ROUTE, 6f)) }
            }
        }
    }
    val dots = remember(myLoc, vm.start, vm.end, vm.vias, mode) {
        buildList {
            myLoc?.let { add(MapDot(it, COLOR_ME, 7f)) }
            if (mode != PlanMode.SAVED) {
                vm.start?.let { add(MapDot(it.pt, GREEN, 9f)) }
                if (mode == PlanMode.AB) {
                    vm.vias.forEach { add(MapDot(it.pt, ORANGE, 7f)) }
                    vm.end?.let { add(MapDot(it.pt, RED, 9f)) }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CyclerMap(
                Modifier.fillMaxSize(), vm.mapStyle, lines, dots, camera,
                initial = vm.prefs.lastLoc,
                onLongPress = if (mode == PlanMode.SAVED) null else { p -> setPoint(p, "Pin on map") },
            )
            LayerButton(vm.mapStyle, Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                vm.mapStyle = it; vm.prefs.mapStyle = it
            }
            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, Cy.colors.border), shadowElevation = 2.dp,
                ) {
                    Column {
                        Segmented(
                            PlanMode.entries.map { it.label }, PlanMode.entries.indexOf(mode),
                            { i ->
                                val m = PlanMode.entries[i]
                                if (mode != m) { vm.clearPlan(); pastRoute = null; places = emptyList(); vm.planMode = m }
                            },
                            Modifier.fillMaxWidth().padding(6.dp),
                        )
                        if (mode != PlanMode.SAVED) {
                            TextField(
                                value = query,
                                onValueChange = { query = it; if (it.isBlank()) { places = emptyList(); searchNote = null } },
                                placeholder = {
                                    Text(
                                        when (pick) {
                                            Pick.START -> "Search for a start point"
                                            Pick.END -> "Search for your destination"
                                            Pick.VIA -> "Search for a stop on the way"
                                        }
                                    )
                                },
                                singleLine = true,
                                trailingIcon = { IconButton({ runSearch() }) { Icon(Icons.Filled.Search, "Search") } },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                ),
                                textStyle = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cy.colors.accent, trackColor = Cy.colors.subtle)
                            searchNote?.let { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
                            places.forEach { pl ->
                                Hairline()
                                Text(
                                    pl.label, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        setPoint(pl.pt, pl.label)
                                        camera = CameraCmd(listOf(pl.pt), 14.0)
                                        centred = true
                                        places = emptyList()
                                        query = ""
                                    }.padding(horizontal = 14.dp, vertical = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        Sheet {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                val hint = "Long-press the map or search above"
                val showResults = vm.options.isNotEmpty() && mode != PlanMode.SAVED
                val startText = vm.start?.label ?: if (myLoc != null) "My location" else "My location (waiting for GPS…)"
                when (mode) {
                    PlanMode.AB -> if (showResults) {
                        val wind = option?.pts?.let { p ->
                            if (p.size >= 2 && Geo.dist(p.first(), p.last()) > 1000) vm.weather?.feel(Geo.bearing(p.first(), p.last())) else null
                        }
                        PlanSummary(
                            "Route", startText.substringBefore(",") + "  →  " + (vm.end?.label?.substringBefore(",") ?: "") +
                                if (vm.vias.isNotEmpty()) "  (${vm.vias.size} stop${if (vm.vias.size > 1) "s" else ""})" else "",
                            wind,
                        ) { vm.options = emptyList() }
                    } else {
                        StopRow(
                            Color(0xFF2E7D32), "Start", startText, pick == Pick.START,
                            onSelect = { vm.pick = Pick.START },
                            onClear = if (vm.start != null) ({ vm.start = null; vm.options = emptyList() }) else null,
                            clearLabel = "Use my location",
                        )
                        vm.vias.forEachIndexed { i, v ->
                            StopRow(
                                Color(0xFFFB8C00), "Stop ${i + 1}", v.label, false, onSelect = {},
                                onClear = { vm.vias = vm.vias.filterIndexed { k, _ -> k != i }; vm.options = emptyList() },
                                clearLabel = "Remove stop",
                            )
                        }
                        if (pick == Pick.VIA) {
                            StopRow(Color(0xFFFB8C00), "Add a stop", hint, true, onSelect = {}, onClear = { vm.pick = Pick.END }, clearLabel = "Cancel")
                        }
                        StopRow(
                            Color(0xFFD32F2F), "End", vm.end?.label ?: if (pick == Pick.END) hint else "Tap to choose",
                            pick == Pick.END,
                            onSelect = { vm.pick = Pick.END },
                            onClear = if (vm.end != null) ({ vm.end = null; vm.options = emptyList() }) else null,
                            clearLabel = "Clear end",
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton({ vm.pick = Pick.VIA }, enabled = pick != Pick.VIA) { Text("+ Add stop") }
                            TextButton(
                                {
                                    val oldStart = vm.start ?: myLoc?.let { Stop(it, "My location") }
                                    vm.start = vm.end
                                    vm.end = oldStart
                                    vm.vias = vm.vias.reversed()
                                    vm.options = emptyList()
                                },
                                enabled = vm.end != null,
                            ) { Text("Swap") }
                            Spacer(Modifier.weight(1f))
                            TextButton({ vm.clearPlan() }) { Text("Clear") }
                        }
                        CyButton(
                            if (vm.options.isEmpty()) "Find routes" else "Find again", { vm.findRoutes(abPoints) },
                            Modifier.fillMaxWidth(), if (vm.options.isEmpty()) Btn.PRIMARY else Btn.SECONDARY,
                            enabled = startPt != null && vm.end != null && !vm.busy,
                        )
                    }
                    PlanMode.LOOP -> if (showResults) {
                        PlanSummary("Loops", "From ${startText.substringBefore(",")}") { vm.options = emptyList() }
                    } else {
                        StopRow(
                            Color(0xFF2E7D32), "Start and finish", startText, true, onSelect = {},
                            onClear = if (vm.start != null) ({ vm.start = null; vm.options = emptyList() }) else null,
                            clearLabel = "Use my location",
                        )
                        Text(
                            "Long-press the map or search to start somewhere else.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            SectionLabel("Distance", Modifier.weight(1f).padding(bottom = 6.dp))
                            Text("${vm.loopKm.roundToInt()}", style = MaterialTheme.typography.displaySmall)
                            Text(
                                " km", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp),
                            )
                        }
                        Slider(
                            value = vm.loopKm,
                            onValueChange = { vm.loopKm = ((it / 5f).roundToInt() * 5f).coerceIn(5f, 200f) },
                            valueRange = 5f..200f,
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Cy.colors.border,
                            ),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("5 km", style = MaterialTheme.typography.labelSmall)
                            Text("200 km", style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.height(6.dp))
                        CyButton(
                            if (vm.options.isEmpty()) "Suggest loops" else "Suggest again",
                            { startPt?.let { vm.findLoops(it) } },
                            Modifier.fillMaxWidth(), if (vm.options.isEmpty()) Btn.PRIMARY else Btn.SECONDARY,
                            enabled = startPt != null && !vm.busy,
                        )
                    }
                    PlanMode.SAVED -> PastRides(vm, metas.sortedBy { m ->
                        if (myLoc == null) 0.0 else Geo.dist(myLoc.lat, myLoc.lon, m.startLat, m.startLon)
                    }, myLoc, pastRoute) { r ->
                        pastRoute = r
                        if (r != null) camera = CameraCmd(r.pts)
                    }
                }

                if (mode != PlanMode.SAVED) {
                    if (vm.busy) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cy.colors.accent, trackColor = Cy.colors.subtle)
                        Text(
                            "Working out routes…", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    vm.error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Couldn't find a route: $it", color = Cy.colors.danger,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (vm.options.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            vm.options.forEachIndexed { i, o ->
                                val sel = i == vm.selected
                                Column(
                                    Modifier.width(156.dp).clip(RoundedCornerShape(12.dp))
                                        .background(if (sel) Cy.colors.accentSoft else Color.Transparent)
                                        .border(
                                            if (sel) 1.5.dp else 1.dp,
                                            if (sel) Cy.colors.accent else Cy.colors.border, RoundedCornerShape(12.dp),
                                        )
                                        .clickable { vm.selected = i }.padding(12.dp),
                                ) {
                                    Text(
                                        o.label.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(Fmt.km(o.distanceM), style = MaterialTheme.typography.titleLarge.tabular)
                                    Text(
                                        "↑ ${o.ascentM.toInt()} m · ${Fmt.dur(o.timeS)}",
                                        style = MaterialTheme.typography.bodySmall.tabular,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        DifficultyTag(com.cycler.app.data.Difficulty.of(o.distanceM, o.ascentM))
                                        o.tags.firstOrNull()?.let { Tag(it) }
                                    }
                                }
                            }
                        }
                        if (option != null) {
                            Spacer(Modifier.height(10.dp))
                            ElevationChart(option.pts)
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CyButton("Ride it now", { vm.rideSelected() }, Modifier.weight(1f))
                                CyButton("Save route", { showSave = true }, Modifier.weight(1f), Btn.SECONDARY)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSave && option != null) {
        val default = when {
            mode == PlanMode.LOOP -> "${option.label} · ${Fmt.km(option.distanceM)}"
            vm.end != null && vm.end?.label != "Pin on map" -> "To " + vm.end!!.label.substringBefore(",")
            else -> "Route · ${Fmt.km(option.distanceM)}"
        }
        NameDialog("Save route", default, "Save", { showSave = false; vm.saveSelected(it) }, { showSave = false })
    }
}

/** Shown above the results in place of the editor, so the routes stay in view. */
@Composable
private fun PlanSummary(title: String, value: String, note: String? = null, onEdit: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            SectionLabel(title)
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        CyButton("Edit", onEdit, kind = Btn.SECONDARY, height = 36.dp)
    }
}

/** One line of the trip: a coloured dot, what it is, and the chosen place. */
@Composable
private fun StopRow(
    color: Color, title: String, value: String, active: Boolean,
    onSelect: () -> Unit, onClear: (() -> Unit)?, clearLabel: String,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(MaterialTheme.shapes.medium)
            .background(if (active) Cy.colors.accentSoft else Color.Transparent)
            .clickable(onClick = onSelect).padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onClear != null) {
            IconButton(onClear) { Icon(Icons.Filled.Close, clearLabel) }
        } else {
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun PastRides(
    vm: MainVm,
    metas: List<RouteMeta>,
    myLoc: Pt?,
    shown: Route?,
    onShow: (Route?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    if (metas.isEmpty()) {
        Text("Nothing saved yet. Record a ride or save a planned route and it will show up here, nearest first.")
        return
    }
    if (shown != null) {
        Text(shown.meta.name, style = MaterialTheme.typography.titleMedium)
        Text(
            "${Fmt.km(shown.meta.distanceM)} · ↑ ${shown.meta.ascentM.toInt()} m",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        ElevationChart(shown.pts)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CyButton("Ride it", { vm.follow(shown); vm.tab = Tab.RIDE }, Modifier.weight(1f))
            CyButton("Save reversed", {
                scope.launch {
                    val id = withContext(Dispatchers.IO) {
                        val pts = shown.pts.reversed().map { it.copy(t = 0L) }
                        vm.store.save(
                            shown.meta.name + " (reversed)", RouteType.PLANNED, pts,
                            shown.meta.durationS, shown.meta.distanceM,
                            Geo.ascent(pts, smooth = shown.meta.type == RouteType.RECORDED),
                        )
                    }
                    vm.detailId = id
                }
            }, Modifier.weight(1f), Btn.SECONDARY)
        }
        TextButton({ onShow(null) }) { Text("Back to list") }
        return
    }
    SectionLabel("Your saved routes, nearest first")
    metas.forEach { m ->
        Row(
            Modifier.fillMaxWidth().clickable {
                scope.launch { onShow(withContext(Dispatchers.IO) { vm.store.load(m.id) }) }
            }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(m.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${Fmt.km(m.distanceM)} · ↑ ${m.ascentM.toInt()} m",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (myLoc != null) {
                Text(
                    Fmt.km(Geo.dist(myLoc.lat, myLoc.lon, m.startLat, m.startLon)) + " away",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Hairline()
    }
}
