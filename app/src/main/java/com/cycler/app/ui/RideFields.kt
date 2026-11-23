package com.cycler.app.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.TurnLeft
import androidx.compose.material.icons.outlined.TurnRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cycler.app.MainVm
import com.cycler.app.data.Workout
import com.cycler.app.track.TrackState
import com.cycler.app.track.TrackStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The numbers that can be shown on the big ride screen, and how each is worked out. */
object RideFields {
    class Field(val key: String, val label: String, val unit: String)

    val all = listOf(
        Field("speed", "Speed", "km/h"), Field("distance", "Distance", "km"), Field("moving", "Moving time", ""),
        Field("elapsed", "Total time", ""), Field("avg", "Average", "km/h"), Field("max", "Top speed", "km/h"),
        Field("climb", "Climb", "m"), Field("lapDistance", "Lap distance", "km"), Field("lapTime", "Lap time", ""),
        Field("lapAvg", "Lap average", "km/h"), Field("lapNumber", "Lap", ""), Field("toGo", "To go", "km"),
        Field("stops", "Stops", ""), Field("clock", "Time of day", ""),
    )

    const val DEFAULT = "speed,distance,moving|avg,max,climb,elapsed|lapDistance,lapTime,lapAvg,clock"
    const val MAX_PER_PAGE = 6

    fun of(key: String) = all.firstOrNull { it.key == key }

    fun pages(spec: String): List<List<String>> =
        spec.split('|').map { p -> p.split(',').filter { k -> of(k) != null } }.filter { it.isNotEmpty() }
            .ifEmpty { pages(DEFAULT) }

    fun join(pages: List<List<String>>) = pages.filter { it.isNotEmpty() }.joinToString("|") { it.joinToString(",") }

    fun value(key: String, st: TrackState, now: Long, climbM: Double): String = when (key) {
        "speed" -> Fmt.kmh(st.speedMs.toDouble())
        "distance" -> Fmt.kmNumber(st.distanceM)
        "moving" -> Fmt.clock(st.movingMs)
        "elapsed" -> Fmt.clock(st.elapsedMs(now))
        "avg" -> Fmt.kmh(if (st.movingMs > 0) st.distanceM / (st.movingMs / 1000.0) else 0.0)
        "max" -> Fmt.kmh(st.maxSpeedMs.toDouble())
        "climb" -> "${climbM.toInt()}"
        "lapDistance" -> Fmt.kmNumber(st.lapDistanceM)
        "lapTime" -> Fmt.clock(st.lapMovingMs)
        "lapAvg" -> Fmt.kmh(if (st.lapMovingMs > 0) st.lapDistanceM / (st.lapMovingMs / 1000.0) else 0.0)
        "lapNumber" -> "${st.laps.size + 1}"
        "toGo" -> if (st.guideTotalM > 0) Fmt.kmNumber((st.guideTotalM - st.guideDoneM).coerceAtLeast(0.0)) else "–"
        "stops" -> "${st.stops}"
        "clock" -> SimpleDateFormat("h:mm", Locale.getDefault()).format(Date(now))
        else -> "–"
    }
}

/** The current workout block: what to do, how long is left, and what comes next. */
@Composable
fun WorkoutBar(w: Workout, st: TrackState, modifier: Modifier = Modifier) {
    val step = w.steps.getOrNull(st.workoutStep)
    Column(modifier.fillMaxWidth()) {
        if (step == null) {
            Text("Workout complete", style = MaterialTheme.typography.labelLarge, color = Cy.colors.success)
            return@Column
        }
        val hard = step.effort >= 2
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                SectionLabel("Workout · block ${st.workoutStep + 1} of ${w.steps.size}")
                Text(step.label, style = MaterialTheme.typography.titleLarge, color = if (hard) Cy.colors.danger else MaterialTheme.colorScheme.onSurface)
            }
            Text(Fmt.clock(st.workoutLeftS * 1000L), style = MaterialTheme.typography.displaySmall)
        }
        Spacer(Modifier.height(6.dp))
        Progress(1f - st.workoutLeftS.toFloat() / step.seconds.coerceAtLeast(1), color = if (hard) Cy.colors.danger else Cy.colors.accent)
        w.steps.getOrNull(st.workoutStep + 1)?.let { next ->
            Text(
                "Next: ${next.label}, ${Fmt.clock(next.seconds * 1000L)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Big numbers for the handlebar: swipe between the pages chosen in Settings. */
@Composable
fun BigNumbers(vm: MainVm, st: TrackState, now: Long, climbM: Double, climbNote: String?, workout: Workout?, onClose: () -> Unit) {
    val pages = RideFields.pages(vm.ridePages)
    val pager = rememberPagerState { pages.size }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    when {
                        st.offRoute -> Text("Off route · ${st.guideOffM?.toInt() ?: 0} m", style = MaterialTheme.typography.titleLarge, color = Cy.colors.danger)
                        st.nextTurn != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (st.nextTurnLeft) Icons.Outlined.TurnLeft else Icons.Outlined.TurnRight, null, Modifier.size(34.dp), tint = Cy.colors.accent)
                            Spacer(Modifier.width(8.dp))
                            Text("${st.nextTurn} · ${Fmt.km(st.nextTurnM)}", style = MaterialTheme.typography.titleLarge)
                        }
                        else -> Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(if (st.autoPaused || st.status == TrackStatus.PAUSED) Cy.colors.warn else Cy.colors.danger, 10.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when { st.status == TrackStatus.PAUSED -> "Paused"; st.autoPaused -> "Auto-paused"; else -> "Recording" },
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
                IconButton(onClose) { Icon(Icons.Outlined.Close, "Back to the map") }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                val keys = pages[page]
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                    // One to three fields: the first is the hero. More than that: an even grid.
                    val hero = if (keys.size <= 3) keys.take(1) else emptyList()
                    hero.forEach { BigField(it, st, now, climbM, if (keys.size == 1) 150 else 128, Modifier.fillMaxWidth()) }
                    val rest = keys.drop(hero.size)
                    val size = when { rest.size <= 2 -> 62; rest.size <= 4 -> 58; else -> 46 }
                    rest.chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEach { BigField(it, st, now, climbM, size, Modifier.weight(1f)) }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            if (workout != null && st.workoutStep >= -1) {
                WorkoutBar(workout, st, Modifier.padding(bottom = 10.dp))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    climbNote ?: "Swipe for more · change these in Settings", style = MaterialTheme.typography.bodySmall,
                    color = if (climbNote != null) Cy.colors.warn else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(pages.size) { i ->
                        StatusDot(if (i == pager.currentPage) MaterialTheme.colorScheme.onSurface else Cy.colors.border, 7.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun BigField(key: String, st: TrackState, now: Long, climbM: Double, size: Int, modifier: Modifier) {
    val f = RideFields.of(key) ?: return
    // The big numbers are already huge; cap the system font scale here so they cannot overflow at 200%.
    val d = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, d.fontScale.coerceAtMost(1.3f)),
    ) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            RideFields.value(key, st, now, climbM), maxLines = 1,
            style = MaterialTheme.typography.displayLarge.copy(fontSize = size.sp, lineHeight = (size + 4).sp),
        )
        Text(
            if (f.unit.isEmpty()) f.label.lowercase() else "${f.label.lowercase()} · ${f.unit}",
            style = if (size > 100) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    }
}

/** Settings page for choosing what the big ride screen shows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RideScreenEditor(vm: MainVm) {
    val pages = RideFields.pages(vm.ridePages)
    fun save(p: List<List<String>>) {
        val spec = RideFields.join(p).ifEmpty { RideFields.DEFAULT }
        vm.ridePages = spec
        vm.prefs.ridePages = spec
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(start = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ vm.showFieldEditor = false }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Text("Ride screen", style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(
                    "Choose what the big-number screen shows while you ride. Each page holds up to ${RideFields.MAX_PER_PAGE} numbers; " +
                        "with one to three, the first is shown largest. Swipe between pages on the ride.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                pages.forEachIndexed { i, keys ->
                    Row(Modifier.padding(top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("Page ${i + 1} · ${keys.size} of ${RideFields.MAX_PER_PAGE}", Modifier.weight(1f))
                        if (pages.size > 1) {
                            Text(
                                "Remove page", style = MaterialTheme.typography.labelMedium, color = Cy.colors.danger,
                                modifier = Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button) { save(pages.filterIndexed { k, _ -> k != i }) }.minimumInteractiveComponentSize().padding(4.dp),
                            )
                        }
                    }
                    // Chosen fields first, in their order, then the rest.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        (keys + RideFields.all.map { it.key }.filter { it !in keys }).forEach { key ->
                            val on = key in keys
                            Pill(RideFields.of(key)!!.label, on) {
                                val next = if (on) keys - key else if (keys.size < RideFields.MAX_PER_PAGE) keys + key else keys
                                if (next.isNotEmpty()) save(pages.mapIndexed { k, p -> if (k == i) next else p })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CyButton("Add a page", { save(pages + listOf(listOf("speed"))) }, Modifier.weight(1f), Btn.SECONDARY, enabled = pages.size < 6)
                    CyButton("Reset", { save(RideFields.pages(RideFields.DEFAULT)) }, Modifier.weight(1f), Btn.SECONDARY)
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}
