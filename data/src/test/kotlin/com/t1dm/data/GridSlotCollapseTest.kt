package com.t1dm.data

import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.db.CgmReadingEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** §3.1. */
class GridSlotCollapseTest {

    private fun reading(
        sourceId: String,
        tsMs: Long,
        bg: Int,
        rxWallMs: Long,
        provenance: ReadingProvenance = ReadingProvenance.MEASURED,
        flag: ReadingFlag = ReadingFlag.NORMAL,
    ) = CgmReadingEntity(
        sourceId = sourceId,
        tsMs = tsMs,
        bgMgdl = bg,
        trendTenthsPerMin = null,
        minFromStart = 120,
        quality = null,
        provenance = provenance,
        flag = flag,
        tzOffsetMin = 0,
        rxWallMs = rxWallMs,
        rssi = null,
    )

    @Test
    fun `a single source passes through untouched`() {
        val rows = listOf(
            reading("a", 300_000L, 100, 1L),
            reading("a", 600_000L, 110, 2L),
            reading("a", 900_000L, 120, 3L),
        )
        assertSame(rows, collapseByGridSlot(rows, "a"))
    }

    @Test
    fun `the selected source wins a contested slot even when its reading is older`() {
        val rows = listOf(
            reading("old", 300_000L, 137, rxWallMs = 9_000L),
            reading("new", 300_000L, 142, rxWallMs = 1_000L),
        )
        val out = collapseByGridSlot(rows, selectedSourceId = "new")
        assertEquals(1, out.size)
        assertEquals("new", out[0].sourceId)
        assertEquals(142, out[0].bgMgdl)
    }

    @Test
    fun `with neither source selected the newest reception wins`() {
        val rows = listOf(
            reading("a", 300_000L, 100, rxWallMs = 1_000L),
            reading("b", 300_000L, 105, rxWallMs = 2_000L),
        )
        assertEquals("b", collapseByGridSlot(rows, selectedSourceId = null).single().sourceId)
        assertEquals("b", collapseByGridSlot(rows, selectedSourceId = "gone").single().sourceId)
    }

    @Test
    fun `a reception-time tie resolves by source id, so the trace is a function of its input`() {
        val ab = listOf(
            reading("a", 300_000L, 100, rxWallMs = 5_000L),
            reading("b", 300_000L, 105, rxWallMs = 5_000L),
        )
        assertEquals("a", collapseByGridSlot(ab, null).single().sourceId)
        assertEquals("a", collapseByGridSlot(ab.reversed(), null).single().sourceId)
    }

    @Test
    fun `three sources in one slot reduce to one, and uncontested slots are preserved in order`() {
        val rows = listOf(
            reading("a", 300_000L, 100, 1L),
            reading("a", 600_000L, 101, 2L),
            reading("b", 600_000L, 102, 3L),
            reading("c", 600_000L, 103, 4L),
            reading("c", 900_000L, 104, 5L),
        )
        val out = collapseByGridSlot(rows, selectedSourceId = "b")
        assertEquals(listOf(300_000L, 600_000L, 900_000L), out.map { it.tsMs })
        assertEquals(listOf("a", "b", "c"), out.map { it.sourceId })
    }

    @Test
    fun `an empty window collapses to an empty window`() {
        assertEquals(emptyList<CgmReadingEntity>(), collapseByGridSlot(emptyList(), "a"))
    }

    /** §3.6. */
    @Test
    fun `a measuring sibling beats the selected sensor's warm-up reading`() {
        val rows = listOf(
            reading("retiring", 300_000L, 104, rxWallMs = 1_000L),
            reading("fresh", 300_000L, 210, rxWallMs = 2_000L, flag = ReadingFlag.WARMUP),
        )
        val out = collapseByGridSlot(rows, selectedSourceId = "fresh").single()
        assertEquals("retiring", out.sourceId)
        assertEquals(104, out.bgMgdl)
    }

    @Test
    fun `a measured sibling beats the selected sensor's interpolated fill`() {
        val rows = listOf(
            reading("other", 300_000L, 100, rxWallMs = 1_000L),
            reading("sel", 300_000L, 155, rxWallMs = 9_000L, provenance = ReadingProvenance.INTERPOLATED),
        )
        assertEquals("other", collapseByGridSlot(rows, selectedSourceId = "sel").single().sourceId)
    }

    @Test
    fun `selection still decides when both readings are equally real`() {
        val rows = listOf(
            reading("other", 300_000L, 100, rxWallMs = 9_000L),
            reading("sel", 300_000L, 142, rxWallMs = 1_000L),
        )
        assertEquals("sel", collapseByGridSlot(rows, selectedSourceId = "sel").single().sourceId)
    }

    @Test
    fun `two fabricated readings still resolve, and deterministically`() {
        val rows = listOf(
            reading("a", 300_000L, 100, rxWallMs = 1_000L, flag = ReadingFlag.WARMUP),
            reading("b", 300_000L, 105, rxWallMs = 2_000L, provenance = ReadingProvenance.INTERPOLATED),
        )
        // Neither is real: ranking falls through to selection, then reception time.
        assertEquals("a", collapseByGridSlot(rows, selectedSourceId = "a").single().sourceId)
        assertEquals("b", collapseByGridSlot(rows, selectedSourceId = null).single().sourceId)
    }
}
