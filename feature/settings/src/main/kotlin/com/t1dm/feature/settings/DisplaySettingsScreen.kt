package com.t1dm.feature.settings

import androidx.compose.runtime.Composable
import com.t1dm.core.model.UnitSpace

/**
 * Settings → Units & targets (PLAN.private.md Phase 7C item 14; ux-decisions.md). The GLOBAL glucose
 * unit space (mg/dL default · mmol/L · Kovatchev raw risk), the single GLOBAL stats target range
 * (distinct from the alarm thresholds), and the "disable all animations" toggle. Pure/stateless.
 */
@Composable
fun DisplaySettingsScreen(
    unitSpace: UnitSpace,
    targetLow: Int,
    targetHigh: Int,
    animationsEnabled: Boolean,
    onSetUnitSpace: (UnitSpace) -> Unit,
    onSetTargetRange: (low: Int, high: Int) -> Unit,
    onSetAnimationsEnabled: (Boolean) -> Unit,
) {
    SettingsScaffold("Units & targets") {
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

        SettingsSectionHeader("Motion")
        ToggleRow("Animations", animationsEnabled, "Turn off all motion for a static UI") { onSetAnimationsEnabled(it) }
    }
}
