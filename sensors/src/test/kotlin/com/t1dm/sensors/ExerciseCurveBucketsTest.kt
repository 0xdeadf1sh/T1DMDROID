package com.t1dm.sensors

import com.t1dm.data.T1dmRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Laying a bout's resolved disposal curve on the five-minute grid, and diffing it against what the
 * same bout last wrote.
 *
 * Framework-free: the curve arrives already resolved, so nothing here needs Room or the JNI seam.
 * What it pins is the alignment — bucket 0 at the slot the session started in, on the grid the
 * repository's own `requireGrid` enforces — and the bookkeeping that keeps a rewrite idempotent.
 */
class ExerciseCurveBucketsTest {

    private val grid = T1dmRepository.GRID_MS
    private val b0 = grid * 5_000_000L

    private fun buckets(
        startMs: Long,
        values: DoubleArray,
        written: Map<Long, Double> = emptyMap(),
    ) = exerciseCurveBuckets(startMs, values, written) { 0 }

    @Test
    fun `bucket zero is the slot the session started in`() {
        val out = buckets(b0 + 60_000, doubleArrayOf(1.0, 2.0, 3.0))
        assertEquals(listOf(b0, b0 + grid, b0 + 2 * grid), out.map { it.gridTs })
    }

    @Test
    fun `the start is snapped to the NEAREST slot, as every other event on this grid is`() {
        // Round, not floor: SPEC §1 makes which of the two part of the contract, because both land on
        // the grid and both pass every validation while filing the same event in different buckets.
        assertEquals(b0, buckets(b0 + 2 * 60_000, doubleArrayOf(1.0)).first().gridTs)
        assertEquals(b0 + grid, buckets(b0 + 3 * 60_000, doubleArrayOf(1.0)).first().gridTs)
    }

    @Test
    fun `every slot lands on the grid`() {
        val out = buckets(b0 + 137_000, DoubleArray(30) { it + 1.0 })
        assertTrue(out.all { it.gridTs % grid == 0L })
    }

    @Test
    fun `a first write claims the whole curve and carries nothing before it`() {
        val values = doubleArrayOf(0.5, 1.5, 1.0)
        val out = buckets(b0, values)
        assertEquals(3, out.size)
        assertEquals(values.sum(), out.sumOf { it.grams }, EPS)
        assertTrue(out.all { it.priorGrams == 0.0 })
    }

    @Test
    fun `a rewrite carries what this bout last put in each slot`() {
        val first = doubleArrayOf(0.5, 1.5, 1.0)
        val written = buckets(b0, first).associate { it.gridTs to it.grams }
        // The bout ran longer, so the magnitude grew and every bucket moved with it.
        val second = doubleArrayOf(1.0, 3.0, 2.0, 0.4)
        val out = buckets(b0, second, written)
        assertEquals(4, out.size)
        assertEquals(listOf(0.5, 1.5, 1.0, 0.0), out.map { it.priorGrams })
        assertEquals(second.toList(), out.map { it.grams })
    }

    @Test
    fun `an unmoved slot is not rewritten`() {
        // Recording the same duration twice must cost nothing: the write would be a no-op on the
        // stored number and would still mint the row and enqueue an INGEST push for it.
        val values = doubleArrayOf(0.5, 1.5, 1.0)
        val written = buckets(b0, values).associate { it.gridTs to it.grams }
        assertEquals(emptyList<Long>(), buckets(b0, values, written).map { it.gridTs })
        // Only the slot that actually moved is filed.
        val moved = doubleArrayOf(0.5, 1.5, 2.0)
        assertEquals(listOf(b0 + 2 * grid), buckets(b0, moved, written).map { it.gridTs })
    }

    @Test
    fun `the curve extends past now, because a truncated one does not sum to its total`() {
        // A 30-minute bout is 24 buckets of curve — its own six plus §5's ninety-minute tail — so
        // eighteen of them are slots the clock has not reached. That is inherent to the definition,
        // not an accident of when the write happened.
        val out = buckets(b0, DoubleArray(24) { 1.0 })
        assertEquals(b0 + 23 * grid, out.last().gridTs)
    }

    private companion object {
        const val EPS = 1e-9
    }
}
