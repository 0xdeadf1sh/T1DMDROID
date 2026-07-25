package com.t1dm.core.nativecore

import com.t1dm.core.common.GameWorld
import com.t1dm.core.common.NativeCore
import com.t1dm.core.model.AccuracyPair
import com.t1dm.core.model.CarTuning
import com.t1dm.core.model.TerrainSpec
import com.t1dm.core.model.AccuracyReport
import com.t1dm.core.model.HorizonAccuracy
import com.t1dm.core.model.AdvancedStats
import com.t1dm.core.model.BasalSchedule
import com.t1dm.core.model.BuiltContext
import com.t1dm.core.model.StatSample
import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.DecodedAdvert
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

/**
 * TEMPORARY pure-Kotlin stand-in so the app runs end-to-end while the Rust cross-build is
 * blocked (no NDK / no aarch64 Rust std). The host crate already proves `roundtrip` under
 * `cargo test`.
 *
 * TODO(native-agent): replace with the uniffi-generated binding calling t1dm-core.
 */
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

    // ── Model pre/post pipeline (Phase 2) — real path is [UniffiNativeCore] ──────────

    override fun parseDescriptor(json: String): ModelDescriptor? =
        TODO("Phase 2: native parse_descriptor")

    override fun causalSmooth(series: List<Double>, clampMin: Double?, clampMax: Double?, window: Int): List<Double> =
        TODO("Phase 2: native causal_smooth")

    override fun normalizeSample(desc: ModelDescriptor, bg: Double, carb: Double, insulin: Double): List<Double> =
        TODO("Phase 2: native normalize_sample")

    override fun denormalizeSample(desc: ModelDescriptor, z: List<Double>): List<Double> =
        TODO("Phase 2: native denormalize_sample")

    override fun buildContext(
        desc: ModelDescriptor,
        bg: List<Double>,
        carb: List<Double>,
        insulin: List<Double>,
        announcedCarb: List<Double>?,
        announcedInsulin: List<Double>?,
        smoothingWindow: Int,
    ): BuiltContext = TODO("Phase 2: native build_context")

    override fun assembleDecode(
        desc: ModelDescriptor,
        headRaw: List<Double>,
        lastBg: Double,
        carrySpread: Double,
    ): Forecast = TODO("Phase 2: native assemble_decode")

    override fun forecastDegeneracyCheck(desc: ModelDescriptor, forecast: Forecast): ForecastStatus =
        TODO("Phase 2: native forecast_degeneracy_check")

    // The time-probe decode lives in Rust (golden-gated); the host stub has no .so, so it
    // fails OPEN to null — the predicted hour is optional and never blocks the BG path.
    override fun decodeTime(timeLogits: List<Double>, nBins: Int, binHours: Double): PredictedTime? = null

    // ── Shared curve/PK engine (Phase 4) ────────────────────────────────────────────
    // Unlike the pre/post pipeline (which needs the model), the curve math is pure and
    // deterministic, so the stub carries a faithful Kotlin port of t1dm-core::curve. This
    // keeps host-only builds (and JVM unit tests) exercising the real curve shapes while
    // the NDK cross-build is unavailable. The Rust remains the numeric authority; this
    // mirror is golden-checked against it in the crate's tests and against simulator.py.

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
        // Host mirror of the Loop/OpenAPS exponential activity model (golden-checked against Rust).
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
        // Host mirror of the Rust `insulin_preset_catalog()` (issue 19); values/citations transcribed.
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

    // ── Advanced stats (Phase 6) ────────────────────────────────────────────────────
    // The stats math lives entirely in Rust (golden-gated); the host stub cannot reproduce it
    // without the .so, so it yields the fail-closed empty block. The real path is [UniffiNativeCore];
    // JVM unit tests that need real numbers drive the Rust crate directly (cargo) or a canned fake.
    override fun advancedStats(
        samples: List<StatSample>,
        targetLow: Int,
        targetHigh: Int,
        agpBins: Int,
    ): AdvancedStats = AdvancedStats.EMPTY

    // ── Forecast accuracy (Phase 7C) ────────────────────────────────────────────────
    // Unlike the stats block, the accuracy reduction is trivial arithmetic (no golden-gated
    // Rust numerics), so the host stub reproduces it faithfully — this keeps the data-layer
    // pairing + the drill-down host-testable without the .so. Bit-identical to
    // `t1dm-core::accuracy::accuracy_at_horizons`.
    override fun accuracyAtHorizons(pairs: List<AccuracyPair>, minSamples: Int): AccuracyReport {
        val byHorizon = pairs
            .filter { it.predicted.isFinite() && it.realized.isFinite() }
            .groupBy { it.horizonMin }
        val horizons = byHorizon.toSortedMap().map { (h, rows) ->
            val n = rows.size
            var sq = 0.0; var abs = 0.0; var ard = 0.0; var covHits = 0.0; var covN = 0
            for (r in rows) {
                val e = r.predicted - r.realized
                sq += e * e; abs += kotlin.math.abs(e)
                ard += kotlin.math.abs(e) / kotlin.math.max(r.realized, 1.0)
                if (r.hasBand) { covN++; if (r.realized in r.bandLo..r.bandHi) covHits += 1.0 }
            }
            HorizonAccuracy(
                horizonMin = h,
                n = n,
                rmse = kotlin.math.sqrt(sq / n),
                mae = abs / n,
                mard = 100.0 * ard / n,
                coverage90 = if (covN > 0) covHits / covN else null,
                sufficient = n >= minSamples,
            )
        }
        return AccuracyReport(horizons, byHorizon.values.sumOf { it.size }, minSamples)
    }

    // ── Hill-climb minigame physics ─────────────────────────────────────────────────
    // The solver is Rust-only by design (a per-frame path with zero allocation is the whole
    // reason it is not Kotlin), and unlike the curve engine there is no host consumer that
    // needs it — the minigame only ever runs on device. A stub car would be a second physics
    // implementation to keep in sync for no benefit, so this stays unimplemented.

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
