package com.cycler.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.cycler.app.data.CareItem
import com.cycler.app.data.Geo
import com.cycler.app.data.Period
import com.cycler.app.data.Pt
import com.cycler.app.data.RideFilter
import com.cycler.app.data.RidePeriod
import com.cycler.app.data.RideSort
import com.cycler.app.data.RouteStore
import com.cycler.app.data.RouteType
import com.cycler.app.track.TrackState
import com.cycler.app.track.TrackingService
import com.cycler.app.ui.ActivityScreen
import com.cycler.app.ui.BikeCareScreen
import com.cycler.app.ui.CyclerTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.cos
import kotlin.math.sin

/** Renders Activity, Bike care and the filtered Rides list to app/build/shots/act_*.png with made-up rides. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi", application = Application::class)
class ActivityShots {
    @get:Rule val rule = createComposeRule()

    private val home = Pt(12.9716, 77.5946)
    private lateinit var vm: MainVm
    private val day = 24L * 3600 * 1000
    private val now = System.currentTimeMillis()

    private fun loop(km: Double, seed: Int): List<Pt> {
        val r = km * 1000 / (2 * Math.PI) / 1.15
        val centre = Geo.destination(home.lat, home.lon, seed * 70.0, r)
        return (0..60).map { i ->
            val a = i * 6.0 + seed * 70.0 + 180
            val wob = 1 + 0.18 * sin(i / 9.0 + seed) + 0.08 * cos(i / 3.0)
            val p = Geo.destination(centre.lat, centre.lon, a, r * wob)
            Pt(p.lat, p.lon, 900 + 40 * sin(i / 14.0 + seed), 0)
        }
    }

    @Before fun seed() {
        RouteStore.resetForTests()
        vm = MainVm(ApplicationProvider.getApplicationContext<Application>())
        // (name, km, days ago, average km/h, climb per km)
        val rides = listOf(
            listOf("Morning ride", 24.6, 0, 20.5, 9.5), listOf("Nandi foothills", 62.3, 2, 19.0, 14.0),
            listOf("Evening ride", 12.1, 3, 17.0, 6.0), listOf("Cubbon Park laps", 18.4, 5, 21.0, 4.0),
            listOf("Commute", 9.0, 6, 16.0, 5.0), listOf("Hesaraghatta loop", 48.0, 9, 22.0, 8.0),
            listOf("Morning ride", 21.0, 12, 20.0, 9.0), listOf("Airport road", 71.5, 16, 24.0, 7.0),
            listOf("Lake circuit", 15.2, 19, 18.0, 6.0), listOf("Nandi hills again", 66.0, 24, 18.5, 15.0),
            listOf("Evening ride", 14.0, 31, 17.5, 5.0), listOf("Hesaraghatta loop", 51.0, 38, 21.5, 8.0),
            listOf("Morning ride", 27.0, 47, 20.0, 9.0), listOf("Skandagiri sunrise", 58.0, 62, 17.5, 16.0),
            listOf("Airport road", 73.0, 78, 23.0, 7.0), listOf("Lake circuit", 16.0, 95, 18.0, 6.0),
            listOf("Morning ride", 22.0, 121, 20.0, 9.0), listOf("Mysore road", 85.0, 150, 22.0, 6.0),
            listOf("Evening ride", 13.0, 190, 17.0, 5.0), listOf("Nandi foothills", 60.0, 240, 19.0, 14.0),
            listOf("Morning ride", 25.0, 300, 20.0, 9.0), listOf("Hesaraghatta loop", 47.0, 340, 21.0, 8.0),
        )
        rides.forEachIndexed { i, r ->
            val km = r[1] as Double
            val ago = (r[2] as Int).toLong()
            val speed = r[3] as Double
            // A clock time on that day, so no ride lands exactly on midnight.
            val start = now - ago * day
            val secs = (km / speed * 3600).toLong()
            val pts = loop(km, i + 1).mapIndexed { k, p -> p.copy(t = start + k * secs * 1000 / 60) }
            vm.store.save(
                r[0] as String, RouteType.RECORDED, pts, secs, km * 1000, km * (r[4] as Double),
                createdAt = start, maxSpeedMs = 11.8, elapsedS = (km / (speed - 2) * 3600).toLong(),
            )
        }
        vm.store.metas.value.first { it.name == "Nandi foothills" && it.distanceM > 60_000 && it.createdAt > now - 5 * day }.let {
            vm.store.updateDetails(it.id, "Nandi foothills at sunrise", "Left at 5:30 to beat the traffic.", 5, listOf("Long ride", "Hills"))
        }
        vm.store.metas.value.first { it.name == "Nandi hills again" }.let {
            vm.store.updateDetails(it.id, it.name, "Headwind on the way back.", 3, listOf("Hills"))
        }
        vm.store.metas.value.first { it.name == "Commute" }.let {
            vm.store.updateDetails(it.id, it.name, "", 3, listOf("Commute"))
        }
        TrackingService.state.value = TrackState()
        TrackingService.setGuide(null)
        vm.saveCare(
            listOf(
                CareItem("Lube the chain", 300, 0).markDone(now - 20 * day),
                CareItem("Check tyre pressure", 200, 0, everyDays = 14, note = "Floor pump, 55 psi front, 60 rear")
                    .markDone(now - 40 * day, "Before the Nandi ride").markDone(now - 18 * day),
                CareItem("Clean the drivetrain", 250, 0).markDone(now - 40 * day, "Degreaser and a brush"),
                CareItem("Check brake pads", 1000, 0),
                CareItem("Full service", 2500, 0).markDone(now - 100 * day, "Bike shop, new cables"),
                CareItem("Replace chain", 4000, 0),
            ),
        )
    }

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        rule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                CyclerTheme(if (dark) "dark" else "light") {
                    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.background) { content() }
                }
            }
        }
        repeat(3) { rule.waitForIdle(); Thread.sleep(250) }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/$name.png")
    }

    private fun shell(name: String, tab: Tab, dark: Boolean = false) {
        vm.tab = tab
        shot(name, dark) { AppShell(vm, home, true) {} }
    }

    private fun careId(name: String) = vm.care.first { it.name == name }.id

    // Activity
    @Test fun week() = shell("act_week", Tab.ACTIVITY)
    @Test @Config(qualifiers = "w412dp-h1700dp-xxhdpi") fun week_full() = shot("act_week_full") { ActivityScreen(vm) }
    @Test fun week_selected() = shot("act_week_selected") { ActivityScreen(vm, initialSelected = 4) }
    @Test fun month() = shot("act_month") { ActivityScreen(vm, Period.MONTH) }
    @Test fun year() = shot("act_year") { ActivityScreen(vm, Period.YEAR) }
    @Test fun week_dark() = shell("act_week_dark", Tab.ACTIVITY, dark = true)
    @Test @Config(qualifiers = "w412dp-h1700dp-xxhdpi") fun month_dark_full() = shot("act_month_dark_full", dark = true) { ActivityScreen(vm, Period.MONTH) }

    // Bike care
    @Test fun care_list() = shot("act_care_list") { BikeCareScreen(vm) }
    @Test fun care_edit() = shot("act_care_edit") { BikeCareScreen(vm, careId("Check tyre pressure")) }
    @Test @Config(qualifiers = "w412dp-h1300dp-xxhdpi") fun care_edit_tall() = shot("act_care_edit_tall") { BikeCareScreen(vm, careId("Check tyre pressure")) }
    @Test fun care_new() = shot("act_care_new") { BikeCareScreen(vm, "new") }
    @Test fun care_list_dark() = shot("act_care_list_dark", dark = true) { BikeCareScreen(vm) }
    @Test fun care_edit_dark() = shot("act_care_edit_dark", dark = true) { BikeCareScreen(vm, careId("Check tyre pressure")) }

    // Rides
    @Test fun rides_filtered() {
        vm.rideFilter = RideFilter(period = RidePeriod.MONTH, sort = RideSort.LONGEST)
        shell("act_rides_filtered", Tab.RIDES)
    }
    @Test fun rides_empty_filter() {
        vm.rideFilter = RideFilter(period = RidePeriod.WEEK, tag = "Commute", query = "zzz")
        shell("act_rides_empty", Tab.RIDES)
    }
    @Test fun rides_dark() {
        vm.rideFilter = RideFilter(period = RidePeriod.YEAR, tag = "Hills")
        shell("act_rides_dark", Tab.RIDES, dark = true)
    }
}
