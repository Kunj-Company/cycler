package com.cycler.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import kotlin.math.max

/**
 * Bike pictures. A picked image is copied into the app's own storage as a JPEG of at most [MAX_SIDE] pixels,
 * one file per bike at bikes/<id>.jpg. They travel in the backup zip and go when their bike is deleted.
 */
object BikePhotos {
    const val MAX_SIDE = 1024
    private const val QUALITY = 85

    fun dir(ctx: Context) = File(ctx.filesDir, "bikes")

    /** The file for bike [id]'s picture, or null when the id could not safely be part of a path. */
    fun file(ctx: Context, id: String): File? = if (RouteStore.isSafeId(id)) File(dir(ctx), "$id.jpg") else null

    /** The size to scale a [w] by [h] image to so its longer side is at most [maxSide]; never enlarges. */
    fun scaledSize(w: Int, h: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        val longest = max(w, h)
        if (longest <= maxSide || w <= 0 || h <= 0) return w to h
        val k = maxSide.toDouble() / longest
        return Math.round(w * k).toInt().coerceAtLeast(1) to Math.round(h * k).toInt().coerceAtLeast(1)
    }

    /** Copy the picture at [uri] in as bike [id]'s photo, downscaled. False (nothing changed) if it could not be read. */
    fun import(ctx: Context, uri: Uri, id: String): Boolean {
        val target = file(ctx, id) ?: return false
        return runCatching {
            val resolver = ctx.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching false
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE * 2) sample *= 2
            val raw = resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: return@runCatching false
            val turn = runCatching {
                resolver.openInputStream(uri)!!.use {
                    when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                }
            }.getOrDefault(0f)
            val (w, h) = scaledSize(raw.width, raw.height)
            var bmp: Bitmap = if (w != raw.width || h != raw.height) Bitmap.createScaledBitmap(raw, w, h, true) else raw
            if (turn != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(turn) }, true)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            if (!tmp.renameTo(target)) { target.delete(); if (!tmp.renameTo(target)) { tmp.delete(); return@runCatching false } }
            true
        }.getOrDefault(false)
    }

    fun remove(ctx: Context, id: String) {
        file(ctx, id)?.delete()
    }

    /** Delete pictures of bikes that are not in [keep] (left over after a restore or a failed add). Returns how many went. */
    fun prune(ctx: Context, keep: Set<String>): Int {
        var n = 0
        dir(ctx).listFiles()?.forEach { f ->
            val id = f.name.removeSuffix(".jpg").removeSuffix(".jpg.tmp")
            if (f.isFile && (id !in keep || f.name.endsWith(".tmp")) && f.delete()) n++
        }
        return n
    }
}
