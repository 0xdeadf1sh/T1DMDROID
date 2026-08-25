package com.t1dm.alerts

import com.t1dm.core.model.AlertBand
import com.t1dm.core.model.AlertThresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmEngineConfigTest {

    private val default = AlarmConfig.DEFAULT // 55 / 70 / 180 / 250

    @Test
    fun `updateConfig does not clear or re-evaluate an active breach on the spot`() {
        val e = AlarmEngine(default)
        e.onReading(reading(50, rxWallMs = 0)) // urgent low
        val before = e.state.value.threshold
        assertEquals(AlertBand.URGENT_LOW, before!!.band)

        // Under the new bounds bg=50 would classify as LOW.
        e.updateConfig(default.copy(thresholds = AlertThresholds(45, 48, 180, 250)))
        assertSame("updateConfig must not re-evaluate the standing breach", before, e.state.value.threshold)
        assertEquals(AlertBand.URGENT_LOW, e.state.value.threshold!!.band)
    }

    @Test
    fun `the next reading re-classifies against the new thresholds without a gap in the breach`() {
        val e = AlarmEngine(default)
        e.onReading(reading(50, rxWallMs = 0)) // urgent low
        e.updateConfig(default.copy(thresholds = AlertThresholds(45, 48, 180, 250)))

        // bg=50 vs the new bounds (urgentLow 45, low 48) ⇒ IN_RANGE.
        e.onReading(reading(50, rxWallMs = 5 * MIN))
        assertNull(e.state.value.threshold)
    }

    @Test
    fun `a raised urgent-low threshold does not instantly clear a genuine low`() {
        val e = AlarmEngine(default)
        e.onReading(reading(50, rxWallMs = 0)) // urgent low at 50 (< 55)
        // bg=50 is still a genuine urgent low under the raised bound.
        e.updateConfig(default.copy(thresholds = AlertThresholds(60, 70, 180, 250)))
        assertEquals(AlertBand.URGENT_LOW, e.state.value.threshold!!.band)
        e.onReading(reading(50, rxWallMs = 5 * MIN))
        assertEquals(AlertBand.URGENT_LOW, e.state.value.threshold!!.band)
    }

    @Test
    fun `a lowered loss-of-signal window applies live to the running engine`() {
        val e = AlarmEngine(default) // default lossMin = 20
        e.onReading(reading(120, rxWallMs = 0))
        e.onTick(15 * MIN)
        assertNull("15 min < 20 min window ⇒ no loss yet", e.state.value.signalLoss)

        e.updateConfig(default.copy(lossMin = 10))
        e.onTick(15 * MIN) // 15 min >= the new 10 min window
        assertNotNull("the shortened window must fire on the running engine", e.state.value.signalLoss)
    }

    @Test
    fun `over-temp params apply live without fabricating a clear of a standing latch`() {
        val e = AlarmEngine(default) // alert 44, clear 41
        e.onTick(MIN, tempC = 45.0)
        assertNotNull(e.state.value.overTemperature)
        // The raised clear point must not fabricate a clear.
        e.updateConfig(default.copy(overTempClearC = 43.0))
        e.onTick(2 * MIN, tempC = 44.5) // still >= alert 44 ⇒ holds
        assertTrue(e.state.value.overTemperature != null)
    }
}
