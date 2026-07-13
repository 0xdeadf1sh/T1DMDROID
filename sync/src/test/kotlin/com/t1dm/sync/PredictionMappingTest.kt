package com.t1dm.sync

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.Precision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PredictionMappingTest {

    @Test
    fun toWriteTransposesStepMajorBandsToQuantileMajorFan() {
        val h = 2
        val nq = 7
        // bands[s·nQ + q] = 100 + s·10 + q  (step-major, τ-minor).
        val bands = DoubleArray(h * nq) { i -> val s = i / nq; val q = i % nq; 100.0 + s * 10 + q }
        val model = ModelPrediction(
            modelId = "m1",
            cycleTsMs = 300_000,
            anchorTsMs = 300_000,
            stepMs = 300_000,
            medianBg = listOf(103.0, 113.0),   // the q=3 row per step
            bandsMgdl = bands.asList(),
            nQuantiles = nq,
            lastBg = 100.0,
            status = ForecastStatus.OK,
            backend = BackendId.EXECUTORCH_XNNPACK_FP32,
            precision = Precision.FP32,
            selected = true,
            stale = false,
            latencyMs = 12.0,
        )

        val wire = model.toWrite(cycleTsMs = 300_000, nowMs = 1_700_000_000_000L)

        assertEquals("m1", wire.model_id)
        assertEquals("cycle ts carried verbatim as made_at", 300_000L, wire.made_at)
        assertEquals("phone wall clock carried verbatim as updated_at", 1_700_000_000_000L, wire.updated_at)
        assertEquals(2, wire.horizon_steps)
        assertEquals(7, wire.fan.size)                         // nQuantiles rows
        assertEquals(listOf(103.0, 113.0), wire.line)
        assertEquals(wire.line, wire.fan[3])                   // row 3 (0.5) == line
        assertEquals(listOf(100.0, 110.0), wire.fan[0])        // 0.05 row
        assertEquals(listOf(106.0, 116.0), wire.fan[6])        // 0.95 row
        assertNull("no time head ⇒ circadian is null, never a zeroed vector", wire.circadian)
    }
}
