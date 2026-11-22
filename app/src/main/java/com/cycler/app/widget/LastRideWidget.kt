package com.cycler.app.widget

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.cycler.app.R
import com.cycler.app.Tab
import com.cycler.app.ui.Fmt
import kotlin.math.roundToInt

/** The most recent recorded ride, with its route drawn beside the numbers. */
class LastRideWidget : BaseWidget() {
    companion object {
        /** Narrower than this (dp) and the route picture is left out to give the numbers room. */
        const val ROUTE_MIN_DP = 230

        fun build(ctx: Context, snap: WidgetSnapshot, widthDp: Int = 250): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_last_ride)
            val ride = snap.rides.firstOrNull()
            if (ride == null) {
                rv.setViewVisibility(R.id.last_content, View.GONE)
                rv.setViewVisibility(R.id.last_empty, View.VISIBLE)
                rv.setOnClickPendingIntent(R.id.last_start, WidgetIntents.startRide(ctx, WidgetIntents.RC_LAST_START))
                rv.setOnClickPendingIntent(android.R.id.background, WidgetIntents.openTab(ctx, Tab.RIDE, WidgetIntents.RC_LAST_OPEN))
                return rv
            }
            rv.setViewVisibility(R.id.last_content, View.VISIBLE)
            rv.setViewVisibility(R.id.last_empty, View.GONE)
            rv.setTextViewText(R.id.last_name, ride.name.ifBlank { ctx.getString(R.string.widget_ride_fallback_name) })
            rv.setTextViewText(R.id.last_date, Fmt.day(ride.createdAt))
            rv.setTextViewText(R.id.last_distance, Fmt.km(ride.distanceM))
            rv.setTextViewText(R.id.last_time, Fmt.dur(ride.durationS))
            val climb = if (ride.ascentM.isNaN()) 0 else ride.ascentM.roundToInt()
            rv.setTextViewText(R.id.last_climb, ctx.getString(R.string.widget_m, climb.toString()))
            rv.setTextViewText(R.id.last_speed, Fmt.kmh(WidgetData.avgSpeedMs(ride.distanceM, ride.durationS)))

            val bmp = if (widthDp >= ROUTE_MIN_DP) runCatching { WidgetRender.routeBitmap(ride.thumb) }.getOrNull() else null
            if (bmp != null) {
                rv.setViewVisibility(R.id.last_route, View.VISIBLE)
                rv.setImageViewBitmap(R.id.last_route, bmp)
            } else rv.setViewVisibility(R.id.last_route, View.GONE)

            rv.setOnClickPendingIntent(android.R.id.background, WidgetIntents.openRide(ctx, ride.id, WidgetIntents.RC_LAST_OPEN))
            return rv
        }
    }
}
