package com.t1dm.alerts

import com.t1dm.core.model.AlertBand

/**
 * Severity of a firing alarm (SPEC.private.md §3.6-A). [CRITICAL] is the tier that, from Phase 7,
 * bypasses Do-Not-Disturb and carries the loudest actuators; in Phase 1 it drives the urgent
 * notification channel and the insistent vibration pattern. Ordinal order is load-bearing:
 * CRITICAL must sort above WARNING when choosing the single primary alarm to surface.
 */
enum class AlarmSeverity { WARNING, CRITICAL }

/** A single active alarm condition. Carries its own plain-language [message] stating WHY it fired. */
sealed interface ActiveAlarm {
    val severity: AlarmSeverity
    val message: String

    /** True when this alarm should escalate (louder / DND-bypass): any CRITICAL threshold breach,
     *  or a loss-of-signal whose last real reading was low or falling. */
    val escalated: Boolean
}

/**
 * A threshold band breach evaluated directly on a MEASURED, in-warm-up-cleared reading — no
 * forecast, no backend, no Rust decode involved (SPEC.private.md §3.6-A threshold alarm).
 */
data class ThresholdBreach(
    val band: AlertBand,
    val bgMgdl: Int,
    val atMs: Long,
    override val severity: AlarmSeverity,
    override val escalated: Boolean,
    override val message: String,
) : ActiveAlarm

/**
 * A loss-of-signal alarm: no MEASURED reading has arrived within the (possibly escalated) window
 * (SPEC.private.md §3.6-A loss-of-signal alarm). Interpolated gap-fill can never postpone it, so
 * a fabricated line hidden inside a dropout still surfaces as lost signal.
 */
data class SignalLoss(
    val lastReadingMs: Long,
    val windowMin: Int,
    val lastBand: AlertBand?,
    val lastBgMgdl: Int?,
    override val severity: AlarmSeverity,
    override val escalated: Boolean,
    override val message: String,
) : ActiveAlarm

/**
 * A weak-signal alarm: the connected sensor link's RSSI has stayed at or below the configured dBm
 * threshold for the sustain window (SPEC.private.md §3.6-A). This is a signal-QUALITY warning —
 * distinct from the age-based [SignalLoss] (no reading at all): here readings are still arriving, but
 * the radio link is weak enough to be at risk. Glucose-adjacent, so it is DEATH-suppressible and
 * snoozable like [SignalLoss]. Always WARNING tier (a weak-but-live link is advisory, not urgent).
 */
data class WeakSignal(
    val rssiDbm: Int,
    val thresholdDbm: Int,
    val atMs: Long,
    override val severity: AlarmSeverity,
    override val escalated: Boolean,
    override val message: String,
) : ActiveAlarm

/**
 * A device-over-temperature alarm: the battery-sensor °C has crossed the configured limit, so
 * inference is being thermally gated (locked decisions D1/D4). This is a device-health notice, not a
 * glucose condition — it rides its OWN channels and is EXEMPT from DEATH mode's global alarm
 * suppression (D4): even with alarms silenced, a phone cooking itself still says so. [alertC]/[clearC]
 * carry the hysteresis band the latching evaluator used.
 */
data class OverTemperature(
    val tempC: Double,
    val atMs: Long,
    val alertC: Double,
    val clearC: Double,
    override val severity: AlarmSeverity,
    override val escalated: Boolean,
    override val message: String,
) : ActiveAlarm

/**
 * The full deterministic-alarm picture at one instant, exposed as a Flow by [AlarmEngine]. The four
 * sub-alarms (threshold, signal-loss, weak-signal, over-temperature) are orthogonal and may co-exist
 * (a low that then loses signal). [primary] picks the single condition to raise as the foreground
 * notification.
 */
data class AlarmState(
    val threshold: ThresholdBreach?,
    val signalLoss: SignalLoss?,
    val overTemperature: OverTemperature? = null,
    val weakSignal: WeakSignal? = null,
) {
    val isActive: Boolean
        get() = threshold != null || signalLoss != null || overTemperature != null || weakSignal != null

    val alarms: List<ActiveAlarm> get() = listOfNotNull(threshold, signalLoss, weakSignal, overTemperature)

    /** Highest-severity active alarm; ties break by kind (see [kindRank]): a glucose threshold breach
     *  beats a signal loss, which beats a weak signal, which beats a device-over-temperature, at equal
     *  severity — the over-temp is a device-health notice, less immediately actionable than any of the
     *  glucose/signal conditions, and a weak-but-live link is milder than an outright loss. `null` when
     *  clear. */
    val primary: ActiveAlarm?
        get() = alarms.maxWithOrNull(
            compareBy<ActiveAlarm>({ it.severity.ordinal }, { kindRank(it) }),
        )

    companion object {
        val CLEAR = AlarmState(threshold = null, signalLoss = null, overTemperature = null, weakSignal = null)

        private fun kindRank(a: ActiveAlarm): Int = when (a) {
            is ThresholdBreach -> 3
            is SignalLoss -> 2
            is WeakSignal -> 1
            is OverTemperature -> 0
        }
    }
}
