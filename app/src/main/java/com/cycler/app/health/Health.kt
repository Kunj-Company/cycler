package com.cycler.app.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant

/**
 * Reads heart rate and weight from Health Connect, Android's shared health store.
 * Samsung Health, Fitbit, Garmin and others can write there, so a watch worn on a
 * ride gives that ride heart-rate numbers without Cycler talking to the watch itself.
 */
object Health {
    val permissions = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
    )

    /** Health Connect exists on this phone and is up to date. */
    fun available(ctx: Context): Boolean =
        runCatching { HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE }.getOrDefault(false)

    suspend fun connected(ctx: Context): Boolean = runCatching {
        available(ctx) && HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions().containsAll(permissions)
    }.getOrDefault(false)

    /** Heart-rate samples between two moments, oldest first, as (epoch millis, beats per minute). */
    suspend fun heartRate(ctx: Context, fromMs: Long, toMs: Long): List<Pair<Long, Int>> = runCatching {
        val client = HealthConnectClient.getOrCreate(ctx)
        val out = ArrayList<Pair<Long, Int>>()
        var token: String? = null
        do {
            val page = client.readRecords(
                ReadRecordsRequest(
                    HeartRateRecord::class,
                    TimeRangeFilter.between(Instant.ofEpochMilli(fromMs), Instant.ofEpochMilli(toMs)),
                    pageToken = token,
                )
            )
            for (rec in page.records) for (s in rec.samples) {
                val t = s.time.toEpochMilli()
                if (t in fromMs..toMs) out.add(t to s.beatsPerMinute.toInt())
            }
            token = page.pageToken
        } while (token != null)
        out.sortedBy { it.first }
    }.getOrDefault(emptyList())

    /** The most recent weight in the last two years, in whole kilograms, or null. */
    suspend fun latestWeightKg(ctx: Context): Int? = runCatching {
        val client = HealthConnectClient.getOrCreate(ctx)
        val page = client.readRecords(
            ReadRecordsRequest(
                WeightRecord::class,
                TimeRangeFilter.after(Instant.now().minusSeconds(2L * 365 * 24 * 3600)),
                ascendingOrder = false, pageSize = 1,
            )
        )
        page.records.firstOrNull()?.weight?.inKilograms?.let { Math.round(it).toInt() }
    }.getOrNull()
}
