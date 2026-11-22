package com.cycler.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cycler.app.data.Geo
import com.cycler.app.data.Pt
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Fmt {
    fun km(m: Double): String =
        if (m < 1000) "${m.toInt()} m" else String.format(Locale.US, "%.1f km", m / 1000)

    fun kmNumber(m: Double): String = String.format(Locale.US, "%.2f", m / 1000)

    fun dur(s: Long): String {
        val h = s / 3600
        val m = (s % 3600) / 60
        return if (h > 0) "${h}h ${m.toString().padStart(2, '0')}m" else "${m} min"
    }

    fun clock(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    fun kmh(ms: Double): String = String.format(Locale.US, "%.1f", ms * 3.6)

    fun date(t: Long): String = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(t))

    fun rideName(): String {
        val c = Calendar.getInstance()
        val part = when (c.get(Calendar.HOUR_OF_DAY)) {
            in 4..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..20 -> "Evening"
            else -> "Night"
        }
        return "$part ride · " + SimpleDateFormat("d MMM", Locale.getDefault()).format(c.time)
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, big: Boolean = false) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = if (big) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val line = MaterialTheme.colorScheme.primary
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Elevation", style = MaterialTheme.typography.labelMedium)
            Text(
                "${minE.toInt()} m – ${maxE.toInt()} m",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Canvas(Modifier.fillMaxWidth().height(84.dp).padding(top = 4.dp)) {
            val span = (maxE - minE).coerceAtLeast(10.0)
            fun x(d: Double) = (d / total * size.width).toFloat()
            fun y(e: Double) = (size.height - (e - minE) / span * size.height * 0.92).toFloat()
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
            drawPath(stroke, line, style = Stroke(width = 2.dp.toPx()))
            drawLine(line.copy(alpha = 0.3f), Offset(0f, size.height), Offset(size.width, size.height))
        }
    }
}
