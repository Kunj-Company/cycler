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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.media.AudioAttributes
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.cycler.app.MainActivity
import com.cycler.app.data.Geo
import com.cycler.app.data.LapMark
import com.cycler.app.data.Workout
import com.cycler.app.data.Prefs
import com.cycler.app.data.Pt
import com.cycler.app.data.RouteStore
import com.cycler.app.data.RouteType
import com.cycler.app.data.Turn
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
    val maxSpeedMs: Float = 0f,
    /** Standing still while recording; the moving clock is stopped. */
    val autoPaused: Boolean = false,
    /** Metres away from the route being followed, or null when not following one. */
    val guideOffM: Double? = null,
    /** How far along the followed route the rider is, metres. */
    val guideDoneM: Double = 0.0,
    val offRoute: Boolean = false,
    /** Length of the followed route, measured the same way as [guideDoneM]. */
    val guideTotalM: Double = 0.0,
    /** The next turn on the followed route, e.g. "Turn left", and how far away it is. */
    val nextTurn: String? = null,
    val nextTurnLeft: Boolean = false,
    val nextTurnM: Double = 0.0,
    /** Times the rider has come to a stop so far. */
    val stops: Int = 0,
    val laps: List<LapMark> = emptyList(),
    /** Which step of the chosen workout is running; -1 when there is no workout or it has finished. */
    val workoutStep: Int = -1,
    val workoutLeftS: Int = 0,
    /** Location is switched off on the phone, so nothing can be recorded. */
    val gpsOff: Boolean = false,
    /** A one-off message for the rider, e.g. why a ride could not start. */
    val notice: String? = null,
    /** Set once a ride has been saved, so the UI can open it. */
    val savedId: String? = null,
) {
    /** Distance and moving time since the last lap mark. */
    val lapDistanceM: Double get() = distanceM - (laps.lastOrNull()?.distanceM ?: 0.0)
    val lapMovingMs: Long get() = movingMs - (laps.lastOrNull()?.movingMs ?: 0L)

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
    private var stillSince = 0L
    private var autoPauseOn = true
    private var offRouteAlertOn = true
    private var offCount = 0
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastSpokenKm = 0
    /** Off-route alerts only start once the rider has actually been on the route. */
    private var onRouteSeen = false
    private var lastCued: Turn? = null
    private var turnCuesOn = true
    private var voiceEveryKm = 1
    private var voiceKmOn = false
    private var pauseAfterMs = 4000L
    private var autoLapM = 0.0
    private val ticker = Handler(Looper.getMainLooper())
    private var lastWorkoutStep = -2
    /** Runs once a second while a workout is on, so blocks change on time even without a GPS fix. */
    private val tick = object : Runnable {
        override fun run() {
            val s = state.value
            val w = workout
            if (s.status == TrackStatus.IDLE || w == null) return
            if (s.status == TrackStatus.RECORDING) {
                val pos = w.at(s.elapsedMs(System.currentTimeMillis()) / 1000)
                val step = pos?.first ?: -1
                if (step != lastWorkoutStep) {
                    if (lastWorkoutStep != -2 || step == 0) announceStep(w, step)
                    lastWorkoutStep = step
                }
                if (pos != null && pos.second == 3) buzz(longArrayOf(0, 60))
                state.update { it.copy(workoutStep = step, workoutLeftS = pos?.second ?: 0) }
                if (pos == null) return
            }
            ticker.postDelayed(this, 1000)
        }
    }

    private fun announceStep(w: Workout, step: Int) {
        val text = if (step < 0) "Workout complete. Nice work." else w.steps[step].let { st ->
            val len = if (st.seconds >= 60) "${st.seconds / 60} minute${if (st.seconds >= 120) "s" else ""}" +
                (if (st.seconds % 60 != 0) " ${st.seconds % 60}" else "") else "${st.seconds} seconds"
            "${st.label}, $len"
        }
        buzz(if (step >= 0 && w.steps[step].effort >= 2) longArrayOf(0, 250, 120, 250) else longArrayOf(0, 400))
        if (ttsReady) runCatching { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "workout") }
    }

    private fun markLap(auto: Boolean) {
        val s = state.value
        if (s.status == TrackStatus.IDLE || s.lapDistanceM < 20.0) return
        val mark = LapMark(s.distanceM, s.movingMs, s.elapsedMs(System.currentTimeMillis()))
        val n = s.laps.size + 1
        val text = String.format(
            Locale.US, "Lap %d: %.2f km in %d:%02d", n, s.lapDistanceM / 1000, s.lapMovingMs / 60000, s.lapMovingMs / 1000 % 60,
        )
        state.update { it.copy(laps = it.laps + mark, notice = text) }
        buzz(longArrayOf(0, 120))
        if (auto && ttsReady) runCatching { tts?.speak("Lap $n", TextToSpeech.QUEUE_ADD, null, "lap") }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin()
            // The other actions only make sense while a ride is running.
            else -> if (state.value.status == TrackStatus.IDLE) stopSelf() else when (intent?.action) {
                ACTION_LAP -> markLap(auto = false)
                ACTION_PAUSE -> pause()
                ACTION_RESUME -> resume()
                ACTION_STOP -> finish(intent.getStringExtra(EXTRA_NAME), save = true)
                ACTION_DISCARD -> finish(null, save = false)
            }
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
            // No precise-location permission or no GPS: nothing to record. Tell the rider why.
            state.update { it.copy(notice = "Couldn't start: Cycler needs precise location. Check the app's location permission.") }
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return
        }
        acquireWakeLock()
        RouteStore.get(this).recordingFile.delete()
        onRouteSeen = false
        lastCued = null
        lastFix = null
        skipNextSegment = false
        stillSince = 0L
        offCount = 0
        lastSpokenKm = 0
        val prefs = Prefs(this)
        autoPauseOn = prefs.autoPause
        offRouteAlertOn = prefs.offRouteAlert
        turnCuesOn = prefs.turnCues
        voiceEveryKm = prefs.voiceEveryKm.coerceAtLeast(1)
        voiceKmOn = prefs.voice
        pauseAfterMs = when (prefs.autoPauseLevel) { 0 -> 8000L; 2 -> 2500L; else -> 4000L }
        autoLapM = prefs.autoLapKm * 1000.0
        lastWorkoutStep = -2
        // A workout needs its prompts spoken even if kilometre updates are off.
        if (prefs.voice || workout != null) {
            tts = TextToSpeech(applicationContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS &&
                    runCatching { (tts?.setLanguage(Locale.US) ?: -2) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)
            }
        }
        state.value = TrackState(
            status = TrackStatus.RECORDING, resumedAt = System.currentTimeMillis(),
            guideTotalM = guideCum?.lastOrNull() ?: 0.0,
            gpsOff = !runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(true),
            workoutStep = if (workout != null) 0 else -1, workoutLeftS = workout?.steps?.firstOrNull()?.seconds ?: 0,
        )
        ticker.removeCallbacks(tick)
        if (workout != null) ticker.postDelayed(tick, 1000)
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cycler:record").apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun pause() {
        val now = System.currentTimeMillis()
        state.update {
            if (it.status != TrackStatus.RECORDING) it
            else it.copy(status = TrackStatus.PAUSED, activeMs = it.elapsedMs(now), speedMs = 0f, autoPaused = false)
        }
        // No GPS or wake lock while paused: a long cafe stop shouldn't drain the battery.
        runCatching { lm.removeUpdates(this) }
        releaseWakeLock()
        notify("Paused")
    }

    @SuppressLint("MissingPermission")
    private fun resume() {
        if (state.value.status != TrackStatus.PAUSED) return
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        }
        acquireWakeLock()
        ticker.removeCallbacks(tick)
        if (workout != null) ticker.postDelayed(tick, 1000)
        lastFix = null
        skipNextSegment = true
        stillSince = 0L
        state.update {
            if (it.status != TrackStatus.PAUSED) it
            else it.copy(status = TrackStatus.RECORDING, resumedAt = System.currentTimeMillis())
        }
    }

    private fun finish(name: String?, save: Boolean) {
        val s = state.value
        runCatching { lm.removeUpdates(this) }
        releaseWakeLock()
        ticker.removeCallbacks(tick)
        workout = null
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
        var id: String? = null
        var notice: String? = null
        val store = RouteStore.get(this)
        if (save && s.points.size < 2) notice = "Too short to save: no distance was recorded."
        if (save && s.points.size >= 2) try {
            id = store.save(
                name = name ?: "Ride",
                type = RouteType.RECORDED,
                pts = s.points,
                durationS = s.movingMs / 1000,
                distanceM = s.distanceM,
                ascentM = Geo.ascent(s.points, 4.0, smooth = true),
                createdAt = s.points.first().t,
                maxSpeedMs = s.maxSpeedMs.toDouble(),
                elapsedS = s.elapsedMs(System.currentTimeMillis()) / 1000,
                stops = s.stops,
                // Whatever was ridden after the last mark is the final lap.
                laps = if (s.laps.isEmpty()) emptyList() else if (s.lapDistanceM > 20.0)
                    s.laps + LapMark(s.distanceM, s.movingMs, s.elapsedMs(System.currentTimeMillis())) else s.laps,
            )
        } catch (e: Exception) {
            // Keep the raw recording so the ride can be recovered on the next launch.
            notice = "Couldn't save the ride just now. It will be recovered next time you open Cycler."
        }
        if (id != null || !save || s.points.size < 2) store.recordingFile.delete()
        state.value = TrackState(savedId = id, notice = notice)
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
        // Only keep a new point after really moving: GPS wanders several metres while you
        // stand at a signal, and that must not count as distance.
        val keep = last == null || (gap >= 4.0 && speed >= 0.5f) || gap >= 15.0
        val addDist = if (keep && !skipNextSegment) gap else 0.0
        if (keep) {
            skipNextSegment = false
            runCatching {
                RouteStore.get(this).recordingFile.appendText(
                    String.format(Locale.US, "%.6f,%.6f,%.1f,%d\n", pt.lat, pt.lon, pt.ele, pt.t)
                )
            }
        }
        // Auto-pause: a few seconds below walking pace counts as stopped.
        if (speed < 0.8f) {
            if (stillSince == 0L) stillSince = loc.time
        } else stillSince = 0L
        // At a known traffic signal a stop is almost certainly a real one, so pause sooner.
        val atSignal = signals.any { Geo.dist(it, pt) < 45.0 }
        val paused = autoPauseOn && stillSince != 0L && loc.time - stillSince >= (if (atSignal) minOf(1500L, pauseAfterMs) else pauseAfterMs)
        val newStop = paused && !s.autoPaused

        // Following a route: how far off it are we, and how far along?
        var off: Double? = null
        var done = s.guideDoneM
        var offRoute = s.offRoute
        var nextTurn: Turn? = null
        val g = guide
        val gc = guideCum
        if (g != null && gc != null && g.size >= 2) {
            val (d, along, _) = Geo.nearestOnRoute(pt, g, gc, done)
            off = d
            if (d <= 60.0) {
                done = along
                nextTurn = guideTurns.firstOrNull { it.alongM > along + 5.0 }
            }
            if (d < 30.0) onRouteSeen = true
            if (d > 50.0) offCount++ else if (d < 30.0) offCount = 0
            // Riding to the start of a route is not "off route".
            val nowOff = onRouteSeen && offCount >= 5
            if (nowOff && !offRoute && offRouteAlertOn) buzz(longArrayOf(0, 350, 150, 350, 150, 350))
            if (!nowOff && offRoute && offRouteAlertOn) buzz(longArrayOf(0, 120))
            offRoute = nowOff
        } else {
            offRoute = false
            offCount = 0
        }

        state.update {
            it.copy(
                points = if (keep) it.points + pt else it.points,
                distanceM = it.distanceM + addDist,
                movingMs = it.movingMs + movingAdd,
                speedMs = if (paused) 0f else speed,
                maxSpeedMs = if (speedTrusted(loc) && speed < 28f) maxOf(it.maxSpeedMs, speed) else it.maxSpeedMs,
                gpsOff = false,
                autoPaused = paused,
                guideOffM = off,
                guideDoneM = done,
                offRoute = offRoute,
                nextTurn = nextTurn?.label,
                nextTurnLeft = nextTurn?.left ?: false,
                nextTurnM = nextTurn?.let { it.alongM - done } ?: 0.0,
                stops = it.stops + if (newStop) 1 else 0,
            )
        }

        // A nudge shortly before each turn.
        val turn = nextTurn
        if (turn != null && turn !== lastCued && turn.alongM - done < 120.0 && !offRoute) {
            lastCued = turn
            if (turnCuesOn) buzz(longArrayOf(0, 90, 90, 90))
            if (ttsReady && turnCuesOn) {
                val m = (((turn.alongM - done) / 10).toInt() * 10).coerceAtLeast(10)
                runCatching { tts?.speak("${turn.label} in $m metres", TextToSpeech.QUEUE_ADD, null, "turn") }
            }
        }

        if (autoLapM > 0 && state.value.lapDistanceM >= autoLapM) markLap(auto = true)

        // Spoken update at every whole kilometre, if switched on.
        val st = state.value
        val km = (st.distanceM / 1000).toInt()
        if (km > lastSpokenKm) {
            lastSpokenKm = km
            if (ttsReady && voiceKmOn && st.movingMs > 0 && km % voiceEveryKm == 0) {
                val avg = st.distanceM / (st.movingMs / 1000.0) * 3.6
                val text = String.format(Locale.US, "%d kilometres. Average %.0f kilometres per hour.", km, avg)
                runCatching { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "km$km") }
            }
        }
        val now = System.currentTimeMillis()
        if (now - lastNotify > 5000) {
            lastNotify = now
            notify(String.format(Locale.US, "%.2f km · %.1f km/h", state.value.distanceM / 1000, speed * 3.6f))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) state.update { it.copy(gpsOff = false) }
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER && state.value.status != TrackStatus.IDLE) {
            state.update { it.copy(gpsOff = true) }
            notify("Location is off. Turn it on to keep recording.")
        }
    }

    private fun speedTrusted(loc: Location): Boolean =
        loc.hasSpeed() && (!loc.hasSpeedAccuracy() || loc.speedAccuracyMetersPerSecond < 1.5f)

    override fun onDestroy() {
        runCatching { lm.removeUpdates(this) }
        releaseWakeLock()
        ticker.removeCallbacks(tick)
        runCatching { tts?.shutdown() }
        if (state.value.status != TrackStatus.IDLE) {
            // The system ended the service mid-ride. Save what was recorded instead of leaving the UI frozen.
            val id = runCatching { RouteStore.get(this).recoverUnfinished() }.getOrNull()
            state.value = TrackState(savedId = id, notice = "Recording stopped unexpectedly. Your ride so far was saved.")
        }
        super.onDestroy()
    }

    private fun buzz(pattern: LongArray) {
        runCatching {
            @Suppress("DEPRECATION")
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val effect = VibrationEffect.createWaveform(pattern, -1)
            // Marked as an alarm so it still fires with the screen off.
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            }
        }
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
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        const val ACTION_START = "com.cycler.app.START"
        const val ACTION_LAP = "com.cycler.app.LAP"
        const val ACTION_PAUSE = "com.cycler.app.PAUSE"
        const val ACTION_RESUME = "com.cycler.app.RESUME"
        const val ACTION_STOP = "com.cycler.app.STOP"
        const val ACTION_DISCARD = "com.cycler.app.DISCARD"
        const val EXTRA_NAME = "name"
        private const val CHANNEL = "ride"
        private const val NOTIF_ID = 1

        val state = MutableStateFlow(TrackState())

        /** The route being followed, if any. Set by the UI; read on every GPS fix. */
        @Volatile var guide: List<Pt>? = null
            private set
        @Volatile private var guideCum: DoubleArray? = null

        /** The interval workout to run during the next ride, chosen before pressing Start. */
        @Volatile var workout: Workout? = null
        @Volatile private var guideTurns: List<Turn> = emptyList()
        /** Traffic signals near the followed route; a stop next to one pauses the clock sooner. */
        @Volatile var signals: List<Pt> = emptyList()

        fun setGuide(pts: List<Pt>?) {
            val cum = pts?.let { Geo.cumulative(it) }
            guideTurns = if (pts != null && cum != null) Geo.turns(pts, cum) else emptyList()
            if (pts == null) signals = emptyList()
            guideCum = cum
            guide = pts
            state.update {
                it.copy(
                    guideOffM = null, guideDoneM = 0.0, offRoute = false, nextTurn = null,
                    guideTotalM = guideCum?.lastOrNull() ?: 0.0,
                )
            }
        }

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
            ?.takeIf { System.currentTimeMillis() - it.time < 10 * 60_000 }
            ?.let { trySend(Pt(it.latitude, it.longitude, Double.NaN, it.time)) }
        for (p in providers) lm.requestLocationUpdates(p, 2000L, 2f, listener, Looper.getMainLooper())
    } catch (e: SecurityException) {
        // permission was revoked; no updates
    }
    awaitClose { lm.removeUpdates(listener) }
}
