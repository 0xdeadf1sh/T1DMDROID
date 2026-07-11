package com.t1dm.feature.settings

import androidx.compose.runtime.Composable

/**
 * Settings → Device temperature alert (F7). A standalone alarm on the phone's BATTERY-sensor temperature,
 * distinct from the glucose alarms: it fires at [alertC] and clears once the reading falls back to
 * [clearC], the gap giving hysteresis so it does not chatter around a single value. Uniquely among the
 * alarms it is EXEMPT from DEATH mode's global suppression (D4) — an overheating phone is a hardware
 * hazard the app will not silence. Marking it critical routes it to the high-priority channel. Shown and
 * stored in °C. Pure/stateless.
 */
@Composable
fun DeviceTempAlertScreen(
    enabled: Boolean,
    alertC: Double,
    clearC: Double,
    critical: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    onSetAlertC: (Double) -> Unit,
    onSetClearC: (Double) -> Unit,
    onSetCritical: (Boolean) -> Unit,
) {
    SettingsScaffold("Device temperature") {
        SettingsNote(
            "Watches the phone's battery-sensor temperature — not blood glucose, and never a replacement " +
                "for the glucose alarm. It fires at the alert level and clears once the phone cools to the " +
                "clear-below level; keep clear-below under the alert level for hysteresis. A change takes " +
                "effect the next time the monitoring service starts.",
        )
        ToggleRow("Enable device-temperature alarm", enabled) { onSetEnabled(it) }
        DoubleStepper("Alert above", alertC, "°C", step = 0.5, min = 0.0) { onSetAlertC(it) }
        DoubleStepper("Clear below", clearC, "°C", step = 0.5, min = 0.0) { onSetClearC(it) }
        ToggleRow(
            "Raise as critical",
            critical,
            "Route to the high-priority alarm channel rather than a warning",
        ) { onSetCritical(it) }
    }
}
