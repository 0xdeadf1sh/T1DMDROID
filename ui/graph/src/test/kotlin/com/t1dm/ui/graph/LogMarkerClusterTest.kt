package com.t1dm.ui.graph

import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.LogMarker
import com.t1dm.core.model.LogState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM tests for the BG panel's log-marker geometry: [clusterLogMarkers], the pure pixel-space
 * arithmetic that decides how many icons a lane shows and where they stand, and [logMarkerLaneTop],
 * which decides which lane each channel gets. The Canvas drawing is not unit-tested; this pins the
 * arithmetic behind it.
 *
 * Clustering is now handed ONE LANE at a time, so every marker in a list here belongs to the same
 * channel and nothing in these tests mixes them: carbs and insulin cannot combine because they are
 * never passed to the same call.
 *
 * The projection is deliberately trivial throughout: a 1000 px plot over a 1 000 000 ms window, so
 * **1 px == 1000 ms** and every expected position can be read off the timestamps by inspection.
 */
class LogMarkerClusterTest {

    private val T0 = 1_700_000_000_000L
    private val SPAN = 1_000_000.0
    private val LEFT = 0f
    private val RIGHT = 1000f
    private val SEP = 30f // ⇒ marks within 30 000 ms of each other combine

    private fun mark(offsetMs: Long, state: LogState = LogState.DELIVERED) =
        LogMarker(T0 + offsetMs, CurveKind.CARB, state)

    private fun cluster(
        markers: List<LogMarker>,
        spanMs: Double = SPAN,
        startMs: Double = T0.toDouble(),
        right: Float = RIGHT,
        sep: Float = SEP,
    ) = clusterLogMarkers(markers, startMs, spanMs, LEFT, right, sep)

    // ── one event is one icon ────────────────────────────────────────────────────────────────────

    @Test fun singleEventStandsAtItsOwnInstant() {
        val out = cluster(listOf(mark(250_000)))
        assertEquals(1, out.size)
        assertEquals(250f, out.single().xPx, 1e-3f)
    }

    @Test fun emptyLaneDrawsNothing() {
        assertTrue(cluster(emptyList()).isEmpty())
    }

    // ── combining ────────────────────────────────────────────────────────────────────────────────

    @Test fun collidingMarksCombineAtTheirMean() {
        // 0 px and 20 px, inside the 30 px separation ⇒ ONE icon, standing between them.
        val out = cluster(listOf(mark(0), mark(20_000)))
        assertEquals(1, out.size)
        assertEquals(10f, out.single().xPx, 1e-3f)
    }

    @Test fun marksBeyondTheSeparationStayApart() {
        // 0 px and 40 px: further apart than the 30 px separation ⇒ two icons, each standing alone.
        val out = cluster(listOf(mark(0), mark(40_000)))
        assertEquals(2, out.size)
        assertEquals(0f, out[0].xPx, 1e-3f)
        assertEquals(40f, out[1].xPx, 1e-3f)
    }

    @Test fun separationIsInclusiveAndChains() {
        // Single linkage on the LAST member admitted: 0 → 30 → 60 px, each step exactly the separation,
        // so all three chain into one icon even though the ends are 60 px apart — and it stands at their
        // mean, which for this run is the middle event.
        val out = cluster(listOf(mark(0), mark(30_000), mark(60_000)))
        assertEquals(1, out.size)
        assertEquals(30f, out.single().xPx, 1e-3f)
    }

    // ── the invariant the clustering exists for ──────────────────────────────────────────────────

    @Test fun twoIconsInOneLaneNeverOverlapAtAnyZoom() {
        // The whole reason the arithmetic survived the badge: with no number to disambiguate them, two
        // icons that touched would be indistinguishable from one. Single linkage guarantees the gap
        // between emitted clusters exceeds the separation, which is a glyph PLUS its clear space — so
        // the silhouettes cannot collide at any span the panel can be pinched to.
        val dpPx = 3f
        val sep = logMarkerSeparationPx(dpPx)
        val glyph = LOG_MARKER_DP * dpPx
        // Irregular spacing, so runs chain differently at every zoom rather than tiling neatly.
        val markers = (0..60).map { mark(it * 137_000L) }
        for (spanMin in longArrayOf(15, 60, 180, 360, 720, 1_440, 4_320, 20_160, 43_200)) {
            val out = cluster(markers, spanMs = spanMin * 60_000.0, sep = sep)
            out.map { it.xPx }.zipWithNext { a, b ->
                assertTrue("at $spanMin min two glyphs at $a and $b overlap", b - a > glyph)
            }
        }
    }

    // ── the two fixed lanes ──────────────────────────────────────────────────────────────────────

    @Test fun insulinIsTheUpperLaneAndCarbsTheLower() {
        val dpPx = 3f
        val plotBottom = 600f
        val size = LOG_MARKER_DP * dpPx
        val insulinTop = logMarkerLaneTop(CurveKind.INSULIN, plotBottom, dpPx)
        val carbTop = logMarkerLaneTop(CurveKind.CARB, plotBottom, dpPx)
        assertTrue("insulin sits above carbs", insulinTop < carbTop)
        assertTrue("the lanes do not overlap each other", insulinTop + size <= carbTop)
        assertTrue("the carb lane clears the axis line", carbTop + size < plotBottom)
    }

    @Test fun laneTopsDependOnNothingButThePlotFloorAndTheDensity() {
        // Lane position is information, so it must not move with the contents of the view: the function
        // takes no marker, no viewport and no toggle, and both lanes are a fixed distance above the plot
        // floor wherever that floor happens to be (it moves with the model-axis strip).
        val dpPx = 2f
        for (plotBottom in floatArrayOf(120f, 481.5f, 600f)) {
            for (kind in CurveKind.entries) {
                assertEquals(
                    logMarkerLaneTop(kind, 0f, dpPx) + plotBottom,
                    logMarkerLaneTop(kind, plotBottom, dpPx),
                    1e-3f,
                )
            }
        }
    }

    @Test fun everythingTheLanesClaimIsDerivedFromTheGlyph() {
        // The glyph size is the ONE knob. The band the lanes borrow, the distance at which two marks
        // combine and the reach of a tap are all measured from it, so growing a mark cannot leave a
        // caption printing over a lane, two glyphs overlapping, or a tap target the size of the old
        // icon. Stated as relations rather than figures: the figures are the layer's own business.
        val dpPx = 3f
        assertTrue("both lanes fit inside the band", LOG_MARKER_BAND_DP > LOG_MARKER_DP * 2f)
        assertTrue("marks combine only past a whole glyph", logMarkerSeparationPx(dpPx) > LOG_MARKER_DP * dpPx)
        assertEquals(logMarkerSeparationPx(dpPx) / 2f, logMarkerTapReachPx(dpPx), 1e-4f)
        assertTrue("a tap reaches past the glyph's edge", logMarkerTapReachPx(dpPx) > LOG_MARKER_DP * dpPx / 2f)
    }

    @Test fun theWholeBandIsWhatTheTwoLanesBorrowFromThePlot() {
        // The band is an OVERLAY: the caller's plotBottom, y scale and trace geometry are unchanged, so
        // the only claim this layer makes on the plot is the strip from the upper lane's top down to the
        // floor. Pinning it keeps a lane from silently growing into the trace.
        val dpPx = 3f
        val plotBottom = 600f
        assertEquals(
            plotBottom - LOG_MARKER_BAND_DP * dpPx,
            logMarkerLaneTop(CurveKind.INSULIN, plotBottom, dpPx),
            1e-3f,
        )
    }

    // ── which logs a mark stands for ─────────────────────────────────────────────────────────────

    @Test fun aClusterNamesTheRunOfItsLaneItStandsFor() {
        // A combined mark has to be able to list what it combined: a timestamp cannot do it (two rows
        // can share a 5-min slot), so the run is carried.
        val out = cluster(listOf(mark(0), mark(20_000), mark(500_000)))
        assertEquals(2, out.size)
        assertEquals(0, out[0].from)
        assertEquals(2, out[0].to)
        assertEquals(2, out[0].size)
        assertEquals(2, out[1].from)
        assertEquals(3, out[1].to)
        assertEquals(1, out[1].size)
    }

    @Test fun theRunsSurviveTheCullAndPartitionWhatIsDrawn() {
        // Culling drops a prefix and a suffix of an ascending list, which is what keeps every run
        // contiguous — the property the tap resolves members through.
        val markers = listOf(mark(-500_000), mark(400_000), mark(420_000), mark(900_000), mark(2_000_000))
        val out = cluster(markers)
        assertEquals(2, out.size)
        assertEquals(1, out[0].from)
        assertEquals(3, out[0].to)
        assertEquals(3, out[1].from)
        assertEquals(4, out[1].to)
        out.zipWithNext { a, b -> assertTrue("runs overlap", a.to <= b.from) }
    }

    // ── the pulse verdict ────────────────────────────────────────────────────────────────────────

    @Test fun clusterIsCommittedWhenAnyMemberStillIs() {
        val out = cluster(
            listOf(
                mark(0, state = LogState.DELIVERED),
                mark(10_000, state = LogState.DELIVERED),
                mark(20_000, state = LogState.COMMITTED),
            ),
        )
        assertEquals(1, out.size)
        assertTrue("one unacknowledged member keeps the whole mark breathing", out.single().committed)
    }

    @Test fun clusterIsDeliveredOnlyWhenEveryMemberIs() {
        val out = cluster(listOf(mark(0), mark(10_000), mark(20_000)))
        assertFalse(out.single().committed)
    }

    @Test fun committedStateDoesNotLeakAcrossClusters() {
        // A committed mark must not make its distant neighbour pulse too.
        val out = cluster(listOf(mark(0, state = LogState.COMMITTED), mark(500_000)))
        assertEquals(2, out.size)
        assertTrue(out[0].committed)
        assertFalse(out[1].committed)
    }

    // ── pixel space, not time: stable across zoom and screen width ───────────────────────────────

    @Test fun zoomingOutCombinesAndZoomingInSeparates() {
        // The same three events, an hour apart, at two zooms. Nothing about the data changed — only the
        // projection — and that is precisely what decides whether they are one icon or three.
        val markers = listOf(mark(0), mark(3_600_000), mark(7_200_000))
        // 1000 px over 24 h ⇒ ~0.0116 px/s: the three land ~42 px apart ⇒ they stay separate…
        assertEquals(3, cluster(markers, spanMs = 24.0 * 3_600_000.0).size)
        // …and 1000 px over 30 days puts them ~1.4 px apart ⇒ a single icon.
        assertEquals(1, cluster(markers, spanMs = 30.0 * 24.0 * 3_600_000.0).size)
    }

    @Test fun aWiderPlotSeparatesWhatANarrowOneCombined() {
        // Same window, same events, twice the pixels: the phone that combines them in portrait shows
        // them apart in landscape. A time-based threshold could not express this.
        val markers = listOf(mark(0), mark(25_000))
        assertEquals(1, cluster(markers).size)                    // 25 px apart on a 1000 px plot
        assertEquals(2, cluster(markers, right = 2000f).size)     // 50 px apart on a 2000 px plot
    }

    // ── culling ──────────────────────────────────────────────────────────────────────────────────

    @Test fun marksOutsideTheViewportAreNotDrawn() {
        val out = cluster(
            listOf(
                mark(-500_000),      // far left of the plot
                mark(500_000),       // on screen
                mark(2_000_000),     // far right of the plot
            ),
        )
        assertEquals(1, out.size)
        assertEquals(500f, out.single().xPx, 1e-3f)
    }

    @Test fun aMarkStraddlingAnEdgeIsStillDrawn() {
        // Just off the left edge by less than a glyph: its silhouette would still be partly visible, so
        // it is kept and the clip trims the overhang.
        val out = cluster(listOf(mark(-10_000)))
        assertEquals(1, out.size)
        assertEquals(-10f, out.single().xPx, 1e-3f)
    }

    // ── degenerate geometry ──────────────────────────────────────────────────────────────────────

    @Test fun aDegenerateViewportDrawsNothingRatherThanDividingByZero() {
        val markers = listOf(mark(0))
        assertTrue(cluster(markers, spanMs = 0.0).isEmpty())
        assertTrue(cluster(markers, right = LEFT).isEmpty())
    }

    // ── the density knob ─────────────────────────────────────────────────────────────────────────

    @Test fun separationScalesWithDisplayDensity() {
        // The clustering distance is a dp quantity: the same two marks combine at the same PHYSICAL
        // distance whatever the display's pixel density.
        assertEquals(logMarkerSeparationPx(1f) * 3f, logMarkerSeparationPx(3f), 1e-4f)
        assertTrue("a mark plus its clear space", logMarkerSeparationPx(1f) > LOG_MARKER_DP)
    }
}
