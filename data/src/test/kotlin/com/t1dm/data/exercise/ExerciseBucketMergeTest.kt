package com.t1dm.data.exercise

import com.t1dm.data.T1dmRepository
import org.junit.Assert.assertEquals
import org.junit.Test

/** Grams of carbohydrate equivalent (`../T1DMCOMMON/SPEC/invariants.md` §3). */
class ExerciseBucketMergeTest {

    private fun merged(stored: Double?, prior: Double, grams: Double) =
        T1dmRepository.mergedExerciseGrams(stored, prior, grams)

    @Test
    fun `a bout writing a bucket nothing has touched writes its own grams`() {
        assertEquals(2.5, merged(stored = null, prior = 0.0, grams = 2.5), EPS)
    }

    @Test
    fun `re-laying the curve replaces this bout's own claim, never adds to it`() {
        // The curve is re-laid on every grid boundary the bout crosses; without the prior it accumulates.
        assertEquals(2.5, merged(stored = 2.5, prior = 2.5, grams = 2.5), EPS)
        assertEquals(4.0, merged(stored = 2.5, prior = 2.5, grams = 4.0), EPS)
        assertEquals(6.5, merged(stored = 4.0, prior = 4.0, grams = 6.5), EPS)
    }

    @Test
    fun `a second bout in the same bucket adds to the first instead of overwriting it`() {
        assertEquals(4.0, merged(stored = 1.5, prior = 0.0, grams = 2.5), EPS)
        assertEquals(5.5, merged(stored = 4.0, prior = 2.5, grams = 4.0), EPS)
    }

    @Test
    fun `there is no ceiling, because grams are not seconds`() {
        // Clamping would truncate a long bout's peak and break §5's sum rule.
        assertEquals(48.0, merged(stored = null, prior = 0.0, grams = 48.0), EPS)
        assertEquals(1_000.0, merged(stored = 700.0, prior = 0.0, grams = 300.0), EPS)
    }

    @Test
    fun `a stored value smaller than this bout's claim is not credited backwards`() {
        // The slot can re-materialise under a running bout; subtracting past zero would undo that.
        assertEquals(1.0, merged(stored = 0.2, prior = 3.0, grams = 1.0), EPS)
        assertEquals(0.0, merged(stored = null, prior = 3.0, grams = 0.0), EPS)
    }

    @Test
    fun `a negative claim is floored rather than subtracted`() {
        assertEquals(1.5, merged(stored = 1.5, prior = -1.0, grams = 0.0), EPS)
        assertEquals(1.5, merged(stored = 1.5, prior = 0.0, grams = -1.0), EPS)
    }

    @Test
    fun `a non-finite value reads as nothing rather than poisoning the column`() {
        // The column crosses the wire: a NaN would be authored server-side as a judgement.
        assertEquals(0.0, merged(stored = Double.NaN, prior = 0.0, grams = 0.0), EPS)
        assertEquals(2.0, merged(stored = 2.0, prior = Double.NaN, grams = 0.0), EPS)
        assertEquals(2.0, merged(stored = 2.0, prior = 0.0, grams = Double.POSITIVE_INFINITY), EPS)
    }

    private companion object {
        const val EPS = 1e-9
    }
}
