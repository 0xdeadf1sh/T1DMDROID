package com.t1dm.data.exercise

import com.t1dm.data.T1dmRepository
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How two bouts share one five-minute bucket of the exercise disposal channel.
 *
 * The failure this guards is quiet and one-directional: a bout's curve is rebuilt from scratch every
 * time its duration grows, so a plain set would let an ordinary stop-and-restart inside the same five
 * minutes silently discard the first bout's grams. The column syncs, so the loss travels.
 *
 * The unit is grams of carbohydrate equivalent (`../T1DMCOMMON/SPEC/invariants.md` §3), which is why
 * there is no ceiling here and why the values are fractional.
 */
class ExerciseBucketMergeTest {

    private fun merged(stored: Double?, prior: Double, grams: Double) =
        T1dmRepository.mergedExerciseGrams(stored, prior, grams)

    @Test
    fun `a bout writing a bucket nothing has touched writes its own grams`() {
        assertEquals(2.5, merged(stored = null, prior = 0.0, grams = 2.5), EPS)
    }

    @Test
    fun `re-laying the curve replaces this bout's own claim, never adds to it`() {
        // The curve is rewritten on every grid boundary the bout crosses. Without the prior it would
        // accumulate, and one bucket of a two-hour walk would read as a meal.
        assertEquals(2.5, merged(stored = 2.5, prior = 2.5, grams = 2.5), EPS)
        assertEquals(4.0, merged(stored = 2.5, prior = 2.5, grams = 4.0), EPS)
        assertEquals(6.5, merged(stored = 4.0, prior = 4.0, grams = 6.5), EPS)
    }

    @Test
    fun `a second bout in the same bucket adds to the first instead of overwriting it`() {
        // Stop at 09:07, restart at 09:08: bout 2 is recorded from zero and must not erase bout 1.
        assertEquals(4.0, merged(stored = 1.5, prior = 0.0, grams = 2.5), EPS)
        // And it keeps replacing only its own share as it grows.
        assertEquals(5.5, merged(stored = 4.0, prior = 2.5, grams = 4.0), EPS)
    }

    @Test
    fun `there is no ceiling, because grams are not seconds`() {
        // The column held whole active seconds once, and a bucket could not hold more than five
        // minutes of time. Grams have no such bound, and clamping one would truncate the peak of a
        // long bout's curve — which is to say break §5's rule that a curve sums to its event total.
        assertEquals(48.0, merged(stored = null, prior = 0.0, grams = 48.0), EPS)
        assertEquals(1_000.0, merged(stored = 700.0, prior = 0.0, grams = 300.0), EPS)
    }

    @Test
    fun `a stored value smaller than this bout's claim is not credited backwards`() {
        // The slot can be re-materialised under a running bout — an archive restore, or a server row
        // gap-filled into a bucket the phone had none for. Subtracting past zero would take that back
        // out again; the floor keeps what is there and adds to it.
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
        // A hand-edited kv row or a restored archive can produce one, and this column crosses the
        // wire — a NaN here would be authored onto the server as the phone's own judgement.
        assertEquals(0.0, merged(stored = Double.NaN, prior = 0.0, grams = 0.0), EPS)
        assertEquals(2.0, merged(stored = 2.0, prior = Double.NaN, grams = 0.0), EPS)
        assertEquals(2.0, merged(stored = 2.0, prior = 0.0, grams = Double.POSITIVE_INFINITY), EPS)
    }

    private companion object {
        const val EPS = 1e-9
    }
}
