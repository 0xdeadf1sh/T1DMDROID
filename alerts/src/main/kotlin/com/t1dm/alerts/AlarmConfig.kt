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
 * @param minActuationIntervalMin minimum minutes between an alarm's SOUND+VIBRATION actuations while
 *   it stays in the same band — so a once-a-minute reading stream re-announces (sound/buzz) at most
 *   this often. A new band/severity always actuates immediately; the notification text still updates
 *   silently in between. Presentation-only: this never changes WHEN the pure engine fires (§3.6-A).
 * @param tickIntervalMs wall-clock cadence at which the loss-of-signal window is re-evaluated.
 * @param overTempEnabled whether the device-over-temperature alarm participates at all (D4).
 * @param overTempAlertC battery-sensor °C at or above which the over-temp alarm fires.
 * @param overTempClearC battery-sensor °C at or below which it clears; the gap to [overTempAlertC] is
 *   deliberate hysteresis so a temperature hovering near the limit does not chatter.
 * @param overTempSeverity the tier the over-temp alarm announces at (WARNING vs the loud CRITICAL).
 */
data class AlarmConfig(
    val thresholds: AlertThresholds,
    val lossMin: Int = 20,
    val lossEscalatedMin: Int = 12,
    val fallingTrendThresholdTenths: Int = -10,
    val repeatCadenceMin: Int = 5,
    val minActuationIntervalMin: Int = 5,
    val tickIntervalMs: Long = 60_000L,
    val overTempEnabled: Boolean = true,
    val overTempAlertC: Double = 44.0,
    val overTempClearC: Double = 41.0,
    val overTempSeverity: AlarmSeverity = AlarmSeverity.WARNING,
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
