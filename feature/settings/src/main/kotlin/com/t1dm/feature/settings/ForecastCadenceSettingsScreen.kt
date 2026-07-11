package com.t1dm.feature.settings

import androidx.compose.runtime.Composable

/**
 * Settings → Forecast cadence (F2). Chooses WHEN the model re-forecasts. In **adaptive** mode a fresh
 * forecast is driven off every incoming CGM reading, so the cadence tracks the sensor's own (irregular,
 * ~5-min) advertisement rhythm; in **timed** mode it fires on a fixed wall-clock grid regardless of when
 * readings land. Only the timed period is user-set — the adaptive branch has nothing to tune. The period
 * is clamped to [1, 60] min by the store. Pure/stateless.
 */
@Composable
fun ForecastCadenceSettingsScreen(
    adaptive: Boolean,
    periodMinutes: Int,
    onSetAdaptive: (Boolean) -> Unit,
    onSetPeriodMinutes: (Int) -> Unit,
) {
    SettingsScaffold("Forecast cadence") {
        SettingsNote(
            "Adaptive re-forecasts on each new CGM reading, so it follows the sensor's own rhythm. " +
                "Timed re-forecasts on a fixed clock grid instead, independent of when readings arrive.",
        )
        ChipPicker(
            "Cadence",
            listOf(true to "Adaptive", false to "Timed"),
            adaptive,
        ) { onSetAdaptive(it) }
        if (!adaptive) {
            IntStepper("Forecast every", periodMinutes, "min", step = 1, min = 1, max = 60) { onSetPeriodMinutes(it) }
        }
    }
}
