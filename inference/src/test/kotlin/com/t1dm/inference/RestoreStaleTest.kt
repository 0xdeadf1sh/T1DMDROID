package com.t1dm.inference

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.nativecore.StubNativeCore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A restored forecast is aged against now, not trusted by the flag it was stored with. */
class RestoreStaleTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `a forecast anchored past the freshness limit restores ineligible`() = runTest {
        val controller = controller(anchorAgeMin = 16)
        controller.restoreLast(FIXTURE_NOW)
        assertFalse(controller.state.value.predictions.single().eligible)
    }

    @Test
    fun `a forecast anchored inside the limit restores eligible`() = runTest {
        val controller = controller(anchorAgeMin = 5)
        controller.restoreLast(FIXTURE_NOW)
        assertTrue(controller.state.value.predictions.single().eligible)
    }

    private fun controller(anchorAgeMin: Long) = InferenceController(
        native = StubNativeCore(),
        dispatchers = FixtureDispatchers(),
        store = ModelStore(tmp.newFolder("models"), StubNativeCore()),
        history = EmptyHistory,
        predictionStore = RecordingPredictions(listOf(prediction(FIXTURE_NOW - anchorAgeMin * 60_000L))),
    )

    private fun prediction(anchorTsMs: Long) = ModelPrediction(
        modelId = "m1",
        cycleTsMs = anchorTsMs,
        anchorTsMs = anchorTsMs,
        stepMs = 300_000L,
        medianBg = listOf(120.0, 121.0),
        bandsMgdl = listOf(110.0, 130.0, 111.0, 131.0),
        nQuantiles = 2,
        lastBg = 119.0,
        status = ForecastStatus.OK,
        backend = BackendId.EXECUTORCH_XNNPACK_FP32,
        selected = true,
        stale = false,
        latencyMs = 12.0,
    )
}
