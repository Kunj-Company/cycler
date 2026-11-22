package com.cycler.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cycler.app.data.Difficulty
import com.cycler.app.data.Geo
import com.cycler.app.data.Pt
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos

object Fmt {
    fun km(m: Double): String =
        if (m < 1000) "${m.toInt()} m" else String.format(Locale.US, "%.1f km", m / 1000)

    fun kmNumber(m: Double): String = String.format(Locale.US, "%.2f", m / 1000)

    fun kmShort(m: Double): String =
        if (m >= 100_000) "${(m / 1000).toInt()}" else String.format(Locale.US, "%.1f", m / 1000)

    fun dur(s: Long): String {
        val h = s / 3600
        val m = (s % 3600) / 60
        return if (h > 0) "${h}h ${m.toString().padStart(2, '0')}m" else "${m} min"
    }

    fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        else String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    fun kmh(ms: Double): String = String.format(Locale.US, "%.1f", ms * 3.6)

    fun date(t: Long): String = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(t))

    fun day(t: Long): String = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(t))

    fun rideName(): String {
        val c = Calendar.getInstance()
        val part = when (c.get(Calendar.HOUR_OF_DAY)) {
            in 4..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..20 -> "Evening"
            else -> "Night"
        }
        return "$part ride"
    }
}

val Radius = RoundedCornerShape(10.dp)
val RadiusLarge = RoundedCornerShape(16.dp)
val SheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

enum class Btn { PRIMARY, SECONDARY, DANGER, GHOST }

/** The one button used across the app. Ink-filled by default, hairline-outlined as secondary. */
@Composable
fun CyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: Btn = Btn.PRIMARY,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 48.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val bg = when (kind) {
        Btn.PRIMARY -> scheme.primary
        Btn.DANGER -> Cy.colors.danger
        else -> Color.Transparent
    }
    val fg = when (kind) {
        Btn.PRIMARY -> scheme.onPrimary
        Btn.DANGER -> Color.White
        else -> scheme.onSurface
    }
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier.height(height).clip(Radius)
            .background(bg.copy(alpha = bg.alpha * alpha))
            .then(if (kind == Btn.SECONDARY) Modifier.border(1.dp, scheme.outline.copy(alpha = alpha), Radius) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = fg.copy(alpha = alpha))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg.copy(alpha = alpha), maxLines = 1)
    }
}

/** Notion-style segmented control. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(Radius).background(Cy.colors.subtle).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (sel) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .then(if (sel) Modifier.border(1.dp, Cy.colors.border, RoundedCornerShape(8.dp)) else Modifier)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    color = if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier,
    )
}

@Composable
fun Hairline(modifier: Modifier = Modifier) = HorizontalDivider(modifier, 1.dp, Cy.colors.border)

/** A small rounded tag. */
@Composable
fun Tag(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, filled: Boolean = false) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp))
            .background(if (filled) color.copy(alpha = 0.12f) else Cy.colors.subtle)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
fun DifficultyTag(d: Difficulty) {
    val c = when (d) {
        Difficulty.EASY -> Cy.colors.success
        Difficulty.MODERATE -> Cy.colors.accent
        Difficulty.HARD -> Cy.colors.warn
        Difficulty.EPIC -> Cy.colors.danger
    }
    Tag(d.label, c, filled = true)
}

/** A floating control that sits on top of the map. */
@Composable
fun MapChip(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, Cy.colors.border),
        shadowElevation = 2.dp,
    ) {
        Row(
            Modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** The bottom panel that carries each screen's controls. */
@Composable
fun Sheet(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = SheetShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, Cy.colors.border),
        shadowElevation = 8.dp,
    ) {
        Column(content = content)
    }
}

@Composable
fun Metric(
    label: String, value: String, modifier: Modifier = Modifier, unit: String? = null, hero: Boolean = false,
    alignment: Alignment.Horizontal = Alignment.Start,
) {
    Column(modifier, horizontalAlignment = alignment) {
        Text(
            label.uppercase(), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value, maxLines = 1,
                style = if (hero) MaterialTheme.typography.displayMedium else MaterialTheme.typography.titleLarge.tabular,
            )
            if (unit != null) {
                Text(
                    " $unit",
                    style = if (hero) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = if (hero) 7.dp else 2.dp),
                )
            }
        }
    }
}

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

@Composable
fun NameDialog(title: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RadiusLarge,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, shape = Radius) },
        confirmButton = { TextButton({ onConfirm(name.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
    )
}

/** A small line drawing of a route's shape. */
@Composable
fun RouteThumb(pts: List<Pt>, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Box(modifier.clip(Radius).background(Cy.colors.subtle)) {
        if (pts.size >= 2) {
            Canvas(Modifier.matchParentSize().padding(8.dp)) {
                val minLat = pts.minOf { it.lat }
                val maxLat = pts.maxOf { it.lat }
                val minLon = pts.minOf { it.lon }
                val maxLon = pts.maxOf { it.lon }
                val kx = cos(Math.toRadians((minLat + maxLat) / 2))
                val w = ((maxLon - minLon) * kx).coerceAtLeast(1e-6)
                val h = (maxLat - minLat).coerceAtLeast(1e-6)
                val scale = minOf(size.width / w, size.height / h)
                val ox = (size.width - w * scale) / 2
                val oy = (size.height - h * scale) / 2
                val path = Path()
                pts.forEachIndexed { i, p ->
                    val x = (ox + (p.lon - minLon) * kx * scale).toFloat()
                    val y = (oy + (maxLat - p.lat) * scale).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** Elevation against distance. Draws nothing when the points carry no elevation. */
@Composable
fun ElevationChart(pts: List<Pt>, modifier: Modifier = Modifier) {
    val data = remember(pts) {
        val out = ArrayList<Pair<Double, Double>>()
        var d = 0.0
        for (i in pts.indices) {
            if (i > 0) d += Geo.dist(pts[i - 1], pts[i])
            if (!pts[i].ele.isNaN()) out.add(d to pts[i].ele)
        }
        val step = maxOf(1, out.size / 300)
        out.filterIndexed { i, _ -> i % step == 0 || i == out.size - 1 }
    }
    if (data.size < 2) return
    val minE = data.minOf { it.second }
    val maxE = data.maxOf { it.second }
    val total = data.last().first.coerceAtLeast(1.0)
    val line = Cy.colors.accent
    val fill = Cy.colors.accent.copy(alpha = 0.10f)
    val grid = Cy.colors.border
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SectionLabel("Elevation")
            Text(
                "${minE.toInt()} – ${maxE.toInt()} m",
                style = MaterialTheme.typography.labelMedium.tabular,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Canvas(Modifier.fillMaxWidth().height(72.dp).padding(top = 6.dp)) {
            val span = (maxE - minE).coerceAtLeast(10.0)
            fun x(d: Double) = (d / total * size.width).toFloat()
            fun y(e: Double) = (size.height - (e - minE) / span * size.height * 0.9).toFloat()
            val stroke = Path()
            val area = Path()
            area.moveTo(0f, size.height)
            data.forEachIndexed { i, (d, e) ->
                if (i == 0) stroke.moveTo(x(d), y(e)) else stroke.lineTo(x(d), y(e))
                area.lineTo(x(d), y(e))
            }
            area.lineTo(size.width, size.height)
            area.close()
            drawPath(area, fill)
            drawPath(stroke, line, style = Stroke(1.6.dp.toPx(), join = StrokeJoin.Round))
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        }
    }
}

/** Distance per week as bars, oldest on the left; the last bar is this week. */
@Composable
fun WeekBars(km: List<Double>, goalKm: Int, modifier: Modifier = Modifier) {
    val bar = MaterialTheme.colorScheme.onSurface
    val soft = Cy.colors.border
    val accent = Cy.colors.accent
    Canvas(modifier.fillMaxWidth().height(96.dp)) {
        val top = maxOf(km.maxOrNull() ?: 0.0, goalKm.toDouble(), 1.0) * 1.1
        val gap = 8.dp.toPx()
        val w = (size.width - gap * (km.size - 1)) / km.size
        val gy = (size.height - goalKm / top * size.height).toFloat()
        drawLine(accent.copy(alpha = 0.6f), Offset(0f, gy), Offset(size.width, gy), 1.dp.toPx(),
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        km.forEachIndexed { i, v ->
            val h = (v / top * size.height).toFloat().coerceAtLeast(2.dp.toPx())
            drawRoundRect(
                if (v <= 0.0) soft else if (i == km.lastIndex) accent else bar,
                topLeft = Offset(i * (w + gap), size.height - h),
                size = androidx.compose.ui.geometry.Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
            )
        }
    }
}

/** Plain bars for a short series, e.g. speed for each 5 km of a ride. */
@Composable
fun MiniBars(values: List<Double>, modifier: Modifier = Modifier) {
    if (values.isEmpty()) return
    val bar = MaterialTheme.colorScheme.onSurface
    val best = Cy.colors.accent
    Canvas(modifier.fillMaxWidth().height(56.dp)) {
        val top = values.max().coerceAtLeast(1.0)
        val gap = (if (values.size > 24) 2.dp else 5.dp).toPx()
        val w = ((size.width - gap * (values.size - 1)) / values.size).coerceAtLeast(1f)
        val bestIdx = values.indexOf(values.max())
        values.forEachIndexed { i, v ->
            val h = (v / top * size.height).toFloat().coerceAtLeast(2.dp.toPx())
            drawRoundRect(
                if (i == bestIdx) best else bar.copy(alpha = 0.85f),
                topLeft = Offset(i * (w + gap), size.height - h),
                size = androidx.compose.ui.geometry.Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
            )
        }
    }
}

/** A thin progress bar. */
@Composable
fun Progress(fraction: Float, modifier: Modifier = Modifier, color: Color = Cy.colors.accent) {
    Box(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Cy.colors.subtle)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(color))
    }
}

@Composable
fun WithContentColor(color: Color, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalContentColor provides color, content = content)

@Composable
fun CySwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.material3.Switch(
        checked, onChange,
        colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedTrackColor = Cy.colors.accent, checkedThumbColor = Color.White,
            uncheckedTrackColor = Cy.colors.subtle, uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
        ),
    )
}
