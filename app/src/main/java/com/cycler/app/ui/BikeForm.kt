package com.cycler.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cycler.app.MainVm
import com.cycler.app.data.Bike
import com.cycler.app.data.BikeList
import com.cycler.app.data.BikePhotos
import com.cycler.app.data.BikeTypes
import com.cycler.app.data.copyJobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Add a bike (name first, everything else optional) or edit one. Editing saves when you go back; adding needs the
 * button. [onClose] gets the new bike's id after an add, else null.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BikeForm(vm: MainVm, id: String?, onClose: (String?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list = vm.bikes
    val bike: Bike? = id?.let { list.find(it) }
    if (id != null && bike == null) {
        LaunchedEffect(Unit) { onClose(null) }
        return
    }
    // A new bike's id is fixed up front so a picture picked now has its final file name.
    val bikeId = rememberSaveable(id) { id ?: list.newId() }
    var name by rememberSaveable(id) { mutableStateOf(bike?.name ?: "") }
    var make by rememberSaveable(id) { mutableStateOf(bike?.make ?: "") }
    var model by rememberSaveable(id) { mutableStateOf(bike?.model ?: "") }
    var type by rememberSaveable(id) { mutableStateOf(bike?.type ?: "") }
    var year by rememberSaveable(id) { mutableStateOf(if ((bike?.year ?: 0) > 0) bike!!.year.toString() else "") }
    var frame by rememberSaveable(id) { mutableStateOf(bike?.frameSize ?: "") }
    var colour by rememberSaveable(id) { mutableStateOf(bike?.colour ?: "") }
    var purchased by rememberSaveable(id) { mutableStateOf(bike?.purchased ?: 0L) }
    var serial by rememberSaveable(id) { mutableStateOf(bike?.serial ?: "") }
    var weight by rememberSaveable(id) { mutableStateOf(if ((bike?.weightKg ?: 0.0) > 0) "%.1f".format(Locale.US, bike!!.weightKg).removeSuffix(".0") else "") }
    var start by rememberSaveable(id) { mutableStateOf(if ((bike?.startKm ?: 0) > 0) bike!!.startKm.toString() else "") }
    var notes by rememberSaveable(id) { mutableStateOf(bike?.notes ?: "") }
    // For a new bike: 0 no jobs, 1 the standard jobs, 2 and up copy the jobs of another bike.
    var careChoice by rememberSaveable { mutableIntStateOf(0) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var datePicker by rememberSaveable { mutableStateOf(false) }
    var photoTick by remember { mutableIntStateOf(0) }
    val now = remember { System.currentTimeMillis() }

    val yearN = year.toIntOrNull()
    val yearOk = year.isEmpty() || (yearN != null && yearN in Bike.Limits.MIN_YEAR..Bike.Limits.maxYear(now))
    val weightN = weight.replace(',', '.').toDoubleOrNull()
    val weightOk = weight.isEmpty() || (weightN != null && weightN in Bike.Limits.MIN_WEIGHT..Bike.Limits.MAX_WEIGHT)
    val valid = name.isNotBlank() && yearOk && weightOk
    val hasPhoto = remember(photoTick) { BikePhotos.file(ctx, bikeId)?.exists() == true }

    fun draft() = Bike(
        bikeId, name, type, notes, start.toIntOrNull() ?: 0, bike?.retired ?: false, make, model,
        if (yearOk) yearN ?: 0 else 0, frame, colour, hasPhoto, purchased, serial, if (weightOk) weightN ?: 0.0 else 0.0,
    )

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) { BikePhotos.import(ctx, uri, bikeId) }
            photoTick++
        }
    }

    fun save() {
        if (bike == null) return
        vm.saveBikes(vm.bikes.edit(bike.id, draft()))
    }
    fun add() {
        if (!valid || bike != null) return
        val before = vm.bikes
        val after = before.add(draft())
        vm.saveBikes(after)
        val copyFrom = before.bikes.filter { b -> vm.care.any { before.ownerOf(it.bikeId) == b.id } }.getOrNull(careChoice - 2)
        when {
            careChoice == 1 -> vm.saveCare(vm.care + BikeList.standardJobs(bikeId))
            careChoice >= 2 && copyFrom != null -> vm.saveCare(vm.care + vm.care.copyJobs(before, copyFrom.id, bikeId))
        }
        onClose(bikeId)
    }
    fun close() {
        if (bike != null) save() else BikePhotos.remove(ctx, bikeId)
        onClose(null)
    }
    BackHandler { close() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.padding(start = 6.dp, top = 2.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ close() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Text(if (bike == null) "New bike" else "Edit bike", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                SectionLabel("Name")
                OutlinedTextField(
                    name, { name = it.take(Bike.Limits.NAME) }, singleLine = true, shape = Radius, isError = name.isBlank() && bike != null,
                    placeholder = { Text("Commuter, Sunday bike…") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        SectionLabel("Make, optional")
                        OutlinedTextField(
                            make, { make = it.take(Bike.Limits.NAME) }, singleLine = true, shape = Radius,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        SectionLabel("Model, optional")
                        OutlinedTextField(
                            model, { model = it.take(Bike.Limits.NAME) }, singleLine = true, shape = Radius,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                SectionLabel("Type, optional")
                FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // A type typed by hand in an earlier version stays selectable.
                    val types = if (type.isNotBlank() && type !in BikeTypes.all) listOf(type) + BikeTypes.all else BikeTypes.all
                    types.forEach { t -> FilterPill(t, t == type, { type = if (t == type) "" else t }) }
                }
                val suggested = BikeTypes.profileFor(type)
                if (suggested != null) {
                    Text(
                        "Route planning will start from the ${com.cycler.app.net.BRouter.profiles.firstOrNull { it.first == suggested }?.second ?: suggested} profile when you ride this bike.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                Spacer(Modifier.height(22.dp))
                SectionLabel("Colour and photo")
                Spacer(Modifier.height(6.dp))
                ColourSwatches(colour) { colour = it }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    key(photoTick) { BikeAvatar(draft().copy(name = name.ifBlank { "Bike" }), 56.dp) }
                    Spacer(Modifier.width(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CyButton(
                            if (hasPhoto) "Change photo" else "Add a photo",
                            { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            kind = Btn.SECONDARY, icon = Icons.Outlined.AddPhotoAlternate,
                        )
                        if (hasPhoto) CyButton("Remove photo", { BikePhotos.remove(ctx, bikeId); photoTick++ }, kind = Btn.GHOST)
                    }
                }
                Text(
                    "The photo is copied onto this phone and shrunk. It leaves only in your own backup.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )

                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        SectionLabel("Year, optional")
                        OutlinedTextField(
                            year, { year = it.filter(Char::isDigit).take(4) }, singleLine = true, shape = Radius, isError = !yearOk,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            supportingText = if (!yearOk) ({ Text("1900 to ${Bike.Limits.maxYear(now)}") }) else null,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        SectionLabel("Frame size, optional")
                        OutlinedTextField(
                            frame, { frame = it.take(Bike.Limits.SHORT) }, singleLine = true, shape = Radius,
                            placeholder = { Text("54 cm, M") },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        SectionLabel("Weight, kg, optional")
                        OutlinedTextField(
                            weight, { weight = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(5) }, singleLine = true, shape = Radius, isError = !weightOk,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            supportingText = if (!weightOk) ({ Text("1 to 100 kg") }) else null,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        SectionLabel("Starting odometer, km")
                        OutlinedTextField(
                            start, { start = it.filter(Char::isDigit).take(7) }, singleLine = true, shape = Radius,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                }
                Text(
                    "The weight is added to yours for the energy estimate on a ride.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(18.dp))
                SectionLabel("Purchase date, optional")
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CyButton(if (purchased > 0) dayShort(purchased) else "Choose a date", { datePicker = true }, kind = Btn.SECONDARY)
                    if (purchased > 0) CyButton("Clear", { purchased = 0L }, kind = Btn.GHOST)
                }
                Spacer(Modifier.height(18.dp))
                SectionLabel("Serial number, optional")
                OutlinedTextField(
                    serial, { serial = it.take(Bike.Limits.NAME) }, singleLine = true, shape = Radius,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                Text("Stays on this phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(18.dp))
                SectionLabel("Notes, optional")
                OutlinedTextField(
                    notes, { notes = it.take(Bike.Limits.NOTES) }, shape = Radius, minLines = 2, maxLines = 5,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )

                if (bike == null) {
                    // Offer the care jobs a new bike can start with.
                    val donors = list.bikes.filter { b -> vm.care.any { list.ownerOf(it.bikeId) == b.id } }
                    Spacer(Modifier.height(22.dp))
                    SectionLabel("Bike care jobs")
                    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterPill("None for now", careChoice == 0, { careChoice = 0 })
                        FilterPill("Standard jobs", careChoice == 1, { careChoice = 1 })
                        donors.forEachIndexed { i, b ->
                            FilterPill("Copy from ${b.name.take(18)}", careChoice == i + 2, { careChoice = i + 2 })
                        }
                    }
                    Text(
                        "New jobs start from zero: nothing is marked as done.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                if (bike != null) {
                    Spacer(Modifier.height(26.dp))
                    SectionLabel("Manage")
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (list.multi && !bike.retired && bike.id != list.defaultId) {
                            CyButton("Set as default bike", { save(); vm.setDefaultBike(bike.id) }, Modifier.fillMaxWidth(), Btn.SECONDARY)
                        }
                        if (bike.retired) {
                            CyButton("Bring back", { vm.saveBikes(vm.bikes.unretire(bike.id)) }, Modifier.fillMaxWidth(), Btn.SECONDARY)
                        } else if (list.canRetire(bike.id)) {
                            CyButton("Retire this bike", { save(); vm.saveBikes(vm.bikes.retire(bike.id)); onClose(null) }, Modifier.fillMaxWidth(), Btn.SECONDARY)
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
                    CyButton("Add bike", { add() }, Modifier.fillMaxWidth(), enabled = valid, height = 52.dp)
                }
            }
        }
    }

    if (datePicker) CareDatePicker(now, onDismiss = { datePicker = false }) { datePicker = false; purchased = it }

    val del = if (confirmDelete && bike != null) list.delete(bike.id) else null
    if (del != null && bike != null) {
        val to = del.list.find(del.movedTo)?.name ?: "your other bike"
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            shape = RadiusLarge,
            title = { Text("Delete ${bike.name}?", style = MaterialTheme.typography.titleLarge) },
            text = { Text("Your rides are kept: rides and jobs on this bike move to $to. Its photo is removed. To keep them apart, retire the bike instead.") },
            confirmButton = {
                androidx.compose.material3.TextButton({
                    confirmDelete = false
                    vm.deleteBike(bike.id)
                    onClose(null)
                }) { Text("Delete and move rides", color = Cy.colors.danger) }
            },
            dismissButton = { androidx.compose.material3.TextButton({ confirmDelete = false }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        )
    }
}
