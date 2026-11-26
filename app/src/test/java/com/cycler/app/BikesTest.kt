package com.cycler.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cycler.app.data.BikeList
import com.cycler.app.data.Care
import com.cycler.app.data.CareItem
import com.cycler.app.data.Pt
import com.cycler.app.data.Prefs
import com.cycler.app.data.RideFilter
import com.cycler.app.data.RideFilters
import com.cycler.app.data.RouteMeta
import com.cycler.app.data.RouteStore
import com.cycler.app.data.RouteType
import com.cycler.app.data.reassignBike
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BikesTest {
    private lateinit var ctx: Application
    private lateinit var store: RouteStore
    private val utc = TimeZone.getTimeZone("UTC")

    @Before fun setUp() {
        RouteStore.resetForTests()
        ctx = ApplicationProvider.getApplicationContext()
        ctx.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        ctx.getSharedPreferences("cycler", 0).edit().clear().commit()
        store = RouteStore.get(ctx)
    }

    private fun ride(id: String, km: Double, at: Long, bike: String? = null, timeS: Long = 3600) =
        RouteMeta(id, id, RouteType.RECORDED, at, km * 1000, 0.0, timeS, 0.0, 0.0, bikeId = bike)

    private fun two(): Pair<BikeList, String> {
        val l = BikeList.initial().add("Gravel")
        return l to l.bikes.last().id
    }

    // ------------------------------------------------------------ the single default bike

    @Test fun freshInstallHasOneDefaultBike() {
        val l = BikeList.parse(null)
        assertEquals(1, l.bikes.size)
        assertEquals("My bike", l.bikes[0].name)
        assertEquals(BikeList.DEFAULT_ID, l.defaultId)
        assertFalse(l.multi)
        assertEquals(l, BikeList.parse(l.encode()))
        assertEquals(BikeList.initial(), BikeList.parse("garbage{"))
        assertEquals(BikeList.initial(), BikeList.parse("""{"bikes":[]}"""))
    }

    @Test fun oldCareFormatsStayOnTheDefaultBike() {
        // Old pipe format and old JSON without bikeId both load, with no bike set.
        val old = CareItem.parse("Lube|300|0;Service|2500|1000")
        assertTrue(old.all { it.bikeId == null })
        val json = CareItem.parse("""[{"id":"a","name":"Chain","everyKm":4000,"everyDays":0,"resetAt":5,"note":"","history":[]}]""")
        assertNull(json[0].bikeId)
        val l = BikeList.initial()
        assertEquals(BikeList.DEFAULT_ID, l.ownerOf(json[0].bikeId))
        // Encode only writes bikeId when there is one, and it round-trips.
        assertFalse(JSONObject(CareItem.encode(json).trim('[', ']')).has("bikeId"))
        val tagged = listOf(json[0].copy(bikeId = "b1"))
        assertEquals("b1", CareItem.parse(CareItem.encode(tagged))[0].bikeId)
    }

    // ------------------------------------------------------------ km attribution

    @Test fun kmCountOnlyRidesOnThatBike() {
        val (l, g) = two()
        val rides = listOf(
            ride("old", 10.0, 100), ride("a", 20.0, 200, BikeList.DEFAULT_ID), ride("g", 50.0, 300, g),
        )
        val road = CareItem("Chain", 1000, 0)                  // no bikeId: home bike
        val gravel = CareItem("Chain", 1000, 0, bikeId = g)
        assertEquals(30.0, Care.status(road, rides, 1000, utc, l).usedKm, 0.001)
        assertEquals(50.0, Care.status(gravel, rides, 1000, utc, l).usedKm, 0.001)
        // Without bikes (single bike) everything counts, as before.
        assertEquals(80.0, Care.status(road, rides, 1000, utc, null).usedKm, 0.001)
        // Only rides after the reset count.
        assertEquals(0.0, Care.status(gravel.copy(resetAt = 300), rides, 1000, utc, l).usedKm, 0.001)
        assertEquals(0, Care.dueCount(listOf(road, gravel), rides, 1000, utc, l))
    }

    @Test fun ridesOnAGoneBikeCountForTheHomeBike() {
        val (l, _) = two()
        assertEquals(BikeList.DEFAULT_ID, l.ownerOf("deleted-long-ago"))
        assertEquals(BikeList.DEFAULT_ID, l.ownerOf(null))
    }

    @Test fun changingTheDefaultDoesNotMoveOldRides() {
        val (l, g) = two()
        val d = l.setDefault(g)
        assertEquals(g, d.defaultId)
        assertEquals(BikeList.DEFAULT_ID, d.ownerOf(null))   // old rides stay with the first bike
        assertEquals(BikeList.DEFAULT_ID, BikeList.parse(d.encode()).homeId)
    }

    @Test fun odometerCountsStartKmAndRides() {
        val l0 = BikeList.initial().add("Gravel", startKm = 1200)
        val g = l0.bikes.last().id
        val rides = listOf(ride("g1", 10.0, 1, g, 600), ride("g2", 5.5, 2, g, 400), ride("r", 7.0, 3))
        val t = l0.totals(g, rides)
        assertEquals(1215.5, t.odometerKm, 0.001)
        assertEquals(2, t.rides)
        assertEquals(1000L, t.timeS)
        assertEquals(7.0, l0.totals(BikeList.DEFAULT_ID, rides).odometerKm, 0.001)
        // Planned routes are not rides.
        val planned = ride("p", 99.0, 4, g).copy(type = RouteType.PLANNED)
        assertEquals(1215.5, l0.totals(g, rides + planned).odometerKm, 0.001)
    }

    // ------------------------------------------------------------ list rules

    @Test fun addRenameAndEmptyNames() {
        val l = BikeList.initial()
        assertEquals(l, l.add("   "))
        val a = l.add("  Road  ", type = " race ", startKm = -5)
        assertEquals("Road", a.bikes[1].name)
        assertEquals("race", a.bikes[1].type)
        assertEquals(0, a.bikes[1].startKm)
        val id = a.bikes[1].id
        assertEquals("Road", a.update(id, "", "x", "", 0).find(id)!!.name)   // empty name keeps the old one
        assertEquals("Fast", a.update(id, " Fast ", "", "", 3).find(id)!!.name)
        // Same name twice is allowed; ids stay unique.
        val b = a.add("Road")
        assertEquals(b.bikes.size, b.bikes.map { it.id }.toSet().size)
    }

    @Test fun parseRepairsDuplicateIdsBlankNamesAndBadDefault() {
        val raw = """{"default":"zzz","home":"nope","bikes":[
            {"id":"x","name":"A","retired":true},{"id":"x","name":" "},{"id":"","name":"C"}]}"""
        val l = BikeList.parse(raw)
        assertEquals(3, l.bikes.size)
        assertEquals(3, l.bikes.map { it.id }.toSet().size)
        assertEquals("Bike 2", l.bikes[1].name)
        assertFalse(l.find(l.defaultId)!!.retired)
        assertNotNull(l.find(l.homeId))
        // All retired: one is brought back so there is always an active bike.
        val all = BikeList.parse("""{"bikes":[{"id":"a","name":"A","retired":true}]}""")
        assertFalse(all.bikes[0].retired)
    }

    @Test fun retireIsSafeAndKeepsADefault() {
        val (l, g) = two()
        assertFalse(BikeList.initial().canRetire(BikeList.DEFAULT_ID))      // the last active bike stays
        assertEquals(BikeList.initial(), BikeList.initial().retire(BikeList.DEFAULT_ID))
        val r = l.retire(BikeList.DEFAULT_ID)                                // the default is retired: another is picked
        assertEquals(g, r.defaultId)
        assertTrue(r.find(BikeList.DEFAULT_ID)!!.retired)
        assertEquals(2, r.bikes.size)
        assertFalse(r.canRetire(g))                                          // now the last active one
        assertEquals(r, r.setDefault(BikeList.DEFAULT_ID))                   // cannot default to a retired bike
        assertEquals(BikeList.DEFAULT_ID, r.ownerOf(null))                   // history stays with its bike
        assertFalse(r.unretire(BikeList.DEFAULT_ID).find(BikeList.DEFAULT_ID)!!.retired)
    }

    @Test fun deleteRulesAndMovedTo() {
        assertNull(BikeList.initial().delete(BikeList.DEFAULT_ID))           // the last bike cannot go
        val (l, g) = two()
        assertNull(l.delete("nope"))
        val d = l.delete(g)!!                                                // not the default: rides go to the default
        assertEquals(BikeList.DEFAULT_ID, d.movedTo)
        assertEquals(1, d.list.bikes.size)
        val h = l.setDefault(g).delete(g)!!                                  // the default: another is picked
        assertEquals(BikeList.DEFAULT_ID, h.movedTo)
        assertEquals(BikeList.DEFAULT_ID, h.list.defaultId)
        val homeGone = l.delete(BikeList.DEFAULT_ID)!!                       // the home bike: unassigned rides follow
        assertEquals(g, homeGone.movedTo)
        assertEquals(g, homeGone.list.homeId)
        // Never leaves only retired bikes.
        val oneRetired = l.retire(g)
        assertNull(oneRetired.delete(BikeList.DEFAULT_ID))
        val items = listOf(CareItem("a", 1, 0, bikeId = g), CareItem("b", 1, 0))
        assertEquals(listOf(BikeList.DEFAULT_ID, null), items.reassignBike(g, BikeList.DEFAULT_ID).map { it.bikeId })
    }

    // ------------------------------------------------------------ filter

    @Test fun rideFilterByBike() {
        val (l, g) = two()
        val rides = listOf(ride("old", 1.0, 1), ride("a", 1.0, 2, BikeList.DEFAULT_ID), ride("g", 1.0, 3, g))
        fun names(f: RideFilter) = RideFilters.apply(rides, f, bikes = l).map { it.name }.toSet()
        assertEquals(setOf("old", "a", "g"), names(RideFilter()))
        assertEquals(setOf("old", "a"), names(RideFilter(bikeId = BikeList.DEFAULT_ID)))
        assertEquals(setOf("g"), names(RideFilter(bikeId = g)))
        assertTrue(RideFilter().isDefault)
        assertFalse(RideFilter(bikeId = g).isDefault)
    }

    // ------------------------------------------------------------ ride meta

    @Test fun metaBikeIdRoundTripsAndOldMetaLoads() {
        val id = store.save("Loop", RouteType.RECORDED, listOf(Pt(50.0, 8.0, 100.0, 0), Pt(50.01, 8.01, 101.0, 60_000)), 60)
        assertNull(store.metas.value.first { it.id == id }.bikeId)
        store.setBike(id, "bike-1")
        assertEquals("bike-1", store.metas.value.first { it.id == id }.bikeId)
        store.updateDetails(id, "Loop", "n", 3, listOf("x"))                 // other edits keep the bike
        store.reload()
        assertEquals("bike-1", store.metas.value.first { it.id == id }.bikeId)
        store.setBike(id, null)
        store.reload()
        assertNull(store.metas.value.first { it.id == id }.bikeId)
        // The file of a ride with no bike has no bikeId key at all, so old versions read it unchanged.
        val raw = File(ctx.filesDir, "routes/$id.meta.json").readText()
        assertFalse(JSONObject(raw).has("bikeId"))
        // And an old file written before the field existed loads fine.
        File(ctx.filesDir, "routes/$id.meta.json").writeText(
            JSONObject(raw).apply { remove("bikeId"); put("bikeId", "") }.toString()
        )
        store.reload()
        assertNull(store.metas.value.first { it.id == id }.bikeId)
    }

    @Test fun reassignMovesOnlyThatBikesRides() {
        fun mk(n: String) = store.save(n, RouteType.RECORDED, listOf(Pt(50.0, 8.0, 100.0, 0), Pt(50.01, 8.01, 101.0, 60_000)), 60)
        val a = mk("a"); val b = mk("b"); val c = mk("c")
        store.setBike(a, "x"); store.setBike(b, "y")
        assertEquals(1, store.reassignBike("x", "y"))
        store.reload()
        val m = store.metas.value.associate { it.id to it.bikeId }
        assertEquals("y", m[a]); assertEquals("y", m[b]); assertNull(m[c])
        assertEquals(3, store.metas.value.size)                              // nothing deleted
    }

    // ------------------------------------------------------------ backup

    private fun backup(bikes: BikeList?, care: List<CareItem>): ByteArray {
        val extras = mutableMapOf("care.json" to CareItem.encode(care))
        if (bikes != null) extras["bikes.json"] = bikes.encode()
        return ByteArrayOutputStream().also { store.exportAll(it, extras) }.toByteArray()
    }

    private fun restore(bytes: ByteArray): Pair<String?, String?> {
        var care: String? = null; var bikes: String? = null
        store.importAll(ByteArrayInputStream(bytes)) { n, t -> if (n == "care.json") care = t else if (n == "bikes.json") bikes = t }
        return care to bikes
    }

    @Test fun backupRoundTripKeepsBikesCareAndRideBikes() {
        val (l, g) = two()
        val id = store.save("R", RouteType.RECORDED, listOf(Pt(50.0, 8.0, 100.0, 0), Pt(50.01, 8.01, 101.0, 60_000)), 60)
        store.setBike(id, g)
        val care = listOf(CareItem("Chain", 300, 0, bikeId = g))
        val bytes = backup(l, care)
        // A fresh device.
        RouteStore.resetForTests()
        ctx.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        store = RouteStore.get(ctx)
        val (c, b) = restore(bytes)
        assertEquals(l, BikeList.restore(BikeList.initial(), b))
        assertEquals(g, CareItem.parse(c)[0].bikeId)
        assertEquals(g, store.metas.value.single().bikeId)
        // Prefs persist the list too.
        val prefs = Prefs(ctx)
        prefs.bikes = l
        assertEquals(l, Prefs(ctx).bikes)
    }

    @Test fun oldBackupRestoresIntoTheSingleDefaultBike() {
        val old = backup(null, CareItem.defaults())                          // no bikes.json
        val (c, b) = restore(old)
        assertNull(b)
        val bikes = BikeList.restore(BikeList.initial(), b)
        assertEquals(BikeList.initial(), bikes)
        assertEquals(1, bikes.bikes.size)
        assertEquals("My bike", bikes.bikes[0].name)
        val items = CareItem.parse(c)
        assertEquals(4, items.size)
        assertTrue(items.all { bikes.ownerOf(it.bikeId) == BikeList.DEFAULT_ID })
        // An old backup does not remove bikes that are already on the device.
        val (cur, _) = two()
        assertEquals(cur, BikeList.restore(cur, b))
    }

    @Test fun restoreMergesBikesWithoutLosingAny() {
        val (mine, g) = two()
        val theirs = BikeList.initial().add("Tandem")
        val merged = BikeList.restore(mine, theirs.encode())
        assertEquals(3, merged.bikes.size)
        assertTrue(merged.bikes.any { it.name == "Tandem" })
        assertEquals(mine.defaultId, merged.defaultId)
        assertNotEquals(null, merged.find(g))
        assertEquals(mine.bikes.size, BikeList.restore(mine, mine.encode()).bikes.size)   // restoring itself adds nothing
        assertEquals(BikeList.initial(), BikeList.restore(BikeList.initial(), "not json"))
    }
}
