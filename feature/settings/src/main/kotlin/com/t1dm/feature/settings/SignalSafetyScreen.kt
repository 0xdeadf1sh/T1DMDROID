package com.t1dm.feature.settings

import androidx.compose.runtime.Composable

/** The loss windows count minutes with no MEASURED reading; they have a 1-minute floor only. */
@Composable
fun SignalSafetyScreen(
    lossMin: Int,
    lossEscalatedMin: Int,
    weakSignalEnabled: Boolean,
    weakSignalDbm: Int,
    weakSignalSustainMin: Int,
    onSetLoss: (lossMin: Int, escalatedMin: Int) -> Unit,
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
    }
}

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

internal val settingsSignalKnobs = listOf(
    signalLossWindow,
    signalLossEscalated,
    signalWeakEnabled,
    signalWeakDbm,
    signalWeakSustain,
)
