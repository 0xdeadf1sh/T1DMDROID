package com.t1dm.alerts

import com.t1dm.core.model.ReadingProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LossOfSignalAlarmTest {

    private val config = AlarmConfig.DEFAULT // lossMin 20, lossEscalatedMin 12, falling <= -10

    private fun alarm() = LossOfSignalAlarm(config)

    @Test
    fun `never fires before a first real reading is seen`() {
        // Nothing to have lost; even far in the future there is no signal-loss alarm.
        assertNull(alarm().evaluate(nowMs = 60 * MIN))
    }

    @Test
    fun `fires after the baseline window with no measured reading`() {
        val a = alarm()
        a.onReading(reading(120, rxWallMs = 0))
        assertNull(a.evaluate(19 * MIN))       // not yet
        val loss = a.evaluate(20 * MIN)
        assertNotNull(loss)
        assertEquals(20, loss!!.windowMin)
        assertEquals(AlarmSeverity.WARNING, loss.severity)
        assertEquals(false, loss.escalated)
    }

    @Test
    fun `escalates to the shorter window when the last real reading was low`() {
        val a = alarm()
        a.onReading(reading(60, rxWallMs = 0)) // LOW band
        assertNull(a.evaluate(11 * MIN))
        val loss = a.evaluate(12 * MIN)
        assertNotNull(loss)
        assertEquals(12, loss!!.windowMin)
        assertEquals(AlarmSeverity.CRITICAL, loss.severity)
        assertEquals(true, loss.escalated)
    }

    @Test
    fun `escalates when the last real reading was falling even if in range`() {
        val a = alarm()
        a.onReading(reading(120, rxWallMs = 0, trendTenthsPerMin = -15)) // in range but dropping
        assertNull(a.evaluate(11 * MIN))
        assertEquals(true, a.evaluate(12 * MIN)!!.escalated)
    }

    @Test
    fun `a flat in-range reading does not escalate`() {
        val a = alarm()
        a.onReading(reading(120, rxWallMs = 0, trendTenthsPerMin = 0))
        assertNull(a.evaluate(12 * MIN))                 // baseline window not yet lapsed
        assertEquals(false, a.evaluate(20 * MIN)!!.escalated)
    }

    @Test
    fun `a new measured reading clears loss and resets the clock`() {
        val a = alarm()
        a.onReading(reading(120, rxWallMs = 0))
        assertNotNull(a.evaluate(20 * MIN))
        a.onReading(reading(120, rxWallMs = 20 * MIN)) // fresh real reading
        assertNull(a.loss)
        assertNull(a.evaluate(20 * MIN))               // age now 0
    }

    @Test
    fun `interpolated gap-fill does not reset the clock`() {
        val a = alarm()
        a.onReading(reading(120, rxWallMs = 0))
        a.onReading(reading(120, rxWallMs = 5 * MIN, provenance = ReadingProvenance.INTERPOLATED))
        // Loss still measured from the last MEASURED reading (t=0), so it fires at 20 min.
        assertNotNull(a.evaluate(20 * MIN))
    }

    @Test
    fun `episode object is stable across ticks once fired`() {
        val a = alarm()
        a.onReading(reading(120, rxWallMs = 0))
        val first = a.evaluate(20 * MIN)
        val later = a.evaluate(25 * MIN)
        assertEquals(first, later) // same escalation ⇒ no churn
    }
}
