package com.cycler.app.analytics

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cycler.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Sends the completed weeks of [UsageStats]: for each week the area report first, then, after a random pause, the install report. */
object StatsSender {
    /** Posts [json] to [url]; returns the HTTP status, or -1 when the server could not be reached. */
    fun interface Http {
        fun post(url: String, json: String): Int
    }

    /** Plain HttpURLConnection. No identifying headers: a fixed user agent, and a fresh connection per request. */
    val urlHttp = Http { url, json ->
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.useCaches = false
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.setRequestProperty("Content-Type", "application/json")
                // The default user agent names the phone model; send nothing but the app name.
                conn.setRequestProperty("User-Agent", "Cycler")
                // No connection reuse between the area and the install report.
                conn.setRequestProperty("Connection", "close")
                val bytes = json.toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
                val code = conn.responseCode
                runCatching { (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() } }
                code
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(-1)
    }

    /** A random 5 to 60 second pause between two requests. */
    val randomPause: suspend () -> Unit = { delay(Random.nextLong(5_000, 60_000)) }

    private fun allowed(ctx: Context) = UsageStats.isEnabled(ctx) && UsageStats.noticeSeen(ctx)

    /**
     * Send what is waiting, oldest week first, at most [maxWeeks] weeks per run. 204 = stored, 400 = the
     * server will never take it (dropped, no retry), anything else stops the run and it is tried again next
     * time. Returns how many weeks were finished with. [includeCurrent] is for the debug hook only.
     */
    suspend fun run(
        ctx: Context,
        baseUrl: String = BuildConfig.STATS_URL,
        http: Http = urlHttp,
        includeCurrent: Boolean = false,
        maxWeeks: Int = 3,
        pause: suspend () -> Unit = randomPause,
    ): Int = withContext(Dispatchers.IO) { send(ctx, baseUrl, http, includeCurrent, maxWeeks, pause) }

    private suspend fun send(
        ctx: Context, baseUrl: String, http: Http, includeCurrent: Boolean, maxWeeks: Int, pause: suspend () -> Unit,
    ): Int {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty() || !allowed(ctx)) return 0
        val version = UsageStats.appVersion
        var done = 0
        var first = true
        for (week in UsageStats.pendingWeeks(ctx, includeCurrent).take(maxWeeks)) {
            val w = UsageStats.weekData(ctx, week) ?: continue
            if (!w.optBoolean("areaDone", false)) {
                val reportId = UsageStats.areaReportId(ctx, week) ?: continue
                val area = UsageStats.buildArea(reportId, version, week, w)
                if (area != null) {
                    if (!first) pause()
                    first = false
                    if (!allowed(ctx)) return done
                    val code = http.post("$base/v1/area", area.toString())
                    if (code != 204 && code != 400) return done
                }
                UsageStats.markAreaDone(ctx, week)
            }
            if (!first) pause()
            first = false
            if (!allowed(ctx)) return done
            val report = UsageStats.buildReport(UsageStats.installId(ctx), version, week, w)
            val code = http.post("$base/v1/report", report.toString())
            if (code != 204 && code != 400) return done
            UsageStats.removeWeek(ctx, week)
            done++
        }
        return done
    }
}

class StatsWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { StatsSender.run(applicationContext) }
        // Failures are retried by the next daily run.
        return Result.success()
    }
}

/** The daily job: only with a network connection and not on low battery. */
object StatsWork {
    private const val NAME = "cycler-stats"
    @Volatile private var scheduled = false

    fun schedule(ctx: Context) {
        runCatching {
            if (BuildConfig.STATS_URL.isBlank() || !UsageStats.isEnabled(ctx) || !UsageStats.noticeSeen(ctx)) return
            if (scheduled) return
            val req = PeriodicWorkRequestBuilder<StatsWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build(),
                )
                .setInitialDelay(Random.nextLong(30, 180), TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx.applicationContext).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
            scheduled = true
        }
    }

    fun cancel(ctx: Context) {
        runCatching {
            scheduled = false
            WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork(NAME)
        }
    }
}
