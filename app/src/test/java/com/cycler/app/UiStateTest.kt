package com.cycler.app

import androidx.compose.runtime.saveable.SaverScope
import com.cycler.app.data.Geo
import com.cycler.app.data.Pt
import com.cycler.app.share.CardSpec
import com.cycler.app.ui.CardSpecSaver
import com.cycler.app.ui.FloatRangeSaver
import com.cycler.app.ui.RouteErrors
import com.cycler.app.ui.SignalsCache
import com.cycler.app.ui.TagListSaver
import com.cycler.app.ui.shouldPersistLoc
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The pure parts of the UI state and error handling work (CYC-012 to CYC-016, CYC-020). */
class UiStateTest {
    private val scope = SaverScope { true }

    // --- error messages -------------------------------------------------------------------------

    @Test fun network_failures_say_no_internet() {
        listOf(
            java.net.UnknownHostException("brouter.de"), java.net.ConnectException("refused"),
            java.net.SocketTimeoutException("timeout"),
        ).forEach { assertEquals(RouteErrors.OFFLINE, RouteErrors.message(it)) }
        assertEquals(RouteErrors.OFFLINE, RouteErrors.message(RuntimeException("wrapped", java.net.UnknownHostException("x"))))
    }

    @Test fun router_no_route_text_maps_to_no_route() {
        assertEquals(RouteErrors.NO_ROUTE, RouteErrors.message(IOException("no track found at pass=1")))
        assertEquals(RouteErrors.NO_ROUTE, RouteErrors.message(IOException("from-position not mapped in existing datafile ./segments4/E5_N45.rd5")))
    }

    @Test fun server_text_is_never_shown() {
        val m = RouteErrors.message(IOException("<html>502 Bad Gateway nginx brouter.de</html>"))
        assertEquals(RouteErrors.BUSY, m)
        assertFalse(m.contains("nginx") || m.contains("brouter.de"))
        assertEquals(RouteErrors.BUSY, RouteErrors.message(null))
    }

    // --- savers ---------------------------------------------------------------------------------

    @Test fun tag_list_saver_round_trips() {
        val tags = listOf("Commute", "Rain, heavy", "")
        val saved = with(TagListSaver) { scope.save(tags) }
        assertEquals(tags, TagListSaver.restore(saved!!))
        assertEquals(emptyList<String>(), TagListSaver.restore(with(TagListSaver) { scope.save(emptyList()) }!!))
    }

    @Test fun card_spec_saver_round_trips() {
        val spec = CardSpec("Sunday loop", showDate = false, showMark = false, stats = listOf("distance", "climb"), photo = 2, h = 1350)
        val back = CardSpecSaver.restore(with(CardSpecSaver) { scope.save(spec) }!!)
        assertEquals(spec, back)
        val none = spec.copy(stats = emptyList())
        assertEquals(none, CardSpecSaver.restore(with(CardSpecSaver) { scope.save(none) }!!))
    }

    @Test fun range_saver_round_trips() {
        val r = 0.2f..0.7f
        assertEquals(r, FloatRangeSaver.restore(with(FloatRangeSaver) { scope.save(r) }!!))
    }

    // --- signals cache --------------------------------------------------------------------------

    @Test fun signals_cache_hit_skips_the_lookup() = runBlocking {
        val cache = SignalsCache()
        var calls = 0
        val found = listOf(Pt(1.0, 2.0))
        assertEquals(found, cache.getOrLoad("a") { calls++; found })
        assertEquals(found, cache.getOrLoad("a") { calls++; emptyList() })
        assertEquals(1, calls)
    }

    @Test fun signals_cache_does_not_store_failures() = runBlocking {
        val cache = SignalsCache()
        assertNull(cache.getOrLoad("a") { null })
        assertNull(cache.get("a"))
        assertNotNull(cache.getOrLoad("a") { listOf(Pt(1.0, 2.0)) })
    }

    // --- last location throttle -----------------------------------------------------------------

    @Test fun last_location_is_saved_first_then_throttled() {
        val here = Pt(12.9716, 77.5946)
        assertTrue(shouldPersistLoc(null, 0, here, 1_000))
        val near = Geo.destination(here.lat, here.lon, 0.0, 50.0)
        assertFalse(shouldPersistLoc(here, 1_000, near, 10_000))
        assertTrue(shouldPersistLoc(here, 1_000, near, 31_001))
        val far = Geo.destination(here.lat, here.lon, 0.0, 250.0)
        assertTrue(shouldPersistLoc(here, 1_000, far, 5_000))
    }

    // --- followed route rebuilt from the recorder -------------------------------------------------

    @Test fun rebuilt_guide_has_planned_meta_and_length() {
        val a = Pt(12.9716, 77.5946)
        val b = Geo.destination(a.lat, a.lon, 90.0, 1000.0)
        val g = rebuildGuide(listOf(a, b))!!
        assertEquals("Route", g.meta.name)
        assertEquals(com.cycler.app.data.RouteType.PLANNED, g.meta.type)
        assertEquals(1000.0, g.meta.distanceM, 5.0)
        assertNull(rebuildGuide(emptyList()))
        assertNull(rebuildGuide(null))
    }
}
