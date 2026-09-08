package com.t1dm.alerts

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.ReadingProvenance

/** Weak-signal (§3.6-A); quiets past the RSSI window, never double-alarms [LossOfSignalAlarm]. */
class WeakSignalAlarm(private var config: AlarmConfig) {

    private var lastRssiDbm: Int? = null
    private var lastRssiMs: Long = 0L
    /** Null while the link is healthy. */
    private var weakSinceMs: Long? = null

    /** RSSI belongs to a radio link: the old sensor's is no evidence about the new one. */
    fun onSourceChanged() {
        lastRssiDbm = null
        lastRssiMs = 0L
        weakSinceMs = null
    }

    var weak: WeakSignal? = null
        private set

    /** Never clears a standing episode; the next [evaluate] re-decides. */
    fun updateConfig(config: AlarmConfig) {
        this.config = config
    }

    fun onReading(reading: CgmReading): WeakSignal? {
        val rssi = reading.rssi
        if (reading.provenance != ReadingProvenance.MEASURED || rssi == null) return weak
        lastRssiDbm = rssi
        lastRssiMs = reading.rxWallMs
        if (rssi <= config.weakSignalDbm) {
            if (weakSinceMs == null) weakSinceMs = reading.rxWallMs
        } else {
            weakSinceMs = null
            weak = null
        }
        return weak
    }

    /** Idempotent while an episode persists: keeps the same [WeakSignal] object. */
    fun evaluate(nowMs: Long): WeakSignal? {
        val rssi = lastRssiDbm
        val since = weakSinceMs
        val stale = nowMs - lastRssiMs >= config.lossMin * 60_000L
        if (!config.weakSignalEnabled || rssi == null || since == null || stale) {
            weak = null
            return null
        }
        val sustained = nowMs - since >= config.weakSignalSustainMin.coerceAtLeast(0) * 60_000L
        weak = when {
            !sustained -> null
            weak != null -> weak
            else -> WeakSignal(
                rssiDbm = rssi,
                thresholdDbm = config.weakSignalDbm,
                atMs = nowMs,
                severity = AlarmSeverity.WARNING,
                escalated = false,
                message = weakSignalMessage(rssi, config.weakSignalDbm),
            )
        }
        return weak
    }
}
