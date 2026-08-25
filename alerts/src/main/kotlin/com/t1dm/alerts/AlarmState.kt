package com.t1dm.alerts

import com.t1dm.core.model.AlertBand

/** Order is load-bearing: CRITICAL must sort above WARNING. */
enum class AlarmSeverity { WARNING, CRITICAL }

sealed interface ActiveAlarm {
    val severity: AlarmSeverity
    val message: String

    /** Warrants the louder, DND-bypassing presentation. */
    val escalated: Boolean
}

data class ThresholdBreach(
    val band: AlertBand,
    val bgMgdl: Int,
    val atMs: Long,
    override val severity: AlarmSeverity,
    override val escalated: Boolean,
    override val message: String,
) : ActiveAlarm

data class SignalLoss(
    val lastReadingMs: Long,
    val windowMin: Int,
    val lastBand: AlertBand?,
    val lastBgMgdl: Int?,
    override val severity: AlarmSeverity,
    override val escalated: Boolean,
    override val message: String,
) : ActiveAlarm

/** Link RSSI low while readings still arrive; distinct from [SignalLoss]. */
data class WeakSignal(
    val rssiDbm: Int,
    val thresholdDbm: Int,
    val atMs: Long,
    override val severity: AlarmSeverity,
    override val escalated: Boolean,
    override val message: String,
) : ActiveAlarm

/** Device health, not a glucose condition: exempt from DEATH suppression (D4). */
data class OverTemperature(
    val tempC: Double,
    val atMs: Long,
    val alertC: Double,
    val clearC: Double,
    override val severity: AlarmSeverity,
    override val escalated: Boolean,
    override val message: String,
) : ActiveAlarm

data class AlarmState(
    val threshold: ThresholdBreach?,
    val signalLoss: SignalLoss?,
    val overTemperature: OverTemperature? = null,
    val weakSignal: WeakSignal? = null,
) {
    val isActive: Boolean
        get() = threshold != null || signalLoss != null || overTemperature != null || weakSignal != null

    val alarms: List<ActiveAlarm> get() = listOfNotNull(threshold, signalLoss, weakSignal, overTemperature)

    val primary: ActiveAlarm?
        get() = alarms.maxWithOrNull(
            compareBy<ActiveAlarm>({ it.severity.ordinal }, { kindRank(it) }),
        )

    companion object {
        val CLEAR = AlarmState(threshold = null, signalLoss = null, overTemperature = null, weakSignal = null)

        private fun kindRank(a: ActiveAlarm): Int = when (a) {
            is ThresholdBreach -> 3
            is SignalLoss -> 2
            is WeakSignal -> 1
            is OverTemperature -> 0
        }
    }
}
