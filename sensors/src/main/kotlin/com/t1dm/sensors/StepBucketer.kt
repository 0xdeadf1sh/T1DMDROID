package com.t1dm.sensors

/** bucketStartMs is grid-aligned; steps is the running total, written to sample.steps set/LWW. */
data class StepBucket(val bucketStartMs: Long, val steps: Int)

/** Folds cumulative TYPE_STEP_COUNTER into deltas, charging the newer bucket. Not thread-safe. */
class StepBucketer(private val bucketMs: Long = FIVE_MIN_MS) {

    private var lastCumulative: Long = UNSET
    private var bucketStart: Long = UNSET
    private var stepsInBucket: Int = 0

    /** Returns buckets whose total changed: at most just-closed plus now-open; empty if priming. */
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
