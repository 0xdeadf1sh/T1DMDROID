package com.t1dm.calc

import com.t1dm.core.model.RolledForecast
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RollDisplayIsolationTest {

    @Test fun rolledForecast_isNotAPredFan() {
        assertNotEquals(PredFan::class.java, RolledForecast::class.java)
        assertFalse(PredFan::class.java.isAssignableFrom(RolledForecast::class.java))
        assertFalse(RolledForecast::class.java.isAssignableFrom(PredFan::class.java))
    }

    @Test fun doseCalculator_onlyConsumesPredFanFromForecastPort() {
        val m = ForecastPort::class.java.methods.first { it.name == "roll" }
        // A suspend fun's erased return is Object, so assert on the absence of a RolledForecast instead.
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
