package com.cycler.app.widget

import android.content.Context
import android.content.Intent
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.withResumed
import com.cycler.app.MainVm
import com.cycler.app.Tab
import com.cycler.app.hasLocationPermission
import com.cycler.app.track.TrackStatus
import com.cycler.app.track.TrackingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What [com.cycler.app.MainActivity] does with an intent that came from a widget. */
object WidgetLaunch {
    /**
     * Show the tab or ride the widget asked for and, for "start", begin recording the way the Ride
     * screen's Start button does. The widget extras are removed from [intent], so a second call with
     * the same intent (a rotation, say) does nothing. Returns whether the intent was from a widget.
     */
    fun handle(ctx: Context, intent: Intent?, vm: MainVm): Boolean {
        val i = intent ?: return false
        val action = i.getStringExtra(WidgetIntents.EXTRA_WIDGET_ACTION)
        val tabName = i.getStringExtra(WidgetIntents.EXTRA_TAB)
        val rideId = i.getStringExtra(WidgetIntents.EXTRA_RIDE_ID)
        if (action == null && tabName == null && rideId == null) return false
        val token = i.getStringExtra(WidgetIntents.EXTRA_TOKEN)
        val authentic = i.action == WidgetIntents.OPEN_ACTION && WidgetAuth.valid(ctx, token)
        i.removeExtra(WidgetIntents.EXTRA_WIDGET_ACTION)
        i.removeExtra(WidgetIntents.EXTRA_TAB)
        i.removeExtra(WidgetIntents.EXTRA_RIDE_ID)
        i.removeExtra(WidgetIntents.EXTRA_TOKEN)
        // Anything exported can be sent these extras; without the install's secret it is not a widget tap.
        if (!authentic) return false

        // Close whatever full-screen page is open so the tab or ride is what the rider sees.
        vm.showSettings = false
        vm.shareId = null
        vm.editId = null
        vm.editIsNew = false
        vm.segmentFromRide = null
        vm.segmentId = null
        vm.showSegments = false
        vm.showOffline = false
        vm.showHeatmap = false
        vm.showCare = false
        vm.showFieldEditor = false
        vm.detailId = null

        val tab = Tab.entries.firstOrNull { it.name == tabName }
        if (tab != null) vm.tab = tab
        if (rideId != null && vm.store.metas.value.any { it.id == rideId }) vm.detailId = rideId
        if (action == WidgetIntents.WIDGET_ACTION_START) start(ctx, vm)
        return true
    }

    private fun idle() = TrackingService.state.value.status == TrackStatus.IDLE

    /** Same checks and call as the Ride screen's Start button; without location permission it only shows the Ride tab. */
    private fun start(ctx: Context, vm: MainVm) {
        // During first-launch setup the Location step asks for permission; never start a ride under it.
        if (!vm.onboarded || !idle() || !hasLocationPermission(ctx)) return
        vm.viewModelScope.launch {
            // Android only lets a location recording start while the app is on screen.
            (ctx as? LifecycleOwner)?.lifecycle?.withResumed { }
            // A ride that was cut short last time is recovered when the app opens; let that finish first.
            val file = vm.store.recordingFile
            if (file.exists()) withTimeoutOrNull(5000) { while (file.exists()) delay(100) }
            if (idle() && hasLocationPermission(ctx)) {
                TrackingService.workout = vm.workout
                runCatching { TrackingService.send(ctx, TrackingService.ACTION_START) }
            }
        }
    }
}
