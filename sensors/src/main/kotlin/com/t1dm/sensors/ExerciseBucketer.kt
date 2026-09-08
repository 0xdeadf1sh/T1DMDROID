package com.t1dm.sensors

import com.t1dm.data.T1dmRepository
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Totals are running for the bucket, not a delta. Timed by trackedMs. Not thread-safe. */
class ExerciseBucketer(
    private val startMs: Long,
    private val bucketMs: Long = T1dmRepository.GRID_MS,
) {
    private var bucketStart: Long = startMs - Math.floorMod(startMs, bucketMs)
    private var advancedToMs: Long = startMs

    private var openMs: Long = 0L
    private var openDistanceM: Double = 0.0
    private var openTrackedMs: Long = 0L
    private var openHasFix: Boolean = false

    private var closedActiveSec: Int = 0
    private var closedDistanceM: Double = 0.0

    private var lastAccepted: ExerciseFix? = null

    val activeSec: Int get() = closedActiveSec + openActiveSec()

    /** Metres so far; null until a fix is accepted. */
    val distanceM: Double? get() = if (lastAccepted == null) null else closedDistanceM + openDistanceM

    /** Newest accepted fix; `lastFix === fix` is how a caller learns its own fix was believed. */
    val lastFix: ExerciseFix? get() = lastAccepted

    /** Buckets the advance closed, then the open partial; withheld with neither second nor fix. */
    fun onTick(wallMs: Long): List<ExerciseBucket> {
        val out = ArrayList<ExerciseBucket>(2)
        advanceTo(wallMs, out)
        val open = peek()
        if (open.activeSec > 0 || open.distanceM != null) out.add(open)
        return out
    }

    /** Buckets the fixs stamp closed; empty if refused, or while inside the open bucket. */
    fun onFix(fix: ExerciseFix): List<ExerciseBucket> {
        val prev = lastAccepted
        val segmentM = segmentFor(fix) ?: return emptyList()
        val out = ArrayList<ExerciseBucket>(2)
        advanceTo(fix.tsMs, out)
        lastAccepted = fix
        openHasFix = true
        openDistanceM += segmentM
        // The priming fix measured no metres, so it timed no interval either.
        if (prev != null) openTrackedMs += fix.tsMs - prev.tsMs
        return out
    }

    fun peek(): ExerciseBucket = ExerciseBucket(
        bucketStartMs = bucketStart,
        activeSec = openActiveSec(),
        distanceM = if (openHasFix) openDistanceM else null,
        trackedMs = openTrackedMs,
    )

    /** Metres this fix adds; null refuses it. The first accepted fix contributes zero. */
    private fun segmentFor(fix: ExerciseFix): Double? {
        if (!fix.lat.isFinite() || !fix.lon.isFinite() || abs(fix.lat) > 90.0 || abs(fix.lon) > 180.0) return null
        if (!fix.accuracyM.isFinite() || fix.accuracyM > MAX_ACCURACY_M) return null
        if (fix.tsMs < startMs) return null
        val prev = lastAccepted ?: return 0.0
        if (fix.tsMs <= prev.tsMs) return null
        val metres = haversineM(prev.lat, prev.lon, fix.lat, fix.lon)
        val seconds = (fix.tsMs - prev.tsMs) / 1000.0
        return if (metres / seconds > MAX_SPEED_MPS) null else metres
    }

    private fun advanceTo(wallMs: Long, out: MutableList<ExerciseBucket>) {
        if (wallMs <= advancedToMs) return
        var cursor = advancedToMs
        while (true) {
            val bucketEnd = bucketStart + bucketMs
            if (wallMs < bucketEnd) {
                openMs += wallMs - cursor
                cursor = wallMs
                break
            }
            openMs += bucketEnd - cursor
            out.add(peek())
            closedActiveSec += openActiveSec()
            closedDistanceM += openDistanceM
            bucketStart = bucketEnd
            openMs = 0L
            openDistanceM = 0.0
            openTrackedMs = 0L
            openHasFix = false
            cursor = bucketEnd
        }
        advancedToMs = cursor
    }

    private fun openActiveSec(): Int = min(openMs / 1000L, bucketMs / 1000L).toInt()

    companion object {
        /** Wider than this and the fix locates a city block, not a path. */
        const val MAX_ACCURACY_M = 50f

        /** Above any running pace (2:20 marathon ~5 m/s): only a receiver glitch exceeds it. */
        const val MAX_SPEED_MPS = 12.0

        private const val EARTH_RADIUS_M = 6_371_008.8

        /** Great-circle, WGS84 spherical: ~0.5% error. The one distance fn this feature uses. */
        fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val sLat = sin(Math.toRadians(lat2 - lat1) / 2)
            val sLon = sin(Math.toRadians(lon2 - lon1) / 2)
            val a = sLat * sLat +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sLon * sLon
            return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
        }
    }
}
