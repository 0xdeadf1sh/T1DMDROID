package com.t1dm.alerts

import com.t1dm.core.model.AlertBand

/**
 * The presentation-layer silence for the deterministic alarm (SPEC.private.md §3.6-A). It mirrors
 * DEATH mode's suppression but is TIME-BOUNDED and per-episode: a snooze/dismiss NEVER touches the
 * pure [AlarmEngine] — the engine keeps firing internally — it only gates whether the notifier
 * ANNOUNCES a still-active breach. Every rule here is a §3.6 safety guard and is test-pinned:
 *
 *  - C1  a snooze is TIME-BOUNDED and auto-expires ([silences] false once `nowMs >= untilMs`); only
 *        DEATH is a permanent silence.
 *  - C2  ESCALATION PIERCES: a live alarm whose severity is worse — or whose excursion crosses to the
 *        other side (low↔high) — than what was silenced is NOT suppressed; it re-fires.
 *  - C3  a dismiss silences only the CURRENT breach: [pruned] drops the entry once its kind clears, so
 *        a genuinely new breach re-alerts.
 *  - C5  over-temperature is NEVER snoozable — it can only ever become MORE annoying, never less safe.
 *
 * Immutable: mutations return a new [SnoozeState]. Pure (no Android, no clock read — time enters via
 * `nowMs`), so the whole gate is host-testable.
 */
data class SnoozeState(val entries: Map<AlarmKind, SnoozeEntry> = emptyMap()) {

    /**
     * Whether [alarm] is currently silenced at [nowMs]. False (i.e. the alarm PRESENTS) unless a live,
     * matching, non-escalated snooze/dismiss entry covers it. Over-temperature is never silenced (C5).
     */
    fun silences(alarm: ActiveAlarm, nowMs: Long): Boolean {
        if (alarm is OverTemperature) return false                       // C5 — never snoozable
        val e = entries[alarm.kind()] ?: return false
        if (!e.dismiss && nowMs >= e.untilMs) return false               // C1 — timed snooze auto-expires
        if (alarm.severity.ordinal > e.severity.ordinal) return false    // C2 — escalated severity pierces
        val liveSide = (alarm as? ThresholdBreach)?.band?.side()
        val silencedSide = e.band?.side()
        // C2 — a threshold that crossed to the OTHER excursion (e.g. was LOW, now HIGH) pierces too.
        if (liveSide != null && silencedSide != null && liveSide != silencedSide) return false
        return true
    }

    /** Timed snooze of [alarm] until [untilMs] (C1). Replaces any prior entry for the same kind. */
    fun snooze(alarm: ActiveAlarm, untilMs: Long): SnoozeState = put(alarm, untilMs, dismiss = false)

    /** Dismiss (acknowledge) the CURRENT breach of [alarm]'s kind (C3): silenced until the breach
     *  clears (see [pruned]) or escalates (C2). DEFENSE-IN-DEPTH: a dismiss of a CRITICAL/urgent tier
     *  (or over-temp) is a NO-OP — dismiss-until-clear is offered ONLY on WARNING tiers (see
     *  [isDismissable]). An urgent condition can be quieted only by an auto-expiring SNOOZE or DEATH,
     *  never permanently; this holds even if a forged intent reaches here. */
    fun dismiss(alarm: ActiveAlarm): SnoozeState =
        if (alarm.isDismissable()) put(alarm, Long.MAX_VALUE, dismiss = true) else this

    private fun put(alarm: ActiveAlarm, untilMs: Long, dismiss: Boolean): SnoozeState {
        if (alarm is OverTemperature) return this                        // C5 — never snoozable
        val band = (alarm as? ThresholdBreach)?.band
        return copy(entries = entries + (alarm.kind() to SnoozeEntry(alarm.severity, band, untilMs, dismiss)))
    }

    /**
     * Prune entries whose kind is no longer active (a dismissed/snoozed breach that CLEARED — C3, so a
     * genuinely new breach re-alerts) and any timed snooze past its expiry. Call on each engine-state
     * change. Pure — it reads the live [state] but never touches the engine.
     */
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

/** One kind's silence record: the severity + band that were silenced (for the C2 escalation-pierce),
 *  when the timed snooze expires ([untilMs]; `Long.MAX_VALUE` for a dismiss), and whether it is a
 *  dismiss (until-clear) rather than a timed snooze. */
data class SnoozeEntry(
    val severity: AlarmSeverity,
    val band: AlertBand?,
    val untilMs: Long,
    val dismiss: Boolean,
)

/** The orthogonal deterministic sub-alarms, used to key the snooze store. */
enum class AlarmKind { THRESHOLD, SIGNAL_LOSS, WEAK_SIGNAL, OVER_TEMPERATURE }

fun ActiveAlarm.kind(): AlarmKind = when (this) {
    is ThresholdBreach -> AlarmKind.THRESHOLD
    is SignalLoss -> AlarmKind.SIGNAL_LOSS
    is WeakSignal -> AlarmKind.WEAK_SIGNAL
    is OverTemperature -> AlarmKind.OVER_TEMPERATURE
}

/**
 * Whether a "Dismiss" (acknowledge-until-clear) affordance may be offered for this alarm: ONLY the
 * WARNING glucose/signal tiers. CRITICAL/urgent tiers are Snooze-only (Dexcom-style — an urgent
 * condition may never be quieted permanently, only by an auto-expiring snooze or DEATH), and
 * over-temperature is never dismissable. A "Snooze" affordance, by contrast, is offered on every
 * glucose/signal tier because it is TIME-BOUNDED (C1).
 */
fun ActiveAlarm.isDismissable(): Boolean =
    this !is OverTemperature && severity == AlarmSeverity.WARNING

/** Which side of range a band sits on — a low↔high crossing pierces a snooze (C2). */
private enum class ExcursionSide { LOW, HIGH }

private fun AlertBand.side(): ExcursionSide? = when (this) {
    AlertBand.URGENT_LOW, AlertBand.LOW -> ExcursionSide.LOW
    AlertBand.HIGH, AlertBand.URGENT_HIGH -> ExcursionSide.HIGH
    AlertBand.IN_RANGE -> null
}

/**
 * The single §3.6 presentation gate: from the live [AlarmState], the DEATH-mode [suppressed] flag, and
 * the [snooze] state, produce the AlarmState the notifier may ANNOUNCE. Glucose/signal are dropped when
 * DEATH-suppressed OR snoozed/dismissed (and not escalated past what was silenced); over-temperature is
 * ALWAYS carried through — it is D4-exempt from DEATH and C5-exempt from snooze. This is pure and
 * host-testable; the engine/breach detection are untouched — only presentation is gated.
 */
fun AlarmState.visibleAfterGates(suppressed: Boolean, snooze: SnoozeState, nowMs: Long): AlarmState =
    AlarmState(
        threshold = threshold?.takeUnless { suppressed || snooze.silences(it, nowMs) },
        signalLoss = signalLoss?.takeUnless { suppressed || snooze.silences(it, nowMs) },
        overTemperature = overTemperature,
        weakSignal = weakSignal?.takeUnless { suppressed || snooze.silences(it, nowMs) },
    )
