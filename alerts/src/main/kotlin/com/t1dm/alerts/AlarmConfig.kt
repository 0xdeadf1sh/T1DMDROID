package com.t1dm.alerts

import com.t1dm.core.model.AlertThresholds

/**
 * Tunables for the deterministic alarm path (SPEC.private.md §3.6-A). Every threshold is user-set
 * and deliberately unbounded (safety lock); the values here are only the conservative defaults the
 * app boots with before the user's config loads.
 *
 * @param lossMin baseline loss-of-signal window (minutes with no MEASURED reading before firing).
 * @param lossEscalatedMin shortened window used when the last real reading was low or falling.
 * @param fallingTrendThresholdTenths trend (0.1 mg/dL/min units) at or below which a reading counts
 *   as "falling" for loss-of-signal escalation; negative means dropping.
 * @param repeatCadenceMin how often a persisting CRITICAL alarm re-vibrates while unchanged.
 * @param tickIntervalMs wall-clock cadence at which the loss-of-signal window is re-evaluated.
 */
data class AlarmConfig(
    val thresholds: AlertThresholds,
    val lossMin: Int = 20,
    val lossEscalatedMin: Int = 12,
    val fallingTrendThresholdTenths: Int = -10,
    val repeatCadenceMin: Int = 5,
    val tickIntervalMs: Long = 60_000L,
) {
    companion object {
        val DEFAULT = AlarmConfig(
            thresholds = AlertThresholds(
                urgentLowMgdl = 55,
                lowMgdl = 70,
                highMgdl = 180,
                urgentHighMgdl = 250,
            ),
        )
    }
}
