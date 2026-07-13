package com.t1dm.sync

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.Precision
import com.t1dm.core.model.PredictedTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #17 — the live circadian (time-of-day) head must ride the `PUT /v1/predictions` write as a nested
 * [CircadianDto] carrying `probs`/`predicted_hour`/`resultant_r`/`n_bins`/`bin_hours` losslessly, and
 * must serialize as `null` (never a zeroed 12-vector) when the model produced no time head.
 */
class WireMapperCircadianTest {

    private fun prediction(predictedTime: PredictedTime?): ModelPrediction {
        val h = 2
        val nq = 7
        val bands = DoubleArray(h * nq) { i -> 100.0 + i }
        return ModelPrediction(
            modelId = "m1",
            cycleTsMs = 300_000,
            anchorTsMs = 300_000,
            stepMs = 300_000,
            medianBg = listOf(103.0, 113.0),
            bandsMgdl = bands.asList(),
            nQuantiles = nq,
            lastBg = 100.0,
            status = ForecastStatus.OK,
            backend = BackendId.EXECUTORCH_XNNPACK_FP32,
            precision = Precision.FP32,
            selected = true,
            stale = false,
            latencyMs = 12.0,
            predictedTime = predictedTime,
        )
    }

    @Test
    fun liveTimeHeadRidesAsNestedCircadian() {
        val probs = List(12) { i -> i / 100.0 }
        val wire = prediction(PredictedTime(probs = probs, predictedHour = 7.5, resultantR = 0.8, nBins = 12, binHours = 2.0))
            .toWrite(cycleTsMs = 300_000, nowMs = 1_700_000_000_000L)

        assertEquals(300_000L, wire.made_at)
        assertEquals(1_700_000_000_000L, wire.updated_at)
        assertNotNull("a live time head must produce a circadian block", wire.circadian)
        val c = wire.circadian!!
        assertEquals(probs, c.probs)
        assertEquals(7.5, c.predicted_hour, 0.0)
        assertEquals(0.8, c.resultant_r, 0.0)
        assertEquals(12, c.n_bins)
        assertEquals(2.0, c.bin_hours, 0.0)
    }

    @Test
    fun absentTimeHeadSerializesAsNullNotZeroed() {
        val wire = prediction(predictedTime = null).toWrite(cycleTsMs = 300_000, nowMs = 1L)
        assertNull("no time head ⇒ circadian is null, never a zeroed 12-vector", wire.circadian)
    }
}
