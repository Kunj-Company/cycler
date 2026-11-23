package com.cycler.app.data

import android.content.Context
import android.location.Location
import android.location.altitude.AltitudeConverter
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Geoid height from Android's built-in model (Android 14 and newer), for [Altitude.toMsl].
 * The lookup reads model data from disk, so it must run off the main thread.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class Geoid(private val ctx: Context) {
    private val converter = AltitudeConverter()

    /** Ellipsoid height minus sea-level height at a point, in metres; null if unavailable. */
    fun heightAt(lat: Double, lon: Double): Double? = runCatching {
        val l = Location("geoid").apply {
            latitude = lat
            longitude = lon
            altitude = 0.0
        }
        converter.addMslAltitudeToLocation(ctx, l)
        if (l.hasMslAltitude()) -l.mslAltitudeMeters else null
    }.getOrNull()

    companion object {
        val available: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }
}
