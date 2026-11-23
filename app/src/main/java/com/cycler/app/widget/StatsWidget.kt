package com.cycler.app.widget

import android.content.Context
import android.widget.RemoteViews
import com.cycler.app.R
import com.cycler.app.Tab
import com.cycler.app.ui.Fmt
import kotlin.math.roundToInt

/** A bar for each of the last 7 days, and this month's totals. */
class StatsWidget : BaseWidget() {
    companion object {
        fun build(ctx: Context, snap: WidgetSnapshot): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_stats)
            val days = WidgetData.lastSevenDays(snap.rides, snap.now)
            rv.setImageViewBitmap(R.id.stats_chart, WidgetRender.weekBars(days, WidgetData.weekdayInitials(snap.now), label = ctx.getColor(R.color.widget_muted)))
            val month = WidgetData.month(snap)
            rv.setTextViewText(R.id.stats_month_distance, ctx.getString(R.string.widget_km, Fmt.kmShort(month.distanceM)))
            rv.setTextViewText(R.id.stats_month_rides, ctx.resources.getQuantityString(R.plurals.widget_rides, month.rides, month.rides))
            rv.setTextViewText(R.id.stats_month_time, ctx.getString(R.string.widget_month_time, Fmt.dur(month.durationS)))
            rv.setTextViewText(R.id.stats_month_climb, ctx.getString(R.string.widget_month_climb, ctx.getString(R.string.widget_m, month.ascentM.roundToInt().toString())))
            rv.setOnClickPendingIntent(android.R.id.background, WidgetIntents.openTab(ctx, Tab.ACTIVITY, WidgetIntents.RC_STATS_OPEN))
            return rv
        }
    }
}
