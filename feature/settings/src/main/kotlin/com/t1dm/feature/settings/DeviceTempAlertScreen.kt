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
    SettingsScaffold(SettingsScreenKey.DEVICE_TEMP) {
        SettingsNote("Battery sensor, not glucose; keep clear-below under alert")
        ToggleRow(deviceTempEnabled, enabled) { onSetEnabled(it) }
        DoubleStepper(deviceTempAlertAbove, alertC, "°C", step = 0.5, min = 0.0) { onSetAlertC(it) }
        DoubleStepper(deviceTempClearBelow, clearC, "°C", step = 0.5, min = 0.0) { onSetClearC(it) }
        ToggleRow(deviceTempCritical, critical) { onSetCritical(it) }
    }
}

// ── search index (see SettingsIndex.kt) ───────────────────────────────────────────────────────────

private const val TEMP_SECTION = "Device temperature alert"

private val deviceTempEnabled = SettingsKnob(
    id = "device_temp.enabled",
    screen = SettingsScreenKey.DEVICE_TEMP,
    section = TEMP_SECTION,
    label = "Enable device-temperature alarm",
    subtitle = "Warn when the phone runs hot — exempt from Death mode",
    synonyms = listOf(
        "overheat", "overheating", "hot", "heat", "temperature", "phone temperature", "battery temperature",
        "thermal alarm", "device temp", "warm", "burning",
    ),
)

private val deviceTempAlertAbove = SettingsKnob(
    id = "device_temp.alert_above",
    screen = SettingsScreenKey.DEVICE_TEMP,
    section = TEMP_SECTION,
    label = "Alert above",
    subtitle = "The battery-sensor temperature at which the alarm fires, in °C",
    synonyms = listOf("alert above", "trip", "fire at", "threshold", "hot", "overheat", "celsius", "degrees", "temperature"),
)

private val deviceTempClearBelow = SettingsKnob(
    id = "device_temp.clear_below",
    screen = SettingsScreenKey.DEVICE_TEMP,
    section = TEMP_SECTION,
    label = "Clear below",
    subtitle = "The cooled-down temperature at which the alarm clears (hysteresis)",
    synonyms = listOf("clear below", "reset", "cool", "cooled", "hysteresis", "chatter", "recover", "celsius", "degrees"),
)

private val deviceTempCritical = SettingsKnob(
    id = "device_temp.critical",
    screen = SettingsScreenKey.DEVICE_TEMP,
    section = TEMP_SECTION,
    label = "Raise as critical",
    subtitle = "Use the high-priority alarm channel, not a warning",
    synonyms = listOf("critical", "urgent", "priority", "channel", "severity", "loud", "escalate"),
)

internal val settingsDeviceTempKnobs = listOf(
    deviceTempEnabled,
    deviceTempAlertAbove,
    deviceTempClearBelow,
    deviceTempCritical,
)
