package com.t1dm.data.backup

import com.t1dm.data.db.CgmSourceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one column of `cgm_source` an archive restore may not copy verbatim.
 *
 * `ordinal` is what names a sensor on every surface outside the CGM panel once sensor names are hidden,
 * which is the default — so two rows sharing one number means `CGM #1` names two physical devices, and
 * permanently, since the number is never revised after it is minted. A restore inserts into the table
 * directly and so misses the mint that keeps it unique; this is what stands in for it.
 *
 * Pure, so it is pinned here rather than in the instrumented round-trip test: the rule is arithmetic on
 * two lists and needs no database to be wrong in.
 */
class ArchiveSourceOrdinalTest {

    @Test
    fun `a restore onto an empty phone keeps every number the file carried`() {
        val incoming = listOf(row("a", 0), row("b", 1), row("c", 2))
        assertEquals(listOf(0, 1, 2), ArchiveReader.renumbered(emptyList(), incoming).map { it.ordinal })
    }

    /** The failure this exists for: three numbers, six sensors, one label each. */
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

    /**
     * A sensor the phone already holds is passed through and claims nothing.
     *
     * Its row is IGNOREd by the merge insert, so the stored number stands. Minting one for it anyway would
     * count the phone's numbers up by the size of the file every time the same file was imported.
     */
    @Test
    fun `re-importing the same file mints nothing`() {
        val stored = listOf(row("a", 0), row("b", 1))
        val out = ArchiveReader.renumbered(stored, listOf(row("a", 0), row("b", 1)))
        assertEquals(listOf(0, 1), out.map { it.ordinal })
    }

    /** A file written before the column existed carries the sentinel, and no sensor may reach the UI
     *  with one: it has no number to print at all. */
    @Test
    fun `the unassigned sentinel is numbered rather than stored`() {
        val out = ArchiveReader.renumbered(listOf(row("x", 0)), listOf(row("a", -1), row("b", -1)))
        assertEquals(listOf(1, 2), out.map { it.ordinal })
        assertTrue(out.all { it.ordinal >= 0 })
    }

    /** A file need not be well formed. Two rows claiming one number is not a reason to write it twice. */
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
