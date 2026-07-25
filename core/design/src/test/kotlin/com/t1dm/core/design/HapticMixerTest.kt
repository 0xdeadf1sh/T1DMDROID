package com.t1dm.core.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arbitration in [HapticMixer], driven against a fake actuator and a hand-cranked clock.
 *
 * Everything asserted here is invisible on a device: a duck that never lifts feels like a bug in the
 * physics, a cue silently queued instead of dropped feels like input lag, and an intensity that scales
 * the bed but not the cues feels like nothing at all. All three are arithmetic, so all three are
 * tested where arithmetic can be — the Android render paths are deliberately behind [HapticRenderer]
 * precisely so this file needs no vibrator.
 */
class HapticMixerTest {

    /** Every actuator write, in order. The renderer interface has no `cancel`, which is decision 2 made
     *  structural: there is no method here through which a §3.6-A alarm buzz could be shortened. */
    private class FakeRenderer : HapticRenderer {
        val arms = mutableListOf<Triple<Float, Float, Float>>()
        val shots = mutableListOf<List<HapticStep>>()

        override fun arm(from: Float, to: Float, texture: Float) {
            arms += Triple(from, to, texture)
        }

        override fun oneShot(steps: List<HapticStep>) {
            shots += steps
        }

        val writes: Int get() = arms.size + shots.size
    }

    private class Clock(var ms: Long = 0L) {
        fun advance(by: Long) {
            ms += by
        }
    }

    private class Rig(strength: HapticStrength = HapticStrength.STANDARD) {
        val renderer = FakeRenderer()
        val clock = Clock()
        var strength: HapticStrength = strength
        val mixer = HapticMixer(renderer, { this.strength }, { clock.ms })

        /** One frame of a 60 fps surface holding the bed at [level]. */
        fun frame(level: Float, texture: Float = 0f, dtMs: Long = 16L) {
            clock.advance(dtMs)
            mixer.bed(level, texture)
        }
    }

    private fun Rig.lastArm() = renderer.arms.last()

    // ── intensity ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `off costs nothing at all — no arm, no shot, at any level`() {
        val rig = Rig(HapticStrength.OFF)
        repeat(60) { rig.frame(1f, texture = 1f) }
        HapticCue.entries.forEach { rig.mixer.cue(it, 1f) }
        assertEquals("OFF must never reach the actuator", 0, rig.renderer.writes)
    }

    @Test
    fun `off mid-run lapses the bed and never arms again`() {
        val rig = Rig()
        repeat(4) { rig.frame(0.9f) }
        val armsWhileOn = rig.renderer.arms.size
        assertTrue(armsWhileOn > 0)
        rig.strength = HapticStrength.OFF
        repeat(60) { rig.frame(0.9f) }
        assertEquals("no arm may follow the switch to OFF", armsWhileOn, rig.renderer.arms.size)
    }

    @Test
    fun `the bed carries the user intensity, and STRONG saturates rather than overflows`() {
        fun armedAt(strength: HapticStrength): Float {
            val rig = Rig(strength)
            rig.frame(0.5f)
            return rig.lastArm().second
        }
        assertEquals(0.5f, armedAt(HapticStrength.STANDARD), 1e-5f)
        assertEquals(0.5f * 0.55f, armedAt(HapticStrength.SUBTLE), 1e-5f)
        assertEquals(0.5f * 1.4f, armedAt(HapticStrength.STRONG), 1e-5f)

        val loud = Rig(HapticStrength.STRONG)
        loud.frame(0.9f)
        assertEquals("STRONG clamps at full scale", 1f, loud.lastArm().second, 1e-5f)
    }

    @Test
    fun `a cue carries both its own intensity and the user level`() {
        val rig = Rig(HapticStrength.SUBTLE)
        rig.mixer.cue(HapticCue.Impact, intensity = 0.5f)
        val amp = rig.renderer.shots.single().single().amplitude
        assertEquals(0.85f * 0.5f * 0.55f, amp, 1e-5f)
    }

    @Test
    fun `a vanishingly quiet cue is floored, never lost`() {
        val rig = Rig(HapticStrength.SUBTLE)
        rig.mixer.cue(HapticCue.Blip, intensity = 0.01f)
        assertTrue(rig.renderer.shots.single().single().amplitude >= 0.05f)
    }

    @Test
    fun `a bed under the floor is silence, not a twitch`() {
        val rig = Rig()
        repeat(10) { rig.frame(0.01f) }
        assertEquals(0, rig.renderer.arms.size)
    }

    // ── the sustained layer ────────────────────────────────────────────────────────────────────

    @Test
    fun `the bed is re-armed at the control period, not at frame rate`() {
        val rig = Rig()
        // One second of a 60 fps surface.
        repeat(60) { rig.frame(0.7f) }
        val expected = (60 * 16L / CONTROL_PERIOD_MS).toInt()
        assertTrue(
            "60 frames armed ${rig.renderer.arms.size} times, wanted about $expected",
            rig.renderer.arms.size in (expected - 1)..(expected + 1),
        )
    }

    @Test
    fun `each arm re-enters at the amplitude the last one settled at`() {
        val rig = Rig()
        rig.frame(0.3f)
        repeat(8) { rig.frame(0.9f) }
        val arms = rig.renderer.arms
        assertTrue(arms.size >= 2)
        arms.zipWithNext { a, b -> assertEquals("a re-arm must continue, not restart", a.second, b.first, 1e-5f) }
    }

    @Test
    fun `silence is immediate — an airborne frame does not wait for the control period`() {
        val rig = Rig()
        rig.frame(0.8f)
        val armed = rig.renderer.arms.size
        // The very next frame: well inside the control period.
        rig.frame(0f)
        assertEquals("going silent must not arm anything", armed, rig.renderer.arms.size)
        // And the bed re-enters from zero, so the swell back is a swell.
        repeat(6) { rig.frame(0.8f) }
        assertEquals(0f, rig.renderer.arms[armed].first, 1e-5f)
    }

    // ── ducking ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a cue owns the actuator while it sounds, then the bed returns at the duck level`() {
        val rig = Rig()
        repeat(6) { rig.frame(0.8f) }
        val before = rig.renderer.arms.size

        rig.mixer.cue(HapticCue.Impact)
        val fired = rig.clock.ms
        assertEquals(1, rig.renderer.shots.size)

        // While the cue is on the actuator nothing else may be issued.
        rig.clock.ms = fired + HapticCue.Impact.durationMs / 2
        rig.mixer.bed(0.8f)
        assertEquals("the bed must not supersede its own cue", before, rig.renderer.arms.size)

        // Then the hush: present, but pressed almost flat.
        rig.clock.ms = fired + HapticCue.Impact.durationMs
        rig.mixer.bed(0.8f)
        val ducked = rig.lastArm().second
        assertTrue("ducked to $ducked, wanted near-silence", ducked > 0f && ducked < 0.8f * 0.2f)
    }

    @Test
    fun `the duck lifts to full the moment the window closes`() {
        val rig = Rig()
        repeat(6) { rig.frame(0.8f) }
        rig.mixer.cue(HapticCue.Impact)
        val fired = rig.clock.ms

        rig.clock.ms = fired + HapticCue.Impact.durationMs
        rig.mixer.bed(0.8f)
        rig.clock.ms = fired + DUCK_WINDOW_MS - 1
        rig.mixer.bed(0.8f)
        assertTrue("still ducked a millisecond short of the window", rig.lastArm().second < 0.8f)

        rig.clock.ms = fired + DUCK_WINDOW_MS
        rig.mixer.bed(0.8f)
        assertEquals("the hush must lift on the boundary", 0.8f, rig.lastArm().second, 1e-5f)
    }

    // ── priority, and the drop rule ────────────────────────────────────────────────────────────

    @Test
    fun `the loudest cue wins outright`() {
        val rig = Rig()
        rig.mixer.cue(HapticCue.Blip)
        rig.clock.advance(10)
        rig.mixer.cue(HapticCue.Shock)
        assertEquals("a shock must preempt a blip", 2, rig.renderer.shots.size)
        assertEquals(HapticCue.Shock.steps.size, rig.renderer.shots.last().size)
    }

    @Test
    fun `a later, quieter cue inside the window is dropped`() {
        val rig = Rig()
        rig.mixer.cue(HapticCue.Shock)
        rig.clock.advance(10)
        rig.mixer.cue(HapticCue.Impact)
        rig.clock.advance(10)
        rig.mixer.cue(HapticCue.Blip)
        assertEquals("only the shock may play", 1, rig.renderer.shots.size)
    }

    @Test
    fun `an equal cue inside the window is dropped, so a stutter is not a machine gun`() {
        val rig = Rig()
        rig.mixer.cue(HapticCue.Impact)
        repeat(5) {
            rig.clock.advance(15)
            rig.mixer.cue(HapticCue.Impact)
        }
        assertEquals(1, rig.renderer.shots.size)
    }

    @Test
    fun `a dropped cue is never played later — nothing is queued`() {
        val rig = Rig()
        rig.mixer.cue(HapticCue.Shock)
        rig.clock.advance(10)
        rig.mixer.cue(HapticCue.Blip)
        assertEquals(1, rig.renderer.shots.size)
        // A whole second of frames: a queue would drain into one of them.
        repeat(60) { rig.frame(0.6f) }
        assertEquals("the dropped blip must be gone, not deferred", 1, rig.renderer.shots.size)
    }

    @Test
    fun `once the window has passed the same cue plays again`() {
        val rig = Rig()
        rig.mixer.cue(HapticCue.Impact)
        rig.clock.advance(DUCK_WINDOW_MS + 1)
        rig.mixer.cue(HapticCue.Impact)
        assertEquals(2, rig.renderer.shots.size)
    }

    @Test
    fun `the priority order is the declared one`() {
        assertTrue(HapticCue.Blip.priority < HapticCue.Impact.priority)
        assertTrue(HapticCue.Impact.priority < HapticCue.Shock.priority)
    }

    // ── the alarm interlock ────────────────────────────────────────────────────────────────────

    @Test
    fun `release hands the actuator back and refuses everything after it`() {
        val rig = Rig()
        repeat(6) { rig.frame(0.9f, texture = 0.5f) }
        assertTrue(rig.renderer.arms.isNotEmpty())
        val writes = rig.renderer.writes

        rig.mixer.release()
        repeat(120) { rig.frame(0.9f, texture = 0.5f) }
        HapticCue.entries.forEach { rig.mixer.cue(it) }
        assertEquals("nothing may reach the actuator while an alarm holds", writes, rig.renderer.writes)
    }

    @Test
    fun `resume takes the actuator back, entering from silence`() {
        val rig = Rig()
        repeat(6) { rig.frame(0.9f) }
        rig.mixer.release()
        repeat(6) { rig.frame(0.9f) }
        val before = rig.renderer.arms.size

        rig.mixer.resume()
        repeat(6) { rig.frame(0.9f) }
        assertTrue("the bed must come back", rig.renderer.arms.size > before)
        assertEquals("and swell rather than snap", 0f, rig.renderer.arms[before].first, 1e-5f)
    }

    @Test
    fun `close is permanent and idempotent`() {
        val rig = Rig()
        rig.mixer.close()
        rig.mixer.close()
        rig.mixer.resume()
        repeat(60) { rig.frame(1f) }
        rig.mixer.cue(HapticCue.Shock)
        assertEquals(0, rig.renderer.writes)
    }

    @Test
    fun `the silent mixer is inert`() {
        HapticMixer.None.bed(1f, 1f)
        HapticMixer.None.cue(HapticCue.Shock)
        HapticMixer.None.release()
        HapticMixer.None.resume()
    }

    // ── the kernel's own contract ──────────────────────────────────────────────────────────────

    @Test
    fun `the kernel reports a drop as null and an acceptance as steps`() {
        val clock = Clock()
        val core = HapticMixCore { clock.ms }
        assertNotNull(core.cue(HapticCue.Impact, 1f, HapticStrength.STANDARD))
        clock.advance(5)
        assertNull(core.cue(HapticCue.Blip, 1f, HapticStrength.STANDARD))
        assertNull(core.cue(HapticCue.Impact, 1f, HapticStrength.OFF))
    }

    @Test
    fun `the kernel lapses exactly once on the way down`() {
        val clock = Clock()
        val core = HapticMixCore { clock.ms }
        clock.advance(16)
        assertTrue(core.bed(0.8f, 0f, HapticStrength.STANDARD) is BedAction.Arm)
        clock.advance(16)
        assertEquals(BedAction.Lapse, core.bed(0f, 0f, HapticStrength.STANDARD))
        repeat(10) {
            clock.advance(16)
            assertEquals(BedAction.Idle, core.bed(0f, 0f, HapticStrength.STANDARD))
        }
        assertEquals(0f, core.armedAt, 1e-6f)
    }
}
