package com.t1dm.sensors

import com.t1dm.data.T1dmRepository
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure, framework-free core of the exercise recorder, in the shape of [StepBucketer]: it folds a
 * bout's wall-clock life and its GPS fixes into per-5-min-bucket totals on the app's grid.
 *
 * Contract:
 * - **Two inputs.** [onTick] advances the seconds, so a bout indoors — or with location denied —
 *   still records its magnitude; [onFix] adds the measured metres on top. A bout that never sees a
 *   fix is a bout with seconds and no distance, not a bout with nothing.
 * - **Active seconds are wall-clock seconds the bout was open inside the bucket**, clipped to the
 *   bout's own start and to `[0, bucketMs/1000]` by construction. A gap in the ticks (the process
 *   suspended, the screen off) is credited: the user did not stop the bout, so it was running.
 * - Segment distance is charged to the bucket the fix **arrives in**. A segment straddling a
 *   boundary goes wholly to the newer bucket; sub-bucket apportionment is deliberately not
 *   attempted, exactly as [StepBucketer] does not attempt it for steps. The interval it was covered
 *   over travels with it, as [ExerciseBucket.trackedMs]: the metres and the seconds that divide them
 *   into a speed have to come from the same segments, and the bucket's own [ExerciseBucket.activeSec]
 *   does not, being clipped to the boundary the metres crossed.
 * - Emitted totals are **this bout's own running total for the bucket**, not a delta: re-emitting a
 *   bucket replaces what this bout previously claimed in it. One bucket can hold two bouts, so what
 *   the store does with the pair is [ExerciseRecorder]'s and the repository's business, not this
 *   class's — a bucketer is built per bout and starts at zero by design.
 * - [onFix] emits only the buckets it CLOSES, never the open partial. The ticker is what persists
 *   the partial, so a 4-second fix cadence is not a Room write per fix.
 *
 * Fixes are filtered here rather than at the Android edge so the rules are host-testable. A fix is
 * refused when its accuracy circle is wider than [MAX_ACCURACY_M], when its stamp does not advance
 * on the last accepted one, or when the speed it implies from that fix exceeds [MAX_SPEED_MPS] —
 * above any running pace, so a receiver's momentary jump across town cannot inflate the track. A
 * refused fix is not remembered, so the next good one measures from the last position actually
 * believed and a single wild reading heals itself.
 *
 * One distance function, [haversineM], defined here and used for every metre this feature reports.
 * `Location.distanceTo` is deliberately not reached for elsewhere: two distance functions disagree.
 *
 * The bucket width is [T1dmRepository.GRID_MS] itself, read rather than restated: crossing one of
 * these boundaries is what [ExerciseRecorder] re-lays the bout's disposal curve on, and the curve
 * lands on the same grid the repository's `requireGrid` enforces. A local copy that drifted would put
 * the two out of step, leaving a bout that records a track and writes its magnitude at the wrong
 * cadence.
 *
 * Not thread-safe: drive it from a single collector ([ExerciseRecorder]'s).
 */
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

    /** Whole seconds recorded so far — exactly the sum of the per-bucket totals this has emitted. */
    val activeSec: Int get() = closedActiveSec + openActiveSec()

    /** Measured metres so far, or null while no fix has been accepted (a bout with no track). */
    val distanceM: Double? get() = if (lastAccepted == null) null else closedDistanceM + openDistanceM

    /** The newest ACCEPTED fix, or null before the first. It is the recorder's staleness signal, and
     *  the only way to learn whether the fix just offered was believed (`lastFix === fix`). */
    val lastFix: ExerciseFix? get() = lastAccepted

    /**
     * Advance the bout to [wallMs]. Returns every bucket whose total changed: the buckets closed by
     * the advance, then the open partial.
     *
     * The partial is withheld while it holds neither a second nor a fix — writing it would mint a
     * grid row and an ingest push for a bucket the bout never touched.
     */
    fun onTick(wallMs: Long): List<ExerciseBucket> {
        val out = ArrayList<ExerciseBucket>(2)
        advanceTo(wallMs, out)
        val open = peek()
        if (open.activeSec > 0 || open.distanceM != null) out.add(open)
        return out
    }

    /**
     * Offer one fix. Returns the buckets the fix's own stamp closed — empty when the fix is refused,
     * and empty (but for the metres it added) while the bout stays inside the open bucket.
     */
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

    /** The open partial bucket, as it stands. */
    fun peek(): ExerciseBucket = ExerciseBucket(
        bucketStartMs = bucketStart,
        activeSec = openActiveSec(),
        distanceM = if (openHasFix) openDistanceM else null,
        trackedMs = openTrackedMs,
    )

    /**
     * The metres this fix adds, or null to refuse it. The first accepted fix contributes zero: it
     * fixes where the track starts and there is nothing yet to measure from.
     */
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
        /** Wider than this and the fix locates the user inside a city block, not on a path. */
        const val MAX_ACCURACY_M = 50f

        /** Above any running pace (a 2:20 marathon is ~5 m/s), so only a receiver glitch exceeds it. */
        const val MAX_SPEED_MPS = 12.0

        private const val EARTH_RADIUS_M = 6_371_008.8

        /**
         * Great-circle metres between two WGS84 points, by the haversine formula. Spherical, so it
         * carries the usual ~0.5 % ellipsoid error — irrelevant beside a consumer GPS's own metres of
         * scatter, and numerically well-behaved at the short distances a fix cadence produces, which
         * the law-of-cosines form is not.
         */
        fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val sLat = sin(Math.toRadians(lat2 - lat1) / 2)
            val sLon = sin(Math.toRadians(lon2 - lon1) / 2)
            val a = sLat * sLat +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sLon * sLon
            return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
        }
    }
}
