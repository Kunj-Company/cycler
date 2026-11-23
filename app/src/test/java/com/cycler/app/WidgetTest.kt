package com.cycler.app

import android.Manifest
import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.cycler.app.data.Pt
import com.cycler.app.data.RouteStore
import com.cycler.app.data.RouteType
import com.cycler.app.track.TrackState
import com.cycler.app.track.TrackStatus
import com.cycler.app.track.TrackingService
import com.cycler.app.widget.LastRideWidget
import com.cycler.app.widget.RideWidget
import com.cycler.app.widget.StatsWidget
import com.cycler.app.widget.WeekWidget
import com.cycler.app.widget.WidgetActionReceiver
import com.cycler.app.widget.WidgetData
import com.cycler.app.widget.WidgetIntents
import com.cycler.app.widget.WidgetLaunch
import com.cycler.app.widget.WidgetRender
import com.cycler.app.widget.WidgetSnapshot
import com.cycler.app.widget.Widgets
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

/**
 * Builds every widget's RemoteViews through the app's own code and inflates it, which catches bad view
 * ids, views that are not allowed in a widget, and crashes in the drawing code. Nothing here proves how
 * a launcher lays the widget out; that needs a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WidgetTest {
    private lateinit var ctx: Application
    private lateinit var store: RouteStore

    /** Wednesday 18 Nov 2026, noon: the week started Monday the 16th and the month on the 1st. */
    private val now: Long = Calendar.getInstance().apply {
        clear(); set(2026, Calendar.NOVEMBER, 18, 12, 0, 0)
    }.timeInMillis
    private val hour = 3600_000L
    private val day = 24 * hour

    private fun loop(seed: Int) = (0..40).map { i ->
        val a = i * 9.0 + seed * 40
        Pt(12.97 + 0.02 * sin(Math.toRadians(a)) * (1 + 0.2 * cos(i / 3.0)), 77.59 + 0.02 * cos(Math.toRadians(a)), 900.0, 0)
    }

    private fun ride(name: String, km: Double, createdAt: Long, ascent: Double = km * 9.0) {
        store.save(name, RouteType.RECORDED, loop(name.length), (km / 20.0 * 3600).toLong(), km * 1000, ascent, createdAt)
    }

    @Before fun setUp() {
        RouteStore.resetForTests()
        ctx = ApplicationProvider.getApplicationContext()
        store = RouteStore.get(ctx)
        TrackingService.state.value = TrackState()
    }

    @After fun tearDown() {
        TrackingService.state.value = TrackState()
    }

    /** 60 km in 3 rides this week, 55 km more earlier this month, and an old one last month. */
    private fun seed() {
        ride("Morning ride", 10.0, now - hour)
        ride("Tuesday spin", 20.0, now - day)
        ride("Monday loop", 30.0, now - 2 * day)
        ride("Sunday long", 40.0, now - 3 * day)
        ride("Early month", 15.0, now - 16 * day)
        ride("Last month", 50.0, now - 29 * day)
        store.save("Planned route", RouteType.PLANNED, loop(9), 3600, 99_000.0, 10.0, now)
    }

    private fun snap(goal: Int = 100) = WidgetSnapshot.of(TrackingService.state.value, store.metas.value, goal, now)

    private fun inflate(rv: RemoteViews): View = rv.apply(ctx, FrameLayout(ctx))
    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()
    private fun View.shown(id: Int) = findViewById<View>(id).visibility == View.VISIBLE

    private fun recording(status: TrackStatus = TrackStatus.RECORDING, auto: Boolean = false) {
        TrackingService.state.value = TrackState(
            status = status, distanceM = 13_420.0, movingMs = 2_284_000, activeMs = 2_520_000,
            resumedAt = now, speedMs = 6.8f, autoPaused = auto,
        )
    }

    // --- Ride ---

    @Test fun ride_idle_no_rides() {
        val v = inflate(RideWidget.build(ctx, snap()))
        assertTrue(v.shown(R.id.ride_idle)); assertFalse(v.shown(R.id.ride_active))
        assertEquals("0.0 / 100 km", v.text(R.id.ride_week_value))
        assertEquals("this week", v.text(R.id.ride_week_label))
        assertEquals("Start ride", v.text(R.id.ride_start))
    }

    @Test fun ride_idle_with_rides_and_goal_reached() {
        seed()
        var v = inflate(RideWidget.build(ctx, snap()))
        assertEquals("60.0 / 100 km", v.text(R.id.ride_week_value))
        v = inflate(RideWidget.build(ctx, snap(goal = 50)))
        assertEquals("60.0 / 50 km", v.text(R.id.ride_week_value))
        assertEquals("this week · goal reached", v.text(R.id.ride_week_label))
    }

    @Test fun ride_recording_wide() {
        seed(); recording()
        val v = inflate(RideWidget.build(ctx, snap(), widthDp = 300))
        assertFalse(v.shown(R.id.ride_idle)); assertTrue(v.shown(R.id.ride_active))
        assertEquals("13.42 km", v.text(R.id.ride_distance))
        assertEquals("38:04 · avg 21.2 km/h", v.text(R.id.ride_detail))
        assertEquals("Recording", v.text(R.id.ride_status)); assertTrue(v.shown(R.id.ride_status))
        assertEquals("Pause", v.text(R.id.ride_pause))
        assertTrue(v.shown(R.id.ride_pause)); assertTrue(v.shown(R.id.ride_lap))
        assertFalse(v.shown(R.id.ride_pause_icon)); assertFalse(v.shown(R.id.ride_lap_icon))
    }

    @Test fun ride_paused_wide() {
        recording(TrackStatus.PAUSED)
        val v = inflate(RideWidget.build(ctx, snap(), widthDp = 300))
        assertEquals("Paused", v.text(R.id.ride_status))
        assertEquals("Resume", v.text(R.id.ride_pause))
        assertFalse(v.shown(R.id.ride_lap))
    }

    @Test fun ride_auto_paused_is_labelled() {
        recording(auto = true)
        val v = inflate(RideWidget.build(ctx, snap(), widthDp = 300))
        assertEquals("Auto-paused", v.text(R.id.ride_status))
    }

    @Test fun ride_narrow_and_medium_use_icons() {
        recording()
        var v = inflate(RideWidget.build(ctx, snap(), widthDp = 110))
        assertEquals("13.42 km", v.text(R.id.ride_distance))
        assertEquals("38:04", v.text(R.id.ride_detail))
        assertTrue(v.shown(R.id.ride_pause_icon)); assertFalse(v.shown(R.id.ride_lap_icon))
        assertFalse(v.shown(R.id.ride_pause)); assertFalse(v.shown(R.id.ride_status))
        v = inflate(RideWidget.build(ctx, snap(), widthDp = 180))
        assertTrue(v.shown(R.id.ride_pause_icon)); assertTrue(v.shown(R.id.ride_lap_icon))
        assertEquals("38:04 · avg 21.2 km/h", v.text(R.id.ride_detail))
        recording(TrackStatus.PAUSED)
        v = inflate(RideWidget.build(ctx, snap(), widthDp = 110))
        assertEquals("Paused · 38:04", v.text(R.id.ride_detail))
        assertFalse(v.shown(R.id.ride_lap_icon))
    }

    @Test fun ride_recording_with_nothing_yet_does_not_divide_by_zero() {
        TrackingService.state.value = TrackState(status = TrackStatus.RECORDING)
        val v = inflate(RideWidget.build(ctx, snap(), widthDp = 300))
        assertEquals("0.00 km", v.text(R.id.ride_distance))
        assertEquals("00:00 · avg 0.0 km/h", v.text(R.id.ride_detail))
    }

    // --- This week ---

    @Test fun week_empty() {
        val v = inflate(WeekWidget.build(ctx, snap()))
        assertEquals("0.0 km", v.text(R.id.week_distance))
        assertEquals("of 100 km this week", v.text(R.id.week_goal))
        assertEquals("0 rides", v.text(R.id.week_rides))
        assertEquals("0 min", v.text(R.id.week_time))
        assertEquals(0, v.findViewById<ProgressBar>(R.id.week_progress).progress)
    }

    @Test fun week_with_rides_counts_only_recorded_rides_of_this_week() {
        seed()
        val v = inflate(WeekWidget.build(ctx, snap()))
        assertEquals("60.0 km", v.text(R.id.week_distance))
        assertEquals("3 rides", v.text(R.id.week_rides))
        assertEquals("3h 00m", v.text(R.id.week_time))
        assertEquals(600, v.findViewById<ProgressBar>(R.id.week_progress).progress)
        val done = inflate(WeekWidget.build(ctx, snap(goal = 50)))
        assertEquals("goal reached · 50 km", done.text(R.id.week_goal))
        assertEquals(1000, done.findViewById<ProgressBar>(R.id.week_progress).progress)
    }

    @Test fun week_one_ride_is_singular() {
        ride("Only", 5.0, now - hour)
        assertEquals("1 ride", inflate(WeekWidget.build(ctx, snap())).text(R.id.week_rides))
    }

    // --- Last ride ---

    @Test fun last_ride_empty_state() {
        val v = inflate(LastRideWidget.build(ctx, snap()))
        assertTrue(v.shown(R.id.last_empty)); assertFalse(v.shown(R.id.last_content))
        assertEquals("Start ride", v.text(R.id.last_start))
    }

    @Test fun last_ride_planned_routes_do_not_count() {
        store.save("Planned route", RouteType.PLANNED, loop(9), 3600, 99_000.0, 10.0, now)
        assertTrue(inflate(LastRideWidget.build(ctx, snap())).shown(R.id.last_empty))
    }

    @Test fun last_ride_shows_newest_ride_and_route() {
        seed()
        val v = inflate(LastRideWidget.build(ctx, snap(), widthDp = 300))
        assertTrue(v.shown(R.id.last_content)); assertFalse(v.shown(R.id.last_empty))
        assertEquals("Morning ride", v.text(R.id.last_name))
        assertEquals("10.0 km", v.text(R.id.last_distance))
        assertEquals("30 min", v.text(R.id.last_time))
        assertEquals("90 m", v.text(R.id.last_climb))
        assertEquals("20.0", v.text(R.id.last_speed))
        assertTrue(v.text(R.id.last_date).isNotBlank())
        assertTrue(v.shown(R.id.last_route))
        val bmp = (v.findViewById<ImageView>(R.id.last_route).drawable as BitmapDrawable).bitmap
        assertEquals(240, bmp.width); assertEquals(240, bmp.height)
        assertTrue(bmp.byteCount < 400_000)
    }

    @Test fun last_ride_narrow_hides_the_route() {
        seed()
        assertFalse(inflate(LastRideWidget.build(ctx, snap(), widthDp = 180)).shown(R.id.last_route))
    }

    // --- Riding stats ---

    @Test fun stats_empty() {
        val v = inflate(StatsWidget.build(ctx, snap()))
        assertEquals("0.0 km", v.text(R.id.stats_month_distance))
        assertEquals("0 rides", v.text(R.id.stats_month_rides))
        assertEquals("0 min moving", v.text(R.id.stats_month_time))
        assertEquals("0 m climb", v.text(R.id.stats_month_climb))
        assertNotNull((v.findViewById<ImageView>(R.id.stats_chart).drawable as BitmapDrawable).bitmap)
    }

    @Test fun stats_with_rides() {
        seed()
        val v = inflate(StatsWidget.build(ctx, snap()))
        assertEquals("115 km", v.text(R.id.stats_month_distance))
        assertEquals("5 rides", v.text(R.id.stats_month_rides))
        assertEquals("5h 45m moving", v.text(R.id.stats_month_time))
        assertEquals("1035 m climb", v.text(R.id.stats_month_climb))
        val bmp = (v.findViewById<ImageView>(R.id.stats_chart).drawable as BitmapDrawable).bitmap
        assertEquals(400, bmp.width); assertEquals(240, bmp.height)
    }

    @Test fun last_seven_days_buckets_and_initials() {
        seed()
        val km = WidgetData.lastSevenDays(snap().rides, now).map { it / 1000 }
        // Thu, Fri, Sat, Sun, Mon, Tue, Wed
        assertEquals(listOf(0.0, 0.0, 0.0, 40.0, 30.0, 20.0, 10.0), km)
        val letters = WidgetData.weekdayInitials(now)
        assertEquals(7, letters.size)
        assertEquals("W", letters.last())
    }

    @Test fun drawing_handles_odd_input() {
        assertNull(WidgetRender.routeBitmap(emptyList()))
        assertNull(WidgetRender.routeBitmap(listOf(Pt(1.0, 1.0))))
        assertNotNull(WidgetRender.routeBitmap(listOf(Pt(1.0, 1.0), Pt(1.0, 1.0))))
        assertNotNull(WidgetRender.weekBars(emptyList(), emptyList()))
        assertNotNull(WidgetRender.weekBars(listOf(0.0, 1e9, 3.0), listOf("M")))
    }

    // --- Taps ---

    @Test fun tap_targets_use_distinct_immutable_pending_intents() {
        val codes = listOf(
            WidgetIntents.RC_RIDE_OPEN, WidgetIntents.RC_RIDE_START, WidgetIntents.RC_RIDE_PAUSE, WidgetIntents.RC_RIDE_RESUME,
            WidgetIntents.RC_RIDE_LAP, WidgetIntents.RC_WEEK_OPEN, WidgetIntents.RC_LAST_OPEN, WidgetIntents.RC_LAST_START,
            WidgetIntents.RC_STATS_OPEN, WidgetIntents.RC_FALLBACK,
        )
        assertEquals(codes.size, codes.toSet().size)
        val start = shadowOf(WidgetIntents.startRide(ctx, WidgetIntents.RC_RIDE_START))
        assertTrue(start.isActivityIntent)
        assertEquals(WidgetIntents.WIDGET_ACTION_START, start.savedIntent.getStringExtra(WidgetIntents.EXTRA_WIDGET_ACTION))
        assertEquals(Tab.RIDE.name, start.savedIntent.getStringExtra(WidgetIntents.EXTRA_TAB))
        assertEquals(MainActivity::class.java.name, start.savedIntent.component?.className)
        val pause = shadowOf(WidgetIntents.serviceAction(ctx, TrackingService.ACTION_PAUSE, WidgetIntents.RC_RIDE_PAUSE))
        assertTrue(pause.isBroadcastIntent)
        assertEquals(TrackingService.ACTION_PAUSE, pause.savedIntent.action)
        val detail = shadowOf(WidgetIntents.openRide(ctx, "abc", WidgetIntents.RC_LAST_OPEN))
        assertEquals("abc", detail.savedIntent.getStringExtra(WidgetIntents.EXTRA_RIDE_ID))
    }

    @Test fun tapping_start_ride_opens_the_activity_with_the_start_extra() {
        val v = inflate(RideWidget.build(ctx, snap()))
        v.findViewById<View>(R.id.ride_start).performClick()
        val started = shadowOf(ctx).nextStartedActivity
        assertNotNull(started)
        assertEquals(WidgetIntents.WIDGET_ACTION_START, started.getStringExtra(WidgetIntents.EXTRA_WIDGET_ACTION))
    }

    @Test fun pause_resume_and_lap_only_reach_a_ride_in_progress() {
        val pause = TrackingService.ACTION_PAUSE; val resume = TrackingService.ACTION_RESUME; val lap = TrackingService.ACTION_LAP
        // No ride: nothing is sent.
        for (a in listOf(pause, resume, lap)) assertFalse(WidgetActionReceiver.forward(ctx, a))
        assertNull(shadowOf(ctx).nextStartedService)
        // Recording: pause and lap, not resume.
        recording()
        assertFalse(WidgetActionReceiver.forward(ctx, resume))
        assertTrue(WidgetActionReceiver.forward(ctx, pause))
        assertEquals(pause, shadowOf(ctx).nextStartedService.action)
        assertTrue(WidgetActionReceiver.forward(ctx, lap))
        assertEquals(lap, shadowOf(ctx).nextStartedService.action)
        // Paused: resume only.
        recording(TrackStatus.PAUSED)
        assertFalse(WidgetActionReceiver.forward(ctx, pause)); assertFalse(WidgetActionReceiver.forward(ctx, lap))
        assertTrue(WidgetActionReceiver.forward(ctx, resume))
        assertEquals(resume, shadowOf(ctx).nextStartedService.action)
        assertFalse(WidgetActionReceiver.forward(ctx, "something.else"))
    }

    // --- Starting from the widget, inside the activity ---

    private fun fromWidget(vararg extras: Pair<String, String>) =
        Intent(WidgetIntents.OPEN_ACTION).putExtra(WidgetIntents.EXTRA_TOKEN, com.cycler.app.widget.WidgetAuth.token(ctx))
            .also { i -> extras.forEach { (k, v) -> i.putExtra(k, v) } }

    private fun startIntent() = fromWidget(
        WidgetIntents.EXTRA_TAB to Tab.RIDE.name, WidgetIntents.EXTRA_WIDGET_ACTION to WidgetIntents.WIDGET_ACTION_START,
    )

    @Test fun start_extra_starts_one_ride_and_is_consumed() {
        shadowOf(ctx).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val vm = MainVm(ctx)
        vm.onboarded = true
        vm.tab = Tab.ACTIVITY
        val i = startIntent()
        assertTrue(WidgetLaunch.handle(ctx, i, vm))
        assertEquals(Tab.RIDE, vm.tab)
        assertEquals(TrackingService.ACTION_START, shadowOf(ctx).nextStartedService.action)
        // The same intent again (rotation, recreate) does nothing.
        assertFalse(WidgetLaunch.handle(ctx, i, vm))
        assertNull(shadowOf(ctx).nextStartedService)
        assertNull(i.getStringExtra(WidgetIntents.EXTRA_WIDGET_ACTION))
    }

    @Test fun start_extra_during_first_launch_setup_does_not_start_a_ride() {
        shadowOf(ctx).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val vm = MainVm(ctx)
        vm.onboarded = false
        WidgetLaunch.handle(ctx, startIntent(), vm)
        assertNull(shadowOf(ctx).nextStartedService)
    }

    @Test fun start_extra_without_location_permission_only_opens_the_ride_tab() {
        val vm = MainVm(ctx)
        vm.tab = Tab.RIDES
        WidgetLaunch.handle(ctx, startIntent(), vm)
        assertEquals(Tab.RIDE, vm.tab)
        assertNull(shadowOf(ctx).nextStartedService)
    }

    @Test fun start_extra_never_starts_over_a_ride_in_progress() {
        shadowOf(ctx).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val vm = MainVm(ctx)
        for (s in listOf(TrackStatus.RECORDING, TrackStatus.PAUSED)) {
            recording(s)
            WidgetLaunch.handle(ctx, startIntent(), vm)
            assertEquals(Tab.RIDE, vm.tab)
            assertNull(shadowOf(ctx).nextStartedService)
        }
    }

    @Test fun tab_and_ride_extras_navigate_and_close_open_pages() {
        seed()
        val vm = MainVm(ctx)
        vm.showSettings = true
        vm.detailId = "something"
        assertTrue(WidgetLaunch.handle(ctx, fromWidget(WidgetIntents.EXTRA_TAB to Tab.ACTIVITY.name), vm))
        assertEquals(Tab.ACTIVITY, vm.tab); assertFalse(vm.showSettings); assertNull(vm.detailId)
        val id = store.metas.value.first().id
        WidgetLaunch.handle(ctx, fromWidget(WidgetIntents.EXTRA_RIDE_ID to id), vm)
        assertEquals(id, vm.detailId)
        vm.detailId = null
        WidgetLaunch.handle(ctx, fromWidget(WidgetIntents.EXTRA_RIDE_ID to "deleted-ride"), vm)
        assertNull(vm.detailId)
        assertFalse(WidgetLaunch.handle(ctx, Intent(), vm))
        assertFalse(WidgetLaunch.handle(ctx, null, vm))
        assertNull(shadowOf(ctx).nextStartedService)
    }

    // --- Updating ---

    @Test fun updateAllNow_fills_every_placed_widget_and_survives_none() {
        Widgets.updateAllNow(ctx) // no widgets placed: must not throw
        seed()
        val mgr = shadowOf(AppWidgetManager.getInstance(ctx))
        val ids = mapOf(
            RideWidget::class.java to mgr.createWidget(RideWidget::class.java, R.layout.widget_loading),
            WeekWidget::class.java to mgr.createWidget(WeekWidget::class.java, R.layout.widget_loading),
            LastRideWidget::class.java to mgr.createWidget(LastRideWidget::class.java, R.layout.widget_loading),
            StatsWidget::class.java to mgr.createWidget(StatsWidget::class.java, R.layout.widget_loading),
        )
        Widgets.updateAllNow(ctx)
        assertEquals("Morning ride", mgr.getViewFor(ids.getValue(LastRideWidget::class.java)).text(R.id.last_name))
        assertTrue(mgr.getViewFor(ids.getValue(RideWidget::class.java)).text(R.id.ride_week_value).contains("/ 50 km"))
        assertTrue(mgr.getViewFor(ids.getValue(WeekWidget::class.java)).text(R.id.week_goal).isNotBlank())
        assertTrue(mgr.getViewFor(ids.getValue(StatsWidget::class.java)).shown(R.id.stats_chart))
    }

    @Test fun fallback_inflates() {
        val v = inflate(Widgets.fallback(ctx))
        assertEquals("Open Cycler", v.text(R.id.loading_text))
    }

    @Test fun updateAll_never_throws_and_can_be_spammed() {
        repeat(50) { Widgets.updateAll(ctx) }
    }
}
