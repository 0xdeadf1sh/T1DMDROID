package com.t1dm.feature.game

import com.t1dm.core.model.GolfRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Arbitrary: the figure is a function OF the tuning, so nothing here mirrors a shipped number. */
private const val BALL_R = 3f
private const val MAX_LAUNCH = 60f

private const val GROUND = 10f
private const val DT = 1f / 60f

/** Where the figure stands to play a ball at [x]. */
private fun stanceOf(x: Float) = x - 1.3f * BALL_R

private val flat: (Float) -> Float = { GROUND }

private fun teed(x: Float = 0f) = BallFrame().apply {
    this.x = x
    atRest = true
}

class GolferTest {

    private fun placed(f: BallFrame): Golfer =
        Golfer(BALL_R, MAX_LAUNCH).also { it.place(f, flat) }

    /** Strikes the ball, then plays out the swing and its follow-through. */
    private fun strike(g: Golfer, f: BallFrame, strokes: Int) {
        f.strokes = strokes
        f.atRest = false
        repeat(60) { g.advance(f, g.pullOf(f), DT, flat) }
    }

    @Test
    fun `a tee stands the figure at the stance, addressing, at once`() {
        val f = teed(137f)
        val g = placed(f)
        assertTrue(f.golferShown)
        assertEquals(stanceOf(137f), f.golferX, 1e-3f)
        assertEquals(GROUND, f.golferY, 0f)
        assertEquals(Golfer.Act.Address, g.act)
        assertEquals(0, g.swings)
    }

    @Test
    fun `the walk arrives at the stance and stops there`() {
        val f = teed()
        val g = placed(f)
        strike(g, f, 1)
        f.x = 120f
        f.atRest = true
        repeat(600) { g.advance(f, g.pullOf(f), DT, flat) }
        assertEquals(Golfer.Act.Address, g.act)
        assertEquals(stanceOf(120f), f.golferX, 0.05f)
        assertEquals("and it stays on the ground", GROUND, f.golferY, 0f)
        // Arrived is arrived: nothing must creep past the ball afterwards.
        repeat(300) { g.advance(f, g.pullOf(f), DT, flat) }
        assertEquals(stanceOf(120f), f.golferX, 0.05f)
    }

    @Test
    fun `the backswing is monotone in the pull, and unwinds when the aim is cancelled`() {
        val f = teed()
        val g = placed(f)
        var previous = -1f
        for (pull in listOf(0f, 0.2f, 0.5f, 0.8f, 1f, 4f)) {
            f.aiming = true
            f.aimVx = pull * MAX_LAUNCH
            g.advance(f, g.pullOf(f), DT, flat)
            assertTrue("pull $pull gave ${f.shoulderRad}", f.shoulderRad >= previous - 1e-4f)
            previous = f.shoulderRad
        }
        assertEquals("a full pull is a full turn", BACKSWING_RAD, previous, 1e-4f)

        f.aiming = false
        f.aimVx = 0f
        repeat(120) { g.advance(f, g.pullOf(f), DT, flat) }
        assertEquals(Golfer.Act.Address, g.act)
        assertEquals("the shoulders unwind", 0f, f.shoulderRad, 0.02f)
        assertEquals("a cancel is not a stroke", 0, g.swings)
    }

    @Test
    fun `a stroke swings exactly once`() {
        val f = teed()
        val g = placed(f)
        strike(g, f, 1)
        assertEquals(1, g.swings)
        repeat(120) { g.advance(f, g.pullOf(f), DT, flat) }
        assertEquals("a level, not an edge, would swing every frame", 1, g.swings)
        strike(g, f, 2)
        assertEquals(2, g.swings)
    }

    @Test
    fun `the swing carries the club through the ball`() {
        val f = teed()
        val g = placed(f)
        f.aiming = true
        f.aimVx = MAX_LAUNCH
        repeat(10) { g.advance(f, g.pullOf(f), DT, flat) }
        val top = f.shoulderRad
        assertTrue("wound up: $top", top > 0.9f * BACKSWING_RAD)
        f.aiming = false
        f.aimVx = 0f
        f.strokes = 1
        f.atRest = false
        repeat(12) { g.advance(f, g.pullOf(f), DT, flat) }
        assertTrue("the arms finish past the ball: ${f.shoulderRad}", f.shoulderRad < 0f)
        assertTrue("and the club is still catching up", f.clubRad > f.shoulderRad)
    }

    @Test
    fun `the figure watches the flight without moving`() {
        val f = teed()
        val g = placed(f)
        f.strokes = 1
        f.atRest = false
        g.advance(f, g.pullOf(f), DT, flat)
        val stood = f.golferX
        for (i in 1..180) {
            f.x = i * 2f
            g.advance(f, g.pullOf(f), DT, flat)
        }
        assertEquals("a flying ball is watched, not chased", stood, f.golferX, 1e-3f)
        assertEquals("and watching is not walking", 0f, f.legPhase, 0f)
        assertEquals(Golfer.Act.Watch, g.act)
        assertTrue("with an arm up: ${f.armsUp}", f.armsUp > 0.5f)
    }

    @Test
    fun `a drowned ball is walked to where it is dropped`() {
        val f = teed()
        val g = placed(f)
        strike(g, f, 1)
        f.x = 200f
        repeat(30) { g.advance(f, g.pullOf(f), DT, flat) }
        // The solver has already dropped the ball back by the frame the penalty shows up on.
        f.penalties = 1
        f.x = 40f
        f.atRest = true
        repeat(600) { g.advance(f, g.pullOf(f), DT, flat) }
        assertEquals(Golfer.Act.Address, g.act)
        assertEquals(stanceOf(40f), f.golferX, 0.05f)
    }

    @Test
    fun `holing raises both arms and keeps them up`() {
        val f = teed()
        val g = placed(f)
        strike(g, f, 1)
        f.x = 90f
        f.atRest = true
        f.run = GolfRun.Holed.ordinal
        // One frame: a holed round freezes the loop, so the pose has to be complete at once.
        g.advance(f, g.pullOf(f), DT, flat)
        assertEquals(Golfer.Act.Cheer, g.act)
        assertEquals("arms up on the frame it is holed on", 1f, f.armsUp, 0f)
        // Terminal: the cheer stays, and the figure does not walk off to the cup.
        val stood = f.golferX
        repeat(600) { g.advance(f, g.pullOf(f), DT, flat) }
        assertEquals(Golfer.Act.Cheer, g.act)
        assertEquals(stood, f.golferX, 1e-3f)
        assertTrue(f.armsUp > 0.9f)
    }

    @Test
    fun `the walk is budgeted by distance, not run at a pace`() {
        // One frame to leave the watch, then the budget itself, plus slack for the last step.
        val frames = (ARRIVE_S / DT).toInt() + 4
        for (target in listOf(50f, 400f)) {
            val f = teed()
            val g = placed(f)
            strike(g, f, 1)
            f.x = target
            f.atRest = true
            repeat(frames) { g.advance(f, g.pullOf(f), DT, flat) }
            assertEquals("$target m arrives", Golfer.Act.Address, g.act)
            assertEquals(stanceOf(target), f.golferX, 0.05f)
        }
    }

    @Test
    fun `a ball still rolling after the watch times out is followed`() {
        val f = teed()
        val g = placed(f)
        f.strokes = 1
        f.atRest = false
        // Creeping and never settling: what a lie the turf almost holds leaves on the panel.
        repeat((WATCH_MAX_S / DT).toInt() + 60) {
            f.x += 0.02f
            g.advance(f, g.pullOf(f), DT, flat)
        }
        assertEquals("standing for minutes is not watching", Golfer.Act.Walk, g.act)
        assertEquals("and the stance re-aims each frame", stanceOf(f.x), f.golferX, 0.2f)
    }

    @Test
    fun `a gap crossed mid-walk keeps the figure running`() {
        val gapped: (Float) -> Float = {
            when {
                it >= 20f && it <= 40f -> Float.NaN
                it > 40f -> GROUND + 5f
                else -> GROUND
            }
        }
        val f = teed()
        val g = Golfer(BALL_R, MAX_LAUNCH).also { it.place(f, gapped) }
        strike(g, f, 1)
        f.x = 60f
        f.atRest = true
        repeat(40) { g.advance(f, g.pullOf(f), DT, gapped) }
        assertTrue("out over the gap: ${f.golferX}", f.golferX > 20f && f.golferX < 40f)
        assertEquals("the feet keep the last solid height", GROUND, f.golferY, 0f)
        assertTrue("and the legs keep turning", f.legPhase > 0f)
        repeat(60) { g.advance(f, g.pullOf(f), DT, gapped) }
        assertEquals(stanceOf(60f), f.golferX, 0.05f)
        assertEquals("onto the far side's ground", GROUND + 5f, f.golferY, 0f)
    }

    @Test
    fun `a gap under the feet keeps the last ground walked in at`() {
        val gapped: (Float) -> Float = { if (it > 20f) Float.NaN else GROUND }
        val f = teed()
        val g = Golfer(BALL_R, MAX_LAUNCH).also { it.place(f, gapped) }
        strike(g, f, 1)
        f.x = 60f
        f.atRest = true
        repeat(600) { g.advance(f, g.pullOf(f), DT, gapped) }
        assertEquals(stanceOf(60f), f.golferX, 0.05f)
        assertEquals("the feet keep the last solid height", GROUND, f.golferY, 0f)
    }

    @Test
    fun `a hostile frame never poisons the pose`() {
        val f = teed()
        val g = placed(f)
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1e30f)) {
            f.x = bad
            f.aiming = true
            f.aimVx = bad
            f.aimVy = bad
            g.advance(f, g.pullOf(f), bad, flat)
            assertTrue("x=${f.golferX}", f.golferX.isFinite())
            assertTrue(f.golferY.isFinite())
            assertTrue(f.shoulderRad.isFinite() && f.clubRad.isFinite())
            assertTrue(f.legPhase.isFinite() && f.armsUp.isFinite() && f.lean.isFinite())
        }
    }
}
