package com.cycler.app.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.cycler.app.MainActivity
import com.cycler.app.Tab

/**
 * Every tap target on the widgets. Taps that change what the app shows (or start a ride) go to
 * [MainActivity] with an extra, handled by [WidgetLaunch]. Pause, resume and lap act on the running
 * service through [WidgetActionReceiver].
 *
 * All pending intents are immutable and use their own request code, so one never replaces another.
 */
object WidgetIntents {
    const val EXTRA_WIDGET_ACTION = "com.cycler.app.extra.WIDGET_ACTION"
    const val WIDGET_ACTION_START = "start"
    const val EXTRA_TAB = "com.cycler.app.extra.TAB"
    const val EXTRA_RIDE_ID = "com.cycler.app.extra.RIDE_ID"

    const val EXTRA_TOKEN = "com.cycler.app.extra.WIDGET_TOKEN"

    const val OPEN_ACTION = "com.cycler.app.widget.OPEN"
    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    // One code per widget and target.
    const val RC_RIDE_OPEN = 101
    const val RC_RIDE_START = 102
    const val RC_RIDE_PAUSE = 103
    const val RC_RIDE_RESUME = 104
    const val RC_RIDE_LAP = 105
    const val RC_WEEK_OPEN = 201
    const val RC_LAST_OPEN = 301
    const val RC_LAST_START = 302
    const val RC_STATS_OPEN = 401
    const val RC_FALLBACK = 901

    /** The request code of the tap, so the anonymous stats can tell which widget was used. */
    const val EXTRA_SOURCE = "com.cycler.app.extra.WIDGET_SOURCE"

    private fun activityIntent(ctx: Context, requestCode: Int) =
        Intent(ctx, MainActivity::class.java).setAction(OPEN_ACTION).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_TOKEN, WidgetAuth.token(ctx)).putExtra(EXTRA_SOURCE, requestCode)

    /** Which widget a request code belongs to, for the anonymous stats; null for the fallback view. */
    fun featureOf(requestCode: Int): com.cycler.app.analytics.Feature? = when (requestCode / 100) {
        1 -> com.cycler.app.analytics.Feature.WIDGET_RIDE
        2 -> com.cycler.app.analytics.Feature.WIDGET_WEEK
        3 -> com.cycler.app.analytics.Feature.WIDGET_LAST_RIDE
        4 -> com.cycler.app.analytics.Feature.WIDGET_STATS
        else -> null
    }

    fun openTab(ctx: Context, tab: Tab, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(ctx, requestCode, activityIntent(ctx, requestCode).putExtra(EXTRA_TAB, tab.name), FLAGS)

    fun openRide(ctx: Context, rideId: String, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(ctx, requestCode, activityIntent(ctx, requestCode).putExtra(EXTRA_RIDE_ID, rideId), FLAGS)

    /** Start a ride. Goes through the activity: a background service start would not be allowed to use location. */
    fun startRide(ctx: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            ctx, requestCode,
            activityIntent(ctx, requestCode).putExtra(EXTRA_TAB, Tab.RIDE.name).putExtra(EXTRA_WIDGET_ACTION, WIDGET_ACTION_START),
            FLAGS,
        )

    /** Pause, resume or lap ([com.cycler.app.track.TrackingService] action strings). */
    fun serviceAction(ctx: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(ctx, requestCode, Intent(ctx, WidgetActionReceiver::class.java).setAction(action), FLAGS)
}
