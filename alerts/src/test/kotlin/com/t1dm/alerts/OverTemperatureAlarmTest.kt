package com.t1dm.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OverTemperatureAlarmTest {

    private fun config(
        enabled: Boolean = true,
        alertC: Double = 44.0,
        clearC: Double = 41.0,
        severity: AlarmSeverity = AlarmSeverity.WARNING,
    ) = AlarmConfig.DEFAULT.copy(
        overTempEnabled = enabled,
        overTempAlertC = alertC,
        overTempClearC = clearC,
        overTempSeverity = severity,
    )

    @Test
    fun `fires at or above the alert threshold`() {
        val a = OverTemperatureAlarm(config())
        assertNull(a.evaluate(43.9, 0))
        val s = a.evaluate(44.0, MIN)
        assertNotNull(s)
        assertEquals(44.0, s!!.tempC, 0.0)
        assertEquals(44.0, s.alertC, 0.0)
        assertEquals(41.0, s.clearC, 0.0)
    }

    @Test
    fun `hysteresis holds between clear and alert, then clears at the clear point`() {
        val a = OverTemperatureAlarm(config())
        assertNotNull(a.evaluate(44.0, 0))       // fire
        assertNotNull(a.evaluate(42.0, MIN))     // in-band: hold
        assertNotNull(a.evaluate(41.5, 2 * MIN)) // just above clear: hold
        assertNull(a.evaluate(41.0, 3 * MIN))    // at clear: clears
    }

    @Test
    fun `does not fire in the hysteresis band from cold`() {
        val a = OverTemperatureAlarm(config())
        assertNull(a.evaluate(42.0, 0)) // between clear and alert, never fired ⇒ inert
    }

    @Test
    fun `null reading is inert and holds prior state`() {
        val a = OverTemperatureAlarm(config())
        a.evaluate(44.0, 0)
        assertNotNull(a.evaluate(null, MIN)) // holds the standing episode
        a.evaluate(41.0, 2 * MIN)            // cools ⇒ clears
        assertNull(a.evaluate(null, 3 * MIN)) // holds the cleared state
    }

    @Test
    fun `a disabled config never fires`() {
        val a = OverTemperatureAlarm(config(enabled = false))
        assertNull(a.evaluate(50.0, 0))
    }

    @Test
    fun `severity and escalation map from config`() {
        val warn = OverTemperatureAlarm(config()).evaluate(44.0, 0)!!
        assertEquals(AlarmSeverity.WARNING, warn.severity)
        assertTrue(!warn.escalated)
        val crit = OverTemperatureAlarm(config(severity = AlarmSeverity.CRITICAL)).evaluate(44.0, 0)!!
        assertEquals(AlarmSeverity.CRITICAL, crit.severity)
        assertTrue(crit.escalated)
    }

    @Test
    fun `keeps a stable episode object across ticks at the same severity`() {
        val a = OverTemperatureAlarm(config())
        val first = a.evaluate(45.0, 0)
        val second = a.evaluate(46.0, MIN)
        assertSame(first, second)
    }
}
