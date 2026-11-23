package com.cycler.app

import com.cycler.app.data.Geo
import com.cycler.app.data.GpsJump
import com.cycler.app.data.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** GPS jumps (bad fixes, tunnels, teleports) must not add distance to a ride. */
class GpsJumpTest {
    private val start = Pt(12.9716, 77.5946)
    private val t0 = 1_700_000_000_000L

    /** A ride north at [ms] metres per second, one fix a second, from [from] at time [t]. */
    private fun ride(from: Pt, t: Long, seconds: Int, ms: Double = 6.0) = (0..seconds).map {
        val p = Geo.destination(from.lat, from.lon, 0.0, it * ms)
        Pt(p.lat, p.lon, Double.NaN, t + it * 1000L)
    }

    @Test fun normal_riding_is_not_a_jump() {
        assertFalse(GpsJump.isJump(6.0, 1000))
        assertFalse(GpsJump.isJump(25.0, 1000)) // 90 km/h downhill still counts
        assertEquals(6.0, GpsJump.addedDistance(6.0, 1000, keep = true, skipNext = false), 0.0)
    }

    @Test fun teleport_is_a_jump_and_adds_nothing() {
        assertTrue(GpsJump.isJump(1100.0, 1000))
        assertEquals(0.0, GpsJump.addedDistance(1100.0, 1000, keep = true, skipNext = false), 0.0)
    }

    @Test fun long_signal_loss_still_counts() {
        // 2 km in 5 minutes is 6.7 m/s: a real gap in coverage, not a jump.
        assertFalse(GpsJump.isJump(2000.0, 5 * 60_000L))
        assertEquals(2000.0, GpsJump.addedDistance(2000.0, 5 * 60_000L, keep = true, skipNext = false), 0.0)
    }

    @Test fun untimed_segments_are_never_jumps() {
        assertFalse(GpsJump.isJump(5000.0, 0))
        assertFalse(GpsJump.isJump(5000.0, -1))
    }

    @Test fun existing_keep_and_skip_rules_are_unchanged() {
        assertEquals(0.0, GpsJump.addedDistance(3.0, 1000, keep = false, skipNext = false), 0.0)
        assertEquals(0.0, GpsJump.addedDistance(20.0, 1000, keep = true, skipNext = true), 0.0)
    }

    @Test fun ride_with_a_teleport_counts_only_the_real_riding() {
        val before = ride(start, t0, 60)                                   // 360 m
        val far = Geo.destination(before.last().lat, before.last().lon, 90.0, 1100.0)
        val after = ride(far, before.last().t + 1000, 60)                  // jump, then 360 m
        val pts = before + after
        assertEquals(720.0, Geo.length(pts), 2.0)
        val cum = Geo.cumulative(pts)
        assertEquals(Geo.length(pts), cum.last(), 0.01)
        // The jump segment itself adds nothing.
        assertEquals(cum[before.size - 1], cum[before.size], 0.0)
    }

    @Test fun planned_routes_without_times_keep_full_length() {
        val route = (0..10).map { Geo.destination(start.lat, start.lon, 0.0, it * 500.0) }
        assertEquals(5000.0, Geo.length(route), 5.0)
    }
}
