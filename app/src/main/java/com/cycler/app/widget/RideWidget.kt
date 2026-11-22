package com.cycler.app.widget

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.cycler.app.R
import com.cycler.app.Tab
import com.cycler.app.track.TrackStatus
import com.cycler.app.track.TrackingService
import com.cycler.app.ui.Fmt

/** Start a ride, or watch and control the one in progress. */
class RideWidget : BaseWidget() {
    companion object {
        /** From this width (dp) the Pause and Lap buttons carry words; narrower ones use icons. */
        const val WIDE_DP = 230
        /** Below this width there is only room for Pause. */
        const val MEDIUM_DP = 150

        fun build(ctx: Context, snap: WidgetSnapshot, widthDp: Int = 250): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_ride)
            // Anywhere outside the buttons opens the Ride tab.
            rv.setOnClickPendingIntent(android.R.id.background, WidgetIntents.openTab(ctx, Tab.RIDE, WidgetIntents.RC_RIDE_OPEN))
            val st = snap.track
            if (st.status == TrackStatus.IDLE) {
                rv.setViewVisibility(R.id.ride_idle, View.VISIBLE)
                rv.setViewVisibility(R.id.ride_active, View.GONE)
                val weekKm = WidgetData.week(snap).distanceM / 1000
                rv.setTextViewText(
                    R.id.ride_week_value,
                    ctx.getString(R.string.widget_week_progress, Fmt.kmShort(weekKm * 1000), snap.goalKm),
                )
                rv.setTextViewText(
                    R.id.ride_week_label,
                    ctx.getString(if (weekKm >= snap.goalKm) R.string.widget_this_week_goal_reached else R.string.widget_this_week),
                )
                rv.setOnClickPendingIntent(R.id.ride_start, WidgetIntents.startRide(ctx, WidgetIntents.RC_RIDE_START))
                return rv
            }

            val paused = st.status == TrackStatus.PAUSED
            val wide = widthDp >= WIDE_DP
            val medium = !wide && widthDp >= MEDIUM_DP
            rv.setViewVisibility(R.id.ride_idle, View.GONE)
            rv.setViewVisibility(R.id.ride_active, View.VISIBLE)
            rv.setTextViewText(R.id.ride_distance, ctx.getString(R.string.widget_km, Fmt.kmNumber(st.distanceM)))

            val clock = Fmt.clock(st.movingMs)
            val avg = ctx.getString(R.string.widget_kmh_avg, Fmt.kmh(WidgetData.avgSpeedMs(st.distanceM, st.movingMs / 1000)))
            val statusText = ctx.getString(
                when {
                    paused -> R.string.widget_status_paused
                    st.autoPaused -> R.string.widget_status_auto_paused
                    else -> R.string.widget_status_recording
                },
            )
            // The status always shows: as a label on wide widgets, in front of the time on narrow ones.
            rv.setViewVisibility(R.id.ride_status, if (wide) View.VISIBLE else View.GONE)
            rv.setTextViewText(R.id.ride_status, statusText)
            rv.setTextColor(R.id.ride_status, ctx.getColor(if (paused || st.autoPaused) R.color.widget_muted else R.color.widget_danger))
            rv.setTextViewText(
                R.id.ride_detail,
                when {
                    wide || (medium && !paused) -> "$clock · $avg"
                    paused -> ctx.getString(R.string.widget_paused_prefix, clock)
                    else -> clock
                },
            )

            // Pause turns into Resume, in the accent colour, while paused.
            val action = if (paused) TrackingService.ACTION_RESUME else TrackingService.ACTION_PAUSE
            val pause = WidgetIntents.serviceAction(ctx, action, if (paused) WidgetIntents.RC_RIDE_RESUME else WidgetIntents.RC_RIDE_PAUSE)
            val pauseLabel = ctx.getString(if (paused) R.string.widget_resume else R.string.widget_pause)
            rv.setViewVisibility(R.id.ride_pause, if (wide) View.VISIBLE else View.GONE)
            rv.setTextViewText(R.id.ride_pause, pauseLabel)
            rv.setInt(R.id.ride_pause, "setBackgroundResource", if (paused) R.drawable.widget_btn_primary else R.drawable.widget_btn_secondary)
            rv.setTextColor(R.id.ride_pause, ctx.getColor(if (paused) R.color.widget_on_accent else R.color.widget_ink))
            rv.setOnClickPendingIntent(R.id.ride_pause, pause)

            rv.setViewVisibility(R.id.ride_pause_icon, if (wide) View.GONE else View.VISIBLE)
            rv.setImageViewResource(R.id.ride_pause_icon, if (paused) R.drawable.widget_ic_play else R.drawable.widget_ic_pause)
            rv.setInt(R.id.ride_pause_icon, "setBackgroundResource", if (paused) R.drawable.widget_btn_primary else R.drawable.widget_btn_secondary)
            rv.setContentDescription(R.id.ride_pause_icon, pauseLabel)
            rv.setOnClickPendingIntent(R.id.ride_pause_icon, pause)

            // Lap is only offered while recording; a paused ride has nothing to mark.
            val lap = WidgetIntents.serviceAction(ctx, TrackingService.ACTION_LAP, WidgetIntents.RC_RIDE_LAP)
            rv.setViewVisibility(R.id.ride_lap, if (wide && !paused) View.VISIBLE else View.GONE)
            rv.setViewVisibility(R.id.ride_lap_icon, if (medium && !paused) View.VISIBLE else View.GONE)
            rv.setOnClickPendingIntent(R.id.ride_lap, lap)
            rv.setOnClickPendingIntent(R.id.ride_lap_icon, lap)
            return rv
        }
    }
}
