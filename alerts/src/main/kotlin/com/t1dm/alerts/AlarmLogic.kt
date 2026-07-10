package com.t1dm.alerts

import com.t1dm.core.model.AlertBand
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance

/**
 * The provenance rule, in one predicate (SPEC.private.md §3.6-A): only a MEASURED, out-of-warm-up
 * reading with a defined value participates in alarm evaluation. INTERPOLATED gap-fill and WARMUP
 * values are structurally barred from ever raising OR clearing an alarm — a fabricated line inside
 * a gap can therefore never silence a real excursion.
 */
internal fun CgmReading.isEligibleMeasured(): Boolean =
    provenance == ReadingProvenance.MEASURED && flag == ReadingFlag.NORMAL && bgMgdl != null

/** Severity a band maps to; the urgent tiers are CRITICAL (DND-bypass from Phase 7). */
internal fun AlertBand.severity(): AlarmSeverity = when (this) {
    AlertBand.URGENT_LOW, AlertBand.URGENT_HIGH -> AlarmSeverity.CRITICAL
    AlertBand.LOW, AlertBand.HIGH, AlertBand.IN_RANGE -> AlarmSeverity.WARNING
}

/**
 * Whether the last real reading warrants a shortened loss-of-signal window: it was in a low band,
 * or it was dropping at/below the configured fall rate. This is the "escalate to 12–15 min if the
 * last real reading was low/falling" rule.
 */
internal fun isLowOrFalling(reading: CgmReading, config: AlarmConfig): Boolean {
    val low = reading.bgMgdl
        ?.let { config.thresholds.bandFor(it) }
        ?.let { it == AlertBand.URGENT_LOW || it == AlertBand.LOW }
        ?: false
    val falling = reading.trendTenthsPerMin
        ?.let { it <= config.fallingTrendThresholdTenths }
        ?: false
    return low || falling
}

/** Plain-language threshold message; wording matches [AlertThresholds.bandFor]'s boundary semantics
 *  (low bands are strict `<`, high bands are `>=`). */
internal fun thresholdMessage(band: AlertBand, bgMgdl: Int, t: AlertThresholds): String = when (band) {
    AlertBand.URGENT_LOW -> "Urgent low: $bgMgdl mg/dL (below ${t.urgentLowMgdl}). Treat now."
    AlertBand.LOW -> "Low: $bgMgdl mg/dL (below ${t.lowMgdl})."
    AlertBand.HIGH -> "High: $bgMgdl mg/dL (at or above ${t.highMgdl})."
    AlertBand.URGENT_HIGH -> "Urgent high: $bgMgdl mg/dL (at or above ${t.urgentHighMgdl})."
    AlertBand.IN_RANGE -> "In range: $bgMgdl mg/dL."
}

/** Plain-language loss-of-signal message stating the window that lapsed and the last known value. */
internal fun signalLossMessage(windowMin: Int, escalated: Boolean, lastBgMgdl: Int?): String {
    val last = lastBgMgdl?.let { " Last reading $it mg/dL." } ?: ""
    return if (escalated) {
        "Signal lost: no sensor reading for over $windowMin min — last reading was low or falling.$last Check the sensor."
    } else {
        "Signal lost: no sensor reading for over $windowMin min.$last Check the sensor."
    }
}
