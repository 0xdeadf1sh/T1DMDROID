package com.t1dm.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Split conformal holds only under exchangeability, and nothing on device detects a change in
 * behaviour — so a correction is trusted for exactly the span it was fitted on and no longer.
 */
class BandCalibrationExpiryTest {

    private val day = 86_400_000L

    private fun calibration(fittedAtMs: Long, windowDays: Int) = BandCalibration(
        modelId = "m",
        delta = List(7) { 0.0 },
        steps = 1,
        nQuantiles = 7,
        nCal = 200,
        nEval = 86,
        maxAbsDeltaMgdl = 18.0,
        cov90Raw = 0.71,
        cov90Cal = 0.90,
        meanWidth90Raw = 62.0,
        meanWidth90Cal = 88.0,
        windowDays = windowDays,
        fittedAtMs = fittedAtMs,
    )

    @Test fun `it expires one fitting window after the fit, not on a constant of its own`() {
        val fitted = 1_700_000_000_000L
        assertEquals(fitted + 14 * day, calibration(fitted, 14).expiresAtMs)
        assertEquals(fitted + 30 * day, calibration(fitted, 30).expiresAtMs)
    }

    @Test fun `it is live right up to the boundary and dead from it`() {
        val fitted = 1_700_000_000_000L
        val cal = calibration(fitted, 14)
        assertFalse(cal.expiredAt(fitted))
        assertFalse(cal.expiredAt(cal.expiresAtMs - 1))
        assertTrue(cal.expiredAt(cal.expiresAtMs))
        assertTrue(cal.expiredAt(cal.expiresAtMs + day))
    }

    @Test fun `a longer fitting window buys proportionately longer life`() {
        val fitted = 1_700_000_000_000L
        val fortnight = calibration(fitted, 14)
        val month = calibration(fitted, 30)
        val at = fitted + 20 * day
        assertTrue(fortnight.expiredAt(at))
        assertFalse(month.expiredAt(at))
    }
}
