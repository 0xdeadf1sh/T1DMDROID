package com.t1dm.feature.settings

import androidx.compose.runtime.Composable

/**
 * Settings → Signal & freshness (Phase 7C item 14; §3.6-A/D). Two safety windows:
 *  - the **loss-of-signal** window (minutes with no MEASURED reading before the model-free alarm
 *    fires) and its **escalated** shortening when the last real reading was low or falling;
 *  - the **dosing staleness gate**: the calculator refuses to recommend a dose off an anchor older
 *    than this many minutes (§3.6-D freshness rail).
 *
 * All are user-set; the loss windows have a 1-minute floor only. Pure/stateless.
 */
@Composable
fun SignalSafetyScreen(
    lossMin: Int,
    lossEscalatedMin: Int,
    dosingStaleMin: Int,
    weakSignalEnabled: Boolean,
    weakSignalDbm: Int,
    weakSignalSustainMin: Int,
    onSetLoss: (lossMin: Int, escalatedMin: Int) -> Unit,
    onSetDosingStale: (min: Int) -> Unit,
    onSetWeakSignal: (enabled: Boolean, dbm: Int, sustainMin: Int) -> Unit,
) {
    SettingsScaffold(SettingsScreenKey.SIGNAL) {
        SettingsSectionHeader("Loss of signal")
        SettingsNote("Silence before the alarm; escalated if last was low/falling")
        IntStepper(signalLossWindow, lossMin, "min", step = 1, min = 1) { onSetLoss(it, lossEscalatedMin) }
        IntStepper(signalLossEscalated, lossEscalatedMin, "min", step = 1, min = 1) { onSetLoss(lossMin, it) }

        SettingsSectionHeader("Weak signal")
        SettingsNote("Warn at or below this for the sustain window")
        ToggleRow(signalWeakEnabled, weakSignalEnabled) { onSetWeakSignal(it, weakSignalDbm, weakSignalSustainMin) }
        IntStepper(signalWeakDbm, weakSignalDbm, "dBm", step = 5, min = -110, max = -40) {
            onSetWeakSignal(weakSignalEnabled, it, weakSignalSustainMin)
        }
        IntStepper(signalWeakSustain, weakSignalSustainMin, "min", step = 1, min = 0) {
            onSetWeakSignal(weakSignalEnabled, weakSignalDbm, it)
        }

        SettingsSectionHeader("Dosing freshness gate")
        DangerBanner("Calculator refuses on readings older than this")
        IntStepper(signalDosingStale, dosingStaleMin, "min", step = 1, min = 1) { onSetDosingStale(it) }
    }
}

// ── search index (see SettingsIndex.kt) ───────────────────────────────────────────────────────────

private val signalLossWindow = SettingsKnob(
    id = "signal.loss_window",
    screen = SettingsScreenKey.SIGNAL,
    section = "Loss of signal",
    label = "Loss-of-signal window",
    subtitle = "Minutes with no measured reading before the loss-of-signal alarm fires",
    synonyms = listOf(
        "loss of signal", "signal loss", "no signal", "no readings", "disconnected", "dropout",
        "gap", "silence", "missing data", "timeout", "sensor lost", "bluetooth",
    ),
)

private val signalLossEscalated = SettingsKnob(
    id = "signal.loss_escalated",
    screen = SettingsScreenKey.SIGNAL,
    section = "Loss of signal",
    label = "Escalated window",
    subtitle = "The shorter window used when the last real reading was low or falling",
    synonyms = listOf(
        "escalated", "escalation", "shorter window", "falling", "low and falling", "urgent gap",
        "loss of signal", "signal loss",
    ),
)

private val signalWeakEnabled = SettingsKnob(
    id = "signal.weak_enabled",
    screen = SettingsScreenKey.SIGNAL,
    section = "Weak signal",
    label = "Weak-signal alarm",
    subtitle = "Warn while the sensor's radio link is weak but still delivering",
    synonyms = listOf(
        "weak signal", "rssi", "dbm", "radio", "reception", "range", "bars", "link quality",
        "bluetooth", "ble", "far away", "out of range",
    ),
)

private val signalWeakDbm = SettingsKnob(
    id = "signal.weak_dbm",
    screen = SettingsScreenKey.SIGNAL,
    section = "Weak signal",
    label = "Weak below",
    subtitle = "The dBm at or under which the link counts as weak (lower is weaker)",
    synonyms = listOf("dbm", "rssi", "signal strength", "weak", "threshold", "radio", "reception", "bars"),
)

private val signalWeakSustain = SettingsKnob(
    id = "signal.weak_sustain",
    screen = SettingsScreenKey.SIGNAL,
    section = "Weak signal",
    label = "Sustained for",
    subtitle = "How long the signal must stay weak before the warning is raised",
    synonyms = listOf("sustain", "sustained", "duration", "hold", "debounce", "how long", "weak signal", "rssi"),
)

private val signalDosingStale = SettingsKnob(
    id = "signal.dosing_stale",
    screen = SettingsScreenKey.SIGNAL,
    section = "Dosing freshness gate",
    label = "Refuse dosing if anchor older than",
    subtitle = "The freshness rail: no recommendation off a reading older than this",
    synonyms = listOf(
        "freshness", "stale", "staleness", "anchor", "age", "old reading", "gate", "rail",
        "refuse", "dosing", "bolus", "calculator", "safety", "interpolated",
    ),
)

internal val settingsSignalKnobs = listOf(
    signalLossWindow,
    signalLossEscalated,
    signalWeakEnabled,
    signalWeakDbm,
    signalWeakSustain,
    signalDosingStale,
)
