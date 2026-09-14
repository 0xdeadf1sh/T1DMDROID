package com.t1dm.core.nativecore

import com.t1dm.core.common.GameWorld
import com.t1dm.core.common.GolfWorld
import com.t1dm.core.common.NativeCore
import com.t1dm.core.model.BallState
import com.t1dm.core.model.GolfCup
import com.t1dm.core.model.GolfRun
import com.t1dm.core.model.GolfTuning
import com.t1dm.core.common.NativeHead
import com.t1dm.core.model.GraphInput
import com.t1dm.core.model.HeadSpec
import com.t1dm.core.model.HeadTensorSpec
import com.t1dm.core.model.LoraGuardOpts
import com.t1dm.core.model.LoraGuardReport
import com.t1dm.core.model.LoraGuardVerdict
import com.t1dm.core.model.LoraConfig
import com.t1dm.core.model.LoraProgressSink
import com.t1dm.core.model.LoraSample
import com.t1dm.core.model.LoraTrainOpts
import com.t1dm.core.model.LoraTrainReport
import com.t1dm.core.model.LoraTrainResult
import com.t1dm.core.model.LoraWeights
import com.t1dm.core.model.MaskSpan
import com.t1dm.core.model.CarState
import com.t1dm.core.model.CarTuning
import com.t1dm.core.model.RunState
import com.t1dm.core.model.Obstacle
import com.t1dm.core.model.TerrainSpec
import com.t1dm.core.model.AdvancedStats
import com.t1dm.core.model.AgpBin
import com.t1dm.core.model.BasalDoseSpec
import com.t1dm.core.model.BasalSchedule
import com.t1dm.core.model.MoodSummary
import com.t1dm.core.model.EpisodeSummary
import com.t1dm.core.model.GradeSplit
import com.t1dm.core.model.ClinicalCuts
import com.t1dm.core.model.HeatCell
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
import com.t1dm.core.model.ScoredPoint
import com.t1dm.core.model.ClarkeZone
import com.t1dm.core.model.DtsZone
import com.t1dm.core.model.TrendMatrix
import com.t1dm.core.model.ConformalFit
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
import uniffi.t1dm_core.clarkeZoneGrid as uniffiClarkeZoneGrid
import uniffi.t1dm_core.dtsZoneGrid as uniffiDtsZoneGrid
import uniffi.t1dm_core.trendBinEdges as uniffiTrendBinEdges
import uniffi.t1dm_core.ConformalFit as UniffiConformalFit
import uniffi.t1dm_core.conformalMinCalWindows as uniffiConformalMinCalWindows
import uniffi.t1dm_core.fitQuantileConformal as uniffiFitQuantileConformal
import uniffi.t1dm_core.applyQuantileConformal as uniffiApplyQuantileConformal
import uniffi.t1dm_core.applyQuantileConformalBatch as uniffiApplyQuantileConformalBatch
import uniffi.t1dm_core.advancedStats as uniffiAdvancedStats
import uniffi.t1dm_core.clinicalCuts as uniffiClinicalCuts
import uniffi.t1dm_core.advertCrc32 as uniffiAdvertCrc32
import uniffi.t1dm_core.assembleDecode as uniffiAssembleDecode
import uniffi.t1dm_core.stepStates as uniffiStepStates
import uniffi.t1dm_core.bateman as uniffiBateman
import uniffi.t1dm_core.bucketize as uniffiBucketize
import uniffi.t1dm_core.buildGraphInput as uniffiBuildGraphInput
import uniffi.t1dm_core.forecastSlice as uniffiForecastSlice
import uniffi.t1dm_core.bandLine as uniffiBandLine
import uniffi.t1dm_core.bandLineAt as uniffiBandLineAt
import uniffi.t1dm_core.loraNew as uniffiLoraNew
import uniffi.t1dm_core.LoraProgress as UniffiLoraProgress
import uniffi.t1dm_core.loraTrain as uniffiLoraTrain
import uniffi.t1dm_core.loraSerialize as uniffiLoraSerialize
import uniffi.t1dm_core.loraDeserialize as uniffiLoraDeserialize
import uniffi.t1dm_core.HeadModel as UniffiHeadModel
import uniffi.t1dm_core.HeadSpec as UniffiHeadSpec
import uniffi.t1dm_core.HeadTensorSpec as UniffiHeadTensorSpec
import uniffi.t1dm_core.GraphInput as UniffiGraphInput
import uniffi.t1dm_core.MaskSpan as UniffiMaskSpan
import uniffi.t1dm_core.LoraConfig as UniffiLoraConfig
import uniffi.t1dm_core.LoraWeights as UniffiLoraWeights
import uniffi.t1dm_core.LoraSample as UniffiLoraSample
import uniffi.t1dm_core.LoraGuardOpts as UniffiLoraGuardOpts
import uniffi.t1dm_core.LoraGuardReport as UniffiLoraGuardReport
import uniffi.t1dm_core.LoraGuardVerdict as UniffiLoraGuardVerdict
import uniffi.t1dm_core.LoraTrainOpts as UniffiLoraTrainOpts
import uniffi.t1dm_core.loraGuard as uniffiLoraGuard
import uniffi.t1dm_core.loraGuardOptsFit as uniffiLoraGuardOptsFit
import uniffi.t1dm_core.LoraTrainReport as UniffiLoraTrainReport
import uniffi.t1dm_core.LoraTrainResult as UniffiLoraTrainResult
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
import uniffi.t1dm_core.Obstacle as UniffiObstacle
import uniffi.t1dm_core.defaultGolfTuning as uniffiDefaultGolfTuning
import uniffi.t1dm_core.BallState as UniffiBallState
import uniffi.t1dm_core.GolfCup as UniffiGolfCup
import uniffi.t1dm_core.GolfRun as UniffiGolfRun
import uniffi.t1dm_core.GolfTuning as UniffiGolfTuning
import uniffi.t1dm_core.GolfWorld as UniffiGolfWorldObject
import uniffi.t1dm_core.CgEga as UniffiCgEga
import uniffi.t1dm_core.CgEgaRegion as UniffiCgEgaRegion
import uniffi.t1dm_core.ScoredPoint as UniffiScoredPoint
import uniffi.t1dm_core.ClarkeZone as UniffiClarkeZone
import uniffi.t1dm_core.DtsZone as UniffiDtsZone
import uniffi.t1dm_core.TrendMatrix as UniffiTrendMatrix
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
import uniffi.t1dm_core.HeatCell as UniffiHeatCell
import uniffi.t1dm_core.HistBin as UniffiHistBin
import uniffi.t1dm_core.TodBucket as UniffiTodBucket
import uniffi.t1dm_core.PredictedTime as UniffiPredictedTime
import uniffi.t1dm_core.TimeHead as UniffiTimeHead
import uniffi.t1dm_core.StatSample as UniffiStatSample
import uniffi.t1dm_core.SubBands as UniffiSubBands

/** Needs libt1dm_core.so (else [StubNativeCore]); CoreException from decode/parse maps to null */
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

    // INFERENCE.md §§6-8

    override fun parseDescriptor(json: String): ModelDescriptor? =
        try {
            uniffiParseDescriptor(json).toModel()
        } catch (_: CoreException) {
            null
        }

    override fun causalSmooth(series: List<Double>, clampMin: Double?, clampMax: Double?, window: Int): List<Double> =
        uniffiCausalSmooth(series, clampMin, clampMax, window)

    override fun normalizeSample(
        desc: ModelDescriptor,
        bg: Double,
        carb: Double,
        insulin: Double,
        exercise: Double,
    ): List<Double> = uniffiNormalizeSample(desc.toUniffi(), bg, carb, insulin, exercise)

    override fun denormalizeSample(desc: ModelDescriptor, z: List<Double>): List<Double> =
        uniffiDenormalizeSample(desc.toUniffi(), z)

    override fun buildGraphInput(
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
    ): GraphInput = uniffiBuildGraphInput(
        desc.toUniffi(), bg, carb, insulin, exercise,
        announcedCarb, announcedInsulin, announcedExercise,
        maskSpans.map { it.toUniffi() }, withForecast, smoothingWindow,
    ).toModel()

    override fun assembleDecode(
        desc: ModelDescriptor,
        headRaw: List<Double>,
        anchors: List<Double>,
        slotPatch: List<Int>,
        nMasked: Int,
        carrySpread: List<Double>,
    ): Forecast =
        uniffiAssembleDecode(desc.toUniffi(), headRaw, anchors, slotPatch, nMasked, carrySpread).toModel()

    override fun stepStates(
        desc: ModelDescriptor,
        hidden: List<Float>,
        slotPatch: List<Int>,
        attnMask: List<Float>,
    ): List<Double> = uniffiStepStates(desc.toUniffi(), hidden, slotPatch, attnMask)

    override fun forecastSlice(f: Forecast, fromPatch: Int, toPatch: Int): Forecast =
        uniffiForecastSlice(f.toUniffi(), fromPatch, toPatch).toModel()

    override fun bandLine(desc: ModelDescriptor, f: Forecast, tau: Double): List<Double> =
        uniffiBandLine(desc.toUniffi(), f.toUniffi(), tau)

    override fun bandLineAt(desc: ModelDescriptor, qTauRisk: List<Double>, tau: Double): List<Double> =
        uniffiBandLineAt(desc.toUniffi(), qTauRisk, tau)

    /** Owns native memory; [close] releases it. */
    private class UniffiHead(val inner: UniffiHeadModel) : NativeHead {
        override fun setLora(w: LoraWeights?) = inner.setLora(w?.toUniffi())
        override fun hasLora(): Boolean = inner.hasLora()
        override fun forward(stepStates: List<Double>, nSlots: Int): List<Double> =
            inner.forward(stepStates, nSlots)
        override fun close() = inner.destroy()
    }

    override fun headOpen(bytes: ByteArray, spec: HeadSpec): NativeHead? =
        try {
            UniffiHead(UniffiHeadModel.parse(bytes, spec.toUniffi()))
        } catch (_: CoreException) {
            null
        }

    override fun loraTrain(
        head: NativeHead,
        desc: ModelDescriptor,
        samples: List<LoraSample>,
        config: LoraConfig,
        opts: LoraTrainOpts,
        progress: LoraProgressSink?,
    ): LoraTrainResult = uniffiLoraTrain(
        (head as UniffiHead).inner,
        desc.toUniffi(),
        samples.map { it.toUniffi() },
        config.toUniffi(),
        opts.toUniffi(),
        progress?.let { sink ->
            object : UniffiLoraProgress {
                override fun onEpoch(epoch: Int, epochs: Int, trainLoss: Double, holdoutLoss: Double) =
                    sink.onEpoch(epoch, epochs, trainLoss, holdoutLoss)
            }
        },
    ).toModel()

    override fun loraNew(
        config: LoraConfig,
        headSha256: String,
        dModel: Int,
        hidden: Int,
        outDim: Int,
        seed: Long,
    ): LoraWeights =
        uniffiLoraNew(config.toUniffi(), headSha256, dModel, hidden, outDim, seed).toModel()

    override fun loraGuard(
        head: NativeHead,
        desc: ModelDescriptor,
        samples: List<LoraSample>,
        weights: LoraWeights,
        opts: LoraGuardOpts,
    ): LoraGuardReport = uniffiLoraGuard(
        (head as UniffiHead).inner,
        desc.toUniffi(),
        samples.map { it.toUniffi() },
        weights.toUniffi(),
        opts.toUniffi(),
    ).toModel()

    override fun loraGuardOptsFit(): LoraGuardOpts = uniffiLoraGuardOptsFit().toModel()

    override fun loraSerialize(w: LoraWeights): ByteArray = uniffiLoraSerialize(w.toUniffi())

    override fun loraDeserialize(bytes: ByteArray): LoraWeights? =
        try {
            uniffiLoraDeserialize(bytes).toModel()
        } catch (_: CoreException) {
            null
        }

    override fun forecastDegeneracyCheck(desc: ModelDescriptor, forecast: Forecast): ForecastStatus =
        uniffiForecastDegeneracyCheck(desc.toUniffi(), forecast.toUniffi()).toModel()

    /** Fail-open null: malformed time output must not crash a cycle; BG path unaffected. */
    override fun decodeTime(timeLogits: List<Double>, nBins: Int, binHours: Double): PredictedTime? =
        try {
            uniffiDecodeTime(timeLogits, nBins, binHours).toModel()
        } catch (_: CoreException) {
            null
        }

    // SPEC §3.3

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

    /** Fail-closed to [AdvancedStats.EMPTY]; a malformed argument can't crash the stats screen. */
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

    /** Cannot fail; fail-closed map kept anyway — a guessed scale anchor is worse than none. */
    override fun clinicalCuts(): ClinicalCuts =
        try {
            uniffiClinicalCuts().let { ClinicalCuts(it.veryLowMgdl, it.veryHighMgdl) }
        } catch (_: CoreException) {
            ClinicalCuts.UNAVAILABLE
        }

    /** Fail-closed to [MetricsSuite.EMPTY], which renders as "insufficient history". */
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

    /** No lattice at all, not partial — a partial one paints regions wrong instead of absent. */
    override fun clarkeZoneGrid(
        truthAxisMgdl: List<Double>,
        predAxisMgdl: List<Double>,
    ): List<ClarkeZone> =
        try {
            uniffiClarkeZoneGrid(truthAxisMgdl, predAxisMgdl).map { it.toModel() }
        } catch (_: CoreException) {
            emptyList()
        }

    /** As [clarkeZoneGrid]: no lattice at all rather than a partial one. */
    override fun dtsZoneGrid(
        truthAxisMgdl: List<Double>,
        predAxisMgdl: List<Double>,
    ): List<DtsZone> =
        try {
            uniffiDtsZoneGrid(truthAxisMgdl, predAxisMgdl).map { it.toModel() }
        } catch (_: CoreException) {
            emptyList()
        }

    /** Cannot fail; an axis labelled from a guessed edge is worse than an unlabelled one. */
    override fun trendBinEdges(): List<Double> =
        try {
            uniffiTrendBinEdges()
        } catch (_: CoreException) {
            emptyList()
        }

    // INFERENCE.md §8.4

    override fun conformalMinCalWindows(): Int = uniffiConformalMinCalWindows().toInt()

    /** Fail-closed to [ConformalFit.NONE], the same answer the core's own refusal path gives. */
    override fun fitQuantileConformal(
        windows: List<ForecastWindow>,
        minCalWindows: Int,
    ): ConformalFit =
        try {
            uniffiFitQuantileConformal(windows.map { it.toUniffi() }, minCalWindows.toUInt()).toModel()
        } catch (_: CoreException) {
            ConformalFit.NONE
        }

    /** `null` means the RAW fan to the one display surface that calls this. */
    override fun applyQuantileConformal(
        bandsMgdl: List<Double>,
        delta: List<Double>,
    ): List<Double>? =
        try {
            uniffiApplyQuantileConformal(bandsMgdl, delta)
        } catch (_: CoreException) {
            null
        }

    /** Like [applyQuantileConformal], batched; core refuses rather than partially correct. */
    override fun applyQuantileConformalBatch(
        fansMgdl: List<Double>,
        delta: List<Double>,
    ): List<Double>? =
        try {
            uniffiApplyQuantileConformalBatch(fansMgdl, delta)
        } catch (_: CoreException) {
            null
        }

    override fun defaultCarTuning(): CarTuning = uniffiDefaultCarTuning().toModel()

    /** NOT swallowed (unlike above): rejects degenerate terrain/tuning; a stub would hide bug. */
    override fun createGameWorld(terrain: TerrainSpec, tuning: CarTuning, obstacles: List<Obstacle>): GameWorld =
        UniffiGameWorld(
            UniffiGameWorldObject.withObstacles(terrain.toUniffi(), tuning.toUniffi(), obstacles.map { it.toUniffi() }),
        )

    override fun defaultGolfTuning(): GolfTuning = uniffiDefaultGolfTuning().toModel()

    /** As [createGameWorld]: a terrain with no room for a cup is an error, not a silent stub. */
    override fun createGolfWorld(terrain: TerrainSpec, tuning: GolfTuning, obstacles: List<Obstacle>): GolfWorld =
        UniffiGolfWorld(
            UniffiGolfWorldObject.withObstacles(terrain.toUniffi(), tuning.toUniffi(), obstacles.map { it.toUniffi() }),
        )
}

/** Holds trackLength locally; a per-frame FFI round trip for a constant is what Rust avoids. */
private class UniffiGameWorld(private val rust: UniffiGameWorldObject) : GameWorld {
    override val trackLength: Float = rust.trackLength()

    override fun step(dtMs: Float, throttle: Float, brake: Float): CarState =
        rust.step(dtMs, throttle, brake).toModel()

    override fun state(): CarState = rust.state().toModel()

    override fun reset(): CarState = rust.reset().toModel()

    override fun resetAt(x: Float): CarState = rust.resetAt(x).toModel()

    /** Frees the Rust world now rather than at the next GC. */
    override fun close() = rust.close()
}

/** Holds `trackLength` and `cup` locally: both fixed at construction, so a round trip is waste. */
private class UniffiGolfWorld(private val rust: UniffiGolfWorldObject) : GolfWorld {
    override val trackLength: Float = rust.trackLength()

    override val cup: GolfCup = rust.cup().toModel()

    override fun step(dtMs: Float): BallState = rust.step(dtMs).toModel()

    override fun state(): BallState = rust.state().toModel()

    override fun shoot(vx: Float, vy: Float): BallState = rust.shoot(vx, vy).toModel()

    override fun teeAt(x: Float): BallState = rust.teeAt(x).toModel()

    override fun reset(): BallState = rust.reset().toModel()

    /** Frees the Rust world now rather than at the next GC. */
    override fun close() = rust.close()
}

private fun GolfTuning.toUniffi(): UniffiGolfTuning = UniffiGolfTuning(
    ballRadius = ballRadius,
    ballMass = ballMass,
    restitution = restitution,
    friction = friction,
    rollingDamping = rollingDamping,
    gravity = gravity,
    maxLaunchSpeed = maxLaunchSpeed,
    restSpeed = restSpeed,
    restHoldS = restHoldS,
)

private fun UniffiGolfTuning.toModel(): GolfTuning = GolfTuning(
    ballRadius = ballRadius,
    ballMass = ballMass,
    restitution = restitution,
    friction = friction,
    rollingDamping = rollingDamping,
    gravity = gravity,
    maxLaunchSpeed = maxLaunchSpeed,
    restSpeed = restSpeed,
    restHoldS = restHoldS,
)

private fun UniffiGolfCup.toModel(): GolfCup = GolfCup(x0 = x0, x1 = x1, rimY = rimY, depth = depth)

private fun UniffiGolfRun.toModel(): GolfRun = when (this) {
    UniffiGolfRun.PLAYING -> GolfRun.Playing
    UniffiGolfRun.HOLED -> GolfRun.Holed
}

private fun UniffiBallState.toModel(): BallState = BallState(
    x = x, y = y, vx = vx, vy = vy, angle = angle,
    atRest = atRest,
    airborne = airborne,
    strokes = strokes.toInt(),
    penalties = penalties.toInt(),
    impact = impact,
    run = run.toModel(),
)

private fun TerrainSpec.toUniffi(): UniffiTerrainSpec = UniffiTerrainSpec(
    heights = heights,
    dx = dx,
    worldHeight = worldHeight,
)

private fun Obstacle.toUniffi(): UniffiObstacle = UniffiObstacle(x = x, halfW = halfW, h = h)

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

private fun UniffiConformalFit.toModel(): ConformalFit = ConformalFit(
    delta = delta,
    steps = steps.toInt(),
    nQuantiles = nQuantiles.toInt(),
    nWindows = nWindows.toInt(),
    nCal = nCal.toInt(),
    nEval = nEval.toInt(),
    nRejected = nRejected.toInt(),
    minCalWindows = minCalWindows.toInt(),
    sufficient = sufficient,
    maxAbsDeltaMgdl = maxAbsDeltaMgdl,
    cov90Raw = cov90Raw,
    cov90Cal = cov90Cal,
    meanWidth90Raw = meanWidth90Raw,
    meanWidth90Cal = meanWidth90Cal,
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

private fun UniffiClarkeZone.toModel(): ClarkeZone = when (this) {
    UniffiClarkeZone.A -> ClarkeZone.A
    UniffiClarkeZone.B -> ClarkeZone.B
    UniffiClarkeZone.C -> ClarkeZone.C
    UniffiClarkeZone.D -> ClarkeZone.D
    UniffiClarkeZone.E -> ClarkeZone.E
}

private fun UniffiDtsZone.toModel(): DtsZone = when (this) {
    UniffiDtsZone.A -> DtsZone.A
    UniffiDtsZone.B -> DtsZone.B
    UniffiDtsZone.C -> DtsZone.C
    UniffiDtsZone.D -> DtsZone.D
    UniffiDtsZone.E -> DtsZone.E
}

private fun UniffiScoredPoint.toModel(): ScoredPoint = ScoredPoint(
    pred = pred,
    truth = truth,
    clarke = clarke.toModel(),
    dts = dts.toModel(),
    dtsRisk = dtsRisk,
)

private fun UniffiTrendMatrix.toModel(): TrendMatrix = TrendMatrix(
    counts = counts.map { it.toInt() },
    categoryN = categoryN.map { it.toInt() },
    categoryPct = categoryPct,
    n = n.toInt(),
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
    dtsA = dtsA,
    dtsB = dtsB,
    dtsC = dtsC,
    dtsD = dtsD,
    dtsE = dtsE,
    dtsMeanAbsRisk = dtsMeanAbsRisk,
    skillPoint = skillPoint,
    points = points.map { it.toModel() },
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
    trend = trend.toModel(),
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
    tzOffsetMin = tzOffsetMin,
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

private fun UniffiHeatCell.toModel(): HeatCell = HeatCell(
    dow = dow.toInt(), hour = hour.toInt(), n = n.toInt(), meanBg = meanBg, medianBg = medianBg,
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
    heatmap = heatmap.map { it.toModel() },
)

private fun UniffiCurveKind.toModel(): CurveKind = when (this) {
    UniffiCurveKind.CARB -> CurveKind.CARB
    UniffiCurveKind.INSULIN -> CurveKind.INSULIN
    UniffiCurveKind.EXERCISE -> CurveKind.EXERCISE
}

private fun CurveKind.toUniffi(): UniffiCurveKind = when (this) {
    CurveKind.CARB -> UniffiCurveKind.CARB
    CurveKind.INSULIN -> UniffiCurveKind.INSULIN
    CurveKind.EXERCISE -> UniffiCurveKind.EXERCISE
}

private fun UniffiInsulinFamily.toModel(): InsulinFamily = when (this) {
    UniffiInsulinFamily.RAPID_EXP -> InsulinFamily.RapidExp
    UniffiInsulinFamily.BASAL_BATEMAN -> InsulinFamily.BasalBateman
    else -> throw IllegalStateException("Unexpected UniffiInsulinFamily: $this")
}

// Rust preset enum not projected: keys on stable [InsulinPresetSpec.label], not uniffi names.
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
    exercise = exercise.toModel(),
    ropeBase = ropeBase,
    quantileSpreadMin = quantileSpreadMin,
    negFill = negFill,
    predictionHorizonHours = predictionHorizonHours,
    maxContextPatches = maxContextPatches,
    minContextPatches = minContextPatches,
    patchSize = patchSize,
    nInputFeatures = nInputFeatures,
    seqLen = seqLen,
    maxMaskedPatches = maxMaskedPatches,
    maskMaxSpans = maskMaxSpans,
    maskSpanMax = maskSpanMax,
    dModel = dModel,
    archVersion = archVersion,
    kovatchev = kovatchev.toModel(),
    conformalEnabled = conformalEnabled,
    time = time?.toModel(),
    head = head?.toModel(),
)

private fun ModelDescriptor.toUniffi(): UniffiModelDescriptor = UniffiModelDescriptor(
    bg = bg.toUniffi(),
    carb = carb.toUniffi(),
    insulin = insulin.toUniffi(),
    exercise = exercise.toUniffi(),
    ropeBase = ropeBase,
    quantileSpreadMin = quantileSpreadMin,
    negFill = negFill,
    predictionHorizonHours = predictionHorizonHours,
    maxContextPatches = maxContextPatches,
    minContextPatches = minContextPatches,
    patchSize = patchSize,
    nInputFeatures = nInputFeatures,
    seqLen = seqLen,
    maxMaskedPatches = maxMaskedPatches,
    maskMaxSpans = maskMaxSpans,
    maskSpanMax = maskSpanMax,
    dModel = dModel,
    archVersion = archVersion,
    kovatchev = kovatchev.toUniffi(),
    conformalEnabled = conformalEnabled,
    time = time?.toUniffi(),
    head = head?.toUniffi(),
)

private fun UniffiGraphInput.toModel(): GraphInput = GraphInput(
    nCtx = nCtx,
    t = t,
    patchDim = patchDim,
    mSlots = mSlots,
    nMasked = nMasked,
    patches = patches.toFloatArray(),
    attnMask = attnMask.toFloatArray(),
    slotSel = slotSel.toFloatArray(),
    anchors = anchors,
    slotPatch = slotPatch,
    firstForecastPatch = firstForecastPatch,
)

private fun MaskSpan.toUniffi(): UniffiMaskSpan = UniffiMaskSpan(startPatch, length)

private fun HeadTensorSpec.toUniffi(): UniffiHeadTensorSpec = UniffiHeadTensorSpec(name, shape)

private fun UniffiHeadTensorSpec.toModel(): HeadTensorSpec = HeadTensorSpec(name, shape)

private fun HeadSpec.toUniffi(): UniffiHeadSpec = UniffiHeadSpec(
    file = file,
    dtype = dtype,
    byteOrder = byteOrder,
    activation = activation,
    sha256 = sha256,
    dModel = dModel,
    hidden = hidden,
    outDim = outDim,
    decoder = decoder,
    tensors = tensors.map { it.toUniffi() },
)

private fun UniffiHeadSpec.toModel(): HeadSpec = HeadSpec(
    file = file,
    dtype = dtype,
    byteOrder = byteOrder,
    activation = activation,
    sha256 = sha256,
    dModel = dModel,
    hidden = hidden,
    outDim = outDim,
    decoder = decoder,
    tensors = tensors.map { it.toModel() },
)

private fun LoraConfig.toUniffi(): UniffiLoraConfig =
    UniffiLoraConfig(rank, alpha, targetHidden, targetL0, targetL1, targetL2)

private fun UniffiLoraConfig.toModel(): LoraConfig =
    LoraConfig(rank, alpha, targetHidden, targetL0, targetL1, targetL2)

private fun LoraWeights.toUniffi(): UniffiLoraWeights =
    UniffiLoraWeights(config.toUniffi(), headSha256, dModel, hidden, outDim, params)

private fun UniffiLoraWeights.toModel(): LoraWeights =
    LoraWeights(config.toModel(), headSha256, dModel, hidden, outDim, params)

private fun LoraSample.toUniffi(): UniffiLoraSample =
    UniffiLoraSample(hidden, anchors, targetBg, nSlots, hiddenPert, isForecast)

private fun LoraTrainOpts.toUniffi(): UniffiLoraTrainOpts =
    UniffiLoraTrainOpts(epochs, lr, holdoutFrac, weightDecay, seed, distillWeight)

private fun LoraGuardOpts.toUniffi(): UniffiLoraGuardOpts = UniffiLoraGuardOpts(
    maxWindows, minWindows, probeDoseU, minFrozenResponse,
    minRetention, maxRetention, minSignAgreement,
)

private fun UniffiLoraGuardOpts.toModel(): LoraGuardOpts = LoraGuardOpts(
    maxWindows, minWindows, probeDoseU, minFrozenResponse,
    minRetention, maxRetention, minSignAgreement,
)

private fun UniffiLoraGuardReport.toModel(): LoraGuardReport = LoraGuardReport(
    verdict = when (verdict) {
        UniffiLoraGuardVerdict.PASS -> LoraGuardVerdict.PASS
        UniffiLoraGuardVerdict.BLOCKED -> LoraGuardVerdict.BLOCKED
        UniffiLoraGuardVerdict.INCONCLUSIVE -> LoraGuardVerdict.INCONCLUSIVE
    },
    nWindows = nWindows,
    frozenResponseMgdl = frozenResponseMgdl,
    adaptedResponseMgdl = adaptedResponseMgdl,
    retention = retention,
    signAgreement = signAgreement,
    why = why,
)

private fun UniffiLoraTrainReport.toModel(): LoraTrainReport = LoraTrainReport(
    nTrain = nTrain,
    nHoldout = nHoldout,
    epochsRun = epochsRun,
    trainLossFirst = trainLossFirst,
    trainLossLast = trainLossLast,
    holdoutLossBefore = holdoutLossBefore,
    holdoutLossAfter = holdoutLossAfter,
    improved = improved,
    lossHistory = lossHistory,
    holdoutHistory = holdoutHistory,
    bestEpoch = bestEpoch,
    nPaired = nPaired,
    distillScale = distillScale,
    distillHistory = distillHistory,
    guard = guard?.toModel(),
)

private fun UniffiLoraTrainResult.toModel(): LoraTrainResult =
    LoraTrainResult(weights.toModel(), report.toModel())

private fun UniffiForecast.toModel(): Forecast = Forecast(
    medianRisk = medianRisk,
    qTauRisk = qTauRisk,
    medianBg = medianBg,
    bandsMgdl = bandsMgdl,
    slotPatch = slotPatch,
)

private fun Forecast.toUniffi(): UniffiForecast = UniffiForecast(
    medianRisk = medianRisk,
    qTauRisk = qTauRisk,
    medianBg = medianBg,
    bandsMgdl = bandsMgdl,
    slotPatch = slotPatch,
)

private fun UniffiForecastStatus.toModel(): ForecastStatus = when (this) {
    UniffiForecastStatus.OK -> ForecastStatus.OK
    UniffiForecastStatus.NON_FINITE -> ForecastStatus.NON_FINITE
    UniffiForecastStatus.RAIL_PINNED -> ForecastStatus.RAIL_PINNED
    UniffiForecastStatus.COLLAPSED_BAND -> ForecastStatus.COLLAPSED_BAND
    UniffiForecastStatus.MISORDERED_QUANTILES -> ForecastStatus.MISORDERED_QUANTILES
}
