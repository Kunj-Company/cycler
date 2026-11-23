package com.cycler.app.ui

import androidx.compose.foundation.selection.selectable
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cycler.app.MainVm
import com.cycler.app.Pick
import com.cycler.app.PlanMode
import com.cycler.app.Stop
import com.cycler.app.data.Difficulty
import com.cycler.app.data.Geo
import com.cycler.app.data.Pt
import com.cycler.app.net.Photon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val GREEN = Color(0xFF2E7D32)
private val RED = Color(0xFFD32F2F)
private val ORANGE = Color(0xFFFB8C00)

@Composable
fun PlanScreen(vm: MainVm, myLoc: Pt?) {
    var camera by remember { mutableStateOf<CameraCmd?>(null) }
    var centred by remember { mutableStateOf(false) }
    var showSave by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    /** Which field the full-screen place search is open for, if it is open. */
    var searchFor by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Pick?>(null) }

    val mode = vm.planMode
    fun switchMode(i: Int) {
        val m = PlanMode.entries[i]
        if (mode != m) { vm.clearPlan(); vm.planMode = m }
    }

    if (mode == PlanMode.SAVED) {
        // Saved and suggested routes: a plain list, no map.
        Column(Modifier.fillMaxSize()) {
            Segmented(
                PlanMode.entries.map { it.label }, PlanMode.entries.indexOf(mode), { switchMode(it) },
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            )
            RoutesScreen(vm, myLoc)
        }
        return
    }

    val option = vm.options.getOrNull(vm.selected)
    // In loop mode the only thing to pick is the start.
    val pick = if (mode == PlanMode.LOOP) Pick.START else vm.pick
    val startPt = vm.start?.pt ?: myLoc
    val abPoints = listOfNotNull(startPt) + vm.vias.map { it.pt } + listOfNotNull(vm.end?.pt)
    val showResults = vm.options.isNotEmpty()
    val startText = vm.start?.label ?: "My location"

    LaunchedEffect(myLoc) {
        if (!centred && myLoc != null && vm.options.isEmpty() && vm.end == null && vm.start == null) {
            camera = CameraCmd(listOf(myLoc), 13.5)
            centred = true
        }
    }
    LaunchedEffect(vm.options, vm.selected) {
        vm.options.getOrNull(vm.selected)?.let { camera = CameraCmd(it.pts); centred = true }
    }

    fun setPoint(p: Pt, label: String, target: Pick = pick) {
        val stop = Stop(p, label)
        when (target) {
            Pick.START -> { vm.start = stop; if (mode == PlanMode.AB) vm.pick = Pick.END }
            Pick.END -> vm.end = stop
            Pick.VIA -> { vm.vias = vm.vias + stop; vm.pick = Pick.END }
        }
        vm.options = emptyList()
        vm.error = null
    }

    val lines = remember(vm.options, vm.selected) {
        buildList {
            vm.options.forEachIndexed { i, o -> if (i != vm.selected) add(MapLine(o.pts, COLOR_ALT, 4f)) }
            vm.options.getOrNull(vm.selected)?.let { add(MapLine(it.pts, COLOR_ROUTE, 6f)) }
        }
    }
    val dots = remember(myLoc, vm.start, vm.end, vm.vias, mode) {
        buildList {
            myLoc?.let { add(MapDot(it, COLOR_ME, 7f)) }
            vm.start?.let { add(MapDot(it.pt, "#2E7D32", 9f)) }
            if (mode == PlanMode.AB) {
                vm.vias.forEach { add(MapDot(it.pt, "#FB8C00", 7f)) }
                vm.end?.let { add(MapDot(it.pt, "#D32F2F", 9f)) }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CyclerMap(
                Modifier.fillMaxSize(), vm.mapStyle, lines, dots, camera,
                initial = vm.prefs.lastLoc,
                onLongPress = { p -> setPoint(p, "Pin on map"); camera = null },
            )
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Surface(
                    shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Cy.colors.border), shadowElevation = 2.dp,
                ) {
                    Segmented(PlanMode.entries.map { it.label }, PlanMode.entries.indexOf(mode), { switchMode(it) }, Modifier.fillMaxWidth())
                }
                if (!showResults) {
                    Spacer(Modifier.height(8.dp))
                    MapChip {
                        Icon(Icons.Outlined.TouchApp, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when (pick) {
                                Pick.START -> "Long-press the map to set the start"
                                Pick.END -> "Long-press the map to set where you're going"
                                Pick.VIA -> "Long-press the map to add a stop"
                            },
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            LayerButton(vm.mapStyle, Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                vm.mapStyle = it; vm.prefs.mapStyle = it
            }
        }

        Sheet {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 20.dp),
            ) {
                if (showResults) {
                    val wind = if (mode != PlanMode.AB) null else option?.pts?.let { p ->
                        if (p.size >= 2 && Geo.dist(p.first(), p.last()) > 1000) vm.weather?.feel(Geo.bearing(p.first(), p.last())) else null
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            SectionLabel(if (mode == PlanMode.AB) "Route" else "Loops")
                            Text(
                                if (mode == PlanMode.AB) startText.substringBefore(",") + "  →  " + (vm.end?.label?.substringBefore(",") ?: "")
                                else "From ${startText.substringBefore(",")}",
                                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (wind != null) Text(wind, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        CyButton("Edit", { vm.options = emptyList() }, kind = Btn.SECONDARY, height = 36.dp)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        vm.options.forEachIndexed { i, o ->
                            val sel = i == vm.selected
                            Column(
                                Modifier.width(164.dp).clip(RoundedCornerShape(12.dp))
                                    .background(if (sel) Cy.colors.accentSoft else Color.Transparent)
                                    .border(if (sel) 1.5.dp else 1.dp, if (sel) Cy.colors.accent else Cy.colors.border, RoundedCornerShape(12.dp))
                                    .selectable(selected = sel, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = { vm.selected = i }).padding(14.dp),
                            ) {
                                Text(
                                    o.label.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(Fmt.km(o.distanceM), style = MaterialTheme.typography.titleLarge.tabular)
                                Text(
                                    "↑ ${o.ascentM.toInt()} m · ${Fmt.dur(o.timeS)}", style = MaterialTheme.typography.bodySmall.tabular,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    DifficultyTag(Difficulty.of(o.distanceM, o.ascentM))
                                    o.tags.firstOrNull()?.let { Tag(it) }
                                }
                            }
                        }
                    }
                    if (option != null) {
                        Spacer(Modifier.height(16.dp))
                        ElevationChart(option.pts)
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CyButton("Ride it now", { vm.rideSelected() }, Modifier.weight(1f), height = 50.dp)
                            CyButton("Save route", { showSave = true }, Modifier.weight(1f), Btn.SECONDARY, height = 50.dp)
                        }
                    }
                } else if (mode == PlanMode.AB) {
                    Text("Where are you riding?", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // The trip as one box: start on top, any stops, end at the bottom.
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).border(1.dp, Cy.colors.border, RoundedCornerShape(12.dp))) {
                            Field(
                                GREEN, startText, vm.start == null,
                                if (vm.start == null && myLoc == null) "waiting for GPS" else null,
                                onClick = { searchFor = Pick.START },
                                onClear = if (vm.start != null) ({ vm.start = null }) else null,
                            )
                            vm.vias.forEachIndexed { i, v ->
                                Hairline()
                                Field(ORANGE, v.label, false, null, onClick = {}, onClear = { vm.vias = vm.vias.filterIndexed { k, _ -> k != i } })
                            }
                            Hairline()
                            Field(
                                RED, vm.end?.label ?: "Search or pick a destination", vm.end == null, null,
                                onClick = { searchFor = Pick.END },
                                onClear = if (vm.end != null) ({ vm.end = null }) else null,
                            )
                        }
                        IconButton(
                            {
                                val oldStart = vm.start ?: myLoc?.let { Stop(it, "My location") }
                                vm.start = vm.end
                                vm.end = oldStart
                                vm.vias = vm.vias.reversed()
                            },
                            enabled = vm.end != null,
                        ) { Icon(Icons.Outlined.SwapVert, "Swap start and end") }
                    }
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "+ Add a stop", style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.clip(Radius).clickable { searchFor = Pick.VIA }.padding(vertical = 10.dp, horizontal = 4.dp),
                        )
                        Spacer(Modifier.weight(1f))
                        if (vm.end != null || vm.start != null || vm.vias.isNotEmpty()) {
                            Text(
                                "Clear", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clip(Radius).clickable { vm.clearPlan() }.padding(vertical = 10.dp, horizontal = 4.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    CyButton(
                        "Find routes", { vm.findRoutes(abPoints) }, Modifier.fillMaxWidth(),
                        enabled = startPt != null && vm.end != null && !vm.busy, height = 52.dp,
                    )
                } else {
                    Text("Plan a loop", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(14.dp))
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, Cy.colors.border, RoundedCornerShape(12.dp))) {
                        Field(
                            GREEN, startText, vm.start == null,
                            if (vm.start == null && myLoc == null) "waiting for GPS" else "start and finish",
                            onClick = { searchFor = Pick.START },
                            onClear = if (vm.start != null) ({ vm.start = null }) else null,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        SectionLabel("Distance", Modifier.weight(1f).padding(bottom = 8.dp))
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
                    // Common distances, one tap each.
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(10, 20, 40, 60, 100).forEach { km ->
                            Pill("$km km", vm.loopKm.roundToInt() == km) { vm.loopKm = km.toFloat() }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    CyButton(
                        "Suggest loops", { startPt?.let { vm.findLoops(it) } }, Modifier.fillMaxWidth(),
                        enabled = startPt != null && !vm.busy, height = 52.dp,
                    )
                }

                if (vm.busy) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cy.colors.accent, trackColor = Cy.colors.subtle)
                    Text("Working out routes…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
                vm.error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = Cy.colors.danger, style = MaterialTheme.typography.bodySmall)
                    androidx.compose.material3.TextButton({ vm.retryPlan?.invoke() }, enabled = !vm.busy) { Text("Retry") }
                }
            }
        }
    }

    searchFor?.let { target ->
        PlaceSearch(
            title = when (target) {
                Pick.START -> "Choose a start"
                Pick.END -> "Where to?"
                Pick.VIA -> "Add a stop"
            },
            near = myLoc ?: vm.prefs.lastLoc,
            offerMyLocation = target == Pick.START,
            onMyLocation = { vm.start = null; vm.options = emptyList(); searchFor = null },
            onPickOnMap = { if (mode == PlanMode.AB) vm.pick = target; searchFor = null },
            onPlace = { place ->
                setPoint(place.pt, place.label, target)
                camera = CameraCmd(listOf(place.pt), 14.0)
                centred = true
                searchFor = null
            },
            onClose = { searchFor = null },
        )
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

/** One line of the trip box: a coloured dot and the place, or a prompt to choose one. */
@Composable
private fun Field(color: Color, text: String, placeholder: Boolean, note: String?, onClick: () -> Unit, onClear: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable(onClick = onClick).padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (placeholder && text != "My location") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onClear != null) {
            IconButton(onClear) { Icon(Icons.Outlined.Close, "Clear", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else Spacer(Modifier.width(14.dp))
    }
}

/** A full-screen place search, so typing has the whole screen rather than a sliver above the map. */
@Composable
private fun PlaceSearch(
    title: String, near: Pt?, offerMyLocation: Boolean,
    onMyLocation: () -> Unit, onPickOnMap: () -> Unit, onPlace: (Photon.Place) -> Unit, onClose: () -> Unit,
) {
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var places by remember { mutableStateOf(listOf<Photon.Place>()) }
    var searching by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // Search shortly after typing stops.
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 3) { places = emptyList(); note = null; return@LaunchedEffect }
        delay(450)
        searching = true
        val res = withContext(Dispatchers.IO) { runCatching { Photon.search(q, near) } }
        places = res.getOrDefault(emptyList())
        note = when {
            res.isFailure -> "Search isn't reachable. Check your connection."
            places.isEmpty() -> "Nothing found for \"$q\""
            else -> null
        }
        searching = false
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.padding(start = 4.dp, top = 6.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                TextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text(title) }, singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {}),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
                if (query.isNotEmpty()) {
                    IconButton({ query = "" }) { Icon(Icons.Outlined.Close, "Clear", Modifier.size(20.dp)) }
                }
            }
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cy.colors.accent, trackColor = Cy.colors.subtle)
            else Hairline()
            LazyColumn(Modifier.fillMaxSize()) {
                if (offerMyLocation) item { Shortcut(Icons.Outlined.MyLocation, "Use my location", onMyLocation) }
                item { Shortcut(Icons.Outlined.TouchApp, "Pick on the map", onPickOnMap) }
                note?.let { n ->
                    item {
                        Text(
                            n, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
                items(places) { pl ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPlace(pl) }.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Place, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(pl.label.substringBefore(","), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val rest = pl.label.substringAfter(",", "").trim()
                            if (rest.isNotEmpty()) {
                                Text(
                                    rest, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Hairline(Modifier.padding(start = 54.dp))
                }
            }
        }
    }
}

@Composable
private fun Shortcut(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = Cy.colors.accent)
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
    Hairline(Modifier.padding(start = 54.dp))
}
