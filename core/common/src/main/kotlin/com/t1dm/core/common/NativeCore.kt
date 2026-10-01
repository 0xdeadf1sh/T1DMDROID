package com.t1dm.core.common

import com.t1dm.core.model.AdvancedStats
import com.t1dm.core.model.AlarmFanEdges
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
import com.t1dm.core.model.GolfTuning
import com.t1dm.core.model.ClarkeZone
import com.t1dm.core.model.DtsZone
import com.t1dm.core.model.ConformalFit
import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.DecodedAdvert
import com.t1dm.core.model.DescriptorParse
import com.t1dm.core.model.ForecastWindow
import com.t1dm.core.model.MetricsConfig
import com.t1dm.core.model.MetricsSuite
import com.t1dm.core.model.Forecast
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.core.model.PredictedTime
import com.t1dm.core.model.StatSample
import com.t1dm.core.model.Obstacle
import com.t1dm.core.model.TerrainSpec

/** Kotlin surface of t1dm-core; depend on this, never uniffi binding; total on garbage input. */
interface NativeHead : AutoCloseable {
    /** Attach an adapter, or detach with `null`. The base weights are untouched. */
    fun setLora(w: LoraWeights?)

    fun hasLora(): Boolean

    /** head_raw for nSlots of stepStates, in assembleDecode's layout; no adapter = graph's own. */
    fun forward(stepStates: List<Double>, nSlots: Int): List<Double>
}

interface NativeCore {
    fun roundtrip(msg: String): String

    /** Decode+CRC-validate a 20-byte advert payload (CGM.md §3.1/§3.2); null if short/CRC fails. */
    fun decodeAdvert(payload: ByteArray): DecodedAdvert?

    /** The advert CRC32 (CGM.md §3.2), as an unsigned value in the low 32 bits of the Long. */
    fun advertCrc32(payload: ByteArray): Long

    /** Kovatchev BG → risk-space transform `f`. */
    fun kovatchevF(mgdl: Double): Double

    /** [kovatchevF] over a series; one JNI crossing where the backend has a batch export. */
    fun kovatchevFClinicalBatch(mgdl: DoubleArray): DoubleArray =
        DoubleArray(mgdl.size) { kovatchevF(mgdl[it]) }

    /** Inverse Kovatchev transform `f_inv`, risk-space → mg/dL. */
    fun kovatchevFInv(risk: Double): Double


    /** Parse a model `descriptor.json` (SPEC §2.4); `null` on malformed JSON / a missing field. */
    fun parseDescriptor(json: String): ModelDescriptor?

    /** [parseDescriptor] keeping the refusal text, so a refused model is named, never dropped. */
    fun parseDescriptorOrRefusal(json: String): DescriptorParse =
        DescriptorParse(parseDescriptor(json), null)

    /** Causal one-sided SavGol smooth (§7.1); odd window, 1=pass-through; bad window degrades. */
    fun causalSmooth(series: List<Double>, clampMin: Double?, clampMax: Double?, window: Int): List<Double>

    /** z-score a raw `[bg, carb, insulin]` sample (bg risk-z, the rest log1p-z). */
    fun normalizeSample(desc: ModelDescriptor, bg: Double, carb: Double, insulin: Double): List<Double>

    /** Inverse of [normalizeSample]; the 3-element `z` must carry all channels. */
    fun denormalizeSample(desc: ModelDescriptor, z: List<Double>): List<Double>

    /** Fixed-shape graph input from per-step history (§7.2-7.4); throws on bad shape/window. */
    fun buildGraphInput(
        desc: ModelDescriptor,
        bg: List<Double>,
        carb: List<Double>,
        insulin: List<Double>,
        announcedCarb: List<Double>?,
        announcedInsulin: List<Double>?,
        maskSpans: List<MaskSpan>,
        withForecast: Boolean,
        smoothingWindow: Int,
    ): GraphInput

    /** head_raw (M·S·7, risk) -> quantile fan, mg/dL (§8); raw, §8.4 applied downstream. */
    fun assembleDecode(
        desc: ModelDescriptor,
        headRaw: List<Double>,
        anchors: List<Double>,
        slotPatch: List<Int>,
        nMasked: Int,
        carrySpread: List<Double>,
    ): Forecast

    /** Head per-step input, splined from hidden over span nodes (§8.2); attnMask excludes pads. */
    fun stepStates(
        desc: ModelDescriptor,
        hidden: List<Float>,
        slotPatch: List<Int>,
        attnMask: List<Float>,
    ): List<Double>

    /** The rows of [f] whose slot sits in `[fromPatch, toPatch)`, as a Forecast of its own. */
    fun forecastSlice(f: Forecast, fromPatch: Int, toPatch: Int): Forecast

    /** Fan's line at arbitrary tau, mg/dL: risk-space interp, clamped outside published levels. */
    fun bandLine(desc: ModelDescriptor, f: Forecast, tau: Double): List<Double>

    /** bandLine for a standalone fan; qTauRisk is steps×7 risk values, ascending τ per step. */
    fun bandLineAt(desc: ModelDescriptor, qTauRisk: List<Double>, tau: Double): List<Double>


    /** Digest checked against the descriptor's head block; null if head and graph disagree. */
    fun headOpen(bytes: ByteArray, spec: HeadSpec): NativeHead?

    /** Attaches nothing; caller decides. progress called once per epoch, on the calling thread. */
    fun loraTrain(
        head: NativeHead,
        desc: ModelDescriptor,
        samples: List<LoraSample>,
        config: LoraConfig,
        opts: LoraTrainOpts,
        progress: LoraProgressSink? = null,
    ): LoraTrainResult

    /** Preservation, not correctness of an adapter's marginal insulin response, held-out. */
    fun loraGuard(
        head: NativeHead,
        desc: ModelDescriptor,
        samples: List<LoraSample>,
        weights: LoraWeights,
        opts: LoraGuardOpts,
    ): LoraGuardReport

    /** The bar the fit's own guard pass measures against; a later probe must use the same one. */
    fun loraGuardOptsFit(): LoraGuardOpts

    /** B=0: identity until trained. headSha256 binds it to the head it may attach to. */
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

    /** Safety guard every rail/alert gates on (§3.6-B); desc must match the forecast's decode. */
    fun forecastDegeneracyCheck(desc: ModelDescriptor, forecast: Forecast): ForecastStatus

    /** timeLogits flat (P,nBins); null on bad shape/logit; fails open, never blocks forecast. */
    fun decodeTime(timeLogits: List<Double>, nBins: Int, binHours: Double): PredictedTime?


    /** Amount per 5-min step, `sum == total`; == `simulator.gamma_curve`. */
    fun gamma(total: Double, k: Double, theta: Double, durMin: Double): List<Double>

    /** Amount per 5-min step, `sum == total`; == `simulator.basal_curve`, last sixth tapered. */
    fun bateman(total: Double, durMin: Double, ka: Double, ke: Double): List<Double>

    /** == `simulator.bolus_pk_for_dose`; [theta] minutes and [diaBaseHours] at 5 U. */
    fun bolusPkForDose(doseU: Double, k: Double, theta: Double, diaBaseHours: Double): com.t1dm.core.model.BolusPk

    /** SPEC/invariants.md §5 insulin table. */
    fun insulinPresetCatalog(): List<com.t1dm.core.model.InsulinPresetSpec>

    /** Sums every kind-matching event's curve onto the fixed grid; pre-grid tails carry forward. */
    fun bucketize(events: List<CurveEvent>, gridStartMs: Long, nSteps: Int, kind: CurveKind): List<Double>

    /** IOB/COB at [atMs] = the remaining tail area of every [kind]-matching event. */
    fun onBoard(events: List<CurveEvent>, atMs: Long, kind: CurveKind): Double

    /** The Bateman events of a daily-repeating schedule whose action overlaps [fromMs, toMs). */
    fun extendBasal(schedule: BasalSchedule, fromMs: Long, toMs: Long): List<CurveEvent>


    /** targetLow/High are mg/dL; agpBins must divide 1440. Fail-closed to AdvancedStats.EMPTY. */
    fun advancedStats(
        samples: List<StatSample>,
        targetLow: Int,
        targetHigh: Int,
        agpBins: Int,
    ): AdvancedStats

    /** Fixed clinical level-2 cuts, read from crate, never restated here; fails to UNAVAILABLE. */
    fun clinicalCuts(): ClinicalCuts


    /** Band projection §6.2 per horizon plus CG-EGA (§6.3); under minSamples, sufficient=false. */
    fun forecastMetricsSuite(
        windows: List<ForecastWindow>,
        horizonsMin: List<Int>,
        config: MetricsConfig,
        includeCgEga: Boolean,
    ): MetricsSuite

    /** cell (i,j) is truthAxis[i] vs predAxis[j] at i*predAxis.size+j; fails closed to empty. */
    fun clarkeZoneGrid(truthAxisMgdl: List<Double>, predAxisMgdl: List<Double>): List<ClarkeZone>

    /** DTS Error Grid; same layout/fail-closed contract as clarkeZoneGrid; coeffs live in Rust. */
    fun dtsZoneGrid(truthAxisMgdl: List<Double>, predAxisMgdl: List<Double>): List<DtsZone>

    /** Interior rate-bin edges, ascending, always TREND_BINS-1; read from crate, fails to empty. */
    fun trendBinEdges(): List<Double>

    /** Read from crate; null fails every predictive alarm closed. */
    fun alarmFanEdges(): AlarmFanEdges?

    /** Smallest calibration count with no clamped order stat (§6); floors caller's threshold. */
    fun conformalMinCalWindows(): Int

    /** Per-(step,τ) correction (§8.4); windows chronological; floors to conformalMinCalWindows. */
    fun fitQuantileConformal(windows: List<ForecastWindow>, minCalWindows: Int): ConformalFit

    /** §8.4: add, restore median exactly, clamp outward; fails closed to raw fan, never partial. */
    fun applyQuantileConformal(bandsMgdl: List<Double>, delta: List<Double>): List<Double>?

    /** applyQuantileConformal for same-shape fans in one pass (§8.4); fails closed for batch. */
    fun applyQuantileConformalBatch(fansMgdl: List<Double>, delta: List<Double>): List<Double>?


    /** Rust owns these numbers; nothing on this side transcribes them. */
    fun defaultCarTuning(): CarTuning

    /** Heightfield crosses the FFI here; caller owns the result, must GameWorld.close it. */
    fun createGameWorld(terrain: TerrainSpec, tuning: CarTuning, obstacles: List<Obstacle>): GameWorld

    /** Rust owns these numbers; nothing on this side transcribes them. */
    fun defaultGolfTuning(): GolfTuning

    /** Same heightfield, cup cut at the last sample; caller must GolfWorld.close it. */
    fun createGolfWorld(terrain: TerrainSpec, tuning: GolfTuning, obstacles: List<Obstacle>): GolfWorld
}
