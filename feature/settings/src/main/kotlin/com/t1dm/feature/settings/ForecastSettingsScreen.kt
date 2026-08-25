package com.t1dm.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.rememberT1dmHaptics

@Composable
fun ForecastSettingsScreen(
    warmupHoursValue: Int,
    onSetWarmupHours: (Int) -> Unit,
    modelCount: Int,
    onSetModelCount: (Int) -> Unit,
    adaptive: Boolean,
    periodMinutes: Int,
    logDebounceSeconds: Int,
    onSetAdaptive: (Boolean) -> Unit,
    onSetPeriodMinutes: (Int) -> Unit,
    onSetLogDebounceSeconds: (Int) -> Unit,
    thermalOn: Boolean,
    maxTempC: Double,
    warnMarginC: Double,
    onSetThermalEnabled: (Boolean) -> Unit,
    onSetMaxTempC: (Double) -> Unit,
    onSetWarnMarginC: (Double) -> Unit,
    warmupMinHours: Int = 8,
    warmupMaxHours: Int = 72,
    minModelCount: Int = 1,
    maxModelCount: Int = 8,
) {
    SettingsScaffold(SettingsScreenKey.FORECAST) {
        SettingsSectionHeader("Warmup")
        WarmupSection(warmupHoursValue, warmupMinHours, warmupMaxHours, onSetWarmupHours)

        SettingsSectionHeader("Cadence")
        SettingsNote("Adaptive re-forecasts on each reading; timed uses a fixed clock grid.")
        ChipPicker(
            forecastCadenceMode,
            listOf(true to "Adaptive", false to "Timed"),
            adaptive,
        ) { onSetAdaptive(it) }
        // Absent in adaptive mode; the scaffold releases a search request that finds no row.
        if (!adaptive) {
            IntStepper(forecastCadencePeriod, periodMinutes, "min", step = 1, min = 1, max = 60) { onSetPeriodMinutes(it) }
        }
        IntStepper(logReforecastDebounce, logDebounceSeconds, "s", step = 1, min = 0, max = 60) { onSetLogDebounceSeconds(it) }

        SettingsSectionHeader("Models run at once")
        ModelCountSection(modelCount, minModelCount, maxModelCount, onSetModelCount)

        SettingsSectionHeader("Thermal gate")
        SettingsNote("Pauses forecasting when hot; active even in Death mode")
        ToggleRow(thermalEnabled, thermalOn) { onSetThermalEnabled(it) }
        DoubleStepper(thermalPauseAt, maxTempC, "°C", step = 0.5, min = 0.0) { onSetMaxTempC(it) }
        DoubleStepper(thermalWarnWithin, warnMarginC, "°C of threshold", step = 0.5, min = 0.0) { onSetWarnMarginC(it) }
    }
}

/** Hours of measured, non-interpolated BG. Floored at the model's MIN_CONTEXT. */
@Composable
private fun WarmupSection(hours: Int, minHours: Int, maxHours: Int, onChange: (Int) -> Unit) {
    val haptics = rememberT1dmHaptics()
    SettingsNote("Measured BG the forecast waits for; fill doesn't count")
    SettingsAnchor(warmupHours) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = {
                    haptics.perform(HapticEvent.SegmentTick)
                    onChange((hours - 1).coerceAtLeast(minHours))
                },
                enabled = hours > minHours,
            ) { Text("−1 h") }

            Text(
                text = "$hours h",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )

            Button(
                onClick = {
                    haptics.perform(HapticEvent.SegmentTick)
                    onChange((hours + 1).coerceAtMost(maxHours))
                },
                enabled = hours < maxHours,
            ) { Text("+1 h") }
        }
    }
    SettingsNote("Min $minHours h (the model's context floor) · max $maxHours h")
}

/** Every running model pushes under its own id; the Models screen picks the one the dashboard draws. */
@Composable
private fun ModelCountSection(count: Int, minCount: Int, maxCount: Int, onChange: (Int) -> Unit) {
    val haptics = rememberT1dmHaptics()
    SettingsNote("Models run per cycle")
    SettingsAnchor(modelCountRunning) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = {
                    haptics.perform(HapticEvent.SegmentTick)
                    onChange((count - 1).coerceAtLeast(minCount))
                },
                enabled = count > minCount,
            ) { Text("−1") }

            Text(
                text = count.toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )

            Button(
                onClick = {
                    haptics.perform(HapticEvent.SegmentTick)
                    onChange((count + 1).coerceAtMost(maxCount))
                },
                enabled = count < maxCount,
            ) { Text("+1") }
        }
    }
    SettingsNote("1–8 · more models, more CPU")
}

private val warmupHours = SettingsKnob(
    id = "warmup.hours",
    screen = SettingsScreenKey.FORECAST,
    section = "Warmup",
    label = "Forecast warmup",
    subtitle = "How much real, measured BG history must accrue before a forecast is shown",
    synonyms = listOf(
        "warmup", "warm up", "warm-up", "history", "context", "hours", "before forecasting",
        "startup", "settle", "min context", "no forecast", "waiting", "first run", "delay",
    ),
)

private val modelCountRunning = SettingsKnob(
    id = "model_count.running",
    screen = SettingsScreenKey.FORECAST,
    section = "Models run at once",
    label = "Models run at once",
    subtitle = "How many discovered models forecast each cycle — all are pushed, the selected one is drawn",
    synonyms = listOf(
        "models", "how many", "running", "count", "concurrent", "at once", "cap", "limit",
        "ensemble", "cpu", "battery",
    ),
)

private val forecastCadenceMode = SettingsKnob(
    id = "forecast_cadence.mode",
    screen = SettingsScreenKey.FORECAST,
    section = "Cadence",
    label = "Forecast cadence",
    subtitle = "Adaptive follows each reading; timed fires on a fixed clock grid",
    synonyms = listOf(
        "cadence", "adaptive", "timed", "period", "how often", "frequency", "interval",
        "re-forecast", "reforecast", "schedule", "tick",
    ),
)

private val forecastCadencePeriod = SettingsKnob(
    id = "forecast_cadence.period",
    screen = SettingsScreenKey.FORECAST,
    section = "Cadence",
    label = "Timed period",
    subtitle = "Minutes between forecasts in timed mode",
    synonyms = listOf("period", "minutes", "every", "interval", "timed", "grid", "clock"),
)

private val logReforecastDebounce = SettingsKnob(
    id = "forecast_cadence.log_debounce",
    screen = SettingsScreenKey.FORECAST,
    section = "Cadence",
    label = "Re-forecast after a log",
    subtitle = "How long a cycle earned by a logged meal or dose waits for its neighbours",
    synonyms = listOf(
        "debounce", "after logging", "meal", "bolus", "dose", "coalesce", "wait", "seconds",
        "batch", "re-forecast", "reforecast",
    ),
)

private const val THERMAL_SECTION = "Thermal gate"

private val thermalEnabled = SettingsKnob(
    id = "thermal.enabled",
    screen = SettingsScreenKey.FORECAST,
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
    screen = SettingsScreenKey.FORECAST,
    section = THERMAL_SECTION,
    label = "Pause inference at",
    subtitle = "The battery-sensor temperature at which forecasting stops, in °C",
    synonyms = listOf("pause", "stop", "threshold", "trip", "max temperature", "celsius", "degrees", "hot", "cutoff"),
)

private val thermalWarnWithin = SettingsKnob(
    id = "thermal.warn_within",
    screen = SettingsScreenKey.FORECAST,
    section = THERMAL_SECTION,
    label = "Warn within",
    subtitle = "How close to the pause threshold a warning is raised, in °C",
    synonyms = listOf("warn", "warning", "margin", "headroom", "approaching", "nearly", "buffer", "hysteresis", "degrees"),
)

internal val settingsForecastKnobs = listOf(
    warmupHours,
    modelCountRunning,
    forecastCadenceMode,
    forecastCadencePeriod,
    logReforecastDebounce,
    thermalEnabled,
    thermalPauseAt,
    thermalWarnWithin,
)
