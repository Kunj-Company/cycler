package com.cycler.app

import com.cycler.app.net.UpdateCheck
import com.cycler.app.net.Updates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric because org.json is only a stub in plain unit tests. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class UpdatesTest {
    private fun rel(tag: String, draft: Boolean = false, asset: String? = "cycler.apk"): String {
        val apk = if (asset != null) ",{\"name\":\"$asset\",\"browser_download_url\":\"https://x/$tag/$asset\"}" else ""
        return """{"tag_name":"$tag","draft":$draft,"html_url":"https://github.com/x/releases/tag/$tag","published_at":"2026-11-23T10:00:00Z","body":"Notes",
            "assets":[{"name":"checksums.txt","browser_download_url":"https://x/c.txt"}$apk]}"""
    }

    @Test fun versionsCompareNumerically() {
        assertTrue(Updates.compare("0.1.10", "0.1.9") > 0)
        assertTrue(Updates.compare("v0.1.9", "0.1.10") < 0)
        assertEquals(0, Updates.compare("v0.1.9-beta", "0.1.9"))
        assertEquals(0, Updates.compare("0.1", "0.1.0"))
        assertEquals(listOf(0, 1, 10), Updates.parseVersion("v0.1.10-rc1"))
        assertNull(Updates.parseVersion("nightly"))
    }

    @Test fun picksHighestAndSkipsDrafts() {
        val json = "[" + listOf(rel("v0.1.9"), rel("v0.1.11", draft = true), rel("v0.1.10")).joinToString(",") + "]"
        val r = (Updates.decide("0.1.9", json) as UpdateCheck.Available).release
        assertEquals("0.1.10", r.version)
        assertEquals("https://x/v0.1.10/cycler.apk", r.apkUrl)
        assertEquals("23 Nov 2026", Updates.displayDate(r.publishedAt))
    }

    @Test fun upToDateWhenSameOrNewer() {
        assertEquals(UpdateCheck.UpToDate("0.1.10"), Updates.decide("0.1.10", "[" + rel("v0.1.10") + "]"))
        assertEquals(UpdateCheck.UpToDate("0.1.12"), Updates.decide("0.1.12", "[" + rel("v0.1.10") + "]"))
        assertEquals(UpdateCheck.UpToDate("0.1.9"), Updates.decide("0.1.9", "[]"))
    }

    @Test fun noApkAssetGivesNull() {
        val r = (Updates.decide("0.1.9", "[" + rel("v0.1.10", asset = null) + "]") as UpdateCheck.Available).release
        assertNull(r.apkUrl)
    }

    @Test fun badJsonIsAnError() {
        assertTrue(Updates.decide("0.1.9", "not json") is UpdateCheck.Error)
    }
}
