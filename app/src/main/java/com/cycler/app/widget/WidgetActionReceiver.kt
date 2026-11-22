package com.cycler.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cycler.app.track.TrackStatus
import com.cycler.app.track.TrackingService

/**
 * Receives the Pause, Resume and Lap taps from the Ride widget, plus the system broadcasts that
 * should refresh the widgets (a new day starts a new week, a new locale changes the text).
 */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching {
            when (intent.action) {
                TrackingService.ACTION_PAUSE, TrackingService.ACTION_RESUME, TrackingService.ACTION_LAP -> {
                    forward(context, intent.action!!)
                    // Fixes a widget that was left showing a ride that has since ended.
                    Widgets.updateAll(context)
                }
                else -> Widgets.updateAll(context)
            }
        }
    }

    companion object {
        /**
         * Pass [action] to the running ride. Does nothing when no ride is in progress, or when the
         * action does not fit the state (pause while paused, and so on). Returns whether it was sent.
         */
        fun forward(context: Context, action: String): Boolean {
            val status = TrackingService.state.value.status
            val ok = when (action) {
                TrackingService.ACTION_PAUSE, TrackingService.ACTION_LAP -> status == TrackStatus.RECORDING
                TrackingService.ACTION_RESUME -> status == TrackStatus.PAUSED
                else -> false
            }
            if (!ok) return false
            return runCatching { TrackingService.send(context, action) }.isSuccess
        }
    }
}
