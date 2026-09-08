package com.t1dm.alerts

import com.t1dm.core.model.AlertBand
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CgmReading

/** Threshold alarm (§3.6-A); only an eligible MEASURED reading changes [breach], nothing else. */
class ThresholdAlarm(private var thresholds: AlertThresholds) {

    var breach: ThresholdBreach? = null
        private set

    /** Never clears a standing breach; the next eligible MEASURED reading re-classifies. */
    fun updateThresholds(thresholds: AlertThresholds) {
        this.thresholds = thresholds
    }

    fun onReading(reading: CgmReading): ThresholdBreach? {
        if (!reading.isEligibleMeasured()) return breach
        val bgMgdl = reading.bgMgdl ?: return breach
        val band = thresholds.bandFor(bgMgdl)
        breach = if (band == AlertBand.IN_RANGE) {
            null
        } else {
            ThresholdBreach(
                band = band,
                bgMgdl = bgMgdl,
                atMs = reading.tsMs,
                severity = band.severity(),
                escalated = band.severity() == AlarmSeverity.CRITICAL,
                message = thresholdMessage(band, bgMgdl, thresholds),
            )
        }
        return breach
    }
}
