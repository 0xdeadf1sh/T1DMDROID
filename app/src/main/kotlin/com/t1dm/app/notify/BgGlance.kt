package com.t1dm.app.notify

import com.t1dm.cgm.GridStamper
import com.t1dm.core.model.AlarmFanEdges
import com.t1dm.core.model.AlertBand
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceTelemetry
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.isRealMeasurement
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.SensorArrow
import kotlin.math.roundToInt

/** mg/dL and minutes throughout; crossing fields non-null only for a §3.6-eligible forecast. */
data class BgGlance(
    val bgMgdl: Int?,
    val trendTenths: Int?,
    val readingAgeMs: Long,
    val band: AlertBand?,
    /** [directionOf]'s arrow, as the bottom bar and the watch draw it; null = none. */
    val trend: GlanceTrend?,
    /** Forecast slope, drives the watch fc_trend; never drawn as an arrow. */
    val fcTrend: GlanceTrend,
    /** §3.6-eligible — OK status, fresh anchor — and not in warmup. */
    val forecastEligible: Boolean,
    val forecastStatus: ForecastStatus?,
    /** Selected-model median at the horizon end. */
    val fcEndMgdl: Int?,
    val horizonSteps: Int,
    val warmup: Boolean,
    val signalLoss: Boolean,
    val stale: Boolean,
    /** No eligible forecast, excluding the warmup case. */
    val forecastUnavailable: Boolean,
    val predictedLowCrossing: Boolean,
    val predictedHighCrossing: Boolean,
    val alarmActive: Boolean,
    /** Both §6.1 edges out at the first step either is; [approaching] and [urgent] are null. */
    val unsure: Boolean,
    /** Earliest §6.1-edge crossing of any band. Null when ineligible, unsure, or in range. */
    val approaching: PredictiveCrossing?,
    /** Earliest predicted crossing of an urgent band. */
    val urgent: PredictiveCrossing?,
    /** At most 40 chars; reused verbatim by the watch push. */
    val summary: String,
) {
    val hasReading: Boolean get() = bgMgdl != null
}

enum class GlanceTrend { FLAT, RISING, FALLING, RISING_FAST, FALLING_FAST }

/** [reported] false ⇒ fitted by [fitTrendTenthsPerMin]: the sensor named no arrow and no rate. */
data class BgDirection(val trend: GlanceTrend, val reported: Boolean)

/** [sensor]'s arrow in [latest]'s grid slot, else [latest]'s rate, else a fit; UNDETERMINED fits */
suspend fun directionOf(
    latest: CgmReading?,
    sensor: CgmSourceTelemetry?,
    recent: suspend () -> List<CgmReading>,
): BgDirection? {
    if (latest == null) return null
    val own = sensor?.takeIf { GridStamper().snap(it.sampledAtMs) == latest.tsMs }?.arrow
    val ownTrend = own?.toGlanceTrend()
    val rate = latest.trendTenthsPerMin
    return when {
        ownTrend != null -> BgDirection(ownTrend, reported = true)
        own == null && rate != null -> BgGlanceComputer.measuredTrend(rate)?.let { BgDirection(it, reported = true) }
        else -> fitTrendTenthsPerMin(recent().filter { it.tsMs >= latest.tsMs - TREND_FIT_WINDOW_MS })
            ?.let { BgGlanceComputer.measuredTrend(it) }
            ?.let { BgDirection(it, reported = false) }
    }
}

private fun SensorArrow.toGlanceTrend(): GlanceTrend? = when (this) {
    SensorArrow.FALLING_FAST -> GlanceTrend.FALLING_FAST
    SensorArrow.FALLING -> GlanceTrend.FALLING
    SensorArrow.FLAT -> GlanceTrend.FLAT
    SensorArrow.RISING -> GlanceTrend.RISING
    SensorArrow.RISING_FAST -> GlanceTrend.RISING_FAST
    SensorArrow.UNDETERMINED -> null
}

const val TREND_FIT_WINDOW_MS = 15 * 60_000L

/** The 5-min grid points [TREND_FIT_WINDOW_MS] spans, newest inclusive. */
const val TREND_FIT_POINTS = 4

/** Least-squares slope over real measurements, in 0.1 mg/dL/min; null under two usable points. */
fun fitTrendTenthsPerMin(readings: List<CgmReading>): Int? {
    val pts = readings.mapNotNull { r ->
        if (!isRealMeasurement(r.provenance, r.flag)) null else r.bgMgdl?.let { r.tsMs to it }
    }
    if (pts.size < 2) return null
    val t0 = pts.first().first
    val xs = pts.map { (it.first - t0) / 60_000.0 }
    val ys = pts.map { it.second.toDouble() }
    val mx = xs.average()
    val my = ys.average()
    var num = 0.0
    var den = 0.0
    for (i in xs.indices) {
        val dx = xs[i] - mx
        num += dx * (ys[i] - my)
        den += dx * dx
    }
    return if (den <= 0.0) null else (num / den * 10.0).roundToInt()
}

/** CRITICAL is the urgent bands, WARNING is low/high. */
data class PredictiveCrossing(
    val kind: Kind,
    val severity: Severity,
    val etaMin: Int,
    val thresholdMgdl: Int,
    val projectedMgdl: Int,
) {
    enum class Kind { HYPO, HYPER }
    enum class Severity { WARNING, CRITICAL }
}

/** STABLE: §3.6-eligible, no predicted crossing. Every ineligible state is VOID, never STABLE. */
sealed interface GlyStatus {
    data object Stable : GlyStatus
    data object Unsure : GlyStatus
    /** [atMs]: wall time of the first forecast step out of range. */
    data class Excursion(val kind: PredictiveCrossing.Kind, val atMs: Long) : GlyStatus
    data class Void(val reason: String) : GlyStatus
}

sealed interface FanScan {
    data object Clear : FanScan
    data class Unsure(val step: Int) : FanScan
    /** [edgeMgdl]: the hypo edge for HYPO, the hyper edge for HYPER. */
    data class Out(val kind: PredictiveCrossing.Kind, val step: Int, val edgeMgdl: Double) : FanScan
}

/** One value per call site: a promoted reconstruction must not appear as the lock-screen BG. */
data class GlanceReadings private constructor(
    /** Newest row, whatever its provenance. */
    val latest: CgmReading?,
    /** Newest real measurement with a value; drives everything else. */
    val lastMeasured: CgmReading?,
) {
    companion object {
        /** [rows] newest-first, as every reading DAO returns them. */
        fun create(rows: List<CgmReading>): GlanceReadings = GlanceReadings(
            latest = rows.firstOrNull(),
            lastMeasured = rows.firstOrNull {
                isRealMeasurement(it.provenance, it.flag) && it.bgMgdl != null
            },
        )

        /** Unchecked: the caller must have proved [lastMeasured] is a measurement. */
        fun of(latest: CgmReading?, lastMeasured: CgmReading?): GlanceReadings =
            GlanceReadings(latest, lastMeasured)

        val EMPTY = GlanceReadings(null, null)
    }
}

object BgGlanceComputer {

    fun compute(
        readings: GlanceReadings,
        state: InferenceState,
        thresholds: AlertThresholds,
        /** Null reads every forecast as unavailable. */
        edges: AlarmFanEdges?,
        lossMin: Int,
        staleMin: Int,
        nowMs: Long,
        /** [directionOf]'s, from the caller: only it holds the sensor's arrow and the fit rows. */
        trend: GlanceTrend?,
    ): BgGlance {
        val latest = readings.lastMeasured
        val warmup = state.warmup != null
        if (latest == null) {
            return BgGlance(
                bgMgdl = null, trendTenths = null, readingAgeMs = 0L, band = null,
                trend = null, fcTrend = GlanceTrend.FLAT, forecastEligible = false,
                forecastStatus = null,
                fcEndMgdl = null, horizonSteps = 0, warmup = warmup, signalLoss = false,
                stale = false, forecastUnavailable = true, predictedLowCrossing = false,
                predictedHighCrossing = false, alarmActive = false, unsure = false, approaching = null,
                urgent = null, summary = if (warmup) "collecting context" else "no reading",
            )
        }

        val bg = latest.bgMgdl
        val ageMs = (nowMs - latest.rxWallMs).coerceAtLeast(0L)
        val band = bg?.let { thresholds.bandFor(it) }

        val sel = state.selectedPrediction
        // §3.6-B/D, and a fan that holds the §6.1 edges.
        val scan = sel?.takeIf { it.eligible }?.let { scanFan(it, edges, thresholds.lowMgdl, thresholds.highMgdl) }
        val eligible = scan != null
        val fcEnd = sel?.takeIf { eligible }?.medianBg?.lastOrNull()?.roundToInt()
        val horizon = sel?.horizonSteps ?: 0

        val predLow = eligible && (0 until horizon).any { edge(sel!!, edges!!.hypoIdx, it) < thresholds.lowMgdl }
        val predHigh = eligible && (0 until horizon).any { edge(sel!!, edges!!.hyperIdx, it) >= thresholds.highMgdl }

        val signalLoss = ageMs > lossMin * 60_000L
        val stale = ageMs > staleMin * 60_000L
        val alarmActive = band == AlertBand.URGENT_LOW || band == AlertBand.URGENT_HIGH || signalLoss

        val unsure = scan is FanScan.Unsure
        val stepMin = sel?.let { (it.stepMs / 60_000L).toInt().coerceAtLeast(1) } ?: 1
        val approaching = (scan as? FanScan.Out)?.let { out ->
            val critical = (out.kind == PredictiveCrossing.Kind.HYPO && out.edgeMgdl < thresholds.urgentLowMgdl) ||
                (out.kind == PredictiveCrossing.Kind.HYPER && out.edgeMgdl >= thresholds.urgentHighMgdl)
            out.toCrossing(
                if (critical) PredictiveCrossing.Severity.CRITICAL else PredictiveCrossing.Severity.WARNING,
                stepMin, thresholds.lowMgdl, thresholds.highMgdl,
            )
        }
        val urgent = if (!eligible || unsure) null else {
            (scanFan(sel!!, edges, thresholds.urgentLowMgdl, thresholds.urgentHighMgdl) as? FanScan.Out)
                ?.toCrossing(PredictiveCrossing.Severity.CRITICAL, stepMin, thresholds.urgentLowMgdl, thresholds.urgentHighMgdl)
        }

        return BgGlance(
            bgMgdl = bg,
            trendTenths = latest.trendTenthsPerMin,
            readingAgeMs = ageMs,
            band = band,
            trend = trend,
            fcTrend = forecastTrend(bg, fcEnd),
            forecastEligible = eligible && !warmup,
            forecastStatus = sel?.status,
            fcEndMgdl = fcEnd,
            horizonSteps = horizon,
            warmup = warmup,
            signalLoss = signalLoss,
            stale = stale,
            forecastUnavailable = !warmup && (sel == null || !eligible),
            predictedLowCrossing = predLow,
            predictedHighCrossing = predHigh,
            alarmActive = alarmActive,
            unsure = unsure,
            approaching = approaching,
            urgent = urgent,
            summary = summarize(bg, trend, eligible, fcEnd, horizon, sel?.status, warmup),
        )
    }

    /** The badge the top bar, widget tile and watch outlook show; [readingAgeMs] null is none. */
    fun status(
        state: InferenceState,
        thresholds: AlertThresholds?,
        edges: AlarmFanEdges?,
        nowMs: Long,
        readingAgeMs: Long?,
        staleMin: Int,
    ): GlyStatus {
        state.warmup?.let {
            return GlyStatus.Void("Collecting context — %.1f / %.0f h BG".format(it.measuredHours, it.requiredHours))
        }
        val staleMs = staleMin * 60_000L
        if (readingAgeMs == null || readingAgeMs > staleMs) {
            return GlyStatus.Void(if (readingAgeMs == null) "No reading" else "Reading stale")
        }
        val p = state.selectedPrediction ?: return GlyStatus.Void("No forecast yet")
        // `p.stale` is stamped inside a cycle; once readings stop, only the anchor's age shows it.
        if (p.stale || nowMs - p.anchorTsMs > staleMs) return GlyStatus.Void("Anchor reading stale")
        if (p.status != ForecastStatus.OK) return GlyStatus.Void("Forecast degenerate (collapsed or rail-pinned)")
        thresholds ?: return GlyStatus.Void("No thresholds set")
        return when (val s = scanFan(p, edges, thresholds.lowMgdl, thresholds.highMgdl)) {
            null -> GlyStatus.Void("Alarm band unavailable")
            FanScan.Clear -> GlyStatus.Stable
            is FanScan.Unsure -> GlyStatus.Unsure
            is FanScan.Out -> GlyStatus.Excursion(s.kind, p.anchorTsMs + (s.step + 1L) * p.stepMs)
        }
    }

    /** First step a §6.1 edge leaves [low, high); null if [edges] is absent or off the fan. */
    fun scanFan(sel: ModelPrediction, edges: AlarmFanEdges?, lowMgdl: Int, highMgdl: Int): FanScan? {
        edges ?: return null
        val nq = sel.nQuantiles
        if (edges.hypoIdx !in 0 until nq || edges.hyperIdx !in 0 until nq) return null
        if (sel.bandsMgdl.size < sel.horizonSteps * nq) return null
        for (i in 0 until sel.horizonSteps) {
            val lo = edge(sel, edges.hypoIdx, i)
            val hi = edge(sel, edges.hyperIdx, i)
            val hypo = lo < lowMgdl
            val hyper = hi >= highMgdl
            when {
                hypo && hyper -> return FanScan.Unsure(i)
                hypo -> return FanScan.Out(PredictiveCrossing.Kind.HYPO, i, lo)
                hyper -> return FanScan.Out(PredictiveCrossing.Kind.HYPER, i, hi)
            }
        }
        return FanScan.Clear
    }

    private fun edge(sel: ModelPrediction, idx: Int, step: Int): Double = sel.bandsMgdl[step * sel.nQuantiles + idx]

    /** ETA is (step+1)*stepMin, first step past the now-line. */
    private fun FanScan.Out.toCrossing(
        severity: PredictiveCrossing.Severity,
        stepMin: Int,
        lowMgdl: Int,
        highMgdl: Int,
    ) = PredictiveCrossing(
        kind = kind,
        severity = severity,
        etaMin = (step + 1) * stepMin,
        thresholdMgdl = if (kind == PredictiveCrossing.Kind.HYPO) lowMgdl else highMgdl,
        projectedMgdl = edgeMgdl.roundToInt(),
    )

    /** Null, never FLAT: a forecast's slope is not a measurement and must not be drawn as one. */
    fun measuredTrend(trendTenths: Int?): GlanceTrend? = trendTenths?.let { classify(it / 10.0) }

    fun forecastTrend(bg: Int?, fcEnd: Int?): GlanceTrend =
        if (bg == null || fcEnd == null) GlanceTrend.FLAT else classify((fcEnd - bg) / 24.0)

    private fun classify(rate: Double): GlanceTrend = when {
        rate > 2.0 -> GlanceTrend.RISING_FAST
        rate > 0.5 -> GlanceTrend.RISING
        rate < -2.0 -> GlanceTrend.FALLING_FAST
        rate < -0.5 -> GlanceTrend.FALLING
        else -> GlanceTrend.FLAT
    }

    private fun summarize(
        bg: Int?, trend: GlanceTrend?, eligible: Boolean, fcEnd: Int?, horizonSteps: Int,
        status: ForecastStatus?, warmup: Boolean,
    ): String = when {
        warmup -> "collecting context"
        eligible && fcEnd != null -> {
            val mins = horizonSteps * 5
            val h = if (mins % 60 == 0) "${mins / 60}h" else "${mins}m"
            val dir = when {
                bg == null -> "to"
                fcEnd - bg > 10 -> "rising to"
                bg - fcEnd > 10 -> "falling to"
                else -> "steady ~"
            }
            "$dir $fcEnd in $h"
        }
        status != null && status != ForecastStatus.OK -> "forecast unavailable"
        bg != null -> {
            val arrow = when (trend) {
                GlanceTrend.RISING_FAST -> "↑↑"
                GlanceTrend.RISING -> "↑"
                GlanceTrend.FALLING_FAST -> "↓↓"
                GlanceTrend.FALLING -> "↓"
                GlanceTrend.FLAT -> "→"
                null -> null
            }
            if (arrow == null) "$bg" else "$bg $arrow"
        }
        else -> "no reading"
    }.let { if (it.length <= 40) it else it.take(40) }
}
