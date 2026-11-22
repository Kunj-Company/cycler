package com.cycler.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.cycler.app.R
import com.cycler.app.Tab
import com.cycler.app.data.Prefs
import com.cycler.app.data.RouteStore
import com.cycler.app.track.TrackingService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Rebuilds every widget on the home screen. Safe to call from anywhere, as often as you like: the work
 * is done on a background thread, calls that arrive while one is queued are merged, and nothing it does
 * can throw into the caller.
 */
object Widgets {
    private class Kind(val cls: Class<*>, val build: (Context, WidgetSnapshot, Int) -> RemoteViews)

    private val kinds = listOf(
        Kind(RideWidget::class.java) { c, s, w -> RideWidget.build(c, s, w) },
        Kind(WeekWidget::class.java) { c, s, _ -> WeekWidget.build(c, s) },
        Kind(LastRideWidget::class.java) { c, s, w -> LastRideWidget.build(c, s, w) },
        Kind(StatsWidget::class.java) { c, s, _ -> StatsWidget.build(c, s) },
    )

    private val queued = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "cycler-widgets").apply { isDaemon = true } }

    /** Ask for a refresh of all widgets; returns at once. */
    fun updateAll(context: Context) {
        val app = context.applicationContext ?: return
        if (!queued.compareAndSet(false, true)) return
        try {
            executor.execute {
                queued.set(false)
                updateAllNow(app)
            }
        } catch (e: Throwable) {
            queued.set(false)
        }
    }

    /** Run [block] on the widget thread; used by the providers, which finish their broadcast afterwards. */
    internal fun async(block: () -> Unit) {
        try { executor.execute { runCatching(block) } } catch (e: Throwable) { runCatching(block) }
    }

    /** Refresh all widgets now, on the calling thread. May read from disk if the ride list is not loaded yet. */
    fun updateAllNow(context: Context) {
        runCatching {
            val app = context.applicationContext ?: context
            val mgr = AppWidgetManager.getInstance(app)
            val placed = kinds.map { k -> k to runCatching { mgr.getAppWidgetIds(ComponentName(app, k.cls)) }.getOrDefault(IntArray(0)) }
                .filter { it.second.isNotEmpty() }
            if (placed.isEmpty()) return
            val snap = runCatching { snapshot(app) }.getOrNull()
            for ((kind, ids) in placed) for (id in ids) {
                val views = runCatching {
                    val opts = mgr.getAppWidgetOptions(id)
                    // The narrowest the widget gets, which is what the layout has to fit.
                    val width = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250) ?: 250
                    kind.build(app, snap ?: error("no data"), width)
                }.getOrElse { fallback(app) }
                runCatching { mgr.updateAppWidget(id, views) }
            }
        }
    }

    fun snapshot(context: Context): WidgetSnapshot =
        WidgetSnapshot.of(
            TrackingService.state.value, RouteStore.get(context).metas.value,
            Prefs(context).weeklyGoalKm.coerceAtLeast(1),
        )

    /** Shown if a widget could not be built: the app name, and a tap opens the app. */
    fun fallback(context: Context): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_loading).apply {
            setTextViewText(R.id.loading_text, context.getString(R.string.widget_open_cycler))
            setOnClickPendingIntent(android.R.id.background, WidgetIntents.openTab(context, Tab.RIDE, WidgetIntents.RC_FALLBACK))
        }
}

/** Shared by the four providers: any system update request rebuilds everything, off the main thread. */
abstract class BaseWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = refresh(context)

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle) =
        refresh(context)

    private fun refresh(context: Context) {
        val app = context.applicationContext ?: context
        val pending = runCatching { goAsync() }.getOrNull()
        Widgets.async {
            try { Widgets.updateAllNow(app) } finally { runCatching { pending?.finish() } }
        }
    }
}
