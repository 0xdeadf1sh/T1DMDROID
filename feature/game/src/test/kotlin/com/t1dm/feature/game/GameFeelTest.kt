package com.t1dm.feature.game

import com.t1dm.core.design.HapticCue
import com.t1dm.core.model.CarState
import com.t1dm.core.model.RunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The physics-to-senses mapping. Everything here is an opinion about feel that can only be wrong
 * silently: a landing that fires every frame reads as a broken actuator, one that never fires reads as
 * a dead one, and a rumble that ignores the ground reads as no rumble at all.
 */
class GameFeelTest {

    private fun car(
        rpm: Float = FeelTracker.IDLE_RPM,
        throttle: Float = 0f,
        vx: Float = 0f,
        roughness: Float = 0f,
        airborne: Boolean = false,
        impulse: Float = 0f,
        run: RunState = RunState.Running,
    ) = CarState(
        x = 0f, y = 0f, angle = 0f, vx = vx, vy = 0f, angularVelocity = 0f,
        rearX = 0f, rearY = 0f, rearAngle = 0f, rearOmega = 0f, rearContact = !airborne,
        frontX = 0f, frontY = 0f, frontAngle = 0f, frontOmega = 0f, frontContact = !airborne,
        rpm = rpm, throttleApplied = throttle, impactImpulse = impulse, roughness = roughness,
        airborne = airborne, distanceM = 0f, run = run, elapsedS = 0f,
    )

    /** The tracker seeds on its first observation, so a run always starts from a settled frame. */
    private fun seeded(first: CarState = car()): FeelTracker =
        FeelTracker().also { it.observe(first) }

    // ── the sustained bed ──────────────────────────────────────────────────────────────────────

    @Test
    fun `airborne is silence, not a quieter bed`() {
        val t = seeded()
        val flying = t.observe(car(rpm = 8_000f, throttle = 1f, vx = 9f, roughness = 1f, airborne = true))
        assertEquals("weightlessness must read by absence", 0f, flying.bed, 0f)
        assertEquals(0f, flying.texture, 0f)
    }

    @Test
    fun `the engine alone still gives a bed on smooth ground`() {
        val t = seeded()
        val idle = t.observe(car(rpm = FeelTracker.IDLE_RPM)).bed
        assertTrue("an idling engine must be felt", idle > 0f)
        val revving = t.observe(car(rpm = 8_000f, throttle = 1f)).bed
        assertTrue("idle=$idle revving=$revving", revving > idle)
    }

    @Test
    fun `roughness adds to the bed, but only while moving`() {
        val t = seeded()
        val parked = t.observe(car(rpm = 2_000f, roughness = 1f, vx = 0f)).bed
        val rolling = t.observe(car(rpm = 2_000f, roughness = 1f, vx = FeelTracker.SPEED_FULL_MS)).bed
        val smooth = t.observe(car(rpm = 2_000f, roughness = 0f, vx = FeelTracker.SPEED_FULL_MS)).bed
        assertEquals("a parked car is not vibrating from the ground", smooth, parked, 1e-5f)
        assertTrue("rough=$rolling smooth=$smooth", rolling > smooth)
    }

    @Test
    fun `texture is the surface, scaled by speed, and nothing else`() {
        val t = seeded()
        assertEquals(0f, t.observe(car(roughness = 0f, vx = 5f)).texture, 1e-6f)
        val half = t.observe(car(roughness = 0.5f, vx = FeelTracker.SPEED_FULL_MS)).texture
        assertEquals(0.5f, half, 1e-5f)
        val crawling = t.observe(car(roughness = 1f, vx = FeelTracker.SPEED_FULL_MS / 4f)).texture
        assertEquals(0.25f, crawling, 1e-5f)
    }

    @Test
    fun `the bed never leaves unit scale`() {
        val t = seeded()
        val loud = t.observe(
            car(rpm = 9_000f, throttle = 1f, vx = 40f, roughness = 1f),
        ).bed
        assertTrue("bed=$loud", loud in 0f..1f)
    }

    @Test
    fun `a terminal run is silent on both surfaces`() {
        val t = seeded()
        t.observe(car(run = RunState.Crashed))
        val after = t.observe(car(rpm = 5_000f, throttle = 1f, run = RunState.Crashed))
        assertEquals(0f, after.bed, 0f)
        assertEquals(0f, after.engine, 0f)
    }

    // ── the transients ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the first observation never fires — a car placed in the air does not thud`() {
        val t = FeelTracker()
        val first = t.observe(car(airborne = true, impulse = 900f))
        assertNull(first.cue)
        assertNull(first.sfx)
    }

    @Test
    fun `a landing fires once, on the edge`() {
        val t = seeded()
        t.observe(car(airborne = true))
        val landing = t.observe(car(airborne = false, impulse = 300f))
        assertEquals(HapticCue.Impact, landing.cue)
        assertEquals(GameSfx.Landing, landing.sfx)
        // …and never again while the wheels stay down.
        repeat(10) { assertNull(t.observe(car(airborne = false, impulse = 300f)).cue) }
    }

    @Test
    fun `a landing is scaled by its impulse`() {
        fun land(impulse: Float): Float {
            val t = seeded()
            t.observe(car(airborne = true))
            return t.observe(car(airborne = false, impulse = impulse)).cueIntensity
        }
        val kerb = land(20f)
        val drop = land(400f)
        assertTrue("kerb=$kerb drop=$drop", drop > kerb)
        assertTrue("even a kerb is felt", kerb >= FeelTracker.IMPACT_FLOOR)
        assertTrue("and nothing exceeds full", drop <= 1f)
    }

    @Test
    fun `impact intensity saturates rather than clipping`() {
        var previous = 0f
        listOf(0f, 10f, 50f, 140f, 500f, 5_000f, 1e9f).forEach { j ->
            val v = FeelTracker.impactIntensity(j)
            assertTrue("impulse $j gave $v", v in FeelTracker.IMPACT_FLOOR..1f)
            assertTrue("must be monotone at $j", v >= previous - 1e-6f)
            previous = v
        }
        assertEquals(FeelTracker.IMPACT_FLOOR, FeelTracker.impactIntensity(Float.NaN), 1e-6f)
    }

    @Test
    fun `a hard bump with the wheels down is still an impact`() {
        val t = seeded()
        val bump = t.observe(car(impulse = FeelTracker.BUMP_IMPULSE * 4f))
        assertEquals(HapticCue.Impact, bump.cue)
        // A level, not an edge, would re-fire every frame the load stayed high.
        assertNull(t.observe(car(impulse = FeelTracker.BUMP_IMPULSE * 4f)).cue)
        // It re-arms once the load drops back.
        t.observe(car(impulse = 0f))
        assertEquals(HapticCue.Impact, t.observe(car(impulse = FeelTracker.BUMP_IMPULSE * 4f)).cue)
    }

    @Test
    fun `resting on a slope is not a bump`() {
        val t = seeded()
        repeat(20) { assertNull(t.observe(car(impulse = 4f)).cue) }
    }

    @Test
    fun `a crash fires the shock exactly once, and no landing with it`() {
        val t = seeded()
        t.observe(car(airborne = true))
        val crash = t.observe(car(airborne = false, impulse = 800f, run = RunState.Crashed))
        assertEquals(HapticCue.Shock, crash.cue)
        assertEquals(GameSfx.Crash, crash.sfx)
        repeat(10) { assertNull(t.observe(car(run = RunState.Crashed, impulse = 800f)).cue) }
    }

    @Test
    fun `reaching the present is not a collision`() {
        val t = seeded()
        val done = t.observe(car(run = RunState.Finished))
        assertNull(done.cue)
        assertNull(done.sfx)
    }

    // ── the whole layer ────────────────────────────────────────────────────────────────────────

    @Test
    fun `the mute feel layer swallows a whole run`() {
        val feel = GameFeel.None
        feel.resume()
        repeat(100) { feel.frame(car(rpm = 6_000f, throttle = 1f, vx = 8f, roughness = 0.7f)) }
        feel.frame(car(run = RunState.Crashed))
        feel.hold()
        feel.release()
        feel.resume()
        feel.close()
    }
}
