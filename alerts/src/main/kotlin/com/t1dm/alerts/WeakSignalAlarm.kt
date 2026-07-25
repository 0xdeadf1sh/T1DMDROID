package com.t1dm.alerts

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.ReadingProvenance

/**
 * The weak-signal alarm (§3.6-A). Fires when the connected sensor link's RSSI has
 * stayed at or below [AlarmConfig.weakSignalDbm] for [AlarmConfig.weakSignalSustainMin] minutes — a
 * signal-QUALITY warning, complementary to [LossOfSignalAlarm] (which fires on the ABSENCE of readings,
 * not their strength). It only considers MEASURED readings carrying an RSSI (interpolated gap-fill
 * carries none), so a fabricated line can never raise it.
 *
 * It self-clears when the link recovers (an RSSI above the threshold) and, so it never double-alarms
 * with loss-of-signal, it goes quiet once the last RSSI-bearing reading is older than the loss window
 * — a dropped link is loss-of-signal's job, not a stale weak-signal reading's.
 *
 * Deterministic: the wall clock is always passed in via [evaluate], never read internally.
 */
class WeakSignalAlarm(private var config: AlarmConfig) {

    private var lastRssiDbm: Int? = null
    private var lastRssiMs: Long = 0L
    /** When the RSSI first dropped to/below the threshold (null while the link is healthy). */
    private var weakSinceMs: Long? = null

    var weak: WeakSignal? = null
        private set

    /** Live-swap the threshold / sustain / enable WITHOUT touching [weak] or the weak-since clock: a
     *  changed threshold re-decides on the next [evaluate], never clears a standing episode on the spot. */
    fun updateConfig(config: AlarmConfig) {
        this.config = config
    }

    /** Record the RSSI carried on a MEASURED reading (interpolated gap-fill has none, so it is ignored).
     *  Latches when the link first goes weak, and un-latches (clearing the alarm) the moment it recovers. */
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

    /** Re-evaluate against wall-clock [nowMs]. Idempotent while an episode persists (keeps the same
     *  object). Goes quiet when disabled, when the link recovered, or when the last RSSI reading has
     *  aged past the loss-of-signal window (loss-of-signal then owns the condition). */
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
            weak != null -> weak // keep the existing episode object
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
