package com.t1dm.data.curve

import com.t1dm.core.common.NativeCore
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.BasalSchedule
import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.CurveKind
import kotlinx.coroutines.withContext

/**
 * JNI bridge over `t1dm-core::curve`; every call on [T1dmDispatchers.default].
 * Carbs feed the model as an appearance (Ra) curve, insulin as a PK ACTION rate — not delivery,
 * not IOB. Basal and bolus sum into one `insulin_combined` channel.
 */
class CurveEngine(
    private val native: NativeCore,
    private val dispatchers: T1dmDispatchers,
) {
    /** Amount per 5-min step; `sum == total`. */
    suspend fun gamma(total: Double, k: Double, theta: Double, durMin: Double): DoubleArray =
        withContext(dispatchers.default) { native.gamma(total, k, theta, durMin).toDoubleArray() }

    /** Amount per 5-min step; `sum == total`. */
    suspend fun bateman(total: Double, durMin: Double, ka: Double, ke: Double): DoubleArray =
        withContext(dispatchers.default) { native.bateman(total, durMin, ka, ke).toDoubleArray() }

    /** Amount per 5-min step; `sum == total`. Peaks at [peakMin], ~0 by [diaMin]. Off-distribution. */
    suspend fun expAction(total: Double, peakMin: Double, diaMin: Double): DoubleArray =
        withContext(dispatchers.default) { native.expActionCurve(total, peakMin, diaMin).toDoubleArray() }

    suspend fun presetCatalog(): List<com.t1dm.core.model.InsulinPresetSpec> =
        withContext(dispatchers.default) { native.insulinPresetCatalog() }

    /** Window is half-open: `[gridStartMs, gridStartMs + nSteps·STEP_MS)`. */
    suspend fun bucketize(
        evs: List<CurveEvent>,
        gridStartMs: Long,
        nSteps: Int,
        kind: CurveKind,
    ): DoubleArray =
        withContext(dispatchers.default) { native.bucketize(evs, gridStartMs, nSteps, kind).toDoubleArray() }

    /** IOB/COB = remaining tail area. */
    suspend fun onBoard(evs: List<CurveEvent>, atMs: Long, kind: CurveKind): Double =
        withContext(dispatchers.default) { native.onBoard(evs, atMs, kind) }

    suspend fun extendBasal(schedule: BasalSchedule, fromMs: Long, toMs: Long): List<CurveEvent> =
        withContext(dispatchers.default) { native.extendBasal(schedule, fromMs, toMs) }

    suspend fun rapidEvent(units: Double, startMs: Long, peakMin: Double, diaMin: Double): CurveEvent =
        withContext(dispatchers.default) { CurveEvent(startMs, STEP_MS, CurveKind.INSULIN, units, native.expActionCurve(units, peakMin, diaMin)) }

    suspend fun carbEvent(grams: Double, startMs: Long, k: Double, theta: Double, durMin: Double): CurveEvent =
        CurveEvent(startMs, STEP_MS, CurveKind.CARB, grams, native.gamma(grams, k, theta, durMin))

    companion object {
        /** Must equal `t1dm-core::curve::STEP_MS`. */
        const val STEP_MS: Long = 300_000L

        private fun List<Double>.toDoubleArray(): DoubleArray = DoubleArray(size) { this[it] }
    }

    /** The simulator's noise-free central values; the model was trained on this neighbourhood. */
    object Presets {
        // Per hour. tmax ≈ 6.3 h, near-flat once tiled at cadence.
        const val BASAL_KA_PER_HOUR: Double = 0.30
        const val BASAL_KE_PER_HOUR: Double = 0.07

        /** Minutes. */
        const val LANTUS_DIA_MIN: Double = 24.0 * 60.0
        const val TRESIBA_DIA_MIN: Double = 42.0 * 60.0

        /** [gi] 0..100. */
        fun carbGammaForGi(gi: Double): Triple<Double, Double, Double> {
            val g = gi.coerceIn(0.0, 100.0) / 100.0 // 1.0 = highest GI
            val k = lerp(4.5, 2.0, g)
            val theta = lerp(30.0, 15.0, g)
            // ~5 mean-lives of tail, so the gamma integrates to ~grams.
            val durMin = ((k) * theta * 4.0).coerceIn(120.0, 360.0)
            return Triple(k, theta, durMin)
        }

        private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t
    }
}
