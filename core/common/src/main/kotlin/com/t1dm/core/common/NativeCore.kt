package com.t1dm.core.common

import com.t1dm.core.model.AdvancedStats
import com.t1dm.core.model.BaselineFit
import com.t1dm.core.model.BaselineForecast
import com.t1dm.core.model.BaselineModel
import com.t1dm.core.model.BaselineSpec
import com.t1dm.core.model.ClinicalCuts
import com.t1dm.core.model.BasalSchedule
import com.t1dm.core.model.BuiltContext
import com.t1dm.core.model.CarTuning
import com.t1dm.core.model.ClarkeZone
import com.t1dm.core.model.ConformalFit
import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.DecodedAdvert
import com.t1dm.core.model.ForecastWindow
import com.t1dm.core.model.MetricsConfig
import com.t1dm.core.model.MetricsSuite
import com.t1dm.core.model.Forecast
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.core.model.PredictedTime
import com.t1dm.core.model.StatSample
import com.t1dm.core.model.TerrainSpec

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

    /** Parse a model `descriptor.json` (SPEC §2.4); `null` on malformed JSON / a missing field. */
    fun parseDescriptor(json: String): ModelDescriptor?

    /** Strictly-causal one-sided Savitzky-Golay smooth (INFERENCE.md §7.1) over an ODD [window]
     *  (1 = unfiltered pass-through); optional output clamps (BG → [20,500]; carb/insulin → min 0)
     *  hold at every window. An out-of-contract window degrades to the default rather than throwing —
     *  [buildContext], the model-input path, rejects it instead. */
    fun causalSmooth(series: List<Double>, clampMin: Double?, clampMax: Double?, window: Int): List<Double>

    /** z-score a raw `[bg, carb, insulin]` sample (bg risk-z, carb/insulin log1p-z). */
    fun normalizeSample(desc: ModelDescriptor, bg: Double, carb: Double, insulin: Double): List<Double>

    /** Inverse of [normalizeSample]; the 3-element `z` must carry all channels. */
    fun denormalizeSample(desc: ModelDescriptor, z: List<Double>): List<Double>

    /** Build the normalized context + prediction zone + `last_bg` anchor from a raw
     *  per-step history (INFERENCE.md §7.2-7.4). Announced future doses are raw values or
     *  `null` (→ the `normalize(0)` no-dose baseline). [smoothingWindow] is the odd causal SavGol
     *  window applied to the **BG channel only** (1 = unfiltered); carb/insulin are analytic
     *  reconstructions and reach the model raw. Throws on a malformed shape OR an out-of-contract
     *  window — the window moves the §3.6-D `last_bg` anchor, so it fails closed here. */
    fun buildContext(
        desc: ModelDescriptor,
        bg: List<Double>,
        carb: List<Double>,
        insulin: List<Double>,
        announcedCarb: List<Double>?,
        announcedInsulin: List<Double>?,
        smoothingWindow: Int,
    ): BuiltContext

    /** Assemble `head_raw` (P·S·7, risk) into an ascending quantile fan and decode to mg/dL
     *  (INFERENCE.md §8). `headRaw` is fp64-upcast by the backend.
     *
     *  The fan this returns is the RAW one: no conformal correction is applied here, and none is
     *  applied to anything stored, pushed or classified. §8.4's recalibration is a display quantity
     *  fitted on device and applied downstream by [applyQuantileConformal]. */
    fun assembleDecode(
        desc: ModelDescriptor,
        headRaw: List<Double>,
        lastBg: Double,
        carrySpread: Double,
    ): Forecast

    /** The safety guard every rail/alert gates on (§3.6-B): rejects non-finite, rail-pinned,
     *  collapsed-band, and mis-ordered forecasts. Takes the [desc] the forecast was decoded
     *  with — the rails are descriptor-defined, and against the wrong physical range the
     *  rail-pin test cannot fire at all. */
    fun forecastDegeneracyCheck(desc: ModelDescriptor, forecast: Forecast): ForecastStatus

    /** Decode the time-probe's per-patch logits (flat `(P, nBins)`, the `.pte` slot-1 tensor)
     *  into a single hour-of-day belief via the `origin_patch` reduction (softmax patch 0 + mean
     *  resultant). `null` on a bad shape / non-finite logit (fail-open — the predicted hour is
     *  optional and never blocks the BG forecast). */
    fun decodeTime(timeLogits: List<Double>, nBins: Int, binHours: Double): PredictedTime?

    // ── Shared curve/PK engine (Phase 4, SPEC §3.3; bit-faithful to simulator.py) ────

    /** Gamma-distributed Ra/PK curve, amount-per-5-min-step, `sum == total` (carbs +
     *  bolus shape; == `simulator.gamma_curve`). */
    fun gamma(total: Double, k: Double, theta: Double, durMin: Double): List<Double>

    /** Bateman long-acting basal curve, amount-per-5-min-step, `sum == total`
     *  (== `simulator.basal_curve`, default 5 h tail-clip). */
    fun bateman(total: Double, durMin: Double, ka: Double, ke: Double): List<Double>

    /** Loop/OpenAPS exponential insulin-activity curve (OPT-IN clinical rapid presets, issue 19),
     *  amount-per-5-min-step, `sum == total`; peaks at [peakMin], ~0 by [diaMin]. Off-distribution. */
    fun expActionCurve(total: Double, peakMin: Double, diaMin: Double): List<Double>

    /** The clinical insulin preset catalogue (issue 19): the two in-distribution simulator defaults
     *  plus the OPT-IN, off-distribution rapid/basal presets, each with its peak/DIA + citation. */
    fun insulinPresetCatalog(): List<com.t1dm.core.model.InsulinPresetSpec>

    /** Sum every [kind]-matching event's curve onto the fixed grid
     *  `[gridStartMs, gridStartMs + nSteps·STEP_MS)`; pre-grid tails carry forward. */
    fun bucketize(events: List<CurveEvent>, gridStartMs: Long, nSteps: Int, kind: CurveKind): List<Double>

    /** IOB/COB at [atMs] = the remaining tail area of every [kind]-matching event. */
    fun onBoard(events: List<CurveEvent>, atMs: Long, kind: CurveKind): Double

    /** Expand a daily-repeating [schedule] into the Bateman events whose action overlaps
     *  `[fromMs, toMs)` (the auto-extended near-flat basal background). */
    fun extendBasal(schedule: BasalSchedule, fromMs: Long, toMs: Long): List<CurveEvent>

    // ── Advanced stats (Phase 6, SPEC §"Phase 6 — Stats"; t1dm-core::stats) ─────────

    /** The full advanced-stats block (TIR/TBR/TAR + sub-bands, LBGI/HBGI, MAGE, the shared
     *  server-parity block, per-channel totals, and the AGP percentile ribbon) over [samples]
     *  vs the `[targetLow, targetHigh]` mg/dL range, binned to [agpBins] time-of-day bins
     *  (must divide 1440). Fail-closed: an empty/all-invalid series yields
     *  [AdvancedStats.EMPTY]; a bad range/bin argument yields it too rather than throwing. */
    fun advancedStats(
        samples: List<StatSample>,
        targetLow: Int,
        targetHigh: Int,
        agpBins: Int,
    ): AdvancedStats

    /** The fixed clinical level-2 cuts (mg/dL) that [AdvancedStats.subBands] partitions on — the two
     *  numbers a glucose COLOUR SCALE anchors its extremes at. Read from the crate rather than
     *  restated on this side, so a scale cannot come to disagree with the fractions it is colouring.
     *  Fail-closed: a host stub with no library yields [ClinicalCuts.UNAVAILABLE], and a caller that
     *  cannot anchor must render nothing rather than a wrong scale. */
    fun clinicalCuts(): ClinicalCuts

    // ── Forecast accuracy (Phase 7C, t1dm-core::accuracy) ───────────────────────────

    /**
     * Score matured forecast [windows] the way `T1DMAI/realdata/metrics.py::compute_suite` does —
     * per horizon on the band projection of `SPEC/invariants.md` §6.2 with the median line nested
     * beneath, plus CG-EGA (§6.3) over the whole window. A horizon with fewer than
     * [MetricsConfig.minSamples] scored windows is still returned with its true `n` and
     * `sufficient = false`, so the UI can say "insufficient history" plainly.
     *
     * [includeCgEga] gates the CG-EGA pass alone: it walks every step of every window through the
     * P-EGA × R-EGA zone algebra, so a caller wanting only the level metrics passes `false` and
     * gets [MetricsSuite.cgega] `= null`. Total on any input; a bad argument yields
     * [MetricsSuite.EMPTY].
     */
    fun forecastMetricsSuite(
        windows: List<ForecastWindow>,
        horizonsMin: List<Int>,
        config: MetricsConfig,
        includeCgEga: Boolean,
    ): MetricsSuite

    /**
     * Classify a lattice of `(truth, pred)` mg/dL coordinates into Clarke zones, row-major and
     * TRUTH-MAJOR: cell `(i, j)` is `truthAxisMgdl[i]` against `predAxisMgdl[j]`, at index
     * `i * predAxisMgdl.size + j`.
     *
     * The reason this crosses the seam at all: the zone boundaries are inequalities inside the
     * core, and a figure drawing the five regions would otherwise transcribe them. It samples this
     * instead and paints what comes back, so an outline is the classifier's own verdict rasterised
     * rather than a second copy free to drift from the points it encloses.
     *
     * Fail-closed: an axis carrying a non-finite coordinate, or a lattice past the core's cell
     * ceiling, yields an empty list — no regions rather than wrong ones.
     */
    fun clarkeZoneGrid(truthAxisMgdl: List<Double>, predAxisMgdl: List<Double>): List<ClarkeZone>

    // ── Split-conformal band recalibration (t1dm-core::conformal, INFERENCE.md §8.4) ─

    /**
     * The smallest calibration count at which no quantile level's conformal order statistic is
     * clamped — derived from the level tuple of `SPEC/invariants.md` §6, not chosen. Below it the
     * extreme levels' offsets ARE the minimum and the maximum of the residual sample, so the
     * nominal coverage they claim is arithmetic that never ran. A caller's own threshold is raised
     * to this.
     */
    fun conformalMinCalWindows(): Int

    /**
     * Fit a per-`(step, τ)` additive band correction (§8.4) from matured forecast [windows], which
     * must be in the order they were made: the split is CHRONOLOGICAL, the older part fitted on and
     * the newer part held out and scored. A random split would read better and mean less — the
     * question a delta has to answer is whether a correction fitted on the past holds on the future.
     *
     * [minCalWindows] is the threshold on the CALIBRATION split, raised to [conformalMinCalWindows]
     * when it is below the point the arithmetic degenerates. Under it the result is
     * [ConformalFit.sufficient] `= false` with an all-zero delta. Total on any input; a core error
     * yields [ConformalFit.NONE], which is the same fail-closed answer.
     */
    fun fitQuantileConformal(windows: List<ForecastWindow>, minCalWindows: Int): ConformalFit

    /**
     * Apply a fitted delta to a band fan (§8.4): add, restore the median EXACTLY, then clamp
     * outward so the fan cannot cross. Both arrays are `steps · nQuantiles` step-major, ascending τ.
     *
     * Fail-closed to the RAW fan (`null`) on anything the core rejects — a length disagreement, a
     * non-finite value, or a delta whose median column is not zero. Never a partially corrected
     * fan, and never a fan drawn around a moved point forecast.
     */
    fun applyQuantileConformal(bandsMgdl: List<Double>, delta: List<Double>): List<Double>?

    // ── The classical baseline (t1dm-core::baseline) ────────────────────────────────

    /** The baseline's shipped shape and shrinkage — 12 lags, 24 steps, λ = 1.0, IOB and COB on.
     *  Read from the core rather than restated here so a retune moves one number, not two. */
    fun baselineDefaultSpec(): BaselineSpec

    /**
     * Fit the baseline and calibrate its band fan in one pass over the patient's own history.
     *
     * [bgMgdl] is a grid-aligned trailing series starting at [gridStartMs], newest last, with a
     * non-finite entry marking a gap — gaps DROP the affected design rows rather than being
     * filled, since `SPEC/invariants.md` §1 makes gap-filling a presentation step and never a
     * fitted value. [events] are the resolved carb and insulin curves spanning the same window,
     * including the tails of anything still acting when it opens, from which the core derives
     * strictly-causal IOB and COB.
     *
     * The window is split chronologically: the older part fits the weights, the newer part becomes
     * the conformal window set, and the conformal fit splits again inside itself — so the coverage
     * it reports was measured on windows neither the weights nor the delta ever saw.
     *
     * Total on any input in the sense that matters: `null` on anything the core rejects, which is
     * a window too short, a design with no complete rows, or a spec it cannot solve. A fit that
     * RAN but found too little held-out history returns non-null with
     * [com.t1dm.core.model.ConformalFit.sufficient] `= false` and an all-zero delta — a model that
     * predicts a median and has its forecasts withheld for want of an honest band.
     */
    fun fitBaselineRidge(
        bgMgdl: List<Double>,
        gridStartMs: Long,
        events: List<CurveEvent>,
        spec: BaselineSpec,
        nowMs: Long,
        minCalWindows: Int,
    ): BaselineFit?

    /**
     * Run a fitted baseline for one cycle. [bgTail] is the trailing `nLags` mg/dL values
     * OLDEST-FIRST; [iob]/[cob] are the causal on-board amounts at the anchor.
     *
     * The band estimator travels inside [model], so there is no uncalibrated fan a caller could
     * accidentally publish. `null` on anything the core rejects — a gap in the tail, a tail of the
     * wrong length, a model whose weights disagree with its spec — which the controller treats as
     * "no forecast this cycle" rather than substituting one.
     */
    fun baselinePredict(model: BaselineModel, bgTail: List<Double>, iob: Double, cob: Double): BaselineForecast?

    /**
     * The strictly-causal on-board amount at one instant — the IOB/COB feature
     * [baselinePredict] consumes, counting only events that had already STARTED at [atMs].
     *
     * Deliberately not [onBoard], which counts every matching event's remaining tail including an
     * announced future dose. That is the right reading for "insulin still to act" on the calculator,
     * and the wrong one for a design column: the fit builds its IOB/COB from started events only, so
     * a live cycle using the other definition would infer on a feature it never trained on.
     */
    fun baselineOnBoardAt(events: List<CurveEvent>, atMs: Long, kind: CurveKind): Double

    /**
     * The §3.6-B degeneracy guard for a forecast with no risk space: fan order judged on the
     * mg/dL bands, rails on the clinical physical domain. Shares its predicates and epsilons with
     * [forecastDegeneracyCheck] inside the core rather than restating them.
     *
     * An uncalibrated baseline lands on [ForecastStatus.CollapsedBand] here, which is the intended
     * withholding and not an error.
     */
    fun baselineDegeneracyCheck(forecast: BaselineForecast): ForecastStatus

    // ── Hill-climb minigame physics (t1dm-core::game) ───────────────────────────────

    /** The offroad car tune the game ships (large wheels, long travel, high torque, strong
     *  grip). Rust owns these numbers; nothing on this side transcribes them. */
    fun defaultCarTuning(): CarTuning

    /** Open a live physics world over [terrain]. The heightfield crosses the FFI exactly
     *  here — every later frame is one [GameWorld.step]. The caller OWNS the result and must
     *  [GameWorld.close] it. Throws on a degenerate terrain or tuning (a caller bug: the
     *  solver validates once here so the per-frame path can be total). */
    fun createGameWorld(terrain: TerrainSpec, tuning: CarTuning): GameWorld
}
