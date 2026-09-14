package com.t1dm.feature.game

import com.t1dm.core.design.HapticCue
import com.t1dm.core.model.BallState
import com.t1dm.core.model.GolfRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MAX_LAUNCH = 60f

class GolfFeelTest {

    private fun ball(
        vx: Float = 0f,
        vy: Float = 0f,
        atRest: Boolean = true,
        airborne: Boolean = false,
        strokes: Int = 0,
        penalties: Int = 0,
        impact: Float = 0f,
        run: GolfRun = GolfRun.Playing,
    ) = BallState(
        x = 0f, y = 0f, vx = vx, vy = vy, angle = 0f,
        atRest = atRest, airborne = airborne, strokes = strokes, penalties = penalties,
        impact = impact, run = run,
    )

    private fun seeded(first: BallState = ball()): GolfFeelTracker =
        GolfFeelTracker(MAX_LAUNCH).also { it.observe(first) }

    @Test
    fun `the first observation never fires — a ball placed in the air does not thud`() {
        val t = GolfFeelTracker(MAX_LAUNCH)
        val first = t.observe(ball(airborne = true, atRest = false, impact = 900f, strokes = 3))
        assertNull(first.cue)
        assertNull(first.sfx)
    }

    @Test
    fun `a strike fires once per shot`() {
        val t = seeded()
        val hit = t.observe(ball(vx = 40f, vy = 30f, atRest = false, strokes = 1))
        assertEquals(GameSfx.Strike, hit.sfx)
        assertEquals(HapticCue.Impact, hit.cue)
        // A level, not an edge, would re-fire every frame the stroke count stayed at one.
        repeat(10) {
            assertNotEquals(GameSfx.Strike, t.observe(ball(vx = 40f, atRest = false, strokes = 1)).sfx)
        }
        val second = t.observe(ball(vx = 20f, atRest = false, strokes = 2))
        assertEquals(GameSfx.Strike, second.sfx)
    }

    @Test
    fun `a strike is scaled by how hard it was hit`() {
        fun strike(speed: Float): Float {
            val t = seeded()
            return t.observe(ball(vx = speed, atRest = false, strokes = 1)).cueIntensity
        }
        val tap = strike(4f)
        val full = strike(MAX_LAUNCH)
        assertTrue("tap=$tap full=$full", full > tap)
        assertTrue("even a tap putt is felt", tap >= GolfFeelTracker.STRIKE_FLOOR)
        assertTrue("and nothing exceeds full", full <= 1f)
        // A speed past the cap cannot push the cue past unit scale.
        assertTrue(strike(1_000f) <= 1f)
    }

    @Test
    fun `water splashes once, and is not a bounce`() {
        val t = seeded()
        t.observe(ball(airborne = true, atRest = false, strokes = 1))
        val splash = t.observe(ball(atRest = true, penalties = 1, strokes = 1, impact = 500f))
        assertEquals(GameSfx.Splash, splash.sfx)
        assertEquals(HapticCue.Impact, splash.cue)
        assertEquals(GolfFeelTracker.SPLASH_CUE, splash.cueIntensity, 1e-5f)
        repeat(10) {
            assertNull(t.observe(ball(atRest = true, penalties = 1, strokes = 1)).sfx)
        }
        val second = t.observe(ball(atRest = true, penalties = 2, strokes = 1))
        assertEquals(GameSfx.Splash, second.sfx)
    }

    @Test
    fun `holing is not a bounce`() {
        val t = seeded()
        t.observe(ball(airborne = true, atRest = false, strokes = 2))
        val holed = t.observe(
            ball(atRest = true, strokes = 2, impact = 800f, run = GolfRun.Holed),
        )
        assertEquals(GameSfx.Holed, holed.sfx)
        assertEquals(HapticCue.Blip, holed.cue)
        // And nothing afterwards: the round is over, the world is frozen.
        repeat(10) {
            assertNull(t.observe(ball(strokes = 2, run = GolfRun.Holed, impact = 800f)).cue)
        }
    }

    @Test
    fun `a landing fires once, on the edge`() {
        val t = seeded()
        t.observe(ball(airborne = true, atRest = false, strokes = 1))
        val landing = t.observe(ball(airborne = false, atRest = false, strokes = 1, impact = 300f))
        assertEquals(HapticCue.Impact, landing.cue)
        assertEquals(GameSfx.Bounce, landing.sfx)
        repeat(10) {
            assertNull(t.observe(ball(atRest = false, strokes = 1, impact = 300f)).cue)
        }
    }

    @Test
    fun `resting on a slope is not a bump`() {
        val t = seeded()
        repeat(20) { assertNull(t.observe(ball(impact = 4f)).cue) }
    }

    @Test
    fun `a hard knock with the ball down is felt but not heard as a landing`() {
        val t = seeded()
        val bump = t.observe(ball(atRest = false, impact = GolfFeelTracker.BUMP_IMPULSE * 4f))
        assertEquals(HapticCue.Impact, bump.cue)
        assertNull("a grounded knock must not re-trigger the bounce voice", bump.sfx)
    }

    @Test
    fun `the bed is the roll, and only the roll`() {
        val t = seeded()
        assertEquals("a settled ball is silent", 0f, t.observe(ball(atRest = true)).bed, 0f)
        val flying = t.observe(ball(vx = 40f, atRest = false, airborne = true))
        assertEquals("weightlessness must read by absence", 0f, flying.bed, 0f)
        val rolling = t.observe(ball(vx = GolfFeelTracker.ROLL_FULL_MS, atRest = false)).bed
        assertEquals(GolfFeelTracker.ROLL_BED, rolling, 1e-5f)
        val crawling = t.observe(ball(vx = GolfFeelTracker.ROLL_FULL_MS / 2f, atRest = false)).bed
        assertTrue("crawl=$crawling roll=$rolling", crawling < rolling)
        assertTrue("the bed never leaves unit scale", t.observe(ball(vx = 900f, atRest = false)).bed <= 1f)
    }

    @Test
    fun `the engine voice is never asked for`() {
        val t = seeded()
        repeat(5) { assertEquals(0f, t.observe(ball(vx = 40f, atRest = false)).engine, 0f) }
    }

    @Test
    fun `the mute feel layer swallows a whole round`() {
        val feel = GolfFeel.None
        feel.resume()
        repeat(100) { feel.frame(ball(vx = 30f, atRest = false, strokes = 1)) }
        feel.frame(ball(penalties = 1, strokes = 1))
        feel.frame(ball(run = GolfRun.Holed, strokes = 2))
        feel.hold()
        feel.release()
        feel.resume()
        feel.close()
    }
}
