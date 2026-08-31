package com.t1dm.data.curve

import com.t1dm.data.T1dmRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
        // Round, not floor: SPEC §1 makes which of the two part of the contract.
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
        val second = doubleArrayOf(1.0, 3.0, 2.0, 0.4)
        val out = buckets(b0, second, written)
        assertEquals(4, out.size)
        assertEquals(listOf(0.5, 1.5, 1.0, 0.0), out.map { it.priorGrams })
        assertEquals(second.toList(), out.map { it.grams })
    }

    @Test
    fun `an unmoved slot is not rewritten`() {
        // The write would be a no-op that still mints the row and enqueues an INGEST push.
        val values = doubleArrayOf(0.5, 1.5, 1.0)
        val written = buckets(b0, values).associate { it.gridTs to it.grams }
        assertEquals(emptyList<Long>(), buckets(b0, values, written).map { it.gridTs })
        val moved = doubleArrayOf(0.5, 1.5, 2.0)
        assertEquals(listOf(b0 + 2 * grid), buckets(b0, moved, written).map { it.gridTs })
    }

    @Test
    fun `the curve extends past now, because a truncated one does not sum to its total`() {
        // 24 buckets: the bout's six plus §5's ninety-minute tail, most of them past now.
        val out = buckets(b0, DoubleArray(24) { 1.0 })
        assertEquals(b0 + 23 * grid, out.last().gridTs)
    }

    private companion object {
        const val EPS = 1e-9
    }
}
