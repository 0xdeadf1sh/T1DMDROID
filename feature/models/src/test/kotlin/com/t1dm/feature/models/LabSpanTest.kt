package com.t1dm.feature.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mask strip may only paint a set the model accepts: spans never overlap, never abut (one
 * visible patch must separate them, because that separator is what the anchor and the per-span
 * basis are defined against), and never take the final patch, whose visibility is what the
 * trailing forecast anchors on.
 *
 * Enforced while painting rather than at run time: a set refused after a run is a set the user
 * already believed in.
 */
class LabSpanTest {

    private fun spans(vararg pairs: Pair<Int, Int>) = pairs.map { LabSpan(it.first, it.second) }

    @Test
    fun `a painted span never takes the last patch`() {
        val out = addSpan(emptyList(), LabSpan(46, 4), contextPatches = 48)
        assertTrue("a span must leave the final patch visible", out.all { it.endPatch <= 47 })
    }

    @Test
    fun `an overlapping paint replaces what it covers`() {
        val out = addSpan(spans(10 to 2, 20 to 2), LabSpan(9, 5), contextPatches = 48)
        assertEquals(listOf(LabSpan(9, 5), LabSpan(20, 2)), out)
    }

    @Test
    fun `spans that would abut are not fused into one the user did not paint`() {
        // 10..11 painted, then 12..13: adjacent, so the later one is dropped rather than merged.
        val out = addSpan(spans(10 to 2), LabSpan(12, 2), contextPatches = 48)
        assertEquals(1, out.size)
        assertTrue("neighbours must stay separated by a visible patch", out.first().patches == 2)
    }

    @Test
    fun `a separated neighbour is kept`() {
        val out = addSpan(spans(10 to 2), LabSpan(13, 2), contextPatches = 48)
        assertEquals(spans(10 to 2, 13 to 2), out)
    }

    @Test
    fun `a zero-length paint adds nothing, and a paint off the end is clamped`() {
        assertEquals(emptyList<LabSpan>(), addSpan(emptyList(), LabSpan(5, 0), contextPatches = 48))
        // Painting the final patch slides onto the last legal one rather than being swallowed.
        assertEquals(spans(46 to 1), addSpan(emptyList(), LabSpan(47, 1), contextPatches = 48))
        assertEquals(emptyList<LabSpan>(), addSpan(emptyList(), LabSpan(0, 1), contextPatches = 1))
    }

    @Test
    fun `the state reports what the model would refuse`() {
        val base = LabUiState(
            modelId = "m",
            contextPatches = 168,
            maxMaskedPatches = 12,
            maxSpans = 3,
            maxSpanPatches = 8,
            realPatches = 168,
            forecastPatches = 4,
        )
        assertEquals(null, base.copy(spans = spans(10 to 4)).outOfDistribution)
        assertTrue(base.copy(spans = spans(10 to 9)).outOfDistribution!!.contains("span over 8"))
        assertTrue(
            base.copy(spans = spans(10 to 1, 20 to 1, 30 to 1, 40 to 1)).outOfDistribution!!.contains("4 spans"),
        )
        // The forecast span costs slots too, so the cap counts both.
        assertTrue(base.copy(spans = spans(10 to 8, 30 to 1)).blocked!!.contains("Over 12"))
        // Below the model's own context floor, only synthetic mode can run it.
        assertTrue(base.copy(realPatches = 40).blocked!!.contains("have 40"))
        assertEquals(null, base.copy(realPatches = 40, synthetic = true).blocked)
    }
}
