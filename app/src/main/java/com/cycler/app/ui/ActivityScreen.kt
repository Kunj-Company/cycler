package com.cycler.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cycler.app.MainVm
import com.cycler.app.data.Bucket
import com.cycler.app.data.Care
import com.cycler.app.data.Insights
import com.cycler.app.data.Measure
import com.cycler.app.data.Period
import com.cycler.app.data.RouteMeta
import com.cycler.app.data.RouteType
import com.cycler.app.data.Totals
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


/** The number and unit shown for a measure; time carries its unit inside the number. */
private fun valueText(m: Measure, distanceM: Double, durationS: Long, ascentM: Double): Pair<String, String?> = when (m) {
    Measure.DISTANCE -> String.format(Locale.US, "%.1f", distanceM / 1000) to "km"
    Measure.TIME -> Fmt.dur(durationS) to null
    Measure.CLIMB -> "${ascentM.toInt()}" to "m"
}

/** Week, month and year at a glance: one chart you can tap, a row of totals, records and links. */
@Composable
fun ActivityScreen(vm: MainVm, initialPeriod: Period = Period.WEEK, now: Long = System.currentTimeMillis(), initialSelected: Int = -1) {
    val metas by vm.store.metas.collectAsState()
    val rides = remember(metas) { metas.filter { it.type == RouteType.RECORDED } }
    val goal = vm.weeklyGoalKm

    var periodIx by rememberSaveable { mutableIntStateOf(initialPeriod.ordinal) }
    var back by rememberSaveable { mutableIntStateOf(0) }
    var measureIx by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableIntStateOf(initialSelected) }
    var showAllEfforts by rememberSaveable { mutableStateOf(false) }
    val period = Period.values()[periodIx]
    val measure = Measure.values()[measureIx]

    val span = remember(period, back, now) { Insights.span(period, now, back) }
    val prevSpan = remember(period, back, now) { Insights.span(period, now, back + 1) }
    val buckets = remember(rides, span) { Insights.buckets(rides, span) }
    val total = remember(rides, span) { Insights.totals(rides, span) }
    val prev = remember(rides, prevSpan) { Insights.totals(rides, prevSpan) }
    val earliest = remember(rides) { rides.minOfOrNull { it.createdAt } ?: Long.MAX_VALUE }
    val sel = buckets.getOrNull(selected)?.takeIf { it.start <= now }
    val avg = remember(buckets, measure, now) { Insights.average(buckets, measure, now) }
    val todayIx = Insights.indexAt(buckets, now)
    val prevName = when (period) {
        Period.WEEK -> if (back == 0) "last week" else "the week before"
        Period.MONTH -> if (back == 0) "last month" else "the month before"
        Period.YEAR -> if (back == 0) "last year" else "the year before"
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Activity", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton({ vm.showSettings = true }) { Icon(Icons.Outlined.Settings, "Settings") }
        }

        Spacer(Modifier.height(10.dp))
        Segmented(Period.values().map { it.label }, periodIx, { periodIx = it; back = 0; selected = -1 }, Modifier.fillMaxWidth())

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Insights.title(span), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton({ back++; selected = -1 }, enabled = earliest < span.start) {
                Icon(Icons.Outlined.ChevronLeft, "Earlier")
            }
            IconButton({ back--; selected = -1 }, enabled = back > 0) {
                Icon(Icons.Outlined.ChevronRight, "Later")
            }
        }

        // What the chart says right now: the tapped bar, or the whole period.
        val (big, unit) = if (sel != null) valueText(measure, sel.distanceM, sel.durationS, sel.ascentM)
        else valueText(measure, total.distanceM, total.durationS, total.ascentM)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text(big, style = MaterialTheme.typography.displaySmall, maxLines = 1)
            if (unit != null) {
                Text(
                    " $unit", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (avg > 0) {
                val (a, au) = valueText(measure, avg * 1000, (avg * 3600).toLong(), avg)
                Text(
                    "– – avg $a${if (au != null) " $au" else ""}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
        Text(
            if (sel != null) Insights.bucketTitle(period, sel) + if (sel.rides > 0) " · ${sel.rides} ride${if (sel.rides > 1) "s" else ""}" else " · no rides"
            else "Total · ${total.rides} ride${if (total.rides == 1) "" else "s"}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))
        ActivityChart(
            values = buckets.map { it.value(measure) }, selectable = buckets.map { it.start <= now },
            selected = sel?.let { selected }, average = avg,
            descriptions = buckets.map { b ->
                val (v, u) = valueText(measure, b.distanceM, b.durationS, b.ascentM)
                Insights.bucketTitle(period, b) + ": " + v + (if (u != null) " $u" else "") +
                    if (b.start > now) " (not yet)" else if (b.rides > 0) ", ${b.rides} ride${if (b.rides > 1) "s" else ""}" else ", no rides"
            },
            onSelect = { i -> selected = if (i == selected) -1 else i },
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            buckets.forEachIndexed { i, b ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        Insights.axisLabel(period, b), style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false,
                        fontWeight = if (i == todayIx) FontWeight.Bold else null,
                        color = if (i == todayIx) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.wrapContentWidth(Alignment.CenterHorizontally, unbounded = true),
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Measure.values().forEach { m -> FilterPill(m.label, m == measure, { measureIx = m.ordinal }) }
        }

        Spacer(Modifier.height(16.dp))
        Hairline()
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            Metric("Distance", Fmt.kmShort(total.distanceM), Modifier.weight(1.1f), unit = "km")
            Metric("Rides", "${total.rides}", Modifier.weight(0.8f))
            Metric("Time", Fmt.dur(total.durationS), Modifier.weight(1.1f))
            Metric("Climb", "${total.ascentM.toInt()}", Modifier.weight(0.9f), unit = "m")
        }
        val compare = Insights.compare(measure, total, prev, prevName)
        val showGoal = period == Period.WEEK && measure == Measure.DISTANCE
        if (showGoal || compare != null) {
            Spacer(Modifier.height(12.dp))
            if (showGoal) {
                val km = total.distanceM / 1000
                Progress((km / goal).toFloat(), color = if (km >= goal) Cy.colors.success else Cy.colors.accent)
                Spacer(Modifier.height(6.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (showGoal) Arrangement.SpaceBetween else Arrangement.Start) {
                if (showGoal) {
                    val km = total.distanceM / 1000
                    Text(
                        if (km >= goal) "Weekly goal of $goal km reached" else String.format(Locale.US, "%.1f of %d km goal", km, goal),
                        style = MaterialTheme.typography.bodySmall.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (compare != null) {
                    Text(
                        compare, style = MaterialTheme.typography.bodySmall.tabular,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (rides.isEmpty()) {
            Spacer(Modifier.height(28.dp))
            Text(
                "Your totals fill in as you record rides. Head to the Ride tab and press Start.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.height(26.dp))
            SectionLabel("Records")
            Spacer(Modifier.height(2.dp))
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
                Spacer(Modifier.height(22.dp))
                SectionLabel("Best efforts")
                Spacer(Modifier.height(2.dp))
                (if (showAllEfforts) efforts else efforts.take(3)).forEach { (km, m) ->
                    val sec = m.bests[km]!!
                    RecordRow("Fastest $km km", Fmt.clock(sec * 1000) + String.format(Locale.US, "  ·  %.1f km/h", km * 3600.0 / sec), m) { vm.detailId = m.id }
                }
                if (efforts.size > 3) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { showAllEfforts = !showAllEfforts }, contentAlignment = Alignment.CenterStart) {
                        Text(
                            if (showAllEfforts) "Show fewer" else "Show all ${efforts.size}",
                            style = MaterialTheme.typography.labelLarge, color = Cy.colors.accent,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        val segTick by vm.store.segTick.collectAsState()
        val segCount by androidx.compose.runtime.produceState(0, segTick) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.store.segments().size }
        }
        val due = remember(vm.care, rides, now, vm.bikes) { Care.dueCount(vm.care, rides, now, bikes = vm.bikes) }
        Hairline()
        LinkRow("Segments", if (segCount == 0) "None yet" else "$segCount saved") { vm.showSegments = true }
        LinkRow("All rides map", "${rides.size} ride${if (rides.size == 1) "" else "s"}") { vm.showHeatmap = true }
        LinkRow(
            "Bike care",
            when {
                vm.care.isEmpty() -> "Nothing tracked"
                due == 0 -> "All up to date"
                else -> "$due job${if (due > 1) "s" else ""} due"
            },
            statusColor = if (due > 0) Cy.colors.warn else null,
        ) { vm.showCare = true }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Bars for one period. A tap or a drag picks a bar; the picked one turns accent blue and the rest
 * fade back. Bars that lie in the future stay empty and cannot be picked.
 */
@Composable
private fun ActivityChart(
    values: List<Double>, selectable: List<Boolean>, selected: Int?, average: Double, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier, descriptions: List<String> = emptyList(),
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = Cy.colors.accent
    val faint = Cy.colors.border
    val avgColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val n = values.size
    Canvas(
        modifier.fillMaxWidth().height(136.dp)
            .semantics {
                contentDescription = "Bar chart, $n bars. " + descriptions.joinToString(". ")
                if (selected != null) stateDescription = descriptions.getOrElse(selected) { "" }
                val prev = (selected?.minus(1) ?: n - 1).coerceAtLeast(0)
                val next = (selected?.plus(1) ?: 0).coerceAtMost(n - 1)
                customActions = listOf(
                    CustomAccessibilityAction("Previous bar") { if (n > 0 && selectable.getOrElse(prev) { false }) { onSelect(prev); true } else false },
                    CustomAccessibilityAction("Next bar") { if (n > 0 && selectable.getOrElse(next) { false }) { onSelect(next); true } else false },
                )
            }
            .pointerInput(n, selectable) {
                detectTapGestures { p ->
                    val i = (p.x / size.width * n).toInt().coerceIn(0, n - 1)
                    if (selectable.getOrElse(i) { false }) onSelect(i)
                }
            }
            .pointerInput(n, selectable, selected) {
                var last = -1
                detectHorizontalDragGestures(onDragStart = { last = -1 }) { change, _ ->
                    val i = (change.position.x / size.width * n).toInt().coerceIn(0, n - 1)
                    if (i != last && selectable.getOrElse(i) { false }) { last = i; if (i != selected) onSelect(i) }
                }
            },
    ) {
        if (n == 0) return@Canvas
        val top = maxOf(values.maxOrNull() ?: 0.0, average, 1e-6) * 1.08
        val slot = size.width / n
        val barW = (slot * 0.64f).coerceAtMost(34.dp.toPx())
        val minH = 2.dp.toPx()
        val radius = CornerRadius(minOf(3.dp.toPx(), barW / 2))
        val base = size.height
        drawLine(faint, Offset(0f, base), Offset(size.width, base), 1.dp.toPx())
        values.forEachIndexed { i, v ->
            val x = i * slot + (slot - barW) / 2
            if (!selectable[i]) {
                drawRoundRect(faint.copy(alpha = 0.6f), Offset(x, base - minH), Size(barW, minH), radius)
                return@forEachIndexed
            }
            val h = (v / top * base).toFloat().coerceAtLeast(minH)
            val color = when {
                v <= 0.0 -> faint
                selected == i -> accent
                selected == null -> ink.copy(alpha = 0.88f)
                else -> ink.copy(alpha = 0.26f)
            }
            drawRoundRect(color, Offset(x, base - h), Size(barW, h), radius)
        }
        if (average > 0) {
            val y = (base - average / top * base).toFloat()
            drawLine(
                avgColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
        }
    }
}

@Composable
private fun LinkRow(title: String, status: String, statusColor: androidx.compose.ui.graphics.Color? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            status, style = MaterialTheme.typography.bodySmall.tabular,
            color = statusColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.padding(start = 4.dp).size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Hairline()
}

@Composable
private fun RecordRow(label: String, value: String, m: RouteMeta, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${m.name} · ${Fmt.day(m.createdAt)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(value, style = MaterialTheme.typography.titleMedium.tabular)
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.padding(start = 4.dp).size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
            // At most 200 points per ride and about 120k overall, so the map data stays small.
            val per = if (rides.size * 200 > 120_000) maxOf(2, 120_000 / rides.size) else 200
            rides.mapNotNull { m ->
                vm.store.load(m.id)?.pts?.let { pts ->
                    val step = pts.size / per + 1
                    MapLine(pts.filterIndexed { i, _ -> i % step == 0 || i == pts.size - 1 }, COLOR_TRACK, 4f, 0.45f)
                }
            }
        }
    }
    val mapReady = lines.isNotEmpty() || rides.isEmpty()
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
                if (!mapReady) {
                    Row(Modifier.align(Alignment.TopCenter).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("  Loading your rides…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                LayerButton(vm.mapStyle, Modifier.align(Alignment.BottomEnd).padding(12.dp)) { vm.mapStyle = it; vm.prefs.mapStyle = it }
            }
        }
    }
}
