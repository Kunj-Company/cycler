package com.cycler.app

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.cycler.app.data.Geo
import com.cycler.app.data.Pt
import com.cycler.app.data.Route
import com.cycler.app.data.RouteStore
import com.cycler.app.data.RouteType
import com.cycler.app.net.Weather
import com.cycler.app.track.TrackState
import com.cycler.app.track.TrackStatus
import com.cycler.app.track.TrackingService
import com.cycler.app.ui.CyclerTheme
import com.cycler.app.ui.RouteDetailScreen
import com.cycler.app.ui.SettingsScreen
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

/**
 * Renders each screen to app/build/shots/<name>.png with made-up rides, so the
 * layout can be checked without a phone. The map is a sketch, not real tiles.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi", application = Application::class)
class Screenshots {
    @get:Rule val rule = createComposeRule()

    private val home = Pt(12.9716, 77.5946)
    private lateinit var vm: MainVm

    /** A wobbly loop of roughly [km] kilometres with rolling elevation. */
    private fun loop(km: Double, seed: Int): List<Pt> {
        val r = km * 1000 / (2 * Math.PI) / 1.15
        val centre = Geo.destination(home.lat, home.lon, seed * 70.0, r)
        return (0..120).map { i ->
            val a = i * 3.0 + seed * 70.0 + 180
            val wob = 1 + 0.18 * sin(i / 9.0 + seed) + 0.08 * cos(i / 3.0)
            val p = Geo.destination(centre.lat, centre.lon, a, r * wob)
            Pt(p.lat, p.lon, 900 + 40 * sin(i / 14.0 + seed) + 15 * cos(i / 5.0), 0)
        }
    }

    @Before fun seed() {
        RouteStore.resetForTests()
        val app = ApplicationProvider.getApplicationContext<Application>()
        vm = MainVm(app)
        val day = 24L * 3600 * 1000
        val now = System.currentTimeMillis()
        val rides = listOf(
            Triple("Morning ride", 24.6, 0L), Triple("Nandi foothills", 62.3, 2L), Triple("Evening ride", 12.1, 5L),
            Triple("Cubbon Park laps", 18.4, 9L), Triple("Hesaraghatta loop", 48.0, 16L),
            Triple("Morning ride", 21.0, 23L), Triple("Airport road", 71.5, 31L), Triple("Lake circuit", 15.2, 44L),
        )
        rides.forEachIndexed { i, (name, km, ago) ->
            val pts = loop(km, i + 1).mapIndexed { k, p -> p.copy(t = now - ago * day + k * 30_000L) }
            vm.store.save(
                name, RouteType.RECORDED, pts, (km / 21.0 * 3600).toLong(), km * 1000, km * 9.5,
                createdAt = now - ago * day, maxSpeedMs = 11.8, elapsedS = (km / 18.0 * 3600).toLong(),
            )
        }
        vm.store.save("To Lalbagh Botanical Garden", RouteType.PLANNED, loop(9.0, 11), 1800, 9000.0, 48.0)
        vm.weather = Weather.Now(27.4, 12.3, 25.0, 49.0, 30, 2, System.currentTimeMillis() + 70 * 60_000)
        TrackingService.state.value = TrackState()
        TrackingService.setGuide(null)
    }

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        rule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                CyclerTheme(if (dark) "dark" else "light") { content() }
            }
        }
        // Give background loading (files, images) a moment to land before the picture is taken.
        repeat(3) { rule.waitForIdle(); Thread.sleep(250) }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/$name.png")
    }

    private fun shell(name: String, tab: Tab, dark: Boolean = false, granted: Boolean = true) {
        vm.tab = tab
        shot(name, dark) { AppShell(vm, home, granted) {} }
    }

    private fun options() = listOf(
        RouteOption("Route 1", loop(14.0, 3), 14200.0, 96.0, 2460, listOf("Fastest")),
        RouteOption("Route 2", loop(13.0, 4), 13100.0, 142.0, 2580, listOf("Shortest")),
        RouteOption("Route 3", loop(16.0, 5), 16400.0, 71.0, 2820, listOf("Flattest")),
    )

    private fun recording(offRoute: Boolean = false) {
        val pts = loop(24.0, 2).take(70)
        TrackingService.state.value = TrackState(
            status = TrackStatus.RECORDING, points = pts, distanceM = 13_420.0, movingMs = 2_284_000,
            activeMs = 2_520_000, resumedAt = System.currentTimeMillis(), speedMs = 6.8f, maxSpeedMs = 11.4f,
            guideOffM = if (offRoute) 84.0 else 6.0, guideDoneM = 13_000.0, offRoute = offRoute,
        )
    }

    @Test fun ride_idle() = shell("01_ride_idle", Tab.RIDE)
    @Test fun ride_no_permission() = shell("02_ride_no_permission", Tab.RIDE, granted = false)
    @Test fun ride_recording() { recording(); shell("03_ride_recording", Tab.RIDE) }
    @Test fun ride_following_off_route() {
        vm.follow(vm.store.load(vm.store.metas.value.first { it.name == "Nandi foothills" }.id))
        recording(offRoute = true)
        shell("04_ride_off_route", Tab.RIDE)
    }
    @Test fun ride_following_turn() {
        vm.follow(vm.store.load(vm.store.metas.value.first { it.name == "Nandi foothills" }.id))
        recording()
        TrackingService.state.value = TrackingService.state.value.copy(
            nextTurn = "Turn left", nextTurnLeft = true, nextTurnM = 180.0, guideTotalM = 62_300.0, stops = 3,
        )
        shell("04b_ride_turn_cue", Tab.RIDE)
    }
    @Test fun share_video() {
        val id = vm.store.metas.value.first { it.name == "Nandi foothills" }.id
        shot("16_share_video") { com.cycler.app.ui.ShareScreen(vm, id) }
    }
    @Test fun heatmap() = shot("17_all_rides") { com.cycler.app.ui.HeatmapScreen(vm) }
    @Test fun ride_recording_dark() { recording(); shell("05_ride_recording_dark", Tab.RIDE, dark = true) }
    @Test fun plan_empty() = shell("06_plan_empty", Tab.PLAN)
    @Test fun plan_ab_results() {
        vm.end = Stop(Pt(12.9507, 77.5848), "Lalbagh Botanical Garden, Bengaluru")
        vm.vias = listOf(Stop(Pt(12.96, 77.59), "Pin on map"))
        vm.options = options()
        shell("07_plan_ab_results", Tab.PLAN)
    }
    @Test fun plan_loop() { vm.planMode = PlanMode.LOOP; shell("08_plan_loop", Tab.PLAN) }
    @Test fun plan_loop_results() {
        vm.planMode = PlanMode.LOOP
        vm.options = options().mapIndexed { i, o -> RouteOption(listOf("North loop", "East loop", "South loop")[i], o.pts, o.distanceM, o.ascentM, o.timeS, o.tags) }
        shell("08b_plan_loop_results", Tab.PLAN, dark = true)
    }
    @Test fun plan_past() { vm.planMode = PlanMode.PAST; shell("09_plan_past", Tab.PLAN) }
    @Test fun routes() {
        vm.suggestions = options().mapIndexed { i, o -> RouteOption(listOf("Short loop · north", "Medium loop · east", "Long loop · south-west")[i], o.pts, o.distanceM * (i + 1), o.ascentM * (i + 1), o.timeS * (i + 1)) }
        shell("10_routes", Tab.ROUTES)
    }
    @Test fun routes_dark() = shell("11_routes_dark", Tab.ROUTES, dark = true)
    @Test fun activity() = shell("12_activity", Tab.ACTIVITY)
    @Test fun activity_dark() = shell("13_activity_dark", Tab.ACTIVITY, dark = true)
    @Test fun settings() = shot("14_settings") { SettingsScreen(vm) }
    @Test fun detail() {
        val id = vm.store.metas.value.first { it.name == "Nandi foothills" }.id
        shot("15_detail") { RouteDetailScreen(vm, id) }
    }
}
