package com.t1dm.alerts

import com.t1dm.core.model.AlertBand
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CgmReading

/**
 * The model-free threshold alarm (§3.6-A). It classifies each MEASURED reading
 * directly against [AlertThresholds] — no forecast, backend, or Rust decode can suppress it.
 *
 * State transitions obey the provenance rule strictly:
 *  - only an eligible MEASURED reading (see [isEligibleMeasured]) can change [breach];
 *  - a breach clears ONLY on a MEASURED in-range reading;
 *  - INTERPOLATED / WARMUP / INVALID readings are inert — they neither raise nor clear.
 *
 * Deterministic and clock-free: callers feed readings; there is nothing time-dependent here.
 */
class ThresholdAlarm(private var thresholds: AlertThresholds) {

    var breach: ThresholdBreach? = null
        private set

    /** Live-swap the classification bounds WITHOUT touching [breach] (§3.6-A). A raised threshold must
     *  NOT instantly clear a genuine active low: the current breach is retained and the NEXT eligible
     *  MEASURED reading re-classifies it against the new bounds. A config edit alone never silences a
     *  standing excursion. */
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
