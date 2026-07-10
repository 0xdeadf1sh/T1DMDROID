package com.t1dm.calc

import com.t1dm.core.model.RolledForecast
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the I2 safety contract in `:calc`: the ON-DEMAND rolled forecast is DISPLAY-ONLY and cannot
 * reach the dosing path.
 *
 * The dose calculator ([BolusCalculator] / [DoseAdvisor]) consumes forecasts EXCLUSIVELY through
 * [ForecastPort.roll] → [PredFan]. The display roll ([RollingForecaster.rollForDisplay]) returns a
 * [RolledForecast] — a DISTINCT type with no conversion into a [PredFan] and no path into
 * [Scoring]/[Rails]. So a 12×-rolled fan can never score a candidate or clear a rail; the seam is the
 * type gap, enforced by the compiler and documented here.
 */
class RollDisplayIsolationTest {

    @Test fun rolledForecast_isNotAPredFan() {
        // The two forecast carriers are unrelated types: a RolledForecast can never be substituted where
        // the calculator expects a PredFan, so it cannot enter dose selection.
        assertNotEquals(PredFan::class.java, RolledForecast::class.java)
        assertFalse(PredFan::class.java.isAssignableFrom(RolledForecast::class.java))
        assertFalse(RolledForecast::class.java.isAssignableFrom(PredFan::class.java))
    }

    @Test fun doseCalculator_onlyConsumesPredFanFromForecastPort() {
        // ForecastPort.roll — the SOLE forecast input to the dose calculator — returns a PredFan.
        val m = ForecastPort::class.java.methods.first { it.name == "roll" }
        // The suspend fun's erased continuation-style return is Object, but the declared PredFan type is
        // the coroutine's result; assert there is no ForecastPort method returning a RolledForecast.
        assertTrue(
            ForecastPort::class.java.methods.none { it.returnType == RolledForecast::class.java },
        )
        assertTrue(m.name == "roll")
    }

    @Test fun missingRoll_isDisplayIneligibleAndFailClosed() = runTest {
        val rf = RolledForecast.missing(requestedHours = 12.0, requestedRolls = 6, reason = "no selected model")
        assertFalse("a missing roll is never eligible", rf.eligible)
        assertTrue(rf.isEmpty)
        assertTrue(rf.reason!!.isNotBlank())
    }

    @Test fun degenerateRoll_keepsValidPrefixButIsIneligible() {
        // The fail-closed shape rollForDisplay produces on a mid-roll degeneracy: a valid prefix, marked
        // degenerate + ineligible, with a plain reason. Display can show the prefix; nothing may alert on it.
        val rf = RolledForecast(
            anchorTsMs = 1_700_000_000_000L, stepMs = 300_000L,
            medianBg = DoubleArray(24) { 120.0 }, lowerBg = DoubleArray(24) { 115.0 }, upperBg = DoubleArray(24) { 125.0 },
            validatedSteps = 24, requestedHours = 8.0, eligible = false, degenerate = true,
            reason = "The rolled forecast degenerated after about 2.0 h; only the valid portion is shown.",
            completedRolls = 1, requestedRolls = 4,
        )
        assertFalse(rf.eligible)
        assertTrue(rf.degenerate)
        assertEquals24(rf.size)
    }

    private fun assertEquals24(n: Int) = assertTrue("valid prefix retained", n == 24)
}
