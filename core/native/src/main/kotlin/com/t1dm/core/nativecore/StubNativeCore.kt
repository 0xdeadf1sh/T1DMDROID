package com.t1dm.core.nativecore

import com.t1dm.core.common.GameWorld
import com.t1dm.core.common.NativeCore
import com.t1dm.core.model.LoraGuardOpts
import com.t1dm.core.model.LoraGuardReport
import com.t1dm.core.model.BaselineFit
import com.t1dm.core.model.BaselineForecast
import com.t1dm.core.model.BaselineModel
import com.t1dm.core.model.BaselineSpec
import com.t1dm.core.model.CarTuning
import com.t1dm.core.model.ClarkeZone
import com.t1dm.core.model.DtsZone
import com.t1dm.core.model.ConformalFit
import com.t1dm.core.model.TerrainSpec
import com.t1dm.core.model.AdvancedStats
import com.t1dm.core.model.ClinicalCuts
import com.t1dm.core.model.BasalSchedule
import com.t1dm.core.common.NativeHead
import com.t1dm.core.model.GapRun
import com.t1dm.core.model.GraphInput
import com.t1dm.core.model.HeadSpec
import com.t1dm.core.model.LoraConfig
import com.t1dm.core.model.LoraProgressSink
import com.t1dm.core.model.LoraSample
import com.t1dm.core.model.LoraTrainOpts
import com.t1dm.core.model.LoraTrainResult
import com.t1dm.core.model.LoraWeights
import com.t1dm.core.model.MaskSpan
import com.t1dm.core.model.SynthParams
import com.t1dm.core.model.SynthSeries
import com.t1dm.core.model.StatSample
import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.DecodedAdvert
import com.t1dm.core.model.ForecastWindow
import com.t1dm.core.model.MetricsConfig
import com.t1dm.core.model.MetricsSuite
import com.t1dm.core.model.InsulinFamily
import com.t1dm.core.model.InsulinPresetSpec
import kotlin.math.ln
import com.t1dm.core.model.Forecast
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.core.model.PredictedTime
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToLong

/** Pure-Kotlin stand-in for host-only builds, where there is no .so to load. */
class StubNativeCore : NativeCore {
    override fun roundtrip(msg: String): String = "t1dm-core(stub):$msg"

    override fun decodeAdvert(payload: ByteArray): DecodedAdvert? =
        TODO("Phase 1: native decode_advert")

    override fun advertCrc32(payload: ByteArray): Long =
        TODO("Phase 1: native advert_crc32")

    override fun kovatchevF(mgdl: Double): Double =
        TODO("Phase 1: native kovatchev_f")

    override fun kovatchevFInv(risk: Double): Double =
        TODO("Phase 1: native kovatchev_f_inv")

    override fun parseDescriptor(json: String): ModelDescriptor? =
        TODO("Phase 2: native parse_descriptor")

    override fun causalSmooth(series: List<Double>, clampMin: Double?, clampMax: Double?, window: Int): List<Double> =
        TODO("Phase 2: native causal_smooth")

    override fun normalizeSample(
        desc: ModelDescriptor,
        bg: Double,
        carb: Double,
        insulin: Double,
        exercise: Double,
    ): List<Double> = TODO("Phase 2: native normalize_sample")

    override fun denormalizeSample(desc: ModelDescriptor, z: List<Double>): List<Double> =
        TODO("Phase 2: native denormalize_sample")

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
    ): GraphInput = TODO("Phase 2: native build_graph_input")

    override fun assembleDecode(
        desc: ModelDescriptor,
        headRaw: List<Double>,
        anchors: List<Double>,
        slotPatch: List<Int>,
        nMasked: Int,
        carrySpread: List<Double>,
    ): Forecast = TODO("Phase 2: native assemble_decode")

    override fun stepStates(
        desc: ModelDescriptor,
        hidden: List<Float>,
        slotPatch: List<Int>,
        attnMask: List<Float>,
    ): List<Double> = TODO("Phase 2: native step_states")

    override fun forecastSlice(f: Forecast, fromPatch: Int, toPatch: Int): Forecast =
        TODO("Phase 2: native forecast_slice")

    override fun bandLine(desc: ModelDescriptor, f: Forecast, tau: Double): List<Double> =
        TODO("Phase 2: native band_line")

    override fun bandLineAt(desc: ModelDescriptor, qTauRisk: List<Double>, tau: Double): List<Double> =
        TODO("Phase 2: native band_line_at")

    // No host-side head; a plausible one would be worse than none.
    override fun headOpen(bytes: ByteArray, spec: HeadSpec): NativeHead? = null

    override fun loraTrain(
        head: NativeHead,
        desc: ModelDescriptor,
        samples: List<LoraSample>,
        config: LoraConfig,
        opts: LoraTrainOpts,
        progress: LoraProgressSink?,
    ): LoraTrainResult = TODO("Phase 2: native lora_train")

    override fun loraGuard(
        head: NativeHead,
        desc: ModelDescriptor,
        samples: List<LoraSample>,
        weights: LoraWeights,
        opts: LoraGuardOpts,
    ): LoraGuardReport = TODO("Phase 2: native lora_guard")

    override fun loraGuardOptsFit(): LoraGuardOpts = TODO("Phase 2: native lora_guard_opts_fit")

    override fun loraNew(
        config: LoraConfig,
        headSha256: String,
        dModel: Int,
        hidden: Int,
        outDim: Int,
        seed: Long,
    ): LoraWeights = TODO("Phase 2: native lora_new")

    override fun loraSerialize(w: LoraWeights): ByteArray = TODO("Phase 2: native lora_serialize")

    override fun loraDeserialize(bytes: ByteArray): LoraWeights? = null

    override fun synthDefaultParams(): SynthParams = TODO("Phase 2: native synth_default_params")

    override fun synthSeries(
        nSteps: Int,
        startHourOfDay: Double,
        params: SynthParams,
        seed: Long,
    ): SynthSeries = TODO("Phase 2: native synth_series")

    override fun synthFillGaps(
        realBg: List<Double>,
        realCarb: List<Double>,
        realInsulin: List<Double>,
        realExercise: List<Double>,
        synth: SynthSeries,
    ): SynthSeries = TODO("Phase 2: native synth_fill_gaps")

    override fun findGaps(bg: List<Double>, minSteps: Int): List<GapRun> = emptyList()

    override fun forecastDegeneracyCheck(desc: ModelDescriptor, forecast: Forecast): ForecastStatus =
        TODO("Phase 2: native forecast_degeneracy_check")

    // Fails OPEN: the predicted hour is optional and never blocks the BG path.
    override fun decodeTime(timeLogits: List<Double>, nBins: Int, binHours: Double): PredictedTime? = null

    // The curve math is pure, so the stub ports `t1dm-core::curve`. The Rust stays the numeric
    // authority; this mirror is golden-checked against it and against simulator.py.

    override fun gamma(total: Double, k: Double, theta: Double, durMin: Double): List<Double> {
        val n = (durMin / DT_MIN).toInt()
        if (n <= 0) return listOf(0.0)
        val v = DoubleArray(n)
        var area = 0.0
        for (i in 0 until n) {
            val t = (i + 1) * DT_MIN
            val x = t.pow(k - 1.0) * exp(-t / theta)
            v[i] = x
            area += x
        }
        if (area > 0.0) for (i in 0 until n) v[i] *= total / area
        return v.asList()
    }

    override fun bateman(total: Double, durMin: Double, ka: Double, ke: Double): List<Double> {
        val n = (durMin / DT_MIN).toInt()
        if (n <= 0) return listOf(0.0)
        val kaEff = maxOf(ka, ke + 1e-3)
        val curve = DoubleArray(n)
        for (i in 0 until n) {
            val th = i * (DT_MIN / 60.0)
            curve[i] = maxOf(0.0, exp(-ke * th) - exp(-kaEff * th))
        }
        val tail = (BASAL_TAIL_CLIP_HOURS * 60.0 / DT_MIN).toInt()
        if (tail in 1 until n) {
            val start = n - tail
            for (j in 0 until tail) {
                val s = when {
                    tail == 1 -> 1.0
                    j == tail - 1 -> 0.0
                    else -> 1.0 - j.toDouble() / (tail - 1)
                }
                curve[start + j] *= s * s * s * (s * (s * 6.0 - 15.0) + 10.0)
            }
        }
        val area = curve.sum()
        if (area > 0.0) for (i in 0 until n) curve[i] *= total / area
        return curve.asList()
    }

    override fun expActionCurve(total: Double, peakMin: Double, diaMin: Double): List<Double> {
        // Loop/OpenAPS exponential activity model.
        val n = (diaMin / DT_MIN).toInt()
        if (n <= 0 || peakMin <= 0.0 || peakMin >= diaMin / 2.0) return listOf(0.0)
        val tp = peakMin
        val td = diaMin
        val tau = tp * (1.0 - tp / td) / (1.0 - 2.0 * tp / td)
        val a = 2.0 * tau / td
        val s = 1.0 / (1.0 - a + (1.0 + a) * exp(-td / tau))
        val v = DoubleArray(n)
        var area = 0.0
        for (i in 0 until n) {
            val t = (i + 1) * DT_MIN
            val ia = maxOf(0.0, (s / (tau * tau)) * t * (1.0 - t / td) * exp(-t / tau))
            v[i] = ia
            area += ia
        }
        if (area > 0.0) for (i in 0 until n) v[i] *= total / area
        return v.asList()
    }

    override fun insulinPresetCatalog(): List<InsulinPresetSpec> {
        // Values and citations transcribed from the Rust `insulin_preset_catalog()`.
        fun rapid(label: String, peak: Double, dia: Double, cite: String) =
            InsulinPresetSpec(InsulinFamily.RapidExp, label, peak, dia, 0.0, 0.0, true, cite)
        fun basal(label: String, diaH: Double, ka: Double, ke: Double, cite: String) =
            InsulinPresetSpec(
                InsulinFamily.BasalBateman, label,
                (ln(ka) - ln(ke)) / (ka - ke) * 60.0, diaH * 60.0, ka, ke, true, cite,
            )
        return listOf(
            rapid("Aspart · NovoRapid/Novolog", 75.0, 360.0, "Loop/OpenAPS rapid-acting adult exponential: peak 75 min, DIA 6 h"),
            rapid("Faster aspart · Fiasp", 55.0, 360.0, "Loop `.fiasp` exponential preset: peak 55 min, DIA 6 h"),
            rapid("Lispro · Humalog", 75.0, 360.0, "Loop/OpenAPS rapid-acting adult exponential: peak 75 min, DIA 6 h"),
            rapid("Ultra-rapid lispro · Lyumjev", 45.0, 300.0, "Ultra-rapid class (Fiasp-like); Bionic Wookiee 2022 peak ≈45 min, DIA 5 h"),
            basal("Glargine U100 · Lantus", 24.0, 0.30, 0.07, "Glargine U100 duration ~24 h (Healio ultra-long-acting review)"),
            basal("Glargine U300 · Toujeo", 36.0, 0.18, 0.05, "Glargine U300 duration ~36 h, flatter GIR than U100 (Healio review)"),
            basal("Degludec · Tresiba", 42.0, 0.12, 0.04, "Degludec duration ~42 h, flat profile, t½ >25 h (Healio review)"),
        )
    }

    override fun bucketize(
        events: List<CurveEvent>,
        gridStartMs: Long,
        nSteps: Int,
        kind: CurveKind,
    ): List<Double> {
        require(nSteps >= 0) { "bucketize n_steps must be >= 0, got $nSteps" }
        val grid = DoubleArray(nSteps)
        for (ev in events) {
            if (ev.kind != kind) continue
            val offset = ((ev.startMs - gridStartMs).toDouble() / STEP_MS).roundToLong()
            for (j in ev.values.indices) {
                val idx = offset + j
                if (idx in 0 until nSteps.toLong()) grid[idx.toInt()] += ev.values[j]
            }
        }
        return grid.asList()
    }

    override fun onBoard(events: List<CurveEvent>, atMs: Long, kind: CurveKind): Double {
        var acc = 0.0
        for (ev in events) {
            if (ev.kind != kind) continue
            for (j in ev.values.indices) {
                if (ev.startMs + j * ev.stepMs >= atMs) acc += ev.values[j]
            }
        }
        return acc
    }

    override fun extendBasal(schedule: BasalSchedule, fromMs: Long, toMs: Long): List<CurveEvent> {
        require(toMs >= fromMs) { "extend_basal window inverted: from $fromMs > to $toMs" }
        val tzMs = schedule.tzOffsetMin.toLong() * MIN_MS
        val out = ArrayList<CurveEvent>()
        for (dose in schedule.doses) {
            if (dose.durationMin <= 0.0 || dose.doseU == 0.0) continue
            val diaMs = (dose.durationMin * MIN_MS).toLong()
            val todMs = dose.timeOfDayMin.toLong() * MIN_MS
            val firstDay = Math.floorDiv(fromMs - diaMs + tzMs, DAY_MS)
            val lastDay = Math.floorDiv(toMs + tzMs, DAY_MS)
            val values = bateman(dose.doseU, dose.durationMin, dose.kaPerHour, dose.kePerHour)
            var day = firstDay
            while (day <= lastDay) {
                val startAbs = day * DAY_MS + todMs - tzMs
                if (startAbs + diaMs > fromMs && startAbs < toMs) {
                    out.add(CurveEvent(startAbs, STEP_MS, CurveKind.INSULIN, dose.doseU, values))
                }
                day++
            }
        }
        out.sortBy { it.startMs }
        return out
    }

    // Fail-closed empty block: the stats math is Rust-only.
    override fun advancedStats(
        samples: List<StatSample>,
        targetLow: Int,
        targetHigh: Int,
        agpBins: Int,
    ): AdvancedStats = AdvancedStats.EMPTY

    // Refuses rather than repeat the crate's values; a caller that cannot anchor a scale draws none.
    override fun clinicalCuts(): ClinicalCuts = ClinicalCuts.UNAVAILABLE

    // Fail-closed empty suite: the metrics are pinned bit-for-bit to `T1DMAI`'s reference, and a
    // Kotlin reproduction would be a second copy free to drift from it.
    override fun forecastMetricsSuite(
        windows: List<ForecastWindow>,
        horizonsMin: List<Int>,
        config: MetricsConfig,
        includeCgEga: Boolean,
    ): MetricsSuite = MetricsSuite.EMPTY

    // Empty rather than a second copy of the zone boundaries; the figure then draws nothing.
    override fun clarkeZoneGrid(
        truthAxisMgdl: List<Double>,
        predAxisMgdl: List<Double>,
    ): List<ClarkeZone> = emptyList()

    override fun dtsZoneGrid(
        truthAxisMgdl: List<Double>,
        predAxisMgdl: List<Double>,
    ): List<DtsZone> = emptyList()

    // The edges are the crate's; an axis labelled from a guess would caption a binning nothing did.
    override fun trendBinEdges(): List<Double> = emptyList()

    // INFERENCE.md §8.4. Refuses: no fit, and the raw fan back from any apply.
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

    // The baseline is what the neural model is MEASURED against, so a second Kotlin numeric
    // authority would corrupt the comparison rather than one reading. Refuses throughout.
    override fun baselineDefaultSpec(): BaselineSpec =
        TODO("the baseline is Rust-only; use UniffiNativeCore")

    override fun fitBaselineRidge(
        bgMgdl: List<Double>,
        gridStartMs: Long,
        events: List<CurveEvent>,
        spec: BaselineSpec,
        nowMs: Long,
        minCalWindows: Int,
    ): BaselineFit? = null

    override fun baselinePredict(
        model: BaselineModel,
        bgTail: List<Double>,
        iob: Double,
        cob: Double,
        futureCarb: List<Double>,
        futureInsulin: List<Double>,
    ): BaselineForecast? = null

    override fun baselineOnBoardAt(events: List<CurveEvent>, atMs: Long, kind: CurveKind): Double = 0.0

    override fun baselineDegeneracyCheck(forecast: BaselineForecast): ForecastStatus =
        ForecastStatus.NON_FINITE

    // Rust-only by design: a zero-allocation per-frame path, and the minigame only runs on device.

    override fun defaultCarTuning(): CarTuning = TODO("game physics is Rust-only; use UniffiNativeCore")

    override fun createGameWorld(terrain: TerrainSpec, tuning: CarTuning): GameWorld =
        TODO("game physics is Rust-only; use UniffiNativeCore")

    private companion object {
        const val DT_MIN = 5.0
        const val STEP_MS = 300_000L
        const val MIN_MS = 60_000L
        const val DAY_MS = 86_400_000L
        const val BASAL_TAIL_CLIP_HOURS = 5.0
    }
}
