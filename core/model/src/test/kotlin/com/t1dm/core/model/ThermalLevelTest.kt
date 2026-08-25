package com.t1dm.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ThermalLevelTest {

    @Test fun nullThresholdIsAlwaysNormal() {
        assertEquals(ThermalLevel.NORMAL, thermalLevel(99.0, null, 3.0))
    }

    @Test fun boundariesForThreshold45Margin3() {
        val t = 45.0
        val m = 3.0
        assertEquals(ThermalLevel.NORMAL, thermalLevel(41.9, t, m))
        assertEquals(ThermalLevel.WARN, thermalLevel(42.0, t, m))
        assertEquals(ThermalLevel.WARN, thermalLevel(44.9, t, m))
        assertEquals(ThermalLevel.CRITICAL, thermalLevel(45.0, t, m))
    }

    @Test fun bandsAboveThresholdStayCritical() {
        assertEquals(ThermalLevel.CRITICAL, thermalLevel(50.0, 45.0, 3.0))
    }
}
