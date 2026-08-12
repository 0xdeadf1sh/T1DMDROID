package com.t1dm.cgm

import com.t1dm.core.common.GameWorld
import com.t1dm.core.common.NativeCore
import com.t1dm.core.model.AdvancedStats
import com.t1dm.core.model.BasalSchedule
import com.t1dm.core.model.BaselineFit
import com.t1dm.core.model.BaselineForecast
import com.t1dm.core.model.BaselineModel
import com.t1dm.core.model.BaselineSpec
import com.t1dm.core.model.BuiltContext
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
import com.t1dm.core.model.InsulinPresetSpec
import com.t1dm.core.model.MetricsConfig
import com.t1dm.core.model.MetricsSuite
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.core.model.PredictedTime
import com.t1dm.core.model.StatSample
import com.t1dm.core.model.TerrainSpec

/**
 * Test-only [NativeCore] backed by [AidexCodec] — a stand-in for the Rust `t1dm-core` whose advert
 * decode is wired in a later phase. Lets the :cgm pipeline be driven bit-faithfully against the
 * CGM.md golden vectors without the native library.
 *
 * Only the Phase-1 advert-decode surface ([decodeAdvert]/[advertCrc32]/[kovatchevF]/[kovatchevFInv])
 * is exercised by the :cgm tests; the model pre/post, curve, stats and accuracy members exist purely
 * to satisfy the (since-grown) [NativeCore] contract and are never called from here — they fail
 * closed (nullable → `null`, aggregate → `EMPTY`) or throw a clear `TODO` if a future test wires them.
 */
class ReferenceNativeCore : NativeCore {
    override fun roundtrip(msg: String): String = msg
    override fun decodeAdvert(payload: ByteArray): DecodedAdvert? = AidexCodec.decode(payload)
    override fun advertCrc32(payload: ByteArray): Long = AidexCodec.crcOf(payload)
    override fun kovatchevF(mgdl: Double): Double = 0.0
    override fun kovatchevFInv(risk: Double): Double = 0.0

    // ── Not exercised by :cgm tests (contract-only) ─────────────────────────────────────
    override fun parseDescriptor(json: String): ModelDescriptor? = null

    override fun causalSmooth(
        series: List<Double>,
        clampMin: Double?,
        clampMax: Double?,
        window: Int,
    ): List<Double> = TODO("not exercised by :cgm tests")

    override fun normalizeSample(desc: ModelDescriptor, bg: Double, carb: Double, insulin: Double): List<Double> =
        TODO("not exercised by :cgm tests")

    override fun denormalizeSample(desc: ModelDescriptor, z: List<Double>): List<Double> =
        TODO("not exercised by :cgm tests")

    override fun buildContext(
        desc: ModelDescriptor,
        bg: List<Double>,
        carb: List<Double>,
        insulin: List<Double>,
        announcedCarb: List<Double>?,
        announcedInsulin: List<Double>?,
        smoothingWindow: Int,
    ): BuiltContext = TODO("not exercised by :cgm tests")

    override fun assembleDecode(
        desc: ModelDescriptor,
        headRaw: List<Double>,
        lastBg: Double,
        carrySpread: Double,
    ): Forecast = TODO("not exercised by :cgm tests")

    override fun forecastDegeneracyCheck(desc: ModelDescriptor, forecast: Forecast): ForecastStatus =
        TODO("not exercised by :cgm tests")

    override fun decodeTime(timeLogits: List<Double>, nBins: Int, binHours: Double): PredictedTime? = null

    override fun gamma(total: Double, k: Double, theta: Double, durMin: Double): List<Double> =
        TODO("not exercised by :cgm tests")

    override fun bateman(total: Double, durMin: Double, ka: Double, ke: Double): List<Double> =
        TODO("not exercised by :cgm tests")

    override fun expActionCurve(total: Double, peakMin: Double, diaMin: Double): List<Double> =
        TODO("not exercised by :cgm tests")

    override fun insulinPresetCatalog(): List<InsulinPresetSpec> = TODO("not exercised by :cgm tests")

    override fun bucketize(
        events: List<CurveEvent>,
        gridStartMs: Long,
        nSteps: Int,
        kind: CurveKind,
    ): List<Double> = TODO("not exercised by :cgm tests")

    override fun onBoard(events: List<CurveEvent>, atMs: Long, kind: CurveKind): Double =
        TODO("not exercised by :cgm tests")

    override fun extendBasal(schedule: BasalSchedule, fromMs: Long, toMs: Long): List<CurveEvent> =
        TODO("not exercised by :cgm tests")

    override fun advancedStats(
        samples: List<StatSample>,
        targetLow: Int,
        targetHigh: Int,
        agpBins: Int,
    ): AdvancedStats = AdvancedStats.EMPTY

    override fun forecastMetricsSuite(
        windows: List<ForecastWindow>,
        horizonsMin: List<Int>,
        config: MetricsConfig,
        includeCgEga: Boolean,
    ): MetricsSuite = MetricsSuite.EMPTY

    // Empty is the contract's own fail-closed answer, not a shortfall of this stand-in: the lattice
    // exists so the zone inequalities stay in the core alone, and classifying one here would put a
    // second copy of them in a test double. `ClarkeZoneGrid.build` reads empty as no lattice.
    override fun clarkeZoneGrid(
        truthAxisMgdl: List<Double>,
        predAxisMgdl: List<Double>,
    ): List<ClarkeZone> = emptyList()

    // Refused for the same reason the lattice above is: §8.4's side-aware order statistic and its
    // median-fixed monotone apply live in the crate and nowhere else, and re-expressing either in a
    // test double would be the second copy of a formula whose two versions could then disagree about
    // the hypo edge. No correction fitted, and the raw fan returned — which is what every caller
    // already falls back to.
    override fun conformalMinCalWindows(): Int = 0

    override fun fitQuantileConformal(
        windows: List<ForecastWindow>,
        minCalWindows: Int,
    ): ConformalFit = ConformalFit.NONE

    override fun applyQuantileConformal(
        bandsMgdl: List<Double>,
        delta: List<Double>,
    ): List<Double>? = null

    override fun applyQuantileConformalBatch(
        fansMgdl: List<Double>,
        delta: List<Double>,
    ): List<Double>? = null

    // Three members :cgm's own tests never reach — the metric suite's clinical cuts, the DTS zone
    // grid and the trend bins all belong to surfaces above this module. They are declared on the port,
    // so the double has to answer them; refusing is the honest answer, and matches StubNativeCore.
    override fun clinicalCuts(): ClinicalCuts = ClinicalCuts.UNAVAILABLE

    override fun dtsZoneGrid(
        truthAxisMgdl: List<Double>,
        predAxisMgdl: List<Double>,
    ): List<DtsZone> = emptyList()

    override fun trendBinEdges(): List<Double> = emptyList()

    // The classical baseline, likewise out of this module's reach: the ridge solve, its causal
    // on-board scatter and its degeneracy verdict all live in the crate, and a Kotlin reproduction
    // here would be a second numeric authority for the model the neural one is MEASURED against.
    // `TODO()` rather than a plausible zero, mirroring StubNativeCore — a :cgm test that reached one
    // of these is a test asking the wrong object, and should fail loudly saying so.
    override fun baselineDefaultSpec(): BaselineSpec = TODO("the baseline is Rust-only")

    override fun fitBaselineRidge(
        bgMgdl: List<Double>,
        gridStartMs: Long,
        events: List<CurveEvent>,
        spec: BaselineSpec,
        nowMs: Long,
        minCalWindows: Int,
    ): BaselineFit? = TODO("the baseline is Rust-only")

    override fun baselinePredict(
        model: BaselineModel,
        bgTail: List<Double>,
        iob: Double,
        cob: Double,
        futureCarb: List<Double>,
        futureInsulin: List<Double>,
    ): BaselineForecast? = TODO("the baseline is Rust-only")

    override fun baselineOnBoardAt(events: List<CurveEvent>, atMs: Long, kind: CurveKind): Double =
        TODO("the baseline is Rust-only")

    override fun baselineDegeneracyCheck(forecast: BaselineForecast): ForecastStatus =
        TODO("the baseline is Rust-only")

    override fun defaultCarTuning(): CarTuning = TODO("not exercised by :cgm tests")

    override fun createGameWorld(terrain: TerrainSpec, tuning: CarTuning): GameWorld =
        TODO("not exercised by :cgm tests")
}
