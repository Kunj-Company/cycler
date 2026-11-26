package com.cycler.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One bike. [startKm] is what its odometer already showed when it was added. Retired bikes keep their rides. */
data class Bike(
    val id: String,
    val name: String,
    val type: String = "",
    val notes: String = "",
    val startKm: Int = 0,
    val retired: Boolean = false,
)

/** What deleting a bike produces: the new list and the bike its rides and jobs move to. */
class BikeDeletion(val list: BikeList, val movedTo: String)

/** One bike's totals: odometer (starting km plus its rides), ride count and moving time. */
data class BikeTotals(val odometerKm: Double, val rides: Int, val timeS: Long, val ridesKm: Double)

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

    fun totals(bikeId: String, rides: List<RouteMeta>): BikeTotals {
        val mine = rides.filter { it.type == RouteType.RECORDED && ownerOf(it.bikeId) == bikeId }
        val km = mine.sumOf { it.distanceM } / 1000
        return BikeTotals((find(bikeId)?.startKm ?: 0) + km, mine.size, mine.sumOf { it.durationS }, km)
    }

    fun add(name: String, type: String = "", notes: String = "", startKm: Int = 0): BikeList {
        val n = name.trim()
        if (n.isEmpty()) return this
        var id = UUID.randomUUID().toString()
        while (find(id) != null) id = UUID.randomUUID().toString()
        return BikeList(bikes + Bike(id, n, type.trim(), notes.trim(), startKm.coerceAtLeast(0)), defaultId, homeId)
    }

    /** Rename and edit details. An empty name keeps the old one. */
    fun update(id: String, name: String, type: String, notes: String, startKm: Int): BikeList {
        val old = find(id) ?: return this
        val b = old.copy(name = name.trim().ifEmpty { old.name }, type = type.trim(), notes = notes.trim(), startKm = startKm.coerceAtLeast(0))
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

    fun encode(): String = JSONObject().put("v", 1).put("default", defaultId).put("home", homeId)
        .put("bikes", JSONArray().also { a ->
            bikes.forEach { b ->
                a.put(
                    JSONObject().put("id", b.id).put("name", b.name).put("type", b.type).put("notes", b.notes)
                        .put("startKm", b.startKm).put("retired", b.retired)
                )
            }
        }).toString()

    override fun equals(other: Any?) = other is BikeList && bikes == other.bikes && defaultId == other.defaultId && homeId == other.homeId
    override fun hashCode() = bikes.hashCode() * 31 + defaultId.hashCode() * 7 + homeId.hashCode()

    companion object {
        const val DEFAULT_ID = "default"
        const val DEFAULT_NAME = "My bike"

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
                        id, b.optString("name").trim().ifEmpty { "Bike ${list.size + 1}" }, b.optString("type").trim(),
                        b.optString("notes").trim(), b.optInt("startKm", 0).coerceAtLeast(0), b.optBoolean("retired", false),
                    )
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

/** Moves the jobs of a deleted bike to another one. */
fun List<CareItem>.reassignBike(from: String, to: String): List<CareItem> =
    map { if (it.bikeId == from) it.copy(bikeId = to) else it }
