package com.t1dm.app.notify

import com.t1dm.core.model.AlertBand
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.ModelPrediction
import kotlin.math.roundToInt

/**
 * The single, model-space snapshot every glanceable surface renders — the always-on notification
 * (item 15), the home/lock widgets, and (via [com.t1dm.app.watch.AppWatchGlanceSource]) the watch
 * push — so all three AGREE by construction (PLAN Phase 7B). It carries the current BG + trend +
 * reading age, the deterministic band, and a §3.6-GATED forecast read-out: the predictive crossing
 * fields are non-null ONLY when the selected model's forecast is eligible (fresh MEASURED anchor,
 * passes the degeneracy guard) and not withheld by the warmup gate.
 *
 * Everything here is mg/dL / minutes; presenters convert to the active unit. Deliberately a plain
 * value type computed by the pure [BgGlanceComputer] so it is host-testable with no Android.
 */
data class BgGlance(
    val bgMgdl: Int?,
    val trendTenths: Int?,
    val readingAgeMs: Long,
    val band: AlertBand?,
    val trend: GlanceTrend,
    /** True iff the selected forecast is §3.6-eligible (OK status, fresh anchor) AND not in warmup. */
    val forecastEligible: Boolean,
    val forecastStatus: ForecastStatus?,
    /** Selected-model median BG at the horizon end (mg/dL), or null when no eligible forecast. */
    val fcEndMgdl: Int?,
    val horizonSteps: Int,
    val warmup: Boolean,
    val signalLoss: Boolean,
    val stale: Boolean,
    /** No eligible forecast exists (no model, degenerate/stale, but NOT the warmup case). */
    val forecastUnavailable: Boolean,
    val predictedLowCrossing: Boolean,
    val predictedHighCrossing: Boolean,
    val alarmActive: Boolean,
    /** Earliest predicted crossing of ANY band (low/high/urgent) — drives the notification's
     *  "approaching …" line. Null when the forecast is ineligible or never leaves range. */
    val approaching: PredictiveCrossing?,
    /** Earliest predicted crossing of an URGENT band — drives the full-screen critical predictive
     *  alert (item 2). Null unless the eligible median reaches urgent-low/urgent-high in horizon. */
    val urgent: PredictiveCrossing?,
    /** The one-line human-readable summary (≤ 40 chars; reused verbatim by the watch push). */
    val summary: String,
) {
    val hasReading: Boolean get() = bgMgdl != null
}

/** Coarse direction for a chevron; classified from the measured rate, falling back to the forecast. */
enum class GlanceTrend { FLAT, RISING, FALLING, RISING_FAST, FALLING_FAST }

/**
 * A §3.6-gated forecast crossing: the selected model predicts BG will cross [thresholdMgdl] in
 * about [etaMin] minutes, reaching [projectedMgdl]. [severity] is CRITICAL for the urgent bands
 * (drives DND-bypass + full-screen), WARNING for low/high.
 */
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

/**
 * The pure, Android-free glance computation lifted out of the watch source so the notification,
 * widgets, and watch all share ONE algorithm (PLAN Phase 7B "lift that same computation"). The
 * §3.6 gate is the `eligible` predicate: a forecast drives NO predictive field unless
 * `status == OK && !stale`, and the warmup gate (predictions cleared, `state.warmup != null`)
 * degrades every surface to BG + trend with an honest "collecting context".
 */
object BgGlanceComputer {

    fun compute(
        latest: CgmReading?,
        state: InferenceState,
        thresholds: AlertThresholds,
        lossMin: Int,
        staleMin: Int,
        nowMs: Long,
    ): BgGlance {
        val warmup = state.warmup != null
        if (latest == null) {
            return BgGlance(
                bgMgdl = null, trendTenths = null, readingAgeMs = 0L, band = null,
                trend = GlanceTrend.FLAT, forecastEligible = false, forecastStatus = null,
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
        val eligible = sel?.eligible == true // §3.6-B/D: OK status AND fresh anchor
        val fcEnd = sel?.takeIf { eligible }?.medianBg?.lastOrNull()?.roundToInt()
        val horizon = sel?.horizonSteps ?: 0

        val predLow = eligible && sel!!.medianBg.any { it < thresholds.lowMgdl }
        val predHigh = eligible && sel!!.medianBg.any { it >= thresholds.highMgdl }

        val signalLoss = ageMs > lossMin * 60_000L
        val stale = ageMs > staleMin * 60_000L
        val alarmActive = band == AlertBand.URGENT_LOW || band == AlertBand.URGENT_HIGH || signalLoss

        val (approaching, urgent) =
            if (eligible) findCrossings(sel!!, thresholds) else null to null

        val trend = classifyTrend(latest.trendTenthsPerMin, bg, fcEnd)

        return BgGlance(
            bgMgdl = bg,
            trendTenths = latest.trendTenthsPerMin,
            readingAgeMs = ageMs,
            band = band,
            trend = trend,
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

    /**
     * One pass over the eligible median: the FIRST step below `low` (or `urgentLow`) and the FIRST
     * step at/above `high` (or `urgentHigh`). Returns (earliest-any, earliest-urgent) so the
     * notification line and the urgent full-screen alert can target different tiers. The ETA is
     * `(i+1)·stepMinutes` — the model's forecast begins one step past the now-line.
     */
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

    fun classifyTrend(trendTenths: Int?, bg: Int?, fcEnd: Int?): GlanceTrend {
        val rate = trendTenths?.let { it / 10.0 } ?: run {
            if (bg != null && fcEnd != null) (fcEnd - bg) / 24.0 else 0.0
        }
        return when {
            rate > 2.0 -> GlanceTrend.RISING_FAST
            rate > 0.5 -> GlanceTrend.RISING
            rate < -2.0 -> GlanceTrend.FALLING_FAST
            rate < -0.5 -> GlanceTrend.FALLING
            else -> GlanceTrend.FLAT
        }
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
