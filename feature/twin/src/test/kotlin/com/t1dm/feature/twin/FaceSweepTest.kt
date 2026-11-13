package com.t1dm.feature.twin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class FaceSweepTest {
    private val sharp = FaceMetrics(128f, 0f, 128f, 0f, 200f, 1f, 1f, 0f, 1f, 0f, 1f)

    /** A frame whose nose sits so that [FaceFrame.yawDeg] reads [yaw]. */
    private fun frame(yaw: Float, pixels: Boolean = true): FaceFrame {
        val p = FloatArray(478 * 3)
        fun put(i: Int, x: Float, y: Float) {
            p[i * 3] = x
            p[i * 3 + 1] = y
        }
        put(FaceFrame.CHEEK_L, 0.3f, 0.5f)
        put(FaceFrame.CHEEK_R, 0.7f, 0.5f)
        put(FaceFrame.NOSE, 0.5f + 0.2f * sin(Math.toRadians(yaw.toDouble())).toFloat(), 0.5f)
        put(FaceFrame.FOREHEAD, 0.5f, 0.3f)
        put(FaceFrame.CHIN, 0.5f, 0.7f)
        return FaceFrame(p, 100, 100, sharp, if (pixels) IntArray(1) else null)
    }

    @Test fun `left first, then right, then done`() {
        val s = FaceSweep()
        assertTrue(s.turningLeft)
        assertTrue(s.offer(frame(29f)))
        assertTrue(s.offer(frame(16f)))
        assertFalse(s.turningLeft)
        s.offer(frame(-14f))
        s.offer(frame(-31f))
        assertTrue(s.done)
        assertEquals(4, s.shots().size)
    }

    @Test fun `a frame nearer the bin centre replaces the first`() {
        val s = FaceSweep()
        s.offer(frame(24f))
        val better = frame(30f)
        assertFalse(s.offer(better))
        assertSame(better, s.shots().single())
    }

    @Test fun `frames without pixels or off any bin are ignored`() {
        val s = FaceSweep()
        assertFalse(s.offer(frame(30f, pixels = false)))
        assertFalse(s.offer(frame(0f)))
        assertEquals(0, s.count)
    }
}
