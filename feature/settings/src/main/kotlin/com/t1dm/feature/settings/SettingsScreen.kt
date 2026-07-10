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
    onOpenCalculator: () -> Unit = {},
    onOpenCurveParams: () -> Unit = {},
    onOpenModels: () -> Unit = {},
    onOpenCgm: () -> Unit = {},
    onOpenServer: () -> Unit = {},
    onOpenWatch: () -> Unit = {},
    onOpenPower: () -> Unit = {},
    onOpenData: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
) {
    SettingsScaffold("Settings") {
        SettingsSectionHeader("Display & units")
        SettingsNavRow("Theme, font, units & targets", "3 themes + JSON import, bundled OFL fonts, unit space, BG target, animations", onClick = onOpenDisplay)
        SettingsNavRow("BG graph range & window", "Y-axis floor/ceiling and the default time window", onClick = onOpenGraph)

        SettingsSectionHeader("Alarms & safety")
        SettingsNavRow("Alarm thresholds", "Urgent-low / low / high / urgent-high — unbounded", onClick = onOpenAlarmThresholds)
        SettingsNavRow("Signal & freshness", "Loss-of-signal windows and dosing staleness gate", onClick = onOpenSignalSafety)
        SettingsNavRow("Alert sound & vibration", "Per-tier tone, K90 vibration, DND bypass, repeat", onClick = onOpenAlerts)

        SettingsSectionHeader("Forecast & models")
        SettingsNavRow("Forecast warmup", "How much real history the forecast waits for", onClick = onOpenWarmup)
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
    }
}
