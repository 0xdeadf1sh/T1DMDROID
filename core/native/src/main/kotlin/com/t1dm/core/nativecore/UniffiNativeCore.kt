package com.t1dm.core.nativecore

import com.t1dm.core.common.GameWorld
import com.t1dm.core.common.NativeCore
import com.t1dm.core.model.CarState
import com.t1dm.core.model.CarTuning
import com.t1dm.core.model.RunState
import com.t1dm.core.model.TerrainSpec
import com.t1dm.core.model.AdvancedStats
import com.t1dm.core.model.AgpBin
import com.t1dm.core.model.BasalDoseSpec
import com.t1dm.core.model.BasalSchedule
import com.t1dm.core.model.BuiltContext
import com.t1dm.core.model.MoodSummary
import com.t1dm.core.model.EpisodeSummary
import com.t1dm.core.model.GradeSplit
import com.t1dm.core.model.HistBin
import com.t1dm.core.model.StatSample
import com.t1dm.core.model.SubBands
import com.t1dm.core.model.TodBucket
import com.t1dm.core.model.ChannelStat
import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.InsulinFamily
import com.t1dm.core.model.InsulinPresetSpec
import com.t1dm.core.model.DecodedAdvert
import com.t1dm.core.model.CgEga
import com.t1dm.core.model.CgEgaRegion
import com.t1dm.core.model.ExcursionAccuracy
import com.t1dm.core.model.Forecast
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ForecastWindow
import com.t1dm.core.model.HorizonMetrics
import com.t1dm.core.model.MetricsConfig
import com.t1dm.core.model.MetricsSuite
import com.t1dm.core.model.PointBlock
import com.t1dm.core.model.KovatchevParams
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.core.model.PredictedTime
import com.t1dm.core.model.PrevGlucose
import com.t1dm.core.model.TimeHead
import uniffi.t1dm_core.CoreException
import uniffi.t1dm_core.forecastMetricsSuite as uniffiForecastMetricsSuite
import uniffi.t1dm_core.advancedStats as uniffiAdvancedStats
import uniffi.t1dm_core.advertCrc32 as uniffiAdvertCrc32
import uniffi.t1dm_core.assembleDecode as uniffiAssembleDecode
import uniffi.t1dm_core.bateman as uniffiBateman
import uniffi.t1dm_core.bucketize as uniffiBucketize
import uniffi.t1dm_core.buildContext as uniffiBuildContext
import uniffi.t1dm_core.causalSmooth as uniffiCausalSmooth
import uniffi.t1dm_core.decodeAdvert as uniffiDecodeAdvert
import uniffi.t1dm_core.decodeTime as uniffiDecodeTime
import uniffi.t1dm_core.denormalizeSample as uniffiDenormalizeSample
import uniffi.t1dm_core.expActionCurve as uniffiExpActionCurve
import uniffi.t1dm_core.insulinPresetCatalog as uniffiInsulinPresetCatalog
import uniffi.t1dm_core.extendBasal as uniffiExtendBasal
import uniffi.t1dm_core.forecastDegeneracyCheck as uniffiForecastDegeneracyCheck
import uniffi.t1dm_core.gamma as uniffiGamma
import uniffi.t1dm_core.kovatchevF as uniffiKovatchevF
import uniffi.t1dm_core.kovatchevFInv as uniffiKovatchevFInv
import uniffi.t1dm_core.normalizeSample as uniffiNormalizeSample
import uniffi.t1dm_core.onBoard as uniffiOnBoard
import uniffi.t1dm_core.parseDescriptor as uniffiParseDescriptor
import uniffi.t1dm_core.defaultCarTuning as uniffiDefaultCarTuning
import uniffi.t1dm_core.roundtrip as uniffiRoundtrip
import uniffi.t1dm_core.CarState as UniffiCarState
import uniffi.t1dm_core.CarTuning as UniffiCarTuning
import uniffi.t1dm_core.GameWorld as UniffiGameWorldObject
import uniffi.t1dm_core.RunState as UniffiRunState
import uniffi.t1dm_core.TerrainSpec as UniffiTerrainSpec
import uniffi.t1dm_core.CgEga as UniffiCgEga
import uniffi.t1dm_core.CgEgaRegion as UniffiCgEgaRegion
import uniffi.t1dm_core.ExcursionAccuracy as UniffiExcursionAccuracy
import uniffi.t1dm_core.ForecastWindow as UniffiForecastWindow
import uniffi.t1dm_core.HorizonMetrics as UniffiHorizonMetrics
import uniffi.t1dm_core.MetricsConfig as UniffiMetricsConfig
import uniffi.t1dm_core.MetricsSuite as UniffiMetricsSuite
import uniffi.t1dm_core.PointBlock as UniffiPointBlock
import uniffi.t1dm_core.AdvancedStats as UniffiAdvancedStats
import uniffi.t1dm_core.AgpBin as UniffiAgpBin
import uniffi.t1dm_core.BasalDoseSpec as UniffiBasalDoseSpec
import uniffi.t1dm_core.BasalSchedule as UniffiBasalSchedule
import uniffi.t1dm_core.BuiltContext as UniffiBuiltContext
import uniffi.t1dm_core.ChannelStat as UniffiChannelStat
import uniffi.t1dm_core.CurveEvent as UniffiCurveEvent
import uniffi.t1dm_core.CurveKind as UniffiCurveKind
import uniffi.t1dm_core.InsulinFamily as UniffiInsulinFamily
import uniffi.t1dm_core.InsulinPresetSpec as UniffiInsulinPresetSpec
import uniffi.t1dm_core.DecodedAdvert as UniffiDecodedAdvert
import uniffi.t1dm_core.Forecast as UniffiForecast
import uniffi.t1dm_core.ForecastStatus as UniffiForecastStatus
import uniffi.t1dm_core.KovatchevParams as UniffiKovatchevParams
import uniffi.t1dm_core.ModelDescriptor as UniffiModelDescriptor
import uniffi.t1dm_core.MoodSummary as UniffiMoodSummary
import uniffi.t1dm_core.EpisodeSummary as UniffiEpisodeSummary
import uniffi.t1dm_core.GradeSplit as UniffiGradeSplit
import uniffi.t1dm_core.HistBin as UniffiHistBin
import uniffi.t1dm_core.TodBucket as UniffiTodBucket
import uniffi.t1dm_core.PredictedTime as UniffiPredictedTime
import uniffi.t1dm_core.TimeHead as UniffiTimeHead
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

    override fun causalSmooth(series: List<Double>, clampMin: Double?, clampMax: Double?, window: Int): List<Double> =
        uniffiCausalSmooth(series, clampMin, clampMax, window)

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
        smoothingWindow: Int,
    ): BuiltContext =
        uniffiBuildContext(desc.toUniffi(), bg, carb, insulin, announcedCarb, announcedInsulin, smoothingWindow).toModel()

    override fun assembleDecode(
        desc: ModelDescriptor,
        headRaw: List<Double>,
        lastBg: Double,
        carrySpread: Double,
    ): Forecast =
        uniffiAssembleDecode(desc.toUniffi(), headRaw, lastBg, carrySpread).toModel()

    override fun forecastDegeneracyCheck(desc: ModelDescriptor, forecast: Forecast): ForecastStatus =
        uniffiForecastDegeneracyCheck(desc.toUniffi(), forecast.toUniffi()).toModel()

    /** Rust `decode_time` throws `CoreException` on a bad shape / non-finite logit; we map it to
     *  the fail-open `null` so a malformed time output can never crash a cycle (the BG forecast
     *  path is unaffected). */
    override fun decodeTime(timeLogits: List<Double>, nBins: Int, binHours: Double): PredictedTime? =
        try {
            uniffiDecodeTime(timeLogits, nBins, binHours).toModel()
        } catch (_: CoreException) {
            null
        }

    // ── Shared curve/PK engine (Phase 4, SPEC §3.3) ─────────────────────────────────

    override fun gamma(total: Double, k: Double, theta: Double, durMin: Double): List<Double> =
        uniffiGamma(total, k, theta, durMin)

    override fun bateman(total: Double, durMin: Double, ka: Double, ke: Double): List<Double> =
        uniffiBateman(total, durMin, ka, ke)

    override fun expActionCurve(total: Double, peakMin: Double, diaMin: Double): List<Double> =
        uniffiExpActionCurve(total, peakMin, diaMin)

    override fun insulinPresetCatalog(): List<InsulinPresetSpec> =
        uniffiInsulinPresetCatalog().map { it.toModel() }

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

    /**
     * Rust `forecast_metrics_suite` is total (empty input, non-finite values and mis-ordered fans
     * are handled internally) and only `Err`s on a structurally impossible argument — a horizon off
     * the five-minute grid, a ragged window set, a non-finite threshold. We map any `CoreException`
     * to [MetricsSuite.EMPTY] so the drill-down can never crash on a malformed window set; the
     * safety posture is fail-closed, and an empty suite renders as "insufficient history".
     */
    override fun forecastMetricsSuite(
        windows: List<ForecastWindow>,
        horizonsMin: List<Int>,
        config: MetricsConfig,
        includeCgEga: Boolean,
    ): MetricsSuite =
        try {
            uniffiForecastMetricsSuite(
                windows.map { it.toUniffi() },
                horizonsMin.map { it.toUInt() },
                config.toUniffi(),
                includeCgEga,
            ).toModel()
        } catch (_: CoreException) {
            MetricsSuite.EMPTY
        }

    // ── Hill-climb minigame physics (t1dm-core::game) ───────────────────────────────

    override fun defaultCarTuning(): CarTuning = uniffiDefaultCarTuning().toModel()

    /**
     * Unlike every fail-open/fail-closed mapping above, a `CoreException` here is NOT swallowed:
     * the constructor only rejects a degenerate terrain or tuning, which is a caller bug on this
     * side of the seam, and silently handing back a stub world would hide it behind a frozen car.
     */
    override fun createGameWorld(terrain: TerrainSpec, tuning: CarTuning): GameWorld =
        UniffiGameWorld(UniffiGameWorldObject(terrain.toUniffi(), tuning.toUniffi()))
}

/**
 * The uniffi object behind the pure-JVM [GameWorld] port. Holds `trackLength` locally because it
 * is fixed at construction and a per-frame FFI round trip for a constant is exactly the cost the
 * Rust solver exists to avoid.
 */
private class UniffiGameWorld(private val rust: UniffiGameWorldObject) : GameWorld {
    override val trackLength: Float = rust.trackLength()

    override fun step(dtMs: Float, throttle: Float, brake: Float): CarState =
        rust.step(dtMs, throttle, brake).toModel()

    override fun state(): CarState = rust.state().toModel()

    override fun reset(): CarState = rust.reset().toModel()

    override fun resetAt(x: Float): CarState = rust.resetAt(x).toModel()

    /** Frees the Rust world now rather than at the next GC — see [GameWorld]. */
    override fun close() = rust.close()
}

private fun TerrainSpec.toUniffi(): UniffiTerrainSpec = UniffiTerrainSpec(
    heights = heights,
    dx = dx,
    worldHeight = worldHeight,
)

private fun CarTuning.toUniffi(): UniffiCarTuning = UniffiCarTuning(
    chassisMass = chassisMass,
    chassisHalfLen = chassisHalfLen,
    chassisHalfHeight = chassisHalfHeight,
    wheelRadius = wheelRadius,
    wheelMass = wheelMass,
    suspensionRest = suspensionRest,
    suspensionTravel = suspensionTravel,
    suspensionStiffness = suspensionStiffness,
    suspensionDamping = suspensionDamping,
    motorTorque = motorTorque,
    brakeTorque = brakeTorque,
    maxWheelOmega = maxWheelOmega,
    grip = grip,
    tractionRelax = tractionRelax,
    gravity = gravity,
    crashTiltRad = crashTiltRad,
)

private fun UniffiCarTuning.toModel(): CarTuning = CarTuning(
    chassisMass = chassisMass,
    chassisHalfLen = chassisHalfLen,
    chassisHalfHeight = chassisHalfHeight,
    wheelRadius = wheelRadius,
    wheelMass = wheelMass,
    suspensionRest = suspensionRest,
    suspensionTravel = suspensionTravel,
    suspensionStiffness = suspensionStiffness,
    suspensionDamping = suspensionDamping,
    motorTorque = motorTorque,
    brakeTorque = brakeTorque,
    maxWheelOmega = maxWheelOmega,
    grip = grip,
    tractionRelax = tractionRelax,
    gravity = gravity,
    crashTiltRad = crashTiltRad,
)

private fun UniffiRunState.toModel(): RunState = when (this) {
    UniffiRunState.RUNNING -> RunState.Running
    UniffiRunState.CRASHED -> RunState.Crashed
    UniffiRunState.FINISHED -> RunState.Finished
}

private fun UniffiCarState.toModel(): CarState = CarState(
    x = x, y = y, angle = angle,
    vx = vx, vy = vy, angularVelocity = angularVelocity,
    rearX = rearX, rearY = rearY, rearAngle = rearAngle, rearOmega = rearOmega, rearContact = rearContact,
    frontX = frontX, frontY = frontY, frontAngle = frontAngle, frontOmega = frontOmega, frontContact = frontContact,
    rpm = rpm,
    throttleApplied = throttleApplied,
    impactImpulse = impactImpulse,
    roughness = roughness,
    airborne = airborne,
    distanceM = distanceM,
    run = run.toModel(),
    elapsedS = elapsedS,
)

private fun ForecastWindow.toUniffi(): UniffiForecastWindow = UniffiForecastWindow(
    bandsMgdl = bandsMgdl,
    medianBg = medianBg,
    realizedBg = realizedBg,
    lastBg = lastBg,
)

private fun MetricsConfig.toUniffi(): UniffiMetricsConfig = UniffiMetricsConfig(
    hypoThresholdMgdl = hypoThresholdMgdl,
    hyperThresholdMgdl = hyperThresholdMgdl,
    excursionPrecisionToleranceMgdl = excursionPrecisionToleranceMgdl,
    minSamples = minSamples.toUInt(),
)

private fun UniffiPointBlock.toModel(): PointBlock = PointBlock(
    rmsePoint = rmsePoint,
    maePoint = maePoint,
    rmseWinmean = rmseWinmean,
    maeWinmean = maeWinmean,
    mard = mard,
    clarkeA = clarkeA,
    clarkeAb = clarkeAb,
    clarkeD = clarkeD,
    clarkeE = clarkeE,
    skillPoint = skillPoint,
)

private fun UniffiExcursionAccuracy.toModel(): ExcursionAccuracy = ExcursionAccuracy(
    recall = recall,
    precision = precision,
    nTrue = nTrue.toInt(),
    nPred = nPred.toInt(),
)

private fun UniffiHorizonMetrics.toModel(): HorizonMetrics = HorizonMetrics(
    horizonMin = horizonMin.toInt(),
    n = n.toInt(),
    sufficient = sufficient,
    band = band.toModel(),
    medianLine = medianLine.toModel(),
    rmsePersistPoint = rmsePersistPoint,
    rmsePersistWinmean = rmsePersistWinmean,
    bandCov50 = bandCov50,
    bandWidth50 = bandWidth50,
    bandCov90 = bandCov90,
    bandWidth90 = bandWidth90,
    hypo = hypo.toModel(),
    hyper = hyper.toModel(),
)

private fun UniffiCgEgaRegion.toModel(): CgEgaRegion = CgEgaRegion(
    apPct = apPct,
    bePct = bePct,
    epPct = epPct,
    nAp = nAp.toInt(),
    nBe = nBe.toInt(),
    nEp = nEp.toInt(),
)

private fun UniffiCgEga.toModel(): CgEga = CgEga(
    hypo = hypo.toModel(),
    eu = eu.toModel(),
    hyper = hyper.toModel(),
)

private fun UniffiMetricsSuite.toModel(): MetricsSuite = MetricsSuite(
    horizons = horizons.map { it.toModel() },
    cgega = cgega?.toModel(),
    nWindows = nWindows.toInt(),
    nRejected = nRejected.toInt(),
    nSteps = nSteps.toInt(),
)

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

private fun UniffiTodBucket.toModel(): TodBucket = TodBucket(
    startMin = startMin.toInt(), n = n.toInt(), tir = tir, tbr = tbr, tar = tar,
)

private fun UniffiHistBin.toModel(): HistBin = HistBin(
    lo = lo, hi = hi, count = count.toInt(), frac = frac,
)

private fun UniffiEpisodeSummary.toModel(): EpisodeSummary = EpisodeSummary(
    count = count.toInt(),
    totalDurationMs = totalDurationMs,
    meanDurationMs = meanDurationMs,
    meanExtreme = meanExtreme,
    worstExtreme = worstExtreme,
)

private fun UniffiGradeSplit.toModel(): GradeSplit = GradeSplit(
    grade = grade, hypo = hypo, eu = eu, hyper = hyper,
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
    modd = modd,
    conga1 = conga1, conga2 = conga2, conga4 = conga4,
    jIndex = jIndex, mValue = mValue, adrr = adrr, dtdSd = dtdSd,
    grade = grade.toModel(),
    tod = tod.map { it.toModel() },
    histogram = histogram.map { it.toModel() },
    hypoEpisodes = hypoEpisodes.toModel(),
    hyperEpisodes = hyperEpisodes.toModel(),
)

private fun UniffiCurveKind.toModel(): CurveKind = when (this) {
    UniffiCurveKind.CARB -> CurveKind.CARB
    UniffiCurveKind.INSULIN -> CurveKind.INSULIN
}

private fun CurveKind.toUniffi(): UniffiCurveKind = when (this) {
    CurveKind.CARB -> UniffiCurveKind.CARB
    CurveKind.INSULIN -> UniffiCurveKind.INSULIN
}

private fun UniffiInsulinFamily.toModel(): InsulinFamily = when (this) {
    UniffiInsulinFamily.RAPID_EXP -> InsulinFamily.RapidExp
    UniffiInsulinFamily.BASAL_BATEMAN -> InsulinFamily.BasalBateman
    else -> throw IllegalStateException("Unexpected UniffiInsulinFamily: $this")
}

// The Rust `preset` enum is intentionally NOT projected — the app keys a selection by the stable
// [InsulinPresetSpec.label] and drives the curve off the numeric peak/DIA/ka/ke fields, so no
// fragile round-trip of the uniffi enum variant names is needed (issue 19).
private fun UniffiInsulinPresetSpec.toModel(): InsulinPresetSpec = InsulinPresetSpec(
    family = family.toModel(),
    label = label,
    peakMin = peakMin,
    diaMin = diaMin,
    kaPerHour = kaPerHour,
    kePerHour = kePerHour,
    offDistribution = offDistribution,
    citation = citation,
)

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

private fun UniffiTimeHead.toModel(): TimeHead = TimeHead(
    outputIndex = outputIndex, nBins = nBins, binHours = binHours,
)

private fun TimeHead.toUniffi(): UniffiTimeHead = UniffiTimeHead(
    outputIndex = outputIndex, nBins = nBins, binHours = binHours,
)

private fun UniffiPredictedTime.toModel(): PredictedTime = PredictedTime(
    probs = probs, predictedHour = predictedHour, resultantR = resultantR,
    nBins = nBins, binHours = binHours,
)

private fun UniffiKovatchevParams.toModel(): KovatchevParams = KovatchevParams(
    scale = scale, power = power, offset = offset,
    bgClampMin = bgClampMin, bgClampMax = bgClampMax,
)

private fun KovatchevParams.toUniffi(): UniffiKovatchevParams = UniffiKovatchevParams(
    scale = scale, power = power, offset = offset,
    bgClampMin = bgClampMin, bgClampMax = bgClampMax,
)

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
    kovatchev = kovatchev.toModel(),
    conformalEnabled = conformalEnabled,
    time = time?.toModel(),
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
    kovatchev = kovatchev.toUniffi(),
    conformalEnabled = conformalEnabled,
    time = time?.toUniffi(),
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
