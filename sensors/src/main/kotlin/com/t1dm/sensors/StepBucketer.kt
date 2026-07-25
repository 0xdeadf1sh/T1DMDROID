package com.t1dm.sensors

/**
 * One closed-or-updated 5-minute step bucket. [bucketStartMs] is a multiple of the bucket width
 * (grid-aligned, `bucketStartMs % 300_000 == 0`); [steps] is the authoritative running total for
 * that bucket, written to `sample.steps` with set/LWW semantics (never additively).
 */
data class StepBucket(val bucketStartMs: Long, val steps: Int)

/**
 * Pure, framework-free core of `:sensors` StepSource (§3.5 / Phase 1): folds the
 * cumulative `TYPE_STEP_COUNTER` reading — which is monotone since the last reboot and resets to
 * 0 across one — into per-5-min-bucket deltas on the app's grid.
 *
 * Contract:
 * - The **first** sample only primes the baseline; it emits nothing (the pre-existing counter
 *   value is not our steps).
 * - Each later sample's delta is attributed to the bucket the sample **arrives in** (floor of the
 *   phone-receive wall time). A delta straddling a boundary is charged wholly to the newer bucket;
 *   sub-bucket apportionment is deliberately not attempted in Phase 1.
 * - **Reset (reboot → 0):** a `cumulative < lastCumulative` step is read as a reboot, so the delta
 *   is `cumulative` itself (the counter climbed 0→cumulative post-boot). The primary reboot
 *   handler is still the service re-priming a fresh bucketer on `BOOT_COMPLETED`; this guard only
 *   covers a reset observed mid-stream within one process.
 * - Emitted totals carry **set** semantics: re-emitting a bucket overwrites its `sample.steps`.
 *
 * Timestamps are assumed non-decreasing (guaranteed by a single monotonic sensor stream stamped on
 * receipt). An out-of-order older stamp is folded into the open bucket rather than mis-keyed.
 *
 * Not thread-safe: drive it from a single collector (the callbackFlow in [StepSource]).
 */
class StepBucketer(private val bucketMs: Long = FIVE_MIN_MS) {

    private var lastCumulative: Long = UNSET
    private var bucketStart: Long = UNSET
    private var stepsInBucket: Int = 0

    /**
     * Feed one cumulative counter sample stamped with phone wall time. Returns the buckets whose
     * total changed and should be persisted — at most the just-closed bucket plus the now-open
     * one on a rollover; empty when the sample only primes the baseline or adds nothing.
     */
    fun onSample(wallMs: Long, cumulative: Long): List<StepBucket> {
        require(cumulative >= 0L) { "TYPE_STEP_COUNTER is non-negative, got $cumulative" }
        val b = wallMs - Math.floorMod(wallMs, bucketMs)
        val delta: Long = when {
            lastCumulative == UNSET -> 0L                 // first sample: baseline only
            cumulative < lastCumulative -> cumulative     // reboot: counter reset 0..cumulative
            else -> cumulative - lastCumulative
        }
        lastCumulative = cumulative

        if (bucketStart == UNSET) {
            bucketStart = b
            stepsInBucket = 0
            return emptyList()
        }

        if (b > bucketStart) {
            val out = ArrayList<StepBucket>(2)
            out.add(StepBucket(bucketStart, stepsInBucket))   // finalize the previous bucket
            bucketStart = b
            stepsInBucket = delta.toInt()
            out.add(StepBucket(b, stepsInBucket))             // open the new bucket with its delta
            return out
        }

        if (delta == 0L) return emptyList()
        stepsInBucket += delta.toInt()
        return listOf(StepBucket(bucketStart, stepsInBucket))
    }

    /** The currently-open partial bucket's total, or `null` before the first sample. Lets a grid
     *  tick persist an in-progress bucket without a new sensor event. */
    fun peek(): StepBucket? =
        if (bucketStart == UNSET) null else StepBucket(bucketStart, stepsInBucket)

    private companion object {
        const val FIVE_MIN_MS = 300_000L
        const val UNSET = -1L
    }
}
