package com.t1dm.inference

import com.t1dm.core.model.MaskGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoraWindowPlanTest {

    private val min = 60_000L

    @Test
    fun onsets_within_15_min_chain_into_one_event_and_16_min_splits_them() {
        val on = longArrayOf(0, 10 * min, 25 * min, 41 * min)
        assertEquals(listOf(0L..25 * min, 41 * min..41 * min), eventClusters(on, 15 * min))
    }

    @Test
    fun no_onsets_no_events() {
        assertEquals(emptyList<LongRange>(), eventClusters(LongArray(0), 15 * min))
    }

    @Test
    fun a_forecast_opens_on_the_step_after_the_last_onset() {
        val shape = loraSpanShape(MaskGeometry.FORECAST, 8, 6, 24, 4)!!
        val w = eventWindowStart(MaskGeometry.FORECAST, shape, firstStep = 100, lastStep = 103)
        assertEquals(104, w + shape.spanOff)
        assertEquals(103, boundaryStep(MaskGeometry.FORECAST, shape, w))
    }

    @Test
    fun an_infill_opens_on_the_step_after_the_last_onset_inside_its_context() {
        val shape = loraSpanShape(MaskGeometry.INFILL, 8, 6, 24, 4)!!
        val w = eventWindowStart(MaskGeometry.INFILL, shape, firstStep = 100, lastStep = 103)
        assertEquals(104, w + shape.spanOff)
        assertEquals(103, boundaryStep(MaskGeometry.INFILL, shape, w))
        assertEquals(true, w + shape.spanOff + shape.spanLen <= w + shape.ctxSteps)
    }

    @Test
    fun a_backcast_closes_on_the_step_before_the_first_onset() {
        val shape = loraSpanShape(MaskGeometry.BACKCAST, 8, 6, 24, 4)!!
        val w = eventWindowStart(MaskGeometry.BACKCAST, shape, firstStep = 100, lastStep = 103)
        assertEquals(0, shape.spanOff)
        assertEquals(100, w + shape.spanLen)
        assertEquals(100, boundaryStep(MaskGeometry.BACKCAST, shape, w))
    }

    @Test
    fun a_context_too_short_for_the_span_poses_no_window() {
        assertNull(loraSpanShape(MaskGeometry.INFILL, 3, 6, 24, 4))
        assertNull(loraSpanShape(MaskGeometry.FORECAST, 8, 6, 0, 4))
    }
}
