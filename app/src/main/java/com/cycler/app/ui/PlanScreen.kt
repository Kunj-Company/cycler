package com.cycler.app.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cycler.app.MainVm
import com.cycler.app.PlanMode
import com.cycler.app.Tab
import com.cycler.app.data.Geo
import com.cycler.app.data.Pt
import com.cycler.app.data.Route
import com.cycler.app.data.RouteType
import com.cycler.app.net.Photon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val abPoints = if (vm.fromMyLocation && myLoc != null) listOf(myLoc) + vm.planPoints else vm.planPoints
    val loopStart = vm.planPoints.firstOrNull() ?: myLoc

    LaunchedEffect(myLoc) {
        if (!centred && myLoc != null && vm.options.isEmpty() && vm.planPoints.isEmpty()) {
            camera = CameraCmd(listOf(myLoc), 13.5)
            centred = true
        }
    }
    LaunchedEffect(vm.options, vm.selected) {
        vm.options.getOrNull(vm.selected)?.let { camera = CameraCmd(it.pts); centred = true }
    }

    fun addPoint(p: Pt) {
        when (mode) {
            PlanMode.AB -> vm.planPoints = vm.planPoints + p
            PlanMode.LOOP -> vm.planPoints = listOf(p)
            PlanMode.PAST -> return
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
            if (mode == PlanMode.PAST) {
                pastRoute?.let { add(MapLine(it.pts, COLOR_GUIDE, 5f)) }
            } else {
                vm.options.forEachIndexed { i, o -> if (i != vm.selected) add(MapLine(o.pts, COLOR_ALT, 4f)) }
                vm.options.getOrNull(vm.selected)?.let { add(MapLine(it.pts, COLOR_ROUTE, 6f)) }
            }
        }
    }
    val dots = remember(myLoc, vm.planPoints, vm.fromMyLocation, mode) {
        buildList {
            if (mode != PlanMode.PAST) {
                val pts = vm.planPoints
                pts.forEachIndexed { i, p ->
                    val color = when {
                        mode == PlanMode.LOOP -> "#2E7D32"
                        i == pts.lastIndex && (pts.size > 1 || vm.fromMyLocation) -> "#D32F2F"
                        i == 0 && !vm.fromMyLocation -> "#2E7D32"
                        else -> "#FB8C00"
                    }
                    add(MapDot(p, color, 8f))
                }
            }
            myLoc?.let { add(MapDot(it, COLOR_ME, 7f)) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CyclerMap(
                Modifier.fillMaxSize(), vm.mapStyle, lines, dots, camera,
                initial = vm.prefs.lastLoc,
                onLongPress = if (mode == PlanMode.PAST) null else { p -> addPoint(p) },
            )
            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                Surface(shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 3.dp) {
                    Column {
                        Row(
                            Modifier.padding(horizontal = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PlanMode.entries.forEach { m ->
                                FilterChip(
                                    selected = mode == m,
                                    onClick = {
                                        if (mode != m) { vm.clearPlan(); pastRoute = null; places = emptyList(); vm.planMode = m }
                                    },
                                    label = { Text(m.label) },
                                )
                            }
                        }
                        if (mode != PlanMode.PAST) {
                            TextField(
                                value = query,
                                onValueChange = { query = it; if (it.isBlank()) { places = emptyList(); searchNote = null } },
                                placeholder = { Text(if (mode == PlanMode.AB) "Search a place to add" else "Search a start point") },
                                singleLine = true,
                                trailingIcon = { IconButton({ runSearch() }) { Icon(Icons.Filled.Search, "Search") } },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                            searchNote?.let { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
                            places.forEach { pl ->
                                Text(
                                    pl.label, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        addPoint(pl.pt)
                                        camera = CameraCmd(listOf(pl.pt), 14.0)
                                        centred = true
                                        places = emptyList()
                                        query = ""
                                    }.padding(horizontal = 14.dp, vertical = 10.dp),
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }

        Surface(tonalElevation = 2.dp) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 330.dp).verticalScroll(rememberScrollState()).padding(14.dp),
            ) {
                when (mode) {
                    PlanMode.AB -> {
                        Text(
                            if (vm.planPoints.isEmpty()) "Long-press the map or search to add your destination. Add more points to shape the route."
                            else "${vm.planPoints.size} point${if (vm.planPoints.size > 1) "s" else ""} added" +
                                if (vm.fromMyLocation && myLoc == null) " · waiting for your location…" else "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = vm.fromMyLocation,
                                onClick = { vm.fromMyLocation = !vm.fromMyLocation; vm.options = emptyList() },
                                label = { Text("Start from my location") },
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                { vm.planPoints = vm.planPoints.dropLast(1); vm.options = emptyList() },
                                enabled = vm.planPoints.isNotEmpty(),
                            ) { Text("Undo") }
                            TextButton({ vm.clearPlan() }, enabled = vm.planPoints.isNotEmpty()) { Text("Clear") }
                        }
                        Button(
                            { vm.findRoutes(abPoints) },
                            enabled = abPoints.size >= 2 && !vm.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (vm.options.isEmpty()) "Find routes" else "Find again") }
                    }
                    PlanMode.LOOP -> {
                        Text(
                            when {
                                vm.planPoints.isNotEmpty() -> "Starting from the point you picked. Long-press to move it."
                                myLoc != null -> "Starting from where you are. Long-press the map to start elsewhere."
                                else -> "Waiting for your location… or long-press the map to pick a start."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("Distance: about ${vm.loopKm.toInt()} km", fontWeight = FontWeight.SemiBold)
                        Slider(
                            value = vm.loopKm,
                            onValueChange = { vm.loopKm = (it / 5).toInt() * 5f },
                            valueRange = 5f..120f,
                        )
                        Button(
                            { loopStart?.let { vm.findLoops(it) } },
                            enabled = loopStart != null && !vm.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (vm.options.isEmpty()) "Suggest loops" else "Suggest again") }
                    }
                    PlanMode.PAST -> PastRides(vm, metas.sortedBy { m ->
                        if (myLoc == null) 0.0 else Geo.dist(myLoc.lat, myLoc.lon, m.startLat, m.startLon)
                    }, myLoc, pastRoute) { r ->
                        pastRoute = r
                        if (r != null) camera = CameraCmd(r.pts)
                    }
                }

                if (mode != PlanMode.PAST) {
                    if (vm.busy) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            "Working out routes…", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    vm.error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Couldn't find a route: $it", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (vm.options.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            vm.options.forEachIndexed { i, o ->
                                val sel = i == vm.selected
                                OutlinedCard(
                                    onClick = { vm.selected = i },
                                    border = BorderStroke(
                                        if (sel) 2.dp else 1.dp,
                                        if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    ),
                                ) {
                                    Column(Modifier.padding(12.dp).width(132.dp)) {
                                        Text(o.label, style = MaterialTheme.typography.labelLarge)
                                        Text(Fmt.km(o.distanceM), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                        Text("↑ ${o.ascentM.toInt()} m · ${Fmt.dur(o.timeS)}", style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            o.tags.joinToString(" · ").ifEmpty { " " },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary, maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                        if (option != null) {
                            Spacer(Modifier.height(10.dp))
                            ElevationChart(option.pts)
                            Spacer(Modifier.height(10.dp))
                            Button({ showSave = true }, Modifier.fillMaxWidth()) { Text("Save this route") }
                        }
                    }
                }
            }
        }
    }

    if (showSave && option != null) {
        val default = if (mode == PlanMode.LOOP) "${option.label} · ${Fmt.km(option.distanceM)}" else "Route · ${Fmt.km(option.distanceM)}"
        NameDialog("Save route", default, "Save", { showSave = false; vm.saveSelected(it) }, { showSave = false })
    }
}

@Composable
private fun PastRides(
    vm: MainVm,
    metas: List<com.cycler.app.data.RouteMeta>,
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
            Button({ vm.guide = shown; vm.tab = Tab.RIDE }, Modifier.weight(1f)) { Text("Ride it") }
            OutlinedButton({
                scope.launch {
                    val id = withContext(Dispatchers.IO) {
                        val pts = shown.pts.reversed().map { it.copy(t = 0L) }
                        vm.store.save(
                            shown.meta.name + " (reversed)", RouteType.PLANNED, pts,
                            shown.meta.durationS, shown.meta.distanceM, Geo.ascent(pts, smooth = shown.meta.type == RouteType.RECORDED),
                        )
                    }
                    vm.detailId = id
                }
            }, Modifier.weight(1f)) { Text("Save reversed") }
        }
        TextButton({ onShow(null) }) { Text("Back to list") }
        return
    }
    Text("Your saved routes, nearest first", style = MaterialTheme.typography.labelMedium)
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
        HorizontalDivider()
    }
}
