package com.t1dm.cgm

import com.t1dm.core.common.GameWorld
import com.t1dm.core.common.NativeCore
import com.t1dm.core.model.AccuracyPair
import com.t1dm.core.model.AccuracyReport
import com.t1dm.core.model.AdvancedStats
import com.t1dm.core.model.BasalSchedule
import com.t1dm.core.model.BuiltContext
import com.t1dm.core.model.CarTuning
import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.DecodedAdvert
import com.t1dm.core.model.Forecast
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.InsulinPresetSpec
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

    override fun accuracyAtHorizons(pairs: List<AccuracyPair>, minSamples: Int): AccuracyReport =
        AccuracyReport.EMPTY

    override fun defaultCarTuning(): CarTuning = TODO("not exercised by :cgm tests")

    override fun createGameWorld(terrain: TerrainSpec, tuning: CarTuning): GameWorld =
        TODO("not exercised by :cgm tests")
}
