package com.t1dm.alerts

import com.t1dm.core.model.AlertBand
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ThresholdAlarmTest {

    private val config = AlarmConfig.DEFAULT // 55 / 70 / 180 / 250, clear margin 5

    private fun alarm() = ThresholdAlarm(config)

    @Test
    fun `each band is classified from a measured reading`() {
        assertEquals(AlertBand.URGENT_LOW, alarm().onReading(reading(50))!!.band)
        assertEquals(AlertBand.LOW, alarm().onReading(reading(65))!!.band)
        assertNull(alarm().onReading(reading(120)))
        assertEquals(AlertBand.HIGH, alarm().onReading(reading(200))!!.band)
        assertEquals(AlertBand.URGENT_HIGH, alarm().onReading(reading(300))!!.band)
    }

    @Test
    fun `band boundaries follow the classifier's strict-low, at-or-above-high semantics`() {
        assertEquals(AlertBand.URGENT_LOW, alarm().onReading(reading(54))!!.band)
        assertEquals(AlertBand.LOW, alarm().onReading(reading(55))!!.band)   // 55 not < 55
        assertEquals(AlertBand.LOW, alarm().onReading(reading(69))!!.band)
        assertNull(alarm().onReading(reading(70)))                            // 70 not < 70 ⇒ in range
        assertNull(alarm().onReading(reading(179)))
        assertEquals(AlertBand.HIGH, alarm().onReading(reading(180))!!.band)  // >= 180
        assertEquals(AlertBand.HIGH, alarm().onReading(reading(249))!!.band)
        assertEquals(AlertBand.URGENT_HIGH, alarm().onReading(reading(250))!!.band) // >= 250
    }

    @Test
    fun `urgent bands are CRITICAL and escalated, plain bands are WARNING`() {
        val urgentLow = alarm().onReading(reading(40))!!
        assertEquals(AlarmSeverity.CRITICAL, urgentLow.severity)
        assertEquals(true, urgentLow.escalated)

        val low = alarm().onReading(reading(65))!!
        assertEquals(AlarmSeverity.WARNING, low.severity)
        assertEquals(false, low.escalated)
    }

    @Test
    fun `a measured in-range reading clears a low`() {
        val a = alarm()
        assertEquals(AlertBand.URGENT_LOW, a.onReading(reading(50))!!.band)
        assertNull(a.onReading(reading(120))) // measured in-range clears
        assertNull(a.breach)
    }

    @Test
    fun `interpolated never clears a low`() {
        val a = alarm()
        a.onReading(reading(50)) // urgent low active
        a.onReading(reading(120, provenance = ReadingProvenance.INTERPOLATED))
        assertEquals(AlertBand.URGENT_LOW, a.breach!!.band) // still low
    }

    @Test
    fun `warmup never clears a low`() {
        val a = alarm()
        a.onReading(reading(50)) // urgent low active
        a.onReading(reading(120, flag = ReadingFlag.WARMUP))
        assertEquals(AlertBand.URGENT_LOW, a.breach!!.band) // still low
        // only a genuine MEASURED/NORMAL in-range reading clears it
        assertNull(a.onReading(reading(120)))
    }

    @Test
    fun `interpolated and warmup never raise an alarm`() {
        assertNull(alarm().onReading(reading(40, provenance = ReadingProvenance.INTERPOLATED)))
        assertNull(alarm().onReading(reading(40, flag = ReadingFlag.WARMUP)))
        assertNull(alarm().onReading(reading(40, flag = ReadingFlag.INVALID)))
    }

    @Test
    fun `noise at the low threshold keeps one breach`() {
        val a = alarm()
        val low = a.onReading(reading(69))!!
        assertSame(low, a.onReading(reading(71)))
        assertSame(low, a.onReading(reading(74)))
    }

    @Test
    fun `a reading at low plus the margin clears`() {
        val a = alarm()
        a.onReading(reading(69))
        assertNull(a.onReading(reading(75)))
    }

    @Test
    fun `noise at the urgent-low threshold stays urgent`() {
        val a = alarm()
        val urgent = a.onReading(reading(54))!!
        assertSame(urgent, a.onReading(reading(56)))
        assertEquals(AlertBand.LOW, a.onReading(reading(60))!!.band)
    }

    @Test
    fun `a jump past urgent-low but not past low steps down to low`() {
        val a = alarm()
        a.onReading(reading(50))
        val stepped = a.onReading(reading(72))!!
        assertEquals(AlertBand.LOW, stepped.band)
        assertEquals("72 mg/dL", stepped.message)
    }

    @Test
    fun `the high side mirrors the margin`() {
        val a = alarm()
        val high = a.onReading(reading(181))!!
        assertSame(high, a.onReading(reading(179)))
        assertSame(high, a.onReading(reading(175)))
        assertNull(a.onReading(reading(174)))

        val urgent = a.onReading(reading(251))!!
        assertSame(urgent, a.onReading(reading(249)))
        assertEquals(AlertBand.HIGH, a.onReading(reading(244))!!.band)
    }

    @Test
    fun `crossing to the other side switches at once`() {
        val a = alarm()
        a.onReading(reading(65))
        assertEquals(AlertBand.HIGH, a.onReading(reading(200))!!.band)
    }

    @Test
    fun `a worsening reading escalates the active band`() {
        val a = alarm()
        assertEquals(AlertBand.LOW, a.onReading(reading(65))!!.band)
        assertEquals(AlertBand.URGENT_LOW, a.onReading(reading(48))!!.band)
    }
}
