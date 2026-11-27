package com.cycler.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cycler.app.MainVm
import com.cycler.app.data.Bike
import com.cycler.app.data.BikeList
import com.cycler.app.data.Care
import com.cycler.app.data.RouteType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pick one bike with pills. Retired bikes are left out, unless [keep] is the bike the ride already has. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BikePicker(list: BikeList, selected: String, onSelect: (String) -> Unit, label: String = "Bike", keep: String? = null) {
    Column {
        SectionLabel(label)
        FlowRow(
            Modifier.padding(top = 4.dp).semantics { contentDescription = "Choose a bike" },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            list.bikes.filter { !it.retired || it.id == selected || it.id == keep }.forEach { b ->
                FilterPill(b.name + if (b.retired) " (retired)" else "", b.id == selected, { onSelect(b.id) })
            }
        }
    }
}

internal fun dayShort(t: Long): String = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(t))

/**
 * Your bikes: a card for each with odometer, rides, time and last ride. A bike opens its page (profile, totals,
 * recent rides, care jobs, actions). The rider with one bike can still name it and describe it here.
 */
@Composable
fun BikesScreen(vm: MainVm, onClose: () -> Unit) {
    val metas by vm.store.metas.collectAsState()
    val rides = remember(metas) { metas.filter { it.type == RouteType.RECORDED } }
    // Which page is open: the list (both null), a bike's page, or the form (NEW_BIKE or a bike id).
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var form by rememberSaveable { mutableStateOf<String?>(null) }
    if (form != null) {
        BackHandler { form = null }
        BikeForm(vm, if (form == NEW_BIKE) null else form) { savedAs ->
            form = null
            // A new bike opens on its own page.
            if (savedAs != null) detail = savedAs
        }
        return
    }
    if (detail != null) {
        BackHandler { detail = null }
        BikeDetail(vm, detail!!, rides, onEdit = { form = it }, onClose = { detail = null })
        return
    }
    BackHandler { onClose() }
    val list = vm.bikes
    val now = remember(metas) { System.currentTimeMillis() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.padding(start = 6.dp, top = 2.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ onClose() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Text("Bikes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton({ form = NEW_BIKE }) { Icon(Icons.Outlined.Add, "Add a bike") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(
                    if (list.multi) {
                        "Tell each ride which bike it was on. Kilometres towards a bike job count only that bike's rides. " +
                            "Rides from before you added a bike count for ${list.find(list.homeId)?.name ?: "your first bike"}."
                    } else {
                        "Name your bike and add its make and model. Add another bike when you have one: " +
                            "you will then pick the bike before each ride, and each bike keeps its own totals and care jobs."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                if (list.multi) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        SectionLabel("New rides use")
                        val def = list.find(list.defaultId)?.name ?: ""
                        Segmented(
                            listOf("Last used bike", "Always " + if (def.length > 12) def.take(11) + "…" else def),
                            if (vm.bikeNewRides == BikeList.MODE_DEFAULT) 1 else 0,
                            {
                                val m = if (it == 1) BikeList.MODE_DEFAULT else BikeList.MODE_LAST
                                vm.bikeNewRides = m
                                vm.prefs.bikeNewRides = m
                            },
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        Text(
                            "The Ride screen offers this bike first. You can change it before you start.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                list.bikes.forEach { b ->
                    Hairline(Modifier.padding(horizontal = 20.dp))
                    BikeCard(b, list, list.totals(b.id, rides, now)) { detail = b.id }
                }
                Hairline(Modifier.padding(horizontal = 20.dp))
                Column(Modifier.padding(20.dp)) {
                    CyButton("Add a bike", { form = NEW_BIKE }, kind = Btn.SECONDARY, icon = Icons.Outlined.Add)
                }
            }
        }
    }
}

internal const val NEW_BIKE = "new"

@Composable
private fun BikeCard(b: Bike, list: BikeList, t: com.cycler.app.data.BikeTotals, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BikeAvatar(b, 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(b.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (b.id == list.defaultId && list.multi) { Spacer(Modifier.width(8.dp)); Tag("Default") }
                    if (b.retired) { Spacer(Modifier.width(8.dp)); Tag("Retired") }
                }
                if (b.subtitle.isNotEmpty()) {
                    Text(b.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (b.type.isNotBlank()) { Spacer(Modifier.height(4.dp)); Tag(b.type) }
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.padding(start = 4.dp).size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric("Odometer", "%,.0f".format(t.odometerKm), unit = "km")
            Metric("Rides", t.rides.toString())
            Metric("Time", Fmt.dur(t.timeS))
        }
        Text(
            if (t.lastRideAt > 0) "Last ride ${dayShort(t.lastRideAt)}" else "No rides yet",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(150.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** One bike's page. */
@Composable
private fun BikeDetail(vm: MainVm, id: String, rides: List<com.cycler.app.data.RouteMeta>, onEdit: (String) -> Unit, onClose: () -> Unit) {
    val list = vm.bikes
    val bike = list.find(id)
    if (bike == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val now = remember(rides) { System.currentTimeMillis() }
    val t = remember(list, rides, id) { list.totals(id, rides, now) }
    val recent = remember(list, rides, id) { list.recentRides(id, rides, 5) }
    val jobs = remember(vm.care, rides, list, id) {
        Care.sorted(vm.care.filter { list.ownerOf(it.bikeId) == id }, rides, now, bikes = list)
    }
    var confirmDelete by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.padding(start = 6.dp, top = 2.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ onClose() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Text(bike.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton({ onEdit(id) }) { Icon(Icons.Outlined.Edit, "Edit ${bike.name}") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BikeAvatar(bike, 72.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        if (bike.subtitle.isNotEmpty()) Text(bike.subtitle, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (bike.type.isNotBlank()) Tag(bike.type)
                            if (bike.id == list.defaultId && list.multi) Tag("Default", Cy.colors.accent, filled = true)
                            if (bike.retired) Tag("Retired")
                        }
                    }
                }

                Spacer(Modifier.height(22.dp))
                SectionLabel("Totals")
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("Odometer", "%,.0f".format(t.odometerKm), unit = "km")
                    Metric("Rides", t.rides.toString())
                    Metric("Time", Fmt.dur(t.timeS))
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("Distance", "%,.0f".format(t.ridesKm), unit = "km")
                    Metric("Climb", "%,.0f".format(t.climbM), unit = "m")
                    Metric("Longest", "%,.0f".format(t.longestKm), unit = "km")
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("Avg speed", if (t.avgKmh > 0) "%.1f".format(Locale.US, t.avgKmh) else "–", unit = "km/h")
                    Metric("This year", "%,.0f".format(t.thisYearKm), unit = "km")
                    Metric("Last 30 days", "%,.0f".format(t.last30Km), unit = "km")
                }

                val info = buildList {
                    if (bike.year > 0) add("Year" to bike.year.toString())
                    if (bike.frameSize.isNotBlank()) add("Frame size" to bike.frameSize)
                    if (bike.weightKg > 0) add("Weight" to "%.1f kg".format(Locale.US, bike.weightKg))
                    if (bike.purchased > 0) add("Purchased" to dayShort(bike.purchased))
                    if (bike.serial.isNotBlank()) add("Serial number" to bike.serial)
                    if (bike.startKm > 0) add("Starting odometer" to "%,d km".format(bike.startKm))
                    if (bike.notes.isNotBlank()) add("Notes" to bike.notes)
                }
                if (info.isNotEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    SectionLabel("Profile")
                    Spacer(Modifier.height(4.dp))
                    info.forEach { (k, v) -> Hairline(); InfoRow(k, v) }
                    Hairline()
                }

                Spacer(Modifier.height(24.dp))
                SectionLabel("Recent rides")
                Spacer(Modifier.height(4.dp))
                if (recent.isEmpty()) {
                    Text("No rides on this bike yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                } else {
                    recent.forEach { r ->
                        Hairline()
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(Radius).clickable { vm.detailId = r.id }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(dayShort(r.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("${Fmt.kmShort(r.distanceM)} km", style = MaterialTheme.typography.bodyMedium.tabular, modifier = Modifier.padding(start = 12.dp))
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.padding(start = 4.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Hairline()
                }

                Spacer(Modifier.height(24.dp))
                SectionLabel("Bike care")
                Spacer(Modifier.height(4.dp))
                if (jobs.isEmpty()) {
                    Text("No jobs for this bike yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                } else {
                    jobs.forEach { (item, st) ->
                        Hairline()
                        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                Care.text(item, st, now), style = MaterialTheme.typography.bodySmall.tabular,
                                color = if (st.due) Cy.colors.warn else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Hairline()
                }
                Spacer(Modifier.height(10.dp))
                CyButton(
                    "Open bike care", { vm.careBike = id; vm.showCare = true },
                    Modifier.fillMaxWidth(), Btn.SECONDARY,
                )

                Spacer(Modifier.height(26.dp))
                SectionLabel("Manage")
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CyButton("Edit", { onEdit(id) }, Modifier.fillMaxWidth(), Btn.SECONDARY)
                    if (list.multi && !bike.retired && bike.id != list.defaultId) {
                        CyButton("Set as default bike", { vm.setDefaultBike(id) }, Modifier.fillMaxWidth(), Btn.SECONDARY)
                    }
                    if (bike.retired) {
                        CyButton("Bring back", { vm.saveBikes(vm.bikes.unretire(id)) }, Modifier.fillMaxWidth(), Btn.SECONDARY)
                    } else if (list.canRetire(id)) {
                        CyButton("Retire this bike", { vm.saveBikes(vm.bikes.retire(id)) }, Modifier.fillMaxWidth(), Btn.SECONDARY)
                        Text(
                            "Retiring hides it from the bike choices. Its rides and jobs stay as they are.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (list.multi) CyButton("Delete this bike…", { confirmDelete = true }, Modifier.fillMaxWidth(), Btn.GHOST)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    val del = if (confirmDelete) list.delete(id) else null
    if (del != null) {
        val to = del.list.find(del.movedTo)?.name ?: "your other bike"
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            shape = RadiusLarge,
            title = { Text("Delete ${bike.name}?", style = MaterialTheme.typography.titleLarge) },
            text = { Text("Your rides are kept: rides and jobs on this bike move to $to. Its photo is removed. To keep them apart, retire the bike instead.") },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    vm.deleteBike(id)
                    onClose()
                }) { Text("Delete and move rides", color = Cy.colors.danger) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        )
    }
}
