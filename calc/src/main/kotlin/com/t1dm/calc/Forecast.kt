package com.t1dm.calc

import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.ForecastStatus

/** Only [ELIGIBLE] may score a candidate or clear a rail; anything else fails the dependent rail
 *  closed (§3.6-B/-C/-D). */
enum class ForecastEligibility {
    /** Finite, monotone, non-collapsed, anchored on a fresh MEASURED reading. */
    ELIGIBLE,

    /** The Rust `forecast_degeneracy_check` rejected the fan. */
    DEGENERATE,

    /** Dead in production: [RollingForecaster] never emits it, freshness is the DoseAdvisor's gate
     *  (§3.6-D). Kept for fakes and defence in depth. */
    STALE,

    /** No selected model / no `.pte` / no context. */
    MISSING,
}

/** One step in mg/dL. [lowerBg]/[upperBg] are τ=.05 / τ=.95, the outermost pair of [bandsMgdl],
 *  and the only levels `:calc` reads. */
data class FanStep(
    val medianBg: Double,
    val lowerBg: Double,
    val upperBg: Double,
    /** DISPLAY-ONLY. Seven mg/dL levels, ascending τ; empty when the producer had no fan. */
    val bandsMgdl: List<Double> = emptyList(),
) {
    val bandWidth: Double get() = upperBg - lowerBg
}

/** Rolled by re-feeding the median (INFERENCE.md §9). [steps] is step-major; [validatedSteps] marks
 *  the prefix dose selection is capped to. A non-eligible fan may have empty [steps]: check
 *  [eligible] before reading any band. */
data class PredFan(
    val candidateU: Double,
    val steps: List<FanStep>,
    val stepMs: Long,
    val validatedSteps: Int,
    val worstStatus: ForecastStatus,
    val eligibility: ForecastEligibility,
) {
    val eligible: Boolean get() = eligibility == ForecastEligibility.ELIGIBLE

    /** Dose decisions read this, never the whole roll. Clamped, so a malformed [validatedSteps]
     *  yields an empty window rather than a throw or a read of the extrapolated tail. */
    fun validatedWindow(): List<FanStep> = steps.subList(0, validatedSteps.coerceIn(0, steps.size))

    /** Null when the validated window is empty. */
    fun minMedianBg(): Double? = validatedWindow().minOfOrNull { it.medianBg }

    /** 0-based step index within the validated window, or null. The median, not the lower band:
     *  a widening edge is under any floor on nearly every roll and pinned the advisor at 0 U. */
    fun firstMedianBelow(mgdl: Double): Int? =
        validatedWindow().indexOfFirst { it.medianBg < mgdl }.takeIf { it >= 0 }
}

/** [announced] is the user's committed future; [candidate] the dose being scored, null = the
 *  do-nothing baseline. [candidateU] is the candidate's INSULIN total, for the card and IOB rail. */
data class ForecastRequest(
    val rollStartMs: Long,
    val fullRollSteps: Int,
    val validatedSteps: Int,
    val announced: List<CurveEvent>,
    val candidate: List<CurveEvent>?,
    val candidateU: Double,
    /** INFERENCE.md §7.1. Pinned by the [DoseAdvisor] across one recommendation; null lets the port
     *  resolve its own from the live setting. */
    val smoothingWindow: Int? = null,
)

/** SPEC §3.2 `ForecastEngine`. */
interface ForecastPort {
    /** Fail-closed: return a non-eligible [PredFan] on a missing model or a degenerate roll — never
     *  throw, never fabricate a band. */
    suspend fun roll(request: ForecastRequest): PredFan
}
