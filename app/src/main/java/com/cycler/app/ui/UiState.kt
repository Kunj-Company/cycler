package com.cycler.app.ui

import androidx.compose.runtime.saveable.listSaver
import com.cycler.app.data.Geo
import com.cycler.app.data.Pt

/** Fixed, plain messages for routing and planning failures. Never raw exception text or host names. */
object RouteErrors {
    const val OFFLINE = "No internet connection. Check your signal and try again."
    const val BUSY = "The route server is busy or couldn't find a route. Try again in a moment."
    const val NO_ROUTE = "No route found between these points."

    fun message(e: Throwable?): String {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 6) {
            if (t is java.net.UnknownHostException || t is java.net.ConnectException ||
                t is java.net.SocketTimeoutException || t is java.net.NoRouteToHostException ||
                t is javax.net.ssl.SSLException
            ) return OFFLINE
            t = t.cause
            depth++
        }
        val text = e?.message?.lowercase().orEmpty()
        val noRoute = listOf("no track found", "not mapped", "no route found", "no loops", "no routes found", "datafile")
        if (noRoute.any { it in text }) return NO_ROUTE
        return BUSY
    }
}

/** Saver for a list of tags so the post-ride tag editor survives rotation. */
val TagListSaver = listSaver<List<String>, String>(save = { listOf("v1") + it }, restore = { it.drop(1) }) // marker keeps an empty list saveable

/** True when a new location should be written to the saved "last location": every 30 s or 200 m. */
fun shouldPersistLoc(last: Pt?, lastAtMs: Long, now: Pt, nowMs: Long): Boolean {
    if (last == null) return true
    return nowMs - lastAtMs >= 30_000 || Geo.dist(last, now) >= 200.0
}

/** Signals along a ride, remembered for the session so reopening it does not query the server again. */
class SignalsCache {
    private val map = HashMap<String, List<Pt>>()
    fun get(id: String): List<Pt>? = map[id]
    fun put(id: String, v: List<Pt>) { map[id] = v }
    suspend fun getOrLoad(id: String, load: suspend () -> List<Pt>?): List<Pt>? {
        map[id]?.let { return it }
        val v = load() ?: return null
        map[id] = v
        return v
    }
}

/** Saver for a two-ended range (the segment cutter's slider). */
val FloatRangeSaver = listSaver<ClosedFloatingPointRange<Float>, Float>(
    save = { listOf(it.start, it.endInclusive) }, restore = { it[0]..it[1] },
)

/** Saver for the share card choices, so editing survives rotation. */
val CardSpecSaver = listSaver<com.cycler.app.share.CardSpec, Any>(
    save = { listOf(it.title, it.showDate, it.showRoute, it.showMark, it.stats.joinToString(","), it.photo, it.w, it.h) },
    restore = {
        com.cycler.app.share.CardSpec(
            title = it[0] as String, showDate = it[1] as Boolean, showRoute = it[2] as Boolean, showMark = it[3] as Boolean,
            stats = (it[4] as String).split(",").filter { k -> k.isNotEmpty() },
            photo = it[5] as Int, w = it[6] as Int, h = it[7] as Int,
        )
    },
)

/** Progress of the flyover video export, kept in the view model so rotation does not stop it. */
sealed interface VideoState {
    data object Idle : VideoState
    data class Running(val progress: Float) : VideoState
    data class Done(val file: java.io.File, val caption: String) : VideoState
    data object Failed : VideoState
    data object Cancelled : VideoState
}
