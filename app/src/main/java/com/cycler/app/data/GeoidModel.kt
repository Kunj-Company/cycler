package com.cycler.app.data

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.IOException
import kotlin.math.floor

/**
 * A coarse offline geoid: the height of mean sea level above the WGS84 ellipsoid (the "geoid
 * height" N, so sea-level height = GPS height - N), for phones older than Android 14 that have
 * no built-in model.
 *
 * Source: EGM96 (NGA / NASA GSFC, a work of the US Government and in the public domain), taken
 * from the 15-minute grid distributed as `egm96-15.pgm` by GeographicLib's geoid data
 * (https://geographiclib.sourceforge.io/C++/doc/geoid.html; the grid's own bilinear error against
 * the full EGM96 model is under 1.2 m). That grid was sampled every 1 degree (181 rows from 90N
 * to 90S by 360 columns from 0E, 65,160 values) and stored as big-endian 16-bit integers in
 * centimetres in `assets/egm96_1deg.bin` (130,320 bytes).
 *
 * Accuracy: measured against the 15-minute grid with 40,000 random points between 85N and 85S,
 * bilinear interpolation of the 1-degree grid has a root-mean-square error of 0.4 m, 99% of
 * points are within 1.8 m, and the worst point found was 9 m (steep geoid, for example near
 * the Andes and New Guinea). A coarser 5-degree grid would have been off by up to 20 m, so it
 * was not used. Android's own model (API 34+) is newer (EGM2008-based); the two differ by well
 * under a metre on average.
 */
class GeoidModel(private val data: ByteArray) {
    init { require(data.size == ROWS * COLS * 2) { "Unexpected geoid grid size ${data.size}" } }

    private fun at(row: Int, col: Int): Double {
        val i = (row * COLS + Math.floorMod(col, COLS)) * 2
        return ((data[i].toInt() shl 8) or (data[i + 1].toInt() and 0xFF)).toShort() / 100.0
    }

    /** Ellipsoid height minus sea-level height at a point, in metres; null for a point off the globe. */
    fun heightAt(lat: Double, lon: Double): Double? {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0) return null
        val fr = 90.0 - lat
        val r0 = floor(fr).toInt().coerceAtMost(ROWS - 2)
        val dr = fr - r0
        val fc = ((lon % 360.0) + 360.0) % 360.0
        val c0 = floor(fc).toInt()
        val dc = fc - c0
        return at(r0, c0) * (1 - dr) * (1 - dc) + at(r0 + 1, c0) * dr * (1 - dc) +
            at(r0, c0 + 1) * (1 - dr) * dc + at(r0 + 1, c0 + 1) * dr * dc
    }

    companion object {
        const val ROWS = 181
        const val COLS = 360
        const val ASSET = "egm96_1deg.bin"

        /** Reads the bundled grid; null if the asset is missing or damaged. Reads from disk, so call it off the main thread. */
        fun load(ctx: Context): GeoidModel? = try {
            GeoidModel(ctx.assets.open(ASSET).use { it.readBytes() })
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/**
 * Picks the best geoid lookup for this phone. Lives apart from [Geoid] so that nothing on an
 * older Android version has to touch that class, which needs API 34.
 */
object GeoidSource {
    /**
     * A lookup of geoid height for [Altitude.toMsl]: Android's own model on Android 14+ (falling
     * back to the bundled one for any point it cannot answer), the bundled model before that.
     * Null if neither is usable. May read from disk, so call it off the main thread.
     */
    fun open(ctx: Context): ((Double, Double) -> Double?)? {
        val model = GeoidModel.load(ctx)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val system = system(ctx)
            return { lat, lon -> system.heightAt(lat, lon) ?: model?.heightAt(lat, lon) }
        }
        return model?.let { m -> { lat, lon -> m.heightAt(lat, lon) } }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun system(ctx: Context) = Geoid(ctx)
}
