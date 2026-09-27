package com.t1dm.inference

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BgSeriesAssemblyTest {

    @Test
    fun `a gap carries the last value and the series ends on whole patches`() = runTest {
        // 13 slots, slot 5 missing: 13 steps trims to 12, dropping the oldest.
        val rows = (0 until 13).filter { it != 5 }.map { reading(it, 100 + it) }.asReversed()

        val s = assembleBgSeries(rows, SRC, maxSteps = 100, minSteps = 6, withReconstructed = true) { _, _ -> emptyMap() }!!

        assertEquals(slot(1), s.gridStartMs)
        assertArrayEquals(
            doubleArrayOf(101.0, 102.0, 103.0, 104.0, 104.0, 106.0, 107.0, 108.0, 109.0, 110.0, 111.0, 112.0),
            s.mgdl,
            0.0,
        )
    }

    @Test
    fun `dosing drops a reconstruction and never reads a fill`() = runTest {
        val rows = ((0 until 11).map { reading(it, 100) } +
            reading(11, 250, ReadingProvenance.RECONSTRUCTED)).asReversed()
        var fillsRead = false

        val dosing = assembleBgSeries(rows, SRC, 100, 6, withReconstructed = false) { _, _ -> fillsRead = true; emptyMap() }!!

        assertEquals(slot(10), dosing.gridStartMs + (dosing.mgdl.size - 1) * 300_000L)
        assertEquals(100.0, dosing.mgdl.last(), 0.0)
        assertEquals(false, fillsRead)
        val inference = assembleBgSeries(rows, SRC, 100, 6, withReconstructed = true) { _, _ -> emptyMap() }!!
        assertEquals(250.0, inference.mgdl.last(), 0.0)
    }

    @Test
    fun `a fill stands in for an uncovered slot and the anchor stays on the last measurement`() = runTest {
        val rows = ((0 until 12).filter { it != 7 }.map { reading(it, 100) } +
            reading(12, 180, ReadingProvenance.INTERPOLATED)).asReversed()

        val s = assembleBgSeries(rows, SRC, 12, 6, withReconstructed = true) { _, _ -> mapOf(slot(7) to 140.0) }!!

        assertEquals(140.0, s.mgdl[6], 0.0)
        assertEquals(slot(11), s.anchorTsMs)
    }

    @Test
    fun `an empty first slot carries a margin row, never a later reading`() = runTest {
        // maxSteps 12 puts slot 0 before the window; slot 1 opens it empty.
        val rows = (listOf(reading(0, 180)) + (2..12).map { reading(it, 90) }).asReversed()

        val s = assembleBgSeries(rows, SRC, maxSteps = 12, minSteps = 6, withReconstructed = true) { _, _ -> emptyMap() }!!

        assertEquals(slot(1), s.gridStartMs)
        assertEquals(180.0, s.mgdl[0], 0.0)
        assertEquals(90.0, s.mgdl[1], 0.0)
    }

    @Test
    fun `too few rows is no series`() = runTest {
        val rows = (0 until 5).map { reading(it, 100) }.asReversed()
        assertNull(assembleBgSeries(rows, SRC, 100, 6, withReconstructed = true) { _, _ -> emptyMap() })
    }

    private fun slot(i: Int) = T0 + i * 300_000L

    private fun reading(i: Int, bg: Int, provenance: ReadingProvenance = ReadingProvenance.MEASURED) = CgmReading(
        sourceId = CgmSourceId(SRC),
        tsMs = slot(i),
        bgMgdl = bg,
        trendTenthsPerMin = null,
        minFromStart = null,
        quality = null,
        provenance = provenance,
        flag = ReadingFlag.NORMAL,
        tzOffsetMin = 0,
        rxWallMs = slot(i),
        rssi = null,
    )

    private companion object {
        const val SRC = "test:src"
        const val T0 = 1_700_000_100_000L
    }
}
