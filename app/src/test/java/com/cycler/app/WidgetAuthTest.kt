package com.cycler.app

import android.Manifest
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.cycler.app.data.RouteStore
import com.cycler.app.track.TrackState
import com.cycler.app.track.TrackStatus
import com.cycler.app.track.TrackingService
import com.cycler.app.widget.WidgetAuth
import com.cycler.app.widget.WidgetIntents
import com.cycler.app.widget.WidgetLaunch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Widget extras only count when they carry this install's secret token. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WidgetAuthTest {
    private lateinit var ctx: Application

    @Before fun setUp() {
        RouteStore.resetForTests()
        ctx = ApplicationProvider.getApplicationContext()
        TrackingService.state.value = TrackState()
        shadowOf(ctx).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun startIntent(token: String?, action: String? = WidgetIntents.OPEN_ACTION) = Intent().also {
        if (action != null) it.action = action
        it.putExtra(WidgetIntents.EXTRA_TAB, Tab.RIDE.name)
        it.putExtra(WidgetIntents.EXTRA_WIDGET_ACTION, WidgetIntents.WIDGET_ACTION_START)
        if (token != null) it.putExtra(WidgetIntents.EXTRA_TOKEN, token)
    }

    private fun vm() = MainVm(ctx).also { it.onboarded = true; it.tab = Tab.ACTIVITY; it.showSettings = true }

    private fun assertRejected(i: Intent) {
        val vm = vm()
        assertFalse(WidgetLaunch.handle(ctx, i, vm))
        assertEquals(Tab.ACTIVITY, vm.tab)
        assertTrue(vm.showSettings) // the open page was not closed
        assertNull(shadowOf(ctx).nextStartedService)
        assertEquals(TrackStatus.IDLE, TrackingService.state.value.status)
        assertNull(i.getStringExtra(WidgetIntents.EXTRA_WIDGET_ACTION))
    }

    @Test fun foreign_start_without_token_does_nothing() = assertRejected(startIntent(null))

    @Test fun foreign_start_with_wrong_token_does_nothing() {
        WidgetAuth.token(ctx)
        assertRejected(startIntent("not-the-token"))
    }

    @Test fun foreign_start_with_empty_token_does_nothing() = assertRejected(startIntent(""))

    @Test fun correct_token_with_wrong_or_missing_intent_action_is_rejected() {
        val t = WidgetAuth.token(ctx)
        assertRejected(startIntent(t, action = null))
        assertRejected(startIntent(t, action = Intent.ACTION_VIEW))
    }

    @Test fun foreign_tab_and_ride_extras_do_not_navigate_or_close_pages() {
        for (k in listOf(WidgetIntents.EXTRA_TAB, WidgetIntents.EXTRA_RIDE_ID)) {
            val vm = vm()
            val i = Intent(WidgetIntents.OPEN_ACTION).putExtra(k, if (k == WidgetIntents.EXTRA_TAB) Tab.RIDE.name else "x")
            assertFalse(WidgetLaunch.handle(ctx, i, vm))
            assertEquals(Tab.ACTIVITY, vm.tab)
            assertTrue(vm.showSettings)
            assertNull(vm.detailId)
        }
    }

    @Test fun correct_token_starts_the_ride() {
        val vm = vm()
        assertTrue(WidgetLaunch.handle(ctx, startIntent(WidgetAuth.token(ctx)), vm))
        assertEquals(Tab.RIDE, vm.tab)
        assertFalse(vm.showSettings)
        assertEquals(TrackingService.ACTION_START, shadowOf(ctx).nextStartedService.action)
    }

    @Test fun the_real_widget_start_pending_intent_is_accepted() {
        val saved = shadowOf(WidgetIntents.startRide(ctx, WidgetIntents.RC_RIDE_START)).savedIntent
        assertEquals(WidgetIntents.OPEN_ACTION, saved.action)
        assertTrue(WidgetLaunch.handle(ctx, Intent(saved), vm()))
        assertEquals(TrackingService.ACTION_START, shadowOf(ctx).nextStartedService.action)
    }

    @Test fun plain_launcher_start_is_not_a_widget_intent() {
        val vm = vm()
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        assertFalse(WidgetLaunch.handle(ctx, i, vm))
        assertEquals(Tab.ACTIVITY, vm.tab)
        assertTrue(vm.showSettings)
    }

    @Test fun token_is_generated_once_and_persisted() {
        val prefs = ctx.getSharedPreferences("widget_auth", 0)
        assertNull(prefs.getString("token", null))
        val a = WidgetAuth.token(ctx)
        assertTrue(a.length >= 32)
        assertEquals(a, prefs.getString("token", null))
        assertEquals(a, WidgetAuth.token(ctx))
        assertTrue(WidgetAuth.valid(ctx, a))
        assertFalse(WidgetAuth.valid(ctx, null))
        assertFalse(WidgetAuth.valid(ctx, a + "x"))
        // Not stored in the main (backed-up) prefs file.
        assertNull(ctx.getSharedPreferences("cycler", 0).getString("token", null))
        assertNotEquals("", a)
    }
}
