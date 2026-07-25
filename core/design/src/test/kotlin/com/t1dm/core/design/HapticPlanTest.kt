package com.t1dm.core.design

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The two parts of the haptics layer that can be wrong without anyone noticing on-device: the
 * intensity scaling (a mis-clamp reads as "Subtle feels the same as Standard", which nobody reports
 * as a bug) and the primitive-support fallback — which, contrary to what the name suggests, is the
 * ONLY path the target device ever takes: `dumpsys vibrator_manager` on the K90 reports
 * `supportedPrimitives = []` and `compositionSizeMax = 0`, so `areAllPrimitivesSupported` is false for
 * every recipe and the composition branch is the one that never runs there. Both are deliberately pure
 * — [hapticPlan] takes the support probe as a predicate precisely so a fake actuator can be handed to
 * it here.
 */
class HapticPlanTest {

    /** The `:alerts` baseline: CLICK + TICK are all a modest LRA is guaranteed to compose. */
    private val modestActuator: (Set<HapticPrimitive>) -> Boolean = { needed ->
        needed.all { it == HapticPrimitive.CLICK || it == HapticPrimitive.TICK }
    }

    private val fullActuator: (Set<HapticPrimitive>) -> Boolean = { true }

    private fun steps(event: HapticEvent, strength: HapticStrength, supports: (Set<HapticPrimitive>) -> Boolean) =
        (hapticPlan(event, strength, supports) as HapticPlan.Primitives).steps

    // ── intensity ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `off plays nothing at all`() {
        HapticEvent.entries.forEach { event ->
            assertNull(event.name, hapticPlan(event, HapticStrength.OFF, fullActuator))
        }
    }

    @Test
    fun `standard is the reference recipe, untouched`() {
        val scaled = steps(HapticEvent.Commit, HapticStrength.STANDARD, fullActuator)
        assertEquals(HapticEvent.Commit.recipe.steps, scaled)
    }

    @Test
    fun `subtle attenuates every step and strong lifts it`() {
        val subtle = steps(HapticEvent.Tap, HapticStrength.SUBTLE, fullActuator).single()
        val standard = steps(HapticEvent.Tap, HapticStrength.STANDARD, fullActuator).single()
        val strong = steps(HapticEvent.Tap, HapticStrength.STRONG, fullActuator).single()
        assertTrue(subtle.amplitude < standard.amplitude)
        assertTrue(standard.amplitude < strong.amplitude)
        assertEquals(0.50f * HapticStrength.SUBTLE.scale, subtle.amplitude, 1e-6f)
        assertEquals(0.50f * HapticStrength.STRONG.scale, strong.amplitude, 1e-6f)
    }

    @Test
    fun `strong saturates rather than overdriving the composition scale`() {
        // Commit's THUD is already at full amplitude; STRONG must clamp, not hand the vibrator 1.4.
        val thud = steps(HapticEvent.Commit, HapticStrength.STRONG, fullActuator)
            .single { it.primitive == HapticPrimitive.THUD }
        assertEquals(1f, thud.amplitude, 1e-6f)
    }

    @Test
    fun `the lightest event stays perceptible at the lowest intensity`() {
        val tick = steps(HapticEvent.SegmentTick, HapticStrength.SUBTLE, fullActuator).single()
        assertTrue("segment tick fell below the perceptual floor", tick.amplitude >= 0.05f)
    }

    @Test
    fun `scaling never alters rhythm`() {
        HapticStrength.entries.filter { it != HapticStrength.OFF }.forEach { strength ->
            val scaled = steps(HapticEvent.Reject, strength, fullActuator)
            assertEquals(
                HapticEvent.Reject.recipe.steps.map { it.primitive to it.delayMs },
                scaled.map { it.primitive to it.delayMs },
            )
        }
    }

    // ── primitive-unsupported fallback ─────────────────────────────────────────────────────────

    @Test
    fun `an event a modest actuator can compose stays on the primitive path`() {
        assertTrue(hapticPlan(HapticEvent.Tap, HapticStrength.STANDARD, modestActuator) is HapticPlan.Primitives)
        assertTrue(hapticPlan(HapticEvent.NavSwitch, HapticStrength.STANDARD, modestActuator) is HapticPlan.Primitives)
    }

    @Test
    fun `an event needing a richer primitive degrades to a waveform`() {
        // Commit needs THUD, Warn needs SPIN, ToggleOn a QUICK_RISE — none of which the modest
        // actuator has — while the CLICK half of the same recipe is irrelevant to the decision.
        listOf(HapticEvent.Commit, HapticEvent.Warn, HapticEvent.ToggleOn, HapticEvent.SegmentTick).forEach {
            assertTrue(it.name, hapticPlan(it, HapticStrength.STANDARD, modestActuator) is HapticPlan.Waveform)
        }
    }

    @Test
    fun `the fallback waveform preserves the recipe rhythm`() {
        val plan = hapticPlan(HapticEvent.Commit, HapticStrength.STANDARD, modestActuator) as HapticPlan.Waveform
        // CLICK, then 50 ms of silence, then a THUD.
        assertArrayEquals(
            longArrayOf(0, HapticPrimitive.CLICK.nominalMs, 50, HapticPrimitive.THUD.nominalMs),
            plan.waveform.timingsMs,
        )
        assertArrayEquals(intArrayOf(0, (0.70f * 255).roundToInt(), 0, 255), plan.waveform.amplitudes)
    }

    @Test
    fun `the fallback waveform is scaled by the intensity too`() {
        val subtle = (hapticPlan(HapticEvent.Commit, HapticStrength.SUBTLE, modestActuator) as HapticPlan.Waveform)
        val standard = (hapticPlan(HapticEvent.Commit, HapticStrength.STANDARD, modestActuator) as HapticPlan.Waveform)
        assertTrue(subtle.waveform.amplitudes[1] < standard.waveform.amplitudes[1])
        assertTrue(subtle.waveform.amplitudes[3] < standard.waveform.amplitudes[3])
        // Vibrator amplitudes are 1..255; a scaled-to-nothing step must still be a legal segment.
        assertTrue(subtle.waveform.amplitudes.filterIndexed { i, _ -> i % 2 == 1 }.all { it in 1..255 })
    }

    @Test
    fun `every waveform is a legal createWaveform argument`() {
        HapticEvent.entries.forEach { event ->
            val waveform = event.recipe.scaled(HapticStrength.SUBTLE).toWaveform()
            assertEquals(event.name, waveform.timingsMs.size, waveform.amplitudes.size)
            assertTrue(event.name, waveform.timingsMs.any { it > 0 })
            assertTrue(event.name, waveform.timingsMs.all { it >= 0 })
            assertTrue(event.name, waveform.amplitudes.all { it in 0..255 })
        }
    }

    // ── the vocabulary itself ──────────────────────────────────────────────────────────────────

    @Test
    fun `only the gesture-riding events are rate limited`() {
        val limited = HapticEvent.entries.filter { it.minIntervalMs > 0 }.toSet()
        assertEquals(
            setOf(HapticEvent.SegmentTick, HapticEvent.ScrubTick, HapticEvent.EdgeStop),
            limited,
        )
    }

    @Test
    fun `unknown or absent persisted intensity falls back to the default`() {
        assertEquals(HapticStrength.DEFAULT, HapticStrength.forKey(null))
        assertEquals(HapticStrength.DEFAULT, HapticStrength.forKey("VIGOROUS"))
        assertEquals(HapticStrength.DEFAULT, HapticStrength.forKey("subtle"))
        assertEquals(HapticStrength.SUBTLE, HapticStrength.forKey("SUBTLE"))
    }
}
