package com.t1dm.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the bout -> per-5-min-bucket folding and the fix filter. Framework-free: [ExerciseBucketer]
 * is pure, and every metre here comes from its own haversine.
 */
class ExerciseBucketerTest {

    private val bucket = 300_000L

    /** A clean bucket boundary so expected `bucketStartMs` values are obvious. */
    private val b0 = bucket * 5_000_000L        // 1_500_000_000_000
    private val b1 = b0 + bucket

    /** ~11.12 m apart at this latitude — one step of the track, well inside every filter. */
    private val lat = 51.5
    private val lon = 0.0
    private val stepDeg = 0.0001
    private val stepM = 11.1195

    private fun fix(tsMs: Long, steps: Int = 0, accuracyM: Float = 5f) =
        ExerciseFix(tsMs, lat + steps * stepDeg, lon, accuracyM, null)

    @Test
    fun firstFixPrimesWithoutEmitting() {
        val b = ExerciseBucketer(b0)
        assertEquals(emptyList<ExerciseBucket>(), b.onFix(fix(b0 + 10_000)))
        // Accepted, so the bout HAS a track — of zero metres, which is not the same as no track.
        assertEquals(0.0, b.distanceM!!, 1e-9)
        assertEquals(10, b.activeSec)
    }

    @Test
    fun consecutiveFixesAccumulateMeasuredMetres() {
        val b = ExerciseBucketer(b0)
        b.onFix(fix(b0 + 4_000, steps = 0))
        b.onFix(fix(b0 + 8_000, steps = 1))
        b.onFix(fix(b0 + 12_000, steps = 2))
        assertEquals(2 * stepM, b.distanceM!!, 0.05)
    }

    @Test
    fun aBoutWithOnlyTicksStillEmitsSecondsAndNoDistance() {
        val b = ExerciseBucketer(b0)
        val out = b.onTick(b0 + 30_000)
        assertEquals(listOf(ExerciseBucket(b0, 30, null)), out)
        assertNull(b.distanceM)
    }

    @Test
    fun rolloverClosesThePreviousBucketAndOpensTheNext() {
        val b = ExerciseBucketer(b0 + 60_000)                  // 60 s into b0
        val out = b.onTick(b1 + 10_000)
        assertEquals(listOf(ExerciseBucket(b0, 240, null), ExerciseBucket(b1, 10, null)), out)
        assertEquals(250, b.activeSec)
    }

    @Test
    fun secondsClipAtTheBucketWidthAndAtTheBoutStart() {
        val whole = ExerciseBucketer(b0)
        assertEquals(300, whole.onTick(b1)[0].activeSec)       // a full bucket is 300 s, never more

        val late = ExerciseBucketer(b0 + 240_000)              // only the last minute of b0 is the bout's
        assertEquals(60, late.onTick(b1)[0].activeSec)
    }

    @Test
    fun theOpenBucketIsWithheldUntilItHoldsSomething() {
        // A bout stopping exactly on a boundary must not mint a grid row for a bucket it never entered.
        val b = ExerciseBucketer(b0)
        assertEquals(listOf(ExerciseBucket(b0, 300, null)), b.onTick(b1))
    }

    @Test
    fun distanceIsChargedToTheBucketTheFixArrivesIn() {
        val b = ExerciseBucketer(b0)
        b.onFix(fix(b0 + 290_000, steps = 0))
        val closed = b.onFix(fix(b1 + 4_000, steps = 1))
        // b0 closes with its 300 s and a track of zero metres; the segment belongs to b1.
        assertEquals(listOf(ExerciseBucket(b0, 300, 0.0)), closed)
        val open = b.peek()
        assertEquals(b1, open.bucketStartMs)
        assertEquals(4, open.activeSec)
        assertEquals(stepM, open.distanceM!!, 0.05)
        assertEquals(stepM, b.distanceM!!, 0.05)
    }

    @Test
    fun theIntervalTheMetresWereCoveredOverTravelsWithThem() {
        // b1 holds one segment's metres and must hold that segment's whole 14 s, not the 4 s it has
        // itself been open — those metres were covered over the 14, and dividing them by the 4 makes a
        // walk read as a vehicle.
        val b = ExerciseBucketer(b0)
        b.onFix(fix(b0 + 290_000, steps = 0))
        b.onFix(fix(b1 + 4_000, steps = 1))
        val open = b.peek()
        assertEquals(14_000L, open.trackedMs)
        assertEquals(4, open.activeSec)
    }

    @Test
    fun aPrimingFixAndARefusedFixBothCarryNoInterval() {
        val b = ExerciseBucketer(b0)
        b.onFix(fix(b0 + 10_000, steps = 0))
        assertEquals(0L, b.peek().trackedMs)                   // nothing yet to have measured over
        b.onFix(fix(b0 + 14_000, steps = 101))                 // 1112 m in 4 s: refused outright
        assertEquals(0L, b.peek().trackedMs)
        b.onFix(fix(b0 + 18_000, steps = 1))
        assertEquals(8_000L, b.peek().trackedMs)               // measured from the last fix BELIEVED
    }

    @Test
    fun theTrackedIntervalIsPerBucketAndAddsUpToTheBoutsOwnFixes() {
        val b = ExerciseBucketer(b0)
        var t = b0
        var steps = 0
        val tracked = LinkedHashMap<Long, Long>()
        while (t <= b1 + 100_000) {
            b.onFix(fix(t, steps)).forEach { tracked[it.bucketStartMs] = it.trackedMs }
            t += 4_000
            steps++
        }
        val open = b.peek()
        tracked[open.bucketStartMs] = open.trackedMs
        // Every fix but the first contributes its 4 s exactly once, to whichever bucket it landed in.
        assertEquals(t - 4_000 - b0, tracked.values.sum())
    }

    @Test
    fun aWildJumpIsRefusedAndDoesNotInflateTheTrack() {
        val b = ExerciseBucketer(b0)
        b.onFix(fix(b0 + 4_000, steps = 0))
        b.onFix(fix(b0 + 8_000, steps = 1))
        val before = b.distanceM!!
        // 100 steps ~ 1112 m in 4 s: 278 m/s, far above any running pace.
        assertEquals(emptyList<ExerciseBucket>(), b.onFix(fix(b0 + 12_000, steps = 101)))
        assertEquals(before, b.distanceM!!, 1e-9)
        // And the refusal is not remembered, so the next honest fix measures from the last good one.
        b.onFix(fix(b0 + 16_000, steps = 2))
        assertEquals(2 * stepM, b.distanceM!!, 0.05)
    }

    @Test
    fun aFixWiderThanTheAccuracyCeilingIsRefused() {
        val b = ExerciseBucketer(b0)
        b.onFix(fix(b0 + 4_000, steps = 0))
        b.onFix(fix(b0 + 8_000, steps = 1, accuracyM = 80f))
        assertEquals(0.0, b.distanceM!!, 1e-9)
    }

    @Test
    fun anOutOfOrderStampFoldsIntoTheOpenBucketRatherThanMisKeyingIt() {
        val b = ExerciseBucketer(b0)
        b.onTick(b1 + 10_000)                                  // the open bucket is now b1
        b.onFix(fix(b1 + 11_000, steps = 0))
        val distance = b.distanceM!!
        val seconds = b.activeSec

        assertEquals(emptyList<ExerciseBucket>(), b.onFix(fix(b0 + 200_000, steps = 5)))
        // A backwards tick accrues nothing and cannot re-open the bucket the stamp falls in.
        b.onTick(b0 + 200_000).forEach { assertEquals(b1, it.bucketStartMs) }
        assertEquals(b1, b.peek().bucketStartMs)
        assertEquals(distance, b.distanceM!!, 1e-9)
        assertEquals(seconds, b.activeSec)
    }

    @Test
    fun aFixBeforeTheBoutBeganIsRefused() {
        val b = ExerciseBucketer(b0 + 60_000)
        assertEquals(emptyList<ExerciseBucket>(), b.onFix(fix(b0 + 10_000)))
        assertNull(b.distanceM)
    }

    @Test
    fun bucketStartsAreAlignedToTheFiveMinuteGrid() {
        val b = ExerciseBucketer(1_600_000_137_123L)           // arbitrary non-aligned bout start
        val out = b.onTick(1_600_000_137_123L + 700_000L)
        assertTrue(out.isNotEmpty())
        out.forEach { assertEquals(0L, it.bucketStartMs % bucket) }
    }

    @Test
    fun activeSecondsAreExactlyTheSumOfTheBucketsWritten() {
        val b = ExerciseBucketer(b0 + 60_000)
        val written = LinkedHashMap<Long, Int>()
        for (t in listOf(b0 + 200_000L, b1 + 10_000L, b1 + 200_000L)) {
            b.onTick(t).forEach { written[it.bucketStartMs] = it.activeSec }
        }
        assertEquals(written.values.sum(), b.activeSec)
    }

    @Test
    fun theNewestAcceptedFixIsTheStalenessSignal() {
        val b = ExerciseBucketer(b0)
        assertNull(b.lastFix)
        val good = fix(b0 + 4_000)
        b.onFix(good)
        assertNotNull(b.lastFix)
        assertEquals(good.tsMs, b.lastFix!!.tsMs)
    }
}
