package com.cycler.app.ui

import androidx.compose.foundation.selection.selectable
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cycler.app.MainVm
import com.cycler.app.net.BRouter

private const val STATS_STEP = 4
private const val LAST_STEP = 5
private const val GOAL_STEP_KM = 10
private const val GOAL_MIN_KM = 10
private const val GOAL_MAX_KM = 500

/** Space a second button would take, kept free so the primary button never jumps between steps. */
private val SECONDARY_SLOT = 56.dp

/**
 * First-launch setup: six short steps, one at a time. Nothing here is required, but Skip still stops
 * at the anonymous stats notice, which is shown once to everyone; everything chosen can be changed later in Settings.
 */
@Composable
fun Onboarding(vm: MainVm, granted: Boolean, requestPermission: () -> Unit, startStep: Int = 0, onDone: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(startStep.coerceIn(0, LAST_STEP)) }
    BackHandler(enabled = step > 0) { step-- }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopRow(
                step = step,
                onBack = { if (step > 0) step-- },
                onSkip = { step = if (vm.statsNotice) STATS_STEP else LAST_STEP },
            )
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                when (step) {
                    0 -> WelcomeStep()
                    1 -> LocationStep(granted)
                    2 -> BackgroundStep()
                    3 -> RidingStep(vm)
                    STATS_STEP -> StatsNoticeContent()
                    else -> ReadyStep()
                }
            }
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp)) {
                val ctx = LocalContext.current
                when (step) {
                    0 -> {
                        CyButton("Get started", { step = 1 }, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(SECONDARY_SLOT))
                    }
                    1 -> {
                        if (granted) CyButton("Continue", { step = 2 }, Modifier.fillMaxWidth())
                        else CyButton("Allow location", requestPermission, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        CyButton("Not now", { step = 2 }, Modifier.fillMaxWidth(), kind = Btn.SECONDARY)
                    }
                    2 -> {
                        CyButton("Continue", { step = 3 }, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        CyButton(
                            "Open app settings",
                            {
                                runCatching {
                                    ctx.startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                                Unit
                            },
                            Modifier.fillMaxWidth(), kind = Btn.SECONDARY,
                        )
                    }
                    3 -> {
                        CyButton("Continue", { step = STATS_STEP }, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(SECONDARY_SLOT))
                    }
                    STATS_STEP -> {
                        // Only this button counts as accepting the notice; Back or leaving the app does not.
                        CyButton("Continue", { vm.acceptStatsNotice(); step = LAST_STEP }, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(SECONDARY_SLOT))
                    }
                    else -> {
                        CyButton("Start riding", onDone, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(SECONDARY_SLOT))
                    }
                }
            }
        }
    }
}

@Composable
private fun TopRow(step: Int, onBack: () -> Unit, onSkip: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (step > 0) {
                Row(
                    Modifier.heightIn(min = 44.dp).clip(Radius).clickable(role = Role.Button, onClick = onBack)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Back", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        StepDots(step)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            // Nothing to skip from the stats notice on: Continue is the only way past it.
            if (step < STATS_STEP) {
                Box(
                    Modifier.heightIn(min = 44.dp).clip(Radius).clickable(role = Role.Button, onClick = onSkip)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Skip", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun StepDots(step: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0..LAST_STEP) {
            val active = i == step
            Box(
                Modifier.height(6.dp).width(if (active) 18.dp else 6.dp).clip(CircleShape)
                    .background(if (active) MaterialTheme.colorScheme.onSurface else Cy.colors.border),
            )
        }
    }
}

@Composable
private fun StepIcon(icon: ImageVector) {
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(Cy.colors.accentSoft),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(26.dp), tint = Cy.colors.accent)
    }
}

@Composable
private fun StepHeader(icon: ImageVector, title: String, body: String) {
    StepIcon(icon)
    Spacer(Modifier.height(24.dp))
    Text(title, style = MaterialTheme.typography.headlineLarge)
    Spacer(Modifier.height(12.dp))
    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** An icon and a line of text, for short lists. */
@Composable
private fun PointRow(icon: ImageVector, title: String, body: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(36.dp).clip(Radius).background(Cy.colors.subtle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(top = if (body == null) 7.dp else 0.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (body != null) {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    StepIcon(Icons.AutoMirrored.Outlined.DirectionsBike)
    Spacer(Modifier.height(24.dp))
    Text("Cycler", style = MaterialTheme.typography.displaySmall)
    Spacer(Modifier.height(12.dp))
    Text(
        "A cycling app that keeps your rides on your phone. No account, no sign-up.",
        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(28.dp))
    Hairline()
    PointRow(Icons.AutoMirrored.Outlined.DirectionsBike, "Record your rides with GPS")
    PointRow(Icons.Outlined.Route, "Plan routes and loops")
    PointRow(Icons.Outlined.PhotoLibrary, "Look back at every ride with stats, photos and a shareable video")
}

@Composable
private fun LocationStep(granted: Boolean) {
    StepHeader(
        Icons.Outlined.LocationOn, "Turn on location",
        "Cycler needs your location to show where you are on the map and to record your rides. " +
            "Your rides stay on this phone, with no account and no ads. Online requests: " +
            "weather sends your position rounded to about 100 m, place search sends what you type and a rounded position, " +
            "route planning sends the points you plan between (a loop starts at your position), map tiles show " +
            "the map provider the area you view, and anonymous stats send counts and the general area (about 10 km) " +
            "where rides start and end. See Settings → Privacy.",
    )
    Spacer(Modifier.height(20.dp))
    Text(
        "Choose Precise when Android asks. Approximate location is not accurate enough to record a ride.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (granted) {
        Spacer(Modifier.height(24.dp))
        Row(
            Modifier.fillMaxWidth().clip(Radius).background(Cy.colors.subtle).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(Cy.colors.success, 10.dp)
            Spacer(Modifier.width(12.dp))
            Text("Location is on", style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun BackgroundStep() {
    StepHeader(
        Icons.Outlined.Notifications, "Recording in the background",
        "Recording keeps going with the screen off, so you can put your phone in a pocket. " +
            "A notification shows while a ride is recording.",
    )
    Spacer(Modifier.height(20.dp))
    Text(
        "On some phones, if rides stop when the screen is off, open the app settings and set " +
            "Battery to Unrestricted.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RidingStep(vm: MainVm) {
    StepHeader(
        Icons.Outlined.Tune, "Set up your riding",
        "Both of these can be changed later in Settings.",
    )
    Spacer(Modifier.height(28.dp))
    SectionLabel("Bike type")
    Spacer(Modifier.height(10.dp))
    Column(Modifier.fillMaxWidth().clip(Radius).border(1.dp, Cy.colors.border, Radius)) {
        BRouter.profiles.forEachIndexed { i, (key, label) ->
            if (i > 0) Hairline()
            val sel = vm.profile == key
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .background(if (sel) Cy.colors.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(selected = sel, role = Role.RadioButton) {
                        vm.profile = key
                        vm.prefs.profile = key
                    }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (sel) Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = Cy.colors.accent)
            }
        }
    }
    Spacer(Modifier.height(28.dp))
    SectionLabel("Weekly distance goal")
    Spacer(Modifier.height(10.dp))
    Row(
        Modifier.fillMaxWidth().clip(Radius).border(1.dp, Cy.colors.border, Radius).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GoalButton(Icons.Outlined.Remove, "Decrease goal", vm.weeklyGoalKm > GOAL_MIN_KM) {
            setGoal(vm, vm.weeklyGoalKm - GOAL_STEP_KM)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
            Text("${vm.weeklyGoalKm}", style = MaterialTheme.typography.headlineMedium.tabular, maxLines = 1)
            Text(
                " km a week", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        GoalButton(Icons.Outlined.Add, "Increase goal", vm.weeklyGoalKm < GOAL_MAX_KM) {
            setGoal(vm, vm.weeklyGoalKm + GOAL_STEP_KM)
        }
    }
}

private fun setGoal(vm: MainVm, km: Int) {
    val v = km.coerceIn(GOAL_MIN_KM, GOAL_MAX_KM)
    vm.weeklyGoalKm = v
    vm.prefs.weeklyGoalKm = v
}

@Composable
private fun GoalButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.38f
    Box(
        Modifier.size(48.dp).clip(Radius).background(Cy.colors.subtle)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
    }
}

/** The one-time notice about anonymous stats: no off button here, only where to find it. */
@Composable
private fun StatsNoticeContent() {
    StepHeader(
        Icons.Outlined.Insights, "Anonymous stats",
        "Cycler shares anonymous stats to help improve the app: features used, total distance, and the " +
            "general area (about 10 km) you ride in. No names, routes, exact places or health data.",
    )
    Spacer(Modifier.height(20.dp))
    Text(
        "You can turn this off anytime in Settings → Privacy.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The stats notice on its own, for riders who set up Cycler before it existed. Shown until Continue is
 * tapped; Back leaves the app as usual and the notice comes back next time.
 */
@Composable
fun StatsNoticeScreen(onContinue: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, top = 72.dp, bottom = 16.dp),
            ) { StatsNoticeContent() }
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp)) {
                CyButton("Continue", onContinue, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ReadyStep() {
    StepHeader(
        Icons.Outlined.Check, "You are ready",
        "Here is where to find things once you are in.",
    )
    Spacer(Modifier.height(20.dp))
    Hairline()
    PointRow(Icons.AutoMirrored.Outlined.DirectionsBike, "Ride", "Record a ride and watch your numbers.")
    PointRow(Icons.Outlined.Route, "Plan", "Build routes and loops, and save them.")
    PointRow(Icons.Outlined.ViewAgenda, "Rides", "Your history, with photos and a shareable video.")
    PointRow(Icons.Outlined.BarChart, "Activity", "Totals by week and your distance goal.")
    PointRow(Icons.Outlined.Tune, "Settings", "Bike type, goal and theme, from Activity.")
}
