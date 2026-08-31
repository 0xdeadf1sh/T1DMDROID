package com.t1dm.ui.graph

import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.LogMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The BG panel's log-marker geometry. Clustering is handed ONE LANE at a time, so nothing here
 *  mixes channels. The projection is deliberately trivial — 1000 px over 1 000 000 ms — so
 *  1 px == 1000 ms and every expected position reads off the timestamps. */
class LogMarkerClusterTest {

    private val T0 = 1_700_000_000_000L
    private val SPAN = 1_000_000.0
    private val LEFT = 0f
    private val RIGHT = 1000f
    private val SEP = 30f // ⇒ marks within 30 000 ms of each other combine

    private fun mark(offsetMs: Long) = LogMarker(T0 + offsetMs, CurveKind.CARB)

    private fun cluster(
        markers: List<LogMarker>,
        spanMs: Double = SPAN,
        startMs: Double = T0.toDouble(),
        right: Float = RIGHT,
        sep: Float = SEP,
    ) = clusterLogMarkers(markers, startMs, spanMs, LEFT, right, sep)

    @Test fun singleEventStandsAtItsOwnInstant() {
        val out = cluster(listOf(mark(250_000)))
        assertEquals(1, out.size)
        assertEquals(250f, out.single().xPx, 1e-3f)
    }

    @Test fun emptyLaneDrawsNothing() {
        assertTrue(cluster(emptyList()).isEmpty())
    }

    @Test fun collidingMarksCombineAtTheirMean() {
        // 0 px and 20 px, inside the 30 px separation.
        val out = cluster(listOf(mark(0), mark(20_000)))
        assertEquals(1, out.size)
        assertEquals(10f, out.single().xPx, 1e-3f)
    }

    @Test fun marksBeyondTheSeparationStayApart() {
        // 0 px and 40 px: past the 30 px separation.
        val out = cluster(listOf(mark(0), mark(40_000)))
        assertEquals(2, out.size)
        assertEquals(0f, out[0].xPx, 1e-3f)
        assertEquals(40f, out[1].xPx, 1e-3f)
    }

    @Test fun separationIsInclusiveAndChains() {
        // Single linkage on the LAST member admitted: 0 → 30 → 60 px, each step exactly the
        // separation, so all three chain even though the ends are 60 px apart.
        val out = cluster(listOf(mark(0), mark(30_000), mark(60_000)))
        assertEquals(1, out.size)
        assertEquals(30f, out.single().xPx, 1e-3f)
    }

    @Test fun twoIconsInOneLaneNeverOverlapAtAnyZoom() {
        // Two icons that touched would be indistinguishable from one. Single linkage keeps every
        // gap past the separation, which is a glyph plus its clear space.
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

    @Test fun theLanesStackCarbsInsulinExerciseUpFromTheFloor() {
        val dpPx = 3f
        val plotBottom = 600f
        val size = LOG_MARKER_DP * dpPx
        val exerciseTop = logMarkerLaneTop(CurveKind.EXERCISE, plotBottom, dpPx)
        val insulinTop = logMarkerLaneTop(CurveKind.INSULIN, plotBottom, dpPx)
        val carbTop = logMarkerLaneTop(CurveKind.CARB, plotBottom, dpPx)
        assertTrue("exercise sits above insulin", exerciseTop < insulinTop)
        assertTrue("insulin sits above carbs", insulinTop < carbTop)
        assertTrue("the lanes do not overlap each other", exerciseTop + size <= insulinTop)
        assertTrue("the lanes do not overlap each other", insulinTop + size <= carbTop)
        assertTrue("the carb lane clears the axis line", carbTop + size < plotBottom)
    }

    @Test fun laneTopsDependOnNothingButThePlotFloorAndTheDensity() {
        // Lane position is information: it must not move with the contents of the view.
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
        // The glyph size is the ONE knob: the band, the distance at which two marks combine and the
        // reach of a tap are all measured from it. Stated as relations, never as figures.
        val dpPx = 3f
        assertTrue("every lane fits inside the band", LOG_MARKER_BAND_DP > LOG_MARKER_DP * CurveKind.entries.size)
        assertTrue("marks combine only past a whole glyph", logMarkerSeparationPx(dpPx) > LOG_MARKER_DP * dpPx)
        assertEquals(logMarkerSeparationPx(dpPx) / 2f, logMarkerTapReachPx(dpPx), 1e-4f)
        assertTrue("a tap reaches past the glyph's edge", logMarkerTapReachPx(dpPx) > LOG_MARKER_DP * dpPx / 2f)
    }

    @Test fun theWholeBandIsWhatTheLanesBorrowFromThePlot() {
        // The band is an OVERLAY: the only claim on the plot is the strip from the topmost lane's
        // top down to the floor.
        val dpPx = 3f
        val plotBottom = 600f
        val topmost = CurveKind.entries.minOf { logMarkerLaneTop(it, plotBottom, dpPx) }
        assertEquals(plotBottom - LOG_MARKER_BAND_DP * dpPx, topmost, 1e-3f)
    }

    @Test fun aClusterNamesTheRunOfItsLaneItStandsFor() {
        // A timestamp cannot name the members — two rows share a 5-min slot — so the run is carried.
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
        // The cull drops a prefix and a suffix of an ascending list, so every run stays contiguous.
        val markers = listOf(mark(-500_000), mark(400_000), mark(420_000), mark(900_000), mark(2_000_000))
        val out = cluster(markers)
        assertEquals(2, out.size)
        assertEquals(1, out[0].from)
        assertEquals(3, out[0].to)
        assertEquals(3, out[1].from)
        assertEquals(4, out[1].to)
        out.zipWithNext { a, b -> assertTrue("runs overlap", a.to <= b.from) }
    }

    @Test fun zoomingOutCombinesAndZoomingInSeparates() {
        // The same three events at two zooms: only the projection decides one icon or three.
        val markers = listOf(mark(0), mark(3_600_000), mark(7_200_000))
        // 24 h over 1000 px ⇒ ~42 px apart.
        assertEquals(3, cluster(markers, spanMs = 24.0 * 3_600_000.0).size)
        // 30 days over 1000 px ⇒ ~1.4 px apart.
        assertEquals(1, cluster(markers, spanMs = 30.0 * 24.0 * 3_600_000.0).size)
    }

    @Test fun aWiderPlotSeparatesWhatANarrowOneCombined() {
        // Twice the pixels: a time-based threshold could not express this.
        val markers = listOf(mark(0), mark(25_000))
        assertEquals(1, cluster(markers).size)                    // 25 px apart on a 1000 px plot
        assertEquals(2, cluster(markers, right = 2000f).size)     // 50 px apart on a 2000 px plot
    }

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
        // Off the left edge by less than a glyph: still partly visible, so it is kept and clipped.
        val out = cluster(listOf(mark(-10_000)))
        assertEquals(1, out.size)
        assertEquals(-10f, out.single().xPx, 1e-3f)
    }

    @Test fun aDegenerateViewportDrawsNothingRatherThanDividingByZero() {
        val markers = listOf(mark(0))
        assertTrue(cluster(markers, spanMs = 0.0).isEmpty())
        assertTrue(cluster(markers, right = LEFT).isEmpty())
    }

    @Test fun separationScalesWithDisplayDensity() {
        // The clustering distance is a dp quantity, so the physical distance is density-free.
        assertEquals(logMarkerSeparationPx(1f) * 3f, logMarkerSeparationPx(3f), 1e-4f)
        assertTrue("a mark plus its clear space", logMarkerSeparationPx(1f) > LOG_MARKER_DP)
    }
}
