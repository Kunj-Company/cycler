package com.cycler.app.analytics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.runBlocking

/**
 * Debug builds only (this file is in src/debug): send the stats now, including the current week, with
 * no pauses. Only the shell (which holds DUMP) can send it:
 *
 *   adb shell am broadcast -a com.cycler.app.DEBUG_SEND_STATS -n com.cycler.app/.analytics.DebugStatsReceiver \
 *       [--es url http://10.0.2.2:8787]
 */
class DebugStatsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val url = intent.getStringExtra("url") ?: com.cycler.app.BuildConfig.STATS_URL
        val pending = goAsync()
        Thread {
            try {
                val logged = StatsSender.Http { u, json ->
                    val code = StatsSender.urlHttp.post(u, json)
                    Log.i(TAG, "POST $u -> $code $json")
                    code
                }
                val n = runBlocking {
                    StatsSender.run(app, url, logged, includeCurrent = true, maxWeeks = UsageStats.MAX_WEEKS + 1, pause = {})
                }
                Log.i(TAG, "sent $n week(s) to $url")
            } catch (e: Throwable) {
                Log.w(TAG, "send failed", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private companion object { const val TAG = "CyclerStats" }
}
