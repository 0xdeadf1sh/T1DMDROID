package com.t1dm.alerts

import com.t1dm.core.model.AlertBand
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The §3.6 presentation-gate safety guards (C1–C5), test-pinned. The snooze/dismiss never touches the
 * engine; these prove the pure [SnoozeState] gate and [visibleAfterGates] behave exactly as specced:
 * time-bounded silence, escalation-pierce, dismiss-until-clear, DEATH-suppresses-all, over-temp-exempt.
 */
class SnoozeStateTest {

    private fun lowBreach() = ThresholdBreach(AlertBand.LOW, 65, 0L, AlarmSeverity.WARNING, false, "low")
    private fun urgentLowBreach() = ThresholdBreach(AlertBand.URGENT_LOW, 45, 0L, AlarmSeverity.CRITICAL, true, "urgent low")
    private fun highBreach() = ThresholdBreach(AlertBand.HIGH, 200, 0L, AlarmSeverity.WARNING, false, "high")
    private fun loss(severity: AlarmSeverity) =
        SignalLoss(0L, 20, null, null, severity, severity == AlarmSeverity.CRITICAL, "loss")
    private fun overTemp() = OverTemperature(45.0, 0L, 44.0, 41.0, AlarmSeverity.WARNING, false, "hot")

    // ── C1 — a snooze is TIME-BOUNDED and auto-expires ─────────────────────────────────────────────

    @Test
    fun `snooze suppresses within the window then auto-expires`() {
        val s = SnoozeState.NONE.snooze(lowBreach(), untilMs = 15 * MIN)
        assertTrue("silenced within the window", s.silences(lowBreach(), nowMs = 5 * MIN))
        assertFalse("expired at the boundary", s.silences(lowBreach(), nowMs = 15 * MIN))
        assertFalse("stays expired after", s.silences(lowBreach(), nowMs = 20 * MIN))
    }

    // ── C2 — escalation PIERCES the snooze ─────────────────────────────────────────────────────────

    @Test
    fun `a worse severity pierces an active snooze`() {
        val s = SnoozeState.NONE.snooze(lowBreach(), untilMs = 15 * MIN)
        assertTrue(s.silences(lowBreach(), nowMs = 5 * MIN))
        assertFalse("LOW→URGENT_LOW escalation must re-fire", s.silences(urgentLowBreach(), nowMs = 5 * MIN))
    }

    @Test
    fun `crossing to the other excursion pierces an active snooze`() {
        val s = SnoozeState.NONE.snooze(lowBreach(), untilMs = 15 * MIN)
        assertFalse("LOW→HIGH is a new condition and must re-fire", s.silences(highBreach(), nowMs = 5 * MIN))
    }

    @Test
    fun `a signal-loss escalation to critical pierces its snooze`() {
        val s = SnoozeState.NONE.snooze(loss(AlarmSeverity.WARNING), untilMs = 15 * MIN)
        assertTrue(s.silences(loss(AlarmSeverity.WARNING), nowMs = 5 * MIN))
        assertFalse(s.silences(loss(AlarmSeverity.CRITICAL), nowMs = 5 * MIN))
    }

    @Test
    fun `a less severe same-side alarm stays silenced under a snooze of the worse one`() {
        val s = SnoozeState.NONE.snooze(urgentLowBreach(), untilMs = 15 * MIN)
        assertTrue("snoozing URGENT_LOW also covers the milder LOW", s.silences(lowBreach(), nowMs = 5 * MIN))
    }

    // ── Option 2 — Dismiss is WARNING-only; CRITICAL/urgent tiers are Snooze-only ──────────────────

    @Test
    fun `only WARNING glucose and signal tiers are dismissable`() {
        assertTrue("WARNING low is dismissable", lowBreach().isDismissable())
        assertTrue("WARNING high is dismissable", highBreach().isDismissable())
        assertTrue("WARNING signal-loss is dismissable", loss(AlarmSeverity.WARNING).isDismissable())
        assertFalse("urgent-low (CRITICAL) is NOT dismissable — Snooze only", urgentLowBreach().isDismissable())
        assertFalse("critical signal-loss is NOT dismissable", loss(AlarmSeverity.CRITICAL).isDismissable())
        assertFalse("over-temperature is never dismissable", overTemp().isDismissable())
    }

    @Test
    fun `dismissing a CRITICAL urgent breach is a no-op — no unbounded silence of an urgent tier`() {
        val s = SnoozeState.NONE.dismiss(urgentLowBreach())
        assertTrue("no entry recorded", s.entries.isEmpty())
        assertFalse("the urgent alarm still presents", s.silences(urgentLowBreach(), nowMs = 5 * MIN))
    }

    @Test
    fun `a snooze still silences a CRITICAL urgent breach within its window`() {
        val s = SnoozeState.NONE.snooze(urgentLowBreach(), untilMs = 15 * MIN)
        assertTrue("urgent low is snoozable (time-bounded)", s.silences(urgentLowBreach(), nowMs = 5 * MIN))
        assertFalse("but the snooze auto-expires", s.silences(urgentLowBreach(), nowMs = 15 * MIN))
    }

    // ── C3 — a dismiss silences only the CURRENT breach; a new one re-alerts ───────────────────────

    @Test
    fun `dismiss suppresses the current breach then a new breach after a clear re-alerts`() {
        var s = SnoozeState.NONE.dismiss(lowBreach())
        assertTrue("dismiss ignores the clock", s.silences(lowBreach(), nowMs = 1_000 * MIN))
        // The breach clears (in-range reading) ⇒ prune drops the dismiss entry.
        s = s.pruned(AlarmState.CLEAR, nowMs = 1_000 * MIN)
        assertFalse("a genuinely new breach must re-alert", s.silences(lowBreach(), nowMs = 2_000 * MIN))
    }

    @Test
    fun `a dismiss is pierced by escalation just like a snooze`() {
        val s = SnoozeState.NONE.dismiss(lowBreach())
        assertFalse(s.silences(urgentLowBreach(), nowMs = 5 * MIN))
    }

    @Test
    fun `prune keeps a still-active dismissed episode`() {
        val s = SnoozeState.NONE.dismiss(lowBreach())
        val active = AlarmState(threshold = lowBreach(), signalLoss = null, overTemperature = null)
        assertTrue(s.pruned(active, nowMs = 5 * MIN).silences(lowBreach(), nowMs = 5 * MIN))
    }

    @Test
    fun `prune drops an expired timed snooze`() {
        val s = SnoozeState.NONE.snooze(lowBreach(), untilMs = 10 * MIN)
        val active = AlarmState(threshold = lowBreach(), signalLoss = null, overTemperature = null)
        assertTrue(s.pruned(active, nowMs = 5 * MIN).entries.isNotEmpty())
        assertTrue(s.pruned(active, nowMs = 10 * MIN).entries.isEmpty())
    }

    // ── C5 — over-temperature is NEVER snoozable ───────────────────────────────────────────────────

    @Test
    fun `over-temperature can never be snoozed or dismissed`() {
        val snoozed = SnoozeState.NONE.snooze(overTemp(), untilMs = 15 * MIN)
        val dismissed = SnoozeState.NONE.dismiss(overTemp())
        assertTrue(snoozed.entries.isEmpty())
        assertTrue(dismissed.entries.isEmpty())
        assertFalse(snoozed.silences(overTemp(), nowMs = 0L))
    }

    // ── visibleAfterGates — the single presentation gate the notifier uses ─────────────────────────

    @Test
    fun `DEATH suppresses glucose and signal but never over-temperature`() {
        val state = AlarmState(lowBreach(), loss(AlarmSeverity.WARNING), overTemp())
        val v = state.visibleAfterGates(suppressed = true, snooze = SnoozeState.NONE, nowMs = 0L)
        assertNull(v.threshold)
        assertNull(v.signalLoss)
        assertNotNull("over-temp is D4-exempt from DEATH", v.overTemperature)
    }

    @Test
    fun `a snooze gates presentation within the window then reveals it after expiry`() {
        val state = AlarmState(lowBreach(), null, null)
        val snoozed = SnoozeState.NONE.snooze(lowBreach(), untilMs = 15 * MIN)
        assertNull(state.visibleAfterGates(false, snoozed, nowMs = 5 * MIN).threshold)
        assertNotNull(state.visibleAfterGates(false, snoozed, nowMs = 15 * MIN).threshold)
    }

    @Test
    fun `a snooze never gates over-temperature presentation`() {
        val state = AlarmState(null, null, overTemp())
        // Even if a stray entry somehow existed, silences() returns false for over-temp.
        val v = state.visibleAfterGates(false, SnoozeState.NONE, nowMs = 5 * MIN)
        assertNotNull(v.overTemperature)
    }
}
