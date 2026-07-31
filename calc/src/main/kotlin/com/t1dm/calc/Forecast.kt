package com.t1dm.calc

import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.ForecastStatus

/**
 * Why a rolled forecast fan may not be trusted to drive a rail or a dose selection (SPEC §3.6-B/-C/-D).
 * Only [ELIGIBLE] fans may score a candidate or clear a rail; every other value forces the
 * dependent rail to fail closed.
 */
enum class ForecastEligibility {
    /** Finite, monotone, non-collapsed, and anchored on a fresh MEASURED reading. */
    ELIGIBLE,

    /** The Rust `forecast_degeneracy_check` rejected the fan (NaN / rail-pinned / collapsed / mis-ordered). */
    DEGENERATE,

    /**
     * The anchor is older than the freshness gate (§3.6-D). **Dead in production**: the real
     * [RollingForecaster] never emits STALE — it yields only [MISSING] / [DEGENERATE] / [ELIGIBLE].
     * Staleness is enforced solely by the DoseAdvisor freshness gate (§3.6-D), never by the fan; this
     * value survives only for fakes and defence in depth.
     */
    STALE,

    /** No selected model / no `.pte` / no context — the forecast could not be produced at all. */
    MISSING,
}

/** One step of the rolled fan in mg/dL: the median plus the extreme band edges (τ=.05 / τ=.95). */
data class FanStep(
    val medianBg: Double,
    val lowerBg: Double,
    val upperBg: Double,
) {
    val bandWidth: Double get() = upperBg - lowerBg
}

/**
 * A candidate dose's forecast fan, rolled to the full action window (~5 h) by re-feeding the median
 * (INFERENCE.md §9). [steps] is step-major over the whole roll; [validatedSteps] marks the prefix
 * inside the validated `PREDICTION_HORIZON_HOURS` window that dose *selection* is capped to — the
 * tail beyond is carried for context and scored only at [HorizonPolicy.beyondWindowWeight].
 *
 * **Fail-closed contract.** A [ForecastPort] never throws for a degenerate/stale/absent forecast; it
 * returns a fan whose [eligibility] is non-[ForecastEligibility.ELIGIBLE] and whose [steps] may be
 * empty. Consumers must check [eligible] before reading any band.
 */
data class PredFan(
    val candidateU: Double,
    val steps: List<FanStep>,
    val stepMs: Long,
    val validatedSteps: Int,
    val worstStatus: ForecastStatus,
    val eligibility: ForecastEligibility,
) {
    val eligible: Boolean get() = eligibility == ForecastEligibility.ELIGIBLE

    /**
     * The validated prefix of [steps] — the window dose selection is capped to. Clamped into
     * `0..steps.size`, so a malformed [validatedSteps] yields an empty window rather than an
     * exception or a silent read of the extrapolated tail.
     *
     * Dose decisions read THIS, never the whole roll. The tail past it is carried for context and
     * for the display curve; it is extrapolated by re-feeding the median (INFERENCE.md §9), and a
     * gate keyed on it would be decided by steps the app itself declines to call validated.
     */
    fun validatedWindow(): List<FanStep> = steps.subList(0, validatedSteps.coerceIn(0, steps.size))

    /** The lowest MEDIAN across the validated window, or null when that window is empty. */
    fun minMedianBg(): Double? = validatedWindow().minOfOrNull { it.medianBg }

    /**
     * The step index (0-based) at which the MEDIAN first drops below [mgdl] within the validated
     * window, or null. The predicted-low veto reads this.
     *
     * The median, not the lower band: a band edge answers "could this go low", which for a widening
     * fan is true of nearly every roll and pinned the advisor at 0 U unconditionally. The median
     * answers "is low the expected outcome". That is a deliberate reduction in caution — the
     * compensating protections are the objective's own hypo term (see [Scoring]) and the
     * degeneracy gate, which still refuses an unusable fan outright.
     */
    fun firstMedianBelow(mgdl: Double): Int? =
        validatedWindow().indexOfFirst { it.medianBg < mgdl }.takeIf { it >= 0 }
}

/**
 * A request to roll the SELECTED fp32-authoritative model under one announced-future scenario. Maps
 * one-to-one onto [com.t1dm.data.curve.ChannelBuilder.futureOverrides]: [announced] is the user's
 * committed future meals/boluses, [candidate] the dose the calculator is scoring (null = the
 * do-nothing baseline). [candidateU] is the candidate's total insulin, carried for the card / IOB rail.
 */
data class ForecastRequest(
    val rollStartMs: Long,
    val fullRollSteps: Int,
    val validatedSteps: Int,
    val announced: List<CurveEvent>,
    val candidate: List<CurveEvent>?,
    val candidateU: Double,
    /** The BG causal-SavGol window (INFERENCE.md §7.1) this roll MUST be built at. The [DoseAdvisor]
     *  pins one resolved value onto every candidate of a recommendation, so the ranking cannot compare
     *  fans anchored on two different `last_bg` values and the §3.6-F card can state the window its fan
     *  was actually built at. `null` lets the port resolve its own — the display roll, which follows
     *  the live setting rather than a decision's snapshot. */
    val smoothingWindow: Int? = null,
)

/**
 * The seam to the selected model's rolled forecast (SPEC §3.2 `ForecastEngine`, used by `:calc`).
 * The real implementation ([RollingForecaster]) drives `ChannelBuilder` → `NativeCore.buildContext`
 * → the fp32 backend → `assemble_decode` → `forecast_degeneracy_check`, per roll. Calculators and
 * their property tests depend only on this interface, so the safety logic is exercised against a
 * deterministic fake with no model on device.
 */
interface ForecastPort {
    /**
     * Roll one candidate to the full window. **Fail-closed**: on a missing model or a degenerate roll,
     * return a non-eligible [PredFan] — never throw, never fabricate a band. Staleness is NOT the fan's
     * concern: the production [RollingForecaster] never emits [ForecastEligibility.STALE]; freshness is
     * enforced solely by the DoseAdvisor freshness gate (§3.6-D).
     */
    suspend fun roll(request: ForecastRequest): PredFan
}
