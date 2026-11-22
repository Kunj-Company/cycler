package com.cycler.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cycler.app.MainVm
import com.cycler.app.data.RouteMeta
import com.cycler.app.data.RouteType
import com.cycler.app.net.BRouter
import java.util.Calendar
import java.util.Locale

object Stats {
    class Sum(val rides: Int, val distanceM: Double, val ascentM: Double, val durationS: Long)

    /** Monday 00:00 of the current week, local time. */
    fun weekStart(now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val back = (c.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
        c.add(Calendar.DAY_OF_YEAR, -back)
        return c.timeInMillis
    }

    fun monthStart(now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun since(rides: List<RouteMeta>, from: Long, until: Long = Long.MAX_VALUE): Sum {
        val r = rides.filter { it.createdAt in from until until }
        return Sum(r.size, r.sumOf { it.distanceM }, r.sumOf { it.ascentM }, r.sumOf { it.durationS })
    }

    /** Kilometres per week for the last [weeks] weeks, oldest first. */
    fun weekly(rides: List<RouteMeta>, weeks: Int = 8, now: Long = System.currentTimeMillis()): List<Double> {
        val start = weekStart(now)
        val week = 7L * 24 * 3600 * 1000
        return (weeks - 1 downTo 0).map { back ->
            since(rides, start - back * week, start - (back - 1) * week).distanceM / 1000
        }
    }
}

@Composable
fun ActivityScreen(vm: MainVm) {
    val metas by vm.store.metas.collectAsState()
    val rides = remember(metas) { metas.filter { it.type == RouteType.RECORDED } }
    val week = remember(rides) { Stats.since(rides, Stats.weekStart()) }
    val month = remember(rides) { Stats.since(rides, Stats.monthStart()) }
    val all = remember(rides) { Stats.since(rides, 0) }
    val weekly = remember(rides) { Stats.weekly(rides) }
    val goal = vm.weeklyGoalKm

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Activity", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton({ vm.showSettings = true }) { Icon(Icons.Outlined.Settings, "Settings") }
        }

        Spacer(Modifier.height(14.dp))
        SectionLabel("This week")
        Row(verticalAlignment = Alignment.Bottom) {
            Text(String.format(Locale.US, "%.1f", week.distanceM / 1000), style = MaterialTheme.typography.displayLarge)
            Text(
                " km", style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 9.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Progress(
            (week.distanceM / 1000 / goal).toFloat(),
            color = if (week.distanceM / 1000 >= goal) Cy.colors.success else Cy.colors.accent,
        )
        val left = goal - week.distanceM / 1000
        Text(
            if (left <= 0) "Weekly goal of $goal km reached"
            else String.format(Locale.US, "%.1f km to go to your %d km goal", left, goal),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(16.dp))
        MetricRow(week)

        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SectionLabel("Last 8 weeks")
            Text(
                "– – goal", style = MaterialTheme.typography.labelSmall, color = Cy.colors.accent,
            )
        }
        Spacer(Modifier.height(12.dp))
        WeekBars(weekly, goal)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("7 weeks ago", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("This week", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(28.dp))
        SectionLabel("This month")
        Spacer(Modifier.height(10.dp))
        MetricRow(month, withDistance = true)

        Spacer(Modifier.height(28.dp))
        SectionLabel("All time")
        Spacer(Modifier.height(10.dp))
        MetricRow(all, withDistance = true)

        if (rides.isEmpty()) {
            Spacer(Modifier.height(28.dp))
            Text(
                "Your totals fill in as you record rides. Head to the Ride tab and press Start.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.height(28.dp))
            SectionLabel("Records")
            Spacer(Modifier.height(4.dp))
            val longest = rides.maxByOrNull { it.distanceM }
            val climbiest = rides.maxByOrNull { it.ascentM }
            val fastest = rides.filter { it.durationS > 600 }.maxByOrNull { it.distanceM / it.durationS }
            longest?.let { RecordRow("Longest ride", Fmt.km(it.distanceM), it) { vm.detailId = it.id } }
            climbiest?.let { RecordRow("Most climbing", "${it.ascentM.toInt()} m", it) { vm.detailId = it.id } }
            fastest?.let { RecordRow("Fastest average", Fmt.kmh(it.distanceM / it.durationS) + " km/h", it) { vm.detailId = it.id } }

            // Fastest time over set distances, found anywhere inside any ride.
            val efforts = com.cycler.app.data.Geo.EFFORTS.mapNotNull { km ->
                rides.filter { it.bests[km] != null }.minByOrNull { it.bests[km]!! }?.let { km to it }
            }
            if (efforts.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                SectionLabel("Best efforts")
                Spacer(Modifier.height(4.dp))
                efforts.forEach { (km, m) ->
                    val s = m.bests[km]!!
                    RecordRow("Fastest $km km", Fmt.clock(s * 1000) + String.format(Locale.US, "  ·  %.1f km/h", km * 3600.0 / s), m) { vm.detailId = m.id }
                }
            }

            Spacer(Modifier.height(20.dp))
            CyButton("See all rides on one map", { vm.showHeatmap = true }, Modifier.fillMaxWidth(), Btn.SECONDARY)
        }

        // Bike care: kilometres ridden since each job was last done.
        Spacer(Modifier.height(28.dp))
        SectionLabel("Bike care")
        Spacer(Modifier.height(4.dp))
        vm.care.forEachIndexed { i, item ->
            val used = rides.filter { it.createdAt > item.resetAt }.sumOf { it.distanceM } / 1000
            val due = used >= item.everyKm
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(item.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (due) "Due now · ${used.toInt()} km since last done"
                        else "${used.toInt()} of ${item.everyKm} km",
                        style = MaterialTheme.typography.bodySmall.tabular,
                        color = if (due) Cy.colors.warn else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Progress((used / item.everyKm).toFloat(), color = if (due) Cy.colors.warn else MaterialTheme.colorScheme.onSurface)
                }
                CyButton("Done", {
                    vm.saveCare(vm.care.mapIndexed { k, c -> if (k == i) c.copy(resetAt = System.currentTimeMillis()) else c })
                }, kind = Btn.SECONDARY, height = 36.dp)
            }
            Hairline()
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MetricRow(s: Stats.Sum, withDistance: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        if (withDistance) Metric("Distance", Fmt.kmShort(s.distanceM), Modifier.weight(1f), unit = "km")
        Metric("Rides", "${s.rides}", Modifier.weight(1f))
        Metric("Time", Fmt.dur(s.durationS), Modifier.weight(1f))
        Metric("Climb", "${s.ascentM.toInt()}", Modifier.weight(1f), unit = "m")
    }
}

@Composable
private fun RecordRow(label: String, value: String, m: RouteMeta, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${m.name} · ${Fmt.day(m.createdAt)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(value, style = MaterialTheme.typography.titleMedium.tabular)
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Hairline()
}

@Composable
fun SettingsScreen(vm: MainVm) {
    val ctx = LocalContext.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(start = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ vm.showSettings = false }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Text("Settings", style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                Group("Appearance")
                val modes = listOf("system" to "System", "light" to "Light", "dark" to "Dark")
                Segmented(
                    modes.map { it.second }, modes.indexOfFirst { it.first == vm.themeMode }.coerceAtLeast(0),
                    { vm.themeMode = modes[it].first; vm.prefs.themeMode = modes[it].first },
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                )

                Group("Map style")
                MapStyles.all.forEach { (key, label) ->
                    CheckRow(label, vm.mapStyle == key) { vm.mapStyle = key; vm.prefs.mapStyle = key }
                }

                Group("Bike", "Decides which roads and tracks route planning prefers.")
                BRouter.profiles.forEach { (key, label) ->
                    CheckRow(label, vm.profile == key) { vm.profile = key; vm.prefs.profile = key }
                }

                Group("Riding")
                ToggleRow("Auto-pause", "Stop the clock when you stop at a signal or for a break.", vm.autoPause) {
                    vm.autoPause = it; vm.prefs.autoPause = it
                }
                ToggleRow("Off-route alert", "Vibrate when you leave the route you are following.", vm.offRouteAlert) {
                    vm.offRouteAlert = it; vm.prefs.offRouteAlert = it
                }
                ToggleRow("Spoken updates", "Say distance and average speed at every kilometre.", vm.voice) {
                    vm.voice = it; vm.prefs.voice = it
                }
                ToggleRow("Keep screen on", "Recording continues with the screen off either way.", vm.keepScreenOn) {
                    vm.keepScreenOn = it; vm.prefs.keepScreenOn = it
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Weekly goal", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Shown on the Ride and Activity tabs.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    fun set(v: Int) { val g = v.coerceIn(10, 500); vm.weeklyGoalKm = g; vm.prefs.weeklyGoalKm = g }
                    CyButton("−", { set(vm.weeklyGoalKm - 10) }, Modifier.width(44.dp), Btn.SECONDARY, height = 36.dp)
                    Text(
                        "${vm.weeklyGoalKm} km", style = MaterialTheme.typography.titleMedium.tabular,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    CyButton("+", { set(vm.weeklyGoalKm + 10) }, Modifier.width(44.dp), Btn.SECONDARY, height = 36.dp)
                }
                Hairline()

                Group("Phone")
                Row(
                    Modifier.fillMaxWidth().clickable {
                        runCatching {
                            ctx.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Background recording", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "If rides stop recording with the screen off, open this and set Battery to Unrestricted.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Hairline()

                Group("About")
                Text(
                    "Cycler keeps everything on this phone: no account, no cloud. " +
                        "Maps, planning and weather need an internet connection; recording a ride does not.\n\n" +
                        "Map data © OpenStreetMap contributors. Map tiles by OpenFreeMap and CyclOSM. " +
                        "Routing and elevation by BRouter. Place search by Photon. Weather by Open-Meteo.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 28.dp),
                )
            }
        }
    }
}

@Composable
private fun Group(title: String, note: String? = null) {
    Spacer(Modifier.height(24.dp))
    SectionLabel(title)
    if (note != null) {
        Text(
            note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun CheckRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Outlined.Check, "Selected", Modifier.size(20.dp), tint = Cy.colors.accent)
        else Box(Modifier.size(20.dp))
    }
    Hairline()
}

@Composable
private fun ToggleRow(title: String, note: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Cy.colors.accent, checkedThumbColor = Color.White,
                uncheckedTrackColor = Cy.colors.subtle, uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
    Hairline()
}

/** Every recorded ride drawn on one map; roads ridden often show darker. */
@Composable
fun HeatmapScreen(vm: MainVm) {
    val metas by vm.store.metas.collectAsState()
    val rides = remember(metas) { metas.filter { it.type == RouteType.RECORDED } }
    val lines by androidx.compose.runtime.produceState(emptyList<MapLine>(), rides) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            rides.mapNotNull { m ->
                vm.store.load(m.id)?.pts?.let { pts ->
                    val step = pts.size / 400 + 1
                    MapLine(pts.filterIndexed { i, _ -> i % step == 0 || i == pts.size - 1 }, COLOR_TRACK, 4f, 0.45f)
                }
            }
        }
    }
    val camera = remember(lines) { if (lines.isEmpty()) null else CameraCmd(lines.flatMap { l -> listOf(l.pts.first(), l.pts[l.pts.size / 2], l.pts.last()) } + lines.flatMap { it.pts.filterIndexed { i, _ -> i % 25 == 0 } }) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.padding(start = 6.dp, top = 2.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ vm.showHeatmap = false }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text("All rides", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${rides.size} rides · ${Fmt.km(rides.sumOf { it.distanceM })}",
                        style = MaterialTheme.typography.bodySmall.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                CyclerMap(Modifier.fillMaxSize(), vm.mapStyle, lines, emptyList(), camera, initial = vm.prefs.lastLoc)
                LayerButton(vm.mapStyle, Modifier.align(Alignment.BottomEnd).padding(12.dp)) { vm.mapStyle = it; vm.prefs.mapStyle = it }
            }
        }
    }
}
