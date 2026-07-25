package com.t1dm.feature.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The generator's arithmetic, checked where arithmetic can be checked.
 *
 * None of this needs an `AudioTrack`, which is the point of [GameSynth] holding no Android type: the
 * failures that matter here — a voice that never decays and pins the mixer, a sweep that steps instead
 * of glides, an overshoot that clips into a buzz — are all properties of the buffer, and a buffer is
 * something a JVM test can look at.
 */
class GameSynthTest {

    private val rate = 48_000
    private val block = 256

    private fun synth() = GameSynth(rate)

    /** Render [seconds] into one flat buffer, a HAL burst at a time, exactly as the audio thread does. */
    private fun GameSynth.take(seconds: Float): FloatArray {
        val frames = (seconds * rate).toInt() / block * block
        val out = FloatArray(frames)
        val buf = FloatArray(block)
        var i = 0
        while (i < frames) {
            render(buf, block)
            buf.copyInto(out, i, 0, block)
            i += block
        }
        return out
    }

    private fun FloatArray.peak(from: Int = 0): Float {
        var m = 0f
        for (i in from until size) m = maxOf(m, abs(this[i]))
        return m
    }

    /**
     * Zero crossings with hysteresis, so the induction noise riding on the engine cannot manufacture
     * one. A plain sign test counts every wobble near the axis; this only counts a genuine excursion.
     */
    private fun FloatArray.crossings(from: Int): Int {
        val gate = peak(from) * 0.3f
        if (gate <= 0f) return 0
        var n = 0
        var high = false
        for (i in from until size) {
            if (!high && this[i] > gate) {
                high = true
                n++
            } else if (high && this[i] < -gate) {
                high = false
            }
        }
        return n
    }

    // ── silence ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a synth nobody has asked for anything of is exactly silent`() {
        val out = synth().take(0.3f)
        assertEquals(0f, out.peak(), 0f)
    }

    @Test
    fun `the master fade silences everything, engine and one-shots alike`() {
        val s = synth()
        s.rpm = 6_000f
        s.load = 1f
        s.engine = 1f
        s.master = 0f
        s.trigger(GameSfx.Crash)
        assertEquals(0f, s.take(0.5f).peak(), 0f)
    }

    @Test
    fun `the engine level silences the engine without stopping the generator`() {
        val s = synth()
        s.master = 1f
        s.rpm = 6_000f
        s.engine = 0f
        assertEquals("no engine", 0f, s.take(0.4f).peak(), 1e-6f)
        // …and a one-shot still lands.
        s.trigger(GameSfx.Coin)
        assertTrue("a coin must still sound", s.take(0.4f).peak() > 0.02f)
    }

    // ── the engine voice ───────────────────────────────────────────────────────────────────────

    @Test
    fun `pitch tracks rpm`() {
        fun rateAt(rpm: Float): Int {
            val s = synth()
            s.master = 1f
            s.engine = 1f
            s.load = 0f
            s.rpm = rpm
            val out = s.take(1f)
            // Skip the first tenth: the master and pitch ramps are still settling there.
            return out.crossings(rate / 10)
        }
        val idle = rateAt(800f)
        val mid = rateAt(3_000f)
        val red = rateAt(9_000f)
        assertTrue("idle=$idle mid=$mid", mid > idle)
        assertTrue("mid=$mid red=$red", red > mid)
        // 800 → 9000 rpm is an 11× fundamental; a mapping that had collapsed to a constant would not
        // clear even a doubling.
        assertTrue("idle=$idle red=$red", red > idle * 3)
    }

    @Test
    fun `a throttle stab sweeps rather than steps`() {
        val s = synth()
        s.master = 1f
        s.engine = 1f
        s.rpm = 900f
        s.take(0.2f)
        // The whole point of the continuous mapping: a jump straight to the limiter must still arrive
        // as a glide, so no two adjacent samples may differ by more than a fraction of full scale.
        s.rpm = 9_000f
        s.load = 1f
        val out = s.take(0.5f)
        var worst = 0f
        for (i in 1 until out.size) worst = maxOf(worst, abs(out[i] - out[i - 1]))
        assertTrue("largest sample-to-sample step was $worst", worst < 0.35f)
    }

    @Test
    fun `load brightens the engine without changing its level much`() {
        fun render(load: Float): FloatArray {
            val s = synth()
            s.master = 1f
            s.engine = 1f
            s.rpm = 5_000f
            s.load = load
            return s.take(0.6f)
        }
        val quiet = render(0f)
        val hard = render(1f)
        fun energy(a: FloatArray): Double {
            var e = 0.0
            for (i in a.size / 3 until a.size) e += a[i].toDouble() * a[i]
            return e
        }
        // Brightness is a redistribution, not a boost: the two must be within a few dB of each other,
        // or "harmonic content tracks load" has quietly become "volume tracks load".
        val ratio = energy(hard) / energy(quiet)
        assertTrue("energy ratio $ratio", ratio in 0.4..2.5)
    }

    // ── one-shots ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every one-shot sounds, then falls silent`() {
        GameSfx.entries.forEach { sfx ->
            val s = synth()
            s.master = 1f
            s.take(0.1f)
            s.trigger(sfx)
            val out = s.take(2f)
            assertTrue("$sfx never sounded", out.peak() > 0.05f)
            // The tail: nothing may still be ringing two seconds later, or a run accumulates voices
            // until the pool is stolen from under the next landing.
            assertEquals("$sfx is still ringing", 0f, out.peak(out.size - rate / 4), 1e-4f)
        }
    }

    @Test
    fun `intensity scales a one-shot`() {
        fun peakOf(intensity: Float): Float {
            val s = synth()
            s.master = 1f
            s.take(0.1f)
            s.trigger(GameSfx.Landing, intensity)
            return s.take(0.6f).peak()
        }
        val soft = peakOf(0.25f)
        val hard = peakOf(1f)
        assertTrue("soft=$soft hard=$hard", hard > soft * 2f)
    }

    @Test
    fun `no one-shot starts with a step discontinuity`() {
        GameSfx.entries.forEach { sfx ->
            val s = synth()
            s.master = 1f
            s.take(0.2f)
            s.trigger(sfx)
            val out = s.take(0.3f)
            // The attack ramp exists so the onset is not a click; a click is a first sample that is
            // already most of the way to the peak.
            assertTrue("$sfx onset ${out[0]}", abs(out[0]) < 0.05f)
        }
    }

    @Test
    fun `the pool survives more triggers than it has voices`() {
        val s = synth()
        s.master = 1f
        s.engine = 1f
        s.rpm = 9_000f
        s.load = 1f
        repeat(40) {
            GameSfx.entries.forEach { sfx -> s.trigger(sfx) }
            s.take(0.02f)
        }
        // Cut the engine so the tail below is the one-shot pool and nothing else.
        s.engine = 0f
        val out = s.take(3f)
        assertEquals("everything must still decay away", 0f, out.peak(out.size - rate / 4), 1e-3f)
    }

    // ── headroom ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `output never leaves full scale, however much is happening`() {
        val s = synth()
        s.master = 1f
        s.engine = 1f
        s.rpm = 9_000f
        s.load = 1f
        repeat(6) { GameSfx.entries.forEach { sfx -> s.trigger(sfx) } }
        val out = s.take(1.5f)
        assertTrue("peak ${out.peak()}", out.peak() <= 1f)
    }

    @Test
    fun `reset drops every voice`() {
        val s = synth()
        s.master = 1f
        s.engine = 1f
        s.rpm = 5_000f
        s.trigger(GameSfx.Crash)
        s.take(0.1f)
        s.reset()
        s.master = 0f
        assertEquals(0f, s.take(0.3f).peak(), 0f)
    }
}
