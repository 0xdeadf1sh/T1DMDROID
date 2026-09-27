package com.t1dm.ui.graph

import com.t1dm.core.model.BezierPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class CurveEditorScaleTest {

    private fun points() = mutableListOf(BezierPoint(0.0, 1.0), BezierPoint(10.0, 2.0), BezierPoint(20.0, 3.0))

    @Test fun aFrozenScaleHoldsWhileTheTallestPointIsDragged() {
        val pts = points()
        val scale = CurveYScale()
        scale.freeze(pts)
        val held = scale.max(pts)
        // The finger held at 95 % of the plot height for 20 move events.
        repeat(20) { pts[2] = BezierPoint(20.0, 0.95 * scale.max(pts)) }
        assertEquals(held, scale.max(pts), 1e-12)
        assertEquals(0.95 * held, pts[2].y, 1e-12)
    }

    @Test fun releasedScaleTracksTheTallestPoint() {
        val pts = points()
        val scale = CurveYScale()
        val before = scale.max(pts)
        scale.freeze(pts)
        pts[2] = BezierPoint(20.0, 6.0)
        assertEquals(before, scale.max(pts), 1e-12)
        scale.release()
        assertEquals(2 * before, scale.max(pts), 1e-12)
    }
}
