package com.t1dm.ui.graph

import com.t1dm.core.model.ReconstructedBg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two interpolations the panel paints through, out of the composable so they can be pinned.
 *
 * Both exist because the states either side of them are DISCRETE — a selection snaps to whole
 * patches, a τ sweep steps a seven-level ladder — so what is being smoothed is how a jump is drawn,
 * never when it takes effect.
 */
class PanelTweenTest {

    private val patchMs = 6 * 300_000L
    private val t0 = 1_700_000_000_000L / patchMs * patchMs

    @Test
    fun a_selection_interpolates_both_edges_and_lands_exactly() {
        val from = MaskSelection(t0, t0 + 2 * patchMs)
        val to = MaskSelection(t0 + 4 * patchMs, t0 + 8 * patchMs)

        assertEquals(from, lerpSelection(from, to, 0f))
        assertEquals(to, lerpSelection(from, to, 1f))

        val mid = lerpSelection(from, to, 0.5f)!!
        assertEquals(t0 + 2 * patchMs, mid.startMs)
        assertEquals(t0 + 5 * patchMs, mid.endMs)
    }

    /**
     * Epoch milliseconds do not survive a `Float`, which is why the mix is in `Double`: the nearest
     * representable neighbours of a 2026 instant are about 130 ms apart, and the panel resolves far
     * finer than that.
     */
    @Test
    fun the_interpolation_keeps_millisecond_resolution() {
        val from = MaskSelection(t0, t0 + patchMs)
        val to = MaskSelection(t0 + 1000L, t0 + patchMs + 1000L)
        val mid = lerpSelection(from, to, 0.5f)!!
        assertEquals(t0 + 500L, mid.startMs)
    }

    /** Nothing to move to means nothing drawn; nothing to move FROM means it is simply there. */
    @Test
    fun a_missing_end_snaps_rather_than_interpolating() {
        val sel = MaskSelection(t0, t0 + patchMs)
        assertEquals(null, lerpSelection(sel, null, 0.5f))
        assertEquals(sel, lerpSelection(null, sel, 0.5f))
    }

    private fun row(ts: Long, mgdl: Double) = ReconstructedBg(
        tsMs = ts,
        mgdl = mgdl,
        lo90 = mgdl - 30,
        hi90 = mgdl + 30,
        modelId = "m.pte",
        spanStartMs = t0,
        promoted = false,
        bands = List(7) { k -> mgdl + (k - 3) * 10.0 },
    )

    @Test
    fun a_sweep_moves_only_the_line_and_leaves_the_emitted_fan_alone() {
        val from = listOf(row(t0, 100.0), row(t0 + 300_000L, 110.0))
        val to = listOf(row(t0, 140.0), row(t0 + 300_000L, 150.0))

        val mid = lerpReconstruction(from, to, 0.5f)
        assertEquals(listOf(120.0, 130.0), mid.map { it.mgdl })
        assertEquals("the fan is the same numbers throughout", to[0].bands, mid[0].bands)
        assertEquals(to[0].lo90, mid[0].lo90, 0.0)
    }

    /**
     * A different SET of slots is a fill landing or a span leaving, not a move. Sliding one set into
     * another would draw a curve through slots the model never spoke about, so the target is taken
     * whole.
     */
    @Test
    fun a_changed_slot_set_is_taken_whole() {
        val from = listOf(row(t0, 100.0))
        val to = listOf(row(t0, 140.0), row(t0 + 300_000L, 150.0))
        assertSame(to, lerpReconstruction(from, to, 0.5f))

        val moved = listOf(row(t0 + 600_000L, 140.0))
        assertSame(moved, lerpReconstruction(from, moved, 0.5f))

        assertFalse(sameSlots(from, to))
        assertFalse("an empty set has nothing to move", sameSlots(emptyList(), emptyList()))
        assertTrue(sameSlots(from, listOf(row(t0, 999.0))))
    }

    /** A slot with no finite value has nothing to interpolate towards; it is drawn as it stands. */
    @Test
    fun a_non_finite_slot_is_left_alone() {
        val from = listOf(row(t0, Double.NaN))
        val to = listOf(row(t0, 140.0))
        assertEquals(140.0, lerpReconstruction(from, to, 0.5f)[0].mgdl, 0.0)
    }
}
