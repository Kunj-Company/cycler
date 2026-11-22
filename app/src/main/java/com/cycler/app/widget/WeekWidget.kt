package com.cycler.app.widget

import android.content.Context
import android.widget.RemoteViews
import com.cycler.app.R
import com.cycler.app.Tab
import com.cycler.app.ui.Fmt

/** This week's distance against the goal, with the number of rides and the time. */
class WeekWidget : BaseWidget() {
    companion object {
        fun build(ctx: Context, snap: WidgetSnapshot): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_week)
            val week = WidgetData.week(snap)
            val km = week.distanceM / 1000
            val goal = snap.goalKm.coerceAtLeast(1)
            rv.setTextViewText(R.id.week_distance, ctx.getString(R.string.widget_km, Fmt.kmShort(week.distanceM)))
            rv.setTextViewText(
                R.id.week_goal,
                if (km >= goal) ctx.getString(R.string.widget_week_goal_reached, goal) else ctx.getString(R.string.widget_week_of_goal, goal),
            )
            rv.setProgressBar(R.id.week_progress, 1000, (km / goal * 1000).toInt().coerceIn(0, 1000), false)
            rv.setTextViewText(R.id.week_rides, ctx.resources.getQuantityString(R.plurals.widget_rides, week.rides, week.rides))
            rv.setTextViewText(R.id.week_time, Fmt.dur(week.durationS))
            rv.setOnClickPendingIntent(android.R.id.background, WidgetIntents.openTab(ctx, Tab.ACTIVITY, WidgetIntents.RC_WEEK_OPEN))
            return rv
        }
    }
}
