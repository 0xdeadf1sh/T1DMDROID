package com.t1dm.ui.graph

import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.LogMarker
import com.t1dm.core.model.LogState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM tests for what a tap on the BG panel's marker band resolves to: [markerLane], which splits
 * the feed into two lanes while remembering where each mark came from, and [hitTestLogMarkers], which
 * turns a pixel into the positions IN THAT FEED of every log standing behind the mark under it.
 *
 * The indices are the whole contract. A mark is not an identity — two logs share a 5-min slot often
 * enough that the app's own feed sorts by row id to break the tie — so the layer answers with the
 * caller's own list positions and the caller does the naming. Everything here is therefore checked as
 * "which rows of the feed", never as "which marker object".
 *
 * The geometry is deliberately trivial: `dpPx = 1`, so a dp IS a pixel and every constant can be read
 * off the source; and a 1000 px plot over a 1 000 000 ms window, so **1 px == 1000 ms** and every mark
 * stands at the x its timestamp spells. At that density the glyph is 30 px, two marks combine within
 * 33 px, a tap reaches 16.5 px, and the band is the 65 px above the plot floor.
 */
class LogMarkerHitTest {

    private val T0 = 1_700_000_000_000L
    private val SPAN = 1_000_000.0
    private val LEFT = 0f
    private val RIGHT = 1000f
    private val DP = 1f
    private val BOTTOM = 600f

    private val bandTop = BOTTOM - LOG_MARKER_BAND_DP * DP
    // The middle of each glyph, taken from the production geometry rather than restated from it.
    private val insulinLaneY = logMarkerLaneTop(CurveKind.INSULIN, BOTTOM, DP) + LOG_MARKER_DP * DP / 2f
    private val carbLaneY = logMarkerLaneTop(CurveKind.CARB, BOTTOM, DP) + LOG_MARKER_DP * DP / 2f

    private fun carb(offsetMs: Long, state: LogState = LogState.DELIVERED) =
        LogMarker(T0 + offsetMs, CurveKind.CARB, state)

    private fun insulin(offsetMs: Long, state: LogState = LogState.DELIVERED) =
        LogMarker(T0 + offsetMs, CurveKind.INSULIN, state)

    /** The feed, split and clustered exactly as the panel does it, then tapped at ([x], [y]).
     *  [viewStartMs] pans the window, which is the only way to put a mark off the plot's edge. */
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

    // ── one mark, one log ────────────────────────────────────────────────────────────────────────

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
        // The reach is derived from the clustering distance precisely so this cannot happen: two marks
        // that stayed apart are more than a separation apart, and the reach is half of it. Swept across
        // the whole gap, no tap ever names more than the one lane's nearest mark.
        val feed = listOf(carb(0), carb(40_000)) // 40 px apart: past the 33 px separation ⇒ two marks
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

    // ── the band, and only the band ──────────────────────────────────────────────────────────────

    @Test fun aTapAboveTheBandIsNotAMarkerTap() {
        // The trace lives up there. The lanes' claim on the plot is the band and nothing above it.
        assertTrue(tap(listOf(carb(250_000)), 250f, bandTop - 1f).isEmpty())
        assertEquals(listOf(0), tap(listOf(carb(250_000)), 250f, bandTop + 1f))
    }

    @Test fun aTapBelowThePlotFloorIsNotAMarkerTap() {
        assertTrue(tap(listOf(carb(250_000)), 250f, BOTTOM + 1f).isEmpty())
    }

    // ── the plot, and only the plot ──────────────────────────────────────────────────────────────

    @Test fun aTapOutsideThePlotIsNotAMarkerTap() {
        // The pointer node is the whole panel — the y-axis gutter and the right margin included — and
        // the drawing is clipped to the plot. Both edges are excluded, or the axis labels open modals.
        val feed = listOf(carb(0), carb(1_000_000))
        assertTrue(tap(feed, LEFT - 1f, carbLaneY).isEmpty())
        assertTrue(tap(feed, RIGHT + 1f, carbLaneY).isEmpty())
        // The edges themselves are IN: a mark standing at the plot's first pixel must be tappable.
        assertEquals(listOf(0), tap(feed, LEFT, carbLaneY))
        assertEquals(listOf(1), tap(feed, RIGHT, carbLaneY))
    }

    @Test fun theGutterCannotOpenAMarkStandingOverIt() {
        // Clustering keeps a mark up to a separation OUTSIDE the plot so a straddling glyph still
        // draws its visible half — so a centroid can sit in the y-axis label gutter, where the clip
        // has erased most of it. The reach alone would answer a tap on the axis labels; the plot
        // bound is what refuses it, while the glyph's visible half stays tappable from inside.
        val feed = listOf(carb(0))
        val straddling = T0.toDouble() + 10_000.0 // the mark projects to x = −10: 5 px of it shows
        var x = LEFT - logMarkerTapReachPx(DP)
        while (x < LEFT) {
            assertTrue("a tap at $x reached into the gutter", tap(feed, x, carbLaneY, straddling).isEmpty())
            x += 0.5f
        }
        assertEquals(listOf(0), tap(feed, LEFT, carbLaneY, straddling))
        assertEquals(listOf(0), tap(feed, LEFT + 6f, carbLaneY, straddling))
    }

    @Test fun aMarkTheClipErasedCannotBeTappedAtAll() {
        // Panned a whole glyph past the edge: nothing of it is drawn anywhere, so no point of the
        // panel — inside the plot or in either margin — may name it.
        val feed = listOf(carb(0))
        val hidden = T0.toDouble() + (LOG_MARKER_DP + 1f) * 1000.0
        var x = LEFT - logMarkerSeparationPx(DP)
        while (x <= RIGHT) {
            assertTrue("a tap at $x named a mark the clip erased", tap(feed, x, carbLaneY, hidden).isEmpty())
            x += 1f
        }
    }

    // ── the stacked column: both lanes answer, never the nearer one ──────────────────────────────

    @Test fun aCarbAndADoseAtOneInstantOpenTogether() {
        // The two stand one above the other. A tap on the column means "what did I log here", so both
        // are named — whichever lane the thumb happened to land in.
        val feed = listOf(insulin(250_000), carb(250_000))
        assertEquals(listOf(0, 1), tap(feed, 250f, insulinLaneY))
        assertEquals(listOf(0, 1), tap(feed, 250f, carbLaneY))
    }

    @Test fun theColumnHoldsEvenWhenTheTwoLanesCentroidsHaveDrifted() {
        // As soon as one lane's mark absorbs a neighbour its centroid moves off the other's, so the two
        // glyphs are no longer vertically aligned. The column is still one column: hit-testing each lane
        // independently at the same x is what keeps it so, and a single lane-picking hit test could not.
        val feed = listOf(insulin(250_000), insulin(270_000), carb(250_000))
        // The insulin mark now stands at 260 px, the carb at 250 — 10 px apart, both within reach of a
        // tap between them.
        val out = tap(feed, 255f, carbLaneY)
        assertEquals(listOf(0, 1, 2), out)
    }

    @Test fun oneLaneAloneAnswersWhenTheOtherHasNothingInTheColumn() {
        val feed = listOf(insulin(250_000), carb(600_000))
        assertEquals(listOf(0), tap(feed, 250f, carbLaneY))
        assertEquals(listOf(1), tap(feed, 600f, insulinLaneY))
    }

    // ── the clustered glyph names every log behind it ────────────────────────────────────────────

    @Test fun aCombinedMarkNamesEveryLogItStandsFor() {
        val feed = listOf(carb(0), carb(20_000), carb(40_000)) // chained: 0 → 20 → 40 px, each ≤ 33
        assertEquals(listOf(0, 1, 2), tap(feed, 20f, carbLaneY))
    }

    @Test fun aCombinedMarkNamesOnlyItsOwnMembers() {
        val feed = listOf(carb(0), carb(20_000), carb(500_000))
        assertEquals(listOf(0, 1), tap(feed, 10f, carbLaneY))
        assertEquals(listOf(2), tap(feed, 500f, carbLaneY))
    }

    @Test fun twoLogsInOneGridSlotAreBothNamed() {
        // The case a timestamp could never resolve: same instant, same channel, two rows. The feed
        // positions are distinct even though the markers compare equal.
        val feed = listOf(carb(250_000), carb(250_000))
        assertEquals(listOf(0, 1), tap(feed, 250f, carbLaneY))
    }

    // ── the indices are the CALLER's, not the lane's ─────────────────────────────────────────────

    @Test fun indicesAreIntoTheFeedTheCallerHandedIn() {
        // Interleaved, so a lane index and a feed index cannot coincide by luck: the carb lane's three
        // marks sit at feed positions 1, 3 and 5.
        val feed = listOf(
            insulin(0), carb(0),
            insulin(200_000), carb(200_000),
            insulin(400_000), carb(400_000),
        )
        assertEquals(listOf(2, 3), tap(feed, 200f, carbLaneY))
        assertEquals(listOf(4, 5), tap(feed, 400f, insulinLaneY))
    }

    @Test fun anUnorderedFeedIsStillNamedCorrectly() {
        // The panel's feed arrives newest-first; the lane sorts it ascending to cluster it, and `source`
        // is what carries each mark back to where it came from.
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

    // ── the lane split itself ────────────────────────────────────────────────────────────────────

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
