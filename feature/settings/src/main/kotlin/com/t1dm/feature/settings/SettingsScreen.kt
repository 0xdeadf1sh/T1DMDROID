package com.t1dm.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The Settings root — the complete configuration hub (Phase 7C, items 14 & 17). Every
 * user-tunable knob in the app is reachable from here, grouped into sub-sections; the safety-critical,
 * deliberately-UNBOUNDED knobs (alarm thresholds, calculator rails/thresholds, loss-of-signal windows)
 * are grouped under "Alarms & safety" and flagged inside their own screens (safety-posture.md). The
 * bottom-nav overflow is deliberately left for Phase 7D — this pass only fleshes out the hub.
 *
 * Above the rows sits the search field: twenty-odd sub-screens hold something over eighty separate
 * knobs, and a hub that lists only the screens cannot answer "where is the DND bypass". It is pinned
 * OUTSIDE the scrolling scaffold so it stays reachable while the rows move under it.
 *
 * Pure/stateless: it holds no state, only routes. Each row navigates to a dedicated sub-screen that
 * the caller ([com.t1dm.app] Navigation) wires to the persisted value + its setter; [onOpenKnob] is
 * that same navigation for a search hit, plus the request to reveal the matched row.
 */
@Composable
fun SettingsScreen(
    onOpenDisplay: () -> Unit = {},
    onOpenGraph: () -> Unit = {},
    onOpenAlarmThresholds: () -> Unit = {},
    onOpenSignalSafety: () -> Unit = {},
    onOpenAlerts: () -> Unit = {},
    onOpenWarmup: () -> Unit = {},
    onOpenModelCount: () -> Unit = {},
    onOpenComputeBackend: () -> Unit = {},
    onOpenCalculator: () -> Unit = {},
    onOpenCurveParams: () -> Unit = {},
    onOpenModels: () -> Unit = {},
    onOpenCgm: () -> Unit = {},
    onOpenServer: () -> Unit = {},
    onOpenWatch: () -> Unit = {},
    onOpenPower: () -> Unit = {},
    onOpenData: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenDeath: () -> Unit = {},
    onOpenForecastCadence: () -> Unit = {},
    onOpenThermal: () -> Unit = {},
    onOpenDeviceTemp: () -> Unit = {},
    onOpenDeathClock: () -> Unit = {},
    /** The three most recent committed searches, newest first (persisted in kv by `:app`). */
    recentSearches: List<String> = emptyList(),
    onOpenKnob: (SettingsKnob) -> Unit = {},
    onRecordSearch: (String) -> Unit = {},
    onClearRecentSearches: () -> Unit = {},
    // False in the public flavor: the fail-open DEATH-mode override is compiled out, so its entry row
    // is withheld rather than shown inert (the death-clock projection row above it stays).
    deathModeSupported: Boolean = true,
) {
    Column(Modifier.fillMaxSize()) {
        SettingsSearchBar(
            // The same withholding the DEATH row gets below: an unfiltered index would let the public
            // build reach a screen its own hub deliberately hides.
            index = SettingsIndex.visible(deathModeSupported),
            recent = recentSearches,
            onOpen = onOpenKnob,
            onRecordSearch = onRecordSearch,
            onClearRecentSearches = onClearRecentSearches,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        )
        Box(Modifier.weight(1f)) {
            SettingsScaffold(SettingsScreenKey.ROOT) {
                SettingsSectionHeader("Display & units")
                SettingsNavRow("Theme, font, units & targets", "3 themes, custom JSON, fonts, animations", onClick = onOpenDisplay)
                SettingsNavRow("BG graph range & window", "Floor, ceiling, default window", onClick = onOpenGraph)

                SettingsSectionHeader("Alarms & safety")
                SettingsNavRow("Alarm thresholds", "Urgent-low / low / high / urgent-high — unbounded", onClick = onOpenAlarmThresholds)
                SettingsNavRow("Signal & freshness", "Loss-of-signal windows, dosing staleness gate", onClick = onOpenSignalSafety)
                SettingsNavRow("Alert sound & vibration", "Per-tier tone, K90 vibration, DND bypass, repeat", onClick = onOpenAlerts)
                SettingsNavRow("Device temperature alert", "Warn when the phone runs hot — fires even in Death mode", onClick = onOpenDeviceTemp)

                SettingsSectionHeader("Forecast & models")
                SettingsNavRow("Forecast warmup", "Real history the forecast waits for", onClick = onOpenWarmup)
                SettingsNavRow("Models run at once", "How many forecast at once — all pushed, selected one shown", onClick = onOpenModelCount)
                SettingsNavRow("Forecast cadence", "Adaptive (every reading) or timed (fixed period)", onClick = onOpenForecastCadence)
                SettingsNavRow("Thermal gate", "Pause inference when too hot", onClick = onOpenThermal)
                SettingsNavRow("Compute backend (CPU / GPU)", "CPU authority or Vulkan GPU; measured & agreement-gated", onClick = onOpenComputeBackend)
                SettingsNavRow("Dose calculator", "Objective, asymmetry, rails, thresholds — unbounded", onClick = onOpenCalculator)
                SettingsNavRow("Curve & PK parameters", "Carb & insulin presets, Bézier designers", onClick = onOpenCurveParams)
                SettingsNavRow("Models & backend", "Running model; backend, precision", onClick = onOpenModels)

                SettingsSectionHeader("Devices & sync")
                SettingsNavRow("CGM source", "Active sensor, recorded sources", onClick = onOpenCgm)
                SettingsNavRow("Server profile", "Base URL, rw token (QR), health check", onClick = onOpenServer)
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

/**
 * The two rows that leave `:feature:settings` entirely (for `:feature:models`). There is nothing of
 * theirs to anchor here, so they are whole-screen entries — a search hit navigates and stops.
 */
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
    SettingsKnob(
        id = "models.compute_backend",
        screen = SettingsScreenKey.MODELS,
        section = "Forecast & models",
        label = "Compute backend (CPU / GPU)",
        subtitle = "CPU authority or Vulkan GPU; measured & agreement-gated",
        synonyms = listOf(
            "cpu", "gpu", "vulkan", "npu", "apu", "xnnpack", "neuropilot", "accelerator",
            "hardware", "compute", "backend", "agreement gate", "benchmark", "measure", "speed",
        ),
        anchored = false,
    ),
)
