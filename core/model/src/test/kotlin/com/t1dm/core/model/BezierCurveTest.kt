package com.t1dm.core.model

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BezierCurveTest {

    @Test fun sampleNormalizedSumsToTotal() {
        val c = BezierCurve.default(180.0)
        val s = c.sampleNormalized(60.0, stepMin = 5.0)
        assertEquals(36, s.size) // ceil(180/5)
        assertTrue("area must normalise to the dose total", abs(s.sum() - 60.0) < 1e-6)
        assertTrue("no negative rates", s.all { it >= 0.0 })
    }

    @Test fun degenerateCurveYieldsZeros() {
        val flat = BezierCurve(120.0, listOf(BezierPoint(0.0, 0.0), BezierPoint(120.0, 0.0)))
        val s = flat.sampleNormalized(10.0)
        assertTrue("a zero-area shape samples to all zeros", s.all { it == 0.0 })
    }

    @Test fun risesFromAndReturnsToZero() {
        val c = BezierCurve.default(200.0)
        assertEquals(0.0, c.valueAt(0.0), 1e-9)
        assertEquals(0.0, c.valueAt(200.0), 1e-9)
        assertEquals(0.0, c.valueAt(250.0), 1e-9) // clamped past the end
        assertTrue("the hump is positive mid-span", c.valueAt(60.0) > 0.0)
    }

    @Test fun degenerateDetection() {
        val flat = BezierCurve(120.0, listOf(BezierPoint(0.0, 0.0), BezierPoint(120.0, 0.0)))
        assertTrue("a flat zero shape is degenerate", flat.isDegenerate())
        assertTrue("the default hump is not degenerate", !BezierCurve.default(180.0).isDegenerate())
    }

    @Test fun encodeDecodeRoundTrips() {
        val c = BezierCurve(180.0, listOf(BezierPoint(0.0, 0.0), BezierPoint(54.0, 1.0), BezierPoint(180.0, 0.0)))
        val back = BezierCurve.decode(BezierCurve.encode(c))!!
        assertEquals(c.durationMin, back.durationMin, 1e-9)
        assertEquals(c.points.size, back.points.size)
        assertEquals(54.0, back.points[1].xMin, 1e-9)
        assertNull(BezierCurve.decode("garbage"))
        assertNull(BezierCurve.decode(null))
    }
}
