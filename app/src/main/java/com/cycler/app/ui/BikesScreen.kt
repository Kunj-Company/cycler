package com.cycler.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cycler.app.MainVm
import com.cycler.app.data.Bike
import com.cycler.app.data.BikeList
import com.cycler.app.data.RouteType
import com.cycler.app.data.reassignBike

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

/** Experimental: your bikes with odometer, rides and time. Add, rename, set the default, retire or delete. */
@Composable
fun BikesScreen(vm: MainVm, onClose: () -> Unit) {
    val metas by vm.store.metas.collectAsState()
    val rides = remember(metas) { metas.filter { it.type == RouteType.RECORDED } }
    // null: the list; NEW: the add form; otherwise the id of the bike being edited.
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    if (open != null) {
        BackHandler { open = null }
        BikeForm(vm, if (open == NEW_BIKE) null else open) { open = null }
        return
    }
    BackHandler { onClose() }
    val list = vm.bikes
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.padding(start = 6.dp, top = 2.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ onClose() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text("Bikes", style = MaterialTheme.typography.titleLarge)
                    Text("Experimental", style = MaterialTheme.typography.labelSmall, color = Cy.colors.accent)
                }
                IconButton({ open = NEW_BIKE }) { Icon(Icons.Outlined.Add, "Add a bike") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(
                    "Tell each ride which bike it was on. Kilometres towards a bike job count only that bike's rides. " +
                        "Rides from before you added a bike count for ${list.find(list.homeId)?.name ?: "your first bike"}.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                list.bikes.forEach { b ->
                    Hairline(Modifier.padding(horizontal = 20.dp))
                    val t = list.totals(b.id, rides)
                    Column(Modifier.fillMaxWidth().clickable { open = b.id }.padding(horizontal = 20.dp, vertical = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(b.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (b.id == list.defaultId && list.multi) { Spacer(Modifier.width(8.dp)); Tag("Default") }
                            if (b.retired) { Spacer(Modifier.width(8.dp)); Tag("Retired") }
                        }
                        if (b.type.isNotBlank()) Text(b.type, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Metric("Odometer", "%,.0f".format(t.odometerKm), unit = "km")
                            Metric("Rides", t.rides.toString())
                            Metric("Time", Fmt.dur(t.timeS))
                        }
                    }
                }
                Hairline(Modifier.padding(horizontal = 20.dp))
                Column(Modifier.padding(20.dp)) {
                    CyButton("Add a bike", { open = NEW_BIKE }, kind = Btn.SECONDARY, icon = Icons.Outlined.Add)
                }
            }
        }
    }
}

private const val NEW_BIKE = "new"

@Composable
private fun BikeForm(vm: MainVm, id: String?, onClose: () -> Unit) {
    val list = vm.bikes
    val bike: Bike? = id?.let { list.find(it) }
    if (id != null && bike == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    var name by rememberSaveable(id) { mutableStateOf(bike?.name ?: "") }
    var type by rememberSaveable(id) { mutableStateOf(bike?.type ?: "") }
    var notes by rememberSaveable(id) { mutableStateOf(bike?.notes ?: "") }
    var start by rememberSaveable(id) { mutableStateOf(if ((bike?.startKm ?: 0) > 0) bike!!.startKm.toString() else "") }
    var confirmDelete by remember { mutableStateOf(false) }
    val startKm = start.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val valid = name.isNotBlank()

    fun save() {
        if (!valid) return
        vm.saveBikes(if (bike == null) list.add(name, type, notes, startKm) else list.update(bike.id, name, type, notes, startKm))
        if (bike == null) vm.stat(com.cycler.app.analytics.Feature.BIKE_ADDED)
    }
    fun close() { if (bike != null) save(); onClose() }
    BackHandler { close() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.padding(start = 6.dp, top = 2.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ close() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Text(if (bike == null) "New bike" else bike.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                SectionLabel("Name")
                OutlinedTextField(
                    name, { name = it.take(40) }, singleLine = true, shape = Radius, isError = name.isBlank() && bike != null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                Spacer(Modifier.height(18.dp))
                SectionLabel("Type, optional")
                OutlinedTextField(
                    type, { type = it.take(40) }, singleLine = true, shape = Radius,
                    placeholder = { Text("Road, gravel, commuter…") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                Spacer(Modifier.height(18.dp))
                SectionLabel("Starting odometer, km, optional")
                OutlinedTextField(
                    start, { start = it.filter(Char::isDigit).take(7) }, singleLine = true, shape = Radius,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                Spacer(Modifier.height(18.dp))
                SectionLabel("Notes, optional")
                OutlinedTextField(
                    notes, { notes = it.take(500) }, shape = Radius, minLines = 2, maxLines = 5,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                if (bike != null) {
                    Spacer(Modifier.height(26.dp))
                    SectionLabel("Manage")
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!bike.retired && bike.id != list.defaultId) {
                            CyButton("Use for new rides", { save(); vm.saveBikes(vm.bikes.setDefault(bike.id)) }, Modifier.fillMaxWidth(), Btn.SECONDARY)
                        }
                        if (bike.retired) {
                            CyButton("Bring back", { vm.saveBikes(vm.bikes.unretire(bike.id)) }, Modifier.fillMaxWidth(), Btn.SECONDARY)
                        } else if (list.canRetire(bike.id)) {
                            CyButton("Retire this bike", { save(); vm.saveBikes(vm.bikes.retire(bike.id)); onClose() }, Modifier.fillMaxWidth(), Btn.SECONDARY)
                            Text(
                                "Retiring hides it from the bike choices. Its rides and jobs stay as they are.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (list.multi) CyButton("Delete this bike…", { confirmDelete = true }, Modifier.fillMaxWidth(), Btn.GHOST)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            if (bike == null) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    CyButton("Add bike", { save(); onClose() }, Modifier.fillMaxWidth(), enabled = valid, height = 52.dp)
                }
            }
        }
    }

    val del = if (confirmDelete && bike != null) list.delete(bike.id) else null
    if (del != null && bike != null) {
        val to = del.list.find(del.movedTo)?.name ?: "your other bike"
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            shape = RadiusLarge,
            title = { Text("Delete ${bike.name}?", style = MaterialTheme.typography.titleLarge) },
            text = { Text("Your rides are kept: rides and jobs on this bike move to $to. To keep them apart, retire the bike instead.") },
            confirmButton = {
                TextButton({
                    vm.store.reassignBike(bike.id, del.movedTo)
                    vm.saveCare(vm.care.reassignBike(bike.id, del.movedTo))
                    vm.saveBikes(del.list)
                    // The filter must not point at a bike that is gone.
                    if (vm.rideFilter.bikeId == bike.id) vm.rideFilter = vm.rideFilter.copy(bikeId = null)
                    confirmDelete = false
                    onClose()
                }) { Text("Delete and move rides", color = Cy.colors.danger) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        )
    }
}
