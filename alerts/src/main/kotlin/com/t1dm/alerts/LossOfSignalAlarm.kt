package com.t1dm.alerts

import com.t1dm.core.model.CgmReading

/** Loss-of-signal (§3.6-A): only an eligible MEASURED reading refreshes the clock, not gap-fill. */
class LossOfSignalAlarm(private var config: AlarmConfig) {

    private var lastMeasured: CgmReading? = null

    private var armedAtMs: Long? = null

    /** Restarts staleness clock at [nowMs]; lastMeasured kept, it is the escalation basis. */
    fun onSourceChanged(nowMs: Long) {
        armedAtMs = nowMs
    }

    var loss: SignalLoss? = null
        private set

    /** Never clears a standing episode; the next [evaluate] re-decides. */
    fun updateConfig(config: AlarmConfig) {
        this.config = config
    }

    fun onReading(reading: CgmReading): SignalLoss? {
        if (reading.isEligibleMeasured()) {
            lastMeasured = reading
            loss = null
        }
        return loss
    }

    /** Idempotent while an episode persists: keeps the same [SignalLoss] object. */
    fun evaluate(nowMs: Long): SignalLoss? {
        val last = lastMeasured
        if (last == null) {
            loss = null
            return null
        }
        val escalate = isLowOrFalling(last, config)
        val windowMin = if (escalate) config.lossEscalatedMin else config.lossMin
        val since = maxOf(last.rxWallMs, armedAtMs ?: Long.MIN_VALUE)
        val overdue = nowMs - since >= windowMin * 60_000L
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
