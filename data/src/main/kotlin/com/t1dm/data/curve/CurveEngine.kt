package com.t1dm.data.curve

import com.t1dm.core.common.NativeCore
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.BasalSchedule
import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.CurveKind
import kotlinx.coroutines.withContext

/**
 * The shared curve/PK engine (§3.3) — a **thin JNI bridge** over the Rust
 * `t1dm-core::curve` functions, with every call dispatched on [T1dmDispatchers.default]. It
 * is the ONE transform that serves all three consumers: historical context channels,
 * announced-future what-if conditioning, and IOB/COB display (remaining tail area).
 *
 * THE KEY MODEL FACT (model-io-curves.md): carbs feed the model as an **appearance (Ra)**
 * gamma curve (grams-per-5-min summing to the meal total); insulin as a **PK ACTION** rate
 * (bolus gamma peaking ~50 min; basal broad Bateman near-flat), NOT delivery/IOB. Basal +
 * bolus sum into one `insulin_combined` channel.
 *
 * The numeric authority is the Rust; this class only marshals and dispatches. The canonical
 * NOISE-FREE presets ([Presets]) are the simulator's central values — an explicit
 * in-distribution choice (SPEC §3.3), not a proof of per-person fidelity.
 */
class CurveEngine(
    private val native: NativeCore,
    private val dispatchers: T1dmDispatchers,
) {
    /** Raw gamma Ra/PK curve (carbs + bolus shape), amount-per-5-min-step, `sum == total`. */
    suspend fun gamma(total: Double, k: Double, theta: Double, durMin: Double): DoubleArray =
        withContext(dispatchers.default) { native.gamma(total, k, theta, durMin).toDoubleArray() }

    /** Raw Bateman long-acting basal curve, amount-per-5-min-step, `sum == total`. */
    suspend fun bateman(total: Double, durMin: Double, ka: Double, ke: Double): DoubleArray =
        withContext(dispatchers.default) { native.bateman(total, durMin, ka, ke).toDoubleArray() }

    /** Loop/OpenAPS exponential insulin-action curve (OPT-IN clinical rapid preset, issue 19),
     *  amount-per-5-min-step, `sum == total`; peaks at [peakMin], ~0 by [diaMin]. Off-distribution. */
    suspend fun expAction(total: Double, peakMin: Double, diaMin: Double): DoubleArray =
        withContext(dispatchers.default) { native.expActionCurve(total, peakMin, diaMin).toDoubleArray() }

    /** The clinical insulin preset catalogue (issue 19): the insulin panel's chips, and the set the
     *  dose writers resolve a logged row's curve out of. */
    suspend fun presetCatalog(): List<com.t1dm.core.model.InsulinPresetSpec> =
        withContext(dispatchers.default) { native.insulinPresetCatalog() }

    /** Sum every [kind]-matching event's curve onto `[gridStartMs, gridStartMs + nSteps·STEP_MS)`. */
    suspend fun bucketize(
        evs: List<CurveEvent>,
        gridStartMs: Long,
        nSteps: Int,
        kind: CurveKind,
    ): DoubleArray =
        withContext(dispatchers.default) { native.bucketize(evs, gridStartMs, nSteps, kind).toDoubleArray() }

    /** IOB/COB at [atMs] = remaining tail area of every [kind]-matching event. */
    suspend fun onBoard(evs: List<CurveEvent>, atMs: Long, kind: CurveKind): Double =
        withContext(dispatchers.default) { native.onBoard(evs, atMs, kind) }

    /** Expand a daily schedule into the Bateman events whose action overlaps `[fromMs, toMs)`. */
    suspend fun extendBasal(schedule: BasalSchedule, fromMs: Long, toMs: Long): List<CurveEvent> =
        withContext(dispatchers.default) { native.extendBasal(schedule, fromMs, toMs) }

    // ── Higher-level resolvers used by the meal builder / dose logging ──────────────────

    /** A rapid-acting bolus [CurveEvent] for [units] at [startMs] using the selected preset's exponential action model. */
    suspend fun rapidEvent(units: Double, startMs: Long, peakMin: Double, diaMin: Double): CurveEvent =
        withContext(dispatchers.default) { CurveEvent(startMs, STEP_MS, CurveKind.INSULIN, units, native.expActionCurve(units, peakMin, diaMin)) }

    /**
     * A carb appearance [CurveEvent] for [grams] at [startMs] using an explicit gamma
     * ([k], [theta], [durMin]) — typically resolved from GI via [Presets.carbGammaForGi].
     */
    suspend fun carbEvent(grams: Double, startMs: Long, k: Double, theta: Double, durMin: Double): CurveEvent =
        CurveEvent(startMs, STEP_MS, CurveKind.CARB, grams, native.gamma(grams, k, theta, durMin))

    companion object {
        /** 5-min grid cadence (== `t1dm-core::curve::STEP_MS`). */
        const val STEP_MS: Long = 300_000L

        private fun List<Double>.toDoubleArray(): DoubleArray = DoubleArray(size) { this[it] }
    }

    /**
     * The canonical NOISE-FREE presets, transcribed from `simulator.py` (SPEC §3.3). Quick-
     * preset defaults for the insulin/food builders; the model was trained on this
     * neighbourhood.
     */
    object Presets {
        // Long-acting basal Bateman rates; tmax ≈ 6.3 h, near-flat once tiled at cadence.
        const val BASAL_KA_PER_HOUR: Double = 0.30
        const val BASAL_KE_PER_HOUR: Double = 0.07

        /** Nominal quick-preset DIA (minutes): Lantus (glargine) ~24 h, Tresiba (degludec) ~42 h. */
        const val LANTUS_DIA_MIN: Double = 24.0 * 60.0
        const val TRESIBA_DIA_MIN: Double = 42.0 * 60.0

        /**
         * Map a glycemic index [gi] (0..100) onto the carb appearance gamma `(k, theta, durMin)`.
         * High GI (juice ⇒ fast, high early peak) sits at the simulator's fast-carb central
         * values (k≈2, θ≈15); low GI (bread/pasta ⇒ spread) at its slow-carb values (k≈4.5,
         * θ≈30). Linear interpolation in between; duration scales with the tail so `sum==grams`.
         */
        fun carbGammaForGi(gi: Double): Triple<Double, Double, Double> {
            val g = gi.coerceIn(0.0, 100.0) / 100.0 // 1.0 = highest GI
            val k = lerp(4.5, 2.0, g)      // low GI → k 4.5 (spread); high GI → k 2.0 (early peak)
            val theta = lerp(30.0, 15.0, g)
            // Peak = (k-1)·θ; give ~5 mean-lives of tail so the gamma integrates to ~grams.
            val durMin = ((k) * theta * 4.0).coerceIn(120.0, 360.0)
            return Triple(k, theta, durMin)
        }

        private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t
    }
}
