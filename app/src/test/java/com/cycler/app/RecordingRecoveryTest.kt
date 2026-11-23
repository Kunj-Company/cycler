package com.cycler.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cycler.app.data.Geo
import com.cycler.app.data.RouteStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RecordingRecoveryTest {
    @Test fun recoveryKeepsLapsStopsTopSpeedAndBreaks() {
        RouteStore.resetForTests()
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        ctx.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        val store = RouteStore.get(ctx)
        val t0 = 1_795_400_000_000L
        val sb = StringBuilder()
        for (i in 0 until 100) sb.append("%.6f,77.590000,900.0,%d,0,%d,8.50,2,%d\n".format(java.util.Locale.US, 12.97 + i * 6.0 / 111195.0, t0 + i * 1000L, i * 1000L, i * 1000L))
        sb.append("#L,600.0,99000,99000\n#P,${t0 + 100_000},99000,99000\n#R,${t0 + 900_000}\n")
        sb.append("%.6f,77.590000,900.0,%d,1,%d,9.00,3,%d\n".format(java.util.Locale.US, 12.99, t0 + 900_000, 99000L, 99000L))
        sb.append("%.6f,77.590000,900.0,%d,0,%d,9.00,3,%d\n".format(java.util.Locale.US, 12.99 + 6.0 / 111195.0, t0 + 901_000, 100000L, 100000L))
        store.recordingFile.writeText(sb.toString())
        val id = store.recoverUnfinished()!!
        val r = store.load(id)!!
        assertEquals(100L, r.meta.durationS)
        assertEquals(3, r.meta.stops)
        assertEquals(9.0, r.meta.maxSpeedMs, 0.01)
        assertTrue(r.meta.laps.isNotEmpty())
        assertTrue(r.pts[100].brk)
        assertEquals(r.meta.distanceM, Geo.length(r.pts), 1.0)
        assertTrue(r.meta.distanceM < 700.0)
    }
}
