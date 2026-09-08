package com.t1dm.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The hub; rows only navigate. Search pinned OUTSIDE scaffold, stays reachable as rows scroll. */
@Composable
fun SettingsScreen(
    onOpenDisplay: () -> Unit = {},
    onOpenGraph: () -> Unit = {},
    onOpenAlarmThresholds: () -> Unit = {},
    onOpenSignalSafety: () -> Unit = {},
    onOpenAlerts: () -> Unit = {},
    onOpenForecast: () -> Unit = {},
    onOpenCalculator: () -> Unit = {},
    onOpenCurveParams: () -> Unit = {},
    onOpenModels: () -> Unit = {},
    onOpenCgm: () -> Unit = {},
    onOpenServer: () -> Unit = {},
    onOpenNightscout: () -> Unit = {},
    onOpenWatch: () -> Unit = {},
    onOpenPower: () -> Unit = {},
    onOpenData: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenDeath: () -> Unit = {},
    onOpenDeviceTemp: () -> Unit = {},
    onOpenDeathClock: () -> Unit = {},
    /** Newest first; persisted by `:app`. */
    recentSearches: List<String> = emptyList(),
    onOpenKnob: (SettingsKnob) -> Unit = {},
    onRecordSearch: (String) -> Unit = {},
    onClearRecentSearches: () -> Unit = {},
    // False in public flavor: fail-open DEATH-mode override is compiled out, row withheld.
    deathModeSupported: Boolean = true,
) {
    Column(Modifier.fillMaxSize()) {
        SettingsSearchBar(
            index = SettingsIndex.visible(deathModeSupported),
            recent = recentSearches,
            onOpen = onOpenKnob,
            onRecordSearch = onRecordSearch,
            onClearRecentSearches = onClearRecentSearches,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        )
        Box(Modifier.weight(1f)) {
            SettingsScaffold(SettingsScreenKey.ROOT) {
                SettingsSectionHeader("Display")
                SettingsNavRow("Theme, font, units & targets", "3 themes, custom JSON, fonts, animations", onClick = onOpenDisplay)
                SettingsNavRow("BG graph range & window", "Floor, ceiling, default window", onClick = onOpenGraph)

                SettingsSectionHeader("Alarms")
                SettingsNavRow("Alarm thresholds", "Urgent-low / low / high / urgent-high — unbounded", onClick = onOpenAlarmThresholds)
                SettingsNavRow("Signal & freshness", "Loss-of-signal windows, dosing staleness gate", onClick = onOpenSignalSafety)
                SettingsNavRow("Alert sound & vibration", "Per-tier tone, K90 vibration, DND bypass, repeat", onClick = onOpenAlerts)
                SettingsNavRow("Device temperature alert", "Warn when the phone runs hot — fires even in Death mode", onClick = onOpenDeviceTemp)

                SettingsSectionHeader("Forecast")
                SettingsNavRow("Forecast", "Warmup, cadence, models at once, thermal gate", onClick = onOpenForecast)
                SettingsNavRow("Dose calculator", "Objective, asymmetry, rails, thresholds — unbounded", onClick = onOpenCalculator)
                SettingsNavRow("Curve & PK parameters", "Carb & insulin presets, Bézier designers", onClick = onOpenCurveParams)
                SettingsNavRow("Models & backend", "Running model; backend, precision", onClick = onOpenModels)

                SettingsSectionHeader("Devices")
                SettingsNavRow("CGM source", "Active sensor, recorded sources", onClick = onOpenCgm)
                SettingsNavRow("Server profile", "Base URL, rw token (QR), health check", onClick = onOpenServer)
                SettingsNavRow("Nightscout bridge", "Mirror BG, carbs & bolus to a Nightscout host", onClick = onOpenNightscout)
                SettingsNavRow("Watch", "ESP32-C3 glance: pair, status", onClick = onOpenWatch)
                SettingsNavRow("Low-power mode", "Battery-saver entry % and behaviour", onClick = onOpenPower)

                SettingsSectionHeader("Data")
                SettingsNavRow("Backup & reset", "Export/import JSON, or erase everything", onClick = onOpenData)
                SettingsNavRow("About", "Version, build, licence, model provenance", onClick = onOpenAbout)

                SettingsSectionHeader("The end")
                SettingsNavRow(
                    "Death clock",
                    "Hours from IOB-zero to DKA, coma, death",
                    onClick = onOpenDeathClock,
                )
                if (deathModeSupported) {
                    SettingsNavRow(
                        "Death mode",
                        "Silence every alarm, drop every rail — irrevocable until rescinded",
                        onClick = onOpenDeath,
                    )
                }
            }
        }
    }
}

/** Rendered by `:feature:models`, so these are whole-screen entries: a hit navigates and stops. */
internal val settingsModelsKnobs = listOf(
    SettingsKnob(
        id = "models.select",
        screen = SettingsScreenKey.MODELS,
        section = "Forecast & models",
        label = "Models & backend",
        subtitle = "Running model; backend, precision",
        synonyms = listOf(
            "model", "models", "checkpoint", "weights", "network", "executorch", "pte",
            "inference", "backend", "precision", "fp16", "fp32", "select model", "running model",
        ),
        anchored = false,
    ),
)
