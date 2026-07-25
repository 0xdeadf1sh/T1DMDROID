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
    SettingsScaffold(SettingsScreenKey.THERMAL) {
        SettingsNote("Pauses forecasting when hot; active even in Death mode")
        ToggleRow(thermalEnabled, enabled) { onSetEnabled(it) }
        DoubleStepper(thermalPauseAt, maxTempC, "°C", step = 0.5, min = 0.0) { onSetMaxTempC(it) }
        DoubleStepper(thermalWarnWithin, warnMarginC, "°C of threshold", step = 0.5, min = 0.0) { onSetWarnMarginC(it) }
    }
}

// ── search index (see SettingsIndex.kt) ───────────────────────────────────────────────────────────

private const val THERMAL_SECTION = "Thermal gate"

private val thermalEnabled = SettingsKnob(
    id = "thermal.enabled",
    screen = SettingsScreenKey.THERMAL,
    section = THERMAL_SECTION,
    label = "Enable thermal gate",
    subtitle = "Pause inference while too hot — active even in Death mode",
    synonyms = listOf(
        "thermal", "thermal gate", "throttle", "overheat", "hot", "heat", "pause inference",
        "npu", "cpu load", "protection", "temperature", "cooling",
    ),
)

private val thermalPauseAt = SettingsKnob(
    id = "thermal.pause_at",
    screen = SettingsScreenKey.THERMAL,
    section = THERMAL_SECTION,
    label = "Pause inference at",
    subtitle = "The battery-sensor temperature at which forecasting stops, in °C",
    synonyms = listOf("pause", "stop", "threshold", "trip", "max temperature", "celsius", "degrees", "hot", "cutoff"),
)

private val thermalWarnWithin = SettingsKnob(
    id = "thermal.warn_within",
    screen = SettingsScreenKey.THERMAL,
    section = THERMAL_SECTION,
    label = "Warn within",
    subtitle = "How close to the pause threshold a warning is raised, in °C",
    synonyms = listOf("warn", "warning", "margin", "headroom", "approaching", "nearly", "buffer", "hysteresis", "degrees"),
)

internal val settingsThermalKnobs = listOf(thermalEnabled, thermalPauseAt, thermalWarnWithin)
