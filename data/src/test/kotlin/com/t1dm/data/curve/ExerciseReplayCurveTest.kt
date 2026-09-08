package com.t1dm.data.curve

import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.core.nativecore.StubNativeCore
import com.t1dm.data.T1dmRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a replay puts in `sample.exercise` and delete removes; merge is ExerciseBucketMergeTest. */
class ExerciseReplayCurveTest {

    private val dispatchers = DefaultT1dmDispatchers(
        main = Dispatchers.Unconfined,
        default = Dispatchers.Unconfined,
        io = Dispatchers.Unconfined,
        inference = Dispatchers.Unconfined,
    )
    private val engine = CurveEngine(StubNativeCore(), dispatchers)
    private val grid = T1dmRepository.GRID_MS
    private val b0 = grid * 5_000_000L

    private suspend fun curve(durationMin: Double, rate: Double = 0.5): DoubleArray {
        val p = ExerciseDisposal.paramsFor(durationMin, rate)
        return engine.gamma(p.grams, p.k, p.theta, p.durationMin)
    }

    private fun merge(slots: MutableMap<Long, Double>, buckets: List<com.t1dm.data.ExerciseCurveBucket>) {
        for (b in buckets) {
            slots[b.gridTs] = T1dmRepository.mergedExerciseGrams(slots[b.gridTs], b.priorGrams, b.grams)
        }
    }

    @Test
    fun `a replay lays its whole magnitude, and a delete leaves nothing behind`() = runTest {
        val values = curve(45.0)
        val slots = HashMap<Long, Double>()
        merge(slots, exerciseCurveLaid(b0, values, TZ))
        assertEquals(22.5, slots.values.sum(), TOTAL_EPS)

        merge(slots, exerciseCurveTaken(b0, values, TZ))
        assertEquals(0.0, slots.values.sum(), EPS)
    }

    @Test
    fun `a delete leaves an overlapping bout's share exactly where it was`() = runTest {
        val values = curve(45.0)
        val slots = HashMap<Long, Double>()
        // A recorded bout already owns these slots; the replay is the second claim on them.
        val other = values.indices.associate { b0 + it * grid to 1.25 }
        slots.putAll(other)

        merge(slots, exerciseCurveLaid(b0, values, TZ))
        merge(slots, exerciseCurveTaken(b0, values, TZ))
        for ((ts, g) in other) assertEquals(g, slots.getValue(ts), EPS)
    }

    @Test
    fun `a shift moves the same curve, taking every gram out of the slots it left`() = runTest {
        val values = curve(30.0)
        val slots = HashMap<Long, Double>()
        merge(slots, exerciseCurveLaid(b0, values, TZ))

        val to = b0 + 12 * grid
        merge(slots, exerciseCurveTaken(b0, values, TZ))
        merge(slots, exerciseCurveLaid(to, values, TZ))

        // The magnitude is unchanged — only where it sits moved.
        assertEquals(15.0, slots.values.sum(), TOTAL_EPS)
        val occupied = slots.filterValues { it > 0.0 }.keys
        assertTrue("nothing may be left before the new start", occupied.all { it >= to })
    }

    @Test
    fun `the laid buckets sum to the magnitude, and the window is the bout plus ninety minutes`() =
        runTest {
            val p = ExerciseDisposal.paramsFor(60.0, 0.5)
            val buckets = exerciseCurveLaid(b0, curve(60.0), TZ)
            assertEquals(p.grams, buckets.sumOf { it.grams }, TOTAL_EPS)
            // 150 min at a 5-min grid.
            assertEquals(b0 + 29 * grid, buckets.last().gridTs)
        }

    private companion object {
        val TZ: (Long) -> Int = { 0 }
        const val EPS = 1e-9

        /** The gamma is sampled per bucket, so its sum lands near the total rather than on it. */
        const val TOTAL_EPS = 0.5
    }
}
