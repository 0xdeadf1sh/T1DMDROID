package com.t1dm.feature.settings

import androidx.compose.runtime.Composable

/**
 * Settings → Low-power mode (Phase 7C item 14; Q9 — default entry 20 %,
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
    SettingsScaffold(SettingsScreenKey.POWER) {
        SettingsNote("Pauses only the watch push")
        ToggleRow(powerEnabled, enabled) { onSetEnabled(it) }
        IntStepper(powerPercent, percent, "%", step = 5, min = 0, max = 100) { onSetPercent(it) }
        ToggleRow(powerOsSaver, useOsSaver) { onSetUseOsSaver(it) }
    }
}

// ── search index (see SettingsIndex.kt) ───────────────────────────────────────────────────────────

private const val POWER_SECTION = "Low-power mode"

private val powerEnabled = SettingsKnob(
    id = "power.enabled",
    screen = SettingsScreenKey.POWER,
    section = POWER_SECTION,
    label = "Enable low-power suspension",
    subtitle = "Pause the watch push on low battery",
    synonyms = listOf(
        "low power", "battery", "battery saver", "power saving", "suspend", "conserve", "economy",
        "watch push", "energy", "drain",
    ),
)

private val powerPercent = SettingsKnob(
    id = "power.percent",
    screen = SettingsScreenKey.POWER,
    section = POWER_SECTION,
    label = "Suspend at battery level",
    subtitle = "The battery percentage at which the watch push pauses",
    synonyms = listOf("battery", "percent", "percentage", "level", "threshold", "charge", "20%", "low battery"),
)

private val powerOsSaver = SettingsKnob(
    id = "power.os_saver",
    screen = SettingsScreenKey.POWER,
    section = POWER_SECTION,
    label = "Also follow OS battery-saver",
    subtitle = "Also suspend when the system battery-saver is on, regardless of level",
    synonyms = listOf(
        "os", "system", "android", "battery saver", "power saver", "follow", "doze", "adaptive battery",
    ),
)

internal val settingsPowerKnobs = listOf(powerEnabled, powerPercent, powerOsSaver)
