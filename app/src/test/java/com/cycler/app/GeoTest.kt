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
}
