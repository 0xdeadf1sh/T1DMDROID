package com.t1dm.calc

import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.core.common.GameWorld
import com.t1dm.core.common.NativeCore
import com.t1dm.core.model.BaselineFit
import com.t1dm.core.model.BaselineForecast
import com.t1dm.core.model.BaselineModel
import com.t1dm.core.model.BaselineSpec
import com.t1dm.core.model.ClinicalCuts
import com.t1dm.core.model.*
import com.t1dm.data.curve.ChannelBuilder
import com.t1dm.data.curve.CurveEngine
import com.t1dm.data.curve.DoseStore
import com.t1dm.inference.BgHistoryProvider
import com.t1dm.inference.BgSeries
import com.t1dm.inference.backend.GraphInput
import com.t1dm.inference.backend.GraphOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the §4-#4 prediction-zone-origin fix (`RollingForecaster.rollInternal`): the roll must origin
 * its CONTEXT at `series.gridStartMs` and its FUTURE at the grid boundary one step past the last
 * context sample (`predZoneStartMs == gridStartMs + nCtx·STEP`, matching the reference cycle path in
 * `InferenceController`), re-anchoring the scored candidate by that same shift. Before the fix the
 * future origin was the raw `nowMs` and the candidate stayed at `nowMs`; because `nowMs` lies in the
 * current incomplete bucket, moving the origin without re-anchoring rounds the candidate's leading
 * step to `idx < 0` (dropped by `bucketize`), under-counting its lowering effect — a fail-OPEN
 * regression that would admit a larger bolus.
 *
 * The three assertions guard the three coupled parts of the fix; each fails against a distinct
 * pre-fix state, so reverting any one regresses this test:
 *  1. the FUTURE dose channel is bucketized on grid origin `G + n·STEP`, never the raw `nowMs`;
 *  2. a candidate of `U` U resolved at `nowMs` lands in future bucket 0 with `Σ ≈ U` (the re-anchor);
 *  3. with four trailing interpolated readings (anchor at `G + (n−4)·STEP`), a context insulin event
 *     at absolute `G + 2·STEP` lands at CONTEXT grid index 2 (origin `gridStartMs`, not `anchor`).
 *
 * The recording [RecordingNativeCore] captures the exact `gridStartMs` every `bucketize` is invoked
 * with and the `insulin`/`announcedInsulin` channels every `buildContext` receives, so the assertions
 * read the origins and the resolved channels the real path actually built.
 */
class RollingForecasterAlignmentTest {

    // 5-min grid; G is grid-aligned so every event sits on an exact bucket boundary.
    private val g = 1_500_000_000_000L
    private val nCtx = 48                       // 8 patches of 6; a valid context length for the descriptor
    private val predZoneStartMs = g + nCtx * STEP_MS            // == series.gridStartMs + nCtx·STEP
    private val lastBucketStartMs = predZoneStartMs - STEP_MS   // the current (incomplete) bucket [·, predZone)
    private val nowMs = lastBucketStartMs + STEP_MS / 4         // inside the last bucket's FIRST half

    private val descriptor = ModelDescriptor(
        bg = ChannelStat(0.0, 1.0),
        carb = ChannelStat(0.0, 1.0),
        insulin = ChannelStat(0.0, 1.0),
        ropeBase = 1000,
        medianGlobalDim = 6,
        stepBasisType = "dct",
        quantileSpreadMin = 1e-3,
        negFill = -30000.0,
        predictionHorizonHours = 2,   // ⇒ predPatches = 2·12/6 = 4, predSteps = 24
        maxContextPatches = 8,        // maxSteps = 48
        minContextPatches = 4,        // minSteps = 24
        patchSize = 6,
        nInputFeatures = 3,
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
        // A single-step insulin context event on grid index 2 (absolute G + 2·STEP).
        val ctxInsulinEvent = CurveEvent(g + 2 * STEP_MS, STEP_MS, CurveKind.INSULIN, 2.5, listOf(2.5))
        // The scored candidate, resolved at the raw nowMs inside the current incomplete bucket.
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
                // Four trailing interpolated readings: the last MEASURED anchor is 4 steps before the
                // last grid slot, so anchorTsMs != gridStartMs + (nCtx-1)·STEP and the pre-fix
                // context origin (anchor - (nCtx-1)·STEP) would differ from gridStartMs.
                BgSeries(DoubleArray(nCtx) { 120.0 }, anchorTsMs = g + (nCtx - 4) * STEP_MS, gridStartMs = g)
        }
        val model = object : SelectedModelHandle {
            override val descriptor = this@RollingForecasterAlignmentTest.descriptor
            override val backendInfo = fp32Backend()
            override suspend fun run(input: GraphInput): GraphOutput = GraphOutput(FloatArray(4 * 6 * 7))
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

        // The whole recording path ran (context + future built, model forward + decode + degeneracy).
        assertTrue("the roll must complete and be eligible over the fakes", fan.eligible)
        assertTrue("buildContext must have been invoked", native.buildContextCalls.isNotEmpty())

        // (1) Future origin == G + n·STEP, context origin == gridStartMs, and NEITHER is the raw nowMs.
        val futureCall = native.bucketizeCalls.first { it.kind == CurveKind.INSULIN && it.nSteps == request.fullRollSteps }
        val contextCall = native.bucketizeCalls.first { it.kind == CurveKind.INSULIN && it.nSteps == nCtx }
        assertEquals("future dose channel origin must be G + n·STEP (== InferenceController's predZone)", predZoneStartMs, futureCall.gridStartMs)
        assertEquals("context dose channel origin must be series.gridStartMs", g, contextCall.gridStartMs)
        assertFalse(
            "no bucketize may originate at the raw non-grid nowMs (the pre-fix future origin)",
            native.bucketizeCalls.any { it.gridStartMs == nowMs },
        )

        val built = native.buildContextCalls.first()

        // (2) The candidate resolved at nowMs, re-anchored by candShift, lands in future bucket 0 and
        // integrates to its full U. Without the re-anchor its leading step rounds to idx<0 (dropped)
        // and announcedInsulin[0] would be 0 — the fail-open under-count.
        val announcedInsulin = built.announcedInsulin!!
        assertTrue("re-anchored candidate must occupy future bucket 0", announcedInsulin[0] > 0.0)
        assertEquals("re-anchored candidate must integrate to its full U", candidateU, announcedInsulin.sum(), 1e-9)

        // (3) The context insulin event at absolute G + 2·STEP lands at context grid index 2 (origin
        // gridStartMs). The pre-fix origin (anchor - (nCtx-1)·STEP == G - 3·STEP) would place it at
        // index 5, so index 5 must be empty.
        val contextInsulin = built.insulin
        assertEquals("context insulin event must land at grid index 2", 2.5, contextInsulin[2], 1e-9)
        assertEquals("context insulin event must NOT land at the pre-fix index 5", 0.0, contextInsulin.getOrElse(5) { 0.0 }, 1e-9)
    }

    /**
     * The DOSING path must build its context at the user's BG smoothing window, not the default: the
     * window shifts `last_bg`, so a calculator left on 7 while the display cycle ran at another would
     * anchor its whole recommendation on a different number from the forecast drawn beside it. Also
     * pins the snap-to-detent — an even/garbage persisted value would be rejected outright by the
     * Rust model-input guard mid-roll.
     */
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
            }
            val model = object : SelectedModelHandle {
                override val descriptor = this@RollingForecasterAlignmentTest.descriptor
                override val backendInfo = fp32Backend()
                override suspend fun run(input: GraphInput): GraphOutput = GraphOutput(FloatArray(4 * 6 * 7))
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

    /**
     * A [NativeCore] that records the `bucketize` grid origins and the `buildContext` channels the roll
     * builds, while giving `bucketize`/`buildContext`/`assembleDecode`/`forecastDegeneracyCheck` just
     * enough real behaviour for one clean roll. `bucketize` mirrors the Rust rule the fix depends on:
     * a step whose absolute time maps to `idx < 0` is DROPPED. Every other method is unused on this path.
     */
    private class RecordingNativeCore : NativeCore {
        data class BucketizeCall(val gridStartMs: Long, val nSteps: Int, val kind: CurveKind)
        data class BuildContextCall(
            val bg: List<Double>,
            val carb: List<Double>,
            val insulin: List<Double>,
            val announcedCarb: List<Double>?,
            val announcedInsulin: List<Double>?,
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
                    val idx = Math.floorDiv(absMs - gridStartMs, STEP_MS).toInt()   // idx<0 dropped, per curve.rs
                    if (idx in 0 until nSteps) out[idx] += ev.values[j]
                }
            }
            return out.toList()
        }

        override fun onBoard(events: List<CurveEvent>, atMs: Long, kind: CurveKind): Double = 0.0

        override fun buildContext(
            desc: ModelDescriptor,
            bg: List<Double>,
            carb: List<Double>,
            insulin: List<Double>,
            announcedCarb: List<Double>?,
            announcedInsulin: List<Double>?,
            smoothingWindow: Int,
        ): BuiltContext {
            buildContextCalls += BuildContextCall(bg, carb, insulin, announcedCarb, announcedInsulin, smoothingWindow)
            val patches = bg.size / desc.patchSize
            val predPatches = desc.predictionHorizonHours * 12 / desc.patchSize
            return BuiltContext(
                nCtx = patches,
                predictionPatches = predPatches,
                context = List(patches * desc.patchSize * desc.nInputFeatures) { 0.0 },
                pred = List(predPatches * desc.patchSize * desc.nInputFeatures) { 0.0 },
                lastBg = bg.lastOrNull() ?: 120.0,
            )
        }

        override fun assembleDecode(desc: ModelDescriptor, headRaw: List<Double>, lastBg: Double, carrySpread: Double): Forecast {
            val steps = 24
            val nq = 7
            return Forecast(
                medianRisk = List(steps) { 0.0 },
                qTauRisk = List(steps * nq) { (it % nq).toDouble() },
                medianBg = List(steps) { 120.0 },
                bandsMgdl = List(steps * nq) { 110.0 + (it % nq) * 2.0 },
            )
        }

        override fun forecastDegeneracyCheck(desc: ModelDescriptor, forecast: Forecast): ForecastStatus = ForecastStatus.OK

        // ── unused on the RollingForecaster roll path ───────────────────────────────────────────
        private fun unused(): Nothing = error("unused by RollingForecasterAlignmentTest")
        override fun roundtrip(msg: String): String = unused()
        override fun decodeAdvert(payload: ByteArray): DecodedAdvert? = unused()
        override fun advertCrc32(payload: ByteArray): Long = unused()
        override fun kovatchevF(mgdl: Double): Double = unused()
        override fun kovatchevFInv(risk: Double): Double = unused()
        override fun parseDescriptor(json: String): ModelDescriptor? = unused()
        override fun causalSmooth(series: List<Double>, clampMin: Double?, clampMax: Double?, window: Int): List<Double> = unused()
        override fun normalizeSample(desc: ModelDescriptor, bg: Double, carb: Double, insulin: Double): List<Double> = unused()
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
        override fun conformalMinCalWindows(): Int = unused()
        override fun fitQuantileConformal(windows: List<ForecastWindow>, minCalWindows: Int): ConformalFit = unused()
        override fun baselineDefaultSpec(): BaselineSpec = unused()
        override fun fitBaselineRidge(
            bgMgdl: List<Double>,
            gridStartMs: Long,
            events: List<CurveEvent>,
            spec: BaselineSpec,
            nowMs: Long,
            minCalWindows: Int,
        ): BaselineFit? = unused()
        override fun baselinePredict(
            model: BaselineModel,
            bgTail: List<Double>,
            iob: Double,
            cob: Double,
        ): BaselineForecast? = unused()
        override fun baselineOnBoardAt(events: List<CurveEvent>, atMs: Long, kind: CurveKind): Double = unused()
        override fun baselineDegeneracyCheck(forecast: BaselineForecast): ForecastStatus = unused()
        // Not `unused()`: the rolling forecaster reads the RAW fan by construction, and the day one
        // of its rails reaches for a calibrated one this must FAIL the alignment rather than throw
        // somewhere unrelated. `null` is what every caller falls back to — the raw fan.
        override fun applyQuantileConformal(bandsMgdl: List<Double>, delta: List<Double>): List<Double>? = null
        override fun defaultCarTuning(): CarTuning = unused()
        override fun createGameWorld(terrain: TerrainSpec, tuning: CarTuning): GameWorld = unused()
    }
}
