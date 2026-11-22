package com.cycler.app.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.core.content.FileProvider
import com.cycler.app.data.Pt
import com.cycler.app.ui.MapStyles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.snapshotter.MapSnapshotter
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

object VideoExport {
    const val W = 720
    const val H = 1280
    const val FPS = 30

    /**
     * Draws every frame of [scene] and encodes them to an MP4 at [out].
     * Blocking; run it off the main thread. [progress] gets 0..1, and returning
     * true from [cancelled] stops early (the file is then deleted).
     */
    fun export(scene: FlyoverScene, out: File, progress: (Float) -> Unit, cancelled: () -> Boolean): Boolean {
        out.parentFile?.mkdirs()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var muxer: MediaMuxer? = null
        var surface: android.view.Surface? = null
        var ok = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var started = false
            var written = 0L

            // Pull finished frames out of the encoder and into the file.
            fun drain(end: Boolean) {
                var idle = 0
                while (true) {
                    val i = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                    if (i == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!end || ++idle > 500) return
                    } else if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        started = true
                    } else if (i >= 0) {
                        idle = 0
                        val buf = codec.getOutputBuffer(i)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (buf != null && info.size > 0 && started) {
                            // Frames are drawn slower than real time, so stamp them at an even 30 a second.
                            info.presentationTimeUs = written * 1_000_000L / FPS
                            written++
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(i, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }

            val frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(frame)
            val total = (scene.durationMs * FPS / 1000).toInt()
            var stopped = false
            for (n in 0 until total) {
                if (cancelled()) { stopped = true; break }
                scene.draw(canvas, W, H, n * 1000L / FPS)
                val c = surface.lockHardwareCanvas()
                try {
                    c.drawBitmap(frame, 0f, 0f, null)
                } finally {
                    surface.unlockCanvasAndPost(c)
                }
                drain(false)
                progress((n + 1f) / total)
            }
            codec.signalEndOfInputStream()
            drain(true)
            ok = !stopped && started && written > 0
        } catch (e: Exception) {
            ok = false
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { surface?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
        if (!ok) out.delete()
        return ok
    }

    /**
     * A top-down picture of the map around [pts], to lay flat as the flyover's ground.
     * Null if the map can't be fetched in time (no signal, say); the flyover then uses a plain grid.
     */
    suspend fun mapGround(ctx: Context, styleKey: String, pts: List<Pt>): Ground? {
        if (pts.size < 2) return null
        val size = 1024
        val minLat = pts.minOf { it.lat }; val maxLat = pts.maxOf { it.lat }
        val minLon = pts.minOf { it.lon }; val maxLon = pts.maxOf { it.lon }
        val lat = (minLat + maxLat) / 2
        val lon = (minLon + maxLon) / 2
        val spanM = max((maxLat - minLat) * 110540.0, (maxLon - minLon) * 111320.0 * cos(Math.toRadians(lat))).coerceAtLeast(300.0)
        // Leave a wide margin so the ground still fills the view when the camera looks along it.
        val wanted = spanM * 2.6 / size
        val world = 40075016.686 * cos(Math.toRadians(lat))
        val zoom = (ln(world / (512.0 * wanted)) / ln(2.0)).coerceIn(2.0, 17.0)
        val mpp = world / (512.0 * 2.0.pow(zoom))
        return withTimeoutOrNull(20_000) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val snapper = runCatching {
                        MapSnapshotter(
                            ctx,
                            MapSnapshotter.Options(size, size)
                                .withPixelRatio(1f)
                                .withLogo(false)
                                .withStyleBuilder(MapStyles.builder(styleKey))
                                .withCameraPosition(CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoom).build()),
                        )
                    }.getOrNull()
                    if (snapper == null) {
                        cont.resume(null)
                    } else {
                        cont.invokeOnCancellation { runCatching { snapper.cancel() } }
                        runCatching {
                            snapper.start(
                                { snap -> if (cont.isActive) cont.resume(Ground(snap.bitmap, lat, lon, mpp)) },
                                { _ -> if (cont.isActive) cont.resume(null) },
                            )
                        }.onFailure { if (cont.isActive) cont.resume(null) }
                    }
                }
            }
        }
    }

    /** Hand files to the system share sheet. */
    fun share(ctx: Context, files: List<File>, mime: String, text: String) {
        val uris = ArrayList(files.map { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", it) })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.type = mime
        intent.putExtra(Intent.EXTRA_TEXT, text)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(intent, "Share ride").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
