package com.t1dm.feature.settings

import androidx.compose.runtime.Composable

/**
 * Settings → Alarm thresholds (Phase 7C item 14; safety-posture.md §3.6-A). The four
 * deterministic model-free bands — urgent-low / low / high / urgent-high — in mg/dL. Every value is
 * **user-set and deliberately UNBOUNDED**: there is no clinical clamp (the user explicitly overrode a
 * compiled ceiling). The screen only warns when the values are out of the expected
 * `urgentLow ≤ low ≤ high ≤ urgentHigh` order so a slip is visible; it never blocks it.
 *
 * Pure/stateless. Changing a threshold re-persists it immediately; the note states that the running
 * model-free alarm adopts new thresholds when the monitoring service next starts (reopening the app).
 */
@Composable
fun AlarmThresholdsScreen(
    urgentLow: Int,
    low: Int,
    high: Int,
    urgentHigh: Int,
    onChange: (urgentLow: Int, low: Int, high: Int, urgentHigh: Int) -> Unit,
) {
    SettingsScaffold("Alarm thresholds") {
        DangerBanner(
            "These bounds are unbounded on purpose — there is no safety clamp. The model-free alarm " +
                "fires strictly on a measured reading crossing them, independent of the forecast. Set " +
                "them to values you trust; a mistake here weakens your safety net.",
        )
        SettingsNote("All values in mg/dL. Urgent-low and urgent-high bypass Do-Not-Disturb.")

        IntStepper("Urgent low", urgentLow, "mg/dL", step = 5, min = 0) { onChange(it, low, high, urgentHigh) }
        IntStepper("Low", low, "mg/dL", step = 5, min = 0) { onChange(urgentLow, it, high, urgentHigh) }
        IntStepper("High", high, "mg/dL", step = 5, min = 0) { onChange(urgentLow, low, it, urgentHigh) }
        IntStepper("Urgent high", urgentHigh, "mg/dL", step = 5, min = 0) { onChange(urgentLow, low, high, it) }

        val ordered = urgentLow <= low && low <= high && high <= urgentHigh
        if (!ordered) {
            DangerBanner(
                "Thresholds are out of order (expected urgent-low ≤ low ≤ high ≤ urgent-high). " +
                    "This is allowed, but the bands will classify oddly — double-check the values.",
            )
        }
    }
}
