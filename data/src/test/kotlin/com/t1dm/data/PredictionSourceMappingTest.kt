package com.t1dm.data

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.Precision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The sensor that conditioned a forecast survives the entity round-trip (Room v25).
 *
 * It is what `T1dmRepository.forecastWindows` refuses a cross-sensor window by, so a mapper that
 * dropped it would silently restore the defect: every window would read back as UNKNOWN, be refused,
 * and the band fit would go on refusing forever with nothing saying why.
 */
class PredictionSourceMappingTest {

    private fun prediction(sourceId: String?) = ModelPrediction(
        modelId = "m",
        cycleTsMs = 1_700_000_000_000L,
        anchorTsMs = 1_700_000_000_000L,
        sourceId = sourceId,
        stepMs = 300_000L,
        medianBg = List(2) { 120.0 },
        bandsMgdl = List(2 * 7) { 100.0 + it },
        nQuantiles = 7,
        lastBg = 118.0,
        status = ForecastStatus.OK,
        backend = BackendId.EXECUTORCH_XNNPACK_FP32,
        precision = Precision.FP32,
        selected = true,
        stale = false,
        latencyMs = 2.0,
    )

    @Test
    fun sourceIdSurvivesTheRoundTrip() {
        val back = prediction("vendorb:TESTSERIAL").toEntity(nowMs = 1L).toModel()
        assertEquals("vendorb:TESTSERIAL", back.sourceId)
        // The fan is transposed on the way in and back on the way out; the source rides beside it.
        assertEquals(prediction("vendorb:TESTSERIAL").bandsMgdl, back.bandsMgdl)
    }

    @Test
    fun anUnknownSourceStaysNullRatherThanBecomingAnEmptyString() {
        // Null is UNKNOWN and must never round-trip into a value that could equal a real source id
        // — "" compares equal to "" and would make two unstamped forecasts look like the same sensor.
        assertNull(prediction(null).toEntity(nowMs = 1L).toModel().sourceId)
        assertNull(prediction(null).toEntity(nowMs = 1L).sourceId)
    }
}
