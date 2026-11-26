package com.cycler.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cycler.app.data.Bike
import com.cycler.app.data.BikeColours
import com.cycler.app.data.BikeList
import com.cycler.app.data.BikePhotos
import com.cycler.app.data.BikeProfiles
import com.cycler.app.data.BikeTypes
import com.cycler.app.data.CareItem
import com.cycler.app.data.Prefs
import com.cycler.app.data.Pt
import com.cycler.app.data.RouteMeta
import com.cycler.app.data.RouteStore
import com.cycler.app.data.RouteType
import com.cycler.app.data.copyJobs
import org.junit.Assert.assertArrayEquals
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The finished multiple-bikes feature: profile, selection, attachment, totals, copy jobs, backup with photos. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BikesCompleteTest {
    private lateinit var ctx: Application
    private lateinit var store: RouteStore
    private val utc = TimeZone.getTimeZone("UTC")
    private val day = 24L * 3600 * 1000

    @Before fun setUp() {
        RouteStore.resetForTests()
        ctx = ApplicationProvider.getApplicationContext()
        ctx.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        ctx.getSharedPreferences("cycler", 0).edit().clear().commit()
        store = RouteStore.get(ctx)
    }

    private fun ride(id: String, km: Double, at: Long, bike: String? = null, timeS: Long = 3600, climb: Double = 0.0) =
        RouteMeta(id, id, RouteType.RECORDED, at, km * 1000, climb, timeS, 0.0, 0.0, bikeId = bike)

    private fun two(): Pair<BikeList, String> {
        val l = BikeList.initial().add("Gravel")
        return l to l.bikes.last().id
    }

    // ------------------------------------------------------------ profile validation

    @Test fun cleanedTrimsAndCapsEverything() {
        val long = "x".repeat(200)
        val b = Bike("id", "  $long  ", type = "  Road ", notes = "n".repeat(900), startKm = 5_000_000, make = " $long", model = long,
            frameSize = long, serial = long, year = 2020, colour = "#000000", weightKg = 9.87).cleaned()
        assertEquals(Bike.Limits.NAME, b.name.length)
        assertEquals("Road", b.type)
        assertEquals(Bike.Limits.NOTES, b.notes.length)
        assertEquals(Bike.Limits.MAX_KM, b.startKm)
        assertEquals(Bike.Limits.NAME, b.make.length); assertEquals(Bike.Limits.NAME, b.model.length)
        assertEquals(Bike.Limits.SHORT, b.frameSize.length); assertEquals(Bike.Limits.NAME, b.serial.length)
        assertEquals("", b.colour)                    // not one of the swatches
        assertEquals(9.9, b.weightKg, 0.0001)
        assertEquals(2020, b.year)
    }

    @Test fun yearWeightAndOdometerRanges() {
        val now = 1_800_000_000_000L          // 2027
        assertEquals(0, Bike("a", "A", year = 1899).cleaned(now).year)
        assertEquals(1900, Bike("a", "A", year = 1900).cleaned(now).year)
        assertEquals(0, Bike("a", "A", year = 3000).cleaned(now).year)
        assertEquals(0.0, Bike("a", "A", weightKg = 0.5).cleaned().weightKg, 0.0)
        assertEquals(0.0, Bike("a", "A", weightKg = 250.0).cleaned().weightKg, 0.0)
        assertEquals(0.0, Bike("a", "A", weightKg = Double.NaN).cleaned().weightKg, 0.0)
        assertEquals(12.5, Bike("a", "A", weightKg = 12.5).cleaned().weightKg, 0.0)
        assertEquals(0, Bike("a", "A", startKm = -4).cleaned().startKm)
        assertEquals(0L, Bike("a", "A", purchased = -9).cleaned().purchased)
        assertEquals(BikeColours.all[2], Bike("a", "A", colour = BikeColours.all[2]).cleaned().colour)
    }

    @Test fun blankNameAddsNothingAndEditKeepsTheOldName() {
        val l = BikeList.initial()
        assertEquals(l, l.add(Bike("", "   ")))
        val e = l.edit(BikeList.DEFAULT_ID, Bike("", "", make = "Trek"))
        assertEquals("My bike", e.find(BikeList.DEFAULT_ID)!!.name)
        assertEquals("Trek", e.find(BikeList.DEFAULT_ID)!!.make)
    }

    @Test fun addAndEditKeepIdAndRetiredFlag() {
        val (l, g) = two()
        val e = l.edit(g, Bike("other-id", "Renamed", retired = true, make = "Canyon"))
        assertEquals(g, e.find(g)!!.id)
        assertFalse(e.find(g)!!.retired)
        assertNull(e.find("other-id"))
    }

    @Test fun duplicateNamesAllowedIdsUnique() {
        val l = BikeList.initial().add("Same").add("Same").add("Same")
        assertEquals(4, l.bikes.size)
        assertEquals(4, l.bikes.map { it.id }.toSet().size)
        // A draft with a taken id gets a fresh one.
        val d = l.add(Bike(BikeList.DEFAULT_ID, "Clash"))
        assertEquals(5, d.bikes.map { it.id }.toSet().size)
    }

    @Test fun veryLongNamesAreCappedWhenAddedAndParsed() {
        val l = BikeList.initial().add("y".repeat(500))
        assertEquals(40, l.bikes.last().name.length)
        val raw = """{"bikes":[{"id":"a","name":"${"z".repeat(300)}"}]}"""
        assertEquals(40, BikeList.parse(raw).bikes[0].name.length)
    }

    // ------------------------------------------------------------ names and display

    @Test fun displayNames() {
        assertEquals("", Bike("a", "Commuter").subtitle)
        assertEquals("Commuter", Bike("a", "Commuter").fullName)
        assertEquals("Trek Domane", Bike("a", "Commuter", make = "Trek", model = "Domane").subtitle)
        assertEquals("Trek", Bike("a", "Commuter", make = "Trek").subtitle)
        assertEquals("Domane", Bike("a", "Commuter", model = " Domane ").subtitle)
        assertEquals("Commuter · Trek Domane", Bike("a", "Commuter", make = "Trek", model = "Domane").fullName)
        assertEquals("C", Bike("a", "commuter").initial)
        assertEquals("B", Bike("a", "!!").initial)
    }

    @Test fun colourFallsBackToAStableOnePerId() {
        val a = Bike("alpha", "A"); val b = Bike("alpha", "B")
        assertEquals(a.colourHex, b.colourHex)
        assertTrue(a.colourHex in BikeColours.all)
        assertEquals(BikeColours.all[1], Bike("alpha", "A", colour = BikeColours.all[1]).colourHex)
    }

    // ------------------------------------------------------------ routing profile

    @Test fun typeSuggestsProfile() {
        val m = mapOf(
            "Road" to "fastbike", "Gravel" to "gravel", "Mountain" to "mtb", "Hybrid / Trekking" to "trekking", "City" to "trekking",
            "E-bike" to "trekking", "Fixed / Single speed" to "trekking", "Folding" to "trekking", "Other" to "trekking",
            "road bike" to "fastbike", "MTB" to "mtb", "Commuter" to "trekking",
        )
        m.forEach { (t, p) -> assertEquals(t, p, BikeTypes.profileFor(t)) }
        assertNull(BikeTypes.profileFor("")); assertNull(BikeTypes.profileFor("  ")); assertNull(BikeTypes.profileFor(null))
        BikeTypes.all.forEach { assertTrue(it, BikeTypes.profileFor(it) in Prefs.PROFILES) }
    }

    @Test fun planningProfileRespectsManualChoice() {
        val road = Bike("a", "A", type = "Road")
        assertEquals("fastbike", BikeProfiles.forPlanning(road, chosenByHand = false))
        assertNull(BikeProfiles.forPlanning(road, chosenByHand = true))
        assertNull(BikeProfiles.forPlanning(Bike("a", "A"), false))   // no type: leave the profile alone
        assertNull(BikeProfiles.forPlanning(null, false))
        assertEquals("fastbike", road.routingProfile)
    }

    // ------------------------------------------------------------ last used vs default

    @Test fun preselectLastUsedOrDefault() {
        val (l, g) = two()
        assertEquals(g, l.preselect(BikeList.MODE_LAST, g))
        assertEquals(BikeList.DEFAULT_ID, l.preselect(BikeList.MODE_DEFAULT, g))
        assertEquals(BikeList.DEFAULT_ID, l.preselect(BikeList.MODE_LAST, ""))          // nothing used yet
        assertEquals(BikeList.DEFAULT_ID, l.preselect(BikeList.MODE_LAST, "gone"))      // that bike no longer exists
        val retired = l.setDefault(BikeList.DEFAULT_ID).retire(g)
        assertEquals(BikeList.DEFAULT_ID, retired.preselect(BikeList.MODE_LAST, g))     // a retired bike is not offered
    }

    @Test fun prefsHoldTheModeAndTheLastBike() {
        val p = Prefs(ctx)
        assertEquals(BikeList.MODE_LAST, p.bikeNewRides)
        assertEquals("", p.lastBikeId)
        p.bikeNewRides = BikeList.MODE_DEFAULT; p.lastBikeId = "abc"
        assertEquals(BikeList.MODE_DEFAULT, Prefs(ctx).bikeNewRides)
        assertEquals("abc", Prefs(ctx).lastBikeId)
        ctx.getSharedPreferences("cycler", 0).edit().putString("bikeNewRides", "junk").commit()
        assertEquals(BikeList.MODE_LAST, Prefs(ctx).bikeNewRides)
        // The mode travels in backups; the last bike is device state.
        assertTrue("bikeNewRides" in Prefs.SPEC.keys); assertFalse("lastBikeId" in Prefs.SPEC.keys)
    }

    @Test fun rideBikeFallsBackToDefaultWhenGoneOrRetired() {
        val (l, g) = two()
        assertEquals(g, l.resolveForRide(g))
        assertEquals(BikeList.DEFAULT_ID, l.resolveForRide(null))
        assertEquals(BikeList.DEFAULT_ID, l.resolveForRide("deleted"))
        assertEquals(BikeList.DEFAULT_ID, l.retire(g).resolveForRide(g))
        assertNull(BikeList.initial().resolveForRide(BikeList.DEFAULT_ID))        // one bike: nothing is attached
        val onlyOneLeft = l.delete(g)!!.list
        assertNull(onlyOneLeft.resolveForRide(g))
    }

    // ------------------------------------------------------------ attachment through save and recovery

    private val t0 = 1_795_400_000_000L
    private fun csv(vararg head: String): String {
        val sb = StringBuilder()
        head.forEach { sb.append(it).append('\n') }
        for (i in 0 until 5) sb.append("%.6f,77.590000,900.0,%d\n".format(java.util.Locale.US, 12.97 + i * 20.0 / 111195.0, t0 + i * 2000L))
        return sb.toString()
    }

    @Test fun saveKeepsTheBikeAndRejectsUnsafeIds() {
        val pts = listOf(Pt(50.0, 8.0, 1.0, 0), Pt(50.001, 8.001, 1.0, 60_000))
        val ok = store.save("R", RouteType.RECORDED, pts, 60, bikeId = "abc-123")
        assertEquals("abc-123", store.metas.value.first { it.id == ok }.bikeId)
        val bad = store.save("R", RouteType.RECORDED, pts, 60, bikeId = "../x")
        assertNull(store.metas.value.first { it.id == bad }.bikeId)
        val none = store.save("R", RouteType.RECORDED, pts, 60)
        assertNull(store.metas.value.first { it.id == none }.bikeId)
        // And it survives a reload from disk.
        RouteStore.resetForTests(); store = RouteStore.get(ctx)
        assertEquals("abc-123", store.metas.value.first { it.id == ok }.bikeId)
    }

    @Test fun recoveryKeepsTheChosenBike() {
        val (l, g) = two()
        Prefs(ctx).bikes = l
        store.recordingFile.writeText(csv("#B,$g"))
        val rec = store.readRecording()!!
        assertEquals(g, rec.bikeId)
        val id = store.recoverUnfinished()!!
        assertEquals(g, store.metas.value.first { it.id == id }.bikeId)
    }

    @Test fun recoveryFallsBackWhenTheBikeWasDeletedOrRetired() {
        val (l, g) = two()
        Prefs(ctx).bikes = l.delete(g)!!.list.add("Third")
        store.recordingFile.writeText(csv("#B,$g"))
        val id = store.recoverUnfinished()!!
        assertEquals(BikeList.DEFAULT_ID, store.metas.value.first { it.id == id }.bikeId)
        Prefs(ctx).bikes = l.retire(g)
        store.recordingFile.writeText(csv("#B,$g"))
        val id2 = store.recoverUnfinished()!!
        assertEquals(BikeList.DEFAULT_ID, store.metas.value.first { it.id == id2 }.bikeId)
    }

    @Test fun recoveryWithOneBikeOrOldFileAttachesNothing() {
        Prefs(ctx).bikes = BikeList.initial()
        store.recordingFile.writeText(csv("#B,default"))
        val a = store.recoverUnfinished()!!
        assertNull(store.metas.value.first { it.id == a }.bikeId)
        // An old recording without a #B line, and one with a junk line.
        Prefs(ctx).bikes = two().first
        store.recordingFile.writeText(csv())
        val b = store.recoverUnfinished()!!
        assertNull(store.metas.value.first { it.id == b }.bikeId)
        store.recordingFile.writeText(csv("#B,../../etc", "#B"))
        assertNull(store.readRecording()!!.bikeId)
        assertNotNull(store.recoverUnfinished())
    }

    @Test fun importedRidesWithoutABikeBelongToTheHomeBike() {
        val (l, _) = two()
        val id = store.save("Imported", RouteType.RECORDED, listOf(Pt(50.0, 8.0, 1.0, 0), Pt(50.001, 8.001, 1.0, 60_000)), 60)
        assertNull(store.metas.value.first { it.id == id }.bikeId)
        assertEquals(l.homeId, l.ownerOf(null))
        assertEquals(l.homeId, l.ownerOf(store.metas.value.first { it.id == id }.bikeId))
    }

    // ------------------------------------------------------------ totals and breakdown

    @Test fun totalsHaveClimbLongestAverageAndRecentDistance() {
        val (l, g) = two()
        val now = 1_795_400_000_000L
        val rides = listOf(
            ride("a", 40.0, now - 2 * day, g, timeS = 7200, climb = 300.0),
            ride("b", 20.0, now - 40 * day, g, timeS = 3600, climb = 100.0),
            ride("c", 10.0, now - 400 * day, g, timeS = 1800, climb = 50.0),
            ride("d", 99.0, now - day, null, timeS = 3600),                   // the home bike's
        )
        val t = l.totals(g, rides, now, utc)
        assertEquals(3, t.rides)
        assertEquals(70.0, t.ridesKm, 1e-6)
        assertEquals(450.0, t.climbM, 1e-6)
        assertEquals(40.0, t.longestKm, 1e-6)
        assertEquals(70.0 / (12600 / 3600.0), t.avgKmh, 1e-6)
        assertEquals(40.0, t.last30Km, 1e-6)
        assertEquals(60.0, t.thisYearKm, 1e-6)              // a and b are in the same calendar year as now, c is not
        assertEquals(now - 2 * day, t.lastRideAt)
        assertEquals(12600L, t.timeS)
    }

    @Test fun bikeWithNoRidesHasZeroTotals() {
        val (l, g) = two()
        val t = l.totals(g, listOf(ride("x", 5.0, 1000)), 5000, utc)
        assertEquals(0, t.rides); assertEquals(0.0, t.avgKmh, 0.0); assertEquals(0L, t.lastRideAt); assertEquals(0.0, t.longestKm, 0.0)
        assertTrue(l.recentRides(g, emptyList()).isEmpty())
    }

    @Test fun recentRidesAreNewestFirstAndOnThatBikeOnly() {
        val (l, g) = two()
        val rides = (1..8).map { ride("r$it", it.toDouble(), it * 1000L, if (it % 2 == 0) g else null) }
        assertEquals(listOf("r8", "r6", "r4", "r2"), l.recentRides(g, rides).map { it.id })
        assertEquals(2, l.recentRides(g, rides, 2).size)
    }

    @Test fun breakdownGroupsByBikeAndSkipsIdleOnes() {
        val l = BikeList.initial().add("Gravel").add("Tandem")
        val gravel = l.bikes[1].id
        val rides = listOf(ride("a", 10.0, 1, null), ride("b", 30.0, 2, gravel), ride("c", 5.0, 3, gravel), ride("d", 1.0, 4, "vanished"))
        val s = l.breakdown(rides)
        assertEquals(listOf("Gravel", "My bike"), s.map { it.bike.name })        // the vanished bike's ride is the home bike's
        assertEquals(35.0, s[0].km, 1e-6); assertEquals(2, s[0].rides)
        assertEquals(11.0, s[1].km, 1e-6); assertEquals(2, s[1].rides)
        assertTrue(l.breakdown(emptyList()).isEmpty())
    }

    @Test fun energyUsesRiderPlusBikeWeight() {
        val l = BikeList.initial().add(Bike("", "Heavy", weightKg = 14.6))
        val heavy = l.bikes.last().id
        assertEquals(85, l.energyWeightKg(70, heavy))
        assertEquals(70, l.energyWeightKg(70, null))                          // home bike has no weight set
        assertEquals(0, l.energyWeightKg(0, heavy))                           // no rider weight: no estimate
    }

    @Test fun rideCardsNameTheBikeOnlyWhenItMatters() {
        val single = BikeList.initial()
        assertNull(single.labelFor(null))
        val (l, g) = two()
        assertEquals("My bike", l.labelFor(null)!!.name)
        assertEquals("Gravel", l.labelFor(g)!!.name)
        // Back to one bike that is not the home bike: its rides are still named.
        val l2 = l.delete(BikeList.DEFAULT_ID)!!.list
        assertEquals(g, l2.homeId)
        assertNull(l2.labelFor(null))
    }

    // ------------------------------------------------------------ copy jobs

    @Test fun copyJobsMakesFreshJobsForTheNewBike() {
        val (l, g) = two()
        val items = listOf(
            CareItem("Chain", 300, 500, 30, "note", listOf(com.cycler.app.data.CareDone(500)), "j1"),            // home bike (no bikeId)
            CareItem("Tyres", 1000, 0, bikeId = g),
        )
        val third = l.add("Third")
        val t = third.bikes.last().id
        val copied = items.copyJobs(third, BikeList.DEFAULT_ID, t)
        assertEquals(1, copied.size)
        val c = copied[0]
        assertEquals("Chain", c.name); assertEquals(300, c.everyKm); assertEquals(30, c.everyDays); assertEquals("note", c.note)
        assertEquals(0L, c.resetAt); assertTrue(c.history.isEmpty()); assertEquals(t, c.bikeId); assertNotEquals("j1", c.id)
        assertEquals("j1", items[0].id)                                            // the original is untouched
        val std = BikeList.standardJobs(t)
        assertEquals(CareItem.defaults().map { it.name }, std.map { it.name })
        assertTrue(std.all { it.bikeId == t && it.resetAt == 0L && it.history.isEmpty() })
        assertEquals(std.size, std.map { it.id }.toSet().size)
        assertTrue(items.copyJobs(third, "nobody", t).isEmpty())
    }

    @Test fun jobsAddedForABikeCountOnlyThatBikesRides() {
        val (l, g) = two()
        val job = CareItem("Chain", 100, 0, bikeId = g)
        val rides = listOf(ride("a", 60.0, 10, g), ride("b", 70.0, 20, null))
        val s = com.cycler.app.data.Care.status(job, rides, 1_000_000, utc, l)
        assertEquals(60.0, s.usedKm, 1e-6)
        assertFalse(s.due)
    }

    // ------------------------------------------------------------ old formats

    @Test fun firstVersionBikeJsonStillLoads() {
        val raw = """{"v":1,"default":"g","home":"default","bikes":[
            {"id":"default","name":"My bike","type":"Commuter","notes":"n","startKm":120,"retired":false},
            {"id":"g","name":"Gravel","type":"","notes":"","startKm":0,"retired":true,"future":"ignored"}]}"""
        val l = BikeList.parse(raw)
        assertEquals(2, l.bikes.size)
        val b = l.find("default")!!
        assertEquals("Commuter", b.type); assertEquals(120, b.startKm)
        assertEquals("", b.make); assertEquals(0, b.year); assertEquals(0.0, b.weightKg, 0.0); assertEquals(0L, b.purchased); assertFalse(b.photo)
        assertEquals("trekking", b.routingProfile)                            // free text type still maps
        assertEquals("default", l.defaultId)                                  // g is retired, so it cannot be the default
    }

    @Test fun newFieldsRoundTripThroughEncode() {
        val l = BikeList.initial().add(
            Bike("", "Tour", "Gravel", "notes", 10, false, "Surly", "Cross-Check", 2019, "56", BikeColours.all[4], true, 1_600_000_000_000L, "SN1", 13.4),
        )
        val back = BikeList.parse(l.encode())
        assertEquals(l, back)
        val b = back.bikes.last()
        assertEquals("Surly Cross-Check", b.subtitle); assertEquals(2019, b.year); assertEquals(13.4, b.weightKg, 0.0)
        assertTrue(b.photo); assertEquals("SN1", b.serial); assertEquals(1_600_000_000_000L, b.purchased)
    }

    // ------------------------------------------------------------ retire and delete with the new fields

    @Test fun retireAndDeleteStillFollowTheRules() {
        val l = BikeList.initial().add(Bike("", "Gravel", make = "Canyon", weightKg = 10.0, serial = "S")).add("Third")
        val g = l.bikes[1].id
        assertTrue(l.canRetire(g))
        val r = l.retire(g)
        assertEquals("Canyon", r.find(g)!!.make)                      // retiring keeps every detail
        assertTrue(r.find(g)!!.retired)
        assertEquals(l.bikes.size, r.bikes.size)
        val del = r.delete(g)!!
        assertNull(del.list.find(g))
        assertEquals(del.list.defaultId, del.movedTo)
        // The last active bike cannot be retired, and the last bike cannot be deleted.
        val solo = BikeList.initial()
        assertFalse(solo.canRetire(BikeList.DEFAULT_ID)); assertNull(solo.delete(BikeList.DEFAULT_ID))
        // All bikes retired never leaves nothing to ride.
        val raw = """{"bikes":[{"id":"a","name":"A","retired":true},{"id":"b","name":"B","retired":true}]}"""
        assertEquals(1, BikeList.parse(raw).active.size)
    }

    // ------------------------------------------------------------ photos and backups

    private fun fakeJpeg(n: Int) = ByteArray(n) { (it % 251).toByte() }

    @Test fun photoFileNamesAreSafe() {
        assertNotNull(BikePhotos.file(ctx, "abc-123_X"))
        assertNull(BikePhotos.file(ctx, "../evil")); assertNull(BikePhotos.file(ctx, "a/b")); assertNull(BikePhotos.file(ctx, ""))
        assertEquals("abc.jpg", BikePhotos.file(ctx, "abc")!!.name)
        assertEquals("bikes", BikePhotos.file(ctx, "abc")!!.parentFile!!.name)
    }

    @Test fun photoIsDownscaledToTheLimit() {
        assertEquals(1024 to 768, BikePhotos.scaledSize(4000, 3000))
        assertEquals(768 to 1024, BikePhotos.scaledSize(3000, 4000))
        assertEquals(800 to 600, BikePhotos.scaledSize(800, 600))            // never enlarged
        assertEquals(1024 to 1, BikePhotos.scaledSize(100_000, 10))
    }

    @Test fun deletingABikeRemovesItsPhotoAndPruneKeepsTheOthers() {
        val a = BikePhotos.file(ctx, "keep")!!.also { it.parentFile!!.mkdirs(); it.writeBytes(fakeJpeg(10)) }
        val b = BikePhotos.file(ctx, "drop")!!.also { it.writeBytes(fakeJpeg(10)) }
        val tmp = File(a.parentFile, "half.jpg.tmp").also { it.writeBytes(fakeJpeg(3)) }
        BikePhotos.remove(ctx, "drop")
        assertFalse(b.exists()); assertTrue(a.exists())
        BikePhotos.remove(ctx, "../nothing")                                   // unsafe ids are ignored, not followed
        File(a.parentFile, "orphan.jpg").writeBytes(fakeJpeg(4))
        assertEquals(2, BikePhotos.prune(ctx, setOf("keep")))                  // the orphan and the half-written file
        assertTrue(a.exists()); assertFalse(tmp.exists())
    }

    @Test fun backupCarriesPhotosAndRestoreDoesNotOverwriteExistingOnes() {
        val (l, g) = two()
        val photo = BikePhotos.file(ctx, g)!!.also { it.parentFile!!.mkdirs(); it.writeBytes(fakeJpeg(5000)) }
        val bytes = ByteArrayOutputStream().also { store.exportAll(it, mapOf("bikes.json" to l.encode())) }.toByteArray()
        val names = java.util.zip.ZipInputStream(ByteArrayInputStream(bytes)).use { z -> generateSequence { z.nextEntry }.map { it.name }.toList() }
        assertTrue("bikes/$g.jpg" in names); assertTrue("settings/bikes.json" in names)
        // A fresh device gets the photo and the bike.
        photo.delete()
        var bikesText: String? = null
        store.importAll(ByteArrayInputStream(bytes)) { n, t -> if (n == "bikes.json") bikesText = t }
        assertArrayEquals(fakeJpeg(5000), photo.readBytes())
        assertEquals(l, BikeList.restore(BikeList.initial(), bikesText))
        // A device that already has a different photo for that bike keeps it.
        photo.writeBytes(fakeJpeg(7))
        store.importAll(ByteArrayInputStream(bytes))
        assertEquals(7, photo.length().toInt())
    }

    @Test fun restoreMergesBikesAndKeepsExistingOnes() {
        val (mine, g) = two()
        val backupBikes = BikeList.initial().add(Bike("", "Tandem", make = "Co-Motion"))
        val merged = BikeList.restore(mine.edit(g, mine.find(g)!!.copy(make = "Mine")), backupBikes.encode())
        assertEquals(3, merged.bikes.size)
        assertEquals("Mine", merged.find(g)!!.make)
        assertEquals("Co-Motion", merged.bikes.last().make)
        assertEquals(merged.bikes.size, merged.bikes.map { it.id }.toSet().size)
    }

    @Test fun restoreOnlyAcceptsSafePhotoEntries() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun add(n: String, size: Int = 10) { z.putNextEntry(ZipEntry(n)); z.write(fakeJpeg(size)); z.closeEntry() }
            add("bikes/ok_1.jpg"); add("bikes/../escaped.jpg"); add("bikes/sp ace.jpg"); add("bikes/x.png"); add("bikes/sub/deep.jpg"); add("bikes/.jpg")
        }
        store.importAll(ByteArrayInputStream(out.toByteArray()))
        assertTrue(File(ctx.filesDir, "bikes/ok_1.jpg").exists())
        assertFalse(File(ctx.filesDir, "escaped.jpg").exists())
        assertEquals(listOf("ok_1.jpg"), File(ctx.filesDir, "bikes").list()!!.toList())
        assertNull(RouteStore.entryLimit("bikes/a/b.jpg")); assertNull(RouteStore.entryLimit("bikes/a.gif"))
        assertEquals(RouteStore.MAX_BIKE_PHOTO_BYTES, RouteStore.entryLimit("bikes/a.jpg"))
    }

    @Test fun oversizedPhotoEntryIsDropped() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("bikes/big.jpg")); z.write(ByteArray((RouteStore.MAX_BIKE_PHOTO_BYTES + 10).toInt())); z.closeEntry()
        }
        store.importAll(ByteArrayInputStream(out.toByteArray()))
        assertFalse(File(ctx.filesDir, "bikes/big.jpg").exists())
        assertFalse(File(ctx.filesDir, "bikes/big.jpg.tmp").exists())
    }

    @Test fun oldBackupWithoutBikesStillRestores() {
        val id = store.save("Old", RouteType.RECORDED, listOf(Pt(50.0, 8.0, 1.0, 0), Pt(50.001, 8.001, 1.0, 60_000)), 60)
        val bytes = ByteArrayOutputStream().also { store.exportAll(it, mapOf("care.json" to CareItem.encode(CareItem.defaults()))) }.toByteArray()
        store.delete(id)
        var sawBikes = false
        val n = store.importAll(ByteArrayInputStream(bytes)) { name, _ -> if (name == "bikes.json") sawBikes = true }
        assertTrue(n > 0); assertFalse(sawBikes)
        assertEquals(listOf(id), store.metas.value.map { it.id })
        assertFalse(File(ctx.filesDir, "bikes").exists() && File(ctx.filesDir, "bikes").list()!!.isNotEmpty())
    }

    // ------------------------------------------------------------ first launch after the update

    @Test fun existingSingleBikeRiderSeesNoMultiBikeUi() {
        val l = Prefs(ctx).bikes                                // nothing stored yet
        assertEquals(BikeList.initial(), l)
        assertFalse(l.multi)
        assertNull(l.labelFor(null))
        assertNull(l.resolveForRide(null))
        assertTrue(l.breakdown(listOf(ride("a", 1.0, 1))).size == 1)   // the maths still works; the Activity section is hidden by !multi
    }
}
