package com.t1dm.alerts

import com.t1dm.core.model.CgmReading

/**
 * The model-free loss-of-signal alarm (PLAN.private.md §3.6-A). Fires when no MEASURED reading has
 * arrived for the configured window, and escalates (shorter window, CRITICAL) when the last real
 * reading was low or falling — the exact windows in which a user relying on urgent-low is otherwise
 * unprotected (official app steals the sensor, out-of-range, Doze kill).
 *
 * Only an eligible MEASURED reading refreshes the clock; INTERPOLATED gap-fill deliberately does
 * NOT, so a dropout papered over with interpolated points still surfaces as lost signal. The alarm
 * never fires before a first real reading is seen — there is no signal to have lost.
 *
 * Deterministic: the wall clock is always passed in via [evaluate], never read internally.
 */
class LossOfSignalAlarm(private val config: AlarmConfig) {

    private var lastMeasured: CgmReading? = null

    var loss: SignalLoss? = null
        private set

    /** Refreshes the freshness clock on an eligible MEASURED reading (which also clears any loss).
     *  INTERPOLATED / WARMUP / INVALID readings are ignored. */
    fun onReading(reading: CgmReading): SignalLoss? {
        if (reading.isEligibleMeasured()) {
            lastMeasured = reading
            loss = null
        }
        return loss
    }

    /** Re-evaluates against wall-clock [nowMs]. Idempotent while an episode persists — it keeps the
     *  existing [SignalLoss] object (same escalation) rather than churning a new one each tick. */
    fun evaluate(nowMs: Long): SignalLoss? {
        val last = lastMeasured
        if (last == null) {
            loss = null
            return null
        }
        val escalate = isLowOrFalling(last, config)
        val windowMin = if (escalate) config.lossEscalatedMin else config.lossMin
        val overdue = nowMs - last.rxWallMs >= windowMin * 60_000L
        loss = when {
            !overdue -> null
            loss.let { it != null && it.escalated == escalate } -> loss
            else -> SignalLoss(
                lastReadingMs = last.rxWallMs,
                windowMin = windowMin,
                lastBand = last.bgMgdl?.let { config.thresholds.bandFor(it) },
                lastBgMgdl = last.bgMgdl,
                severity = if (escalate) AlarmSeverity.CRITICAL else AlarmSeverity.WARNING,
                escalated = escalate,
                message = signalLossMessage(windowMin, escalate, last.bgMgdl),
            )
        }
        return loss
    }
}
