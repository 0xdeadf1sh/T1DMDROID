package com.t1dm.data.curve

import com.t1dm.core.common.NativeCore
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.BasalSchedule
import com.t1dm.core.model.BolusPk
import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.DoseRecord
import com.t1dm.core.model.InsulinFamily
import com.t1dm.core.model.InsulinPresetSpec
import com.t1dm.core.model.PointDose
import com.t1dm.core.model.PointKind
import kotlinx.coroutines.withContext

/** JNI bridge over t1dm-core::curve; carbs feed as Ra, insulin as PK ACTION, not IOB. */
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

    suspend fun presetCatalog(): List<InsulinPresetSpec> =
        withContext(dispatchers.default) { native.insulinPresetCatalog() }

    /** The catalogue's first [family] entry: aspart, glargine U100. */
    suspend fun defaultPreset(family: InsulinFamily): InsulinPresetSpec = presetCatalog().first { it.family == family }

    suspend fun bolusPk(units: Double, spec: InsulinPresetSpec): BolusPk =
        withContext(dispatchers.default) { native.bolusPkForDose(units, spec.gammaK, spec.gammaTheta, spec.diaBaseHours) }

    /** Amount per 5-min step of [units] of [spec]; sum == units. */
    suspend fun presetCurve(units: Double, spec: InsulinPresetSpec): DoubleArray = when (spec.family) {
        InsulinFamily.RapidGamma -> bolusPk(units, spec).let { gamma(units, it.k, it.theta, it.durationMin) }
        InsulinFamily.BasalBateman -> bateman(units, spec.actionMin, spec.kaPerHour, spec.kePerHour)
    }

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

    /** A basal [spec] gives a basal: its curve and its point are the Bateman's. */
    suspend fun rapidEvent(units: Double, startMs: Long, spec: InsulinPresetSpec): CurveEvent {
        val record = when (spec.family) {
            InsulinFamily.RapidGamma -> bolusPk(units, spec).let {
                DoseRecord(startMs, PointKind.BOLUS, units, null, it.k, it.theta, null, null, it.durationMin, null)
            }
            InsulinFamily.BasalBateman -> DoseRecord(
                startMs, PointKind.BASAL, units, null, null, null, spec.kaPerHour, spec.kePerHour, spec.actionMin, null,
            )
        }
        val curve = presetCurve(units, spec).asList()
        return CurveEvent(startMs, STEP_MS, CurveKind.INSULIN, units, curve, pointDose(record))
    }

    /** [k], [theta], [durMin] lay the curve; [gi] is what an events-layout model reads. */
    suspend fun carbEvent(
        grams: Double,
        startMs: Long,
        k: Double,
        theta: Double,
        durMin: Double,
        gi: Double,
    ): CurveEvent {
        val record = DoseRecord(startMs, PointKind.CARB, grams, gi, null, null, null, null, durMin, null)
        return withContext(dispatchers.default) {
            val curve = native.gamma(grams, k, theta, durMin)
            CurveEvent(startMs, STEP_MS, CurveKind.CARB, grams, curve, native.pointDose(record))
        }
    }

    suspend fun pointDose(record: DoseRecord): PointDose =
        withContext(dispatchers.default) { native.pointDose(record) }

    /** `EVENT_CHANNELS` over `[gridStartMs, +nSteps·STEP_MS)`; an event with no point is absent. */
    suspend fun eventChannels(evs: List<CurveEvent>, gridStartMs: Long, nSteps: Int): List<DoubleArray> =
        withContext(dispatchers.default) {
            native.eventChannels(evs, gridStartMs, nSteps).map { it.toDoubleArray() }
        }

    companion object {
        /** Must equal `t1dm-core::curve::STEP_MS`. */
        const val STEP_MS: Long = 300_000L

        private fun List<Double>.toDoubleArray(): DoubleArray = DoubleArray(size) { this[it] }
    }

    /** SPEC/invariants.md §5; curve_golden.json pins it to `simulator.gi_gamma_params`. */
    object Presets {
        /** [gi] 0..100. */
        fun carbGammaForGi(gi: Double): Triple<Double, Double, Double> {
            val g = gi.coerceIn(0.0, 100.0) / 100.0 // 1.0 = highest GI
            val k = lerp(4.5, 2.0, g)
            val theta = lerp(30.0, 15.0, g)
            val durMin = ((k) * theta * 4.0).coerceIn(120.0, 360.0)
            return Triple(k, theta, durMin)
        }

        private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t
    }
}
