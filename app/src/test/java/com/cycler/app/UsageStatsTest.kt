package com.cycler.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cycler.app.analytics.Feature
import com.cycler.app.analytics.StatsSender
import com.cycler.app.analytics.UsageStats
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UsageStatsTest {
    private lateinit var ctx: Context
    private var day = LocalDate.of(2026, 11, 24) // Tuesday of 2026-W48

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        UsageStats.resetForTests(ctx)
        UsageStats.today = { day }
        UsageStats.setNoticeSeen(ctx)
    }

    @After fun tearDown() = UsageStats.resetForTests(ctx)

    /** A fake server: answers each request with the next status and keeps what it was sent. */
    private class FakeHttp(vararg codes: Int) : StatsSender.Http {
        val codes = codes.toMutableList()
        val sent = mutableListOf<Pair<String, JSONObject>>()
        override fun post(url: String, json: String): Int {
            sent += url to JSONObject(json)
            return if (codes.isEmpty()) 204 else codes.removeAt(0)
        }
    }

    private fun send(http: StatsSender.Http, includeCurrent: Boolean = false) =
        runBlocking { StatsSender.run(ctx, "https://stats.example", http, includeCurrent, maxWeeks = 20, pause = {}) }

    private fun keys(o: JSONObject) = o.keys().asSequence().toSet()

    @Test fun nothing_is_counted_before_continue_on_the_notice() {
        UsageStats.resetForTests(ctx)
        UsageStats.today = { day }
        assertFalse(UsageStats.noticeSeen(ctx))
        UsageStats.markActive(ctx)
        UsageStats.rideStarted(ctx)
        UsageStats.track(ctx, Feature.EXPORT_GPX)
        UsageStats.rideFinished(ctx, 5000.0, 12.97, 77.59, 12.98, 77.6)
        day = day.plusWeeks(1)
        assertTrue(UsageStats.pendingWeeks(ctx).isEmpty())
        val http = FakeHttp()
        assertEquals(0, send(http, includeCurrent = true))
        assertTrue(http.sent.isEmpty())

        // Only Continue (setNoticeSeen) turns it on, and it is remembered.
        UsageStats.setNoticeSeen(ctx)
        assertTrue(UsageStats.noticeSeen(ctx))
        UsageStats.track(ctx, Feature.EXPORT_GPX)
        assertEquals(1, UsageStats.weekData(ctx, UsageStats.weekId(day))!!.getJSONObject("f").getInt("export_gpx"))
    }

    @Test fun install_id_is_a_random_uuid_v4_and_stable() {
        val id = UsageStats.installId(ctx)
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(id))
        assertEquals(id, UsageStats.installId(ctx))
    }

    @Test fun iso_week_ids_and_rollover() {
        assertEquals("2026-W48", UsageStats.weekId(LocalDate.of(2026, 11, 27)))
        assertEquals("2025-W52", UsageStats.weekId(LocalDate.of(2025, 12, 28))) // Sunday
        assertEquals("2026-W01", UsageStats.weekId(LocalDate.of(2025, 12, 29))) // Monday, ISO year 2026
        assertEquals("2026-W53", UsageStats.weekId(LocalDate.of(2027, 1, 3)))
        assertEquals(LocalDate.of(2025, 12, 29), UsageStats.weekMonday("2026-W01"))
        assertNull(UsageStats.weekMonday("2025-W53")) // 2025 has 52 weeks
        assertNull(UsageStats.weekMonday("nonsense"))

        day = LocalDate.of(2025, 12, 28)
        UsageStats.track(ctx, Feature.LAP)
        assertTrue("the current week is never sent", UsageStats.pendingWeeks(ctx).isEmpty())
        day = LocalDate.of(2025, 12, 29)
        UsageStats.track(ctx, Feature.PAUSE)
        assertEquals(listOf("2025-W52"), UsageStats.pendingWeeks(ctx))
        assertEquals(listOf("2025-W52", "2026-W01"), UsageStats.pendingWeeks(ctx, includeCurrent = true))
    }

    @Test fun days_active_counts_distinct_days() {
        UsageStats.markActive(ctx); UsageStats.markActive(ctx)
        day = day.plusDays(2); UsageStats.markActive(ctx)
        val r = UsageStats.buildReport("id", "v", "2026-W48", UsageStats.weekData(ctx, "2026-W48")!!)
        assertEquals(2, r.getInt("days_active"))
    }

    @Test fun cells_are_floored_to_a_tenth_of_a_degree_including_negatives() {
        assertEquals(129, UsageStats.cell(12.97))
        assertEquals(775, UsageStats.cell(77.5))
        assertEquals(-339, UsageStats.cell(-33.86))
        assertEquals(-1, UsageStats.cell(-0.05))
        assertEquals(0, UsageStats.cell(0.0))
        assertEquals(-1513, UsageStats.cell(-151.21))
        assertEquals(1799, UsageStats.cell(179.99))

        UsageStats.rideFinished(ctx, 12_345.0, -33.86, 151.21, -33.91, -0.05)
        val area = UsageStats.buildArea("r", "v", "2026-W48", UsageStats.weekData(ctx, "2026-W48")!!)!!
        val cells = area.getJSONArray("cells")
        assertEquals(2, cells.length())
        val start = cells.getJSONObject(0)
        assertEquals("start", start.getString("kind"))
        assertEquals(-33.9, start.getDouble("lat"), 0.0)
        assertEquals(151.2, start.getDouble("lon"), 0.0)
        val end = cells.getJSONObject(1)
        assertEquals(-34.0, end.getDouble("lat"), 0.0)
        assertEquals(-0.1, end.getDouble("lon"), 0.0)
        assertEquals(12.3, end.getDouble("km"), 0.0)
    }

    @Test fun json_shape_matches_the_contract_exactly() {
        UsageStats.markActive(ctx)
        UsageStats.rideStarted(ctx); UsageStats.rideStarted(ctx)
        UsageStats.rideDiscarded(ctx)
        UsageStats.rideFinished(ctx, 42_740.0, 12.97, 77.59, 12.93, 77.61)
        UsageStats.track(ctx, Feature.EXPORT_GPX); UsageStats.track(ctx, Feature.EXPORT_GPX)
        UsageStats.track(ctx, Feature.SHARE_VIDEO)
        day = day.plusWeeks(1)
        val http = FakeHttp()
        assertEquals(1, send(http))
        assertEquals(2, http.sent.size)

        val (areaUrl, area) = http.sent[0]
        assertEquals("https://stats.example/v1/area", areaUrl)
        assertEquals(setOf("schema", "report", "app_version", "week", "cells"), keys(area))
        assertEquals(1, area.getInt("schema"))
        assertEquals("2026-W48", area.getString("week"))
        assertEquals(UsageStats.appVersion, area.getString("app_version"))
        assertTrue(area.getString("app_version").endsWith("-debug"))
        val cell = area.getJSONArray("cells").getJSONObject(0)
        assertEquals(setOf("lat", "lon", "kind", "rides", "km"), keys(cell))
        assertEquals(12.9, cell.getDouble("lat"), 0.0)
        assertEquals(77.5, cell.getDouble("lon"), 0.0)
        assertEquals(1, cell.getInt("rides"))
        assertEquals(42.7, cell.getDouble("km"), 0.0)

        val (reportUrl, report) = http.sent[1]
        assertEquals("https://stats.example/v1/report", reportUrl)
        assertEquals(
            setOf("schema", "install", "app_version", "week", "days_active", "rides_started", "rides_finished", "rides_discarded", "km", "features"),
            keys(report),
        )
        assertEquals(UsageStats.installId(ctx), report.getString("install"))
        assertFalse("the area report must not carry the install id", area.toString().contains(UsageStats.installId(ctx)))
        assertEquals(1, report.getInt("days_active"))
        assertEquals(2, report.getInt("rides_started"))
        assertEquals(1, report.getInt("rides_finished"))
        assertEquals(1, report.getInt("rides_discarded"))
        assertEquals(42.7, report.getDouble("km"), 0.0)
        val f = report.getJSONObject("features")
        assertEquals(setOf("export_gpx", "share_video"), keys(f))
        assertEquals(2, f.getInt("export_gpx"))
        assertTrue(UsageStats.pendingWeeks(ctx, includeCurrent = true).isEmpty())
    }

    @Test fun zero_counts_and_bad_keys_are_left_out() {
        val w = JSONObject().put("f", JSONObject().put("export_gpx", 0).put("lap", 3).put("Bad Key", 2).put("pause", -1))
        val f = UsageStats.buildReport("id", "v", "2026-W48", w).getJSONObject("features")
        assertEquals(setOf("lap"), keys(f))
    }

    @Test fun no_area_report_without_a_finished_ride() {
        UsageStats.track(ctx, Feature.SEARCH)
        UsageStats.rideStarted(ctx)
        day = day.plusWeeks(1)
        val http = FakeHttp()
        send(http)
        assertEquals(listOf("https://stats.example/v1/report"), http.sent.map { it.first })
    }

    @Test fun feature_keys_follow_the_contract() {
        val re = Regex("^[a-z][a-z0-9_]{1,39}$")
        Feature.entries.forEach { assertTrue(it.key, re.matches(it.key)) }
        assertEquals(Feature.entries.size, Feature.entries.map { it.key }.toSet().size)
        assertTrue(Feature.entries.size <= UsageStats.MAX_FEATURES)
    }

    @Test fun turning_off_clears_pending_and_stops_counting_but_keeps_the_id() {
        val id = UsageStats.installId(ctx)
        UsageStats.track(ctx, Feature.LAP)
        day = day.plusWeeks(1)
        UsageStats.track(ctx, Feature.LAP)
        assertEquals(1, UsageStats.pendingWeeks(ctx).size)
        UsageStats.setEnabled(ctx, false)
        assertFalse(UsageStats.isEnabled(ctx))
        assertTrue(UsageStats.pendingWeeks(ctx, includeCurrent = true).isEmpty())
        UsageStats.track(ctx, Feature.LAP)
        UsageStats.rideStarted(ctx)
        assertTrue(UsageStats.pendingWeeks(ctx, includeCurrent = true).isEmpty())
        val http = FakeHttp()
        assertEquals(0, send(http, includeCurrent = true))
        assertTrue(http.sent.isEmpty())

        UsageStats.setEnabled(ctx, true)
        assertEquals(id, UsageStats.installId(ctx))
        UsageStats.track(ctx, Feature.LAP)
        assertEquals(1, UsageStats.pendingWeeks(ctx, includeCurrent = true).size)
    }

    @Test fun only_the_last_eight_completed_weeks_are_kept() {
        repeat(12) {
            UsageStats.track(ctx, Feature.LAP)
            day = day.plusWeeks(1)
        }
        val pending = UsageStats.pendingWeeks(ctx)
        assertEquals(UsageStats.MAX_WEEKS, pending.size)
        assertEquals(UsageStats.weekId(day.minusWeeks(8)), pending.first())
        assertEquals(UsageStats.weekId(day.minusWeeks(1)), pending.last())
        assertEquals(pending.sorted(), pending)
    }

    @Test fun oldest_week_is_sent_first() {
        repeat(3) { UsageStats.track(ctx, Feature.LAP); day = day.plusWeeks(1) }
        val http = FakeHttp()
        assertEquals(3, send(http))
        assertEquals(listOf("2026-W48", "2026-W49", "2026-W50"), http.sent.map { it.second.getString("week") })
    }

    @Test fun server_answers_204_400_and_500() {
        UsageStats.rideFinished(ctx, 10_000.0, 12.97, 77.59, 12.97, 77.59)
        day = day.plusWeeks(1)

        // 500 on the area report: nothing more is sent and everything is kept.
        var http = FakeHttp(500)
        assertEquals(0, send(http))
        assertEquals(1, http.sent.size)
        assertEquals(listOf("2026-W48"), UsageStats.pendingWeeks(ctx))
        val areaId = http.sent[0].second.getString("report")

        // Area stored, report fails (network error): only the report is retried, later.
        http = FakeHttp(204, -1)
        assertEquals(0, send(http))
        assertEquals(areaId, http.sent[0].second.getString("report"))
        http = FakeHttp(204)
        assertEquals(1, send(http))
        assertEquals(listOf("https://stats.example/v1/report"), http.sent.map { it.first })
        assertTrue(UsageStats.pendingWeeks(ctx).isEmpty())

        // 400: dropped, never retried.
        UsageStats.track(ctx, Feature.LAP)
        day = day.plusWeeks(1)
        http = FakeHttp(400)
        assertEquals(1, send(http))
        assertTrue(UsageStats.pendingWeeks(ctx).isEmpty())
        http = FakeHttp()
        send(http)
        assertTrue(http.sent.isEmpty())
    }

    @Test fun empty_url_sends_nothing() {
        UsageStats.track(ctx, Feature.LAP)
        day = day.plusWeeks(1)
        val http = FakeHttp()
        assertEquals(0, runBlocking { StatsSender.run(ctx, "", http, pause = {}) })
        assertTrue(http.sent.isEmpty())
        assertEquals(1, UsageStats.pendingWeeks(ctx).size)
    }

    @Test fun no_forbidden_data_is_sent() {
        UsageStats.rideFinished(ctx, 23_456.7, 12.971599, 77.594566, 13.035542, 77.597100)
        UsageStats.track(ctx, Feature.SEARCH)
        day = day.plusWeeks(1)
        val http = FakeHttp()
        send(http)
        val all = http.sent.joinToString("\n") { it.second.toString() }
        listOf("12.97", "77.59", "13.03", "77.597", "12.971599", "lat\":12.97", "23.456", "T", "Z").forEach {
            if (it.length > 1) assertFalse("found $it in $all", all.contains(it))
        }
        // No timestamps finer than the week, no device or network details.
        assertFalse(Regex("\\d{4}-\\d{2}-\\d{2}").containsMatchIn(all))
        listOf("model", "android_id", "ip", "time", "name", "speed", "hr", "heart", "lat\":12.971").forEach {
            assertFalse("found $it", all.lowercase().contains("\"$it"))
        }
        val area = http.sent[0].second
        assertEquals(setOf(12.9, 13.0), (0 until area.getJSONArray("cells").length()).map { area.getJSONArray("cells").getJSONObject(it).getDouble("lat") }.toSet())
        assertNotNull(http.sent[1].second.optString("install"))
    }
}
