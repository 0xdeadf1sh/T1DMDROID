package com.t1dm.calc

import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.core.common.GameWorld
import com.t1dm.core.common.GolfWorld
import com.t1dm.core.common.NativeCore
import com.t1dm.core.model.ClinicalCuts
import com.t1dm.core.model.*
import com.t1dm.data.curve.ChannelBuilder
import com.t1dm.data.curve.CurveEngine
import com.t1dm.data.curve.DoseStore
import com.t1dm.inference.BgHistoryProvider
import com.t1dm.inference.BgSeries
import com.t1dm.core.common.NativeHead
import com.t1dm.core.model.GraphInput
import com.t1dm.core.model.HeadSpec
import com.t1dm.core.model.LoraConfig
import com.t1dm.core.model.LoraSample
import com.t1dm.core.model.LoraTrainOpts
import com.t1dm.core.model.LoraTrainResult
import com.t1dm.core.model.LoraWeights
import com.t1dm.core.model.MaskSpan
import com.t1dm.inference.backend.GraphTensors
import com.t1dm.inference.backend.GraphOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CONTEXT at gridStartMs, FUTURE at +nCtx·STEP; candidate re-anchored by shift, fails OPEN. */
class RollingForecasterAlignmentTest {

    // G is grid-aligned; every event sits on an exact bucket boundary.
    private val g = 1_500_000_000_000L
    private val nCtx = 48                       // 8 patches of 6
    private val predZoneStartMs = g + nCtx * STEP_MS
    private val lastBucketStartMs = predZoneStartMs - STEP_MS   // the current, incomplete bucket
    private val nowMs = lastBucketStartMs + STEP_MS / 4         // inside its FIRST half

    private val descriptor = ModelDescriptor(
        bg = ChannelStat(0.0, 1.0),
        carb = ChannelStat(0.0, 1.0),
        insulin = ChannelStat(0.0, 1.0),
        exercise = ChannelStat(0.0, 1.0),
        ropeBase = 1000,
        quantileSpreadMin = 1e-3,
        negFill = -30000.0,
        predictionHorizonHours = 2,   // predSteps = 24
        maxContextPatches = 8,        // maxSteps = 48
        minContextPatches = 4,        // minSteps = 24
        patchSize = 6,
        nInputFeatures = 5,
        seqLen = 12,                  // maxContextPatches + predPatches
        maxMaskedPatches = 12,
        maskMaxSpans = 3,
        maskSpanMax = 8,
        dModel = 32,
        archVersion = "risk-v5",
        kovatchev = KovatchevParams(
            scale = 2.2211457449985317,
            power = 1.084,
            offset = 5.540076976170212,
            bgClampMin = 40.0,
            bgClampMax = 400.0,
        ),
        conformalEnabled = false,
    )

    private val dispatchers = DefaultT1dmDispatchers(
        main = Dispatchers.Unconfined,
        default = Dispatchers.Unconfined,
        io = Dispatchers.Unconfined,
        inference = Dispatchers.Unconfined,
    )

    @Test
    fun rollAlignsOriginsAndReanchorsCandidate() = runTest {
        val ctxInsulinEvent = CurveEvent(g + 2 * STEP_MS, STEP_MS, CurveKind.INSULIN, 2.5, listOf(2.5))
        val candidateU = 4.0
        val candidate = CurveEvent(nowMs, STEP_MS, CurveKind.INSULIN, candidateU, listOf(candidateU))

        val native = RecordingNativeCore()
        val store = object : DoseStore {
            override suspend fun carbEvents(fromMs: Long, toMs: Long): List<CurveEvent> = emptyList()
            override suspend fun insulinEvents(fromMs: Long, toMs: Long): List<CurveEvent> = listOf(ctxInsulinEvent)
            override suspend fun activeBasalSchedule(): BasalSchedule? = null
        }
        val channels = ChannelBuilder(CurveEngine(native, dispatchers), store)
        val history = object : BgHistoryProvider {
            override suspend fun recentBgSeries(maxSteps: Int, minSteps: Int): BgSeries =
                // Anchor 4 steps before the last grid slot, so anchorTsMs != the last slot.
                BgSeries(DoubleArray(nCtx) { 120.0 }, anchorTsMs = g + (nCtx - 4) * STEP_MS, gridStartMs = g)

            override suspend fun dosingBgSeries(maxSteps: Int, minSteps: Int): BgSeries =
                recentBgSeries(maxSteps, minSteps)
        }
        val model = object : SelectedModelHandle {
            override val descriptor = this@RollingForecasterAlignmentTest.descriptor
            override val backendInfo = fp32Backend()
            override suspend fun run(input: GraphTensors): GraphOutput = GraphOutput(FloatArray(4 * 6 * 7))
        }
        val forecaster = RollingForecaster(native, dispatchers, channels, history, SelectedModelProvider { model })

        val request = ForecastRequest(
            rollStartMs = nowMs,
            fullRollSteps = 24,
            validatedSteps = 24,
            announced = emptyList(),
            candidate = listOf(candidate),
            candidateU = candidateU,
        )
        val fan = forecaster.roll(request)

        assertTrue("the roll must complete and be eligible over the fakes", fan.eligible)
        assertTrue("buildContext must have been invoked", native.buildContextCalls.isNotEmpty())

        val futureCall = native.bucketizeCalls.first { it.kind == CurveKind.INSULIN && it.nSteps == request.fullRollSteps }
        val contextCall = native.bucketizeCalls.first { it.kind == CurveKind.INSULIN && it.nSteps == nCtx }
        assertEquals("future dose channel origin must be G + n·STEP (== InferenceController's predZone)", predZoneStartMs, futureCall.gridStartMs)
        assertEquals("context dose channel origin must be series.gridStartMs", g, contextCall.gridStartMs)
        assertFalse(
            "no bucketize may originate at the raw non-grid nowMs (the pre-fix future origin)",
            native.bucketizeCalls.any { it.gridStartMs == nowMs },
        )

        val built = native.buildContextCalls.first()

        // Without re-anchor the leading step rounds to idx<0 and drops — fail-open under-count.
        val announcedInsulin = built.announcedInsulin!!
        assertTrue("re-anchored candidate must occupy future bucket 0", announcedInsulin[0] > 0.0)
        assertEquals("re-anchored candidate must integrate to its full U", candidateU, announcedInsulin.sum(), 1e-9)

        // The pre-fix origin (anchor - (nCtx-1)·STEP) would place it at index 5.
        val contextInsulin = built.insulin
        assertEquals("context insulin event must land at grid index 2", 2.5, contextInsulin[2], 1e-9)
        assertEquals("context insulin event must NOT land at the pre-fix index 5", 0.0, contextInsulin.getOrElse(5) { 0.0 }, 1e-9)
    }

    /** Dosing context uses user's BG smoothing window (shifts last_bg); garbage gets snapped. */
    @Test
    fun rollBuildsContextAtTheUserSmoothingWindow() = runTest {
        for ((persisted, expected) in listOf(25 to 25, 1 to 1, 12 to 13, 0 to 1, -4 to 1)) {
            val native = RecordingNativeCore()
            val store = object : DoseStore {
                override suspend fun carbEvents(fromMs: Long, toMs: Long): List<CurveEvent> = emptyList()
                override suspend fun insulinEvents(fromMs: Long, toMs: Long): List<CurveEvent> = emptyList()
                override suspend fun activeBasalSchedule(): BasalSchedule? = null
            }
            val history = object : BgHistoryProvider {
                override suspend fun recentBgSeries(maxSteps: Int, minSteps: Int): BgSeries =
                    BgSeries(DoubleArray(nCtx) { 120.0 }, anchorTsMs = g + (nCtx - 1) * STEP_MS, gridStartMs = g)

                override suspend fun dosingBgSeries(maxSteps: Int, minSteps: Int): BgSeries =
                    recentBgSeries(maxSteps, minSteps)
            }
            val model = object : SelectedModelHandle {
                override val descriptor = this@RollingForecasterAlignmentTest.descriptor
                override val backendInfo = fp32Backend()
                override suspend fun run(input: GraphTensors): GraphOutput = GraphOutput(FloatArray(4 * 6 * 7))
            }
            val forecaster = RollingForecaster(
                native,
                dispatchers,
                ChannelBuilder(CurveEngine(native, dispatchers), store),
                history,
                SelectedModelProvider { model },
                smoothingWindowProvider = { persisted },
            )
            forecaster.roll(
                ForecastRequest(
                    rollStartMs = nowMs,
                    fullRollSteps = 24,
                    validatedSteps = 24,
                    announced = emptyList(),
                    candidate = null,
                    candidateU = 0.0,
                ),
            )
            assertTrue("buildContext must have been invoked", native.buildContextCalls.isNotEmpty())
            native.buildContextCalls.forEach {
                assertEquals("persisted $persisted must reach buildContext as $expected", expected, it.smoothingWindow)
            }
        }
    }

    /** SPEC/inference.md §8.1/§9: layout [up .75 .9 .95|dn .25 .1 .05]; roll 0 carries none. */
    @Test
    fun rollCarriesSpreadPerLevel() = runTest {
        val native = RecordingNativeCore()
        val store = object : DoseStore {
            override suspend fun carbEvents(fromMs: Long, toMs: Long): List<CurveEvent> = emptyList()
            override suspend fun insulinEvents(fromMs: Long, toMs: Long): List<CurveEvent> = emptyList()
            override suspend fun activeBasalSchedule(): BasalSchedule? = null
        }
        val history = object : BgHistoryProvider {
            override suspend fun recentBgSeries(maxSteps: Int, minSteps: Int): BgSeries =
                BgSeries(DoubleArray(nCtx) { 120.0 }, anchorTsMs = g + (nCtx - 1) * STEP_MS, gridStartMs = g)

            override suspend fun dosingBgSeries(maxSteps: Int, minSteps: Int): BgSeries =
                recentBgSeries(maxSteps, minSteps)
        }
        val model = object : SelectedModelHandle {
            override val descriptor = this@RollingForecasterAlignmentTest.descriptor
            override val backendInfo = fp32Backend()
            override suspend fun run(input: GraphTensors): GraphOutput = GraphOutput(FloatArray(4 * 6 * 7))
        }
        val forecaster = RollingForecaster(
            native, dispatchers, ChannelBuilder(CurveEngine(native, dispatchers), store),
            history, SelectedModelProvider { model },
        )

        val fan = forecaster.roll(
            ForecastRequest(
                rollStartMs = nowMs,
                fullRollSteps = 72,          // three 24-step rolls ⇒ two seams
                validatedSteps = 24,
                announced = emptyList(),
                candidate = null,
                candidateU = 0.0,
            ),
        )
        assertTrue("the roll must complete and be eligible over the fakes", fan.eligible)
        assertEquals("three rolls must each decode once", 3, native.carryCalls.size)

        assertTrue(
            "roll 0 has no seam behind it and must carry nothing, got ${native.carryCalls[0]}",
            native.carryCalls[0].isEmpty(),
        )
        // Off RISK_ROW: median 3.0, up 5/7/9⇒[2,4,6]; down 2/1/0⇒[1,2,3] nearest→far.
        val expected = listOf(2.0, 4.0, 6.0, 1.0, 2.0, 3.0)
        for (r in 1 until native.carryCalls.size) {
            assertEquals(
                "roll $r must carry the previous roll's terminal offsets, level by level",
                expected, native.carryCalls[r],
            )
        }
    }

    /** bucketize mirrors Rust: a step mapping to idx<0 is DROPPED; other methods unused here. */
    private class RecordingNativeCore : NativeCore {
        /** In roll order. */
        val carryCalls = mutableListOf<List<Double>>()

        data class BucketizeCall(val gridStartMs: Long, val nSteps: Int, val kind: CurveKind)
        data class BuildContextCall(
            val bg: List<Double>,
            val carb: List<Double>,
            val insulin: List<Double>,
            val exercise: List<Double>,
            val announcedCarb: List<Double>?,
            val announcedInsulin: List<Double>?,
            val announcedExercise: List<Double>?,
            val smoothingWindow: Int,
        )

        val bucketizeCalls = mutableListOf<BucketizeCall>()
        val buildContextCalls = mutableListOf<BuildContextCall>()

        override fun bucketize(events: List<CurveEvent>, gridStartMs: Long, nSteps: Int, kind: CurveKind): List<Double> {
            bucketizeCalls += BucketizeCall(gridStartMs, nSteps, kind)
            val out = DoubleArray(nSteps)
            for (ev in events) {
                if (ev.kind != kind) continue
                for (j in ev.values.indices) {
                    val absMs = ev.startMs + j.toLong() * ev.stepMs
                    val idx = Math.floorDiv(absMs - gridStartMs, STEP_MS).toInt() // idx<0: curve.rs
                    if (idx in 0 until nSteps) out[idx] += ev.values[j]
                }
            }
            return out.toList()
        }

        override fun onBoard(events: List<CurveEvent>, atMs: Long, kind: CurveKind): Double = 0.0

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
        ): GraphInput {
            buildContextCalls += BuildContextCall(
                bg, carb, insulin, exercise, announcedCarb, announcedInsulin, announcedExercise,
                smoothingWindow,
            )
            val patches = bg.size / desc.patchSize
            val predPatches = desc.predictionHorizonHours * 12 / desc.patchSize
            val t = patches + predPatches
            val m = desc.maxMaskedPatches
            return GraphInput(
                nCtx = patches,
                t = t,
                patchDim = desc.patchSize * desc.nInputFeatures,
                mSlots = m,
                nMasked = predPatches,
                patches = FloatArray(t * desc.patchSize * desc.nInputFeatures),
                attnMask = FloatArray(t * t),
                slotSel = FloatArray(m * t),
                anchors = List(m) { bg.lastOrNull() ?: 120.0 },
                slotPatch = List(m) { if (it < predPatches) patches + it else -1 },
                firstForecastPatch = patches,
            )
        }

        override fun stepStates(
            desc: ModelDescriptor,
            hidden: List<Float>,
            slotPatch: List<Int>,
            attnMask: List<Float>,
        ): List<Double> = List(slotPatch.size * desc.patchSize * desc.dModel) { 0.0 }

        override fun assembleDecode(
            desc: ModelDescriptor,
            headRaw: List<Double>,
            anchors: List<Double>,
            slotPatch: List<Int>,
            nMasked: Int,
            carrySpread: List<Double>,
        ): Forecast {
            carryCalls.add(carrySpread)
            val steps = nMasked * desc.patchSize
            val nq = 7
            return Forecast(
                medianRisk = List(steps) { 0.0 },
                qTauRisk = List(steps * nq) { RISK_ROW[it % nq] },
                medianBg = List(steps) { 120.0 },
                bandsMgdl = List(steps * nq) { 110.0 + (it % nq) * 2.0 },
                slotPatch = slotPatch.take(nMasked),
            )
        }

        override fun forecastSlice(f: Forecast, fromPatch: Int, toPatch: Int): Forecast = f

        override fun bandLine(desc: ModelDescriptor, f: Forecast, tau: Double): List<Double> = f.medianBg
        override fun bandLineAt(desc: ModelDescriptor, qTauRisk: List<Double>, tau: Double): List<Double> =
            emptyList()

        override fun forecastDegeneracyCheck(desc: ModelDescriptor, forecast: Forecast): ForecastStatus = ForecastStatus.OK

        private fun unused(): Nothing = error("unused by RollingForecasterAlignmentTest")
        override fun roundtrip(msg: String): String = unused()
        override fun decodeAdvert(payload: ByteArray): DecodedAdvert? = unused()
        override fun advertCrc32(payload: ByteArray): Long = unused()
        override fun kovatchevF(mgdl: Double): Double = unused()
        override fun kovatchevFInv(risk: Double): Double = unused()
        override fun parseDescriptor(json: String): ModelDescriptor? = unused()
        override fun causalSmooth(series: List<Double>, clampMin: Double?, clampMax: Double?, window: Int): List<Double> = unused()
        override fun normalizeSample(
            desc: ModelDescriptor,
            bg: Double,
            carb: Double,
            insulin: Double,
            exercise: Double,
        ): List<Double> = unused()
        override fun headOpen(bytes: ByteArray, spec: HeadSpec): NativeHead? = null
        override fun loraTrain(
            head: NativeHead,
            desc: ModelDescriptor,
            samples: List<LoraSample>,
            config: LoraConfig,
            opts: LoraTrainOpts,
            progress: com.t1dm.core.model.LoraProgressSink?,
        ): LoraTrainResult = unused()
        override fun loraGuard(
            head: NativeHead,
            desc: ModelDescriptor,
            samples: List<LoraSample>,
            weights: com.t1dm.core.model.LoraWeights,
            opts: com.t1dm.core.model.LoraGuardOpts,
        ): com.t1dm.core.model.LoraGuardReport = unused()
        override fun loraGuardOptsFit(): com.t1dm.core.model.LoraGuardOpts = unused()
        override fun loraNew(
            config: LoraConfig,
            headSha256: String,
            dModel: Int,
            hidden: Int,
            outDim: Int,
            seed: Long,
        ): LoraWeights = unused()
        override fun loraSerialize(w: LoraWeights): ByteArray = unused()
        override fun loraDeserialize(bytes: ByteArray): LoraWeights? = null
        override fun denormalizeSample(desc: ModelDescriptor, z: List<Double>): List<Double> = unused()
        override fun decodeTime(timeLogits: List<Double>, nBins: Int, binHours: Double): PredictedTime? = unused()
        override fun gamma(total: Double, k: Double, theta: Double, durMin: Double): List<Double> = unused()
        override fun bateman(total: Double, durMin: Double, ka: Double, ke: Double): List<Double> = unused()
        override fun expActionCurve(total: Double, peakMin: Double, diaMin: Double): List<Double> = unused()
        override fun insulinPresetCatalog(): List<InsulinPresetSpec> = unused()
        override fun extendBasal(schedule: BasalSchedule, fromMs: Long, toMs: Long): List<CurveEvent> = unused()
        override fun advancedStats(samples: List<StatSample>, targetLow: Int, targetHigh: Int, agpBins: Int): AdvancedStats = unused()
        override fun clinicalCuts(): ClinicalCuts = unused()
        override fun forecastMetricsSuite(
            windows: List<ForecastWindow>,
            horizonsMin: List<Int>,
            config: MetricsConfig,
            includeCgEga: Boolean,
        ): MetricsSuite = unused()
        override fun clarkeZoneGrid(truthAxisMgdl: List<Double>, predAxisMgdl: List<Double>): List<ClarkeZone> = unused()
        override fun dtsZoneGrid(truthAxisMgdl: List<Double>, predAxisMgdl: List<Double>): List<DtsZone> = unused()
        override fun trendBinEdges(): List<Double> = unused()
        override fun conformalMinCalWindows(): Int = unused()
        override fun fitQuantileConformal(windows: List<ForecastWindow>, minCalWindows: Int): ConformalFit = unused()
        // Not `unused()`: the roll reads the RAW fan, and `null` is every caller's fallback to it.
        override fun applyQuantileConformal(bandsMgdl: List<Double>, delta: List<Double>): List<Double>? = null
        override fun applyQuantileConformalBatch(fansMgdl: List<Double>, delta: List<Double>): List<Double>? = null
        override fun defaultCarTuning(): CarTuning = unused()
        override fun createGameWorld(terrain: TerrainSpec, tuning: CarTuning, obstacles: List<Obstacle>): GameWorld = unused()
        override fun defaultGolfTuning(): GolfTuning = unused()
        override fun createGolfWorld(terrain: TerrainSpec, tuning: GolfTuning, obstacles: List<Obstacle>): GolfWorld = unused()
    }
}

/** Ascending-τ, ASYMMETRIC about the median (col 3): up offsets [2,4,6], down offsets [1,2,3]. */
private val RISK_ROW = listOf(0.0, 1.0, 2.0, 3.0, 5.0, 7.0, 9.0)
