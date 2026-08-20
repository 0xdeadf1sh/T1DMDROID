package com.t1dm.inference

import com.t1dm.core.model.MaskGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which step a replayed fit window anchors on.
 *
 * This mirrors `build_graph_input`'s own rule rather than restating it: left-preferring, the last
 * step of the patch to the span's left, and the FIRST step of the patch to its right when there is
 * no left one. Pinned here because the gate that keeps a promoted reconstruction out of a fit reads
 * exactly this step — and because the gate that replaced it read the whole context instead, which
 * rejected every window a real record has.
 */
class LoraAnchorStepTest {

    private val patchSize = 6
    private val ctxPatches = 8
    private val ctxFrom = 120
    private val o = ctxFrom + ctxPatches * patchSize

    /** A trailing forecast's left neighbour is the last context patch. */
    @Test
    fun a_forecast_window_anchors_on_the_last_context_step() {
        assertEquals(o - 1, anchorStepOf(ctxFrom, o, startPatch = null, lenPatches = 4, patchSize = patchSize))
    }

    /** An infill has a patch on its left, and the anchor is that patch's LAST step. */
    @Test
    fun an_infill_anchors_on_the_step_before_its_span() {
        val start = LoraGeometryPlan.startPatch(MaskGeometry.INFILL, ctxPatches, 2)!!
        assertEquals(
            ctxFrom + start * patchSize - 1,
            anchorStepOf(ctxFrom, o, start, lenPatches = 2, patchSize = patchSize),
        )
    }

    /**
     * A backcast has nothing on its left, so it anchors on the FIRST step of the patch to its
     * right. Anchoring it on `start * patchSize - 1` would read the step before the window — a
     * value from outside the context entirely.
     */
    @Test
    fun a_backcast_anchors_on_the_step_after_its_span() {
        val start = LoraGeometryPlan.startPatch(MaskGeometry.BACKCAST, ctxPatches, 3)!!
        assertEquals(0, start)
        assertEquals(
            ctxFrom + 3 * patchSize,
            anchorStepOf(ctxFrom, o, start, lenPatches = 3, patchSize = patchSize),
        )
    }

    /** Every anchor lands inside the window it belongs to. */
    @Test
    fun every_anchor_is_inside_its_own_context() {
        for (len in 1..3) {
            for (geometry in MaskGeometry.entries) {
                val start = LoraGeometryPlan.startPatch(geometry, ctxPatches, len)
                val step = anchorStepOf(ctxFrom, o, start, len, patchSize)
                assert(step in ctxFrom until o) { "$geometry len=$len anchored at $step" }
            }
        }
    }

    /** A context that cannot hold the run with a patch to spare poses no masked window at all. */
    @Test
    fun a_run_that_does_not_fit_has_no_start_patch() {
        assertNull(LoraGeometryPlan.startPatch(MaskGeometry.INFILL, 3, 2))
        assertNull(LoraGeometryPlan.startPatch(MaskGeometry.BACKCAST, 3, 3))
        assertNull(LoraGeometryPlan.startPatch(MaskGeometry.FORECAST, 8, 2))
    }
}
