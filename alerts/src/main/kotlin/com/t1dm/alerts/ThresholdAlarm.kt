package com.t1dm.alerts

import com.t1dm.core.model.AlertBand
import com.t1dm.core.model.CgmReading

/** Threshold alarm (§3.6-A). Only eligible MEASURED reading changes [breach]; nothing else can. */
class ThresholdAlarm(private var config: AlarmConfig) {

    var breach: ThresholdBreach? = null
        private set

    /** Never clears a standing breach; the next eligible MEASURED reading re-classifies. */
    fun updateConfig(config: AlarmConfig) {
        this.config = config
    }

    /** Raises, deepens and switches side at once; eases only past the clear margin. */
    fun onReading(reading: CgmReading): ThresholdBreach? {
        if (!reading.isEligibleMeasured()) return breach
        val bgMgdl = reading.bgMgdl ?: return breach
        val band = config.thresholds.bandFor(bgMgdl)
        val held = breach?.band
        val next = if (held != null && band.eases(held)) eased(held, bgMgdl) else band
        if (next == held && band != held) return breach
        breach = if (next == AlertBand.IN_RANGE) {
            null
        } else {
            ThresholdBreach(
                band = next,
                bgMgdl = bgMgdl,
                atMs = reading.tsMs,
                severity = next.severity(),
                escalated = next.severity() == AlarmSeverity.CRITICAL,
                message = thresholdMessage(band, bgMgdl, config.thresholds),
            )
        }
        return breach
    }

    /** The tier [bgMgdl] still holds when every threshold it left must be cleared by the margin. */
    private fun eased(held: AlertBand, bgMgdl: Int): AlertBand {
        val margin = config.clearMarginMgdl.coerceAtLeast(0)
        val band = config.thresholds.bandFor(if (held.isLow()) bgMgdl - margin else bgMgdl + margin)
        return if (band.depth() < held.depth()) band else held
    }
}

private fun AlertBand.isLow(): Boolean = this == AlertBand.URGENT_LOW || this == AlertBand.LOW

private fun AlertBand.depth(): Int = when (this) {
    AlertBand.URGENT_LOW, AlertBand.URGENT_HIGH -> 2
    AlertBand.LOW, AlertBand.HIGH -> 1
    AlertBand.IN_RANGE -> 0
}

/** Shallower than [held] on its own side, or back in range. */
private fun AlertBand.eases(held: AlertBand): Boolean =
    depth() < held.depth() && (this == AlertBand.IN_RANGE || isLow() == held.isLow())
