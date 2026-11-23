package com.cycler.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cycler.app.data.Pt
import com.cycler.app.data.Prefs
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupPrefsTest {
    private lateinit var ctx: Application
    private lateinit var prefs: Prefs

    @Before fun setUp() {
        RouteStore.resetForTests()
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("cycler", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(ctx)
    }

    private fun setAll() {
        prefs.profile = "road"; prefs.mapStyle = "dark"; prefs.keepScreenOn = false; prefs.themeMode = "dark"
        prefs.autoPause = false; prefs.autoPauseLevel = 2; prefs.offRouteAlert = false; prefs.voice = true
        prefs.voiceEveryKm = 5; prefs.turnCues = false; prefs.weeklyGoalKm = 120; prefs.advancedStats = true
        prefs.weightKg = 72; prefs.autoLapKm = 10; prefs.ridePages = "speed|avg"; prefs.customWorkout = "5,3,60,60,5"
        prefs.onboarded = true
    }

    @Test fun roundTripRestoresAllowlistedValues() {
        setAll()
        val json = prefs.exportJson()
        ctx.getSharedPreferences("cycler", Context.MODE_PRIVATE).edit().clear().commit()
        val fresh = Prefs(ctx)
        assertEquals("trekking", fresh.profile)
        assertEquals(17, fresh.importJson(json))
        assertEquals("road", fresh.profile); assertEquals("dark", fresh.mapStyle); assertFalse(fresh.keepScreenOn)
        assertEquals("dark", fresh.themeMode); assertFalse(fresh.autoPause); assertEquals(2, fresh.autoPauseLevel)
        assertFalse(fresh.offRouteAlert); assertTrue(fresh.voice); assertEquals(5, fresh.voiceEveryKm)
        assertFalse(fresh.turnCues); assertEquals(120, fresh.weeklyGoalKm); assertTrue(fresh.advancedStats)
        assertEquals(72, fresh.weightKg); assertEquals(10, fresh.autoLapKm); assertEquals("speed|avg", fresh.ridePages)
        assertEquals("5,3,60,60,5", fresh.customWorkout); assertTrue(fresh.onboarded)
    }

    @Test fun exportExcludesDeviceStateAndCare() {
        setAll()
        prefs.lastLoc = Pt(1.0, 2.0)
        prefs.care = listOf(com.cycler.app.data.CareItem("X", 1, 0))
        ctx.getSharedPreferences("cycler", Context.MODE_PRIVATE).edit().putBoolean("healthConnected", true).putString("recordingId", "r").commit()
        val keys = JSONObject(prefs.exportJson()).keys().asSequence().toSet()
        assertEquals(Prefs.SPEC.keys, keys)
        for (k in listOf("lastLat", "lastLon", "care", "healthConnected", "recordingId")) assertFalse(k, k in keys)
    }

    @Test fun importIgnoresUnknownExcludedAndWrongTypes() {
        val n = prefs.importJson(
            """{"lastLat":5.5,"lastLon":6.5,"care":"evil","bogus":1,"themeMode":"neon","voice":"yes","weeklyGoalKm":"90",
               "autoPauseLevel":9,"voiceEveryKm":0,"weightKg":80.5,"profile":"","ridePages":123,"keepScreenOn":false,"autoLapKm":7}""",
        )
        assertEquals(2, n)
        assertFalse(prefs.keepScreenOn); assertEquals(7, prefs.autoLapKm)
        assertEquals("system", prefs.themeMode); assertFalse(prefs.voice); assertEquals(50, prefs.weeklyGoalKm)
        assertEquals(1, prefs.autoPauseLevel); assertEquals(1, prefs.voiceEveryKm); assertEquals(0, prefs.weightKg)
        assertEquals("trekking", prefs.profile)
        assertNull(prefs.lastLoc)
        assertEquals(com.cycler.app.data.CareItem.defaults().map { it.name }, prefs.care.map { it.name })
        assertEquals(0, prefs.importJson("not json"))
    }

    @Test fun prefsTravelInsideTheBackupZip() {
        setAll()
        val store = RouteStore.get(ctx)
        val out = ByteArrayOutputStream()
        store.exportAll(out, mapOf("prefs.json" to prefs.exportJson()))
        ctx.getSharedPreferences("cycler", Context.MODE_PRIVATE).edit().clear().commit()
        var text: String? = null
        store.importAll(ByteArrayInputStream(out.toByteArray())) { name, t -> if (name == "prefs.json") text = t }
        assertEquals(17, Prefs(ctx).importJson(text!!))
        assertEquals(120, Prefs(ctx).weeklyGoalKm)
    }

    @Test fun oldBackupWithoutPrefsStillRestoresRides() {
        val store = RouteStore.get(ctx)
        val id = store.save("Old", RouteType.RECORDED, listOf(Pt(50.0, 8.0, 1.0, 0), Pt(50.001, 8.001, 1.0, 60_000)), 60)
        val out = ByteArrayOutputStream()
        store.exportAll(out, mapOf("care.json" to "[]"))
        store.delete(id)
        var sawPrefs = false
        val n = store.importAll(ByteArrayInputStream(out.toByteArray())) { name, _ -> if (name == "prefs.json") sawPrefs = true }
        assertTrue(n > 0); assertFalse(sawPrefs)
        assertEquals(listOf(id), store.metas.value.map { it.id })
    }
}
