package com.t1dm.ui.graph

import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.LogMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a tap on the marker band resolves to. The answer is POSITIONS IN THE CALLER'S FEED, never a
 *  marker identity: two logs share a 5-min slot often enough. The geometry is trivial — `dpPx = 1`,
 *  and 1000 px over 1 000 000 ms, so 1 px == 1000 ms. Spacings are DERIVED from the glyph. */
class LogMarkerHitTest {

    private val T0 = 1_700_000_000_000L
    private val SPAN = 1_000_000.0
    private val LEFT = 0f
    private val RIGHT = 1000f
    private val DP = 1f
    private val BOTTOM = 600f

    private val bandTop = BOTTOM - LOG_MARKER_BAND_DP * DP
    // From the production geometry, never restated from it.
    private val insulinLaneY = logMarkerLaneTop(CurveKind.INSULIN, BOTTOM, DP) + LOG_MARKER_DP * DP / 2f
    private val carbLaneY = logMarkerLaneTop(CurveKind.CARB, BOTTOM, DP) + LOG_MARKER_DP * DP / 2f

    /** A gap two marks always combine across: half the clustering distance. In px. */
    private val STEP = logMarkerSeparationPx(DP) / 2f

    /** How far outside the plot a STRADDLING mark's centroid sits — a third of a glyph, so part of it
     *  still shows inside the clip. */
    private val STRADDLE = LOG_MARKER_DP * DP / 3f

    private fun carb(offsetMs: Long) = LogMarker(T0 + offsetMs, CurveKind.CARB)

    private fun insulin(offsetMs: Long) = LogMarker(T0 + offsetMs, CurveKind.INSULIN)

    /** The feed, split and clustered exactly as the panel does it, then tapped at ([x], [y]).
     *  [viewStartMs] pans the window, the only way to put a mark off the plot's edge. */
    private fun tap(
        feed: List<LogMarker>,
        x: Float,
        y: Float,
        viewStartMs: Double = T0.toDouble(),
    ): List<Int> {
        val sep = logMarkerSeparationPx(DP)
        val insulinLane = markerLane(feed, CurveKind.INSULIN)
        val carbLane = markerLane(feed, CurveKind.CARB)
        return hitTestLogMarkers(
            x, y, LEFT, RIGHT, BOTTOM, DP,
            insulinLane, clusterLogMarkers(insulinLane.marks, viewStartMs, SPAN, LEFT, RIGHT, sep),
            carbLane, clusterLogMarkers(carbLane.marks, viewStartMs, SPAN, LEFT, RIGHT, sep),
        )
    }

    @Test fun aTapOnALoneMarkNamesIt() {
        assertEquals(listOf(0), tap(listOf(carb(250_000)), 250f, carbLaneY))
    }

    @Test fun aTapOnEmptyPlotNamesNothing() {
        assertTrue(tap(listOf(carb(250_000)), 700f, carbLaneY).isEmpty())
    }

    @Test fun theReachIsHalfTheClusteringDistance() {
        val feed = listOf(carb(250_000))
        val reach = logMarkerTapReachPx(DP)
        assertEquals(listOf(0), tap(feed, 250f + reach - 0.5f, carbLaneY))
        assertTrue(tap(feed, 250f + reach + 0.5f, carbLaneY).isEmpty())
    }

    @Test fun noPointCanEverReachTwoMarksOfOneLane() {
        // Two marks that stayed apart are more than a separation apart, and the reach is half of it.
        val feed = listOf(carb(0), carb(40_000)) // 40 px apart: past the separation ⇒ two marks
        var hits0 = 0
        var hits1 = 0
        var x = -20f
        while (x <= 60f) {
            val out = tap(feed, x, carbLaneY)
            assertTrue("a tap at $x named two marks of one lane: $out", out.size <= 1)
            if (out == listOf(0)) hits0++
            if (out == listOf(1)) hits1++
            x += 0.5f
        }
        assertTrue("both marks are reachable", hits0 > 0 && hits1 > 0)
    }

    @Test fun aTapAboveTheBandIsNotAMarkerTap() {
        // The trace lives above the band; the lanes claim the band and nothing above it.
        assertTrue(tap(listOf(carb(250_000)), 250f, bandTop - 1f).isEmpty())
        assertEquals(listOf(0), tap(listOf(carb(250_000)), 250f, bandTop + 1f))
    }

    @Test fun aTapBelowThePlotFloorIsNotAMarkerTap() {
        assertTrue(tap(listOf(carb(250_000)), 250f, BOTTOM + 1f).isEmpty())
    }

    @Test fun aTapOutsideThePlotIsNotAMarkerTap() {
        // The pointer node is the whole panel, gutter and margin included; without the plot bound
        // the axis labels would open modals.
        val feed = listOf(carb(0), carb(1_000_000))
        assertTrue(tap(feed, LEFT - 1f, carbLaneY).isEmpty())
        assertTrue(tap(feed, RIGHT + 1f, carbLaneY).isEmpty())
        // The edges themselves are IN.
        assertEquals(listOf(0), tap(feed, LEFT, carbLaneY))
        assertEquals(listOf(1), tap(feed, RIGHT, carbLaneY))
    }

    @Test fun theGutterCannotOpenAMarkStandingOverIt() {
        // Clustering keeps a mark up to a separation OUTSIDE the plot, so a centroid can sit in the
        // axis gutter. The reach alone would answer a tap there; the plot bound is what refuses it.
        val feed = listOf(carb(0))
        val straddling = T0.toDouble() + STRADDLE * 1000.0 // the mark projects to x = −STRADDLE
        var x = LEFT - logMarkerTapReachPx(DP)
        while (x < LEFT) {
            assertTrue("a tap at $x reached into the gutter", tap(feed, x, carbLaneY, straddling).isEmpty())
            x += 0.5f
        }
        assertEquals(listOf(0), tap(feed, LEFT, carbLaneY, straddling))
        // The last x still within a reach of a centroid sitting STRADDLE px out.
        val lastInReach = LEFT + (logMarkerTapReachPx(DP) - STRADDLE) - 0.5f
        assertEquals(listOf(0), tap(feed, lastInReach, carbLaneY, straddling))
    }

    @Test fun aMarkTheClipErasedCannotBeTappedAtAll() {
        // Panned a whole glyph past the edge: nothing of it is drawn, so no point may name it.
        val feed = listOf(carb(0))
        val hidden = T0.toDouble() + (LOG_MARKER_DP + 1f) * 1000.0
        var x = LEFT - logMarkerSeparationPx(DP)
        while (x <= RIGHT) {
            assertTrue("a tap at $x named a mark the clip erased", tap(feed, x, carbLaneY, hidden).isEmpty())
            x += 1f
        }
    }

    @Test fun aCarbAndADoseAtOneInstantOpenTogether() {
        // A tap on the column means "what did I log here", so both lanes answer.
        val feed = listOf(insulin(250_000), carb(250_000))
        assertEquals(listOf(0, 1), tap(feed, 250f, insulinLaneY))
        assertEquals(listOf(0, 1), tap(feed, 250f, carbLaneY))
    }

    @Test fun theColumnHoldsEvenWhenTheTwoLanesCentroidsHaveDrifted() {
        // Once one lane's mark absorbs a neighbour the two glyphs stop being aligned. Hit-testing each
        // lane independently at the same x is what keeps the column one column.
        val feed = listOf(insulin(250_000), insulin(250_000 + (STEP * 1000).toLong()), carb(250_000))
        // The two insulins combined, so their mark stands half a STEP right of the carb's.
        val out = tap(feed, 250f + STEP / 2f, carbLaneY)
        assertEquals(listOf(0, 1, 2), out)
    }

    @Test fun oneLaneAloneAnswersWhenTheOtherHasNothingInTheColumn() {
        val feed = listOf(insulin(250_000), carb(600_000))
        assertEquals(listOf(0), tap(feed, 250f, carbLaneY))
        assertEquals(listOf(1), tap(feed, 600f, insulinLaneY))
    }

    @Test fun aCombinedMarkNamesEveryLogItStandsFor() {
        val step = (STEP * 1000).toLong()
        val feed = listOf(carb(0), carb(step), carb(2 * step)) // chained, single-linkage, at their mean
        assertEquals(listOf(0, 1, 2), tap(feed, STEP, carbLaneY))
    }

    @Test fun aCombinedMarkNamesOnlyItsOwnMembers() {
        val feed = listOf(carb(0), carb((STEP * 1000).toLong()), carb(500_000))
        assertEquals(listOf(0, 1), tap(feed, STEP / 2f, carbLaneY))
        assertEquals(listOf(2), tap(feed, 500f, carbLaneY))
    }

    @Test fun twoLogsInOneGridSlotAreBothNamed() {
        // Same instant, same channel, two rows: the feed positions differ though the markers do not.
        val feed = listOf(carb(250_000), carb(250_000))
        assertEquals(listOf(0, 1), tap(feed, 250f, carbLaneY))
    }

    @Test fun indicesAreIntoTheFeedTheCallerHandedIn() {
        // Interleaved, so a lane index and a feed index cannot coincide by luck.
        val feed = listOf(
            insulin(0), carb(0),
            insulin(200_000), carb(200_000),
            insulin(400_000), carb(400_000),
        )
        assertEquals(listOf(2, 3), tap(feed, 200f, carbLaneY))
        assertEquals(listOf(4, 5), tap(feed, 400f, insulinLaneY))
    }

    @Test fun anUnorderedFeedIsStillNamedCorrectly() {
        // The feed arrives newest-first; `source` carries each mark back to where it came from.
        val feed = listOf(carb(400_000), carb(0), carb(200_000))
        assertEquals(listOf(1), tap(feed, 0f, carbLaneY))
        assertEquals(listOf(2), tap(feed, 200f, carbLaneY))
        assertEquals(listOf(0), tap(feed, 400f, carbLaneY))
    }

    @Test fun hitsComeBackAscendingSoTheFeedsOwnOrderSurvives() {
        val feed = listOf(carb(400_000), insulin(400_000), carb(390_000))
        val out = tap(feed, 395f, carbLaneY)
        assertEquals(listOf(0, 1, 2), out)
        assertEquals(out.sorted(), out)
    }

    @Test fun aLaneIsItsChannelAscending() {
        val feed = listOf(carb(400_000), insulin(100_000), carb(0), insulin(300_000))
        val carbs = markerLane(feed, CurveKind.CARB)
        assertEquals(listOf(0L, 400_000L), carbs.marks.map { it.tsMs - T0 })
        assertEquals(listOf(2, 0), carbs.source.toList())
        val insulins = markerLane(feed, CurveKind.INSULIN)
        assertEquals(listOf(100_000L, 300_000L), insulins.marks.map { it.tsMs - T0 })
        assertEquals(listOf(1, 3), insulins.source.toList())
    }

    @Test fun anEmptyChannelIsAnEmptyLane() {
        val lane = markerLane(listOf(carb(0)), CurveKind.INSULIN)
        assertTrue(lane.marks.isEmpty())
        assertEquals(0, lane.source.size)
    }
}
