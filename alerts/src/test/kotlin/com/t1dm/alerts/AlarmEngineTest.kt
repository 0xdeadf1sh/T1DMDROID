package com.t1dm.alerts

import com.t1dm.core.model.AlertBand
import com.t1dm.core.model.ReadingProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmEngineTest {

    private fun engine() = AlarmEngine(AlarmConfig.DEFAULT)

    @Test
    fun `state flow reflects a threshold breach and its primary`() {
        val e = engine()
        e.onReading(reading(50, rxWallMs = 0))
        val s = e.state.value
        assertTrue(s.isActive)
        assertEquals(AlertBand.URGENT_LOW, s.threshold!!.band)
        assertNull(s.signalLoss)
        assertTrue(s.primary is ThresholdBreach)
    }

    @Test
    fun `interpolated reading inside a gap never clears a low`() {
        val e = engine()
        e.onReading(reading(50, rxWallMs = 0))
        e.onReading(reading(120, rxWallMs = 5 * MIN, provenance = ReadingProvenance.INTERPOLATED))
        assertEquals(AlertBand.URGENT_LOW, e.state.value.threshold!!.band)
    }

    @Test
    fun `loss-of-signal surfaces through ticks and clears on a fresh reading`() {
        val e = engine()
        e.onReading(reading(120, rxWallMs = 0))
        assertNull(e.state.value.signalLoss)
        e.onTick(20 * MIN)
        assertTrue(e.state.value.signalLoss != null)
        e.onReading(reading(120, rxWallMs = 20 * MIN), nowMs = 20 * MIN)
        assertNull(e.state.value.signalLoss)
        assertFalse(e.state.value.isActive)
    }

    @Test
    fun `co-existing critical alarms tie-break to the threshold breach`() {
        val e = engine()
        // Urgent-low (CRITICAL) also makes the last reading "low" ⇒ escalated loss (CRITICAL).
        e.onReading(reading(50, rxWallMs = 0))
        e.onTick(12 * MIN)
        val s = e.state.value
        assertEquals(AlarmSeverity.CRITICAL, s.threshold!!.severity)
        assertEquals(AlarmSeverity.CRITICAL, s.signalLoss!!.severity)
        assertTrue(s.primary is ThresholdBreach) // threshold wins the severity tie
    }

    @Test
    fun `an escalated loss outranks a mere warning threshold`() {
        val e = engine()
        // HIGH (WARNING) but falling ⇒ escalated loss (CRITICAL) should be primary.
        e.onReading(reading(200, rxWallMs = 0, trendTenthsPerMin = -20))
        e.onTick(12 * MIN)
        val s = e.state.value
        assertEquals(AlarmSeverity.WARNING, s.threshold!!.severity)
        assertTrue(s.signalLoss!!.escalated)
        assertTrue(s.primary is SignalLoss)
    }

    @Test
    fun `over-temperature surfaces through onTick and clears on cooling`() {
        val e = engine() // default over-temp: alert 44, clear 41
        e.onTick(MIN, tempC = 44.0)
        assertNotNull(e.state.value.overTemperature)
        assertTrue(e.state.value.isActive)
        e.onTick(2 * MIN, tempC = 41.0)
        assertNull(e.state.value.overTemperature)
        assertFalse(e.state.value.isActive)
    }

    @Test
    fun `a critical threshold breach outranks a critical over-temperature`() {
        val e = AlarmEngine(AlarmConfig.DEFAULT.copy(overTempSeverity = AlarmSeverity.CRITICAL))
        e.onReading(reading(50, rxWallMs = 0)) // urgent low ⇒ CRITICAL threshold
        e.onTick(MIN, tempC = 45.0)            // CRITICAL over-temp
        val s = e.state.value
        assertEquals(AlarmSeverity.CRITICAL, s.threshold!!.severity)
        assertEquals(AlarmSeverity.CRITICAL, s.overTemperature!!.severity)
        assertTrue(s.primary is ThresholdBreach) // glucose beats device at equal severity
    }
}
