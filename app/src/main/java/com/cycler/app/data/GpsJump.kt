package com.cycler.app.data

/**
 * Detects GPS jumps: two fixes whose distance and time difference imply a speed no cyclist
 * reaches (a bad first fix, a tunnel, an emulator teleport). The distance of such a segment
 * is not real, so it is left out of ride distance and everything derived from stored points.
 */
object GpsJump {
    /** Fastest speed, in metres per second, a segment may imply (about 108 km/h). */
    const val MAX_SPEED_MS = 30.0

    /** True when covering [gapM] metres in [dtMs] milliseconds is implausible. Untimed segments never are. */
    fun isJump(gapM: Double, dtMs: Long): Boolean = dtMs > 0 && gapM / (dtMs / 1000.0) > MAX_SPEED_MS

    /** Length of a segment to count: zero for a jump, otherwise [gapM]. */
    fun countedLength(gapM: Double, dtMs: Long): Double = if (isJump(gapM, dtMs)) 0.0 else gapM

    /** Distance a live fix adds to a ride. A jump still moves the anchor (the point is stored) but adds nothing. */
    fun addedDistance(gapM: Double, dtMs: Long, keep: Boolean, skipNext: Boolean): Double =
        if (keep && !skipNext) countedLength(gapM, dtMs) else 0.0
}
