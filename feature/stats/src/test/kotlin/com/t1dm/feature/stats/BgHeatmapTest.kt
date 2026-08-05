package com.t1dm.feature.stats

import com.t1dm.core.model.ClinicalCuts
import com.t1dm.core.model.HeatCell
import com.t1dm.core.model.HeatStat
import com.t1dm.core.model.TargetRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM coverage for the heatmap's colour ramp. The Canvas is not unit-tested; [heatColor] is the
 * whole of what a cell's colour means, and it is pure.
 */
class BgHeatmapTest {

    private val cuts = ClinicalCuts(veryLowMgdl = 54.0, veryHighMgdl = 250.0)
    private val target = TargetRange(70, 180)

    private fun c(mgdl: Double) = heatColor(mgdl, target, cuts)

    @Test fun theWholeTargetRangeIsOneFlatGreen() {
        // In range is a STATE, not a gradient of goodness: 71 and 179 must be indistinguishable, so
        // any departure from the flat colour reads as "out of range" without consulting the legend.
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
        // The property is "further out of range ⇒ nearer the pole", stated as distance to the anchor
        // rather than as any single channel: these ramps are not channel-monotone (#57ACEE is GREENER
        // than #24794A), so asserting a channel would pin an accident of the hex instead of the
        // reading the cell has to carry.
        fun dist(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color) =
            (a.red - b.red) * (a.red - b.red) +
                (a.green - b.green) * (a.green - b.green) +
                (a.blue - b.blue) * (a.blue - b.blue)

        // Below the range, walking down from the low edge to the cut.
        var prev = Float.MAX_VALUE
        for (bg in 69 downTo 54) {
            val d = dist(c(bg.toDouble()), HEAT_LOW)
            assertTrue("$bg is no nearer blue than the value above it", d <= prev)
            prev = d
        }
        assertNotEquals(HEAT_IN, c(69.0))

        // Above it, walking up from the high edge to the cut.
        prev = Float.MAX_VALUE
        for (bg in 181..250) {
            val d = dist(c(bg.toDouble()), HEAT_HIGH)
            assertTrue("$bg is no nearer red than the value below it", d <= prev)
            prev = d
        }
        assertNotEquals(HEAT_IN, c(181.0))
    }

    @Test fun theRampFollowsTheUsersTargetRatherThanFixedEdges() {
        // Widen the target and 190 goes from an out-of-range colour to the flat in-range one.
        assertNotEquals(HEAT_IN, c(190.0))
        assertEquals(HEAT_IN, heatColor(190.0, TargetRange(70, 200), cuts))
    }

    @Test fun anUnboundedTargetStillYieldsAColour() {
        // The target edges are unbounded by design and may swallow the fixed cuts entirely. Each arm
        // must keep a positive span rather than dividing by zero — the same clamp the Rust applies to
        // keep the five sub-bands disjoint.
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
        // One cell whose mean is dragged out of range by a spike its median resists — the case the
        // median exists for. The two must colour differently, and the mean must be the one that reads
        // as high, or the chip is decorative.
        // {100, 102, 400}: mean 200.67 is above the 180 target edge, median 102 is comfortably inside.
        val mean = (100.0 + 102.0 + 400.0) / 3.0
        val cell = HeatCell(dow = 0, hour = 8, n = 3, meanBg = mean, medianBg = 102.0)
        assertEquals(102.0, cell.value(HeatStat.Median), 0.0)
        assertEquals(mean, cell.value(HeatStat.Mean), 0.0)
        assertEquals(HEAT_IN, heatColor(cell.value(HeatStat.Median), target, cuts))
        assertNotEquals(HEAT_IN, heatColor(cell.value(HeatStat.Mean), target, cuts))
    }

    @Test fun theThreeAnchorsAreDistinct() {
        // The pair that collapses under red-green colour blindness is HEAT_IN vs HEAT_HIGH; they are
        // separated in LIGHTNESS as well as hue, so a reader who cannot tell the hues apart still can.
        assertNotEquals(HEAT_IN, HEAT_HIGH)
        assertNotEquals(HEAT_IN, HEAT_LOW)
        fun luma(c: androidx.compose.ui.graphics.Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
        assertTrue("in-range is the darkest anchor", luma(HEAT_IN) < luma(HEAT_HIGH))
        assertTrue("in-range is the darkest anchor", luma(HEAT_IN) < luma(HEAT_LOW))
    }
}
