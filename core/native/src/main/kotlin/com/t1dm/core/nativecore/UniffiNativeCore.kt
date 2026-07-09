package com.t1dm.core.nativecore

import com.t1dm.core.common.NativeCore
import com.t1dm.core.model.AdvancedStats
import com.t1dm.core.model.AgpBin
import com.t1dm.core.model.BasalDoseSpec
import com.t1dm.core.model.BasalSchedule
import com.t1dm.core.model.BuiltContext
import com.t1dm.core.model.MoodSummary
import com.t1dm.core.model.StatSample
import com.t1dm.core.model.SubBands
import com.t1dm.core.model.ChannelStat
import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.DecodedAdvert
import com.t1dm.core.model.Forecast
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.core.model.PrevGlucose
import uniffi.t1dm_core.CoreException
import uniffi.t1dm_core.advancedStats as uniffiAdvancedStats
import uniffi.t1dm_core.advertCrc32 as uniffiAdvertCrc32
import uniffi.t1dm_core.assembleDecode as uniffiAssembleDecode
import uniffi.t1dm_core.bateman as uniffiBateman
import uniffi.t1dm_core.bolusPkForDose as uniffiBolusPkForDose
import uniffi.t1dm_core.bucketize as uniffiBucketize
import uniffi.t1dm_core.buildContext as uniffiBuildContext
import uniffi.t1dm_core.causalSmooth as uniffiCausalSmooth
import uniffi.t1dm_core.decodeAdvert as uniffiDecodeAdvert
import uniffi.t1dm_core.denormalizeSample as uniffiDenormalizeSample
import uniffi.t1dm_core.extendBasal as uniffiExtendBasal
import uniffi.t1dm_core.forecastDegeneracyCheck as uniffiForecastDegeneracyCheck
import uniffi.t1dm_core.gamma as uniffiGamma
import uniffi.t1dm_core.kovatchevF as uniffiKovatchevF
import uniffi.t1dm_core.kovatchevFInv as uniffiKovatchevFInv
import uniffi.t1dm_core.normalizeSample as uniffiNormalizeSample
import uniffi.t1dm_core.onBoard as uniffiOnBoard
import uniffi.t1dm_core.parseDescriptor as uniffiParseDescriptor
import uniffi.t1dm_core.roundtrip as uniffiRoundtrip
import uniffi.t1dm_core.AdvancedStats as UniffiAdvancedStats
import uniffi.t1dm_core.AgpBin as UniffiAgpBin
import uniffi.t1dm_core.BasalDoseSpec as UniffiBasalDoseSpec
import uniffi.t1dm_core.BasalSchedule as UniffiBasalSchedule
import uniffi.t1dm_core.BuiltContext as UniffiBuiltContext
import uniffi.t1dm_core.ChannelStat as UniffiChannelStat
import uniffi.t1dm_core.CurveEvent as UniffiCurveEvent
import uniffi.t1dm_core.CurveKind as UniffiCurveKind
import uniffi.t1dm_core.DecodedAdvert as UniffiDecodedAdvert
import uniffi.t1dm_core.Forecast as UniffiForecast
import uniffi.t1dm_core.ForecastStatus as UniffiForecastStatus
import uniffi.t1dm_core.ModelDescriptor as UniffiModelDescriptor
import uniffi.t1dm_core.MoodSummary as UniffiMoodSummary
import uniffi.t1dm_core.StatSample as UniffiStatSample
import uniffi.t1dm_core.SubBands as UniffiSubBands

/**
 * The real [NativeCore], backed by the uniffi-generated binding into the Rust `t1dm-core`
 * crate. Requires libt1dm_core.so in jniLibs (produced by the `cargoNdkBuild` task); until
 * the NDK cross-build runs, [StubNativeCore] stands in so the app runs on host-only tooling.
 *
 * The Rust `decode_advert` / `parse_descriptor` return `Result`, so a short or CRC-failing
 * payload (resp. malformed descriptor) surfaces as `CoreException` and we map it to the
 * frozen contract's `null`. The remaining pre/post fns surface a malformed shape as
 * `CoreException` too; those are programmer errors on this side of the seam (the Rust is the
 * numeric authority), so they propagate rather than being swallowed. The uniffi record types
 * live under `uniffi.t1dm_core`; the [toModel]/[toUniffi] projections translate them to and
 * from the `:core:model` data classes every downstream consumer speaks.
 */
class UniffiNativeCore : NativeCore {
    override fun roundtrip(msg: String): String = uniffiRoundtrip(msg)

    override fun decodeAdvert(payload: ByteArray): DecodedAdvert? =
        try {
            uniffiDecodeAdvert(payload).toModel()
        } catch (_: CoreException) {
            null
        }

    override fun advertCrc32(payload: ByteArray): Long = uniffiAdvertCrc32(payload)

    override fun kovatchevF(mgdl: Double): Double = uniffiKovatchevF(mgdl)

    override fun kovatchevFInv(risk: Double): Double = uniffiKovatchevFInv(risk)

    // ── Model pre/post pipeline (Phase 2, INFERENCE.md §§6-8) ───────────────────────

    override fun parseDescriptor(json: String): ModelDescriptor? =
        try {
            uniffiParseDescriptor(json).toModel()
        } catch (_: CoreException) {
            null
        }

    override fun causalSmooth(series: List<Double>, clampMin: Double?, clampMax: Double?): List<Double> =
        uniffiCausalSmooth(series, clampMin, clampMax)

    override fun normalizeSample(desc: ModelDescriptor, bg: Double, carb: Double, insulin: Double): List<Double> =
        uniffiNormalizeSample(desc.toUniffi(), bg, carb, insulin)

    override fun denormalizeSample(desc: ModelDescriptor, z: List<Double>): List<Double> =
        uniffiDenormalizeSample(desc.toUniffi(), z)

    override fun buildContext(
        desc: ModelDescriptor,
        bg: List<Double>,
        carb: List<Double>,
        insulin: List<Double>,
        announcedCarb: List<Double>?,
        announcedInsulin: List<Double>?,
    ): BuiltContext =
        uniffiBuildContext(desc.toUniffi(), bg, carb, insulin, announcedCarb, announcedInsulin).toModel()

    override fun assembleDecode(
        desc: ModelDescriptor,
        headRaw: List<Double>,
        lastBg: Double,
        carrySpread: Double,
    ): Forecast =
        uniffiAssembleDecode(desc.toUniffi(), headRaw, lastBg, carrySpread).toModel()

    override fun forecastDegeneracyCheck(forecast: Forecast): ForecastStatus =
        uniffiForecastDegeneracyCheck(forecast.toUniffi()).toModel()

    // ── Shared curve/PK engine (Phase 4, PLAN §3.3) ─────────────────────────────────

    override fun gamma(total: Double, k: Double, theta: Double, durMin: Double): List<Double> =
        uniffiGamma(total, k, theta, durMin)

    override fun bateman(total: Double, durMin: Double, ka: Double, ke: Double): List<Double> =
        uniffiBateman(total, durMin, ka, ke)

    override fun bolusPkForDose(doseU: Double): CurveEvent =
        uniffiBolusPkForDose(doseU).toModel()

    override fun bucketize(
        events: List<CurveEvent>,
        gridStartMs: Long,
        nSteps: Int,
        kind: CurveKind,
    ): List<Double> =
        uniffiBucketize(events.map { it.toUniffi() }, gridStartMs, nSteps, kind.toUniffi())

    override fun onBoard(events: List<CurveEvent>, atMs: Long, kind: CurveKind): Double =
        uniffiOnBoard(events.map { it.toUniffi() }, atMs, kind.toUniffi())

    override fun extendBasal(schedule: BasalSchedule, fromMs: Long, toMs: Long): List<CurveEvent> =
        uniffiExtendBasal(schedule.toUniffi(), fromMs, toMs).map { it.toModel() }

    // ── Advanced stats (Phase 6) ────────────────────────────────────────────────────

    /**
     * Rust `advanced_stats` throws only on a bad range / bin count (both caller-controlled and
     * validated upstream); the empty/all-invalid series returns `AdvancedStats::empty()` as `Ok`.
     * We nonetheless map any `CoreException` to the model's fail-closed [AdvancedStats.EMPTY] so a
     * malformed argument can never crash the stats screen — the safety posture is fail-closed.
     */
    override fun advancedStats(
        samples: List<StatSample>,
        targetLow: Int,
        targetHigh: Int,
        agpBins: Int,
    ): AdvancedStats =
        try {
            uniffiAdvancedStats(
                samples.map { it.toUniffi() },
                targetLow.toUShort(),
                targetHigh.toUShort(),
                agpBins.toUInt(),
            ).toModel()
        } catch (_: CoreException) {
            AdvancedStats.EMPTY
        }
}

private fun StatSample.toUniffi(): UniffiStatSample = UniffiStatSample(
    tsMs = tsMs,
    bgMgdl = bgMgdl,
    carbsG = carbsG,
    bolusU = bolusU,
    basalU = basalU,
    steps = steps,
    mood = mood,
)

private fun UniffiSubBands.toModel(): SubBands = SubBands(
    veryLow = veryLow, low = low, inRange = inRange, high = high, veryHigh = veryHigh,
)

private fun UniffiAgpBin.toModel(): AgpBin = AgpBin(
    minuteOfDay = minuteOfDay.toInt(),
    p5 = p5, p25 = p25, p50 = p50, p75 = p75, p95 = p95,
)

private fun UniffiMoodSummary.toModel(): MoodSummary = MoodSummary(
    mean = mean, n = n.toInt(), min = min, max = max,
)

private fun UniffiAdvancedStats.toModel(): AdvancedStats = AdvancedStats(
    nSamples = nSamples.toInt(),
    spanMs = spanMs,
    tir = tir, tbr = tbr, tar = tar,
    subBands = subBands.toModel(),
    lbgi = lbgi, hbgi = hbgi, mage = mage,
    meanBg = meanBg, sd = sd, cv = cv, gmi = gmi,
    totalCarbs = totalCarbs, totalBolus = totalBolus, totalBasal = totalBasal,
    meanDailyCarbs = meanDailyCarbs, tdd = tdd, bolusBasalRatio = bolusBasalRatio,
    meanSteps = meanSteps,
    mood = mood?.toModel(),
    agp = agp.map { it.toModel() },
)

private fun UniffiCurveKind.toModel(): CurveKind = when (this) {
    UniffiCurveKind.CARB -> CurveKind.CARB
    UniffiCurveKind.INSULIN -> CurveKind.INSULIN
}

private fun CurveKind.toUniffi(): UniffiCurveKind = when (this) {
    CurveKind.CARB -> UniffiCurveKind.CARB
    CurveKind.INSULIN -> UniffiCurveKind.INSULIN
}

private fun UniffiCurveEvent.toModel(): CurveEvent = CurveEvent(
    startMs = startMs,
    stepMs = stepMs,
    kind = kind.toModel(),
    total = total,
    values = values,
)

private fun CurveEvent.toUniffi(): UniffiCurveEvent = UniffiCurveEvent(
    startMs = startMs,
    stepMs = stepMs,
    kind = kind.toUniffi(),
    total = total,
    values = values,
)

private fun BasalDoseSpec.toUniffi(): UniffiBasalDoseSpec = UniffiBasalDoseSpec(
    timeOfDayMin = timeOfDayMin,
    doseU = doseU,
    durationMin = durationMin,
    kaPerHour = kaPerHour,
    kePerHour = kePerHour,
)

private fun BasalSchedule.toUniffi(): UniffiBasalSchedule = UniffiBasalSchedule(
    tzOffsetMin = tzOffsetMin,
    doses = doses.map { it.toUniffi() },
)

private fun UniffiDecodedAdvert.toModel(): DecodedAdvert = DecodedAdvert(
    minFromStart = minFromStart,
    status = status,
    trendTenthsPerMin = trendTenthsPerMin,
    glucoseMgdl = glucoseMgdl,
    valid = valid,
    quality = quality,
    prev = prev.map { PrevGlucose(glucoseMgdl = it.glucoseMgdl, valid = it.valid, quality = it.quality) },
    crc32 = crc32,
)

private fun UniffiChannelStat.toModel(): ChannelStat = ChannelStat(mean = mean, std = std)

private fun ChannelStat.toUniffi(): UniffiChannelStat = UniffiChannelStat(mean = mean, std = std)

private fun UniffiModelDescriptor.toModel(): ModelDescriptor = ModelDescriptor(
    bg = bg.toModel(),
    carb = carb.toModel(),
    insulin = insulin.toModel(),
    ropeBase = ropeBase,
    medianGlobalDim = medianGlobalDim,
    stepBasisType = stepBasisType,
    quantileSpreadMin = quantileSpreadMin,
    negFill = negFill,
    predictionHorizonHours = predictionHorizonHours,
    maxContextPatches = maxContextPatches,
    minContextPatches = minContextPatches,
    patchSize = patchSize,
    nInputFeatures = nInputFeatures,
    conformalEnabled = conformalEnabled,
)

private fun ModelDescriptor.toUniffi(): UniffiModelDescriptor = UniffiModelDescriptor(
    bg = bg.toUniffi(),
    carb = carb.toUniffi(),
    insulin = insulin.toUniffi(),
    ropeBase = ropeBase,
    medianGlobalDim = medianGlobalDim,
    stepBasisType = stepBasisType,
    quantileSpreadMin = quantileSpreadMin,
    negFill = negFill,
    predictionHorizonHours = predictionHorizonHours,
    maxContextPatches = maxContextPatches,
    minContextPatches = minContextPatches,
    patchSize = patchSize,
    nInputFeatures = nInputFeatures,
    conformalEnabled = conformalEnabled,
)

private fun UniffiBuiltContext.toModel(): BuiltContext = BuiltContext(
    nCtx = nCtx,
    predictionPatches = predictionPatches,
    context = context,
    pred = pred,
    lastBg = lastBg,
)

private fun UniffiForecast.toModel(): Forecast = Forecast(
    medianRisk = medianRisk,
    qTauRisk = qTauRisk,
    medianBg = medianBg,
    bandsMgdl = bandsMgdl,
)

private fun Forecast.toUniffi(): UniffiForecast = UniffiForecast(
    medianRisk = medianRisk,
    qTauRisk = qTauRisk,
    medianBg = medianBg,
    bandsMgdl = bandsMgdl,
)

private fun UniffiForecastStatus.toModel(): ForecastStatus = when (this) {
    UniffiForecastStatus.OK -> ForecastStatus.OK
    UniffiForecastStatus.NON_FINITE -> ForecastStatus.NON_FINITE
    UniffiForecastStatus.RAIL_PINNED -> ForecastStatus.RAIL_PINNED
    UniffiForecastStatus.COLLAPSED_BAND -> ForecastStatus.COLLAPSED_BAND
    UniffiForecastStatus.MISORDERED_QUANTILES -> ForecastStatus.MISORDERED_QUANTILES
}
