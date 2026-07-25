package com.t1dm.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the cumulative -> per-5-min-delta bucketing (§3.5 / Phase 1),
 * including a mid-stream counter reset (reboot -> 0). Framework-free: [StepBucketer] is pure.
 */
class StepBucketerTest {

    private val bucket = 300_000L

    /** A clean bucket boundary so expected `bucketStartMs` values are obvious. */
    private val b0 = bucket * 5_000_000L        // 1_500_000_000_000
    private val b1 = b0 + bucket
    private val b2 = b1 + bucket

    @Test
    fun firstSampleOnlyPrimesBaseline() {
        val s = StepBucketer()
        assertEquals(emptyList<StepBucket>(), s.onSample(b0, 1000))
        // The pre-existing counter value is not our steps.
        assertEquals(StepBucket(b0, 0), s.peek())
    }

    @Test
    fun deltasWithinOneBucketAccumulateAsRunningTotal() {
        val s = StepBucketer()
        s.onSample(b0, 1000)                                   // prime
        assertEquals(listOf(StepBucket(b0, 10)), s.onSample(b0 + 10_000, 1010))
        assertEquals(listOf(StepBucket(b0, 25)), s.onSample(b0 + 20_000, 1025))
        assertEquals(StepBucket(b0, 25), s.peek())
    }

    @Test
    fun zeroDeltaWithinBucketEmitsNothing() {
        val s = StepBucketer()
        s.onSample(b0, 1000)
        s.onSample(b0 + 10_000, 1010)
        assertEquals(emptyList<StepBucket>(), s.onSample(b0 + 20_000, 1010))  // stationary
    }

    @Test
    fun rolloverFinalizesPreviousAndOpensNewBucket() {
        val s = StepBucketer()
        s.onSample(b0, 1000)                                   // prime
        s.onSample(b0 + 10_000, 1030)                          // b0 -> 30
        val out = s.onSample(b1 + 5_000, 1050)                 // 20 steps cross into b1
        assertEquals(listOf(StepBucket(b0, 30), StepBucket(b1, 20)), out)
        assertEquals(StepBucket(b1, 20), s.peek())
    }

    @Test
    fun resetWithinBucketCountsPostRebootStepsFromZero() {
        val s = StepBucketer()
        s.onSample(b0, 1000)                                   // prime
        s.onSample(b0 + 10_000, 1030)                          // b0 -> 30
        // Reboot: cumulative drops below the last value -> delta is the new cumulative (0..4).
        assertEquals(listOf(StepBucket(b0, 34)), s.onSample(b0 + 20_000, 4))
        // And the stream continues correctly from the new baseline.
        assertEquals(listOf(StepBucket(b0, 40)), s.onSample(b0 + 30_000, 10))
    }

    @Test
    fun resetAcrossBucketBoundary() {
        val s = StepBucketer()
        s.onSample(b0, 1000)                                   // prime
        s.onSample(b0 + 10_000, 1030)                          // b0 -> 30
        val out = s.onSample(b1 + 5_000, 7)                    // reboot into b1: delta = 7
        assertEquals(listOf(StepBucket(b0, 30), StepBucket(b1, 7)), out)
    }

    @Test
    fun spansThreeBucketsWithGaps() {
        val s = StepBucketer()
        s.onSample(b0, 500)                                    // prime
        assertEquals(listOf(StepBucket(b0, 12)), s.onSample(b0 + 60_000, 512))
        assertEquals(listOf(StepBucket(b0, 12), StepBucket(b1, 8)), s.onSample(b1 + 1_000, 520))
        assertEquals(listOf(StepBucket(b1, 8), StepBucket(b2, 3)), s.onSample(b2 + 1_000, 523))
    }

    @Test
    fun bucketStartsAreAlignedToTheFiveMinuteGrid() {
        val s = StepBucketer()
        s.onSample(1_600_000_137_123L, 100)                   // arbitrary non-aligned wall time, prime
        val out = s.onSample(1_600_000_137_123L + 400_000L, 130)
        // Both emitted bucket starts are exact multiples of 300_000 (grid-aligned).
        assertTrue(out.isNotEmpty())
        out.forEach { assertEquals(0L, it.bucketStartMs % 300_000L) }
    }

    @Test
    fun peekIsNullBeforeAnySample() {
        assertNull(StepBucketer().peek())
    }

    @Test
    fun customBucketWidthIsHonoured() {
        val minute = 60_000L
        val s = StepBucketer(bucketMs = minute)
        val t = minute * 100
        s.onSample(t, 0)                                       // prime
        assertEquals(listOf(StepBucket(t, 5)), s.onSample(t + 10_000, 5))
        assertEquals(listOf(StepBucket(t, 5), StepBucket(t + minute, 4)), s.onSample(t + minute + 1_000, 9))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeCumulativeRejected() {
        StepBucketer().onSample(b0, -1)
    }
}
