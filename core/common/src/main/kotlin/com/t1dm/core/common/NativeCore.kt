package com.t1dm.core.common

import com.t1dm.core.model.AdvancedStats
import com.t1dm.core.model.BaselineFit
import com.t1dm.core.model.BaselineForecast
import com.t1dm.core.model.BaselineModel
import com.t1dm.core.model.BaselineSpec
import com.t1dm.core.model.ClinicalCuts
import com.t1dm.core.model.BasalSchedule
import com.t1dm.core.model.GapRun
import com.t1dm.core.model.GraphInput
import com.t1dm.core.model.HeadSpec
import com.t1dm.core.model.LoraConfig
import com.t1dm.core.model.LoraProgressSink
import com.t1dm.core.model.LoraGuardOpts
import com.t1dm.core.model.LoraGuardReport
import com.t1dm.core.model.LoraSample
import com.t1dm.core.model.LoraTrainOpts
import com.t1dm.core.model.LoraTrainResult
import com.t1dm.core.model.LoraWeights
import com.t1dm.core.model.MaskSpan
import com.t1dm.core.model.SynthParams
import com.t1dm.core.model.SynthSeries
import com.t1dm.core.model.CarTuning
import com.t1dm.core.model.ClarkeZone
import com.t1dm.core.model.DtsZone
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

/** Kotlin-facing surface of the Rust `t1dm-core` crate; consumers depend on THIS, never on the
 *  uniffi-generated binding. Every function is total on garbage input (`panic = "abort"`). */
interface NativeHead : AutoCloseable {
    /** Attach an adapter, or detach with `null`. The base weights are untouched. */
    fun setLora(w: LoraWeights?)

    fun hasLora(): Boolean

    /** `head_raw` for [nSlots] hidden states, in the layout `assembleDecode` consumes. With no
     *  adapter attached it reproduces the graph's own `head_raw`. */
    fun forward(hidden: List<Double>, nSlots: Int): List<Double>
}

interface NativeCore {
    fun roundtrip(msg: String): String

    /** Decode + CRC-validate a 20-byte LinX advert payload (CGM.md §3.1/§3.2); `null` on a
     *  short or CRC-failing payload. */
    fun decodeAdvert(payload: ByteArray): DecodedAdvert?

    /** The advert CRC32 (CGM.md §3.2), as an unsigned value in the low 32 bits of the Long. */
    fun advertCrc32(payload: ByteArray): Long

    /** Kovatchev BG → risk-space transform `f`. */
    fun kovatchevF(mgdl: Double): Double

    /** Inverse Kovatchev transform `f_inv`, risk-space → mg/dL. */
    fun kovatchevFInv(risk: Double): Double


    /** Parse a model `descriptor.json` (SPEC §2.4); `null` on malformed JSON / a missing field. */
    fun parseDescriptor(json: String): ModelDescriptor?

    /** Strictly-causal one-sided Savitzky-Golay smooth (INFERENCE.md §7.1) over an ODD [window]
     *  (1 = pass-through). An out-of-contract window degrades to the default rather than throwing. */
    fun causalSmooth(series: List<Double>, clampMin: Double?, clampMax: Double?, window: Int): List<Double>

    /** z-score a raw `[bg, carb, insulin, exercise]` sample (bg risk-z, the rest log1p-z). */
    fun normalizeSample(
        desc: ModelDescriptor,
        bg: Double,
        carb: Double,
        insulin: Double,
        exercise: Double,
    ): List<Double>

    /** Inverse of [normalizeSample]; the 4-element `z` must carry all channels. */
    fun denormalizeSample(desc: ModelDescriptor, z: List<Double>): List<Double>

    /** The fixed-shape graph input from a raw per-step history (INFERENCE.md §§7.2-7.4).
     *  [exercise] is carbohydrate-EQUIVALENT disposal in g/step, positive. [smoothingWindow] is the
     *  odd causal SavGol window on the BG channel ONLY. Throws on a bad shape, masked set or window. */
    fun buildGraphInput(
        desc: ModelDescriptor,
        bg: List<Double>,
        carb: List<Double>,
        insulin: List<Double>,
        exercise: List<Double>,
        announcedCarb: List<Double>?,
        announcedInsulin: List<Double>?,
        announcedExercise: List<Double>?,
        maskSpans: List<MaskSpan>,
        withForecast: Boolean,
        smoothingWindow: Int,
    ): GraphInput

    /** `head_raw` (`M·S·7`, risk) → ascending quantile fan, decoded to mg/dL (INFERENCE.md §8). The
     *  fan is RAW; §8.4's correction is applied downstream by [applyQuantileConformal]. [carrySpread]
     *  is PER LEVEL: empty, one value, or six in `[.75 .9 .95 | .25 .1 .05]` — never broadcast. */
    fun assembleDecode(
        desc: ModelDescriptor,
        headRaw: List<Double>,
        anchors: List<Double>,
        slotPatch: List<Int>,
        nMasked: Int,
        carrySpread: List<Double>,
    ): Forecast

    /** The rows of [f] whose slot sits in `[fromPatch, toPatch)`, as a Forecast of its own. */
    fun forecastSlice(f: Forecast, fromPatch: Int, toPatch: Int): Forecast

    /** The fan's line at arbitrary [tau], mg/dL: interpolated in RISK space between the bracketing
     *  published levels, clamped outside them. Nothing that classifies a category may consume it. */
    fun bandLine(desc: ModelDescriptor, f: Forecast, tau: Double): List<Double>

    /** [bandLine] for a fan held on its own. [qTauRisk] is `steps × 7` risk-space values, ascending
     *  τ within each step. */
    fun bandLineAt(desc: ModelDescriptor, qTauRisk: List<Double>, tau: Double): List<Double>


    /** Digest checked against the descriptor's `head` block. `null` when they disagree: the head and
     *  the graph are not from the same export. */
    fun headOpen(bytes: ByteArray, spec: HeadSpec): NativeHead?

    /** Attaches nothing; the caller decides. [progress] is called once per epoch, on the calling thread. */
    fun loraTrain(
        head: NativeHead,
        desc: ModelDescriptor,
        samples: List<LoraSample>,
        config: LoraConfig,
        opts: LoraTrainOpts,
        progress: LoraProgressSink? = null,
    ): LoraTrainResult

    /** What an adapter did to the marginal response to one unit of insulin, on held-out windows.
     *  Head-only arithmetic, no trunk forward. Measures PRESERVATION, not correctness: a
     *  wrong-signed response passes as long as the adapter keeps the sign. */
    fun loraGuard(
        head: NativeHead,
        desc: ModelDescriptor,
        samples: List<LoraSample>,
        weights: LoraWeights,
        opts: LoraGuardOpts,
    ): LoraGuardReport

    /** The bar the fit's own guard pass measures against; a later probe must use the same one. */
    fun loraGuardOptsFit(): LoraGuardOpts

    /** `B = 0`: exactly the identity until trained. [headSha256] binds it to the head it may attach to. */
    fun loraNew(
        config: LoraConfig,
        headSha256: String,
        dModel: Int,
        hidden: Int,
        outDim: Int,
        seed: Long,
    ): LoraWeights

    /** Digest-protected. */
    fun loraSerialize(w: LoraWeights): ByteArray

    /** `null` on a truncated, corrupted or foreign blob. */
    fun loraDeserialize(bytes: ByteArray): LoraWeights?

    fun synthDefaultParams(): SynthParams

    /** Ends at the caller's "now"; [startHourOfDay] is the local clock hour at step 0. */
    fun synthSeries(
        nSteps: Int,
        startHourOfDay: Double,
        params: SynthParams,
        seed: Long,
    ): SynthSeries

    /** Fills the `NaN` steps only; the dose channels fill on the SAME steps as the BG. */
    fun synthFillGaps(
        realBg: List<Double>,
        realCarb: List<Double>,
        realInsulin: List<Double>,
        realExercise: List<Double>,
        synth: SynthSeries,
    ): SynthSeries

    /** The absent-sample runs in a gridded BG series (`NaN` marks absent), longest first. */
    fun findGaps(bg: List<Double>, minSteps: Int): List<GapRun>

    /** The safety guard every rail/alert gates on (§3.6-B). [desc] must be the one the forecast was
     *  decoded with: the rails are descriptor-defined. */
    fun forecastDegeneracyCheck(desc: ModelDescriptor, forecast: Forecast): ForecastStatus

    /** [timeLogits] is flat `(P, nBins)`. `null` on a bad shape or non-finite logit — fail-OPEN, the
     *  predicted hour never blocks the BG forecast. */
    fun decodeTime(timeLogits: List<Double>, nBins: Int, binHours: Double): PredictedTime?


    /** Amount per 5-min step, `sum == total`; == `simulator.gamma_curve`. */
    fun gamma(total: Double, k: Double, theta: Double, durMin: Double): List<Double>

    /** Amount per 5-min step, `sum == total`; == `simulator.basal_curve`, default 5 h tail-clip. */
    fun bateman(total: Double, durMin: Double, ka: Double, ke: Double): List<Double>

    /** Amount per 5-min step, `sum == total`; peaks at [peakMin], ~0 by [diaMin]. Off-distribution. */
    fun expActionCurve(total: Double, peakMin: Double, diaMin: Double): List<Double>

    /** The two in-distribution simulator defaults plus the OPT-IN, off-distribution presets. */
    fun insulinPresetCatalog(): List<com.t1dm.core.model.InsulinPresetSpec>

    /** Sum every [kind]-matching event's curve onto the fixed grid
     *  `[gridStartMs, gridStartMs + nSteps·STEP_MS)`; pre-grid tails carry forward. */
    fun bucketize(events: List<CurveEvent>, gridStartMs: Long, nSteps: Int, kind: CurveKind): List<Double>

    /** IOB/COB at [atMs] = the remaining tail area of every [kind]-matching event. */
    fun onBoard(events: List<CurveEvent>, atMs: Long, kind: CurveKind): Double

    /** The Bateman events of a daily-repeating [schedule] whose action overlaps `[fromMs, toMs)`. */
    fun extendBasal(schedule: BasalSchedule, fromMs: Long, toMs: Long): List<CurveEvent>


    /** [targetLow]/[targetHigh] are mg/dL; [agpBins] must divide 1440. Fail-closed: an empty series
     *  or a bad argument yields [AdvancedStats.EMPTY] rather than throwing. */
    fun advancedStats(
        samples: List<StatSample>,
        targetLow: Int,
        targetHigh: Int,
        agpBins: Int,
    ): AdvancedStats

    /** The fixed clinical level-2 cuts (mg/dL), read from the crate and never restated here.
     *  Fail-closed to [ClinicalCuts.UNAVAILABLE]; a caller that cannot anchor renders nothing. */
    fun clinicalCuts(): ClinicalCuts


    /** Band projection of `SPEC/invariants.md` §6.2 per horizon, plus CG-EGA (§6.3). A horizon under
     *  [MetricsConfig.minSamples] returns its true `n` with `sufficient = false`; [includeCgEga]
     *  `= false` yields [MetricsSuite.cgega] `= null`. A bad argument yields [MetricsSuite.EMPTY]. */
    fun forecastMetricsSuite(
        windows: List<ForecastWindow>,
        horizonsMin: List<Int>,
        config: MetricsConfig,
        includeCgEga: Boolean,
    ): MetricsSuite

    /** TRUTH-MAJOR: cell `(i, j)` is `truthAxisMgdl[i]` against `predAxisMgdl[j]`, at index
     *  `i * predAxisMgdl.size + j`. Fail-closed to an empty list on a non-finite coordinate or a
     *  lattice past the core's cell ceiling. */
    fun clarkeZoneGrid(truthAxisMgdl: List<Double>, predAxisMgdl: List<Double>): List<ClarkeZone>

    /** The DTS Error Grid (Klonoff et al. 2024): same layout and fail-closed contract as
     *  [clarkeZoneGrid]. The coefficients are `t1dm-core::accuracy::dts_risk`'s, never repeated here. */
    fun dtsZoneGrid(truthAxisMgdl: List<Double>, predAxisMgdl: List<Double>): List<DtsZone>

    /** Interior rate-bin edges, mg/dL per minute, ascending; always `TREND_BINS - 1`. Read from the
     *  crate, never restated here. Fail-closed to an empty list: unlabelled axes, not guessed edges. */
    fun trendBinEdges(): List<Double>


    /** The smallest calibration count at which no level's order statistic is clamped, derived from
     *  the level tuple of `SPEC/invariants.md` §6. A caller's own threshold is raised to this. */
    fun conformalMinCalWindows(): Int

    /** A per-`(step, τ)` additive band correction (§8.4). [windows] must be in the order they were
     *  made: the split is CHRONOLOGICAL. [minCalWindows] is raised to [conformalMinCalWindows]; under
     *  it, `sufficient = false` with an all-zero delta. A core error yields [ConformalFit.NONE]. */
    fun fitQuantileConformal(windows: List<ForecastWindow>, minCalWindows: Int): ConformalFit

    /** §8.4: add, restore the median EXACTLY, clamp outward. Both arrays are `steps · nQuantiles`,
     *  step-major, ascending τ. Fail-closed to the RAW fan (`null`); never partially corrected. */
    fun applyQuantileConformal(bandsMgdl: List<Double>, delta: List<Double>): List<Double>?

    /** [applyQuantileConformal] for many fans of ONE shape in a single crossing (§8.4). [fansMgdl] is
     *  `nFans · steps · nQuantiles`, fan-major, `fanLen == delta.size`; one [delta] corrects every
     *  fan. Fail-closed to the RAW fans (`null`) for the WHOLE batch, never partially corrected. */
    fun applyQuantileConformalBatch(fansMgdl: List<Double>, delta: List<Double>): List<Double>?


    /** Read from the core rather than restated here. */
    fun baselineDefaultSpec(): BaselineSpec

    /** [bgMgdl] is grid-aligned from [gridStartMs], newest last, a non-finite entry marking a gap —
     *  gaps DROP the affected design rows rather than being filled (`SPEC/invariants.md` §1). Split
     *  chronologically: older fits the weights, newer calibrates. `null` on anything the core rejects;
     *  a fit that ran on too little held-out history returns `sufficient = false` and a zero delta. */
    fun fitBaselineRidge(
        bgMgdl: List<Double>,
        gridStartMs: Long,
        events: List<CurveEvent>,
        spec: BaselineSpec,
        nowMs: Long,
        minCalWindows: Int,
    ): BaselineFit?

    /** [bgTail] is the trailing `nLags` mg/dL values OLDEST-FIRST; [iob]/[cob] are the causal
     *  on-board amounts at the anchor. [futureCarb]/[futureInsulin] are the COMMITTED per-5-min carb
     *  appearance and insulin action over the prediction zone, index 0 the step after the anchor, at
     *  least `horizonSteps` long — a short array yields `null` rather than being zero-padded. */
    fun baselinePredict(
        model: BaselineModel,
        bgTail: List<Double>,
        iob: Double,
        cob: Double,
        futureCarb: List<Double>,
        futureInsulin: List<Double>,
    ): BaselineForecast?

    /** The IOB/COB feature [baselinePredict] consumes: only events already STARTED at [atMs].
     *  Deliberately not [onBoard], which counts announced future doses too. */
    fun baselineOnBoardAt(events: List<CurveEvent>, atMs: Long, kind: CurveKind): Double

    /** §3.6-B for a forecast with no risk space: order judged on the mg/dL bands, rails on the
     *  clinical physical domain. An uncalibrated baseline lands on [ForecastStatus.CollapsedBand],
     *  which is the intended withholding and not an error. */
    fun baselineDegeneracyCheck(forecast: BaselineForecast): ForecastStatus


    /** Rust owns these numbers; nothing on this side transcribes them. */
    fun defaultCarTuning(): CarTuning

    /** The heightfield crosses the FFI exactly here. The caller OWNS the result and must
     *  [GameWorld.close] it. Throws on a degenerate terrain or tuning. */
    fun createGameWorld(terrain: TerrainSpec, tuning: CarTuning): GameWorld
}
