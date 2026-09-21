package com.t1dm.core.nativecore

import com.t1dm.core.common.GameWorld
import com.t1dm.core.common.GolfWorld
import com.t1dm.core.common.NativeCore
import com.t1dm.core.model.GolfTuning
import com.t1dm.core.model.LoraGuardOpts
import com.t1dm.core.model.LoraGuardReport
import com.t1dm.core.model.CarTuning
import com.t1dm.core.model.ClarkeZone
import com.t1dm.core.model.DtsZone
import com.t1dm.core.model.ConformalFit
import com.t1dm.core.model.Obstacle
import com.t1dm.core.model.TerrainSpec
import com.t1dm.core.model.AdvancedStats
import com.t1dm.core.model.ClinicalCuts
import com.t1dm.core.model.BasalSchedule
import com.t1dm.core.common.NativeHead
import com.t1dm.core.model.GraphInput
import com.t1dm.core.model.HeadSpec
import com.t1dm.core.model.LoraConfig
import com.t1dm.core.model.LoraProgressSink
import com.t1dm.core.model.LoraSample
import com.t1dm.core.model.LoraTrainOpts
import com.t1dm.core.model.LoraTrainResult
import com.t1dm.core.model.LoraWeights
import com.t1dm.core.model.MaskSpan
import com.t1dm.core.model.StatSample
import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.DecodedAdvert
import com.t1dm.core.model.ForecastWindow
import com.t1dm.core.model.MetricsConfig
import com.t1dm.core.model.MetricsSuite
import com.t1dm.core.model.BolusPk
import com.t1dm.core.model.InsulinFamily
import com.t1dm.core.model.InsulinPresetSpec
import com.t1dm.core.model.Forecast
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.core.model.PredictedTime
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

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

    override fun normalizeSample(desc: ModelDescriptor, bg: Double, carb: Double, insulin: Double): List<Double> =
        TODO("Phase 2: native normalize_sample")

    override fun denormalizeSample(desc: ModelDescriptor, z: List<Double>): List<Double> =
        TODO("Phase 2: native denormalize_sample")

    override fun buildGraphInput(
        desc: ModelDescriptor,
        bg: List<Double>,
        carb: List<Double>,
        insulin: List<Double>,
        announcedCarb: List<Double>?,
        announcedInsulin: List<Double>?,
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

    override fun forecastDegeneracyCheck(desc: ModelDescriptor, forecast: Forecast): ForecastStatus =
        TODO("Phase 2: native forecast_degeneracy_check")

    // Fails OPEN: the predicted hour is optional and never blocks the BG path.
    override fun decodeTime(timeLogits: List<Double>, nBins: Int, binHours: Double): PredictedTime? = null

    // Pure curve math: stub ports t1dm-core::curve; golden-checked against Rust and simulator.py.

    override fun gamma(total: Double, k: Double, theta: Double, durMin: Double): List<Double> {
        val n = (durMin / DT_MIN).toInt()
        if (n <= 0) return listOf(0.0)
        val h = DT_MIN / GAMMA_CURVE_SUBSTEPS
        val v = DoubleArray(n)
        for (i in 0 until n) {
            var acc = 0.0
            for (j in 0 until GAMMA_CURVE_SUBSTEPS) {
                val t = ((i * GAMMA_CURVE_SUBSTEPS + j) + 0.5) * h
                acc += t.pow(k - 1.0) * exp(-t / theta)
            }
            v[i] = acc / GAMMA_CURVE_SUBSTEPS
        }
        taperTail(v)
        val area = v.sum()
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
        taperTail(curve)
        val onset = (BASAL_ONSET_RAMP_HOURS * 60.0 / DT_MIN).toInt()
        if (onset in 1 until n) for (i in 0 until onset) curve[i] *= smootherstep(i.toDouble() / onset)
        val area = curve.sum()
        if (area > 0.0) for (i in 0 until n) curve[i] *= total / area
        return curve.asList()
    }

    private fun smootherstep(s: Double) = s * s * s * (s * (s * 6.0 - 15.0) + 10.0)

    private fun taperTail(curve: DoubleArray) {
        val n = curve.size
        val tail = (n * CURVE_TAIL_CLIP_FRACTION).toInt()
        if (tail !in 1 until n) return
        val start = n - tail
        for (j in 0 until tail) {
            val s = when {
                tail == 1 -> 1.0
                j == tail - 1 -> 0.0
                else -> 1.0 - j.toDouble() / (tail - 1)
            }
            curve[start + j] *= smootherstep(s)
        }
    }

    override fun bolusPkForDose(doseU: Double, k: Double, theta: Double, diaBaseHours: Double): BolusPk {
        val x = sqrt(maxOf(doseU, 0.5)) - sqrt(5.0)
        val durH = (diaBaseHours + BOLUS_DIA_DOSE_SCALE * x).coerceIn(BOLUS_DIA_MIN_HOURS, BOLUS_DIA_MAX_HOURS)
        return BolusPk(k, theta * (1.0 + BOLUS_THETA_DOSE_SLOPE * x), durH * 60.0)
    }

    override fun insulinPresetCatalog(): List<InsulinPresetSpec> {
        // Transcribed from the Rust `insulin_preset_catalog()`.
        fun rapid(label: String, k: Double, theta: Double, dia: Double, cite: String) =
            InsulinPresetSpec(InsulinFamily.RapidGamma, label, k, theta, dia, 0.0, 0.0, 0.0, cite)
        fun basal(label: String, ka: Double, ke: Double, actionH: Double) =
            InsulinPresetSpec(InsulinFamily.BasalBateman, label, 0.0, 0.0, 0.0, ka, ke, actionH * 60.0, "Label half-life")
        return listOf(
            rapid("Aspart · NovoRapid/Novolog", 3.0, 30.0, 4.0, "Simulator shape"),
            rapid("Faster aspart · Fiasp", 2.55, 35.0, 3.4, "Simulator shape"),
            rapid("Lispro · Humalog", 3.0, 30.0, 4.0, "Simulator shape"),
            rapid("Ultra-rapid lispro · Lyumjev", 2.55, 35.0, 3.4, "Simulator shape"),
            basal("Glargine U100 · Lantus", 0.477, 0.0499, 73.0),
            basal("Glargine U300 · Toujeo", 0.156, 0.0377, 101.0),
            basal("Degludec · Tresiba", 0.187, 0.0277, 133.0),
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

    // Refuses rather than repeat crate values; a caller with no scale anchor draws none.
    override fun clinicalCuts(): ClinicalCuts = ClinicalCuts.UNAVAILABLE

    // Fail-closed empty: metrics pin bit-for-bit to T1DMAI; a Kotlin copy would drift.
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

    // Rust-only by design: a zero-allocation per-frame path, and the minigame only runs on device.

    override fun defaultCarTuning(): CarTuning = TODO("game physics is Rust-only; use UniffiNativeCore")

    override fun createGameWorld(terrain: TerrainSpec, tuning: CarTuning, obstacles: List<Obstacle>): GameWorld =
        TODO("game physics is Rust-only; use UniffiNativeCore")

    override fun defaultGolfTuning(): GolfTuning = TODO("golf physics is Rust-only; use UniffiNativeCore")

    override fun createGolfWorld(terrain: TerrainSpec, tuning: GolfTuning, obstacles: List<Obstacle>): GolfWorld =
        TODO("golf physics is Rust-only; use UniffiNativeCore")

    private companion object {
        const val DT_MIN = 5.0
        const val STEP_MS = 300_000L
        const val MIN_MS = 60_000L
        const val DAY_MS = 86_400_000L
        const val CURVE_TAIL_CLIP_FRACTION = 1.0 / 6.0
        const val BASAL_ONSET_RAMP_HOURS = 3.0
        const val GAMMA_CURVE_SUBSTEPS = 16
        const val BOLUS_DIA_DOSE_SCALE = 0.6
        const val BOLUS_DIA_MIN_HOURS = 2.0
        const val BOLUS_DIA_MAX_HOURS = 7.5
        const val BOLUS_THETA_DOSE_SLOPE = 0.06
    }
}
