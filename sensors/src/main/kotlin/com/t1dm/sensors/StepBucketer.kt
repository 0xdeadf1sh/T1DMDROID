package com.t1dm.sensors

/** [bucketStartMs] is grid-aligned; [steps] is the bucket's running total, written to
 *  `sample.steps` with set/LWW semantics, never additively. */
data class StepBucket(val bucketStartMs: Long, val steps: Int)

/** Folds the cumulative `TYPE_STEP_COUNTER` into per-bucket deltas. The first sample only primes
 *  the baseline. A delta straddling a boundary is charged whole to the newer bucket, and an
 *  out-of-order stamp folds into the open one. Not thread-safe: one collector. */
class StepBucketer(private val bucketMs: Long = FIVE_MIN_MS) {

    private var lastCumulative: Long = UNSET
    private var bucketStart: Long = UNSET
    private var stepsInBucket: Int = 0

    /** Returns the buckets whose total changed: at most the just-closed one plus the now-open one;
     *  empty when the sample only primes the baseline or adds nothing. */
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
            out.add(StepBucket(bucketStart, stepsInBucket))
            bucketStart = b
            stepsInBucket = delta.toInt()
            out.add(StepBucket(b, stepsInBucket))
            return out
        }

        if (delta == 0L) return emptyList()
        stepsInBucket += delta.toInt()
        return listOf(StepBucket(bucketStart, stepsInBucket))
    }

    /** The open partial bucket, or null before the first sample. */
    fun peek(): StepBucket? =
        if (bucketStart == UNSET) null else StepBucket(bucketStart, stepsInBucket)

    private companion object {
        const val FIVE_MIN_MS = 300_000L
        const val UNSET = -1L
    }
}
