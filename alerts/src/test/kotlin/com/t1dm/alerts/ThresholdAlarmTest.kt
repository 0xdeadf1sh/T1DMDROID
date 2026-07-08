package com.t1dm.alerts

import com.t1dm.core.model.AlertBand
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThresholdAlarmTest {

    private val thresholds = AlarmConfig.DEFAULT.thresholds // 55 / 70 / 180 / 250

    private fun alarm() = ThresholdAlarm(thresholds)

    @Test
    fun `each band is classified from a measured reading`() {
        assertEquals(AlertBand.URGENT_LOW, alarm().onReading(reading(50))!!.band)
        assertEquals(AlertBand.LOW, alarm().onReading(reading(65))!!.band)
        assertNull(alarm().onReading(reading(120))) // IN_RANGE ⇒ no breach
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
    fun `a worsening reading escalates the active band`() {
        val a = alarm()
        assertEquals(AlertBand.LOW, a.onReading(reading(65))!!.band)
        assertEquals(AlertBand.URGENT_LOW, a.onReading(reading(48))!!.band)
    }
}
