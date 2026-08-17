package com.t1dm.data.curve

import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.core.nativecore.StubNativeCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exercise disposal curve, against `../T1DMCOMMON/SPEC/invariants.md` §5.
 *
 * Two halves. The parameters are checked as literals, because §5 fixes them and a resolver that
 * quietly drifted off them would still produce a plausible-looking curve; the curve itself is
 * checked through the same [CurveEngine.gamma] the carbohydrate channel uses, over [StubNativeCore]
 * (the Kotlin port of `t1dm-core::curve`), so what is tested is the routine that actually runs.
 */
class ExerciseDisposalTest {

    private val dispatchers = DefaultT1dmDispatchers(
        main = Dispatchers.Unconfined,
        default = Dispatchers.Unconfined,
        io = Dispatchers.Unconfined,
        inference = Dispatchers.Unconfined,
    )
    private val engine = CurveEngine(StubNativeCore(), dispatchers)

    @Test
    fun `the shape is SPEC's, and it does not move with the bout`() {
        val short = ExerciseDisposal.paramsFor(20.0)
        val long = ExerciseDisposal.paramsFor(240.0)
        assertEquals(3.0, short.k, EPS)
        assertEquals(15.0, short.theta, EPS)
        assertEquals(short.k, long.k, EPS)
        assertEquals(short.theta, long.theta, EPS)
        // Peak at (k−1)·θ = 30 min, wherever the bout's length puts the window's end.
        assertEquals(30.0, (short.k - 1.0) * short.theta, EPS)
    }

    @Test
    fun `magnitude is duration times the rate, and the window is duration plus ninety`() {
        val p = ExerciseDisposal.paramsFor(60.0, 0.5)
        assertEquals(30.0, p.grams, EPS)
        assertEquals(150.0, p.durationMin, EPS)
    }

    @Test
    fun `magnitude scales with duration alone`() {
        // Doubling the bout doubles the grams; nothing else in this resolver can change them, which
        // is the whole point — an intensity-scaled magnitude is off-distribution for every model
        // pretrained on T1DMSIM.
        val one = ExerciseDisposal.paramsFor(30.0, 0.5)
        val two = ExerciseDisposal.paramsFor(60.0, 0.5)
        assertEquals(2.0 * one.grams, two.grams, EPS)
    }

    @Test
    fun `the default rate is SPEC's population constant`() {
        assertEquals(0.5, ExerciseDisposal.DEFAULT_CARB_EQUIV_PER_MIN, EPS)
        assertEquals(
            ExerciseDisposal.paramsFor(45.0, ExerciseDisposal.DEFAULT_CARB_EQUIV_PER_MIN),
            ExerciseDisposal.paramsFor(45.0),
        )
    }

    @Test
    fun `a per-patient rate scales the magnitude and nothing else`() {
        val lo = ExerciseDisposal.paramsFor(60.0, 0.3)
        val hi = ExerciseDisposal.paramsFor(60.0, 1.2)
        assertEquals(18.0, lo.grams, EPS)
        assertEquals(72.0, hi.grams, EPS)
        assertEquals(lo.k, hi.k, EPS)
        assertEquals(lo.theta, hi.theta, EPS)
        assertEquals(lo.durationMin, hi.durationMin, EPS)
    }

    @Test
    fun `a rate outside the rails is clamped, and a garbled one falls back to the default`() {
        assertEquals(
            ExerciseDisposal.MAX_CARB_EQUIV_PER_MIN * 60.0,
            ExerciseDisposal.paramsFor(60.0, 99.0).grams,
            EPS,
        )
        assertEquals(
            ExerciseDisposal.MIN_CARB_EQUIV_PER_MIN * 60.0,
            ExerciseDisposal.paramsFor(60.0, -1.0).grams,
            EPS,
        )
        assertEquals(30.0, ExerciseDisposal.paramsFor(60.0, Double.NaN).grams, EPS)
    }

    @Test
    fun `a bout that has recorded nothing has disposed of nothing`() {
        assertEquals(0.0, ExerciseDisposal.paramsFor(0.0).grams, EPS)
        assertEquals(0.0, ExerciseDisposal.paramsFor(-5.0).grams, EPS)
        assertEquals(0.0, ExerciseDisposal.paramsFor(Double.NaN).grams, EPS)
    }

    @Test
    fun `the curve sums to the magnitude`() = runTest {
        // §5: a curve is a per-five-minute rate series that SUMS to the event's total. Summing is the
        // invariant; the shape is the meaning.
        for (minutes in listOf(5.0, 20.0, 45.0, 90.0, 300.0)) {
            val p = ExerciseDisposal.paramsFor(minutes)
            val values = engine.gamma(p.grams, p.k, p.theta, p.durationMin)
            assertEquals("sum at $minutes min", p.grams, values.sum(), 1e-6)
            assertTrue("negative bucket at $minutes min", values.all { it >= 0.0 })
        }
    }

    @Test
    fun `the curve stops at the ninety-minute tail and carries no second one`() = runTest {
        // The post-exercise sensitivity boost runs for six hours and is a SEPARATE mechanism; a
        // consumer that folded it in here would count the same bout twice. A 30-minute bout is
        // therefore two hours of curve, not eight.
        val p = ExerciseDisposal.paramsFor(30.0)
        val values = engine.gamma(p.grams, p.k, p.theta, p.durationMin)
        assertEquals(24, values.size)
        assertEquals(120.0, p.durationMin, EPS)
    }

    @Test
    fun `the curve starts low, peaks near thirty minutes and decays`() = runTest {
        val p = ExerciseDisposal.paramsFor(60.0)
        val values = engine.gamma(p.grams, p.k, p.theta, p.durationMin)
        val peak = values.indices.maxByOrNull { values[it] }!!
        // Buckets are (i+1)·5 minutes, so the mode at 30 min lands on index 5.
        assertEquals(5, peak)
        assertTrue(values.first() < values[peak])
        assertTrue(values.last() < values[peak])
    }

    private companion object {
        const val EPS = 1e-9
    }
}
