package com.t1dm.data.curve

import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.core.nativecore.StubNativeCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the self-describing bolus-logging path (Phase 4, PLAN §3.3). The insulin entry surface
 * stores `CurveEngine.Presets.bolusGammaParams(dose)` on the `logged_dose` row, and `RoomDoseStore`
 * later reconstructs the curve via `gamma(units, k, theta, durMin)`. That reconstruction MUST equal
 * the authoritative `bolusPkForDose(dose)` the model/calculator would otherwise see — otherwise a
 * logged bolus would shift the forecast differently than the same bolus announced live. This test
 * pins that equality across the dose range (and the reference/scaling behaviour of the params).
 */
class BolusParamsTest {

    private val dispatchers = DefaultT1dmDispatchers(
        main = Dispatchers.Unconfined,
        default = Dispatchers.Unconfined,
        io = Dispatchers.Unconfined,
        inference = Dispatchers.Unconfined,
    )
    private val engine = CurveEngine(StubNativeCore(), dispatchers)

    @Test
    fun params_at_reference_dose() {
        val (k, theta, dur) = CurveEngine.Presets.bolusGammaParams(5.0)
        assertEquals(3.0, k, 0.0)
        assertEquals(25.0, theta, 1e-12)          // no √-excess drift at the 5 U reference
        assertEquals(2.5 * 60.0, dur, 1e-12)      // BOLUS_DIA_BASE_HOURS · 60
    }

    @Test
    fun dia_and_theta_increase_with_dose() {
        val small = CurveEngine.Presets.bolusGammaParams(2.0)
        val big = CurveEngine.Presets.bolusGammaParams(12.0)
        assertTrue("θ grows with dose", big.second > small.second)
        assertTrue("DIA grows with dose", big.third > small.third)
        assertTrue("DIA clamped ≤ 7.5 h", CurveEngine.Presets.bolusGammaParams(40.0).third <= 7.5 * 60.0 + 1e-9)
    }

    @Test
    fun reconstruction_matches_authoritative_bolus_pk() = runTest {
        for (dose in listOf(0.5, 1.0, 3.5, 5.0, 8.0, 12.0, 20.0)) {
            val (k, theta, dur) = CurveEngine.Presets.bolusGammaParams(dose)
            val reconstructed = engine.gamma(dose, k, theta, dur)   // the RoomDoseStore path
            val authoritative = engine.bolusPk(dose).values          // == simulator.bolus_pk_for_dose
            assertEquals("length @${dose}U", authoritative.size, reconstructed.size)
            for (i in authoritative.indices) {
                assertEquals("step $i @${dose}U", authoritative[i], reconstructed[i], 1e-12)
            }
        }
    }
}
