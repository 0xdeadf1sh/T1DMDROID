package com.t1dm.alerts

import com.t1dm.core.model.AlertBand

/**
 * Severity of a firing alarm (PLAN.private.md §3.6-A). [CRITICAL] is the tier that, from Phase 7,
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
 * forecast, no backend, no Rust decode involved (PLAN.private.md §3.6-A threshold alarm).
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
 * (PLAN.private.md §3.6-A loss-of-signal alarm). Interpolated gap-fill can never postpone it, so
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
 * The full deterministic-alarm picture at one instant, exposed as a Flow by [AlarmEngine]. The two
 * sub-alarms are orthogonal and may co-exist (a low that then loses signal). [primary] picks the
 * single condition to raise as the foreground notification.
 */
data class AlarmState(
    val threshold: ThresholdBreach?,
    val signalLoss: SignalLoss?,
) {
    val isActive: Boolean get() = threshold != null || signalLoss != null

    val alarms: List<ActiveAlarm> get() = listOfNotNull(threshold, signalLoss)

    /** Highest-severity active alarm; a threshold breach wins a severity tie (an urgent-low is more
     *  immediately actionable than a signal loss of the same tier). `null` when clear. */
    val primary: ActiveAlarm?
        get() = alarms.maxWithOrNull(
            compareBy<ActiveAlarm>({ it.severity.ordinal }, { if (it is ThresholdBreach) 1 else 0 }),
        )

    companion object {
        val CLEAR = AlarmState(threshold = null, signalLoss = null)
    }
}
