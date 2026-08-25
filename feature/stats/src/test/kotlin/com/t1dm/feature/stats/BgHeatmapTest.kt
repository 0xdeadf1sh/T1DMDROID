package com.t1dm.feature.stats

import com.t1dm.core.model.HeatCell
import com.t1dm.core.model.HeatStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BgHeatmapTest {

    private fun c(mgdl: Double) = heatColor(mgdl)

    @Test fun greenIsTheMidpointAlone() {
        assertEquals(HEAT_IN, c(105.0))
        assertNotEquals(HEAT_IN, c(104.0))
        assertNotEquals(HEAT_IN, c(106.0))
    }

    @Test fun theExtremesClampAtTheScaleEdges() {
        assertEquals(HEAT_LOW, c(70.0))
        assertEquals(HEAT_LOW, c(54.0))
        assertEquals(HEAT_LOW, c(-5.0))
        assertEquals(HEAT_HIGH, c(140.0))
        assertEquals(HEAT_HIGH, c(400.0))
    }

    @Test fun eachArmMovesMonotonicallyTowardItsPole() {
        // Distance to the anchor, not any one channel: these ramps are not channel-monotone.
        fun dist(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color) =
            (a.red - b.red) * (a.red - b.red) +
                (a.green - b.green) * (a.green - b.green) +
                (a.blue - b.blue) * (a.blue - b.blue)

        var prev = Float.MAX_VALUE
        for (bg in 104 downTo 70) {
            val d = dist(c(bg.toDouble()), HEAT_LOW)
            assertTrue("$bg is no nearer blue than the value above it", d <= prev)
            prev = d
        }

        prev = Float.MAX_VALUE
        for (bg in 106..140) {
            val d = dist(c(bg.toDouble()), HEAT_HIGH)
            assertTrue("$bg is no nearer red than the value below it", d <= prev)
            prev = d
        }
    }

    @Test fun theScaleIsFixedAndIndependentOfTheTargetRange() {
        // 180 is in range for the default target and still reads as high here, by design.
        assertEquals(HEAT_HIGH, c(180.0))
        assertNotEquals(HEAT_IN, c(125.0))
    }

    @Test fun everyValueYieldsAFiniteColour() {
        for (bg in listOf(0.0, 69.0, 70.0, 105.0, 140.0, 141.0, 600.0)) {
            val col = c(bg)
            assertTrue("$bg gave a non-finite red channel", col.red.isFinite())
            assertTrue("$bg gave a non-finite green channel", col.green.isFinite())
            assertTrue("$bg gave a non-finite blue channel", col.blue.isFinite())
        }
    }

    @Test fun theChipSelectsWhichSummaryColoursTheCell() {
        // {100, 105, 400}: mean 201.67 is above the 140 ceiling, median 105 exactly on green.
        val mean = (100.0 + 105.0 + 400.0) / 3.0
        val cell = HeatCell(dow = 0, hour = 8, n = 3, meanBg = mean, medianBg = 105.0)
        assertEquals(105.0, cell.value(HeatStat.Median), 0.0)
        assertEquals(mean, cell.value(HeatStat.Mean), 0.0)
        assertEquals(HEAT_IN, c(cell.value(HeatStat.Median)))
        assertEquals(HEAT_HIGH, c(cell.value(HeatStat.Mean)))
    }

    @Test fun theThreeAnchorsAreDistinct() {
        // HEAT_IN vs HEAT_HIGH collapses under red-green blindness; separate them by lightness too.
        assertNotEquals(HEAT_IN, HEAT_HIGH)
        assertNotEquals(HEAT_IN, HEAT_LOW)
        fun luma(c: androidx.compose.ui.graphics.Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
        assertTrue("in-range is the darkest anchor", luma(HEAT_IN) < luma(HEAT_HIGH))
        assertTrue("in-range is the darkest anchor", luma(HEAT_IN) < luma(HEAT_LOW))
    }
}
