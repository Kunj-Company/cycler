package com.cycler.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cycler.app.MainVm
import com.cycler.app.data.Care
import com.cycler.app.data.CareDone
import com.cycler.app.data.CareItem
import com.cycler.app.data.RouteMeta
import com.cycler.app.data.RouteType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A pill that is easy to hit: 44 dp tall to touch, a little smaller to look at. */
@Composable
internal fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit, trailing: ImageVector? = null) {
    val shape = RoundedCornerShape(50)
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(Modifier.heightIn(min = 44.dp).clip(shape).selectable(selected = selected, role = Role.RadioButton, onClick = onClick), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(shape)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else Cy.colors.border, shape)
                .padding(start = 14.dp, end = if (trailing != null) 8.dp else 14.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
            if (trailing != null) Icon(trailing, null, Modifier.padding(start = 2.dp).size(18.dp), tint = fg)
        }
    }
}

/** Bike care: every job with how far along it is. Tap one to edit it, mark it done or see its history. */
@Composable
fun BikeCareScreen(vm: MainVm, initialOpen: String? = null, now: Long = System.currentTimeMillis()) {
    val metas by vm.store.metas.collectAsState()
    val rides = remember(metas) { metas.filter { it.type == RouteType.RECORDED } }
    // null: the list; NEW: the add form; otherwise the id of the job being edited.
    var open by rememberSaveable { mutableStateOf(initialOpen) }
    // The bike whose jobs are shown (only when there is more than one); a bike's page can open this on it.
    var pickedBike by rememberSaveable { mutableStateOf(vm.careBike) }
    val bikes = vm.bikes
    val bikeId = if (bikes.multi) bikes.find(pickedBike)?.id ?: bikes.defaultId else null

    if (open != null) {
        BackHandler { open = null }
        CareDetail(vm, if (open == NEW) null else open, rides, now, bikeId) { open = null }
        return
    }

    val rows = remember(vm.care, rides, now, bikes, bikeId) {
        Care.sorted(vm.care.filter { bikeId == null || bikes.ownerOf(it.bikeId) == bikeId }, rides, now, bikes = bikes)
    }
    val due = rows.count { it.second.due }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.padding(start = 6.dp, top = 2.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ vm.showCare = false }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Text("Bike care", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton({ open = NEW }) { Icon(Icons.Outlined.Add, "Add a job") }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (bikeId != null) {
                    item {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                            BikePicker(bikes, bikeId, { pickedBike = it }, label = "Bike")
                            val t = bikes.totals(bikeId, rides)
                            Text(
                                "%,.0f km on the odometer · %d ride%s · %s".format(t.odometerKm, t.rides, if (t.rides == 1) "" else "s", Fmt.dur(t.timeS)),
                                style = MaterialTheme.typography.bodySmall.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
                item {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(if (due > 0) Cy.colors.warn else Cy.colors.success)
                            Text(
                                when {
                                    rows.isEmpty() -> "No jobs yet"
                                    due == 0 -> "Everything is up to date"
                                    else -> "$due job${if (due > 1) "s" else ""} due"
                                },
                                style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        Text(
                            "Distance counts the rides you recorded after each job was last done.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                if (rows.isEmpty()) {
                    item {
                        Column(Modifier.padding(20.dp)) {
                            CyButton("Add a job", { open = NEW }, kind = Btn.SECONDARY, icon = Icons.Outlined.Add)
                        }
                    }
                }
                items(rows, key = { it.first.id }) { (item, st) ->
                    Hairline(Modifier.padding(horizontal = 20.dp))
                    Column(Modifier.fillMaxWidth().clickable { open = item.id }.padding(horizontal = 20.dp, vertical = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    Care.text(item, st, now),
                                    style = MaterialTheme.typography.bodySmall.tabular,
                                    color = if (st.due) Cy.colors.warn else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Progress(st.fraction, color = if (st.due) Cy.colors.warn else MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (rows.isNotEmpty()) item { Hairline(Modifier.padding(horizontal = 20.dp)) }
                item {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { vm.showCare = false; vm.careBike = null; vm.showBikes = true }.padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Bikes", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            if (bikes.multi) "${bikes.bikes.size} bikes" else "Add a bike",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.padding(start = 4.dp).size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Hairline(Modifier.padding(horizontal = 20.dp))
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

private const val NEW = "new"

private fun dayLong(t: Long) = SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()).format(Date(t))

/** Add a job, or open one: edit its numbers, mark it done on any day, read and trim its history. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CareDetail(vm: MainVm, id: String?, rides: List<RouteMeta>, now: Long, newBikeId: String?, onClose: () -> Unit) {
    val item = id?.let { key -> vm.care.firstOrNull { it.id == key } }
    if (id != null && item == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val base = remember(id) { item ?: CareItem("", 0, 0, bikeId = newBikeId) }
    var name by rememberSaveable(id) { mutableStateOf(base.name) }
    var km by rememberSaveable(id) { mutableStateOf(if (base.everyKm > 0) base.everyKm.toString() else "") }
    var days by rememberSaveable(id) { mutableStateOf(if (base.everyDays > 0) base.everyDays.toString() else "") }
    var note by rememberSaveable(id) { mutableStateOf(base.note) }
    var doneNote by rememberSaveable(id) { mutableStateOf("") }
    var lastDone by rememberSaveable(id) { mutableStateOf<Long?>(null) }   // new jobs only
    var confirmDelete by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val kmN = km.toIntOrNull() ?: 0
    val daysN = days.toIntOrNull() ?: 0
    val valid = name.isNotBlank() && (kmN > 0 || daysN > 0)
    val dirty = item != null && (name.trim() != item.name || kmN != item.everyKm || daysN != item.everyDays || note.trim() != item.note)
    fun draft(): CareItem = base.copy(name = name.trim(), everyKm = kmN, everyDays = daysN, note = note.trim())
    fun replace(updated: CareItem) = vm.saveCare(vm.care.map { if (it.id == updated.id) updated else it })

    fun close() {
        if (item != null && dirty && valid) replace(draft().copy(history = item.history, resetAt = item.resetAt))
        onClose()
    }
    BackHandler { close() }

    fun markDone(at: Long) {
        if (item == null) { lastDone = at; return }
        val current = if (valid) item.copy(name = name.trim(), everyKm = kmN, everyDays = daysN, note = note.trim()) else item
        replace(current.markDone(at, doneNote))
        vm.stat(com.cycler.app.analytics.Feature.BIKE_CARE)
        doneNote = ""
        message = "Marked as done · " + Care.dateText(at, now)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.padding(start = 6.dp, top = 2.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ close() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Text(
                    if (item == null) "New job" else item.name, style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (item != null && dirty && valid) {
                    TextButton({ replace(draft().copy(history = item.history, resetAt = item.resetAt)) }) { Text("Save") }
                }
                if (item != null) IconButton({ confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Delete job") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                if (item != null) {
                    val st = Care.status(item, rides, now, bikes = vm.bikes)
                    Text(
                        Care.text(item, st, now), style = MaterialTheme.typography.titleMedium.tabular,
                        color = if (st.due) Cy.colors.warn else MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(10.dp))
                    Progress(st.fraction, color = if (st.due) Cy.colors.warn else MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (item.resetAt > 0) "Last done ${dayLong(item.resetAt)}" else "Not marked as done yet",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )

                    Spacer(Modifier.height(26.dp))
                    SectionLabel("Mark as done")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        doneNote, { doneNote = it }, singleLine = true, shape = Radius,
                        placeholder = { Text("Note for this entry, optional") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    DoneChooser(rides, now, "Done today", ::markDone)
                    message?.let {
                        Text(
                            it, style = MaterialTheme.typography.bodySmall, color = Cy.colors.success,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                } else {
                    SectionLabel("Suggestions")
                    FlowRow(
                        Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Care.presets.forEach { (n, k, d) ->
                            FilterPill(n, name == n, {
                                name = n; km = if (k > 0) k.toString() else ""; days = if (d > 0) d.toString() else ""
                            })
                        }
                    }
                }

                Spacer(Modifier.height(26.dp))
                SectionLabel("Job")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    name, { name = it }, singleLine = true, shape = Radius, label = { Text("Name") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        km, { km = it.filter(Char::isDigit).take(5) }, singleLine = true, shape = Radius,
                        label = { Text("Every (km)") }, placeholder = { Text("None") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        days, { days = it.filter(Char::isDigit).take(4) }, singleLine = true, shape = Radius,
                        label = { Text("Every (days)") }, placeholder = { Text("None") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "Due when either limit is passed. Leave one empty to use only the other.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                OutlinedTextField(
                    note, { note = it }, shape = Radius, minLines = 2, maxLines = 5, label = { Text("Note") },
                    placeholder = { Text("Oil type, tool, part number") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )

                if (item == null) {
                    Spacer(Modifier.height(26.dp))
                    SectionLabel("Last done")
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            lastDone?.let { dayLong(it) } ?: "Not done yet. Every ride counts until you say when.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                        )
                        if (lastDone != null) TextButton({ lastDone = null }) { Text("Clear", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Spacer(Modifier.height(6.dp))
                    DoneChooser(rides, now, "Today", ::markDone)
                } else {
                    Spacer(Modifier.height(26.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("History", Modifier.weight(1f))
                        Text(
                            "${item.history.size} of ${CareItem.MAX_HISTORY}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (item.history.isEmpty()) {
                        Text(
                            "Nothing yet. Each time you mark the job as done it is listed here.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    item.history.forEach { h ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Text(dayLong(h.at), style = MaterialTheme.typography.bodyLarge)
                                if (h.note.isNotBlank()) {
                                    Text(
                                        h.note, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            IconButton({ replace(item.removeDone(h)) }) {
                                Icon(Icons.Outlined.Close, "Remove entry", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Hairline()
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            if (item == null) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    CyButton("Add job", {
                        var created = draft()
                        lastDone?.let { created = created.markDone(it) }
                        vm.saveCare(vm.care + created)
                        vm.stat(com.cycler.app.analytics.Feature.BIKE_CARE)
                        onClose()
                    }, Modifier.fillMaxWidth(), enabled = valid, height = 52.dp)
                }
            }
        }
    }

    if (confirmDelete && item != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            shape = RadiusLarge,
            title = { Text("Delete this job?", style = MaterialTheme.typography.titleLarge) },
            text = { Text("\"${item.name}\" and its history are removed. Your rides are not touched.") },
            confirmButton = {
                TextButton({ vm.saveCare(vm.care.filter { it.id != item.id }); confirmDelete = false; onClose() }) {
                    Text("Delete", color = Cy.colors.danger)
                }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        )
    }
}

/** When was it done: today, on a day picked from a calendar, or right after one of the recent rides. */
@Composable
private fun DoneChooser(rides: List<RouteMeta>, now: Long, todayLabel: String, onPick: (Long) -> Unit) {
    var datePicker by remember { mutableStateOf(false) }
    var ridePicker by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CyButton(todayLabel, { onPick(now) }, Modifier.fillMaxWidth(), Btn.PRIMARY)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CyButton("Pick a date", { datePicker = true }, Modifier.weight(1f), Btn.SECONDARY)
            CyButton("After a ride", { ridePicker = true }, Modifier.weight(1f), Btn.SECONDARY, enabled = rides.isNotEmpty())
        }
    }
    if (datePicker) {
        CareDatePicker(now, onDismiss = { datePicker = false }) { datePicker = false; onPick(it) }
    }
    if (ridePicker) {
        val recent = remember(rides) { rides.sortedByDescending { it.createdAt }.take(12) }
        AlertDialog(
            onDismissRequest = { ridePicker = false },
            shape = RadiusLarge,
            title = { Text("Done after which ride?", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    Text(
                        "Rides after the one you pick count towards the next time it is due.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        items(recent, key = { it.id }) { r ->
                            Column(
                                Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { ridePicker = false; onPick(r.createdAt) }
                                    .padding(vertical = 8.dp),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(r.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${Fmt.day(r.createdAt)} · ${Fmt.km(r.distanceM)}", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Hairline()
                        }
                    }
                }
            },
            confirmButton = { TextButton({ ridePicker = false }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        )
    }
}

/** Material 3 date picker, in the app's ink and blue, with no days in the future. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CareDatePicker(now: Long, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val latest = remember(now) { Care.todayAsUtcMidnight(now) }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = latest,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= latest
            override fun isSelectableYear(year: Int) =
                year <= java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = latest }.get(java.util.Calendar.YEAR)
        },
    )
    val scheme = MaterialTheme.colorScheme
    val colors = DatePickerDefaults.colors(
        containerColor = scheme.surface,
        titleContentColor = scheme.onSurfaceVariant, headlineContentColor = scheme.onSurface,
        weekdayContentColor = scheme.onSurfaceVariant, subheadContentColor = scheme.onSurfaceVariant,
        navigationContentColor = scheme.onSurface, yearContentColor = scheme.onSurface,
        currentYearContentColor = Cy.colors.accent,
        selectedYearContainerColor = scheme.primary, selectedYearContentColor = scheme.onPrimary,
        dayContentColor = scheme.onSurface, disabledDayContentColor = scheme.onSurfaceVariant.copy(alpha = 0.4f),
        selectedDayContainerColor = scheme.primary, selectedDayContentColor = scheme.onPrimary,
        todayContentColor = Cy.colors.accent, todayDateBorderColor = Cy.colors.accent,
        dividerColor = Cy.colors.border,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        shape = RadiusLarge,
        colors = colors,
        confirmButton = {
            TextButton({ state.selectedDateMillis?.let { onPick(Care.doneAtEndOfDay(it, now)) } }, enabled = state.selectedDateMillis != null) {
                Text("Done")
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = scheme.onSurfaceVariant) } },
    ) {
        DatePicker(state, colors = colors, title = { Text("When was it done?", Modifier.padding(start = 24.dp, top = 16.dp), style = MaterialTheme.typography.labelLarge) })
    }
}
