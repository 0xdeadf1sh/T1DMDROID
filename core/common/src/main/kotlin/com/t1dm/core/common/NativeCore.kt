package com.t1dm.core.common

import com.t1dm.core.model.AdvancedStats
import com.t1dm.core.model.ClinicalCuts
import com.t1dm.core.model.BasalSchedule
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

/** Kotlin surface of Rust t1dm-core; depend on THIS, not the uniffi binding. Total on garbage. */
interface NativeHead : AutoCloseable {
    /** Attach an adapter, or detach with `null`. The base weights are untouched. */
    fun setLora(w: LoraWeights?)

    fun hasLora(): Boolean

    /** head_raw for nSlots of stepStates; no adapter attached reproduces the graph's own. */
    fun forward(stepStates: List<Double>, nSlots: Int): List<Double>
}

interface NativeCore {
    fun roundtrip(msg: String): String

    /** Decode+CRC-validate a 20-byte LinX advert (CGM.md §3.1/§3.2); null on short/CRC-fail. */
    fun decodeAdvert(payload: ByteArray): DecodedAdvert?

    /** The advert CRC32 (CGM.md §3.2), as an unsigned value in the low 32 bits of the Long. */
    fun advertCrc32(payload: ByteArray): Long

    /** Kovatchev BG → risk-space transform `f`. */
    fun kovatchevF(mgdl: Double): Double

    /** Inverse Kovatchev transform `f_inv`, risk-space → mg/dL. */
    fun kovatchevFInv(risk: Double): Double


    /** Parse a model `descriptor.json` (SPEC §2.4); `null` on malformed JSON / a missing field. */
    fun parseDescriptor(json: String): ModelDescriptor?

    /** Causal SavGol smooth (INFERENCE.md §7.1), ODD window (1=pass-through); bad -> default. */
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

    /** Fixed-shape graph input from per-step history (INFERENCE §§7.2-7.4); throws on bad shape. */
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

    /** head_raw (M*S*7 risk) -> quantile fan, mg/dL (§8); RAW, §8.4 applied downstream. */
    fun assembleDecode(
        desc: ModelDescriptor,
        headRaw: List<Double>,
        anchors: List<Double>,
        slotPatch: List<Int>,
        nMasked: Int,
        carrySpread: List<Double>,
    ): Forecast

    /** Head's per-step input, splined from hidden over each span's nodes (INFERENCE.md §8.2). */
    fun stepStates(
        desc: ModelDescriptor,
        hidden: List<Float>,
        slotPatch: List<Int>,
        attnMask: List<Float>,
    ): List<Double>

    /** The rows of [f] whose slot sits in `[fromPatch, toPatch)`, as a Forecast of its own. */
    fun forecastSlice(f: Forecast, fromPatch: Int, toPatch: Int): Forecast

    /** Fan's line at arbitrary tau, mg/dL, in RISK space; never consumed to classify. */
    fun bandLine(desc: ModelDescriptor, f: Forecast, tau: Double): List<Double>

    /** bandLine for a standalone fan; qTauRisk is steps x 7 risk-space values, ascending τ. */
    fun bandLineAt(desc: ModelDescriptor, qTauRisk: List<Double>, tau: Double): List<Double>


    /** Digest checked against descriptor's head block; null means not from the same export. */
    fun headOpen(bytes: ByteArray, spec: HeadSpec): NativeHead?

    /** Attaches nothing, caller decides; progress called once per epoch, on the calling thread. */
    fun loraTrain(
        head: NativeHead,
        desc: ModelDescriptor,
        samples: List<LoraSample>,
        config: LoraConfig,
        opts: LoraTrainOpts,
        progress: LoraProgressSink? = null,
    ): LoraTrainResult

    /** What an adapter did to insulin's marginal response, held-out; measures sign only. */
    fun loraGuard(
        head: NativeHead,
        desc: ModelDescriptor,
        samples: List<LoraSample>,
        weights: LoraWeights,
        opts: LoraGuardOpts,
    ): LoraGuardReport

    /** The bar the fit's own guard pass measures against; a later probe must use the same one. */
    fun loraGuardOptsFit(): LoraGuardOpts

    /** B=0: identity until trained; headSha256 binds it to the head it may attach to. */
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

    /** Safety guard every rail/alert gates on (§3.6-B); desc must match the decoded forecast. */
    fun forecastDegeneracyCheck(desc: ModelDescriptor, forecast: Forecast): ForecastStatus

    /** timeLogits flat (P, nBins); null on bad shape, fail-OPEN, never blocks the BG forecast. */
    fun decodeTime(timeLogits: List<Double>, nBins: Int, binHours: Double): PredictedTime?


    /** Amount per 5-min step, `sum == total`; == `simulator.gamma_curve`. */
    fun gamma(total: Double, k: Double, theta: Double, durMin: Double): List<Double>

    /** Amount per 5-min step, `sum == total`; == `simulator.basal_curve`, default 5 h tail-clip. */
    fun bateman(total: Double, durMin: Double, ka: Double, ke: Double): List<Double>

    /** Amount per 5-min step, sum==total; peaks at peakMin, ~0 by diaMin. Off-distribution. */
    fun expActionCurve(total: Double, peakMin: Double, diaMin: Double): List<Double>

    /** The two in-distribution simulator defaults plus the OPT-IN, off-distribution presets. */
    fun insulinPresetCatalog(): List<com.t1dm.core.model.InsulinPresetSpec>

    /** Sums matching events' curve onto the fixed grid; pre-grid tails carry forward. */
    fun bucketize(events: List<CurveEvent>, gridStartMs: Long, nSteps: Int, kind: CurveKind): List<Double>

    /** IOB/COB at [atMs] = the remaining tail area of every [kind]-matching event. */
    fun onBoard(events: List<CurveEvent>, atMs: Long, kind: CurveKind): Double

    /** Bateman events of a daily-repeating schedule whose action overlaps [fromMs, toMs). */
    fun extendBasal(schedule: BasalSchedule, fromMs: Long, toMs: Long): List<CurveEvent>


    /** targetLow/targetHigh mg/dL, agpBins divides 1440; bad input yields AdvancedStats.EMPTY. */
    fun advancedStats(
        samples: List<StatSample>,
        targetLow: Int,
        targetHigh: Int,
        agpBins: Int,
    ): AdvancedStats

    /** Fixed clinical level-2 cuts (mg/dL), read from the crate; fails closed to UNAVAILABLE. */
    fun clinicalCuts(): ClinicalCuts


    /** Band projection (SPEC/invariants.md §6.2) plus CG-EGA (§6.3); bad argument yields EMPTY. */
    fun forecastMetricsSuite(
        windows: List<ForecastWindow>,
        horizonsMin: List<Int>,
        config: MetricsConfig,
        includeCgEga: Boolean,
    ): MetricsSuite

    /** TRUTH-MAJOR: cell (i,j) at index i*predAxisMgdl.size+j; fails closed to empty list. */
    fun clarkeZoneGrid(truthAxisMgdl: List<Double>, predAxisMgdl: List<Double>): List<ClarkeZone>

    /** DTS Error Grid (Klonoff 2024): same layout/contract as clarkeZoneGrid, not repeated. */
    fun dtsZoneGrid(truthAxisMgdl: List<Double>, predAxisMgdl: List<Double>): List<DtsZone>

    /** Rate-bin edges, mg/dL/min, ascending, TREND_BINS-1 long; fails closed to empty list. */
    fun trendBinEdges(): List<Double>


    /** Smallest calibration count where no order statistic clamps (SPEC/invariants.md §6). */
    fun conformalMinCalWindows(): Int

    /** Per-(step,τ) band correction (§8.4); windows CHRONOLOGICAL, core error yields NONE. */
    fun fitQuantileConformal(windows: List<ForecastWindow>, minCalWindows: Int): ConformalFit

    /** §8.4: add, restore median exactly, clamp outward; fails closed to RAW fan, never partial. */
    fun applyQuantileConformal(bandsMgdl: List<Double>, delta: List<Double>): List<Double>?

    /** applyQuantileConformal for many same-shape fans (§8.4); fails closed for the WHOLE batch. */
    fun applyQuantileConformalBatch(fansMgdl: List<Double>, delta: List<Double>): List<Double>?


    /** Rust owns these numbers; nothing on this side transcribes them. */
    fun defaultCarTuning(): CarTuning

    /** Heightfield crosses FFI here; caller OWNS the result and must GameWorld.close it. */
    fun createGameWorld(terrain: TerrainSpec, tuning: CarTuning): GameWorld
}
