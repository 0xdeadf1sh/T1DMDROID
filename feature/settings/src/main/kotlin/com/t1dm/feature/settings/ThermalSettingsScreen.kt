package com.t1dm.feature.settings

import androidx.compose.runtime.Composable

/**
 * Settings → Thermal gate (F6, locked decisions D1/D3/D4). When enabled, inference is paused whenever the
 * phone's BATTERY-sensor temperature reaches the threshold, and resumes once it has cooled by a fixed
 * hysteresis margin (a truer die/APU temperature is unreadable on this device — D1). Enabled by default
 * (D3). The gate stays ACTIVE even in DEATH mode (D4): running the NPU on a phone that is already too hot
 * is a hardware hazard, not a glucose-safety alarm, so DEATH's fail-open override does not lift it.
 * Temperatures are shown and stored in °C. Pure/stateless.
 */
@Composable
fun ThermalSettingsScreen(
    enabled: Boolean,
    maxTempC: Double,
    warnMarginC: Double,
    onSetEnabled: (Boolean) -> Unit,
    onSetMaxTempC: (Double) -> Unit,
    onSetWarnMarginC: (Double) -> Unit,
) {
    SettingsScaffold("Thermal gate") {
        SettingsNote(
            "Pauses forecasting when the phone's battery-sensor temperature reaches the threshold, and " +
                "resumes once it cools. Protects the device on sustained NPU load; it stays active even " +
                "in DEATH mode.",
        )
        ToggleRow("Enable thermal gate", enabled) { onSetEnabled(it) }
        DoubleStepper("Pause inference at", maxTempC, "°C", step = 0.5, min = 0.0) { onSetMaxTempC(it) }
        DoubleStepper(
            "Warn within",
            warnMarginC,
            "°C of threshold",
            step = 0.5,
            min = 0.0,
        ) { onSetWarnMarginC(it) }
    }
}
