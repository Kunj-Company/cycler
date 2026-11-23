package com.cycler.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cycler.app.data.Altitude
import com.cycler.app.data.Pt
import com.cycler.app.data.RouteStore
import com.cycler.app.data.RouteType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Raw GPS heights are converted to heights above sea level; other heights are left alone. */
class AltitudeTest {
    private val kochi = Pt(10.05, 76.32, -85.0, 1_000L)

    @Test fun converts_by_the_geoid_height() {
        // Around Kochi the geoid is about 95 m below the ellipsoid: a raw −85 m is +10 m.
        val out = Altitude.toMsl(listOf(kochi)) { _, _ -> -95.0 }!!
        assertEquals(10.0, out[0].ele, 1e-9)
        assertEquals(kochi.lat, out[0].lat, 0.0)
        assertEquals(kochi.t, out[0].t)
    }

    @Test fun points_without_height_stay_without() {
        val out = Altitude.toMsl(listOf(Pt(10.0, 76.0, Double.NaN, 1L))) { _, _ -> -95.0 }!!
        assertTrue(out[0].ele.isNaN())
    }

    @Test fun a_failed_lookup_converts_nothing() {
        assertNull(Altitude.toMsl(listOf(kochi, kochi.copy(lat = 12.0))) { lat, _ -> if (lat > 11) null else -95.0 })
    }

    @Test fun looks_up_once_per_small_area() {
        var calls = 0
        val ride = (0 until 200).map { Pt(10.05 + it * 0.0001, 76.32, -80.0, it.toLong()) } // about 2 km
        Altitude.toMsl(ride) { _, _ -> calls++; -95.0 }
        assertTrue("lookups: $calls", calls <= 2)
    }

    @Test fun older_rides_are_classified_by_how_they_were_saved() {
        assertEquals(Altitude.GPS, Altitude.legacy(RouteType.RECORDED, 3600))  // the app's recorder
        assertEquals(Altitude.MSL, Altitude.legacy(RouteType.RECORDED, 0))     // a timed GPX import
        assertEquals(Altitude.MSL, Altitude.legacy(RouteType.PLANNED, 0))
    }
}

/** The height reference is saved with each ride, and inferred for rides saved before it was. */
@RunWith(RobolectricTestRunner::class)
// Android 13: no built-in geoid, so rides keep the reference they were saved with.
@Config(sdk = [33], application = Application::class)
class AltitudeStoreTest {
    private fun store(): Pair<Application, RouteStore> {
        RouteStore.resetForTests()
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        ctx.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        return ctx to RouteStore.get(ctx)
    }

    private val pts = listOf(Pt(10.05, 76.32, -85.0, 1_000L), Pt(10.06, 76.32, -84.0, 200_000L))

    @Test fun recorded_and_imported_rides_keep_their_reference() {
        val (_, s) = store()
        val rec = s.save("Ride", RouteType.RECORDED, pts, 199, elapsedS = 199, alt = Altitude.GPS)
        val gpx = s.save("Import", RouteType.RECORDED, pts, 199)
        RouteStore.resetForTests()
        val again = RouteStore.get(ApplicationProvider.getApplicationContext())
        assertEquals(Altitude.GPS, again.metas.value.first { it.id == rec }.alt)
        assertEquals(Altitude.MSL, again.metas.value.first { it.id == gpx }.alt)
    }

    @Test fun older_files_without_a_reference_are_inferred() {
        val (ctx, s) = store()
        val id = s.save("Old ride", RouteType.RECORDED, pts, 199, elapsedS = 199)
        val f = File(File(ctx.filesDir, "routes"), "$id.meta.json")
        f.writeText(JSONObject(f.readText()).apply { remove("alt") }.toString())
        RouteStore.resetForTests()
        val again = RouteStore.get(ctx)
        assertEquals(Altitude.GPS, again.metas.value.first { it.id == id }.alt)
    }
}

/** On Android 14+ the store converts raw heights in the background; opening it must not crash. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AltitudeStartupTest {
    @Test fun store_opens_with_a_raw_height_ride_on_disk() {
        RouteStore.resetForTests()
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        ctx.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        val pts = listOf(Pt(10.05, 76.32, -85.0, 1_000L), Pt(10.06, 76.32, -84.0, 200_000L))
        val id = RouteStore.get(ctx).save("Ride", RouteType.RECORDED, pts, 199, elapsedS = 199, alt = Altitude.GPS)
        // A fresh start, as after an app update, with a ride still holding raw GPS heights.
        RouteStore.resetForTests()
        val store = RouteStore.get(ctx)
        assertTrue(store.metas.value.any { it.id == id })
    }
}
