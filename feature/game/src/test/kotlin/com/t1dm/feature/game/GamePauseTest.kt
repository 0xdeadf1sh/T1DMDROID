package com.t1dm.feature.game

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MS = 1_000_000L
private const val FRAME_120 = 8_333_333L
private const val FRAME_60 = 16_666_667L

/**
 * Vsync jitter as PHASE noise about a fixed refresh grid, which is what a panel actually does: the
 * callback times wander inside a band, they do not random-walk away from the nominal rate.
 */
private fun vsyncStamps(t0: Long, periodNs: Long, count: Int, jitterNs: Long, seed: Long): LongArray {
    val rng = Random(seed)
    return LongArray(count) { k ->
        t0 + (k + 1) * periodNs + ((rng.nextDouble() * 2.0 - 1.0) * jitterNs).toLong()
    }
}

/** Indices, into the callback train, of the callbacks the pacer chose to simulate. */
private fun FrameClockPacer.simulatedIndices(stamps: LongArray): List<Int> =
    stamps.indices.filter { tick(stamps[it], false) > 0f }

class GameHoldsTest {

    @Test
    fun `no hold is not paused`() {
        assertFalse(GameHolds.NONE.paused)
        assertNull(GameHolds.NONE.primary)
    }

    @Test
    fun `holds released out of order do not resume the world`() {
        // The failure a single boolean has: the screen goes to the background WHILE an alarm is up, the
        // alarm clears first, and a `paused = false` write resumes a game nobody is looking at.
        var h = GameHolds.NONE.with(GameHold.Background, true).with(GameHold.Modal, true)
        assertTrue(h.paused)
        h = h.with(GameHold.Modal, false)
        assertTrue("still backgrounded", h.paused)
        h = h.with(GameHold.Background, false)
        assertFalse(h.paused)
    }

    @Test
    fun `releasing a hold that was never taken is a no-op`() {
        val h = GameHolds.NONE.with(GameHold.Background, true).with(GameHold.Modal, false)
        assertTrue(h.paused)
        assertTrue(h.has(GameHold.Background))
        assertFalse(h.has(GameHold.Modal))
    }

    @Test
    fun `the earliest-declared hold is the reason reported`() {
        // Declaration order IS precedence, and Background is first: a backgrounded screen is why the
        // world is frozen even if a modal is also up over it.
        val h = GameHolds.NONE
            .with(GameHold.Modal, true)
            .with(GameHold.Background, true)
        assertEquals(GameHold.Background, h.primary)
    }

    @Test
    fun `taking the same hold twice is idempotent`() {
        val once = GameHolds.NONE.with(GameHold.Modal, true)
        val twice = once.with(GameHold.Modal, true)
        assertEquals(once.bits, twice.bits)
        assertFalse(twice.with(GameHold.Modal, false).paused)
    }
}

class GamePauseGateTest {

    @Test
    fun `the gate is the union of its reasons`() {
        val gate = GamePauseGate()
        assertFalse(gate.paused)
        gate.set(GameHold.Background, true)
        assertTrue(gate.paused)
        gate.set(GameHold.Modal, true)
        gate.set(GameHold.Background, false)
        assertTrue(gate.paused)
        gate.set(GameHold.Modal, false)
        assertFalse(gate.paused)
    }
}

class FrameClockPacerTest {

    @Test
    fun `the first frame simulates nothing`() {
        val p = FrameClockPacer()
        assertEquals(0f, p.tick(5_000 * MS, paused = false), 0f)
    }

    @Test
    fun `a 120 Hz panel is simulated at 60`() {
        val p = FrameClockPacer()
        var t = 1_000 * MS
        p.tick(t, false)
        var simulated = 0
        var total = 0f
        repeat(120) {
            t += FRAME_120
            val dt = p.tick(t, false)
            if (dt > 0f) {
                simulated++
                total += dt
            }
        }
        assertEquals("one frame in two", 60, simulated)
        // …and each carries a FULL frame of time, not half of one: skipping must not halve the timestep.
        assertEquals(16.667f, total / simulated, 0.01f)
    }

    @Test
    fun `a 60 Hz panel is simulated every frame despite its own jitter`() {
        // A callback that arrives 0.4 ms early is still THE frame for its slot, not an early one to be
        // skipped: reading it as early would collapse the rate to 30 fps every time it happened.
        val p = FrameClockPacer()
        var t = 1_000 * MS
        p.tick(t, false)
        var simulated = 0
        repeat(60) { i ->
            t += FRAME_60 + if (i % 2 == 0) -400_000L else 400_000L
            if (p.tick(t, false) > 0f) simulated++
        }
        assertEquals(60, simulated)
    }

    @Test
    fun `a jittery 120 Hz panel simulates every other callback and never every third`() {
        // The defect this pacer exists to fix: gating on elapsed-since-last-SIMULATED-frame leaves
        // 0.667 ms of margin across two 120 Hz callbacks, so any jitter past that waits for a third and
        // the frame train alternates ~16.7 / ~25 ms. Phase-locked, the cadence is unconditional.
        val p = FrameClockPacer()
        val t0 = 1_000 * MS
        p.tick(t0, false)
        val stamps = vsyncStamps(t0, FRAME_120, count = 400, jitterNs = 1_500_000L, seed = 20260724L)
        val fired = p.simulatedIndices(stamps)

        assertEquals("one callback in two", 200, fired.size)
        val gaps = fired.zipWithNext { a, b -> b - a }.distinct()
        assertEquals("no 3-callback gaps, and no doubled-up frames", listOf(2), gaps)
    }

    @Test
    fun `phase locking does not let the cadence drift over a long run`() {
        // Each frame carries real wall clock, so the simulated total must track the callback train it
        // was drawn from — no shortfall, no borrowed time.
        val p = FrameClockPacer()
        val t0 = 1_000 * MS
        p.tick(t0, false)
        val stamps = vsyncStamps(t0, FRAME_120, count = 600, jitterNs = 1_500_000L, seed = 7L)
        var total = 0f
        var simulated = 0
        var last = t0
        for (s in stamps) {
            val dt = p.tick(s, false)
            if (dt > 0f) {
                simulated++
                total += dt
                last = s
            }
        }
        assertEquals(300, simulated)
        assertEquals("real elapsed, summed", (last - t0) / 1_000_000f, total, 0.01f)
        assertEquals(16.667f, total / simulated, 0.1f)
    }

    @Test
    fun `a stall re-bases the phase instead of firing a catch-up burst`() {
        val p = FrameClockPacer()
        var t = 1_000 * MS
        p.tick(t, false)
        repeat(20) {
            t += FRAME_120
            p.tick(t, false)
        }

        t += 3_000 * MS
        val stalled = p.tick(t, false)
        assertTrue("the stall is handed over whole: $stalled", stalled > 2_990f)

        // Whatever the phase was owed, it is not repaid: the very next callbacks resume the ordinary
        // every-other cadence rather than a run of near-empty frames walking the backlog off.
        val stamps = LongArray(40) { t + (it + 1) * FRAME_120 }
        val fired = p.simulatedIndices(stamps)
        assertEquals(20, fired.size)
        assertEquals(listOf(2), fired.zipWithNext { a, b -> b - a }.distinct())
        assertEquals("no short first frame", 1, fired.first())
    }

    @Test
    fun `resuming after a long background costs one frame, not the whole absence`() {
        val p = FrameClockPacer()
        var t = 1_000 * MS
        p.tick(t, false)
        t += FRAME_60
        assertTrue(p.tick(t, false) > 0f)

        // Five minutes with the screen away. Frames may or may not keep arriving; either way nothing is
        // simulated and nothing is banked.
        repeat(50) {
            t += 6_000 * MS
            assertEquals(0f, p.tick(t, paused = true), 0f)
        }
        t += FRAME_60
        val resumed = p.tick(t, false)
        assertTrue("dt=$resumed", resumed > 0f && resumed < 20f)
    }

    @Test
    fun `a pause with no frames at all still resumes cleanly`() {
        // The frame clock stops entirely when the window detaches, so the loop simply blocks. The very
        // next callback carries the whole absence and must not be integrated.
        val p = FrameClockPacer()
        var t = 1_000 * MS
        p.tick(t, false)
        t += 300_000 * MS
        assertEquals(0f, p.tick(t, paused = true), 0f)
        t += FRAME_60
        assertEquals(16.667f, p.tick(t, false), 0.01f)
    }

    @Test
    fun `a genuine stall is handed over whole for the solver to clamp`() {
        // Not the pacer's decision: GameWorld.step consumes wall clock in fixed ticks and drops the
        // surplus past its own cap, and duplicating that here would give two places to get it wrong.
        val p = FrameClockPacer()
        var t = 1_000 * MS
        p.tick(t, false)
        t += 480 * MS
        assertEquals(480f, p.tick(t, false), 0.01f)
    }

    @Test
    fun `a clock that goes backwards re-marks instead of stepping`() {
        val p = FrameClockPacer()
        p.tick(9_000 * MS, false)
        assertEquals(0f, p.tick(1_000 * MS, false), 0f)
        assertEquals(16.667f, p.tick(1_000 * MS + FRAME_60, false), 0.01f)
    }
}
