package com.t1dm.feature.settings

import androidx.compose.runtime.Composable

/**
 * The Settings root — the complete configuration hub (Phase 7C, items 14 & 17). Every
 * user-tunable knob in the app is reachable from here, grouped into sub-sections; the safety-critical,
 * deliberately-UNBOUNDED knobs (alarm thresholds, calculator rails/thresholds, loss-of-signal windows)
 * are grouped under "Alarms & safety" and flagged inside their own screens (safety-posture.md). The
 * bottom-nav overflow is deliberately left for Phase 7D — this pass only fleshes out the hub.
 *
 * Pure/stateless: it holds no state, only routes. Each row navigates to a dedicated sub-screen that
 * the caller ([com.t1dm.app] Navigation) wires to the persisted value + its setter.
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
    // False in the public flavor: the fail-open DEATH-mode override is compiled out, so its entry row
    // is withheld rather than shown inert (the death-clock projection row above it stays).
    deathModeSupported: Boolean = true,
) {
    SettingsScaffold("Settings") {
        SettingsSectionHeader("Display & units")
        SettingsNavRow("Theme, font, units & targets", "3 themes + JSON import, bundled OFL fonts, unit space, BG target, animations", onClick = onOpenDisplay)
        SettingsNavRow("BG graph range & window", "Y-axis floor/ceiling and the default time window", onClick = onOpenGraph)

        SettingsSectionHeader("Alarms & safety")
        SettingsNavRow("Alarm thresholds", "Urgent-low / low / high / urgent-high — unbounded", onClick = onOpenAlarmThresholds)
        SettingsNavRow("Signal & freshness", "Loss-of-signal windows and dosing staleness gate", onClick = onOpenSignalSafety)
        SettingsNavRow("Alert sound & vibration", "Per-tier tone, K90 vibration, DND bypass, repeat", onClick = onOpenAlerts)
        SettingsNavRow("Device temperature alert", "Warn when the phone runs hot enough to pause forecasting — fires even in DEATH mode", onClick = onOpenDeviceTemp)

        SettingsSectionHeader("Forecast & models")
        SettingsNavRow("Forecast warmup", "How much real history the forecast waits for", onClick = onOpenWarmup)
        SettingsNavRow("Models run at once", "How many models forecast simultaneously (all push to the server; the selected one shows on the dashboard)", onClick = onOpenModelCount)
        SettingsNavRow("Forecast cadence", "Re-forecast on every reading (adaptive) or on a fixed clock period", onClick = onOpenForecastCadence)
        SettingsNavRow("Thermal gate", "Pause inference when the phone runs too hot — threshold and warning margin", onClick = onOpenThermal)
        SettingsNavRow("Compute backend (CPU / GPU)", "Run the forecast on the CPU authority or the Vulkan GPU; measure & agreement-gate", onClick = onOpenComputeBackend)
        SettingsNavRow("Dose calculator", "Objective, asymmetry, rails, and rail thresholds — unbounded", onClick = onOpenCalculator)
        SettingsNavRow("Curve & PK parameters", "Carb-appearance and insulin-action presets + Bézier custom-curve designers", onClick = onOpenCurveParams)
        SettingsNavRow("Models & backend", "Select the running model; inference backend/precision", onClick = onOpenModels)

        SettingsSectionHeader("Devices & sync")
        SettingsNavRow("CGM source", "Active sensor and recorded sources", onClick = onOpenCgm)
        SettingsNavRow("Server profile", "Base URL + rw token (QR scan), health check", onClick = onOpenServer)
        SettingsNavRow("Watch", "ESP32-C3 glance pairing and status", onClick = onOpenWatch)
        SettingsNavRow("Low-power mode", "Battery-saver entry percentage and behaviour", onClick = onOpenPower)

        SettingsSectionHeader("Data")
        SettingsNavRow("Backup & reset", "Export/import settings as JSON, or erase all data", onClick = onOpenData)
        SettingsNavRow("About", "Version, build, licence, model provenance", onClick = onOpenAbout)

        SettingsSectionHeader("The end")
        SettingsNavRow(
            "Death clock",
            "Tune the insulin-exhaustion projection — hours from IOB-zero to DK, coma, and death",
            onClick = onOpenDeathClock,
        )
        if (deathModeSupported) {
            SettingsNavRow(
                "DEATH mode",
                "Silence every alarm, disable every safety rail, and still the warnings — irrevocably, until you rescind it",
                onClick = onOpenDeath,
            )
        }
    }
}
