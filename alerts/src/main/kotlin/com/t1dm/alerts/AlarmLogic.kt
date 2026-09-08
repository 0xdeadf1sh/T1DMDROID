package com.t1dm.alerts

import com.t1dm.core.model.isRealMeasurement
import com.t1dm.core.model.AlertBand
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance

/** §3.6-A: only a MEASURED, warmup-cleared reading may raise/clear an alarm; gapfill can't. */
internal fun CgmReading.isEligibleMeasured(): Boolean =
    isRealMeasurement(provenance, flag) && bgMgdl != null

internal fun AlertBand.severity(): AlarmSeverity = when (this) {
    AlertBand.URGENT_LOW, AlertBand.URGENT_HIGH -> AlarmSeverity.CRITICAL
    AlertBand.LOW, AlertBand.HIGH, AlertBand.IN_RANGE -> AlarmSeverity.WARNING
}

internal fun isLowOrFalling(reading: CgmReading, config: AlarmConfig): Boolean {
    val low = reading.bgMgdl
        ?.let { config.thresholds.bandFor(it) }
        ?.let { it == AlertBand.URGENT_LOW || it == AlertBand.LOW }
        ?: false
    val falling = reading.trendTenthsPerMin
        ?.let { it <= config.fallingTrendThresholdTenths }
        ?: false
    return low || falling
}

internal fun thresholdMessage(band: AlertBand, bgMgdl: Int, t: AlertThresholds): String = when (band) {
    AlertBand.URGENT_LOW -> "$bgMgdl mg/dL, below ${t.urgentLowMgdl}"
    AlertBand.LOW -> "$bgMgdl mg/dL, below ${t.lowMgdl}"
    AlertBand.HIGH -> "$bgMgdl mg/dL, at/above ${t.highMgdl}"
    AlertBand.URGENT_HIGH -> "$bgMgdl mg/dL, at/above ${t.urgentHighMgdl}"
    AlertBand.IN_RANGE -> "$bgMgdl mg/dL"
}

internal fun overTempMessage(tempC: Double, clearC: Double): String =
    "%.1f°C, paused until below %.0f°C".format(tempC, clearC)

internal fun weakSignalMessage(rssiDbm: Int, thresholdDbm: Int): String =
    "$rssiDbm dBm (≤ $thresholdDbm) — readings may drop"

internal fun signalLossMessage(windowMin: Int, escalated: Boolean, lastBgMgdl: Int?): String {
    val last = lastBgMgdl?.let { " Last $it mg/dL." } ?: ""
    return if (escalated) {
        "No reading for over $windowMin min — last was low/falling.$last"
    } else {
        "No reading for over $windowMin min.$last"
    }
}
