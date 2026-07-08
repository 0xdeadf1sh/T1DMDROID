package com.t1dm.core.common

import com.t1dm.core.model.BasalSchedule
import com.t1dm.core.model.BuiltContext
import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.DecodedAdvert
import com.t1dm.core.model.Forecast
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelDescriptor

/**
 * Kotlin-facing surface of the Rust `t1dm-core` crate. :app (and every consumer) depends on
 * THIS, never on the uniffi-generated binding directly; :core:native supplies the real impl.
 *
 * The Phase-1 FFI surface is frozen here as Kotlin signatures; the Rust bodies + the uniffi
 * wiring land in :core:native in the next phase. Every function is total on garbage input
 * (`panic = "abort"` in the crate) — a short or CRC-failing advert is `null`, never a panic.
 */
interface NativeCore {
    fun roundtrip(msg: String): String

    /** Decode + CRC-validate a 20-byte LinX advert payload (CGM.md §3.1/§3.2); `null` on a
     *  short or CRC-failing payload. */
    fun decodeAdvert(payload: ByteArray): DecodedAdvert?

    /** The advert CRC32 (CGM.md §3.2), as an unsigned value in the low 32 bits of the Long. */
    fun advertCrc32(payload: ByteArray): Long

    /** Kovatchev BG → risk-space transform `f` (used by the graph unit space + stats axis). */
    fun kovatchevF(mgdl: Double): Double

    /** Inverse Kovatchev transform `f_inv`, risk-space → mg/dL. */
    fun kovatchevFInv(risk: Double): Double

    // ── Model pre/post pipeline (Phase 2, INFERENCE.md §§6-8) ───────────────────────

    /** Parse a model `descriptor.json` (PLAN §2.4); `null` on malformed JSON / a missing field. */
    fun parseDescriptor(json: String): ModelDescriptor?

    /** Strictly-causal one-sided Savitzky-Golay smooth (INFERENCE.md §7.1); optional output
     *  clamps (BG → [20,500]; carb/insulin → min 0). */
    fun causalSmooth(series: List<Double>, clampMin: Double?, clampMax: Double?): List<Double>

    /** z-score a raw `[bg, carb, insulin]` sample (bg risk-z, carb/insulin log1p-z). */
    fun normalizeSample(desc: ModelDescriptor, bg: Double, carb: Double, insulin: Double): List<Double>

    /** Inverse of [normalizeSample]; the 3-element `z` must carry all channels. */
    fun denormalizeSample(desc: ModelDescriptor, z: List<Double>): List<Double>

    /** Build the normalized context + prediction zone + `last_bg` anchor from a raw
     *  per-step history (INFERENCE.md §7.2-7.4). Announced future doses are raw values or
     *  `null` (→ the `normalize(0)` no-dose baseline). Throws on a malformed shape. */
    fun buildContext(
        desc: ModelDescriptor,
        bg: List<Double>,
        carb: List<Double>,
        insulin: List<Double>,
        announcedCarb: List<Double>?,
        announcedInsulin: List<Double>?,
    ): BuiltContext

    /** Assemble `head_raw` (P·S·7, risk) into an ascending quantile fan and decode to mg/dL
     *  (INFERENCE.md §8; conformal OFF). `headRaw` is fp64-upcast by the backend. */
    fun assembleDecode(
        desc: ModelDescriptor,
        headRaw: List<Double>,
        lastBg: Double,
        carrySpread: Double,
    ): Forecast

    /** The safety guard every rail/alert gates on (§3.6-B): rejects non-finite, rail-pinned,
     *  collapsed-band, and mis-ordered forecasts. */
    fun forecastDegeneracyCheck(forecast: Forecast): ForecastStatus

    // ── Shared curve/PK engine (Phase 4, PLAN §3.3; bit-faithful to simulator.py) ────

    /** Gamma-distributed Ra/PK curve, amount-per-5-min-step, `sum == total` (carbs +
     *  bolus shape; == `simulator.gamma_curve`). */
    fun gamma(total: Double, k: Double, theta: Double, durMin: Double): List<Double>

    /** Bateman long-acting basal curve, amount-per-5-min-step, `sum == total`
     *  (== `simulator.basal_curve`, default 5 h tail-clip). */
    fun bateman(total: Double, durMin: Double, ka: Double, ke: Double): List<Double>

    /** Resolve a rapid-acting bolus of [doseU] into an insulin [CurveEvent] at `startMs=0`
     *  (dose-scaled DIA/θ; == `simulator.bolus_pk_for_dose` + `gamma_curve`). */
    fun bolusPkForDose(doseU: Double): CurveEvent

    /** Sum every [kind]-matching event's curve onto the fixed grid
     *  `[gridStartMs, gridStartMs + nSteps·STEP_MS)`; pre-grid tails carry forward. */
    fun bucketize(events: List<CurveEvent>, gridStartMs: Long, nSteps: Int, kind: CurveKind): List<Double>

    /** IOB/COB at [atMs] = the remaining tail area of every [kind]-matching event. */
    fun onBoard(events: List<CurveEvent>, atMs: Long, kind: CurveKind): Double

    /** Expand a daily-repeating [schedule] into the Bateman events whose action overlaps
     *  `[fromMs, toMs)` (the auto-extended near-flat basal background). */
    fun extendBasal(schedule: BasalSchedule, fromMs: Long, toMs: Long): List<CurveEvent>
}
