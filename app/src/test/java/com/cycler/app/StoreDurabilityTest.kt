package com.cycler.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cycler.app.data.Altitude
import com.cycler.app.data.Pt
import com.cycler.app.data.RouteStore
import com.cycler.app.data.RouteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class StoreDurabilityTest {
    private lateinit var ctx: Application
    private lateinit var store: RouteStore
    private val t0 = 1_795_400_000_000L
    private val pts = (0 until 20).map { Pt(12.97 + it * 0.001, 77.59, 900.0 + it, t0 + it * 10_000L) }

    @Before fun setUp() {
        RouteStore.resetForTests()
        ctx = ApplicationProvider.getApplicationContext()
        ctx.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        store = RouteStore.get(ctx)
    }

    @Test fun zeroLengthMetaIsRebuiltFromThePoints() {
        val id = store.save("Morning", RouteType.RECORDED, pts, 190)
        val dir = File(ctx.filesDir, "routes")
        File(dir, "$id.meta.json").writeText("")
        store.reload()
        val m = store.metas.value.single()
        assertEquals(id, m.id)
        assertEquals("Recovered ride", m.name)
        assertEquals(RouteType.RECORDED, m.type)
        assertEquals(t0, m.createdAt)
        assertEquals(190L, m.durationS)
        assertTrue(m.distanceM > 2000)
        assertEquals(Altitude.GPS, m.alt)
        // The rebuilt meta was written back, so the next start reads it normally.
        assertTrue(File(dir, "$id.meta.json").length() > 0)
    }

    @Test fun unreadableMetaWithoutPointsStaysHidden() {
        File(ctx.filesDir, "routes/ghost.meta.json").writeText("{\"id\":")
        store.reload()
        assertTrue(store.metas.value.isEmpty())
    }

    @Test fun safeWriteLeavesNoTempFileAndReplacesContent() {
        val id = store.save("A", RouteType.RECORDED, pts, 190)
        store.rename(id, "B")
        store.rename(id, "C")
        val dir = File(ctx.filesDir, "routes")
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
        store.reload()
        assertEquals("C", store.metas.value.single().name)
    }

    @Test fun renameAndDeletePatchTheIndexImmediately() {
        val id = store.save("A", RouteType.RECORDED, pts, 190)
        assertTrue(store.isLoaded.value)
        store.rename(id, "Z")
        assertEquals("Z", store.metas.value.single().name)
        store.delete(id)
        assertTrue(store.metas.value.isEmpty())
    }
}
