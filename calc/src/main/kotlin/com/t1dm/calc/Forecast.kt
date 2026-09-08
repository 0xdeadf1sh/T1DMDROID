package com.t1dm.calc

import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.ForecastStatus

/** Only ELIGIBLE may score a candidate or clear a rail; anything else fails closed (§3.6-B/C/D). */
enum class ForecastEligibility {
    /** Finite, monotone, non-collapsed, anchored on a fresh MEASURED reading. */
    ELIGIBLE,

    /** The Rust `forecast_degeneracy_check` rejected the fan. */
    DEGENERATE,

    /** Dead in production: RollingForecaster never emits it; kept for fakes, defence in depth. */
    STALE,

    /** No selected model / no `.pte` / no context. */
    MISSING,
}

/** One step in mg/dL; lowerBg/upperBg are τ=.05/.95, the outermost pair :calc reads. */
data class FanStep(
    val medianBg: Double,
    val lowerBg: Double,
    val upperBg: Double,
    /** DISPLAY-ONLY. Seven mg/dL levels, ascending τ; empty when the producer had no fan. */
    val bandsMgdl: List<Double> = emptyList(),
) {
    val bandWidth: Double get() = upperBg - lowerBg
}

/** Rolled by re-feeding the median (INFERENCE.md §9); check eligible before reading any band. */
data class PredFan(
    val candidateU: Double,
    val steps: List<FanStep>,
    val stepMs: Long,
    val validatedSteps: Int,
    val worstStatus: ForecastStatus,
    val eligibility: ForecastEligibility,
) {
    val eligible: Boolean get() = eligibility == ForecastEligibility.ELIGIBLE

    /** Dose decisions read this, never the whole roll; malformed validatedSteps yields empty. */
    fun validatedWindow(): List<FanStep> = steps.subList(0, validatedSteps.coerceIn(0, steps.size))

    /** Null when the validated window is empty. */
    fun minMedianBg(): Double? = validatedWindow().minOfOrNull { it.medianBg }

    /** 0-based step index in the validated window; median not lower band pins advisor at 0U. */
    fun firstMedianBelow(mgdl: Double): Int? =
        validatedWindow().indexOfFirst { it.medianBg < mgdl }.takeIf { it >= 0 }
}

/** announced is committed future; candidate is scored (null=do-nothing); candidateU is insulin. */
data class ForecastRequest(
    val rollStartMs: Long,
    val fullRollSteps: Int,
    val validatedSteps: Int,
    val announced: List<CurveEvent>,
    val candidate: List<CurveEvent>?,
    val candidateU: Double,
    /** INFERENCE.md §7.1; pinned by DoseAdvisor per recommendation, else resolved live. */
    val smoothingWindow: Int? = null,
)

/** SPEC §3.2 `ForecastEngine`. */
interface ForecastPort {
    /** Fail-closed: non-eligible PredFan on missing model or degenerate roll; never fabricates. */
    suspend fun roll(request: ForecastRequest): PredFan
}
