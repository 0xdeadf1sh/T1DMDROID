package com.t1dm.alerts

import com.t1dm.core.model.ReadingProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WeakSignalAlarmTest {

    // weakSignalDbm -90, sustain 3 min, lossMin 20 (the freshness bound for weak-signal).
    private val config = AlarmConfig.DEFAULT

    private fun alarm() = WeakSignalAlarm(config)

    @Test
    fun `never fires before an rssi-bearing reading is seen`() {
        assertNull(alarm().evaluate(nowMs = 60 * MIN))
    }

    @Test
    fun `a strong link never fires`() {
        val a = alarm()
        a.onReading(reading(120, rxWallMs = 0, rssi = -60))
        assertNull(a.evaluate(30 * MIN))
    }

    @Test
    fun `fires only after the weak signal is sustained`() {
        val a = alarm()
        a.onReading(reading(120, rxWallMs = 0, rssi = -95)) // below -90
        assertNull(a.evaluate(2 * MIN))                     // not sustained long enough
        val weak = a.evaluate(3 * MIN)
        assertNotNull(weak)
        assertEquals(-95, weak!!.rssiDbm)
        assertEquals(-90, weak.thresholdDbm)
        assertEquals(AlarmSeverity.WARNING, weak.severity)
    }

    @Test
    fun `a threshold-boundary rssi counts as weak (at-or-below)`() {
        val a = alarm()
        a.onReading(reading(120, rxWallMs = 0, rssi = -90)) // exactly at the threshold
        assertNotNull(a.evaluate(3 * MIN))
    }

    @Test
    fun `recovery clears the alarm and resets the sustain clock`() {
        val a = alarm()
        a.onReading(reading(120, rxWallMs = 0, rssi = -95))
        assertNotNull(a.evaluate(3 * MIN))
        a.onReading(reading(120, rxWallMs = 3 * MIN, rssi = -70)) // link recovered
        assertNull(a.weak)
        assertNull(a.evaluate(3 * MIN))
    }

    @Test
    fun `goes quiet when readings go stale (loss-of-signal owns it)`() {
        val a = alarm()
        a.onReading(reading(120, rxWallMs = 0, rssi = -95))
        assertNotNull(a.evaluate(3 * MIN))
        // No fresh reading for over lossMin (20) minutes — weak-signal defers to loss-of-signal.
        assertNull(a.evaluate(21 * MIN))
    }

    @Test
    fun `interpolated readings carry no rssi and never trigger it`() {
        val a = alarm()
        a.onReading(reading(120, rxWallMs = 0, provenance = ReadingProvenance.INTERPOLATED, rssi = null))
        assertNull(a.evaluate(10 * MIN))
    }

    @Test
    fun `disabled config never fires`() {
        val a = WeakSignalAlarm(config.copy(weakSignalEnabled = false))
        a.onReading(reading(120, rxWallMs = 0, rssi = -99))
        assertNull(a.evaluate(10 * MIN))
    }

    @Test
    fun `it surfaces through the engine state`() {
        val engine = AlarmEngine(config)
        engine.onReading(reading(120, rxWallMs = 0, rssi = -95), nowMs = 0)
        assertNull(engine.state.value.weakSignal) // not yet sustained
        engine.onTick(3 * MIN)
        assertNotNull(engine.state.value.weakSignal)
    }
}
