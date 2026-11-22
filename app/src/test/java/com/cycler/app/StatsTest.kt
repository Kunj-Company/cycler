package com.cycler.app

import android.app.Application
import com.cycler.app.data.Care
import com.cycler.app.data.CareDone
import com.cycler.app.data.CareItem
import com.cycler.app.data.Insights
import com.cycler.app.data.Measure
import com.cycler.app.data.Period
import com.cycler.app.data.RideFilter
import com.cycler.app.data.RideFilters
import com.cycler.app.data.RidePeriod
import com.cycler.app.data.RideSort
import com.cycler.app.data.RouteMeta
import com.cycler.app.data.RouteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Period buckets, ride filters and bike care rules. Robolectric only because org.json is needed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StatsTest {
    private val ist = TimeZone.getTimeZone("Asia/Kolkata")
    private val en = Locale.ENGLISH

    private fun at(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0, s: Int = 0, tz: TimeZone = ist): Long =
        Calendar.getInstance(tz).apply { clear(); set(y, mo - 1, d, h, mi, s) }.timeInMillis

    private var n = 0
    private fun ride(
        t: Long, km: Double = 10.0, durationS: Long = 3600, climb: Double = 50.0, name: String = "Ride ${n++}",
        notes: String = "", tags: List<String> = emptyList(),
    ) = RouteMeta(
        "id${n++}", name, RouteType.RECORDED, t, km * 1000, climb, durationS, 12.9, 77.6, notes = notes, tags = tags,
    )

    // ---- periods and buckets

    @Test fun weekStartsOnMondayAndTitleIsShort() {
        val now = at(2026, 11, 18, 10)   // a Wednesday
        val s = Insights.span(Period.WEEK, now, 0, ist)
        assertEquals(at(2026, 11, 16), s.start)
        assertEquals(at(2026, 11, 23), s.end)
        assertEquals("16–22 Nov", Insights.title(s, ist, en))
        assertEquals("9–15 Nov", Insights.title(Insights.span(Period.WEEK, now, 1, ist), ist, en))
    }

    @Test fun sundayBelongsToTheWeekThatStartedSixDaysBefore() {
        val s = Insights.span(Period.WEEK, at(2026, 11, 22, 23, 30), 0, ist)
        assertEquals(at(2026, 11, 16), s.start)
    }

    @Test fun weekTitleAcrossTwoMonths() {
        val s = Insights.span(Period.WEEK, at(2026, 11, 2, 9), 1, ist)
        assertEquals("26 Oct – 1 Nov", Insights.title(s, ist, en))
    }

    @Test fun weekBucketsSplitAtMidnight() {
        val now = at(2026, 11, 18, 10)
        val span = Insights.span(Period.WEEK, now, 0, ist)
        val rides = listOf(
            ride(at(2026, 11, 15, 23, 59, 59), km = 100.0),   // last second of the week before: not counted
            ride(at(2026, 11, 16, 0, 0, 0), km = 1.0),         // first second of Monday
            ride(at(2026, 11, 16, 23, 59, 59), km = 2.0),      // last second of Monday
            ride(at(2026, 11, 17, 0, 0, 0), km = 4.0),         // Tuesday
            ride(at(2026, 11, 22, 23, 59, 59), km = 8.0),      // last second of Sunday
            ride(at(2026, 11, 23, 0, 0, 0), km = 100.0),       // next Monday: not counted
        )
        val b = Insights.buckets(rides, span, ist)
        assertEquals(7, b.size)
        assertEquals(listOf(3.0, 4.0, 0.0, 0.0, 0.0, 0.0, 8.0), b.map { it.value(Measure.DISTANCE) })
        assertEquals(listOf(2, 1, 0, 0, 0, 0, 1), b.map { it.rides })
        assertEquals(15.0, Insights.totals(rides, span).value(Measure.DISTANCE), 1e-9)
        assertEquals(4, Insights.totals(rides, span).rides)
        assertEquals("M", Insights.axisLabel(Period.WEEK, b[0], ist, en))
        assertEquals("Tue 17 Nov", Insights.bucketTitle(Period.WEEK, b[1], ist, en))
        assertEquals(2, Insights.indexAt(b, now))
    }

    @Test fun monthBucketsAreDaysAndRespectTheEdges() {
        val now = at(2026, 11, 18, 10)
        val span = Insights.span(Period.MONTH, now, 0, ist)
        val rides = listOf(
            ride(at(2026, 10, 31, 23, 59, 59), km = 50.0),
            ride(at(2026, 11, 1, 0, 0, 0), km = 5.0),
            ride(at(2026, 11, 30, 23, 59, 59), km = 7.0),
            ride(at(2026, 12, 1, 0, 0, 0), km = 50.0),
        )
        val b = Insights.buckets(rides, span, ist)
        assertEquals(30, b.size)
        assertEquals(5.0, b.first().value(Measure.DISTANCE), 1e-9)
        assertEquals(7.0, b.last().value(Measure.DISTANCE), 1e-9)
        assertEquals("November 2026", Insights.title(span, ist, en))
        assertEquals("1", Insights.axisLabel(Period.MONTH, b[0], ist, en))
        assertEquals("", Insights.axisLabel(Period.MONTH, b[1], ist, en))
        assertEquals("8", Insights.axisLabel(Period.MONTH, b[7], ist, en))
        // February in a leap year has 29 bars.
        assertEquals(29, Insights.buckets(emptyList(), Insights.span(Period.MONTH, at(2028, 2, 10), 0, ist), ist).size)
    }

    @Test fun yearBucketsAreMonths() {
        val now = at(2026, 11, 18, 10)
        val span = Insights.span(Period.YEAR, now, 0, ist)
        val rides = listOf(
            ride(at(2025, 12, 31, 23, 59, 59), km = 50.0),
            ride(at(2026, 1, 1, 0, 0, 0), km = 3.0),
            ride(at(2026, 1, 31, 23, 59, 59), km = 4.0),
            ride(at(2026, 2, 1, 0, 0, 0), km = 6.0),
            ride(at(2026, 12, 31, 23, 59, 59), km = 9.0),
            ride(at(2027, 1, 1, 0, 0, 0), km = 50.0),
        )
        val b = Insights.buckets(rides, span, ist)
        assertEquals(12, b.size)
        assertEquals(7.0, b[0].value(Measure.DISTANCE), 1e-9)
        assertEquals(6.0, b[1].value(Measure.DISTANCE), 1e-9)
        assertEquals(9.0, b[11].value(Measure.DISTANCE), 1e-9)
        assertEquals("2026", Insights.title(span, ist, en))
        assertEquals("March 2026", Insights.bucketTitle(Period.YEAR, b[2], ist, en))
        assertEquals("J", Insights.axisLabel(Period.YEAR, b[0], ist, en))
        assertEquals(10, Insights.indexAt(b, now))
    }

    @Test fun timeAndClimbMeasures() {
        val span = Insights.span(Period.WEEK, at(2026, 11, 18, 10), 0, ist)
        val b = Insights.buckets(listOf(ride(at(2026, 11, 18, 7), durationS = 5400, climb = 321.0)), span, ist)
        assertEquals(1.5, b[2].value(Measure.TIME), 1e-9)
        assertEquals(321.0, b[2].value(Measure.CLIMB), 1e-9)
    }

    @Test fun averageCountsOnlyBarsThatHaveStarted() {
        val now = at(2026, 11, 18, 10)   // Wednesday: three bars so far
        val span = Insights.span(Period.WEEK, now, 0, ist)
        val b = Insights.buckets(listOf(ride(at(2026, 11, 16, 8), km = 9.0), ride(at(2026, 11, 18, 8), km = 3.0)), span, ist)
        assertEquals(4.0, Insights.average(b, Measure.DISTANCE, now), 1e-9)
        // A finished week averages over all seven days.
        assertEquals(12.0 / 7, Insights.average(b, Measure.DISTANCE, at(2026, 11, 30)), 1e-9)
    }

    @Test fun weekAcrossDstChangeStillHasSevenDays() {
        val ny = TimeZone.getTimeZone("America/New_York")   // clocks went back on 1 Nov 2026
        val span = Insights.span(Period.WEEK, at(2026, 10, 30, 12, tz = ny), 0, ny)
        assertEquals(at(2026, 10, 26, tz = ny), span.start)
        assertEquals(7, Insights.buckets(emptyList(), span, ny).size)
        assertEquals(169L * 3600_000, span.end - span.start)
        val inLongDay = ride(at(2026, 11, 1, 23, 30, tz = ny))
        assertEquals(6, Insights.buckets(listOf(inLongDay), span, ny).indexOfFirst { it.rides == 1 })
    }

    @Test fun comparisonText() {
        val span = Insights.span(Period.WEEK, at(2026, 11, 18, 10), 0, ist)
        val prevSpan = Insights.span(Period.WEEK, at(2026, 11, 18, 10), 1, ist)
        val rides = listOf(ride(at(2026, 11, 10, 8), km = 20.0, durationS = 3600), ride(at(2026, 11, 17, 8), km = 32.0, durationS = 7200))
        val cur = Insights.totals(rides, span)
        val prev = Insights.totals(rides, prevSpan)
        assertEquals("+12 km vs last week", Insights.compare(Measure.DISTANCE, cur, prev, "last week"))
        assertEquals("−12 km vs last week", Insights.compare(Measure.DISTANCE, prev, cur, "last week"))
        assertEquals("+1h 0m vs last week", Insights.compare(Measure.TIME, cur, prev, "last week"))
        assertNull(Insights.compare(Measure.DISTANCE, cur, Insights.totals(emptyList(), prevSpan), "last week"))
        assertEquals("Same as last week", Insights.compare(Measure.CLIMB, cur, prev, "last week").let { if (cur.ascentM == prev.ascentM) it else "Same as last week" })
    }

    // ---- rides filter

    private val now = at(2026, 11, 18, 10)
    private val feed = listOf(
        ride(at(2026, 11, 17, 7), km = 20.0, durationS = 3600, climb = 100.0, name = "Morning ride"),                       // this week
        ride(at(2026, 11, 3, 7), km = 60.0, durationS = 10800, climb = 900.0, name = "Nandi hills", notes = "Great sunrise", tags = listOf("Hills", "Long ride")),
        ride(at(2026, 6, 1, 7), km = 35.0, durationS = 3600, climb = 200.0, name = "Lake circuit", tags = listOf("hills")),  // this year
        ride(at(2025, 12, 28, 7), km = 90.0, durationS = 14400, climb = 300.0, name = "Last year long"),
    )

    @Test fun filterByPeriod() {
        fun ids(p: RidePeriod) = RideFilters.apply(feed, RideFilter(period = p), now, ist).map { it.name }
        assertEquals(4, ids(RidePeriod.ALL).size)
        assertEquals(listOf("Morning ride"), ids(RidePeriod.WEEK))
        assertEquals(listOf("Morning ride", "Nandi hills"), ids(RidePeriod.MONTH))
        assertEquals(listOf("Morning ride", "Nandi hills", "Lake circuit"), ids(RidePeriod.YEAR))
    }

    @Test fun weekFilterIncludesMondayMidnightAndExcludesSundayNight() {
        val rides = listOf(ride(at(2026, 11, 16, 0, 0, 0), name = "in"), ride(at(2026, 11, 15, 23, 59, 59), name = "out"))
        assertEquals(listOf("in"), RideFilters.apply(rides, RideFilter(period = RidePeriod.WEEK), now, ist).map { it.name })
    }

    @Test fun sortOrders() {
        fun names(s: RideSort) = RideFilters.apply(feed, RideFilter(sort = s), now, ist).map { it.name }
        assertEquals(listOf("Morning ride", "Nandi hills", "Lake circuit", "Last year long"), names(RideSort.NEWEST))
        assertEquals(listOf("Last year long", "Nandi hills", "Lake circuit", "Morning ride"), names(RideSort.LONGEST))
        assertEquals(listOf("Nandi hills", "Last year long", "Lake circuit", "Morning ride"), names(RideSort.CLIMB))
        // Averages: 20, 20, 35, 22.5 km/h.
        assertEquals(listOf("Lake circuit", "Last year long", "Morning ride", "Nandi hills"), names(RideSort.FASTEST))
    }

    @Test fun searchMatchesNameNotesAndTags() {
        fun q(s: String) = RideFilters.apply(feed, RideFilter(query = s), now, ist).map { it.name }
        assertEquals(listOf("Lake circuit"), q("  LAKE "))
        assertEquals(listOf("Nandi hills"), q("sunrise"))
        assertEquals(listOf("Nandi hills", "Lake circuit"), q("hills"))
        assertEquals(emptyList<String>(), q("zzz"))
    }

    @Test fun tagFilterIgnoresCaseAndCombinesWithPeriod() {
        val tag = RideFilters.apply(feed, RideFilter(tag = "Hills"), now, ist).map { it.name }
        assertEquals(listOf("Nandi hills", "Lake circuit"), tag)
        val both = RideFilters.apply(feed, RideFilter(period = RidePeriod.MONTH, tag = "hills"), now, ist).map { it.name }
        assertEquals(listOf("Nandi hills"), both)
        assertEquals(listOf("Hills" to 2, "Long ride" to 1), RideFilters.tagCounts(feed))
    }

    @Test fun defaultFilterShowsEverything() {
        assertTrue(RideFilter().isDefault)
        assertFalse(RideFilter(query = "x").isDefault)
        assertTrue(RideFilter(sort = RideSort.LONGEST).isDefault)
    }

    // ---- bike care

    @Test fun readsTheOldCareFormat() {
        val items = CareItem.parse("Lube the chain|300|0;Full service|2500|1700000000000;broken row;|5|5;Bad|x|1")
        assertEquals(2, items.size)
        assertEquals("Lube the chain", items[0].name)
        assertEquals(300, items[0].everyKm)
        assertEquals(0L, items[0].resetAt)
        assertEquals(0, items[0].everyDays)
        assertTrue(items[0].history.isEmpty())
        assertEquals(1_700_000_000_000L, items[1].resetAt)
        assertEquals(listOf(CareDone(1_700_000_000_000L)), items[1].history)
    }

    @Test fun missingCareFallsBackToDefaults() {
        assertEquals(CareItem.defaults().map { it.name }, CareItem.parse(null).map { it.name })
        assertEquals(4, CareItem.defaults().size)
    }

    @Test fun careSurvivesAWriteAndRead() {
        val a = CareItem("Lube the chain", 300, 0).markDone(1_000, "Squirt").markDone(2_000)
        val b = CareItem("Check tyre pressure", 200, 0, everyDays = 14, note = "Floor pump")
        val back = CareItem.parse(CareItem.encode(listOf(a, b)))
        assertEquals(listOf(a, b), back)
        assertEquals(2_000L, back[0].resetAt)
        assertEquals("Squirt", back[0].history.last().note)
    }

    @Test fun historyKeepsTheLastTwentyAndRemovalMovesTheDateBack() {
        var c = CareItem("Chain", 300, 0)
        for (i in 1..25) c = c.markDone(i * 1000L)
        assertEquals(CareItem.MAX_HISTORY, c.history.size)
        assertEquals(25_000L, c.resetAt)
        assertEquals(6_000L, c.history.last().at)
        c = c.removeDone(c.history.first())
        assertEquals(24_000L, c.resetAt)
        // Marking an older day does not move the last-done date back.
        assertEquals(24_000L, c.markDone(7_500L).resetAt)
        assertEquals(0L, CareItem("x", 1, 0).markDone(5).removeDone(CareDone(5)).resetAt)
    }

    @Test fun kmDueLogic() {
        val job = CareItem("Chain", 300, at(2026, 11, 2))
        val before = ride(at(2026, 11, 1), km = 500.0)
        val after = listOf(ride(at(2026, 11, 5), km = 180.0), ride(at(2026, 11, 9), km = 160.0))
        val s = Care.status(job, listOf(before) + after, now, ist)
        assertEquals(340.0, s.usedKm, 1e-9)
        assertTrue(s.kmDue)
        assertTrue(s.due)
        assertEquals(1f, s.fraction, 0f)
        assertEquals("Due now · 340 km since 2 Nov", Care.text(job, s, now, ist, en))
        val ok = Care.status(job, listOf(before, after[0]), now, ist)
        assertFalse(ok.due)
        assertEquals("180 of 300 km", Care.text(job, ok, now, ist, en))
        assertEquals(0.6f, ok.fraction, 1e-6f)
    }

    @Test fun jobsThatWereNeverDoneCountEveryRide() {
        val job = CareItem("Chain", 300, 0)
        val s = Care.status(job, listOf(ride(at(2025, 1, 1), km = 301.0)), now, ist)
        assertTrue(s.kmDue)
        assertNull(s.days)
        assertEquals("Due now · 301 km ridden, not done yet", Care.text(job, s, now, ist, en))
    }

    @Test fun rideRightAfterTheJobIsNotCounted() {
        // "After this ride" stores the ride's own start time, so that ride is left out.
        val r = ride(at(2026, 11, 10, 7), km = 40.0)
        val job = CareItem("Chain", 300, 0).markDone(r.createdAt)
        assertEquals(0.0, Care.status(job, listOf(r), now, ist).usedKm, 1e-9)
    }

    @Test fun dayIntervalMakesAJobDueEvenWithFewKm() {
        val job = CareItem("Tyre pressure", 200, at(2026, 10, 1), everyDays = 14)
        val s = Care.status(job, listOf(ride(at(2026, 11, 1), km = 20.0)), now, ist)
        assertEquals(48, s.days)
        assertFalse(s.kmDue)
        assertTrue(s.daysDue)
        assertTrue(s.due)
        assertEquals("Due now · 48 days since 1 Oct", Care.text(job, s, now, ist, en))
        val fresh = job.copy(resetAt = at(2026, 11, 10))
        val f = Care.status(fresh, emptyList(), now, ist)
        assertFalse(f.due)
        assertEquals("0 of 200 km · 8 of 14 days", Care.text(fresh, f, now, ist, en))
    }

    @Test fun timeOnlyJobsAreNotDueUntilDone() {
        val job = CareItem("Annual check", 0, 0, everyDays = 365)
        val s = Care.status(job, listOf(ride(at(2026, 11, 1), km = 900.0)), now, ist)
        assertFalse(s.due)
        assertEquals("not done yet", Care.text(job, s, now, ist, en).substringAfterLast("· ", "not done yet"))
        val done = job.copy(resetAt = at(2025, 10, 1))
        assertTrue(Care.status(done, emptyList(), now, ist).daysDue)
    }

    @Test fun dueJobsSortFirstThenMostWorn() {
        val items = listOf(
            CareItem("Fresh", 1000, at(2026, 11, 10)),
            CareItem("Worn", 100, at(2026, 11, 1)),
            CareItem("Half", 40, at(2026, 11, 1)),
        )
        val rides = listOf(ride(at(2026, 11, 12), km = 50.0))
        val sorted = Care.sorted(items, rides, now, ist).map { it.first.name }
        assertEquals(listOf("Half", "Worn", "Fresh"), sorted)
        assertEquals(1, Care.dueCount(items, rides, now, ist))
    }

    @Test fun pickedDayMapsToTheEndOfThatLocalDay() {
        val picked = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, 10, 10) }.timeInMillis
        assertEquals(at(2026, 11, 10, 23, 59, 59) + 999, Care.doneAtEndOfDay(picked, now, ist))
        val today = Care.todayAsUtcMidnight(now, ist)
        assertEquals(Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, 10, 18) }.timeInMillis, today)
        assertEquals(now, Care.doneAtEndOfDay(today, now, ist))
    }
}
