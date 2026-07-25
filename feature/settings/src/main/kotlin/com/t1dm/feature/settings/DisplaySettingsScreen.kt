package com.t1dm.feature.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.model.TempUnit
import com.t1dm.core.model.UnitSpace

/**
 * Settings → Display (Phase 7C item 14 + 7D item 25; ux-decisions.md). The GLOBAL
 * glucose unit space, the single GLOBAL stats target range, the "disable all animations" toggle, and
 * — new in 7D — the THEME + FONT switcher, the custom-theme JSON import, and the haptics intensity.
 * Pure/stateless: theme / font / haptic ids are opaque strings the caller (`:app` Navigation, which
 * owns `:core:design`) supplies, marshals to their enums, and persists. The rule the SIGNATURE keeps
 * is that no `:core:design` (or `:alerts`) type crosses it; the haptics ENGINE, by contrast, arrives
 * ambiently through `LocalT1dmHaptics` and is never a parameter, so the screen stays as stateless and
 * as callback-driven as it was.
 */
@Composable
fun DisplaySettingsScreen(
    unitSpace: UnitSpace,
    targetLow: Int,
    targetHigh: Int,
    animationsEnabled: Boolean,
    backgroundAlphaPct: Int,
    themeOptions: List<Pair<String, String>>,
    selectedThemeId: String,
    fontOptions: List<Pair<String, String>>,
    selectedFontId: String,
    customThemeName: String?,
    importStatus: String?,
    temperatureUnit: TempUnit,
    hapticOptions: List<Pair<String, String>>,
    selectedHaptic: String,
    onSetUnitSpace: (UnitSpace) -> Unit,
    onSetTargetRange: (low: Int, high: Int) -> Unit,
    onSetAnimationsEnabled: (Boolean) -> Unit,
    onSetBackgroundAlpha: (Int) -> Unit,
    onSelectTheme: (String) -> Unit,
    onSelectFont: (String) -> Unit,
    onImportCustomTheme: () -> Unit,
    onSetTemperatureUnit: (TempUnit) -> Unit,
    onSelectHaptic: (String) -> Unit,
    /** Play the tapped intensity immediately, before the setting round-trips (the alerts precedent). */
    onPreviewHaptic: (String) -> Unit = {},
    widgetPinSupported: Boolean = false,
    widgetPinActions: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val haptics = rememberT1dmHaptics()
    SettingsScaffold(SettingsScreenKey.DISPLAY) {
        SettingsSectionHeader("Theme")
        ChipPicker(displayTheme, themeOptions, selectedThemeId) { onSelectTheme(it) }
        SettingsAnchor(displayCustomTheme) {
            if (customThemeName != null) {
                Text(
                    "Custom theme: $customThemeName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            OutlinedButton(
                onClick = { haptics.perform(HapticEvent.Tap); onImportCustomTheme() },
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(if (customThemeName != null) "Replace custom theme (JSON)…" else "Import custom theme (JSON)…")
            }
            if (importStatus != null) {
                Text(importStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SettingsSectionHeader("Background")
        SettingsNote("Each theme's own motif behind the app; 0 % turns it off.")
        IntStepper(displayBackgroundOpacity, backgroundAlphaPct, "%", step = 5, min = 0, max = 100) { onSetBackgroundAlpha(it) }

        SettingsSectionHeader("Font")
        ChipPicker(displayFont, fontOptions, selectedFontId) { onSelectFont(it) }

        SettingsSectionHeader("Glucose unit")
        ChipPicker(
            displayGlucoseUnit,
            listOf(
                UnitSpace.MgDl to "mg/dL",
                UnitSpace.MmolL to "mmol/L",
                UnitSpace.Kovatchev to "Kovatchev risk",
            ),
            unitSpace,
        ) { onSetUnitSpace(it) }

        SettingsSectionHeader("Target range")
        SettingsNote("For stats and the graph — independent of the alarm thresholds.")
        IntStepper(displayTargetLow, targetLow, "mg/dL", step = 5, min = 0) { onSetTargetRange(it, targetHigh) }
        IntStepper(displayTargetHigh, targetHigh, "mg/dL", step = 5, min = 0) { onSetTargetRange(targetLow, it) }

        SettingsSectionHeader("Device temperature")
        SettingsNote("The battery sensor's reading — no truer sensor is readable on this device.")
        ChipPicker(
            displayTemperatureUnit,
            listOf(
                TempUnit.CELSIUS to "Celsius (°C)",
                TempUnit.FAHRENHEIT to "Fahrenheit (°F)",
                TempUnit.KELVIN to "Kelvin (K)",
            ),
            temperatureUnit,
        ) { onSetTemperatureUnit(it) }

        SettingsSectionHeader("Widgets")
        // ONE index entry rather than one per widget: the button list is discovered at runtime from
        // the launcher's pin support, and either branch of it answers the same search.
        SettingsAnchor(displayWidgets) {
            if (widgetPinSupported && widgetPinActions.isNotEmpty()) {
                widgetPinActions.forEach { (label, onPin) ->
                    OutlinedButton(
                        onClick = { haptics.perform(HapticEvent.Tap); onPin() },
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text("Add \"$label\" widget")
                    }
                }
            } else {
                SettingsNote("This launcher can't add widgets from the app — long-press the home screen, Widgets, T1DM.")
            }
        }

        SettingsSectionHeader("Motion")
        ToggleRow(displayAnimations, animationsEnabled) { onSetAnimationsEnabled(it) }

        SettingsSectionHeader("Haptics")
        SettingsNote("Independent of Animations; alarm vibration is set separately under Alerts.")
        // The preview IS this picker's feedback, so it suppresses the detent every other ChipPicker
        // plays: that tick would sound at the level being replaced, not the one being chosen.
        ChipPicker(displayHaptics, hapticOptions, selectedHaptic, tickOnSelect = false) {
            onPreviewHaptic(it)
            onSelectHaptic(it)
        }
    }
}

// ── search index (see SettingsIndex.kt) ───────────────────────────────────────────────────────────

private val displayTheme = SettingsKnob(
    id = "display.theme",
    screen = SettingsScreenKey.DISPLAY,
    section = "Theme",
    label = "Colour theme",
    subtitle = "Recolours the whole app",
    synonyms = listOf(
        "theme", "colour", "color", "colours", "colors", "palette", "skin", "appearance", "look",
        "dark", "light", "tron", "umbrella", "hello kitty", "accent", "style", "recolour",
    ),
)

private val displayCustomTheme = SettingsKnob(
    id = "display.custom_theme",
    screen = SettingsScreenKey.DISPLAY,
    section = "Theme",
    label = "Import custom theme (JSON)",
    subtitle = "Load a palette from a JSON file",
    synonyms = listOf("custom theme", "import theme", "json", "palette file", "own colours", "my colours", "load theme"),
)

private val displayBackgroundOpacity = SettingsKnob(
    id = "display.background_opacity",
    screen = SettingsScreenKey.DISPLAY,
    section = "Background",
    label = "Background image opacity",
    subtitle = "The theme's motif behind the app; 0 % turns it off",
    synonyms = listOf(
        "background", "backdrop", "wallpaper", "motif", "image", "picture", "opacity", "alpha",
        "transparency", "faint", "dim", "grid", "watermark",
    ),
)

private val displayFont = SettingsKnob(
    id = "display.font",
    screen = SettingsScreenKey.DISPLAY,
    section = "Font",
    label = "Font family",
    subtitle = "Bundled OFL monospaces, or the system default",
    synonyms = listOf("font", "typeface", "type", "text", "lettering", "monospace", "mono", "ofl", "typography"),
)

private val displayGlucoseUnit = SettingsKnob(
    id = "display.glucose_unit",
    screen = SettingsScreenKey.DISPLAY,
    section = "Glucose unit",
    label = "Display and axis unit",
    subtitle = "mg/dL, mmol/L, or the Kovatchev risk space",
    synonyms = listOf(
        "unit", "units", "mgdl", "mg/dl", "milligrams", "mmol", "mmoll", "mmol/l", "millimoles",
        "si", "kovatchev", "risk", "risk space", "glucose unit", "measurement",
    ),
)

private val displayTargetLow = SettingsKnob(
    id = "display.target_low",
    screen = SettingsScreenKey.DISPLAY,
    section = "Target range",
    label = "Target low",
    subtitle = "Bottom of the in-range band — not an alarm threshold",
    synonyms = listOf(
        "target", "target range", "in range", "in-range", "time in range", "tir", "band", "goal",
        "stats", "a1c", "hba1c", "gmi", "green band", "low",
    ),
)

private val displayTargetHigh = SettingsKnob(
    id = "display.target_high",
    screen = SettingsScreenKey.DISPLAY,
    section = "Target range",
    label = "Target high",
    subtitle = "Top of the in-range band — not an alarm threshold",
    synonyms = listOf(
        "target", "target range", "in range", "in-range", "time in range", "tir", "band", "goal",
        "stats", "a1c", "hba1c", "gmi", "green band", "high",
    ),
)

private val displayTemperatureUnit = SettingsKnob(
    id = "display.temperature_unit",
    screen = SettingsScreenKey.DISPLAY,
    section = "Device temperature",
    label = "Temperature unit",
    subtitle = "For the phone temperature on the BG and Hardware panels",
    synonyms = listOf(
        "temperature", "temp", "celsius", "centigrade", "fahrenheit", "kelvin", "degrees",
        "thermometer", "heat", "unit",
    ),
)

private val displayWidgets = SettingsKnob(
    id = "display.widgets",
    screen = SettingsScreenKey.DISPLAY,
    section = "Widgets",
    label = "Add a widget to the home screen",
    subtitle = "The BG tile, the prediction glance, and the lock-screen glance",
    synonyms = listOf(
        "widget", "widgets", "home screen", "homescreen", "launcher", "tile", "glance",
        "lock screen", "lockscreen", "pin", "shortcut", "desktop",
    ),
)

private val displayAnimations = SettingsKnob(
    id = "display.animations",
    screen = SettingsScreenKey.DISPLAY,
    section = "Motion",
    label = "Animations",
    subtitle = "Turn off all motion for a static UI",
    synonyms = listOf(
        "animation", "animations", "motion", "movement", "static", "still", "reduce motion",
        "transitions", "crossfade", "effects", "accessibility",
    ),
)

private val displayHaptics = SettingsKnob(
    id = "display.haptics",
    screen = SettingsScreenKey.DISPLAY,
    section = "Haptics",
    label = "Touch feedback",
    subtitle = "A short vibration on taps, toggles, detents and confirmations",
    synonyms = listOf(
        "haptic", "haptics", "buzz", "rumble", "vibrate", "vibration", "touch feedback", "taptic",
        "feel", "force feedback", "intensity", "strength", "silent",
    ),
)

internal val settingsDisplayKnobs = listOf(
    displayTheme,
    displayCustomTheme,
    displayBackgroundOpacity,
    displayFont,
    displayGlucoseUnit,
    displayTargetLow,
    displayTargetHigh,
    displayTemperatureUnit,
    displayWidgets,
    displayAnimations,
    displayHaptics,
)
