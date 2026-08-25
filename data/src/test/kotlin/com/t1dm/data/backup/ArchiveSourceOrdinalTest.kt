package com.t1dm.data.backup

import com.t1dm.data.db.CgmSourceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveSourceOrdinalTest {

    @Test
    fun `a restore onto an empty phone keeps every number the file carried`() {
        val incoming = listOf(row("a", 0), row("b", 1), row("c", 2))
        assertEquals(listOf(0, 1, 2), ArchiveReader.renumbered(emptyList(), incoming).map { it.ordinal })
    }

    @Test
    fun `a restore onto a phone using the same numbers never lands two sensors on one`() {
        val stored = listOf(row("x", 0), row("y", 1), row("z", 2))
        val incoming = listOf(row("a", 0), row("b", 1), row("c", 2))
        val out = ArchiveReader.renumbered(stored, incoming)
        assertEquals(listOf(3, 4, 5), out.map { it.ordinal })
        val all = stored.map { it.ordinal } + out.map { it.ordinal }
        assertEquals("every sensor keeps its own number", all.size, all.toSet().size)
    }

    @Test
    fun `a free number is kept and only the clash is moved`() {
        val out = ArchiveReader.renumbered(listOf(row("x", 0)), listOf(row("a", 0), row("b", 7)))
        assertEquals(listOf(1, 7), out.map { it.ordinal })
    }

    /** The row is IGNOREd by the merge insert, so the stored number stands. */
    @Test
    fun `re-importing the same file mints nothing`() {
        val stored = listOf(row("a", 0), row("b", 1))
        val out = ArchiveReader.renumbered(stored, listOf(row("a", 0), row("b", 1)))
        assertEquals(listOf(0, 1), out.map { it.ordinal })
    }

    /** -1 is what a file written before the column carries. */
    @Test
    fun `the unassigned sentinel is numbered rather than stored`() {
        val out = ArchiveReader.renumbered(listOf(row("x", 0)), listOf(row("a", -1), row("b", -1)))
        assertEquals(listOf(1, 2), out.map { it.ordinal })
        assertTrue(out.all { it.ordinal >= 0 })
    }

    @Test
    fun `a file whose own numbers collide is still numbered uniquely`() {
        val out = ArchiveReader.renumbered(emptyList(), listOf(row("a", 4), row("b", 4)))
        assertEquals(2, out.map { it.ordinal }.toSet().size)
    }

    private fun row(id: String, ordinal: Int) = CgmSourceEntity(
        sourceId = id,
        vendorId = "v",
        sensorModelId = "v:model",
        advertName = null,
        displayName = "d",
        serialSuffix = null,
        authoritative = false,
        active = false,
        warmupWindowMin = 60,
        addedAtMs = 1L,
        lastSeenMs = 2L,
        hidden = false,
        ordinal = ordinal,
    )
}
