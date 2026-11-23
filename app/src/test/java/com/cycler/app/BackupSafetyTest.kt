package com.cycler.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cycler.app.data.Pt
import com.cycler.app.data.RouteStore
import com.cycler.app.data.RouteType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupSafetyTest {
    private lateinit var ctx: Application
    private lateinit var store: RouteStore

    @Before fun setUp() {
        RouteStore.resetForTests()
        ctx = ApplicationProvider.getApplicationContext()
        ctx.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        store = RouteStore.get(ctx)
    }

    private fun zip(block: (ZipOutputStream) -> Unit): InputStream {
        val b = ByteArrayOutputStream()
        ZipOutputStream(b).use(block)
        return ByteArrayInputStream(b.toByteArray())
    }

    private fun ZipOutputStream.text(name: String, text: String) {
        putNextEntry(ZipEntry(name)); write(text.toByteArray()); closeEntry()
    }

    private fun ZipOutputStream.zeros(name: String, size: Long) {
        putNextEntry(ZipEntry(name))
        val chunk = ByteArray(1 shl 20)
        var left = size
        while (left > 0) { val n = minOf(left, chunk.size.toLong()).toInt(); write(chunk, 0, n); left -= n }
        closeEntry()
    }

    private fun metaJson(id: String) = JSONObject()
        .put("id", id).put("name", "Evil").put("type", "RECORDED").put("createdAt", 1L)
        .put("distanceM", 1.0).put("ascentM", 0.0).put("durationS", 1L).toString()

    private fun pts() = listOf(Pt(50.0, 8.0, 100.0, 0), Pt(50.001, 8.001, 101.0, 60_000))

    @Test fun idValidator() {
        assertTrue(RouteStore.isSafeId(java.util.UUID.randomUUID().toString()))
        assertTrue(RouteStore.isSafeId("abc_DEF-123"))
        for (bad in listOf("", "..", ".", "a/b", "a\\b", "../x", "a.b", "a b", "x".repeat(65), "a\u0000b")) {
            assertFalse("'$bad'", RouteStore.isSafeId(bad))
        }
        assertFalse(RouteStore.isSafeId(null))
    }

    @Test fun entryLimitAcceptsOnlyPlainNames() {
        assertTrue(RouteStore.entryLimit("routes/abc-1.meta.json") != null)
        assertTrue(RouteStore.entryLimit("routes/abc-1.pts.json") != null)
        assertTrue(RouteStore.entryLimit("routes/abc-1.hr.json") != null)
        assertTrue(RouteStore.entryLimit("segments/abc-1.json") != null)
        assertTrue(RouteStore.entryLimit("media/abc-1/photo.jpg") != null)
        for (bad in listOf(
            "routes/../x.json", "routes/...meta.json", "routes/a.b.meta.json", "routes/x.txt", "routes/sub/x.meta.json",
            "segments/../x.json", "media/../x/y.jpg", "media/abc/../y.jpg", "media/abc/..", "media/abc/.hidden", "media/abc",
            "media/abc/d/e.jpg", "other/x.json", "/etc/passwd",
        )) assertNull(bad, RouteStore.entryLimit(bad))
    }

    @Test fun pathLikeEntriesAreIgnoredAndNothingEscapes() {
        val canary = File(ctx.filesDir, "canary.txt").apply { writeText("keep") }
        val n = store.importAll(zip {
            it.text("routes/...meta.json", metaJson(".."))
            it.text("routes/../escape.json", "x")
            it.text("media/../evil.jpg", "x")
            it.text("media/../../evil2.jpg", "x")
            it.text("segments/../../evil3.json", "x")
        })
        assertEquals(0, n)
        assertTrue(canary.exists())
        assertFalse(File(ctx.filesDir, "escape.json").exists())
        assertFalse(File(ctx.filesDir, "evil.jpg").exists())
        assertFalse(File(ctx.filesDir.parentFile, "evil2.jpg").exists())
        assertTrue(store.metas.value.isEmpty())
    }

    @Test fun rideWithDotDotIdInMetaIsSkippedAndDeleteCannotWipe() {
        val canary = File(ctx.filesDir, "canary.txt").apply { writeText("keep") }
        // A meta file with a good name but a malicious id inside.
        store.importAll(zip { it.text("routes/goodname.meta.json", metaJson("..")) })
        assertTrue(store.metas.value.none { it.id == ".." })
        store.delete("..")
        store.delete("../")
        store.delete("")
        assertTrue(canary.exists())
        assertTrue(File(ctx.filesDir, "routes").isDirectory)
    }

    @Test fun deleteOnInvalidIdIsNoOpAndMediaDirIsInert() {
        val id = store.save("Real", RouteType.RECORDED, pts(), 60)
        val canary = File(ctx.filesDir, "canary.txt").apply { writeText("keep") }
        store.delete("..")
        assertTrue(canary.exists())
        assertEquals(1, store.metas.value.size)
        assertFalse(store.mediaDir("..").exists())
        assertTrue(store.heartRate("../x").isEmpty())
        store.delete(id)
        assertTrue(store.metas.value.isEmpty())
    }

    @Test fun oversizedSettingsEntryIsRejected() {
        var got = 0
        val n = store.importAll(zip {
            it.zeros("settings/big.json", RouteStore.MAX_SETTINGS_BYTES + 1)
            it.text("settings/small.json", "{}")
        }) { name, _ -> if (name == "small.json") got++ else error("oversized entry delivered: $name") }
        assertEquals(1, n)
        assertEquals(1, got)
    }

    @Test fun oversizedRouteEntryIsRejectedAndLeavesNothing() {
        val n = store.importAll(zip {
            it.zeros("routes/abc.pts.json", RouteStore.MAX_JSON_BYTES + 1)
            it.text("routes/abc2.pts.json", "[]")
        })
        assertEquals(1, n)
        val routes = File(ctx.filesDir, "routes")
        assertFalse(File(routes, "abc.pts.json").exists())
        assertFalse(File(routes, "abc.pts.json.tmp").exists())
        assertTrue(File(routes, "abc2.pts.json").exists())
    }

    @Test fun oldBackupStillRestoresRidesAndKeepsExisting() {
        val keep = store.save("Keep", RouteType.RECORDED, pts(), 60)
        val out = ByteArrayOutputStream()
        store.exportAll(out, mapOf("care.json" to "[]"))
        store.delete(keep)
        assertTrue(store.metas.value.isEmpty())
        val other = store.save("Other", RouteType.RECORDED, pts(), 60)
        var care: String? = null
        val n = store.importAll(ByteArrayInputStream(out.toByteArray())) { name, text -> if (name == "care.json") care = text }
        assertTrue(n > 0)
        assertEquals("[]", care)
        assertEquals(setOf(keep, other), store.metas.value.map { it.id }.toSet())
    }
}
