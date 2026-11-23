package com.cycler.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DirectionsBike
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cycler.app.MainVm
import com.cycler.app.net.BRouter
import kotlinx.coroutines.launch
import java.util.Locale

private const val HUB = "hub"

/** Where the back arrow on each page leads. */
private fun parentOf(page: String) = when (page) {
    "whatsnew", "licence", "notices" -> "about"
    else -> HUB
}

private fun titleOf(page: String) = when (page) {
    "appearance" -> "Appearance"
    "bike" -> "Bike"
    "recording" -> "Recording"
    "alerts" -> "Alerts and voice"
    "goals" -> "Goals and you"
    "health" -> "Health Connect"
    "data" -> "Maps and data"
    "phone" -> "Phone"
    "about" -> "About"
    "whatsnew" -> "What's new"
    "licence" -> "Licence"
    "notices" -> "Open-source notices"
    else -> "Settings"
}

@Composable
fun SettingsScreen(vm: MainVm, startPage: String? = null) {
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
                runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { vm.store.exportAll(it, mapOf("care.json" to com.cycler.app.data.CareItem.encode(vm.care), "prefs.json" to vm.prefs.exportJson())) } }.getOrNull()
            }
            toast(if (n != null) "Backed up $n rides and routes" else "Couldn't write the backup")
        }
    }
    val restore = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            var pendingCare: String? = null
            var pendingPrefs: String? = null
            val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)!!.use { vm.store.importAll(it) { name, text -> if (name == "care.json") pendingCare = text else if (name == "prefs.json") pendingPrefs = text } } }.getOrNull()
            }
            // The bike care list and settings in the backup replace the ones here.
            pendingCare?.let { vm.saveCare(com.cycler.app.data.CareItem.parse(it)) }
            pendingPrefs?.let { if (vm.prefs.importJson(it) > 0) vm.reloadSettings() }
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
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty() }
    var page by rememberSaveable { mutableStateOf(startPage ?: HUB) }
    BackHandler(enabled = page != HUB) { page = parentOf(page) }

    val themeModes = listOf("system" to "System", "light" to "Light", "dark" to "Dark")
    val lapSteps = listOf(0, 1, 5, 10)
    val voiceSteps = listOf(1, 2, 5, 10)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.padding(start = 6.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ if (page == HUB) vm.showSettings = false else page = parentOf(page) }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                }
                Text(titleOf(page), style = MaterialTheme.typography.titleLarge)
            }
            key(page) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                    when (page) {
                        HUB -> {
                            val themeLabel = themeModes.firstOrNull { it.first == vm.themeMode }?.second ?: "System"
                            val mapLabel = (MapStyles.all.firstOrNull { it.first == vm.mapStyle }?.second ?: "Standard").substringBefore(" (")
                            val bikeLabel = BRouter.profiles.firstOrNull { it.first == vm.profile }?.second ?: "Hybrid / touring"
                            val recordingSummary = (if (vm.autoPause) "Auto-pause on" else "Auto-pause off") + " · " +
                                (if (vm.autoLapKm == 0) "Auto-lap off" else "Auto-lap every ${vm.autoLapKm} km")
                            val alertParts = buildList {
                                if (vm.turnCues) add("Turn cues")
                                if (vm.offRouteAlert) add("Off-route alert")
                                if (vm.voice) add("Voice every ${vm.voiceEveryKm} km")
                            }
                            val goalsSummary = "${vm.weeklyGoalKm} km a week" + if (vm.weightKg > 0) " · ${vm.weightKg} kg" else ""
                            val healthSummary = when {
                                !healthAvailable -> "Not available"
                                healthOn -> "Connected"
                                else -> "Not connected"
                            }
                            HubGroup(
                                "Riding",
                                HubItem(Icons.Outlined.DirectionsBike, "Bike", bikeLabel) { page = "bike" },
                                HubItem(Icons.Outlined.Timer, "Recording", recordingSummary) { page = "recording" },
                                HubItem(
                                    Icons.Outlined.NotificationsActive, "Alerts and voice",
                                    if (alertParts.isEmpty()) "All off" else alertParts.joinToString(" · "),
                                ) { page = "alerts" },
                            )
                            HubGroup(
                                "You",
                                HubItem(Icons.Outlined.Palette, "Appearance", "$themeLabel · $mapLabel map") { page = "appearance" },
                                HubItem(Icons.Outlined.Flag, "Goals and you", goalsSummary) { page = "goals" },
                                HubItem(Icons.Outlined.FavoriteBorder, "Health Connect", healthSummary) { page = "health" },
                            )
                            HubGroup(
                                "App and phone",
                                HubItem(Icons.Outlined.Map, "Maps and data", "Offline maps, backup and restore") { page = "data" },
                                HubItem(Icons.Outlined.PhoneAndroid, "Phone", "Battery, location and notifications") { page = "phone" },
                                HubItem(Icons.Outlined.Info, "About", if (version.isEmpty()) "Cycler" else "Cycler $version") { page = "about" },
                            )
                        }

                        "appearance" -> {
                            Spacer(Modifier.height(8.dp))
                            SectionLabel("Theme")
                            Segmented(
                                themeModes.map { it.second }, themeModes.indexOfFirst { it.first == vm.themeMode }.coerceAtLeast(0),
                                { vm.themeMode = themeModes[it].first; vm.prefs.themeMode = themeModes[it].first },
                                Modifier.fillMaxWidth().padding(top = 10.dp),
                            )
                            Spacer(Modifier.height(28.dp))
                            SectionLabel("Map style")
                            Spacer(Modifier.height(4.dp))
                            MapStyles.all.forEach { (key, label) ->
                                CheckRow(label, vm.mapStyle == key) { vm.mapStyle = key; vm.prefs.mapStyle = key }
                            }
                        }

                        "bike" -> {
                            PageNote("Decides which roads and tracks route planning prefers.")
                            BRouter.profiles.forEach { (key, label) ->
                                CheckRow(label, vm.profile == key) { vm.profile = key; vm.prefs.profile = key }
                            }
                        }

                        "recording" -> {
                            Spacer(Modifier.height(4.dp))
                            ToggleRow("Auto-pause", "Stop the clock at a signal or a break.", vm.autoPause) {
                                vm.autoPause = it; vm.prefs.autoPause = it
                            }
                            if (vm.autoPause) {
                                SegmentedSetting(
                                    "How quickly to pause", "Quick pauses after about 2 seconds, Relaxed after 8.",
                                    listOf("Relaxed", "Normal", "Quick"), vm.autoPauseLevel,
                                ) { vm.autoPauseLevel = it; vm.prefs.autoPauseLevel = it }
                            }
                            ToggleRow("Keep screen on", "Recording continues with the screen off anyway.", vm.keepScreenOn) {
                                vm.keepScreenOn = it; vm.prefs.keepScreenOn = it
                            }
                            SegmentedSetting(
                                "Auto-lap", "Mark a lap by itself at a set distance. The Lap button works either way.",
                                lapSteps.map { if (it == 0) "Off" else "$it km" }, lapSteps.indexOf(vm.autoLapKm).coerceAtLeast(0),
                            ) { vm.autoLapKm = lapSteps[it]; vm.prefs.autoLapKm = lapSteps[it] }
                            LinkRow("Ride screen", "Choose the numbers and pages shown while riding.") { vm.showFieldEditor = true }
                        }

                        "alerts" -> {
                            Spacer(Modifier.height(4.dp))
                            SectionLabel("Following a route")
                            Spacer(Modifier.height(4.dp))
                            ToggleRow("Turn cues", "A short vibration, and a spoken cue if speech is on, before each turn.", vm.turnCues) {
                                vm.turnCues = it; vm.prefs.turnCues = it
                            }
                            ToggleRow("Off-route alert", "A longer vibration when you leave the route.", vm.offRouteAlert) {
                                vm.offRouteAlert = it; vm.prefs.offRouteAlert = it
                            }
                            Spacer(Modifier.height(28.dp))
                            SectionLabel("Spoken updates")
                            Spacer(Modifier.height(4.dp))
                            ToggleRow("Speak while riding", "Distance and average speed read aloud, plus turn cues.", vm.voice) {
                                vm.voice = it; vm.prefs.voice = it
                            }
                            if (vm.voice) {
                                SegmentedSetting(
                                    "Speak every", null,
                                    voiceSteps.map { "$it km" }, voiceSteps.indexOf(vm.voiceEveryKm).coerceAtLeast(0),
                                ) { vm.voiceEveryKm = voiceSteps[it]; vm.prefs.voiceEveryKm = voiceSteps[it] }
                            }
                        }

                        "goals" -> {
                            Spacer(Modifier.height(4.dp))
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
                        }

                        "health" -> {
                            PageNote("Reads heart rate and weight that Samsung Health or another app has put in Health Connect. Nothing is written back.")
                            when {
                                !healthAvailable -> Text(
                                    "Health Connect isn't available on this phone, or needs updating from the Play Store.",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                                healthOn -> {
                                    Row(Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                                        StatusDot(Cy.colors.success)
                                        Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text("Connected", style = MaterialTheme.typography.bodyLarge)
                                            Text(
                                                "Ride pages show heart rate when there is some for that ride.",
                                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    Hairline()
                                }
                                else -> {
                                    Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                        StatusDot(MaterialTheme.colorScheme.outline)
                                        Spacer(Modifier.width(12.dp))
                                        Text("Not connected", style = MaterialTheme.typography.bodyLarge)
                                    }
                                    CyButton(
                                        "Connect Health Connect", { askHealth.launch(com.cycler.app.health.Health.permissions) },
                                        Modifier.fillMaxWidth().padding(vertical = 8.dp), Btn.SECONDARY,
                                    )
                                    Text(
                                        "In Samsung Health, also turn on syncing: Settings → Health Connect → allow Heart rate and Weight.",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        "data" -> {
                            PageNote("Everything lives on this phone only. Keep a backup somewhere else.")
                            LinkRow("Offline maps", "Save the map for an area so it shows without mobile data.") { vm.showOffline = true }
                            LinkRow("Back up everything", "One zip with all rides, routes, photos, your settings and a GPX copy of each ride.") {
                                backup.launch("cycler-backup-" + java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date()) + ".zip")
                            }
                            LinkRow("Restore from a backup", "Adds the rides from a backup zip. Nothing already here is removed.") {
                                restore.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                            }
                        }

                        "phone" -> {
                            PageNote("These open Cycler's page in Android settings.")
                            LinkRow("Battery and background", "If rides stop recording with the screen off, set Battery to Unrestricted here.", external = true) { openAppSettings() }
                            LinkRow("Location permission", "Cycler needs Precise location, allowed while using the app.", external = true) { openAppSettings() }
                            LinkRow("Notifications", "The recording notification is what keeps a ride alive in the background.", external = true) {
                                runCatching {
                                    ctx.startActivity(
                                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                        }

                        "about" -> AboutPage(version) { page = it }
                        "whatsnew" -> WhatsNewPage()
                        "licence" -> LicencePage()
                        "notices" -> NoticesPage()
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

private class HubItem(val icon: ImageVector, val title: String, val summary: String, val onClick: () -> Unit)

@Composable
private fun HubGroup(label: String, vararg items: HubItem) {
    Spacer(Modifier.height(20.dp))
    SectionLabel(label, Modifier.padding(bottom = 4.dp))
    items.forEachIndexed { i, it ->
        if (i > 0) Hairline(Modifier.padding(start = 52.dp))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = it.onClick).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(38.dp).clip(Radius).background(Cy.colors.subtle),
                contentAlignment = Alignment.Center,
            ) { Icon(it.icon, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurface) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(it.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    it.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PageNote(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
    )
}

@Composable
private fun CheckRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
        if (selected) Icon(Icons.Outlined.Check, "Selected", Modifier.size(20.dp), tint = Cy.colors.accent)
        else Box(Modifier.size(20.dp))
    }
    Hairline()
}

@Composable
private fun ToggleRow(title: String, note: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CySwitch(checked, onChange)
    }
    Hairline()
}

@Composable
private fun SegmentedSetting(title: String, note: String?, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Segmented(options, selected, onSelect, Modifier.fillMaxWidth().padding(top = 10.dp))
    }
    Hairline()
}

@Composable
private fun StepperRow(title: String, note: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CyButton("−", onMinus, Modifier.width(44.dp), Btn.SECONDARY, height = 40.dp)
        Text(
            value, style = MaterialTheme.typography.titleMedium.tabular,
            modifier = Modifier.width(76.dp).padding(horizontal = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        CyButton("+", onPlus, Modifier.width(44.dp), Btn.SECONDARY, height = 40.dp)
    }
    Hairline()
}

@Composable
private fun LinkRow(title: String, note: String, external: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            if (external) Icons.AutoMirrored.Outlined.OpenInNew else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            null, Modifier.size(if (external) 20.dp else 24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Hairline()
}

/** A plain row that just leads somewhere, for the About page. */
@Composable
private fun SimpleLinkRow(title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Hairline()
}

@Composable
private fun AboutPage(version: String, open: (String) -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text("Cycler", style = MaterialTheme.typography.headlineMedium)
    if (version.isNotEmpty()) {
        Text("Version $version", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(
        "A cycling app for recording rides with GPS, planning routes and looking back on your rides. " +
            "Everything stays on this phone: no account, no cloud, no tracking.",
        style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp),
    )
    Text("Developed by Abhiram N J", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp))
    Text(
        "Open source under the MIT licence.", style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
    )
    Spacer(Modifier.height(20.dp))
    Hairline()
    SimpleLinkRow("What's new") { open("whatsnew") }
    SimpleLinkRow("Licence") { open("licence") }
    SimpleLinkRow("Open-source notices") { open("notices") }
    Spacer(Modifier.height(28.dp))
    SectionLabel("Credits")
    Spacer(Modifier.height(8.dp))
    listOf(
        "Map data © OpenStreetMap contributors",
        "Map tiles by OpenFreeMap and CyclOSM",
        "Routing and elevation by BRouter",
        "Place search by Photon",
        "Weather by Open-Meteo",
        "Traffic signals via the Overpass API",
    ).forEach {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
    }
}

@Composable
private fun WhatsNewPage() {
    Changelog.versions.forEachIndexed { i, v ->
        Spacer(Modifier.height(if (i == 0) 8.dp else 24.dp))
        Text(v.version, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        v.lines.forEach {
            Row(Modifier.padding(vertical = 3.dp)) {
                Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(16.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

private val MIT_LICENCE = listOf(
    "MIT License",
    "Copyright (c) 2026 Abhiram N J",
    "Permission is hereby granted, free of charge, to any person obtaining a copy " +
        "of this software and associated documentation files (the \"Software\"), to deal " +
        "in the Software without restriction, including without limitation the rights " +
        "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell " +
        "copies of the Software, and to permit persons to whom the Software is " +
        "furnished to do so, subject to the following conditions:",
    "The above copyright notice and this permission notice shall be included in all " +
        "copies or substantial portions of the Software.",
    "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR " +
        "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, " +
        "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE " +
        "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER " +
        "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, " +
        "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE " +
        "SOFTWARE.",
)

@Composable
private fun LicencePage() {
    Spacer(Modifier.height(8.dp))
    MIT_LICENCE.forEachIndexed { i, para ->
        Text(
            para,
            style = if (i == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            modifier = Modifier.padding(bottom = 14.dp),
        )
    }
}

@Composable
private fun NoticesPage() {
    PageNote("What Cycler is built with, and the licence each part comes under.")
    listOf(
        "MapLibre Native for Android" to "BSD 2-Clause",
        "AndroidX" to "Apache 2.0",
        "Jetpack Compose and Material 3" to "Apache 2.0",
        "Health Connect client" to "Apache 2.0",
        "Kotlin and kotlinx.coroutines" to "Apache 2.0",
        "Inter typeface by Rasmus Andersson" to "SIL Open Font License 1.1",
        "Map data from OpenStreetMap" to "Open Database License",
    ).forEach { (name, licence) ->
        Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 10.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(licence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Hairline()
    }
}
