package com.t1dm.alerts

import com.t1dm.core.model.AlertThresholds

/** Defaults unbounded (§3.6-A). fallingTrendThresholdTenths: 0.1 mg/dL/min, negative falling. */
data class AlarmConfig(
    val thresholds: AlertThresholds,
    val lossMin: Int = 20,
    val lossEscalatedMin: Int = 12,
    val fallingTrendThresholdTenths: Int = -10,
    val repeatCadenceMin: Int = 5,
    val minActuationIntervalMin: Int = 5,
    val tickIntervalMs: Long = 60_000L,
    val overTempEnabled: Boolean = true,
    val overTempAlertC: Double = 44.0,
    val overTempClearC: Double = 41.0,
    val overTempSeverity: AlarmSeverity = AlarmSeverity.WARNING,
    val weakSignalEnabled: Boolean = true,
    val weakSignalDbm: Int = -90,
    val weakSignalSustainMin: Int = 3,
) {
    companion object {
        val DEFAULT = AlarmConfig(
            thresholds = AlertThresholds(
                urgentLowMgdl = 55,
                lowMgdl = 70,
                highMgdl = 180,
                urgentHighMgdl = 250,
            ),
        )
    }
}
