package com.cycler.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One bike. [startKm] is what its odometer already showed when it was added. Retired bikes keep their rides.
 * Everything but [id] and [name] is optional: [year], [weightKg] and [purchased] are 0 when not set, [colour]
 * is a hex string from [BikeColours] (empty: one is picked from the id), [photo] says a picture is stored for it.
 */
data class Bike(
    val id: String,
    val name: String,
    val type: String = "",
    val notes: String = "",
    val startKm: Int = 0,
    val retired: Boolean = false,
    val make: String = "",
    val model: String = "",
    val year: Int = 0,
    val frameSize: String = "",
    val colour: String = "",
    val photo: Boolean = false,
    /** Purchase date as epoch milliseconds; 0 when not set. */
    val purchased: Long = 0L,
    val serial: String = "",
    val weightKg: Double = 0.0,
) {
    /** "Make Model" when either is known, else empty. */
    val subtitle: String get() = listOf(make, model).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    /** The name plus the make and model, for places with room for one line. */
    val fullName: String get() = if (subtitle.isEmpty()) name else "$name · $subtitle"

    /** One letter for the avatar. */
    val initial: String get() = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "B"

    /** The colour to draw it with: the chosen one, else one picked from the id so bikes differ without any set-up. */
    val colourHex: String get() = if (colour in BikeColours.all) colour else BikeColours.all[(id.hashCode() and 0x7fffffff) % BikeColours.all.size]

    /** The routing profile this bike's type suggests, or null when no type is set. */
    val routingProfile: String? get() = BikeTypes.profileFor(type)

    /** The same bike with every field trimmed and held to its limits. An empty name stays empty (callers decide). */
    fun cleaned(now: Long = System.currentTimeMillis()): Bike = copy(
        name = name.trim().take(Limits.NAME), type = type.trim().take(Limits.NAME), notes = notes.trim().take(Limits.NOTES),
        startKm = startKm.coerceIn(0, Limits.MAX_KM), make = make.trim().take(Limits.NAME), model = model.trim().take(Limits.NAME),
        year = if (year in Limits.MIN_YEAR..Limits.maxYear(now)) year else 0,
        frameSize = frameSize.trim().take(Limits.SHORT), colour = if (colour in BikeColours.all) colour else "",
        purchased = purchased.coerceAtLeast(0L), serial = serial.trim().take(Limits.NAME),
        weightKg = if (weightKg.isNaN() || weightKg < Limits.MIN_WEIGHT || weightKg > Limits.MAX_WEIGHT) 0.0 else Math.round(weightKg * 10) / 10.0,
    )

    object Limits {
        const val NAME = 40
        const val SHORT = 20
        const val NOTES = 500
        const val MAX_KM = 1_000_000
        const val MIN_YEAR = 1900
        const val MIN_WEIGHT = 1.0
        const val MAX_WEIGHT = 100.0
        fun maxYear(now: Long = System.currentTimeMillis()): Int =
            java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.YEAR) + 1
    }
}

/** The swatches a bike can be drawn in (avatar and chips). Dark enough for white letters. */
object BikeColours {
    val all = listOf("#2563EB", "#E5484D", "#16A34A", "#B45309", "#7C3AED", "#0E7C86", "#DB2777", "#52525B")
    val names = listOf("Blue", "Red", "Green", "Amber", "Violet", "Teal", "Pink", "Graphite")
}

/** What kind of bike it is, and the route-planning profile that suits it. */
object BikeTypes {
    val all = listOf("Road", "Gravel", "Mountain", "Hybrid / Trekking", "City", "E-bike", "Fixed / Single speed", "Folding", "Other")

    /** Road is fastbike, gravel is gravel, mountain is mtb; every other type, and free text from older versions, trekking. Null for no type. */
    fun profileFor(type: String?): String? {
        val t = type?.trim()?.lowercase().orEmpty()
        if (t.isEmpty()) return null
        return when {
            t.startsWith("road") -> "fastbike"
            t.startsWith("gravel") -> "gravel"
            t.startsWith("mountain") || t == "mtb" -> "mtb"
            else -> "trekking"
        }
    }
}

/** Which routing profile Plan starts from, given the bike about to be ridden. */
object BikeProfiles {
    /** The profile [bike]'s type suggests, or null to leave the current one alone (no bike, no type, or the rider chose by hand). */
    fun forPlanning(bike: Bike?, chosenByHand: Boolean): String? = if (chosenByHand) null else bike?.routingProfile
}

/** One bike's share of a period, for the Activity breakdown. */
data class BikeShare(val bike: Bike, val km: Double, val rides: Int)

/** What deleting a bike produces: the new list and the bike its rides and jobs move to. */
class BikeDeletion(val list: BikeList, val movedTo: String)

/** One bike's totals: odometer (starting km plus its rides), ride count, moving time, climb, longest ride, average speed, recent distance. */
data class BikeTotals(
    val odometerKm: Double, val rides: Int, val timeS: Long, val ridesKm: Double,
    val climbM: Double = 0.0, val longestKm: Double = 0.0, val avgKmh: Double = 0.0,
    val thisYearKm: Double = 0.0, val last30Km: Double = 0.0, val lastRideAt: Long = 0L,
)

/**
 * The rider's bikes. [defaultId] is the bike new rides and new jobs go to. [homeId] is the bike that
 * owns everything recorded before bikes existed (rides and jobs without a bike), so that choosing a
 * different default later never moves old history. Invariants: never empty, ids unique, at least one
 * bike not retired, and [defaultId] is one of those.
 */
class BikeList private constructor(val bikes: List<Bike>, val defaultId: String, val homeId: String) {
    /** True once there is more than one bike; until then none of the multi-bike UI shows. */
    val multi get() = bikes.size > 1
    val active get() = bikes.filter { !it.retired }
    fun find(id: String?): Bike? = id?.let { k -> bikes.firstOrNull { it.id == k } }

    /** The bike a ride or job belongs to; no bike, or one that is gone, means the home bike. */
    fun ownerOf(bikeId: String?): String = find(bikeId)?.id ?: homeId

    fun totals(
        bikeId: String, rides: List<RouteMeta>, now: Long = System.currentTimeMillis(), tz: java.util.TimeZone = java.util.TimeZone.getDefault(),
    ): BikeTotals {
        val mine = rides.filter { it.type == RouteType.RECORDED && ownerOf(it.bikeId) == bikeId }
        val km = mine.sumOf { it.distanceM } / 1000
        val timeS = mine.sumOf { it.durationS }
        val year = java.util.Calendar.getInstance(tz).apply { timeInMillis = now }.get(java.util.Calendar.YEAR)
        val thisYear = mine.filter { java.util.Calendar.getInstance(tz).apply { timeInMillis = it.createdAt }.get(java.util.Calendar.YEAR) == year }
        val recent = mine.filter { it.createdAt >= now - 30L * 24 * 3600 * 1000 && it.createdAt <= now }
        return BikeTotals(
            (find(bikeId)?.startKm ?: 0) + km, mine.size, timeS, km,
            climbM = mine.sumOf { it.ascentM }, longestKm = (mine.maxOfOrNull { it.distanceM } ?: 0.0) / 1000,
            avgKmh = if (timeS > 0) km / (timeS / 3600.0) else 0.0,
            thisYearKm = thisYear.sumOf { it.distanceM } / 1000, last30Km = recent.sumOf { it.distanceM } / 1000,
            lastRideAt = mine.maxOfOrNull { it.createdAt } ?: 0L,
        )
    }

    /** The newest rides on a bike, for its page. */
    fun recentRides(bikeId: String, rides: List<RouteMeta>, n: Int = 5): List<RouteMeta> =
        rides.filter { it.type == RouteType.RECORDED && ownerOf(it.bikeId) == bikeId }.sortedByDescending { it.createdAt }.take(n)

    /** Distance by bike for [rides] (already limited to a period), biggest first; bikes with no rides are left out. */
    fun breakdown(rides: List<RouteMeta>): List<BikeShare> =
        bikes.map { b ->
            val mine = rides.filter { it.type == RouteType.RECORDED && ownerOf(it.bikeId) == b.id }
            BikeShare(b, mine.sumOf { it.distanceM } / 1000, mine.size)
        }.filter { it.rides > 0 }.sortedByDescending { it.km }

    /** Rider plus bike weight for the calorie estimate; 0 (not set) when the rider's own weight is not set. */
    fun energyWeightKg(riderKg: Int, bikeId: String?): Int =
        if (riderKg <= 0) 0 else riderKg + Math.round(find(ownerOf(bikeId))?.weightKg ?: 0.0).toInt()

    /** The bike to name on a ride card or page: only with several bikes, or when the ride is on a bike other than the home bike. */
    fun labelFor(bikeId: String?): Bike? {
        val owner = ownerOf(bikeId)
        return if (multi || owner != homeId) find(owner) else null
    }

    /** A bike id that no bike here uses. */
    fun newId(): String {
        var id = UUID.randomUUID().toString()
        while (find(id) != null) id = UUID.randomUUID().toString()
        return id
    }

    /** The bike the Ride screen offers first: the last one used or the default, as [mode] says, never a retired or missing one. */
    fun preselect(mode: String, lastId: String?): String =
        if (mode == MODE_LAST) active.firstOrNull { it.id == lastId }?.id ?: defaultId else defaultId

    /** The bike to save a ride under when it ends: the chosen one if it is still here and active, else the default. Null with one bike. */
    fun resolveForRide(chosen: String?): String? =
        if (!multi) null else active.firstOrNull { it.id == chosen }?.id ?: defaultId

    fun add(name: String, type: String = "", notes: String = "", startKm: Int = 0): BikeList =
        add(Bike("", name, type = type, notes = notes, startKm = startKm))

    /** Add a bike. Its id is kept when it is free, else a new one is made. A blank name adds nothing. */
    fun add(draft: Bike): BikeList {
        val b = draft.cleaned()
        if (b.name.isEmpty()) return this
        val id = if (b.id.isNotBlank() && find(b.id) == null) b.id else newId()
        return BikeList(bikes + b.copy(id = id, retired = false), defaultId, homeId)
    }

    /** Rename and edit details. An empty name keeps the old one. */
    fun update(id: String, name: String, type: String, notes: String, startKm: Int): BikeList {
        val old = find(id) ?: return this
        return edit(id, old.copy(name = name, type = type, notes = notes, startKm = startKm))
    }

    /** Replace a bike's details with [draft] (id and retired flag stay). An empty name keeps the old one. */
    fun edit(id: String, draft: Bike): BikeList {
        val old = find(id) ?: return this
        val b = draft.copy(id = id, retired = old.retired).cleaned().let { if (it.name.isEmpty()) it.copy(name = old.name) else it }
        return BikeList(bikes.map { if (it.id == id) b else it }, defaultId, homeId)
    }

    fun setDefault(id: String): BikeList {
        val b = find(id) ?: return this
        return if (b.retired) this else BikeList(bikes, id, homeId)
    }

    /** Retiring never removes anything. The last active bike cannot be retired; retiring the default picks another. */
    fun canRetire(id: String) = find(id)?.let { !it.retired && active.size > 1 } ?: false

    fun retire(id: String): BikeList {
        if (!canRetire(id)) return this
        val rest = bikes.map { if (it.id == id) it.copy(retired = true) else it }
        val d = if (defaultId == id) rest.first { !it.retired }.id else defaultId
        return BikeList(rest, d, homeId)
    }

    fun unretire(id: String): BikeList =
        if (find(id) == null) this else BikeList(bikes.map { if (it.id == id) it.copy(retired = false) else it }, defaultId, homeId)

    /**
     * Delete a bike. Its rides and jobs must then be moved to [BikeDeletion.movedTo] by the caller, which
     * should only happen after the rider confirmed. The last bike cannot be deleted. Null when not allowed.
     */
    fun delete(id: String): BikeDeletion? {
        if (find(id) == null || bikes.size < 2) return null
        val rest = bikes.filter { it.id != id }
        if (rest.none { !it.retired }) return null
        val d = if (defaultId == id) rest.first { !it.retired }.id else defaultId
        val h = if (homeId == id) d else homeId
        return BikeDeletion(BikeList(rest, d, h), d)
    }

    fun encode(): String = JSONObject().put("v", 2).put("default", defaultId).put("home", homeId)
        .put("bikes", JSONArray().also { a ->
            bikes.forEach { b ->
                a.put(
                    JSONObject().put("id", b.id).put("name", b.name).put("type", b.type).put("notes", b.notes)
                        .put("startKm", b.startKm).put("retired", b.retired)
                        .put("make", b.make).put("model", b.model).put("year", b.year).put("frameSize", b.frameSize)
                        .put("colour", b.colour).put("photo", b.photo).put("purchased", b.purchased)
                        .put("serial", b.serial).put("weightKg", b.weightKg)
                )
            }
        }).toString()

    override fun equals(other: Any?) = other is BikeList && bikes == other.bikes && defaultId == other.defaultId && homeId == other.homeId
    override fun hashCode() = bikes.hashCode() * 31 + defaultId.hashCode() * 7 + homeId.hashCode()

    companion object {
        const val DEFAULT_ID = "default"
        const val DEFAULT_NAME = "My bike"
        /** [Prefs.bikeNewRides]: start from the bike used last, or always from the default bike. */
        const val MODE_LAST = "last"
        const val MODE_DEFAULT = "default"

        /** The standard jobs as new jobs for bike [forBike], never done. */
        fun standardJobs(forBike: String): List<CareItem> =
            CareItem.defaults().map { it.copy(id = UUID.randomUUID().toString(), bikeId = forBike) }

        /** The single implicit bike every rider starts with. */
        fun initial() = BikeList(listOf(Bike(DEFAULT_ID, DEFAULT_NAME)), DEFAULT_ID, DEFAULT_ID)

        /** Reads the stored text; anything missing or broken gives a valid list (never an exception, never empty). */
        fun parse(raw: String?): BikeList {
            if (raw.isNullOrBlank()) return initial()
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return initial()
            val arr = o.optJSONArray("bikes") ?: return initial()
            val seen = HashSet<String>()
            val list = ArrayList<Bike>()
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                var id = b.optString("id").trim().ifEmpty { UUID.randomUUID().toString() }
                if (!seen.add(id)) { id = UUID.randomUUID().toString(); seen.add(id) }
                list.add(
                    Bike(
                        id, b.optString("name"), b.optString("type"), b.optString("notes"), b.optInt("startKm", 0),
                        b.optBoolean("retired", false), b.optString("make"), b.optString("model"), b.optInt("year", 0),
                        b.optString("frameSize"), b.optString("colour"), b.optBoolean("photo", false), b.optLong("purchased", 0L),
                        b.optString("serial"), b.optDouble("weightKg", 0.0),
                    ).cleaned().let { if (it.name.isEmpty()) it.copy(name = "Bike ${list.size + 1}") else it }
                )
            }
            if (list.isEmpty()) return initial()
            if (list.all { it.retired }) list[0] = list[0].copy(retired = false)
            val d = list.firstOrNull { it.id == o.optString("default") && !it.retired }?.id ?: list.first { !it.retired }.id
            val h = list.firstOrNull { it.id == o.optString("home") }?.id ?: d
            return BikeList(list, d, h)
        }

        /**
         * Bring the bikes of a backup into [current]. No bikes in the backup (an old backup) changes nothing,
         * so its jobs and rides, which have no bike, land on the home bike. Bikes already here win on a clash.
         */
        fun restore(current: BikeList, backupText: String?): BikeList {
            if (backupText.isNullOrBlank()) return current
            val theirs = parse(backupText)
            if (current == initial()) return theirs
            val mine = current.bikes.map { it.id }.toSet()
            return BikeList(current.bikes + theirs.bikes.filter { it.id !in mine }, current.defaultId, current.homeId)
        }
    }
}

/**
 * Copies the job definitions of bike [from] (name, intervals, note) to bike [to], as new jobs that have never been
 * done: no history and resetAt 0, so every ride on the new bike counts and a day interval starts once it is first done.
 */
fun List<CareItem>.copyJobs(list: BikeList, from: String, to: String): List<CareItem> =
    filter { list.ownerOf(it.bikeId) == from }.map {
        it.copy(id = UUID.randomUUID().toString(), bikeId = to, resetAt = 0L, history = emptyList())
    }

/** Moves the jobs of a deleted bike to another one. */
fun List<CareItem>.reassignBike(from: String, to: String): List<CareItem> =
    map { if (it.bikeId == from) it.copy(bikeId = to) else it }
