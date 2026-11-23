package com.cycler.app.share

import android.graphics.Bitmap
import android.util.LruCache
import java.io.File

/**
 * Small in-memory cache of decoded photos for the Rides feed, so a card that scrolls back into view
 * (or a refresh that did not change any file) does not decode its pictures again.
 * Keyed by path, size on screen, modified time and length, so a replaced file is never served stale.
 */
object ThumbCache {
    private const val MAX_BYTES = 24 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun key(f: File, side: Int) = "${f.path}|$side|${f.lastModified()}|${f.length()}"

    /** The cached bitmap, decoding (at most about twice [side] on the long edge) on a miss. */
    fun load(file: File, side: Int): Bitmap? {
        val k = key(file, side)
        cache.get(k)?.let { return it }
        return loadShareImage(file, side)?.also { cache.put(k, it) }
    }

    fun clear() = cache.evictAll()
}
