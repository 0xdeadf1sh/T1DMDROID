package com.t1dm.ui.graph

import com.t1dm.core.model.MaskGeometry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The mask selection arithmetic, out of the composable so it can be pinned as numbers.
 *
 * Every bound here comes from the model's own descriptor, so the tests state descriptor-shaped
 * numbers rather than round ones: a patch is six five-minute steps (30 min), which is what makes an
 * off-by-one in the snap visible as a half-hour rather than a pixel.
 */
class MaskSelectionTest {

    private val patchMs = 6 * 300_000L // 30 min
    private val t0 = 1_700_000_000_000L / patchMs * patchMs // on a boundary

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

    /** Boundaries are absolute, so a selection means the same thing however the panel is panned. */
    @Test
    fun a_drag_snaps_to_absolute_patch_boundaries() {
        val c = controls()
        val from = t0 + 10 * patchMs + 7 * 60_000L // 7 min into patch 10
        val to = t0 + 11 * patchMs + 2 * 60_000L // 2 min into patch 11
        val out = selectionOf(from, to, c)!!

        assertEquals(t0 + 10 * patchMs, out.startMs)
        // End is exclusive and covers the patch the drag ended in.
        assertEquals(t0 + 12 * patchMs, out.endMs)
        assertEquals(2, out.patches(patchMs))
    }

    /** A drag entirely inside one patch still selects that whole patch — a mask is patch-shaped. */
    @Test
    fun a_drag_inside_one_patch_selects_it_whole() {
        val c = controls()
        val out = selectionOf(t0 + 5 * patchMs + 60_000L, t0 + 5 * patchMs + 120_000L, c)!!
        assertEquals(1, out.patches(patchMs))
        assertEquals(t0 + 5 * patchMs, out.startMs)
    }

    /** A drag is the same stretch whichever way the finger travelled. */
    @Test
    fun direction_does_not_change_the_stretch() {
        val c = controls()
        assertEquals(
            selectionOf(t0 + 2 * patchMs, t0 + 4 * patchMs, c),
            selectionOf(t0 + 4 * patchMs, t0 + 2 * patchMs, c),
        )
    }

    /**
     * Clamped, not refused. A user asking for a longer span than the sampler ever drew gets the
     * longest one it did draw; a refusal at the end of a drag reads as a broken gesture.
     */
    @Test
    fun a_too_long_drag_is_clamped_to_the_models_own_cap() {
        val c = controls(maxSpanPatches = 4)
        val out = selectionOf(t0 + 2 * patchMs, t0 + 20 * patchMs, c)!!
        assertEquals(4, out.patches(patchMs))
        assertEquals(t0 + 2 * patchMs, out.startMs)
    }

    /**
     * The clamp keeps the end the FINGER stopped on. Growing rightwards out of a right-to-left drag
     * is the opposite of what the hand just asked for.
     */
    @Test
    fun a_backwards_drag_is_clamped_towards_its_own_anchor() {
        val c = controls(maxSpanPatches = 4)
        val out = selectionOf(t0 + 20 * patchMs, t0 + 2 * patchMs, c)!!
        assertEquals(4, out.patches(patchMs))
        assertEquals("the end the finger settled on survives", t0 + 21 * patchMs, out.endMs)
    }

    /** A model with no patch size cannot bound anything, so nothing is selected. */
    @Test
    fun a_degenerate_patch_size_selects_nothing() {
        val c = controls().copy(patchMs = 0L)
        assertEquals(null, selectionOf(t0, t0 + 10 * patchMs, c))
    }

    /**
     * The binding cap is the SMALLER of the two the descriptor states.
     *
     * One span spends the whole masked budget on its own, so a run longer than that budget is
     * refused by the runner however short the sampler's envelope allows. Clamping to the envelope
     * alone let a selection be drawn that could only ever come back as a message.
     */
    @Test
    fun the_total_budget_clamps_a_single_span_when_it_is_the_smaller_limit() {
        val c = controls(maxSpanPatches = 8, maxMaskedPatches = 3)
        assertEquals(3, selectionOf(t0, t0 + 20 * patchMs, c)!!.patches(patchMs))

        val other = controls(maxSpanPatches = 2, maxMaskedPatches = 12)
        assertEquals(2, selectionOf(t0, t0 + 20 * patchMs, other)!!.patches(patchMs))
    }

    /**
     * A stretch reaching past the newest measurement is a forecast, and a forecast's length is the
     * descriptor's horizon whatever the drag said — so the highlight stops there rather than
     * describing a run nothing will make.
     */
    @Test
    fun a_forecast_selection_is_clamped_to_the_horizon() {
        val newest = t0 + 10 * patchMs
        val c = controls(maxSpanPatches = 8, newestMeasuredMs = newest)
        val horizonEnd = newest + patchMs + 4 * patchMs // forecastPatches = 4 in `controls`

        val out = selectionOf(newest - patchMs, newest + 30 * patchMs, c)!!
        assertEquals(horizonEnd, out.endMs)
        assertEquals(MaskGeometry.FORECAST, geometryOf(out, c))

        // A stretch that stays in the past is untouched by this clamp.
        val past = selectionOf(t0, t0 + 2 * patchMs, c)!!
        assertEquals(t0 + 3 * patchMs, past.endMs)
    }

    /**
     * A handle drag anchors on the opposite edge INCLUSIVELY.
     *
     * `endMs` is exclusive and `selectionOf` treats a boundary as lying inside the patch that starts
     * there, so handing it `endMs` grew the untouched right edge by a patch on every left-handle
     * resize. The last INCLUDED patch's start is `endMs - patchMs`, and anchoring there holds the
     * right edge still.
     */
    @Test
    fun a_left_handle_resize_holds_the_right_edge() {
        val c = controls(maxSpanPatches = 8)
        val sel = MaskSelection(t0 + 4 * patchMs, t0 + 8 * patchMs)

        val resized = selectionOf(sel.endMs - c.patchMs, t0 + 2 * patchMs, c)!!
        assertEquals("the right edge did not move", sel.endMs, resized.endMs)
        assertEquals(t0 + 2 * patchMs, resized.startMs)

        // The right handle anchors on `startMs`, which is inclusive already.
        val other = selectionOf(sel.startMs, t0 + 10 * patchMs, c)!!
        assertEquals("the left edge did not move", sel.startMs, other.startMs)
        assertEquals(t0 + 11 * patchMs, other.endMs)
    }

    /** The geometry is a fact about where the span sits, never a choice. */
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
        // A span ending exactly AT the newest measurement is still an infill: the boundary is
        // exclusive, so its last patch is bracketed on the right.
        assertEquals(
            MaskGeometry.INFILL,
            geometryOf(MaskSelection(t0 + 38 * patchMs, t0 + 40 * patchMs), c),
        )
    }
}
