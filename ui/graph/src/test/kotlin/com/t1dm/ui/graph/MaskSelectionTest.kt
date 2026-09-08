package com.t1dm.ui.graph

import com.t1dm.core.model.MaskGeometry
import org.junit.Assert.assertEquals
import org.junit.Test

class MaskSelectionTest {

    private val patchMs = 6 * 300_000L // 30 min
    private val t0 = 1_700_000_000_000L / patchMs * patchMs

    private fun controls(
        maxSpans: Int = 3,
        maxSpanPatches: Int = 4,
        maxMaskedPatches: Int = 8,
        newestMeasuredMs: Long = t0 + 40 * patchMs,
        contextFloorMs: Long = t0,
    ) = MaskControls(
        patchMs = patchMs,
        maxSpans = maxSpans,
        maxSpanPatches = maxSpanPatches,
        maxMaskedPatches = maxMaskedPatches,
        forecastPatches = 4,
        newestMeasuredMs = newestMeasuredMs,
        contextFloorMs = contextFloorMs,
    )

    @Test
    fun a_drag_snaps_to_absolute_patch_boundaries() {
        val c = controls()
        val from = t0 + 10 * patchMs + 7 * 60_000L
        val to = t0 + 11 * patchMs + 2 * 60_000L
        val out = selectionOf(from, to, c)!!

        assertEquals(t0 + 10 * patchMs, out.startMs)
        assertEquals(t0 + 12 * patchMs, out.endMs)
        assertEquals(2, out.patches(patchMs))
    }

    @Test
    fun a_drag_inside_one_patch_selects_it_whole() {
        val c = controls()
        val out = selectionOf(t0 + 5 * patchMs + 60_000L, t0 + 5 * patchMs + 120_000L, c)!!
        assertEquals(1, out.patches(patchMs))
        assertEquals(t0 + 5 * patchMs, out.startMs)
    }

    @Test
    fun direction_does_not_change_the_stretch() {
        val c = controls()
        assertEquals(
            selectionOf(t0 + 2 * patchMs, t0 + 4 * patchMs, c),
            selectionOf(t0 + 4 * patchMs, t0 + 2 * patchMs, c),
        )
    }

    @Test
    fun a_too_long_drag_is_clamped_to_the_models_own_cap() {
        val c = controls(maxSpanPatches = 4)
        val out = selectionOf(t0 + 2 * patchMs, t0 + 20 * patchMs, c)!!
        assertEquals(4, out.patches(patchMs))
        assertEquals(t0 + 2 * patchMs, out.startMs)
    }

    @Test
    fun a_backwards_drag_is_clamped_towards_its_own_anchor() {
        val c = controls(maxSpanPatches = 4)
        val out = selectionOf(t0 + 20 * patchMs, t0 + 2 * patchMs, c)!!
        assertEquals(4, out.patches(patchMs))
        assertEquals("the end the finger settled on survives", t0 + 21 * patchMs, out.endMs)
    }

    @Test
    fun a_degenerate_patch_size_selects_nothing() {
        val c = controls().copy(patchMs = 0L)
        assertEquals(null, selectionOf(t0, t0 + 10 * patchMs, c))
    }

    @Test
    fun the_total_budget_clamps_a_single_span_when_it_is_the_smaller_limit() {
        val c = controls(maxSpanPatches = 8, maxMaskedPatches = 3)
        assertEquals(3, selectionOf(t0, t0 + 20 * patchMs, c)!!.patches(patchMs))

        val other = controls(maxSpanPatches = 2, maxMaskedPatches = 12)
        assertEquals(2, selectionOf(t0, t0 + 20 * patchMs, other)!!.patches(patchMs))
    }

    @Test
    fun a_forecast_selection_is_clamped_to_the_horizon() {
        val newest = t0 + 10 * patchMs
        val c = controls(maxSpanPatches = 8, newestMeasuredMs = newest)
        val horizonEnd = newest + patchMs + 4 * patchMs // forecastPatches = 4 in `controls`

        val out = selectionOf(newest - patchMs, newest + 30 * patchMs, c)!!
        assertEquals(horizonEnd, out.endMs)
        assertEquals(MaskGeometry.FORECAST, geometryOf(out, c))

        val past = selectionOf(t0, t0 + 2 * patchMs, c)!!
        assertEquals(t0 + 3 * patchMs, past.endMs)
    }

    /** endMs is exclusive: anchor a left-handle drag on endMs-patchMs, or the right edge grows. */
    @Test
    fun a_left_handle_resize_holds_the_right_edge() {
        val c = controls(maxSpanPatches = 8)
        val sel = MaskSelection(t0 + 4 * patchMs, t0 + 8 * patchMs)

        val resized = selectionOf(sel.endMs - c.patchMs, t0 + 2 * patchMs, c)!!
        assertEquals("the right edge did not move", sel.endMs, resized.endMs)
        assertEquals(t0 + 2 * patchMs, resized.startMs)

        val other = selectionOf(sel.startMs, t0 + 10 * patchMs, c)!!
        assertEquals("the left edge did not move", sel.startMs, other.startMs)
        assertEquals(t0 + 11 * patchMs, other.endMs)
    }

    @Test
    fun the_geometry_is_derived_from_where_the_span_sits() {
        val c = controls(newestMeasuredMs = t0 + 40 * patchMs, contextFloorMs = t0)

        assertEquals(
            MaskGeometry.INFILL,
            geometryOf(MaskSelection(t0 + 5 * patchMs, t0 + 7 * patchMs), c),
        )
        assertEquals(
            "nothing brackets it on the left",
            MaskGeometry.BACKCAST,
            geometryOf(MaskSelection(t0, t0 + 2 * patchMs), c),
        )
        assertEquals(
            "it runs past the newest measurement",
            MaskGeometry.FORECAST,
            geometryOf(MaskSelection(t0 + 39 * patchMs, t0 + 42 * patchMs), c),
        )
        // Exclusive end: the last patch is bracketed on the right.
        assertEquals(
            MaskGeometry.INFILL,
            geometryOf(MaskSelection(t0 + 38 * patchMs, t0 + 40 * patchMs), c),
        )
    }
}
