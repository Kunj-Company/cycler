package com.cycler.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cycler.app.MainVm
import com.cycler.app.Tab
import com.cycler.app.data.Geo
import com.cycler.app.data.Gpx
import com.cycler.app.data.Route
import com.cycler.app.data.RouteType
import com.cycler.app.net.BRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RoutesScreen(vm: MainVm) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val metas by vm.store.metas.collectAsState()
    var filter by remember { mutableStateOf<RouteType?>(null) }

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

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Routes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton({ importGpx.launch(arrayOf("*/*")) }) { Text("Import GPX") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(filter == null, { filter = null }, { Text("All") })
            FilterChip(filter == RouteType.RECORDED, { filter = RouteType.RECORDED }, { Text("Rides") })
            FilterChip(filter == RouteType.PLANNED, { filter = RouteType.PLANNED }, { Text("Planned") })
        }
        val shown = metas.filter { filter == null || it.type == filter }
        if (shown.isEmpty()) {
            Text(
                "Nothing here yet. Record a ride from the Ride tab or plan one from the Plan tab.",
                Modifier.padding(top = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 10.dp),
        ) {
            items(shown, key = { it.id }) { m ->
                Card(onClick = { vm.detailId = m.id }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (m.type == RouteType.RECORDED) Icons.AutoMirrored.Filled.DirectionsBike else Icons.Filled.Route,
                            contentDescription = if (m.type == RouteType.RECORDED) "Recorded ride" else "Planned route",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${Fmt.km(m.distanceM)} · ↑ ${m.ascentM.toInt()} m · ${Fmt.dur(m.durationS)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                Fmt.date(m.createdAt), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RouteDetailScreen(vm: MainVm, id: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val metas by vm.store.metas.collectAsState()
    val meta = metas.firstOrNull { it.id == id }
    val route by produceState<Route?>(null, id) { value = withContext(Dispatchers.IO) { vm.store.load(id) } }
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

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

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ vm.detailId = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text(
                    meta?.name ?: "Route", style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                IconButton({ rename = true }) { Icon(Icons.Filled.Edit, "Rename") }
                IconButton({ confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
            }
            val r = route
            val lines = remember(r) {
                listOfNotNull(r?.let { MapLine(it.pts, if (it.meta.type == RouteType.RECORDED) COLOR_TRACK else COLOR_ROUTE, 5f) })
            }
            val dots = remember(r) {
                if (r == null || r.pts.isEmpty()) emptyList()
                else listOf(MapDot(r.pts.first(), "#2E7D32", 7f), MapDot(r.pts.last(), "#D32F2F", 7f))
            }
            val camera = remember(r) { r?.let { CameraCmd(it.pts) } }
            CyclerMap(
                Modifier.weight(1f).fillMaxWidth(), vm.mapStyle, lines, dots, camera,
                initial = meta?.let { com.cycler.app.data.Pt(it.startLat, it.startLon) },
            )
            if (meta != null) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Stat("distance", Fmt.km(meta.distanceM))
                        Stat("climb", "${meta.ascentM.toInt()} m")
                        Stat(if (meta.type == RouteType.RECORDED) "moving time" else "est. time", Fmt.dur(meta.durationS))
                        if (meta.type == RouteType.RECORDED && meta.durationS > 0) {
                            Stat("avg km/h", Fmt.kmh(meta.distanceM / meta.durationS))
                        }
                    }
                    Text(
                        (if (meta.type == RouteType.RECORDED) "Ridden " else "Planned ") + Fmt.date(meta.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (r != null) {
                        Spacer(Modifier.height(10.dp))
                        ElevationChart(r.pts)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            { vm.guide = r; vm.detailId = null; vm.tab = Tab.RIDE },
                            enabled = r != null, modifier = Modifier.weight(1f),
                        ) { Text("Ride this route") }
                        OutlinedButton(
                            { export.launch(meta.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "route" } + ".gpx") },
                            enabled = r != null, modifier = Modifier.weight(1f),
                        ) { Text("Export GPX") }
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
            title = { Text("Delete this route?") },
            text = { Text("It will be removed from this phone for good.") },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    if (vm.guide?.meta?.id == id) vm.guide = null
                    vm.detailId = null
                    vm.store.delete(id)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun SettingsScreen(vm: MainVm) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        Text("Bike type", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 18.dp))
        Text(
            "Decides which roads and tracks route planning prefers.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BRouter.profiles.forEach { (key, label) ->
            Choice(label, vm.profile == key) { vm.profile = key; vm.prefs.profile = key }
        }

        Text("Map style", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 18.dp))
        MapStyles.all.forEach { (key, label) ->
            Choice(label, vm.mapStyle == key) { vm.mapStyle = key; vm.prefs.mapStyle = key }
        }

        Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Keep screen on while riding", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Recording continues with the screen off either way.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(vm.keepScreenOn, { vm.keepScreenOn = it; vm.prefs.keepScreenOn = it })
        }

        Text("About", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
        Text(
            "Cycler keeps everything on this phone: no account, no cloud. " +
                "Maps and routing need an internet connection; recording a ride does not.\n\n" +
                "Map data © OpenStreetMap contributors. Map tiles by OpenFreeMap and CyclOSM. " +
                "Routing and elevation by BRouter. Place search by Photon (Komoot).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}
