package com.t1dm.ui.graph

import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.LogMarker
import com.t1dm.core.model.LogState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM tests for [clusterLogMarkers] — the pure pixel-space arithmetic behind the BG panel's foot
 * markers. The Canvas drawing is not unit-tested; this pins what decides how many marks appear, where
 * they sit, what number each carries, and which of them pulse.
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

    private fun mark(offsetMs: Long, kind: CurveKind = CurveKind.CARB, state: LogState = LogState.DELIVERED) =
        LogMarker(T0 + offsetMs, kind, state)

    private fun cluster(
        markers: List<LogMarker>,
        spanMs: Double = SPAN,
        startMs: Double = T0.toDouble(),
        right: Float = RIGHT,
        sep: Float = SEP,
    ) = clusterLogMarkers(markers, startMs, spanMs, LEFT, right, sep)

    // ── one event is one mark, never a count of one ──────────────────────────────────────────────

    @Test fun singleEventStandsAtItsOwnInstantWithCountOne() {
        val out = cluster(listOf(mark(250_000)))
        assertEquals(1, out.size)
        val c = out.single()
        assertEquals(CurveKind.CARB, c.kind)
        assertEquals(1, c.count) // the draw suppresses the label at count 1 — a lone log is just a mark
        assertEquals(250f, c.xPx, 1e-3f)
    }

    @Test fun emptyFeedDrawsNothing() {
        assertTrue(cluster(emptyList()).isEmpty())
    }

    // ── combining ────────────────────────────────────────────────────────────────────────────────

    @Test fun collidingMarksOfOneKindCombineAtTheirMean() {
        // 0 px and 20 px, inside the 30 px separation.
        val out = cluster(listOf(mark(0), mark(20_000)))
        assertEquals(1, out.size)
        assertEquals(2, out.single().count)
        assertEquals(10f, out.single().xPx, 1e-3f)
    }

    @Test fun marksBeyondTheSeparationStayApart() {
        // 0 px and 40 px: further apart than the 30 px separation ⇒ two marks, each standing alone.
        val out = cluster(listOf(mark(0), mark(40_000)))
        assertEquals(2, out.size)
        assertTrue(out.all { it.count == 1 })
        assertEquals(0f, out[0].xPx, 1e-3f)
        assertEquals(40f, out[1].xPx, 1e-3f)
    }

    @Test fun separationIsInclusiveAndChains() {
        // Single linkage on the LAST member admitted: 0 → 30 → 60 px, each step exactly the separation,
        // so all three chain into one mark even though the ends are 60 px apart.
        val out = cluster(listOf(mark(0), mark(30_000), mark(60_000)))
        assertEquals(1, out.size)
        assertEquals(3, out.single().count)
        assertEquals(30f, out.single().xPx, 1e-3f)
    }

    @Test fun distinctClustersNeverOverlap() {
        // The invariant single linkage buys: whatever the input, two emitted marks of one kind are
        // always more than a separation apart, so their glyphs cannot collide on screen.
        val markers = (0..40).map { mark(it * 17_000L) } + (0..10).map { mark(900_000 + it * 3_000L) }
        val out = cluster(markers.sortedBy { it.tsMs })
        val xs = out.filter { it.kind == CurveKind.CARB }.map { it.xPx }
        xs.zipWithNext { a, b -> assertTrue("marks at $a and $b overlap", b - a > SEP) }
    }

    // ── kinds never merge into each other ────────────────────────────────────────────────────────

    @Test fun carbAndInsulinAtTheSameInstantStayTwoMarks() {
        val out = cluster(listOf(mark(100_000, CurveKind.CARB), mark(100_000, CurveKind.INSULIN)))
        assertEquals(2, out.size)
        assertEquals(1, out.count { it.kind == CurveKind.CARB })
        assertEquals(1, out.count { it.kind == CurveKind.INSULIN })
        assertTrue("both stand at the same instant", out.all { kotlin.math.abs(it.xPx - 100f) < 1e-3f })
    }

    @Test fun interleavedKindsClusterWithinTheirOwnChannel() {
        // Four carbs and four insulin doses alternating every 5 000 ms: every mark is within the
        // separation of its neighbours, yet the result is exactly TWO clusters, one per channel.
        val markers = (0 until 8).map {
            mark(it * 5_000L, if (it % 2 == 0) CurveKind.CARB else CurveKind.INSULIN)
        }
        val out = cluster(markers)
        assertEquals(2, out.size)
        assertEquals(4, out.first { it.kind == CurveKind.CARB }.count)
        assertEquals(4, out.first { it.kind == CurveKind.INSULIN }.count)
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
        // projection — and that is precisely what decides whether they are one mark or three.
        val markers = listOf(mark(0), mark(3_600_000), mark(7_200_000))
        // 1000 px over 24 h ⇒ ~0.0116 px/s: the three land ~42 px apart ⇒ they stay separate…
        val zoomedIn = cluster(markers, spanMs = 24.0 * 3_600_000.0)
        assertEquals(3, zoomedIn.size)
        // …and 1000 px over 30 days puts them ~1.4 px apart ⇒ one mark reading "3".
        val zoomedOut = cluster(markers, spanMs = 30.0 * 24.0 * 3_600_000.0)
        assertEquals(1, zoomedOut.size)
        assertEquals(3, zoomedOut.single().count)
    }

    @Test fun aWiderPlotSeparatesWhatANarrowOneCombined() {
        // Same window, same events, twice the pixels: the phone that combines them in portrait shows
        // them apart in landscape. A time-based threshold could not express this.
        val markers = listOf(mark(0), mark(25_000))
        assertEquals(1, cluster(markers).size)                    // 25 px apart on a 1000 px plot
        assertEquals(2, cluster(markers, right = 2000f).size)     // 50 px apart on a 2000 px plot
    }

    // ── culling ──────────────────────────────────────────────────────────────────────────────────

    @Test fun marksOutsideTheViewportAreNotDrawnAndNotCounted() {
        val out = cluster(
            listOf(
                mark(-500_000),      // far left of the plot
                mark(500_000),       // on screen
                mark(2_000_000),     // far right of the plot
            ),
        )
        assertEquals(1, out.size)
        assertEquals(1, out.single().count) // the count states what is on screen, never the whole feed
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
