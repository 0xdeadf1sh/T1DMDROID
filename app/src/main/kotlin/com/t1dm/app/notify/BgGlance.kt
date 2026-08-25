package com.t1dm.app.notify

import com.t1dm.core.model.AlertBand
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.isRealMeasurement
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.ModelPrediction
import kotlin.math.roundToInt

/**
 * mg/dL and minutes throughout; presenters convert to the active unit. The predictive crossing
 * fields are non-null only for a §3.6-eligible, non-warmup forecast.
 */
data class BgGlance(
    val bgMgdl: Int?,
    val trendTenths: Int?,
    val readingAgeMs: Long,
    val band: AlertBand?,
    /** Measured rate only; null where the source reports no rate. */
    val trend: GlanceTrend?,
    /** The selected forecast's own slope; drives the watch's `fc_trend` and nothing drawn as an arrow. */
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
    /** Earliest predicted crossing of any band. Null when ineligible or never out of range. */
    val approaching: PredictiveCrossing?,
    /** Earliest predicted crossing of an urgent band. */
    val urgent: PredictiveCrossing?,
    /** At most 40 chars; reused verbatim by the watch push. */
    val summary: String,
) {
    val hasReading: Boolean get() = bgMgdl != null
}

enum class GlanceTrend { FLAT, RISING, FALLING, RISING_FAST, FALLING_FAST }

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

/** One value so no call site can pass the same row twice: a promoted reconstruction is a row like
 *  any other, and as the current BG it would put a model's number on the lock screen. */
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
        lossMin: Int,
        staleMin: Int,
        nowMs: Long,
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
                predictedHighCrossing = false, alarmActive = false, approaching = null,
                urgent = null, summary = if (warmup) "collecting context" else "no reading",
            )
        }

        val bg = latest.bgMgdl
        val ageMs = (nowMs - latest.rxWallMs).coerceAtLeast(0L)
        val band = bg?.let { thresholds.bandFor(it) }

        val sel = state.selectedPrediction
        val eligible = sel?.eligible == true // §3.6-B/D
        val fcEnd = sel?.takeIf { eligible }?.medianBg?.lastOrNull()?.roundToInt()
        val horizon = sel?.horizonSteps ?: 0

        val predLow = eligible && sel!!.medianBg.any { it < thresholds.lowMgdl }
        val predHigh = eligible && sel!!.medianBg.any { it >= thresholds.highMgdl }

        val signalLoss = ageMs > lossMin * 60_000L
        val stale = ageMs > staleMin * 60_000L
        val alarmActive = band == AlertBand.URGENT_LOW || band == AlertBand.URGENT_HIGH || signalLoss

        val (approaching, urgent) =
            if (eligible) findCrossings(sel!!, thresholds) else null to null

        val trend = measuredTrend(latest.trendTenthsPerMin)

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
            approaching = approaching,
            urgent = urgent,
            summary = summarize(bg, latest.trendTenthsPerMin, eligible, fcEnd, horizon, sel?.status, warmup),
        )
    }

    /** (earliest-any, earliest-urgent). ETA is `(i+1)·stepMin`: the first step is one past the
     *  now-line. */
    private fun findCrossings(
        sel: ModelPrediction,
        t: AlertThresholds,
    ): Pair<PredictiveCrossing?, PredictiveCrossing?> {
        val stepMin = (sel.stepMs / 60_000L).toInt().coerceAtLeast(1)
        val median = sel.medianBg
        var anyIdx = -1
        var anyKind = PredictiveCrossing.Kind.HYPO
        var urgentIdx = -1
        var urgentKind = PredictiveCrossing.Kind.HYPO
        for (i in median.indices) {
            val v = median[i]
            val hypo = v < t.lowMgdl
            val hyper = v >= t.highMgdl
            if (anyIdx < 0 && (hypo || hyper)) {
                anyIdx = i
                anyKind = if (hypo) PredictiveCrossing.Kind.HYPO else PredictiveCrossing.Kind.HYPER
            }
            val urgentHypo = v < t.urgentLowMgdl
            val urgentHyper = v >= t.urgentHighMgdl
            if (urgentIdx < 0 && (urgentHypo || urgentHyper)) {
                urgentIdx = i
                urgentKind = if (urgentHypo) PredictiveCrossing.Kind.HYPO else PredictiveCrossing.Kind.HYPER
            }
            if (anyIdx >= 0 && urgentIdx >= 0) break
        }
        val approaching = if (anyIdx < 0) null else {
            val v = median[anyIdx].roundToInt()
            val critical = (anyKind == PredictiveCrossing.Kind.HYPO && v < t.urgentLowMgdl) ||
                (anyKind == PredictiveCrossing.Kind.HYPER && v >= t.urgentHighMgdl)
            PredictiveCrossing(
                kind = anyKind,
                severity = if (critical) PredictiveCrossing.Severity.CRITICAL else PredictiveCrossing.Severity.WARNING,
                etaMin = (anyIdx + 1) * stepMin,
                thresholdMgdl = if (anyKind == PredictiveCrossing.Kind.HYPO) t.lowMgdl else t.highMgdl,
                projectedMgdl = v,
            )
        }
        val urgent = if (urgentIdx < 0) null else PredictiveCrossing(
            kind = urgentKind,
            severity = PredictiveCrossing.Severity.CRITICAL,
            etaMin = (urgentIdx + 1) * stepMin,
            thresholdMgdl = if (urgentKind == PredictiveCrossing.Kind.HYPO) t.urgentLowMgdl else t.urgentHighMgdl,
            projectedMgdl = median[urgentIdx].roundToInt(),
        )
        return approaching to urgent
    }

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
        bg: Int?, trendTenths: Int?, eligible: Boolean, fcEnd: Int?, horizonSteps: Int,
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
            val arrow = when {
                (trendTenths ?: 0) > 20 -> "↑↑"
                (trendTenths ?: 0) > 5 -> "↑"
                (trendTenths ?: 0) < -20 -> "↓↓"
                (trendTenths ?: 0) < -5 -> "↓"
                else -> "→"
            }
            "$bg $arrow"
        }
        else -> "no reading"
    }.let { if (it.length <= 40) it else it.take(40) }
}
