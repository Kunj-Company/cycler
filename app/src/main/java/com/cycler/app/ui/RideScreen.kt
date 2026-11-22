package com.cycler.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cycler.app.MainVm
import com.cycler.app.data.Geo
import com.cycler.app.data.Pt
import com.cycler.app.track.TrackStatus
import com.cycler.app.track.TrackingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update

const val COLOR_TRACK = "#E53935"
const val COLOR_GUIDE = "#7E57C2"
const val COLOR_ROUTE = "#1565C0"
const val COLOR_ALT = "#90A4AE"
const val COLOR_ME = "#1E88E5"

@Composable
fun NameDialog(title: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton({ onConfirm(name.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun RideScreen(vm: MainVm, myLoc: Pt?, granted: Boolean, requestPermission: () -> Unit) {
    val ctx = LocalContext.current
    val st by TrackingService.state.collectAsStateWithLifecycle()
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
    LaunchedEffect(st.savedId) {
        val id = st.savedId ?: return@LaunchedEffect
        TrackingService.state.update { it.copy(savedId = null) }
        vm.guide = null
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
            if (st.points.size >= 2) add(MapLine(st.points, COLOR_TRACK, 5f))
        }
    }
    val dots = remember(here) { listOfNotNull(here?.let { MapDot(it, COLOR_ME, 8f) }) }
    val climb = remember(st.points.size / 10) { Geo.ascent(st.points, 4.0, smooth = true) }

    Box(Modifier.fillMaxSize()) {
        CyclerMap(
            Modifier.fillMaxSize(), vm.mapStyle, lines, dots, camera,
            initial = vm.prefs.lastLoc,
            onUserMove = { follow = false },
        )

        if (guide != null) {
            Surface(
                Modifier.align(Alignment.TopCenter).padding(12.dp),
                shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 3.dp,
            ) {
                Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Following: ${guide.meta.name} · ${Fmt.km(guide.meta.distanceM)}",
                        style = MaterialTheme.typography.labelLarge, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    IconButton({ vm.guide = null }) { Icon(Icons.Filled.Close, "Stop following") }
                }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)) {
            SmallFloatingActionButton(
                onClick = { follow = true; zoomed = false },
                modifier = Modifier.align(Alignment.End),
            ) { Icon(Icons.Filled.MyLocation, "Centre on me") }
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    if (!active) {
                        if (!granted) {
                            Text("Cycler needs your location to show where you are and record rides.")
                            Spacer(Modifier.height(8.dp))
                            Button(requestPermission, Modifier.fillMaxWidth()) { Text("Allow location") }
                        } else {
                            Button(
                                onClick = {
                                    follow = true
                                    zoomed = false
                                    TrackingService.send(ctx, TrackingService.ACTION_START)
                                },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                            ) {
                                Icon(Icons.Filled.PlayArrow, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Start ride")
                            }
                        }
                    } else {
                        val avg = if (st.movingMs > 0) st.distanceM / (st.movingMs / 1000.0) else 0.0
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Stat("km", Fmt.kmNumber(st.distanceM), big = true)
                            Stat(if (st.status == TrackStatus.PAUSED) "paused" else "time", Fmt.clock(st.elapsedMs(now)), big = true)
                            Stat("km/h", Fmt.kmh(st.speedMs.toDouble()), big = true)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Stat("avg km/h", Fmt.kmh(avg))
                            Stat("moving", Fmt.clock(st.movingMs))
                            Stat("climb", "${climb.toInt()} m")
                        }
                        if (st.points.isEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Waiting for a GPS fix… best under open sky.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (st.status == TrackStatus.RECORDING) {
                                FilledTonalButton(
                                    { TrackingService.send(ctx, TrackingService.ACTION_PAUSE) },
                                    Modifier.weight(1f).height(48.dp),
                                ) { Icon(Icons.Filled.Pause, null); Spacer(Modifier.width(6.dp)); Text("Pause") }
                            } else {
                                FilledTonalButton(
                                    { TrackingService.send(ctx, TrackingService.ACTION_RESUME) },
                                    Modifier.weight(1f).height(48.dp),
                                ) { Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Resume") }
                            }
                            Button({ showFinish = true }, Modifier.weight(1f).height(48.dp)) {
                                Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("Finish")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFinish) {
        var name by remember { mutableStateOf(Fmt.rideName()) }
        AlertDialog(
            onDismissRequest = { showFinish = false },
            title = { Text("Finish ride") },
            text = {
                Column {
                    Text("${Fmt.km(st.distanceM)} in ${Fmt.clock(st.movingMs)} moving")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") })
                }
            },
            confirmButton = {
                TextButton({
                    showFinish = false
                    TrackingService.send(ctx, TrackingService.ACTION_STOP, name.trim().ifBlank { "Ride" })
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton({
                        showFinish = false
                        TrackingService.send(ctx, TrackingService.ACTION_DISCARD)
                    }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                    TextButton({ showFinish = false }) { Text("Keep riding") }
                }
            },
        )
    }
}
