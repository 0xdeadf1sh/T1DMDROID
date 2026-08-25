package com.t1dm.feature.stats

import com.t1dm.core.model.ClinicalCuts
import com.t1dm.core.model.HeatCell
import com.t1dm.core.model.HeatStat
import com.t1dm.core.model.TargetRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BgHeatmapTest {

    private val cuts = ClinicalCuts(veryLowMgdl = 54.0, veryHighMgdl = 250.0)
    private val target = TargetRange(70, 180)

    private fun c(mgdl: Double) = heatColor(mgdl, target, cuts)

    @Test fun theWholeTargetRangeIsOneFlatGreen() {
        assertEquals(HEAT_IN, c(70.0))
        assertEquals(HEAT_IN, c(125.0))
        assertEquals(HEAT_IN, c(180.0))
    }

    @Test fun theExtremesClampAtTheClinicalCuts() {
        assertEquals(HEAT_LOW, c(54.0))
        assertEquals(HEAT_LOW, c(20.0))
        assertEquals(HEAT_LOW, c(-5.0))
        assertEquals(HEAT_HIGH, c(250.0))
        assertEquals(HEAT_HIGH, c(400.0))
    }

    @Test fun eachArmMovesMonotonicallyTowardItsPole() {
        // Distance to the anchor, not any one channel: these ramps are not channel-monotone.
        fun dist(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color) =
            (a.red - b.red) * (a.red - b.red) +
                (a.green - b.green) * (a.green - b.green) +
                (a.blue - b.blue) * (a.blue - b.blue)

        var prev = Float.MAX_VALUE
        for (bg in 69 downTo 54) {
            val d = dist(c(bg.toDouble()), HEAT_LOW)
            assertTrue("$bg is no nearer blue than the value above it", d <= prev)
            prev = d
        }
        assertNotEquals(HEAT_IN, c(69.0))

        prev = Float.MAX_VALUE
        for (bg in 181..250) {
            val d = dist(c(bg.toDouble()), HEAT_HIGH)
            assertTrue("$bg is no nearer red than the value below it", d <= prev)
            prev = d
        }
        assertNotEquals(HEAT_IN, c(181.0))
    }

    @Test fun theRampFollowsTheUsersTargetRatherThanFixedEdges() {
        assertNotEquals(HEAT_IN, c(190.0))
        assertEquals(HEAT_IN, heatColor(190.0, TargetRange(70, 200), cuts))
    }

    @Test fun anUnboundedTargetStillYieldsAColour() {
        // Target edges are unbounded and may swallow the cuts; each arm keeps a positive span,
        // the same clamp the Rust applies.
        val wide = TargetRange(40, 400)
        for (bg in listOf(0.0, 39.0, 40.0, 200.0, 400.0, 401.0, 600.0)) {
            val col = heatColor(bg, wide, cuts)
            assertTrue("$bg gave a non-finite red channel", col.red.isFinite())
            assertTrue("$bg gave a non-finite green channel", col.green.isFinite())
            assertTrue("$bg gave a non-finite blue channel", col.blue.isFinite())
        }
        assertEquals(HEAT_IN, heatColor(45.0, wide, cuts))
        assertEquals(HEAT_IN, heatColor(390.0, wide, cuts))
    }

    @Test fun theChipSelectsWhichSummaryColoursTheCell() {
        // {100, 102, 400}: mean 200.67 is above the 180 edge, median 102 well inside.
        val mean = (100.0 + 102.0 + 400.0) / 3.0
        val cell = HeatCell(dow = 0, hour = 8, n = 3, meanBg = mean, medianBg = 102.0)
        assertEquals(102.0, cell.value(HeatStat.Median), 0.0)
        assertEquals(mean, cell.value(HeatStat.Mean), 0.0)
        assertEquals(HEAT_IN, heatColor(cell.value(HeatStat.Median), target, cuts))
        assertNotEquals(HEAT_IN, heatColor(cell.value(HeatStat.Mean), target, cuts))
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
