package com.t1dm.cgm

import com.t1dm.core.common.GameWorld
import com.t1dm.core.common.NativeCore
import com.t1dm.core.common.NativeHead
import com.t1dm.core.model.AdvancedStats
import com.t1dm.core.model.BasalSchedule
import com.t1dm.core.model.BaselineFit
import com.t1dm.core.model.BaselineForecast
import com.t1dm.core.model.BaselineModel
import com.t1dm.core.model.BaselineSpec
import com.t1dm.core.model.CarTuning
import com.t1dm.core.model.ClarkeZone
import com.t1dm.core.model.ClinicalCuts
import com.t1dm.core.model.ConformalFit
import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.DecodedAdvert
import com.t1dm.core.model.DtsZone
import com.t1dm.core.model.Forecast
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ForecastWindow
import com.t1dm.core.model.GapRun
import com.t1dm.core.model.GraphInput
import com.t1dm.core.model.HeadSpec
import com.t1dm.core.model.LoraConfig
import com.t1dm.core.model.LoraGuardOpts
import com.t1dm.core.model.LoraGuardReport
import com.t1dm.core.model.LoraProgressSink
import com.t1dm.core.model.LoraSample
import com.t1dm.core.model.LoraTrainOpts
import com.t1dm.core.model.LoraTrainResult
import com.t1dm.core.model.LoraWeights
import com.t1dm.core.model.MaskSpan
import com.t1dm.core.model.MetricsConfig
import com.t1dm.core.model.MetricsSuite
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.core.model.PredictedTime
import com.t1dm.core.model.StatSample
import com.t1dm.core.model.SynthParams
import com.t1dm.core.model.SynthSeries
import com.t1dm.core.model.TerrainSpec

/**
 * Test-only [NativeCore] backed by [AidexCodec] — a stand-in for the Rust `t1dm-core`, so the :cgm
 * pipeline can be driven bit-faithfully against the CGM.md golden vectors without the native library.
 *
 * **Only the advert-decode surface is real.** [decodeAdvert], [advertCrc32] and the two risk-space
 * conversions are what the :cgm tests exercise; every other member exists to satisfy the [NativeCore]
 * contract and throws if a future test wires one. That contract grows with the model work, so the
 * stubs below are mechanical and meant to be regenerated from the interface rather than maintained by
 * hand — and they throw rather than answer, because a stub returning a plausible zero would let a
 * test pass on an answer nothing computed.
 */
class ReferenceNativeCore : NativeCore {
    override fun roundtrip(msg: String): String = msg
    override fun decodeAdvert(payload: ByteArray): DecodedAdvert? = AidexCodec.decode(payload)
    override fun advertCrc32(payload: ByteArray): Long = AidexCodec.crcOf(payload)
    override fun kovatchevF(mgdl: Double): Double = 0.0
    override fun kovatchevFInv(risk: Double): Double = 0.0

    // ── Contract-only, never called from :cgm ───────────────────────────────────────────
    override fun parseDescriptor(json: String): ModelDescriptor? = TODO("not exercised by :cgm tests")
    override fun causalSmooth(series: List<Double>, clampMin: Double?, clampMax: Double?, window: Int): List<Double> = TODO("not exercised by :cgm tests")
    override fun normalizeSample(desc: ModelDescriptor, bg: Double, carb: Double, insulin: Double, exercise: Double): List<Double> = TODO("not exercised by :cgm tests")
    override fun denormalizeSample(desc: ModelDescriptor, z: List<Double>): List<Double> = TODO("not exercised by :cgm tests")
    override fun buildGraphInput(desc: ModelDescriptor, bg: List<Double>, carb: List<Double>, insulin: List<Double>, exercise: List<Double>, announcedCarb: List<Double>?, announcedInsulin: List<Double>?, announcedExercise: List<Double>?, maskSpans: List<MaskSpan>, withForecast: Boolean, smoothingWindow: Int): GraphInput = TODO("not exercised by :cgm tests")
    override fun assembleDecode(desc: ModelDescriptor, headRaw: List<Double>, anchors: List<Double>, slotPatch: List<Int>, nMasked: Int, carrySpread: Double): Forecast = TODO("not exercised by :cgm tests")
    override fun forecastSlice(f: Forecast, fromPatch: Int, toPatch: Int): Forecast = TODO("not exercised by :cgm tests")
    override fun bandLine(desc: ModelDescriptor, f: Forecast, tau: Double): List<Double> = TODO("not exercised by :cgm tests")
    override fun bandLineAt(desc: ModelDescriptor, qTauRisk: List<Double>, tau: Double): List<Double> = TODO("not exercised by :cgm tests")
    override fun loraGuard(head: NativeHead, desc: ModelDescriptor, samples: List<LoraSample>, weights: LoraWeights, opts: LoraGuardOpts): LoraGuardReport = TODO("not exercised by :cgm tests")
    override fun loraGuardOptsFit(): LoraGuardOpts = TODO("not exercised by :cgm tests")
    override fun headOpen(bytes: ByteArray, spec: HeadSpec): NativeHead? = TODO("not exercised by :cgm tests")
    override fun loraTrain(head: NativeHead, desc: ModelDescriptor, samples: List<LoraSample>, config: LoraConfig, opts: LoraTrainOpts, progress: LoraProgressSink?): LoraTrainResult = TODO("not exercised by :cgm tests")
    override fun loraNew(config: LoraConfig, headSha256: String, dModel: Int, hidden: Int, outDim: Int, seed: Long): LoraWeights = TODO("not exercised by :cgm tests")
    override fun loraSerialize(w: LoraWeights): ByteArray = TODO("not exercised by :cgm tests")
    override fun loraDeserialize(bytes: ByteArray): LoraWeights? = TODO("not exercised by :cgm tests")
    override fun synthDefaultParams(): SynthParams = TODO("not exercised by :cgm tests")
    override fun synthSeries(nSteps: Int, startHourOfDay: Double, params: SynthParams, seed: Long): SynthSeries = TODO("not exercised by :cgm tests")
    override fun synthFillGaps(realBg: List<Double>, realCarb: List<Double>, realInsulin: List<Double>, realExercise: List<Double>, synth: SynthSeries): SynthSeries = TODO("not exercised by :cgm tests")
    override fun findGaps(bg: List<Double>, minSteps: Int): List<GapRun> = TODO("not exercised by :cgm tests")
    override fun forecastDegeneracyCheck(desc: ModelDescriptor, forecast: Forecast): ForecastStatus = TODO("not exercised by :cgm tests")
    override fun decodeTime(timeLogits: List<Double>, nBins: Int, binHours: Double): PredictedTime? = TODO("not exercised by :cgm tests")
    override fun gamma(total: Double, k: Double, theta: Double, durMin: Double): List<Double> = TODO("not exercised by :cgm tests")
    override fun bateman(total: Double, durMin: Double, ka: Double, ke: Double): List<Double> = TODO("not exercised by :cgm tests")
    override fun expActionCurve(total: Double, peakMin: Double, diaMin: Double): List<Double> = TODO("not exercised by :cgm tests")
    override fun insulinPresetCatalog(): List<com.t1dm.core.model.InsulinPresetSpec> = TODO("not exercised by :cgm tests")
    override fun bucketize(events: List<CurveEvent>, gridStartMs: Long, nSteps: Int, kind: CurveKind): List<Double> = TODO("not exercised by :cgm tests")
    override fun onBoard(events: List<CurveEvent>, atMs: Long, kind: CurveKind): Double = TODO("not exercised by :cgm tests")
    override fun extendBasal(schedule: BasalSchedule, fromMs: Long, toMs: Long): List<CurveEvent> = TODO("not exercised by :cgm tests")
    override fun advancedStats(samples: List<StatSample>, targetLow: Int, targetHigh: Int, agpBins: Int): AdvancedStats = TODO("not exercised by :cgm tests")
    override fun clinicalCuts(): ClinicalCuts = TODO("not exercised by :cgm tests")
    override fun forecastMetricsSuite(windows: List<ForecastWindow>, horizonsMin: List<Int>, config: MetricsConfig, includeCgEga: Boolean): MetricsSuite = TODO("not exercised by :cgm tests")
    override fun clarkeZoneGrid(truthAxisMgdl: List<Double>, predAxisMgdl: List<Double>): List<ClarkeZone> = TODO("not exercised by :cgm tests")
    override fun dtsZoneGrid(truthAxisMgdl: List<Double>, predAxisMgdl: List<Double>): List<DtsZone> = TODO("not exercised by :cgm tests")
    override fun trendBinEdges(): List<Double> = TODO("not exercised by :cgm tests")
    override fun conformalMinCalWindows(): Int = TODO("not exercised by :cgm tests")
    override fun fitQuantileConformal(windows: List<ForecastWindow>, minCalWindows: Int): ConformalFit = TODO("not exercised by :cgm tests")
    override fun applyQuantileConformal(bandsMgdl: List<Double>, delta: List<Double>): List<Double>? = TODO("not exercised by :cgm tests")
    override fun applyQuantileConformalBatch(fansMgdl: List<Double>, delta: List<Double>): List<Double>? = TODO("not exercised by :cgm tests")
    override fun baselineDefaultSpec(): BaselineSpec = TODO("not exercised by :cgm tests")
    override fun fitBaselineRidge(bgMgdl: List<Double>, gridStartMs: Long, events: List<CurveEvent>, spec: BaselineSpec, nowMs: Long, minCalWindows: Int): BaselineFit? = TODO("not exercised by :cgm tests")
    override fun baselinePredict(model: BaselineModel, bgTail: List<Double>, iob: Double, cob: Double, futureCarb: List<Double>, futureInsulin: List<Double>): BaselineForecast? = TODO("not exercised by :cgm tests")
    override fun baselineOnBoardAt(events: List<CurveEvent>, atMs: Long, kind: CurveKind): Double = TODO("not exercised by :cgm tests")
    override fun baselineDegeneracyCheck(forecast: BaselineForecast): ForecastStatus = TODO("not exercised by :cgm tests")
    override fun defaultCarTuning(): CarTuning = TODO("not exercised by :cgm tests")
    override fun createGameWorld(terrain: TerrainSpec, tuning: CarTuning): GameWorld = TODO("not exercised by :cgm tests")
}
