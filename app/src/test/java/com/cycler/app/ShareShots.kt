package com.cycler.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.core.app.ApplicationProvider
import com.cycler.app.data.Geo
import com.cycler.app.data.Pt
import com.cycler.app.data.RouteMeta
import com.cycler.app.data.RouteType
import com.cycler.app.share.FlyoverScene
import com.cycler.app.share.Ground
import com.cycler.app.share.ShareCard
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** Renders the share card and frames of the flyover video to app/build/shots/share_*.png. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class ShareShots {
    private val home = Pt(12.9716, 77.5946)
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    private fun loop(km: Double): List<Pt> {
        val r = km * 1000 / (2 * Math.PI) / 1.15
        val centre = Geo.destination(home.lat, home.lon, 40.0, r)
        return (0..360).map { i ->
            val wob = 1 + 0.2 * sin(i / 28.0) + 0.09 * cos(i / 9.0)
            val p = Geo.destination(centre.lat, centre.lon, i + 220.0, r * wob)
            Pt(p.lat, p.lon, 900 + 60 * sin(i / 40.0) + 25 * cos(i / 13.0), 1_700_000_000_000L + i * 20_000L)
        }
    }

    private val pts = loop(32.0)
    private val meta = RouteMeta(
        "x", "Nandi foothills at sunrise", RouteType.RECORDED, 1_795_000_000_000L, 32_400.0, 412.0, 5640,
        home.lat, home.lon, 13.1, 6300,
    )

    private fun fakePhoto(a: Int, b: Int): Bitmap {
        val bmp = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 900f, 1200f, a, b, Shader.TileMode.CLAMP) })
        return bmp
    }

    /** A checkerboard standing in for the map image, so perspective and alignment are visible. */
    private fun fakeGround(): Ground {
        val size = 1024
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.parseColor("#E9ECE6"))
        val p = Paint().apply { color = Color.parseColor("#D3D9CC") }
        for (y in 0 until 16) for (x in 0 until 16) if ((x + y) % 2 == 0) c.drawRect(x * 64f, y * 64f, x * 64f + 64, y * 64f + 64, p)
        val lat0 = (pts.minOf { it.lat } + pts.maxOf { it.lat }) / 2
        val lon0 = (pts.minOf { it.lon } + pts.maxOf { it.lon }) / 2
        return Ground(bmp, lat0, lon0, 30.0)
    }

    private fun save(bmp: Bitmap, name: String) {
        val f = File("build/shots/$name.png")
        f.parentFile?.mkdirs()
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun card_plain() = save(ShareCard.render(ctx, meta, pts, emptyList(), dark = false), "share_card_plain")

    @Test fun card_photos_dark() = save(
        ShareCard.render(
            ctx, meta, pts,
            listOf(fakePhoto(0xFF2B5876.toInt(), 0xFF4E4376.toInt()), fakePhoto(0xFFF7971E.toInt(), 0xFFFFD200.toInt()), fakePhoto(0xFF11998E.toInt(), 0xFF38EF7D.toInt())),
            dark = true,
        ),
        "share_card_photos_dark",
    )

    @Test fun flyover_frames() {
        val photos = listOf(fakePhoto(0xFF2B5876.toInt(), 0xFF4E4376.toInt()))
        for ((tag, ground, dark) in listOf(Triple("map", fakeGround(), false), Triple("grid", null, true))) {
            val scene = FlyoverScene(ctx, meta, pts, photos, ground, dark)
            val bmp = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val fly = scene.flyMs
            listOf(
                "0_intro" to 300L, "1_dropin" to scene.introMs + fly / 9, "2_chase" to scene.introMs + fly / 3,
                "3_mid" to scene.introMs + fly / 2, "4_late" to scene.introMs + fly * 3 / 4,
                "5_pullout" to scene.introMs + fly * 19 / 20, "6_photo" to scene.introMs + fly + 800,
                "7_end" to scene.durationMs - 200,
            ).forEach { (name, t) ->
                scene.draw(c, 720, 1280, t)
                save(bmp, "share_fly_${tag}_$name")
            }
        }
    }
}
