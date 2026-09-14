package com.t1dm.feature.game

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.UnitSpace
import com.t1dm.ui.game.GameTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** Arbitrary: these are pure functions OF the tuning, so nothing here mirrors a shipped number. */
private const val GRAVITY = 18f
private const val MAX_SPEED = 55f
private const val BALL_R = 2.5f

/** A flat trace, so the ground the arc lands on is one known height everywhere. */
private fun flatTrack(n: Int = 300): GameTrack {
    val t0 = 1_700_000_000_000L / 300_000L * 300_000L
    val readings = List(n) { i ->
        CgmReading(
            sourceId = CgmSourceId("test"),
            tsMs = t0 + i * 300_000L,
            bgMgdl = 120,
            trendTenthsPerMin = 0,
            minFromStart = 120 + i * 5,
            quality = 1,
            provenance = ReadingProvenance.MEASURED,
            flag = ReadingFlag.NORMAL,
            tzOffsetMin = 0,
            rxWallMs = t0 + i * 300_000L,
            rssi = -60,
        )
    }
    return runBlocking { loadGameScene(readings, emptyList(), UnitSpace.MgDl, null, 20, 250).track }
}

class GolfArcTest {

    private val track = flatTrack()
    private val ground = track.groundAt(100f)

    private fun aimOf(dx: Float, dy: Float, full: Float = 200f): GolfAim =
        GolfAim().also { it.set(dx, dy, full, MAX_SPEED) }

    @Test
    fun `the launch is the opposite of the pull`() {
        // Pulled right and down; the ball must go left and up.
        val a = aimOf(120f, 90f)
        assertTrue("vx=${a.vx}", a.vx < 0f)
        assertTrue("vy=${a.vy}", a.vy > 0f)
        // Direction is exact: only the magnitude is scaled.
        assertEquals(-120f / 90f, a.vx / a.vy, 1e-4f)
    }

    @Test
    fun `speed is monotone in the pull and capped at the launch limit`() {
        var previous = -1f
        for (pull in listOf(0f, 5f, 14f, 20f, 50f, 100f, 150f, 199f, 200f, 400f, 5_000f)) {
            val a = aimOf(pull, 0f)
            val speed = hypot(a.vx, a.vy)
            assertTrue("pull $pull gave $speed", speed >= previous - 1e-4f)
            assertTrue("pull $pull exceeded the cap: $speed", speed <= MAX_SPEED + 1e-3f)
            previous = speed
        }
        assertEquals("full pull must reach the cap", MAX_SPEED, hypot(aimOf(200f, 0f).vx, 0f), 1e-3f)
        assertEquals("and past it stays there", MAX_SPEED, hypot(aimOf(9_000f, 0f).vx, 0f), 1e-3f)
    }

    @Test
    fun `a pull inside the dead radius is a cancel, not a dribble`() {
        for (pull in listOf(0f, 1f, DRAG_DEAD_PX - 0.01f, DRAG_DEAD_PX)) {
            val a = aimOf(pull, 0f)
            assertEquals("pull $pull", 0f, a.vx, 0f)
            assertEquals(0f, a.vy, 0f)
        }
    }

    @Test
    fun `a hostile pull never produces a non-finite launch`() {
        for (v in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1e30f)) {
            val a = aimOf(v, v)
            assertTrue("vx=${a.vx}", a.vx.isFinite())
            assertTrue("vy=${a.vy}", a.vy.isFinite())
            assertTrue(hypot(a.vx, a.vy) <= MAX_SPEED + 1e-3f)
        }
        // A zero full-scale pull cannot divide the launch into infinity.
        val degenerate = GolfAim().also { it.set(50f, 50f, 0f, MAX_SPEED) }
        assertEquals(0f, degenerate.vx, 0f)
    }

    @Test
    fun `the arc is the closed-form parabola the solver flies`() {
        val out = FloatArray(ARC_POINTS * 2)
        val x0 = 60f
        val y0 = ground + BALL_R
        val n = ballisticArc(x0, y0, 40f, 34f, GRAVITY, ARC_STEP_S, BALL_R, track, out)
        assertTrue("the arc must have some length, n=$n", n > 4)

        // Velocity-Verlet: exact for a constant acceleration, so it must agree sample for sample.
        var vx = 40f
        var vy = 34f
        var px = x0
        var py = y0
        assertEquals(px, out[0], 1e-3f)
        assertEquals(py, out[1], 1e-3f)
        for (i in 1 until n - 1) {
            px += vx * ARC_STEP_S
            py += vy * ARC_STEP_S - 0.5f * GRAVITY * ARC_STEP_S * ARC_STEP_S
            vy -= GRAVITY * ARC_STEP_S
            assertEquals("point $i x", px, out[2 * i], 0.02f)
            assertEquals("point $i y", py, out[2 * i + 1], 0.05f)
        }
    }

    @Test
    fun `the arc terminates on the ground, one ball radius up`() {
        val out = FloatArray(ARC_POINTS * 2)
        val y0 = ground + BALL_R
        val n = ballisticArc(60f, y0, 40f, 34f, GRAVITY, ARC_STEP_S, BALL_R, track, out)
        assertTrue(n in 3..ARC_POINTS)
        val lastX = out[2 * (n - 1)]
        val lastY = out[2 * (n - 1) + 1]
        assertEquals("lands on the trace", track.groundAt(lastX) + BALL_R, lastY, 1e-3f)
        // Everything before the last point is still in the air.
        for (i in 1 until n - 1) {
            assertTrue("point $i is underground", out[2 * i + 1] > track.groundAt(out[2 * i]) + BALL_R)
        }
    }

    @Test
    fun `a flatter shot lands shorter than a fuller one`() {
        fun carry(vx: Float, vy: Float): Float {
            val out = FloatArray(ARC_POINTS * 2)
            val n = ballisticArc(60f, ground + BALL_R, vx, vy, GRAVITY, ARC_STEP_S, BALL_R, track, out)
            return out[2 * (n - 1)] - 60f
        }
        assertTrue(carry(40f, 40f) > carry(40f, 10f))
        assertTrue(carry(50f, 30f) > carry(25f, 30f))
    }

    @Test
    fun `the arc stops where the world does rather than drawing over nothing`() {
        val out = FloatArray(ARC_POINTS * 2)
        val end = track.length
        // Teed on the last metre of trace and hit hard: everything past the edge is not ground.
        val n = ballisticArc(end - 1f, ground + BALL_R, 55f, 20f, GRAVITY, ARC_STEP_S, BALL_R, track, out)
        assertTrue("must draw something", n >= 2)
        assertTrue("and must stop near the edge", out[2 * (n - 1)] >= end)
        assertTrue("without running to the sample cap", n < ARC_POINTS)
    }

    @Test
    fun `a shot with no speed draws no arc at all`() {
        val out = FloatArray(ARC_POINTS * 2)
        assertEquals(0, ballisticArc(60f, ground + BALL_R, 0f, 0f, GRAVITY, ARC_STEP_S, BALL_R, track, out))
        assertEquals(0, ballisticArc(60f, ground + BALL_R, 40f, 10f, 0f, ARC_STEP_S, BALL_R, track, out))
        assertEquals(0, ballisticArc(60f, ground + BALL_R, Float.NaN, 10f, GRAVITY, ARC_STEP_S, BALL_R, track, out))
    }

    @Test
    fun `the press radius is generous and never smaller than a fingertip`() {
        // A wide camera shrinks the ball to nothing; the floor is what keeps it grabbable.
        assertEquals(48f, grabRadiusPx(BALL_R, 0.01f, 48f), 1e-4f)
        assertEquals(BALL_R * 4f * GRAB_RADII, grabRadiusPx(BALL_R, 4f, 48f), 1e-3f)
        assertTrue(nearBall(10f, 10f, 48f))
        assertFalse(nearBall(60f, 0f, 48f))
    }

    @Test
    fun `full-scale pull scales with the panel, with a floor of its own`() {
        assertEquals(1080f * DRAG_FULL_FRAC, dragFullPx(1080f, 2000f), 1e-3f)
        assertEquals(600f * DRAG_FULL_FRAC, dragFullPx(1080f, 600f), 1e-3f)
        assertTrue("a tiny panel must still leave room past the dead radius",
            dragFullPx(10f, 10f) > DRAG_DEAD_PX)
    }
}
