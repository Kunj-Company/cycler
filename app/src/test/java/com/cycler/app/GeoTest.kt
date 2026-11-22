package com.cycler.app

import com.cycler.app.data.Difficulty
import com.cycler.app.data.Geo
import com.cycler.app.data.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks on the maths the app's numbers depend on. */
class GeoTest {
    private val start = Pt(12.9716, 77.5946)

    /** A straight route heading north, one point every 100 m. */
    private fun north(n: Int) = (0..n).map { Geo.destination(start.lat, start.lon, 0.0, it * 100.0) }

    @Test fun distance_matches_known_value() {
        // One degree of latitude is about 111.2 km.
        assertEquals(111_195.0, Geo.dist(12.0, 77.0, 13.0, 77.0), 150.0)
    }

    @Test fun destination_and_bearing_agree() {
        val p = Geo.destination(start.lat, start.lon, 90.0, 5000.0)
        assertEquals(5000.0, Geo.dist(start, p), 5.0)
        assertEquals(90.0, Geo.bearing(start, p), 0.5)
    }

    @Test fun nearest_on_route_reports_offset_and_progress() {
        val route = north(50)
        val cum = Geo.cumulative(route)
        assertEquals(5000.0, cum.last(), 5.0)
        // 40 m east of the route, 2.05 km along it.
        val on = Geo.destination(start.lat, start.lon, 0.0, 2050.0)
        val p = Geo.destination(on.lat, on.lon, 90.0, 40.0)
        val (off, along, idx) = Geo.nearestOnRoute(p, route, cum, 1900.0)
        assertEquals(40.0, off, 2.0)
        assertEquals(2050.0, along, 5.0)
        assertEquals(20, idx)
    }

    @Test fun nearest_on_route_does_not_snap_back_on_out_and_back() {
        // Out 2 km and back along the same road.
        val out = north(20)
        val route = out + out.reversed().drop(1)
        val cum = Geo.cumulative(route)
        // Rider is on the way back, 500 m from home, having covered 3.5 km.
        val p = Geo.destination(start.lat, start.lon, 0.0, 500.0)
        val (_, along, _) = Geo.nearestOnRoute(p, route, cum, done = 3400.0)
        assertEquals(3500.0, along, 10.0)
        // And on the way out at the same spot, progress is 500 m.
        assertEquals(500.0, Geo.nearestOnRoute(p, route, cum, done = 450.0).second, 10.0)
    }

    @Test fun nearest_on_route_starts_a_loop_at_the_beginning() {
        // A square loop that ends where it starts: standing at home must read as 0 km done, not finished.
        val a = start
        val b = Geo.destination(a.lat, a.lon, 0.0, 3000.0)
        val c = Geo.destination(b.lat, b.lon, 90.0, 3000.0)
        val d = Geo.destination(a.lat, a.lon, 90.0, 3000.0)
        val route = listOf(a, b, c, d, a)
        val cum = Geo.cumulative(route)
        val nearHome = Geo.destination(a.lat, a.lon, 135.0, 8.0)
        assertTrue(Geo.nearestOnRoute(nearHome, route, cum, done = 0.0).second < 50.0)
        // Joining the route halfway round still works: nothing near the start, so the whole route is searched.
        val (off, along, _) = Geo.nearestOnRoute(c, route, cum, done = 0.0)
        assertEquals(0.0, off, 2.0)
        assertEquals(6000.0, along, 20.0)
    }

    @Test fun ascent_ignores_small_bumps() {
        val flat = (0..100).map { Pt(12.0, 77.0 + it * 0.0001, 900.0 + if (it % 2 == 0) 1.0 else 0.0) }
        assertEquals(0.0, Geo.ascent(flat), 0.01)
        val climb = (0..100).map { Pt(12.0, 77.0 + it * 0.0001, 900.0 + it) }
        assertEquals(100.0, Geo.ascent(climb), 3.0)
    }

    @Test fun loop_waypoints_start_and_end_at_home() {
        val w = Geo.loopWaypoints(start, 45.0, 20_000.0)
        assertEquals(5, w.size)
        assertEquals(0.0, Geo.dist(w.first(), w.last()), 0.01)
        // The straight-line loop is shorter than the target, leaving room for real roads to wiggle.
        val len = Geo.length(w)
        assertTrue("length was $len", len in 11_000.0..18_000.0)
    }

    @Test fun splits_give_speed_per_step() {
        // 10 km at a steady 18 km/h (5 m/s): 100 m every 20 s.
        val pts = north(100).mapIndexed { i, p -> p.copy(t = 1_000_000L + i * 20_000L) }
        val s = Geo.splits(pts, 1000.0)
        assertTrue("got ${s.size} splits", s.size in 9..10)
        s.forEach { assertEquals(18.0, it, 0.3) }
    }

    @Test fun difficulty_scales_with_distance_and_climb() {
        assertEquals(Difficulty.EASY, Difficulty.of(12_000.0, 60.0))
        assertEquals(Difficulty.MODERATE, Difficulty.of(30_000.0, 300.0))
        assertEquals(Difficulty.HARD, Difficulty.of(60_000.0, 900.0))
    }

    @Test fun turns_are_found_with_side_and_position() {
        // North 1 km, right turn, east 1 km, left turn, north 1 km.
        val a = (0..10).map { Geo.destination(start.lat, start.lon, 0.0, it * 100.0) }
        val b = (1..10).map { Geo.destination(a.last().lat, a.last().lon, 90.0, it * 100.0) }
        val c = (1..10).map { Geo.destination(b.last().lat, b.last().lon, 0.0, it * 100.0) }
        val route = a + b + c
        val turns = Geo.turns(route, Geo.cumulative(route))
        assertEquals(2, turns.size)
        assertEquals("Turn right", turns[0].label)
        assertEquals(1000.0, turns[0].alongM, 30.0)
        assertEquals("Turn left", turns[1].label)
        assertEquals(2000.0, turns[1].alongM, 30.0)
        // A straight road and a gentle curve have none.
        assertEquals(0, Geo.turns(north(30), Geo.cumulative(north(30))).size)
        val curve = (0..60).map { Geo.destination(start.lat, start.lon, it * 1.0, 3000.0) }
        assertEquals(0, Geo.turns(curve, Geo.cumulative(curve)).size)
    }

    @Test fun climbs_are_found_and_measured() {
        // Flat 2 km, then 2 km climbing 100 m (5%), then flat 2 km.
        val route = north(60).mapIndexed { i, p ->
            p.copy(ele = 900.0 + when { i < 20 -> 0.0; i < 40 -> (i - 20) * 5.0; else -> 100.0 })
        }
        val climbs = Geo.climbs(route, Geo.cumulative(route))
        assertEquals(1, climbs.size)
        assertEquals(2000.0, climbs[0].startM, 200.0)
        assertEquals(4000.0, climbs[0].endM, 200.0)
        assertEquals(100.0, climbs[0].gainM, 8.0)
        assertEquals(5.0, climbs[0].gradePct, 0.8)
        // Rolling bumps of a few metres are not climbs.
        val bumpy = north(60).mapIndexed { i, p -> p.copy(ele = 900.0 + if (i % 4 < 2) 3.0 else 0.0) }
        assertEquals(0, Geo.climbs(bumpy, Geo.cumulative(bumpy)).size)
    }

    @Test fun best_effort_finds_the_fast_stretch() {
        // 10 km: first 5 km at 18 km/h (20 s per 100 m), last 5 km at 36 km/h (10 s per 100 m).
        var t = 1_000_000L
        val pts = north(100).mapIndexed { i, p ->
            if (i > 0) t += if (i <= 50) 20_000L else 10_000L
            p.copy(t = t)
        }
        val bests = Geo.bestEfforts(pts)
        assertEquals(500.0, bests[5]!!.toDouble(), 12.0)   // the fast half: 5 km in 500 s
        assertEquals(1500.0, bests[10]!!.toDouble(), 25.0) // the whole ride
        assertEquals(100.0, bests[1]!!.toDouble(), 12.0)
        assertTrue(20 !in bests)
    }

    @Test fun ride_stats_cover_speed_stops_and_hills() {
        // 6 km north at 36 km/h with a three-minute stop after 3 km, climbing 60 m then dropping 30 m.
        var t = 1_000_000L
        val pts = north(60).mapIndexed { i, p ->
            if (i > 0) t += 10_000L               // 100 m every 10 s
            if (i == 30) t += 180_000L            // the stop
            p.copy(t = t, ele = 900.0 + if (i <= 40) i * 1.5 else 60.0 - (i - 40) * 1.5)
        }
        val meta = com.cycler.app.data.RouteMeta(
            "x", "t", com.cycler.app.data.RouteType.RECORDED, pts.first().t, 6000.0, 60.0, 600, 0.0, 0.0,
        )
        val s = Geo.rideStats(pts, meta)
        assertEquals(36.0, s.avgMovingKmh, 0.5)
        assertEquals(1, s.stops)
        assertTrue("longest stop ${s.longestStopS}", s.longestStopS in 180..195)
        assertEquals(780.0, s.elapsedS.toDouble(), 15.0)
        assertTrue("overall ${s.avgOverallKmh}", s.avgOverallKmh < s.avgMovingKmh)
        assertEquals(36.0, s.maxKmh, 2.0)
        assertEquals(960.0, s.highestM, 1.0)
        assertEquals(900.0, s.lowestM, 1.0)
        assertEquals(30.0, s.lossM, 8.0)
        assertEquals(1.5, s.steepestUpPct, 0.4)
        assertEquals(1.0, s.zones.sum(), 0.001)
        assertEquals(1.0, s.zones[5], 0.05)   // all of it above 30 km/h
        assertTrue(s.kcal(70) > 0)
    }

    /** Writes a FIT and a TCX file to build/export so an independent parser can read them back. */
    @Test fun exports_fit_and_tcx() {
        var t = 1_795_000_000_000L
        val pts = north(120).mapIndexed { i, p -> t += 12_000L; p.copy(t = t, ele = 900.0 + i * 0.5) }
        val meta = com.cycler.app.data.RouteMeta(
            "x", "Export <test> & ride", com.cycler.app.data.RouteType.RECORDED, pts.first().t, 12_000.0, 60.0, 1380, 0.0, 0.0,
            maxSpeedMs = 9.1, elapsedS = 1440,
            laps = listOf(com.cycler.app.data.LapMark(5000.0, 575_000, 600_000), com.cycler.app.data.LapMark(12_000.0, 1_380_000, 1_440_000)),
            avgHr = 142, maxHr = 171,
        )
        val hr = pts.map { it.t to (130 + (it.t / 12_000 % 40).toInt()) }
        val route = com.cycler.app.data.Route(meta, pts)
        val dir = java.io.File("build/export").apply { mkdirs() }
        java.io.File(dir, "ride.fit").outputStream().use { com.cycler.app.data.Fit.write(route, hr, it) }
        java.io.File(dir, "ride.tcx").writeText(com.cycler.app.data.Tcx.write(route, hr))
        assertTrue(java.io.File(dir, "ride.fit").length() > 2000)
    }

    @Test fun segment_matching_finds_each_pass_and_ignores_other_roads() {
        // The segment: 1 km north, starting 2 km up the road.
        val road = north(60)
        val seg = road.subList(20, 31)
        val segLen = Geo.length(seg)
        // A ride up the road and back down: one pass in the segment's direction.
        val out = road.mapIndexed { i, p -> p.copy(t = 1_000_000L + i * 10_000L) }
        val back = road.reversed().drop(1).mapIndexed { i, p -> p.copy(t = out.last().t + (i + 1) * 10_000L) }
        val ride = out + back
        val passes = Geo.matchSegment(ride, Geo.cumulative(ride), seg, segLen)
        assertEquals(1, passes.size)
        val (a, b) = passes[0]
        assertEquals(100.0, (ride[b].t - ride[a].t) / 1000.0, 11.0)   // 1 km at 100 m per 10 s
        // Two laps of the road give two passes.
        val twice = out + back + out.map { it.copy(t = it.t + 2_000_000L) }
        assertEquals(2, Geo.matchSegment(twice, Geo.cumulative(twice), seg, segLen).size)
        // A parallel road 200 m to the east is not a match.
        val other = road.map { Geo.destination(it.lat, it.lon, 90.0, 200.0).copy(t = 5L) }
        assertEquals(0, Geo.matchSegment(other, Geo.cumulative(other), seg, segLen).size)
        // Nor is a ride that touches both ends but takes a 3 km detour between them.
        val detour = road.subList(0, 21) +
            (1..15).map { Geo.destination(road[20].lat, road[20].lon, 90.0, it * 100.0) } +
            (15 downTo 0).map { Geo.destination(road[30].lat, road[30].lon, 90.0, it * 100.0) } + road.subList(31, 61)
        assertEquals(0, Geo.matchSegment(detour, Geo.cumulative(detour), seg, segLen).size)
    }
}
