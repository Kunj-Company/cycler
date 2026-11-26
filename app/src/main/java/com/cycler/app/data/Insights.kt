package com.cycler.app.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Pure helpers behind the Activity chart, the Rides filters and Bike care. No Android types, so they are easy to test. */

enum class Period(val label: String) { WEEK("Week"), MONTH("Month"), YEAR("Year") }

enum class Measure(val label: String) { DISTANCE("Distance"), TIME("Time"), CLIMB("Climb") }

/** A stretch of time, [start] inclusive to [end] exclusive, in milliseconds. */
class Span(val period: Period, val start: Long, val end: Long)

/** One bar of the chart: a day (week and month views) or a month (year view). */
class Bucket(
    val start: Long, val end: Long, val rides: Int,
    val distanceM: Double, val durationS: Long, val ascentM: Double,
) {
    /** The bar's value in the chart's unit: km, hours or metres. */
    fun value(m: Measure): Double = when (m) {
        Measure.DISTANCE -> distanceM / 1000
        Measure.TIME -> durationS / 3600.0
        Measure.CLIMB -> ascentM
    }
}

class Totals(val rides: Int, val distanceM: Double, val durationS: Long, val ascentM: Double) {
    fun value(m: Measure): Double = when (m) {
        Measure.DISTANCE -> distanceM / 1000
        Measure.TIME -> durationS / 3600.0
        Measure.CLIMB -> ascentM
    }
}

object Insights {
    private const val DAY = 24L * 3600 * 1000

    private fun cal(tz: TimeZone, t: Long): Calendar =
        Calendar.getInstance(tz, Locale.US).apply {
            firstDayOfWeek = Calendar.MONDAY
            minimalDaysInFirstWeek = 4
            timeInMillis = t
        }

    private fun floorDay(c: Calendar) {
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    }

    /** Local midnight at the start of the day, week (Monday), month or year that contains [t]. */
    fun startOf(period: Period, t: Long, tz: TimeZone = TimeZone.getDefault()): Long {
        val c = cal(tz, t)
        floorDay(c)
        when (period) {
            Period.WEEK -> c.add(Calendar.DAY_OF_YEAR, -((c.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7))
            Period.MONTH -> c.set(Calendar.DAY_OF_MONTH, 1)
            Period.YEAR -> c.set(Calendar.DAY_OF_YEAR, 1)
        }
        return c.timeInMillis
    }

    private fun shift(period: Period, start: Long, n: Int, tz: TimeZone): Long {
        val c = cal(tz, start)
        when (period) {
            Period.WEEK -> c.add(Calendar.DAY_OF_YEAR, 7 * n)
            Period.MONTH -> c.add(Calendar.MONTH, n)
            Period.YEAR -> c.add(Calendar.YEAR, n)
        }
        return c.timeInMillis
    }

    /** The period that contains [now], or the one [back] periods before it. */
    fun span(period: Period, now: Long, back: Int = 0, tz: TimeZone = TimeZone.getDefault()): Span {
        val start = shift(period, startOf(period, now, tz), -back, tz)
        return Span(period, start, shift(period, start, 1, tz))
    }

    /** Where each bar starts and ends, plus the closing edge. Days for week and month, months for year. */
    private fun edges(span: Span, tz: TimeZone): List<Long> {
        val out = ArrayList<Long>()
        val c = cal(tz, span.start)
        while (c.timeInMillis < span.end) {
            out.add(c.timeInMillis)
            if (span.period == Period.YEAR) c.add(Calendar.MONTH, 1) else c.add(Calendar.DAY_OF_YEAR, 1)
        }
        out.add(span.end)
        return out
    }

    fun buckets(rides: List<RouteMeta>, span: Span, tz: TimeZone = TimeZone.getDefault()): List<Bucket> {
        val e = edges(span, tz)
        val n = e.size - 1
        val rc = IntArray(n); val dist = DoubleArray(n); val dur = LongArray(n); val up = DoubleArray(n)
        for (r in rides) {
            if (r.createdAt < span.start || r.createdAt >= span.end) continue
            var i = 0
            while (i < n - 1 && r.createdAt >= e[i + 1]) i++
            rc[i]++; dist[i] += r.distanceM; dur[i] += r.durationS; up[i] += r.ascentM
        }
        return (0 until n).map { Bucket(e[it], e[it + 1], rc[it], dist[it], dur[it], up[it]) }
    }

    fun totals(rides: List<RouteMeta>, span: Span): Totals {
        val r = rides.filter { it.createdAt >= span.start && it.createdAt < span.end }
        return Totals(r.size, r.sumOf { it.distanceM }, r.sumOf { it.durationS }, r.sumOf { it.ascentM })
    }

    /** The mean bar height over the bars that have started by [now]; 0 when none has. */
    fun average(buckets: List<Bucket>, m: Measure, now: Long): Double {
        val past = buckets.filter { it.start <= now }
        return if (past.isEmpty()) 0.0 else past.sumOf { it.value(m) } / past.size
    }

    /** Index of the bar holding [t], or -1 when [t] is outside the chart. */
    fun indexAt(buckets: List<Bucket>, t: Long): Int = buckets.indexOfFirst { t >= it.start && t < it.end }

    private fun fmt(pattern: String, tz: TimeZone, locale: Locale) =
        SimpleDateFormat(pattern, locale).apply { timeZone = tz }

    /** "16–22 Nov", "November 2026" or "2026". */
    fun title(span: Span, tz: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()): String = when (span.period) {
        Period.YEAR -> fmt("yyyy", tz, locale).format(Date(span.start))
        Period.MONTH -> fmt("MMMM yyyy", tz, locale).format(Date(span.start))
        Period.WEEK -> {
            val last = Date(span.end - 1)
            val first = Date(span.start)
            val sameMonth = fmt("yyyyMM", tz, locale).format(first) == fmt("yyyyMM", tz, locale).format(last)
            if (sameMonth) fmt("d", tz, locale).format(first) + "–" + fmt("d MMM", tz, locale).format(last)
            else fmt("d MMM", tz, locale).format(first) + " – " + fmt("d MMM", tz, locale).format(last)
        }
    }

    /** The full date of one bar: "Tue 18 Nov", or "March 2026" in the year view. */
    fun bucketTitle(period: Period, b: Bucket, tz: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()): String =
        if (period == Period.YEAR) fmt("MMMM yyyy", tz, locale).format(Date(b.start))
        else fmt("EEE d MMM", tz, locale).format(Date(b.start))

    /** The short label under a bar; empty for bars that go without one so the axis stays readable. */
    fun axisLabel(period: Period, b: Bucket, tz: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()): String = when (period) {
        Period.WEEK -> fmt("EEE", tz, locale).format(Date(b.start)).take(1)
        Period.YEAR -> fmt("MMM", tz, locale).format(Date(b.start)).take(1)
        Period.MONTH -> fmt("d", tz, locale).format(Date(b.start)).let { if (it in setOf("1", "8", "15", "22", "29")) it else "" }
    }

    private fun signed(v: Double, digits: Int, unit: String): String {
        val sign = if (v < 0) "−" else "+"
        return sign + String.format(Locale.US, "%.${digits}f", kotlin.math.abs(v)) + " " + unit
    }

    /** "+12 km vs last week"; null when the earlier period has nothing to compare with. */
    fun compare(m: Measure, cur: Totals, prev: Totals, prevName: String): String? {
        if (prev.rides == 0) return null
        val d = cur.value(m) - prev.value(m)
        val text = when (m) {
            Measure.DISTANCE -> if (kotlin.math.abs(d) < 0.05) null else signed(d, if (kotlin.math.abs(d) >= 10) 0 else 1, "km")
            Measure.CLIMB -> if (kotlin.math.abs(d) < 0.5) null else signed(d, 0, "m")
            Measure.TIME -> {
                val s = kotlin.math.abs(cur.durationS - prev.durationS)
                if (s < 60) null else (if (cur.durationS < prev.durationS) "−" else "+") +
                    (if (s >= 3600) "${s / 3600}h ${(s % 3600) / 60}m" else "${s / 60} min")
            }
        }
        return if (text == null) "Same as $prevName" else "$text vs $prevName"
    }
}

// ---------------------------------------------------------------- Rides filters

enum class RidePeriod(val label: String) { ALL("All"), WEEK("This week"), MONTH("This month"), YEAR("This year") }

enum class RideSort(val label: String) {
    NEWEST("Newest"), LONGEST("Longest"), CLIMB("Most climbing"), FASTEST("Fastest average"),
}

data class RideFilter(
    val period: RidePeriod = RidePeriod.ALL,
    val sort: RideSort = RideSort.NEWEST,
    val query: String = "",
    val tag: String? = null,
    /** Only rides on this bike (experimental, multiple bikes); null for all bikes. */
    val bikeId: String? = null,
) {
    /** True when the list is showing everything in the default order. */
    val isDefault get() = period == RidePeriod.ALL && query.isBlank() && tag == null && bikeId == null
}

object RideFilters {
    fun avgSpeed(m: RouteMeta): Double = if (m.durationS > 0) m.distanceM / m.durationS else 0.0

    fun apply(
        rides: List<RouteMeta>, f: RideFilter, now: Long = System.currentTimeMillis(), tz: TimeZone = TimeZone.getDefault(),
        bikes: BikeList? = null,
    ): List<RouteMeta> {
        val from = when (f.period) {
            RidePeriod.ALL -> Long.MIN_VALUE
            RidePeriod.WEEK -> Insights.startOf(Period.WEEK, now, tz)
            RidePeriod.MONTH -> Insights.startOf(Period.MONTH, now, tz)
            RidePeriod.YEAR -> Insights.startOf(Period.YEAR, now, tz)
        }
        val q = f.query.trim()
        val out = rides.filter { m ->
            m.createdAt >= from &&
                (f.bikeId == null || bikes == null || bikes.ownerOf(m.bikeId) == f.bikeId) &&
                (f.tag == null || m.tags.any { it.equals(f.tag, ignoreCase = true) }) &&
                (q.isEmpty() || m.name.contains(q, true) || m.notes.contains(q, true) || m.tags.any { it.contains(q, true) })
        }
        return when (f.sort) {
            RideSort.NEWEST -> out.sortedByDescending { it.createdAt }
            RideSort.LONGEST -> out.sortedWith(compareByDescending<RouteMeta> { it.distanceM }.thenByDescending { it.createdAt })
            RideSort.CLIMB -> out.sortedWith(compareByDescending<RouteMeta> { it.ascentM }.thenByDescending { it.createdAt })
            RideSort.FASTEST -> out.sortedWith(compareByDescending<RouteMeta> { avgSpeed(it) }.thenByDescending { it.createdAt })
        }
    }

    /** Every tag used by [rides] with how many rides carry it, most used first. */
    fun tagCounts(rides: List<RouteMeta>): List<Pair<String, Int>> =
        rides.flatMap { r -> r.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() } }
            .groupBy { it.lowercase() }
            .map { (_, v) -> v.first() to v.size }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })
}

// ---------------------------------------------------------------- Bike care

class CareStatus(
    val usedKm: Double,
    /** Whole days since the job was last done; null when it never was. */
    val days: Int?,
    val kmDue: Boolean,
    val daysDue: Boolean,
    /** How far through the interval, 0 to 1, by whichever limit is closer. */
    val fraction: Float,
) {
    val due get() = kmDue || daysDue
}

object Care {
    fun daysBetween(from: Long, to: Long, tz: TimeZone = TimeZone.getDefault()): Int {
        val a = cal(tz, from); val b = cal(tz, to)
        return Math.round((b.timeInMillis - a.timeInMillis) / (24.0 * 3600 * 1000)).toInt()
    }

    private fun cal(tz: TimeZone, t: Long) = Calendar.getInstance(tz, Locale.US).apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    /** Distance since the job was done comes from the rides recorded after that moment. */
    fun status(item: CareItem, rides: List<RouteMeta>, now: Long, tz: TimeZone = TimeZone.getDefault(), bikes: BikeList? = null): CareStatus {
        // With several bikes only the rides on the job's own bike count towards it.
        val mine = if (bikes == null) rides else bikes.ownerOf(item.bikeId).let { b -> rides.filter { bikes.ownerOf(it.bikeId) == b } }
        val used = mine.filter { it.createdAt > item.resetAt }.sumOf { it.distanceM } / 1000
        val days = if (item.resetAt > 0) daysBetween(item.resetAt, now, tz).coerceAtLeast(0) else null
        val kmDue = item.everyKm > 0 && used >= item.everyKm
        val daysDue = item.everyDays > 0 && days != null && days >= item.everyDays
        val fKm = if (item.everyKm > 0) used / item.everyKm else 0.0
        val fDays = if (item.everyDays > 0 && days != null) days.toDouble() / item.everyDays else 0.0
        return CareStatus(used, days, kmDue, daysDue, maxOf(fKm, fDays).coerceIn(0.0, 1.0).toFloat())
    }

    /** Due jobs first, then the most worn. */
    fun sorted(items: List<CareItem>, rides: List<RouteMeta>, now: Long, tz: TimeZone = TimeZone.getDefault(), bikes: BikeList? = null): List<Pair<CareItem, CareStatus>> =
        items.map { it to status(it, rides, now, tz, bikes) }
            .sortedWith(compareByDescending<Pair<CareItem, CareStatus>> { it.second.due }.thenByDescending { it.second.fraction })

    fun dueCount(items: List<CareItem>, rides: List<RouteMeta>, now: Long, tz: TimeZone = TimeZone.getDefault(), bikes: BikeList? = null): Int =
        items.count { status(it, rides, now, tz, bikes).due }

    private fun short(t: Long, now: Long, tz: TimeZone, locale: Locale): String {
        val year = Calendar.getInstance(tz).apply { timeInMillis = t }.get(Calendar.YEAR)
        val thisYear = Calendar.getInstance(tz).apply { timeInMillis = now }.get(Calendar.YEAR)
        return SimpleDateFormat(if (year == thisYear) "d MMM" else "d MMM yyyy", locale).apply { timeZone = tz }.format(Date(t))
    }

    fun dateText(t: Long, now: Long, tz: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()) = short(t, now, tz, locale)

    /** "180 of 300 km", or "Due now · 340 km since 2 Nov". */
    fun text(
        item: CareItem, s: CareStatus, now: Long, tz: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault(),
    ): String {
        val since = if (item.resetAt > 0) "since " + short(item.resetAt, now, tz, locale) else "ridden, not done yet"
        if (s.kmDue) return "Due now · ${s.usedKm.toInt()} km $since"
        if (s.daysDue) return "Due now · ${s.days} days since " + short(item.resetAt, now, tz, locale)
        val parts = ArrayList<String>()
        if (item.everyKm > 0) parts.add("${s.usedKm.toInt()} of ${item.everyKm} km")
        if (item.everyDays > 0) parts.add(if (s.days != null) "${s.days} of ${item.everyDays} days" else "not done yet")
        return if (parts.isEmpty()) "Not done yet" else parts.joinToString(" · ")
    }

    /**
     * The moment a job counts as done when the rider picks a calendar day. The picker reports
     * midnight UTC of the chosen date; the job is taken to be done at the end of that local day,
     * or right now when the day is today, so rides on that day count as before it.
     */
    fun doneAtEndOfDay(pickedUtcMidnight: Long, now: Long, tz: TimeZone = TimeZone.getDefault()): Long {
        val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = pickedUtcMidnight }
        val c = Calendar.getInstance(tz).apply {
            clear()
            set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH), 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }
        return minOf(c.timeInMillis, now)
    }

    /** Midnight UTC of today's local date: the latest day the date picker may offer. */
    fun todayAsUtcMidnight(now: Long, tz: TimeZone = TimeZone.getDefault()): Long {
        val l = Calendar.getInstance(tz).apply { timeInMillis = now }
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(l.get(Calendar.YEAR), l.get(Calendar.MONTH), l.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }

    /** Ready-made jobs offered when adding one: name, km interval, day interval. */
    val presets = listOf(
        Triple("Lube the chain", 300, 0),
        Triple("Check tyre pressure", 200, 14),
        Triple("Check brake pads", 1000, 0),
        Triple("Clean the drivetrain", 800, 0),
        Triple("Replace chain", 4000, 0),
        Triple("Full service", 2500, 365),
        Triple("Replace tyres", 5000, 0),
    )
}
