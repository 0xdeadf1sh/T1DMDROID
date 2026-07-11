package com.t1dm.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ThermalLevelTest {

    @Test fun nullThresholdIsAlwaysNormal() {
        // Gate disabled ⇒ the chip must stay its normal color no matter how hot the device reads.
        assertEquals(ThermalLevel.NORMAL, thermalLevel(99.0, null, 3.0))
    }

    @Test fun boundariesForThreshold45Margin3() {
        val t = 45.0
        val m = 3.0
        assertEquals(ThermalLevel.NORMAL, thermalLevel(41.9, t, m))   // just below the warn band
        assertEquals(ThermalLevel.WARN, thermalLevel(42.0, t, m))     // warn band opens at threshold - margin
        assertEquals(ThermalLevel.WARN, thermalLevel(44.9, t, m))     // still below threshold
        assertEquals(ThermalLevel.CRITICAL, thermalLevel(45.0, t, m)) // at threshold ⇒ pause line
    }

    @Test fun bandsAboveThresholdStayCritical() {
        assertEquals(ThermalLevel.CRITICAL, thermalLevel(50.0, 45.0, 3.0))
    }
}
