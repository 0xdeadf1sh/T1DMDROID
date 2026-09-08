package com.t1dm.inference

import com.t1dm.core.model.MaskGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** build_graph_input rule: last step left of the span, else FIRST step of the patch right. */
class LoraAnchorStepTest {

    private val patchSize = 6
    private val ctxPatches = 8
    private val ctxFrom = 120
    private val o = ctxFrom + ctxPatches * patchSize

    @Test
    fun a_forecast_window_anchors_on_the_last_context_step() {
        assertEquals(o - 1, anchorStepOf(ctxFrom, o, startPatch = null, lenPatches = 4, patchSize = patchSize))
    }

    @Test
    fun an_infill_anchors_on_the_step_before_its_span() {
        val start = LoraGeometryPlan.startPatch(MaskGeometry.INFILL, ctxPatches, 2)!!
        assertEquals(
            ctxFrom + start * patchSize - 1,
            anchorStepOf(ctxFrom, o, start, lenPatches = 2, patchSize = patchSize),
        )
    }

    /** `start * patchSize - 1` would read from outside the context. */
    @Test
    fun a_backcast_anchors_on_the_step_after_its_span() {
        val start = LoraGeometryPlan.startPatch(MaskGeometry.BACKCAST, ctxPatches, 3)!!
        assertEquals(0, start)
        assertEquals(
            ctxFrom + 3 * patchSize,
            anchorStepOf(ctxFrom, o, start, lenPatches = 3, patchSize = patchSize),
        )
    }

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

    @Test
    fun a_run_that_does_not_fit_has_no_start_patch() {
        assertNull(LoraGeometryPlan.startPatch(MaskGeometry.INFILL, 3, 2))
        assertNull(LoraGeometryPlan.startPatch(MaskGeometry.BACKCAST, 3, 3))
        assertNull(LoraGeometryPlan.startPatch(MaskGeometry.FORECAST, 8, 2))
    }
}
