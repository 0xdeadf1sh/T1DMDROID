package com.t1dm.feature.settings

import androidx.compose.runtime.Composable

/**
 * Settings → Low-power mode (Phase 7C item 14; progress.md Q9 — default entry 20 %,
 * configurable). When the phone crosses the battery floor (or, optionally, the OS battery-saver turns
 * on) the watch push suspends after one final flagged frame; the CGM scan and alarms keep running.
 * Pure/stateless.
 */
@Composable
fun PowerSettingsScreen(
    enabled: Boolean,
    percent: Int,
    useOsSaver: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    onSetPercent: (Int) -> Unit,
    onSetUseOsSaver: (Boolean) -> Unit,
) {
    SettingsScaffold("Low-power mode") {
        SettingsNote(
            "Low-power mode only affects the optional watch push — it pauses to save battery. Passive " +
                "CGM scanning, the model-free alarm, and forecasting are never suspended.",
        )
        ToggleRow("Enable low-power suspension", enabled) { onSetEnabled(it) }
        IntStepper("Suspend at battery level", percent, "%", step = 5, min = 0, max = 100) { onSetPercent(it) }
        ToggleRow(
            "Also follow OS battery-saver",
            useOsSaver,
            "Suspend whenever the system battery-saver is on, regardless of level",
        ) { onSetUseOsSaver(it) }
    }
}
