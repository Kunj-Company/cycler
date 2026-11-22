package com.cycler.app.track

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.cycler.app.MainActivity
import com.cycler.app.data.Geo
import com.cycler.app.data.Pt
import com.cycler.app.data.RouteStore
import com.cycler.app.data.RouteType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

enum class TrackStatus { IDLE, RECORDING, PAUSED }

data class TrackState(
    val status: TrackStatus = TrackStatus.IDLE,
    val points: List<Pt> = emptyList(),
    val distanceM: Double = 0.0,
    val movingMs: Long = 0,
    /** Recording time accumulated before the last resume. */
    val activeMs: Long = 0,
    val resumedAt: Long = 0,
    val speedMs: Float = 0f,
    /** Set once a ride has been saved, so the UI can open it. */
    val savedId: String? = null,
) {
    fun elapsedMs(now: Long) = activeMs + if (status == TrackStatus.RECORDING) (now - resumedAt).coerceAtLeast(0) else 0
}

/**
 * Records a ride with GPS while the screen is off or the app is in the
 * background. Runs as a foreground service with a notification.
 */
class TrackingService : Service(), LocationListener {

    private lateinit var lm: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastFix: Location? = null
    private var skipNextSegment = false
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> finish(intent.getStringExtra(EXTRA_NAME), save = true)
            ACTION_DISCARD -> finish(null, save = false)
            else -> if (state.value.status == TrackStatus.IDLE) stopSelf()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission", "WakelockTimeout")
    private fun begin() {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            // Always go foreground first: Android requires it after startForegroundService().
            ServiceCompat.startForeground(this, NOTIF_ID, notification("Waiting for GPS…"), type)
            if (state.value.status != TrackStatus.IDLE) return
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        } catch (e: Exception) {
            // No location permission or GPS hardware: nothing to record.
            stopSelf()
            return
        }
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cycler:record").apply { acquire() }
        RouteStore.get(this).recordingFile.delete()
        lastFix = null
        skipNextSegment = false
        state.value = TrackState(status = TrackStatus.RECORDING, resumedAt = System.currentTimeMillis())
    }

    private fun pause() {
        val now = System.currentTimeMillis()
        state.update {
            if (it.status != TrackStatus.RECORDING) it
            else it.copy(status = TrackStatus.PAUSED, activeMs = it.elapsedMs(now), speedMs = 0f)
        }
        notify("Paused")
    }

    private fun resume() {
        lastFix = null
        skipNextSegment = true
        state.update {
            if (it.status != TrackStatus.PAUSED) it
            else it.copy(status = TrackStatus.RECORDING, resumedAt = System.currentTimeMillis())
        }
    }

    private fun finish(name: String?, save: Boolean) {
        val s = state.value
        lm.removeUpdates(this)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        var id: String? = null
        val store = RouteStore.get(this)
        if (save && s.points.size >= 2) {
            id = store.save(
                name = name ?: "Ride",
                type = RouteType.RECORDED,
                pts = s.points,
                durationS = s.movingMs / 1000,
                distanceM = s.distanceM,
                ascentM = Geo.ascent(s.points, 4.0, smooth = true),
                createdAt = s.points.first().t,
            )
        }
        store.recordingFile.delete()
        state.value = TrackState(savedId = id)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onLocationChanged(loc: Location) {
        val s = state.value
        if (s.status != TrackStatus.RECORDING) return
        if (loc.hasAccuracy() && loc.accuracy > 25f) return

        val prev = lastFix
        lastFix = loc
        var movingAdd = 0L
        var speed = if (loc.hasSpeed()) loc.speed else 0f
        if (prev != null) {
            val dt = loc.time - prev.time
            if (!loc.hasSpeed() && dt > 0) speed = prev.distanceTo(loc) / (dt / 1000f)
            if (speed > 0.7f && dt in 1..10000) movingAdd = dt
        }

        val pt = Pt(loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else Double.NaN, loc.time)
        val last = s.points.lastOrNull()
        val gap = if (last == null) 0.0 else Geo.dist(last, pt)
        // Only keep a new point after moving a few metres, so standing still adds no fake distance.
        val keep = last == null || gap >= 4.0
        val addDist = if (keep && !skipNextSegment) gap else 0.0
        if (keep) {
            skipNextSegment = false
            runCatching {
                RouteStore.get(this).recordingFile.appendText(
                    String.format(Locale.US, "%.6f,%.6f,%.1f,%d\n", pt.lat, pt.lon, pt.ele, pt.t)
                )
            }
        }
        state.update {
            it.copy(
                points = if (keep) it.points + pt else it.points,
                distanceM = it.distanceM + addDist,
                movingMs = it.movingMs + movingAdd,
                speedMs = speed,
            )
        }
        val now = System.currentTimeMillis()
        if (now - lastNotify > 5000) {
            lastNotify = now
            notify(String.format(Locale.US, "%.2f km · %.1f km/h", state.value.distanceM / 1000, speed * 3.6f))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onDestroy() {
        runCatching { lm.removeUpdates(this) }
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun notify(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Ride recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Cycler is recording your ride")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        const val ACTION_START = "com.cycler.app.START"
        const val ACTION_PAUSE = "com.cycler.app.PAUSE"
        const val ACTION_RESUME = "com.cycler.app.RESUME"
        const val ACTION_STOP = "com.cycler.app.STOP"
        const val ACTION_DISCARD = "com.cycler.app.DISCARD"
        const val EXTRA_NAME = "name"
        private const val CHANNEL = "ride"
        private const val NOTIF_ID = 1

        val state = MutableStateFlow(TrackState())

        fun send(ctx: Context, action: String, name: String? = null) {
            val i = Intent(ctx, TrackingService::class.java).setAction(action)
            if (name != null) i.putExtra(EXTRA_NAME, name)
            if (action == ACTION_START) ContextCompat.startForegroundService(ctx, i) else ctx.startService(i)
        }
    }
}

/** Live position for the map's "you are here" dot. Needs location permission. */
@SuppressLint("MissingPermission")
fun locationFlow(ctx: Context): Flow<Pt> = callbackFlow {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    var lastGps = 0L
    fun emit(l: Location) {
        val now = System.currentTimeMillis()
        if (l.provider == LocationManager.GPS_PROVIDER) lastGps = now
        else if (now - lastGps < 15000) return // prefer GPS while it is fresh
        trySend(Pt(l.latitude, l.longitude, if (l.hasAltitude()) l.altitude else Double.NaN, l.time))
    }
    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = emit(location)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }
    val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .filter { runCatching { lm.allProviders.contains(it) }.getOrDefault(false) }
    try {
        providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            ?.let { trySend(Pt(it.latitude, it.longitude, Double.NaN, it.time)) }
        for (p in providers) lm.requestLocationUpdates(p, 2000L, 2f, listener, Looper.getMainLooper())
    } catch (e: SecurityException) {
        // permission was revoked; no updates
    }
    awaitClose { lm.removeUpdates(listener) }
}
