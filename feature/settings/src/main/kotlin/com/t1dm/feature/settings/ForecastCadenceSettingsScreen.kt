package com.t1dm.feature.settings

import androidx.compose.runtime.Composable

/**
 * Settings → Forecast cadence (F2). Chooses WHEN the model re-forecasts. In **adaptive** mode a fresh
 * forecast is driven off every incoming CGM reading, so the cadence tracks the sensor's own realtime
 * push rhythm; in **timed** mode it fires on a fixed wall-clock grid regardless of when
 * readings land. Only the timed period is user-set — the adaptive branch has nothing to tune. The period
 * is clamped to [1, 60] min by the store. Pure/stateless.
 *
 * The third knob is orthogonal to that choice and applies in both modes: a logged meal or dose moves
 * the carb/insulin channels the model conditions on, so it earns a cycle of its own rather than
 * waiting out the tick, and [logDebounceSeconds] is how long that cycle waits for its neighbours —
 * a meal and the bolus that follows it cost one forward, not two. Clamped to [0, 60] s by the store.
 */
@Composable
fun ForecastCadenceSettingsScreen(
    adaptive: Boolean,
    periodMinutes: Int,
    logDebounceSeconds: Int,
    onSetAdaptive: (Boolean) -> Unit,
    onSetPeriodMinutes: (Int) -> Unit,
    onSetLogDebounceSeconds: (Int) -> Unit,
) {
    SettingsScaffold(SettingsScreenKey.FORECAST_CADENCE) {
        SettingsNote("Adaptive re-forecasts on each reading; timed uses a fixed clock grid.")
        ChipPicker(
            forecastCadenceMode,
            listOf(true to "Adaptive", false to "Timed"),
            adaptive,
        ) { onSetAdaptive(it) }
        // Only exists in timed mode; a search landing here while adaptive simply finds no row to
        // reveal, and the scaffold releases the request rather than holding it for the next visit.
        if (!adaptive) {
            IntStepper(forecastCadencePeriod, periodMinutes, "min", step = 1, min = 1, max = 60) { onSetPeriodMinutes(it) }
        }
        IntStepper(logReforecastDebounce, logDebounceSeconds, "s", step = 1, min = 0, max = 60) { onSetLogDebounceSeconds(it) }
    }
}

// ── search index (see SettingsIndex.kt) ───────────────────────────────────────────────────────────

private val forecastCadenceMode = SettingsKnob(
    id = "forecast_cadence.mode",
    screen = SettingsScreenKey.FORECAST_CADENCE,
    section = "Forecast cadence",
    label = "Cadence",
    subtitle = "Re-forecast on every reading (adaptive) or on a fixed clock period (timed)",
    synonyms = listOf(
        "cadence", "adaptive", "timed", "how often", "frequency", "rhythm", "schedule",
        "re-forecast", "reforecast", "trigger", "when", "rate",
    ),
)

private val forecastCadencePeriod = SettingsKnob(
    id = "forecast_cadence.period",
    screen = SettingsScreenKey.FORECAST_CADENCE,
    section = "Forecast cadence",
    label = "Forecast every",
    subtitle = "The fixed clock period between forecasts, in timed mode only",
    synonyms = listOf(
        "period", "every", "interval", "minutes", "clock", "timed", "fixed", "how often",
        "frequency", "schedule",
    ),
)

private val logReforecastDebounce = SettingsKnob(
    id = "forecast_cadence.log_debounce",
    screen = SettingsScreenKey.FORECAST_CADENCE,
    section = "Forecast cadence",
    label = "Re-forecast after a log",
    subtitle = "How long a logged meal or dose waits before its own forecast; nearby logs share one run",
    synonyms = listOf(
        "debounce", "delay", "log", "logged", "meal", "carbs", "dose", "bolus", "basal", "insulin",
        "immediate", "instant", "coalesce", "settle", "seconds", "after", "re-forecast", "reforecast",
    ),
)

internal val settingsForecastCadenceKnobs =
    listOf(forecastCadenceMode, forecastCadencePeriod, logReforecastDebounce)
