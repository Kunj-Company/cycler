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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun SettingsScreen(vm: MainVm) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun toast(msg: String) = android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
    fun openAppSettings() {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
    val backup = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) scope.launch {
            val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { vm.store.exportAll(it) } }.getOrNull()
            }
            toast(if (n != null) "Backed up $n rides and routes" else "Couldn't write the backup")
        }
    }
    val restore = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)!!.use { vm.store.importAll(it) } }.getOrNull()
            }
            toast(if (n != null && n > 0) "Restored $n files from the backup" else "That file isn't a Cycler backup")
        }
    }
    val inspection = androidx.compose.ui.platform.LocalInspectionMode.current
    val healthAvailable = remember { !inspection && com.cycler.app.health.Health.available(ctx) }
    var healthOn by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { if (healthAvailable) healthOn = com.cycler.app.health.Health.connected(ctx) }
    val askHealth = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.health.connect.client.PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        healthOn = granted.containsAll(com.cycler.app.health.Health.permissions)
        if (healthOn) scope.launch {
            // Fill in weight for the energy estimate if the rider hasn't set one.
            if (vm.weightKg == 0) com.cycler.app.health.Health.latestWeightKg(ctx)?.let { vm.weightKg = it; vm.prefs.weightKg = it }
            toast("Health Connect is connected")
        } else toast("Heart rate and weight access wasn't granted")
    }
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

                LinkRow("Offline maps", "Save the map for an area so it shows without mobile data.") { vm.showOffline = true }

                Group("Bike", "Decides which roads and tracks route planning prefers.")
                BRouter.profiles.forEach { (key, label) ->
                    CheckRow(label, vm.profile == key) { vm.profile = key; vm.prefs.profile = key }
                }

                Group("Recording")
                ToggleRow("Auto-pause", "Stop the clock when you stop at a signal or for a break.", vm.autoPause) {
                    vm.autoPause = it; vm.prefs.autoPause = it
                }
                if (vm.autoPause) {
                    SettingLabel("How quickly to pause", "Quick pauses after about 2 seconds stopped, Relaxed after 8.")
                    Segmented(
                        listOf("Relaxed", "Normal", "Quick"), vm.autoPauseLevel,
                        { vm.autoPauseLevel = it; vm.prefs.autoPauseLevel = it }, Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    )
                    Hairline()
                }
                ToggleRow("Keep screen on", "Recording continues with the screen off either way.", vm.keepScreenOn) {
                    vm.keepScreenOn = it; vm.prefs.keepScreenOn = it
                }
                SettingLabel("Auto-lap", "Mark a lap by itself at a set distance. The Lap button works either way.")
                val lapSteps = listOf(0, 1, 5, 10)
                Segmented(
                    lapSteps.map { if (it == 0) "Off" else "$it km" }, lapSteps.indexOf(vm.autoLapKm).coerceAtLeast(0),
                    { vm.autoLapKm = lapSteps[it]; vm.prefs.autoLapKm = lapSteps[it] }, Modifier.fillMaxWidth().padding(bottom = 10.dp),
                )
                Hairline()
                LinkRow("Ride screen", "Choose the numbers and pages on the big-number screen.") { vm.showFieldEditor = true }

                Group("Following a route")
                ToggleRow("Turn cues", "A short vibration, and a spoken cue if speech is on, before each turn.", vm.turnCues) {
                    vm.turnCues = it; vm.prefs.turnCues = it
                }
                ToggleRow("Off-route alert", "A longer vibration when you leave the route.", vm.offRouteAlert) {
                    vm.offRouteAlert = it; vm.prefs.offRouteAlert = it
                }

                Group("Spoken updates")
                ToggleRow("Speak while riding", "Distance and average speed read aloud, plus turn cues.", vm.voice) {
                    vm.voice = it; vm.prefs.voice = it
                }
                if (vm.voice) {
                    SettingLabel("How often", null)
                    val steps = listOf(1, 2, 5, 10)
                    Segmented(
                        steps.map { "Every $it km" }, steps.indexOf(vm.voiceEveryKm).coerceAtLeast(0),
                        { vm.voiceEveryKm = steps[it]; vm.prefs.voiceEveryKm = steps[it] }, Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    )
                    Hairline()
                }

                Group("You")
                StepperRow("Weekly goal", "Shown on the Ride and Activity tabs.", "${vm.weeklyGoalKm} km",
                    { val g = (vm.weeklyGoalKm - 10).coerceIn(10, 500); vm.weeklyGoalKm = g; vm.prefs.weeklyGoalKm = g },
                    { val g = (vm.weeklyGoalKm + 10).coerceIn(10, 500); vm.weeklyGoalKm = g; vm.prefs.weeklyGoalKm = g })
                StepperRow("Your weight", "Only used to estimate energy burned. Stays on this phone.",
                    if (vm.weightKg > 0) "${vm.weightKg} kg" else "Not set",
                    { val w = if (vm.weightKg == 0) 70 else (vm.weightKg - 1).coerceIn(30, 200); vm.weightKg = w; vm.prefs.weightKg = w },
                    { val w = if (vm.weightKg == 0) 70 else (vm.weightKg + 1).coerceIn(30, 200); vm.weightKg = w; vm.prefs.weightKg = w })
                ToggleRow("Advanced stats", "Open ride pages with the detailed numbers showing.", vm.advanced) {
                    vm.advanced = it; vm.prefs.advancedStats = it
                }

                Group("Health Connect", "Reads heart rate and weight that Samsung Health or another app has put in Health Connect. Nothing is written back.")
                when {
                    !healthAvailable -> Text(
                        "Health Connect isn't available on this phone, or needs updating from the Play Store.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    healthOn -> {
                        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(Cy.colors.success)
                            Spacer(Modifier.width(10.dp))
                            Text("Connected. Ride pages show heart rate when there is some for that ride.", style = MaterialTheme.typography.bodyMedium)
                        }
                        Hairline()
                    }
                    else -> {
                        CyButton("Connect Health Connect", { askHealth.launch(com.cycler.app.health.Health.permissions) }, Modifier.fillMaxWidth().padding(vertical = 8.dp), Btn.SECONDARY)
                        Text(
                            "In Samsung Health, also turn on syncing: Settings → Health Connect → allow Heart rate and Weight.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Group("Your data", "Everything lives on this phone only. Keep a backup somewhere else.")
                LinkRow("Back up everything", "One zip with all rides, routes, photos and a GPX copy of each.") {
                    backup.launch("cycler-backup-" + java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date()) + ".zip")
                }
                LinkRow("Restore from a backup", "Adds the rides from a backup zip. Nothing already here is removed.") {
                    restore.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                }

                Group("Phone")
                LinkRow("Battery and background", "If rides stop recording with the screen off, set Battery to Unrestricted here.") { openAppSettings() }
                LinkRow("Location permission", "Cycler needs Precise location, allowed while using the app.") { openAppSettings() }
                LinkRow("Notifications", "The recording notification is what keeps a ride alive in the background.") {
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }

                Group("About")
                Text(
                    "Cycler keeps everything on this phone: no account, no cloud. " +
                        "Maps, planning and weather need an internet connection; recording a ride does not.\n\n" +
                        "Map data © OpenStreetMap contributors. Map tiles by OpenFreeMap and CyclOSM. " +
                        "Routing and elevation by BRouter. Place search by Photon. Weather by Open-Meteo. Signals via Overpass.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Version " + (runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, bottom = 28.dp),
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


@Composable
private fun SettingLabel(title: String, note: String?) {
    Column(Modifier.padding(top = 10.dp, bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StepperRow(title: String, note: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CyButton("−", onMinus, Modifier.width(44.dp), Btn.SECONDARY, height = 36.dp)
        Text(value, style = MaterialTheme.typography.titleMedium.tabular, modifier = Modifier.padding(horizontal = 10.dp))
        CyButton("+", onPlus, Modifier.width(44.dp), Btn.SECONDARY, height = 36.dp)
    }
    Hairline()
}

@Composable
private fun LinkRow(title: String, note: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Hairline()
}
