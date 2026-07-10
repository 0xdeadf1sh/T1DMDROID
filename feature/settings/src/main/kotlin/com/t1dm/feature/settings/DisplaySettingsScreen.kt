package com.t1dm.feature.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.t1dm.core.model.TempUnit
import com.t1dm.core.model.UnitSpace

/**
 * Settings → Display (Phase 7C item 14 + 7D item 25; ux-decisions.md). The GLOBAL
 * glucose unit space, the single GLOBAL stats target range, the "disable all animations" toggle, and
 * — new in 7D — the THEME + FONT switcher and the custom-theme JSON import. Pure/stateless: theme/font
 * ids are opaque strings the caller (`:app` Navigation, which owns `:core:design`) supplies + persists,
 * so this module stays free of a `:core:design` dependency.
 */
@Composable
fun DisplaySettingsScreen(
    unitSpace: UnitSpace,
    targetLow: Int,
    targetHigh: Int,
    animationsEnabled: Boolean,
    themeOptions: List<Pair<String, String>>,
    selectedThemeId: String,
    fontOptions: List<Pair<String, String>>,
    selectedFontId: String,
    customThemeName: String?,
    importStatus: String?,
    temperatureUnit: TempUnit,
    onSetUnitSpace: (UnitSpace) -> Unit,
    onSetTargetRange: (low: Int, high: Int) -> Unit,
    onSetAnimationsEnabled: (Boolean) -> Unit,
    onSelectTheme: (String) -> Unit,
    onSelectFont: (String) -> Unit,
    onImportCustomTheme: () -> Unit,
    onSetTemperatureUnit: (TempUnit) -> Unit,
) {
    SettingsScaffold("Display") {
        SettingsSectionHeader("Theme")
        SettingsNote("Recolours the whole app — graph bands, stats, alerts, widgets, the Circadian dial.")
        ChipPicker("Colour theme", themeOptions, selectedThemeId) { onSelectTheme(it) }
        if (customThemeName != null) {
            Text(
                "Custom theme loaded: $customThemeName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        OutlinedButton(onClick = onImportCustomTheme, modifier = Modifier.padding(top = 4.dp)) {
            Text(if (customThemeName != null) "Replace custom theme (JSON)…" else "Import custom theme (JSON)…")
        }
        if (importStatus != null) {
            Text(importStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SettingsSectionHeader("Font")
        SettingsNote("A global type family — three bundled OFL monospaces, or the system default.")
        ChipPicker("Font family", fontOptions, selectedFontId) { onSelectFont(it) }

        SettingsSectionHeader("Glucose unit")
        ChipPicker(
            "Display and axis unit",
            listOf(
                UnitSpace.MgDl to "mg/dL",
                UnitSpace.MmolL to "mmol/L",
                UnitSpace.Kovatchev to "Kovatchev risk",
            ),
            unitSpace,
        ) { onSetUnitSpace(it) }

        SettingsSectionHeader("Target range")
        SettingsNote("The in-range band for stats (time-in-range) and the graph. Independent of the alarm thresholds.")
        IntStepper("Target low", targetLow, "mg/dL", step = 5, min = 0) { onSetTargetRange(it, targetHigh) }
        IntStepper("Target high", targetHigh, "mg/dL", step = 5, min = 0) { onSetTargetRange(targetLow, it) }

        SettingsSectionHeader("Device temperature")
        SettingsNote(
            "The unit for the device temperature shown on the BG panel and the Hardware panel. The " +
                "value is the battery sensor's reading (there is no readable fan on this device).",
        )
        ChipPicker(
            "Temperature unit",
            listOf(
                TempUnit.CELSIUS to "Celsius (°C)",
                TempUnit.FAHRENHEIT to "Fahrenheit (°F)",
                TempUnit.KELVIN to "Kelvin (K)",
            ),
            temperatureUnit,
        ) { onSetTemperatureUnit(it) }

        SettingsSectionHeader("Motion")
        ToggleRow("Animations", animationsEnabled, "Turn off all motion for a static UI") { onSetAnimationsEnabled(it) }
    }
}
