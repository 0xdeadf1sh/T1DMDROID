package com.t1dm.alerts

import com.t1dm.core.model.AlertBand

/** Presentation gate only: a snooze never touches [AlarmEngine], which keeps firing. C1 a snooze is
 *  time-bounded; C2 worse severity or a low↔high crossing pierces it; C3 a dismiss holds only until
 *  the breach clears; C5 over-temperature is never snoozable. */
data class SnoozeState(val entries: Map<AlarmKind, SnoozeEntry> = emptyMap()) {

    fun silences(alarm: ActiveAlarm, nowMs: Long): Boolean {
        if (alarm is OverTemperature) return false                       // C5
        val e = entries[alarm.kind()] ?: return false
        if (!e.dismiss && nowMs >= e.untilMs) return false               // C1
        if (alarm.severity.ordinal > e.severity.ordinal) return false    // C2
        val liveSide = (alarm as? ThresholdBreach)?.band?.side()
        val silencedSide = e.band?.side()
        // C2
        if (liveSide != null && silencedSide != null && liveSide != silencedSide) return false
        return true
    }

    fun snooze(alarm: ActiveAlarm, untilMs: Long): SnoozeState = put(alarm, untilMs, dismiss = false)

    /** Dismiss until the breach clears (C3). A no-op on urgent tiers: an urgent condition may never
     *  be quieted permanently, even by a forged intent. */
    fun dismiss(alarm: ActiveAlarm): SnoozeState =
        if (alarm.isDismissable()) put(alarm, Long.MAX_VALUE, dismiss = true) else this

    private fun put(alarm: ActiveAlarm, untilMs: Long, dismiss: Boolean): SnoozeState {
        if (alarm is OverTemperature) return this                        // C5
        val band = (alarm as? ThresholdBreach)?.band
        return copy(entries = entries + (alarm.kind() to SnoozeEntry(alarm.severity, band, untilMs, dismiss)))
    }

    fun pruned(state: AlarmState, nowMs: Long): SnoozeState {
        if (entries.isEmpty()) return this
        val active = state.alarms.map { it.kind() }.toSet()
        val kept = entries.filter { (kind, e) -> kind in active && (e.dismiss || nowMs < e.untilMs) }
        return if (kept.size == entries.size) this else copy(entries = kept)
    }

    companion object {
        val NONE = SnoozeState()
    }
}

/** [untilMs] is `Long.MAX_VALUE` for a dismiss. */
data class SnoozeEntry(
    val severity: AlarmSeverity,
    val band: AlertBand?,
    val untilMs: Long,
    val dismiss: Boolean,
)

enum class AlarmKind { THRESHOLD, SIGNAL_LOSS, WEAK_SIGNAL, OVER_TEMPERATURE }

fun ActiveAlarm.kind(): AlarmKind = when (this) {
    is ThresholdBreach -> AlarmKind.THRESHOLD
    is SignalLoss -> AlarmKind.SIGNAL_LOSS
    is WeakSignal -> AlarmKind.WEAK_SIGNAL
    is OverTemperature -> AlarmKind.OVER_TEMPERATURE
}

/** Only WARNING tiers may be dismissed: an urgent condition may never be quieted permanently. */
fun ActiveAlarm.isDismissable(): Boolean =
    this !is OverTemperature && severity == AlarmSeverity.WARNING

private enum class ExcursionSide { LOW, HIGH }

private fun AlertBand.side(): ExcursionSide? = when (this) {
    AlertBand.URGENT_LOW, AlertBand.LOW -> ExcursionSide.LOW
    AlertBand.HIGH, AlertBand.URGENT_HIGH -> ExcursionSide.HIGH
    AlertBand.IN_RANGE -> null
}

/** The §3.6 presentation gate. Over-temperature always passes: exempt from DEATH (D4) and from
 *  snooze (C5). */
fun AlarmState.visibleAfterGates(suppressed: Boolean, snooze: SnoozeState, nowMs: Long): AlarmState =
    AlarmState(
        threshold = threshold?.takeUnless { suppressed || snooze.silences(it, nowMs) },
        signalLoss = signalLoss?.takeUnless { suppressed || snooze.silences(it, nowMs) },
        overTemperature = overTemperature,
        weakSignal = weakSignal?.takeUnless { suppressed || snooze.silences(it, nowMs) },
    )
