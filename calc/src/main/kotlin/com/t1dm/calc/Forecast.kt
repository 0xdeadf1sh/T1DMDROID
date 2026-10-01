package com.t1dm.calc

import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.ForecastStatus

/** Only ELIGIBLE may score a candidate or clear a rail; else the dependent rail fails closed. */
enum class ForecastEligibility {
    /** Finite, monotone, non-collapsed. Anchor age unchecked. */
    ELIGIBLE,

    /** The Rust `forecast_degeneracy_check` rejected the fan. */
    DEGENERATE,

    /** Dead in production: RollingForecaster never emits it; kept for fakes, defence in depth. */
    STALE,

    /** No selected model / no `.pte` / no context. */
    MISSING,
}

/** One step in mg/dL; lowerBg/upperBg are τ=.05/.95, outer pair of bandsMgdl, calc's only read. */
data class FanStep(
    val medianBg: Double,
    val lowerBg: Double,
    val upperBg: Double,
    /** DISPLAY-ONLY. Seven mg/dL levels, ascending τ; empty when the producer had no fan. */
    val bandsMgdl: List<Double> = emptyList(),
) {
    val bandWidth: Double get() = upperBg - lowerBg
}

/** Rolled by re-feeding the median (§9); step-major; validatedSteps caps dose selection. */
data class PredFan(
    val candidateU: Double,
    val steps: List<FanStep>,
    val stepMs: Long,
    val validatedSteps: Int,
    val worstStatus: ForecastStatus,
    val eligibility: ForecastEligibility,
) {
    val eligible: Boolean get() = eligibility == ForecastEligibility.ELIGIBLE

    /** Dose decisions read this, not the whole roll; bad validatedSteps yields empty, no throw. */
    fun validatedWindow(): List<FanStep> = steps.subList(0, validatedSteps.coerceIn(0, steps.size))

    /** Null when the validated window is empty. */
    fun minMedianBg(): Double? = validatedWindow().minOfOrNull { it.medianBg }

    /** 0-based index in the validated window, or null; median, not lower band, avoids a pin. */
    fun firstMedianBelow(mgdl: Double): Int? =
        validatedWindow().indexOfFirst { it.medianBg < mgdl }.takeIf { it >= 0 }
}

/** announced is the committed future; candidate the dose scored, null = do-nothing baseline. */
data class ForecastRequest(
    val rollStartMs: Long,
    val fullRollSteps: Int,
    /** Ignored by [RollingForecaster], which stamps the descriptor's window on the fan. */
    val validatedSteps: Int,
    val announced: List<CurveEvent>,
    val candidate: List<CurveEvent>?,
    val candidateU: Double,
    /** §7.1. Pinned by DoseAdvisor per recommendation; null resolves from the live setting. */
    val smoothingWindow: Int? = null,
)

/** SPEC §3.2 `ForecastEngine`. */
interface ForecastPort {
    /** Fail-closed: a non-eligible PredFan on a missing model or degenerate roll, never throw. */
    suspend fun roll(request: ForecastRequest): PredFan
}
