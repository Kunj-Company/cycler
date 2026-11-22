package com.cycler.app.widget

import com.cycler.app.data.RouteMeta
import com.cycler.app.data.RouteType
import com.cycler.app.track.TrackState
import com.cycler.app.ui.Stats
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Everything a widget needs, read once per refresh. [rides] are recorded rides only, newest first. */
class WidgetSnapshot(
    val track: TrackState,
    val rides: List<RouteMeta>,
    val goalKm: Int,
    val now: Long = System.currentTimeMillis(),
) {
    companion object {
        /** Recorded rides only: planned routes are not rides, as on the Activity screen. */
        fun of(track: TrackState, metas: List<RouteMeta>, goalKm: Int, now: Long = System.currentTimeMillis()) =
            WidgetSnapshot(track, metas.filter { it.type == RouteType.RECORDED }.sortedByDescending { it.createdAt }, goalKm, now)
    }
}

object WidgetData {
    /** This week, with the same Monday-to-Sunday definition as the Activity screen. */
    fun week(s: WidgetSnapshot): Stats.Sum = Stats.since(s.rides, Stats.weekStart(s.now))

    fun month(s: WidgetSnapshot): Stats.Sum = Stats.since(s.rides, Stats.monthStart(s.now))

    /** Metres ridden on each of the last 7 days, oldest first, today last. */
    fun lastSevenDays(rides: List<RouteMeta>, now: Long): List<Double> {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -6)
        }
        return (0 until 7).map {
            val from = c.timeInMillis
            c.add(Calendar.DAY_OF_YEAR, 1)
            Stats.since(rides, from, c.timeInMillis).distanceM
        }
    }

    /** One-letter weekday names for the last 7 days, oldest first, in the phone's language. */
    fun weekdayInitials(now: Long): List<String> {
        val f = SimpleDateFormat("EEE", Locale.getDefault())
        val c = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, -6) }
        return (0 until 7).map {
            // First letter of the short name: the narrow pattern ("EEEEE") is not the same on every JVM and ICU.
            f.format(Date(c.timeInMillis)).trim().take(1).uppercase(Locale.getDefault()).also { c.add(Calendar.DAY_OF_YEAR, 1) }
        }
    }

    /** Average moving speed in metres per second; 0 until there is something to divide by. */
    fun avgSpeedMs(distanceM: Double, movingS: Long): Double = if (movingS > 0) distanceM / movingS else 0.0
}
