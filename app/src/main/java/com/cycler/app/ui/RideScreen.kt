package com.cycler.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material.icons.outlined.TurnLeft
import androidx.compose.material.icons.outlined.TurnRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cycler.app.MainVm
import com.cycler.app.data.Geo
import com.cycler.app.data.Pt
import com.cycler.app.net.Weather
import com.cycler.app.track.TrackState
import com.cycler.app.track.TrackStatus
import com.cycler.app.track.TrackingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update

const val COLOR_TRACK = "#E5484D"
const val COLOR_GUIDE = "#2563EB"
const val COLOR_ROUTE = "#2563EB"
const val COLOR_ALT = "#9A9A95"
const val COLOR_ME = "#1C1C1A"

@Composable
fun WeatherChip(w: Weather.Now, modifier: Modifier = Modifier) {
    MapChip(modifier) {
        Text("${w.tempC.toInt()}°", style = MaterialTheme.typography.labelLarge.tabular)
        Spacer(Modifier.width(10.dp))
        Icon(Icons.Outlined.Air, "Wind", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(
            "${w.windKmh.toInt()} km/h ${w.windFrom}", style = MaterialTheme.typography.labelLarge.tabular,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (w.rainChance >= 20) {
            Spacer(Modifier.width(10.dp))
            Text(
                "Rain ${w.rainChance}%", style = MaterialTheme.typography.labelLarge.tabular,
                color = if (w.rainChance >= 50) Cy.colors.warn else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun RideScreen(vm: MainVm, myLoc: Pt?, granted: Boolean, requestPermission: () -> Unit) {
    val ctx = LocalContext.current
    val st by TrackingService.state.collectAsStateWithLifecycle()
    val metas by vm.store.metas.collectAsState()
    var follow by remember { mutableStateOf(vm.guide == null) }
    var zoomed by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<CameraCmd?>(null) }
    var showFinish by remember { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(1000); value = System.currentTimeMillis() }
    }
    val active = st.status != TrackStatus.IDLE
    val here = if (st.status == TrackStatus.RECORDING) st.points.lastOrNull() ?: myLoc else myLoc

    LaunchedEffect(here, follow) {
        if (follow && here != null) {
            camera = CameraCmd(listOf(here), if (zoomed) null else 16.0)
            zoomed = true
        }
    }
    LaunchedEffect(vm.guide) {
        val g = vm.guide
        if (g != null && !active) { follow = false; camera = CameraCmd(g.pts) }
    }
    LaunchedEffect(st.notice) {
        val msg = st.notice ?: return@LaunchedEffect
        TrackingService.state.update { it.copy(notice = null) }
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    }
    LaunchedEffect(st.savedId) {
        val id = st.savedId ?: return@LaunchedEffect
        TrackingService.state.update { it.copy(savedId = null) }
        vm.follow(null)
        vm.detailId = id
    }
    val view = LocalView.current
    DisposableEffect(active, vm.keepScreenOn) {
        view.keepScreenOn = active && vm.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    val guide = vm.guide
    val lines = remember(guide, st.points) {
        buildList {
            if (guide != null) add(MapLine(guide.pts, COLOR_GUIDE, 5f))
            if (st.points.size >= 2) {
                // On long rides, thin out the drawn line so redrawing every second stays cheap.
                val n = st.points.size
                val step = n / 1500 + 1
                val shown = if (step == 1) st.points else st.points.filterIndexed { i, _ -> i % step == 0 || i == n - 1 }
                add(MapLine(shown, COLOR_TRACK, 5f))
            }
        }
    }
    val climbs = remember(guide) {
        guide?.let { Geo.climbs(it.pts, Geo.cumulative(it.pts)) } ?: emptyList()
    }
    // Inside a climb, or the next one starting within a kilometre.
    val climbNote = if (!active) null else climbs.firstOrNull { st.guideDoneM < it.endM && it.startM - st.guideDoneM < 1000.0 }?.let { c ->
        if (st.guideDoneM >= c.startM) "Climbing · ${Fmt.km(c.endM - st.guideDoneM)} to the top"
        else "Climb in ${Fmt.km(c.startM - st.guideDoneM)} · ${Fmt.km(c.lengthM)} at ${c.gradePct.toInt()}%"
    }
    var big by remember { mutableStateOf(false) }
    val signals = vm.guideSignals
    val dots = remember(here, guide, signals) {
        buildList {
            signals.forEach { add(MapDot(it, "#E0A030", 4f)) }
            guide?.pts?.lastOrNull()?.let { add(MapDot(it, "#D4403A", 6f)) }
            here?.let { add(MapDot(it, COLOR_ME, 8f)) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        CyclerMap(
            Modifier.fillMaxSize(), vm.mapStyle, lines, dots, camera,
            initial = vm.prefs.lastLoc, initialZoom = 15.5,
            onUserMove = { follow = false },
        )

        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(12.dp)) {
            vm.weather?.let { WeatherChip(it) }
            if (guide != null) {
                Spacer(Modifier.height(8.dp))
                MapChip(Modifier.fillMaxWidth()) {
                    StatusDot(Cy.colors.accent)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(guide.meta.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (active) "${Fmt.km(((if (st.guideTotalM > 0) st.guideTotalM else guide.meta.distanceM) - st.guideDoneM).coerceAtLeast(0.0))} to go"
                            else "${Fmt.km(guide.meta.distanceM)} · ↑ ${guide.meta.ascentM.toInt()} m",
                            style = MaterialTheme.typography.bodySmall.tabular,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Outlined.Close, "Stop following",
                        Modifier.size(20.dp).clip(Radius).clickable { vm.follow(null) },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (active && st.nextTurn != null && !st.offRoute) {
                    Spacer(Modifier.height(8.dp))
                    MapChip {
                        Icon(
                            if (st.nextTurnLeft) Icons.Outlined.TurnLeft else Icons.Outlined.TurnRight, null,
                            Modifier.size(22.dp), tint = Cy.colors.accent,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(st.nextTurn ?: "", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "in ${Fmt.km(st.nextTurnM)}", style = MaterialTheme.typography.bodyMedium.tabular,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (climbNote != null) {
                    Spacer(Modifier.height(8.dp))
                    MapChip {
                        Icon(Icons.Outlined.Terrain, null, Modifier.size(18.dp), tint = Cy.colors.warn)
                        Spacer(Modifier.width(8.dp))
                        Text(climbNote, style = MaterialTheme.typography.labelLarge.tabular)
                    }
                }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Column(Modifier.align(Alignment.End).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LayerButton(vm.mapStyle) { vm.mapStyle = it; vm.prefs.mapStyle = it }
                MapChip(onClick = { follow = true; zoomed = false }) {
                    Icon(
                        Icons.Outlined.MyLocation, "Centre on me", Modifier.size(20.dp),
                        tint = if (follow) Cy.colors.accent else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Sheet {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                    if (!active) {
                        IdlePanel(vm, metas.filter { it.type == com.cycler.app.data.RouteType.RECORDED }, granted, requestPermission) {
                            follow = true
                            zoomed = false
                            TrackingService.send(ctx, TrackingService.ACTION_START)
                        }
                    } else {
                        RecordingPanel(vm, st, now, onBig = { big = true }, onFinish = { showFinish = true })
                    }
                }
            }
        }
    }

    if (big && active) {
        BigNumbers(vm, st, now, climbNote) { big = false }
    }

    if (showFinish) {
        var name by remember { mutableStateOf(Fmt.rideName()) }
        AlertDialog(
            onDismissRequest = { showFinish = false },
            shape = RadiusLarge,
            title = { Text("Finish ride", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    Text(
                        "${Fmt.km(st.distanceM)} · ${Fmt.clock(st.movingMs)} moving",
                        style = MaterialTheme.typography.bodyMedium.tabular,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, shape = Radius)
                }
            },
            confirmButton = {
                TextButton({
                    showFinish = false
                    TrackingService.send(ctx, TrackingService.ACTION_STOP, name.trim().ifBlank { "Ride" })
                }) { Text("Save ride") }
            },
            dismissButton = {
                Row {
                    TextButton({
                        showFinish = false
                        TrackingService.send(ctx, TrackingService.ACTION_DISCARD)
                    }) { Text("Discard", color = Cy.colors.danger) }
                    TextButton({ showFinish = false }) { Text("Keep riding", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            },
        )
    }
}

@Composable
private fun IdlePanel(
    vm: MainVm, rides: List<com.cycler.app.data.RouteMeta>, granted: Boolean,
    requestPermission: () -> Unit, onStart: () -> Unit,
) {
    if (!granted) {
        Text("Turn on location", style = MaterialTheme.typography.titleLarge)
        Text(
            "Cycler uses your location to show where you are and to record rides. It stays on this phone.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        CyButton("Allow location", requestPermission, Modifier.fillMaxWidth(), height = 52.dp)
        return
    }
    val weekKm = remember(rides) { Stats.since(rides, Stats.weekStart()).distanceM / 1000 }
    val goal = vm.weeklyGoalKm
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            SectionLabel("This week")
            Row(verticalAlignment = Alignment.Bottom) {
                Text(String.format(java.util.Locale.US, "%.1f", weekKm), style = MaterialTheme.typography.displaySmall)
                Text(
                    " / $goal km", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp),
                )
            }
        }
        if (weekKm >= goal) Tag("Goal reached", Cy.colors.success, filled = true)
    }
    Spacer(Modifier.height(8.dp))
    Progress((weekKm / goal).toFloat(), color = if (weekKm >= goal) Cy.colors.success else Cy.colors.accent)
    val sunset = vm.weather?.sunsetMs ?: 0L
    val minsLeft = (sunset - System.currentTimeMillis()) / 60000
    if (sunset > 0 && minsLeft < 120 && minsLeft > -360) {
        Text(
            if (minsLeft > 0) "Sunset in ${Fmt.dur(minsLeft * 60)}. Carry lights if you'll be out longer."
            else "It's after sunset. Ride with lights.",
            style = MaterialTheme.typography.bodySmall, color = Cy.colors.warn,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
    Spacer(Modifier.height(16.dp))
    CyButton(
        if (vm.guide != null) "Start and follow route" else "Start ride", onStart,
        Modifier.fillMaxWidth(), icon = Icons.Filled.PlayArrow, height = 52.dp,
    )
}

@Composable
private fun RecordingPanel(vm: MainVm, st: TrackState, now: Long, onBig: () -> Unit, onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val paused = st.status == TrackStatus.PAUSED
    val avg = if (st.movingMs > 0) st.distanceM / (st.movingMs / 1000.0) else 0.0
    val climb = remember(st.points.size / 10) { Geo.ascent(st.points, 4.0, smooth = true) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(
            when {
                paused -> MaterialTheme.colorScheme.onSurfaceVariant
                st.autoPaused -> Cy.colors.warn
                else -> Cy.colors.danger
            }
        )
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                paused -> "Paused"
                st.points.isEmpty() -> "Waiting for GPS"
                st.autoPaused -> "Auto-paused"
                else -> "Recording"
            },
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "Elapsed ${Fmt.clock(st.elapsedMs(now))}", style = MaterialTheme.typography.labelMedium.tabular,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Icon(
            Icons.Outlined.Fullscreen, "Big numbers",
            Modifier.size(24.dp).clip(Radius).clickable(onClick = onBig),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (st.gpsOff) {
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().clip(Radius).background(Cy.colors.warn.copy(alpha = 0.12f)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Location is switched off. Turn it on to keep recording.",
                style = MaterialTheme.typography.labelLarge, color = Cy.colors.warn,
            )
        }
    }
    if (st.offRoute) {
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().clip(Radius).background(Cy.colors.danger.copy(alpha = 0.10f)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Off route · ${st.guideOffM?.toInt() ?: 0} m away", style = MaterialTheme.typography.labelLarge,
                color = Cy.colors.danger,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Metric("Distance", Fmt.kmNumber(st.distanceM), Modifier.weight(1f), unit = "km", hero = true)
        Metric("Speed", Fmt.kmh(st.speedMs.toDouble()), unit = "km/h", hero = true, alignment = Alignment.End)
    }
    Spacer(Modifier.height(12.dp))
    Hairline()
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Metric(if (vm.autoPause) "Moving" else "Time", Fmt.clock(if (vm.autoPause) st.movingMs else st.elapsedMs(now)))
        Metric("Avg", Fmt.kmh(avg), unit = "km/h")
        Metric("Max", Fmt.kmh(st.maxSpeedMs.toDouble()), unit = "km/h")
        Metric("Climb", "${climb.toInt()}", unit = "m")
    }
    val g = vm.guide
    if (g != null && g.meta.distanceM > 0) {
        Spacer(Modifier.height(12.dp))
        Progress((st.guideDoneM / (if (st.guideTotalM > 0) st.guideTotalM else g.meta.distanceM)).toFloat())
    }
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (paused) {
            CyButton(
                "Resume", { TrackingService.send(ctx, TrackingService.ACTION_RESUME) },
                Modifier.weight(1f), Btn.SECONDARY, Icons.Filled.PlayArrow, height = 52.dp,
            )
        } else {
            CyButton(
                "Pause", { TrackingService.send(ctx, TrackingService.ACTION_PAUSE) },
                Modifier.weight(1f), Btn.SECONDARY, Icons.Filled.Pause, height = 52.dp,
            )
        }
        CyButton("Finish", onFinish, Modifier.weight(1f), Btn.PRIMARY, Icons.Filled.Stop, height = 52.dp)
    }
}

/** Three huge numbers, readable at a glance on the handlebar. Tap anywhere to go back. */
@Composable
private fun BigNumbers(vm: MainVm, st: TrackState, now: Long, climbNote: String?, onClose: () -> Unit) {
    androidx.compose.material3.Surface(
        Modifier.fillMaxSize().clickable(onClick = onClose), color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            if (st.offRoute) {
                Text("Off route · ${st.guideOffM?.toInt() ?: 0} m", style = MaterialTheme.typography.headlineMedium, color = Cy.colors.danger)
            } else if (st.nextTurn != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (st.nextTurnLeft) Icons.Outlined.TurnLeft else Icons.Outlined.TurnRight, null,
                        Modifier.size(44.dp), tint = Cy.colors.accent,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("${st.nextTurn} · ${Fmt.km(st.nextTurnM)}", style = MaterialTheme.typography.headlineMedium)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (st.autoPaused || st.status == TrackStatus.PAUSED) Cy.colors.warn else Cy.colors.danger, 12.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when {
                            st.status == TrackStatus.PAUSED -> "Paused"
                            st.autoPaused -> "Auto-paused"
                            else -> "Recording"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    Fmt.kmh(st.speedMs.toDouble()),
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = androidx.compose.ui.unit.TextUnit(128f, androidx.compose.ui.unit.TextUnitType.Sp),
                        lineHeight = androidx.compose.ui.unit.TextUnit(132f, androidx.compose.ui.unit.TextUnitType.Sp),
                    ),
                )
                Text("km/h", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Fmt.kmNumber(st.distanceM), style = MaterialTheme.typography.displayLarge)
                    Text("km", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        Fmt.clock(if (vm.autoPause) st.movingMs else st.elapsedMs(now)),
                        style = MaterialTheme.typography.displayLarge,
                    )
                    Text(
                        if (vm.autoPause) "moving" else "time", style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                climbNote ?: "Tap anywhere to go back", style = MaterialTheme.typography.bodyLarge,
                color = if (climbNote != null) Cy.colors.warn else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
