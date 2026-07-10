package com.t1dm.feature.settings

import androidx.compose.runtime.Composable

/**
 * Settings → Dose calculator (Phase 7C item 14; safety-posture.md, §3.6). The full
 * advisory policy: the scoring objective, the hypo/hyper asymmetry, the candidate-dose grid, the
 * per-rail enable switches, and the rail thresholds. Every numeric threshold is **user-set and
 * deliberately UNBOUNDED** — a disabled rail is a no-op, an enabled rail still fails closed on
 * missing/degenerate/stale input; a threshold only tunes where it trips. Nothing here actuates; the
 * calculator only *recommends* a dose the user administers.
 *
 * The freshness (staleness) gate lives on the "Signal & freshness" screen. Pure/stateless. Objective
 * is an opaque key string so this module stays free of the `:calc` dependency.
 */
@Composable
fun CalculatorSettingsScreen(
    objectiveOptions: List<Pair<String, String>>,
    objective: String,
    targetLow: Double,
    targetHigh: Double,
    targetMid: Double,
    hypoWeight: Double,
    hyperWeight: Double,
    predictedLow: Double,
    iobCeiling: Double,
    gridMaxU: Double,
    gridStepU: Double,
    railFreshness: Boolean,
    railPredictedLow: Boolean,
    railIobCeiling: Boolean,
    railConfirm: Boolean,
    railHypoTreatment: Boolean,
    onSetObjective: (String) -> Unit,
    onSetTarget: (low: Double, high: Double, mid: Double) -> Unit,
    onSetAsymmetry: (hypo: Double, hyper: Double) -> Unit,
    onSetPredictedLow: (Double) -> Unit,
    onSetIobCeiling: (Double) -> Unit,
    onSetGrid: (maxU: Double, stepU: Double) -> Unit,
    onSetRailFreshness: (Boolean) -> Unit,
    onSetRailPredictedLow: (Boolean) -> Unit,
    onSetRailIobCeiling: (Boolean) -> Unit,
    onSetRailConfirm: (Boolean) -> Unit,
    onSetRailHypoTreatment: (Boolean) -> Unit,
) {
    SettingsScaffold("Dose calculator") {
        DangerBanner(
            "Advisory only — the app never actuates insulin; it recommends a dose you administer. " +
                "These thresholds are unbounded on purpose. Enabled rails still refuse on stale or " +
                "degenerate data; disabling a rail removes that protection.",
        )

        SettingsSectionHeader("Objective")
        ChipPicker("Score the forecast by", objectiveOptions, objective) { onSetObjective(it) }

        SettingsSectionHeader("Target range (mg/dL)")
        DoubleStepper("Low", targetLow, "mg/dL", step = 5.0, min = 0.0) { onSetTarget(it, targetHigh, targetMid) }
        DoubleStepper("High", targetHigh, "mg/dL", step = 5.0, min = 0.0) { onSetTarget(targetLow, it, targetMid) }
        DoubleStepper("Aim point", targetMid, "mg/dL", step = 5.0, min = 0.0) { onSetTarget(targetLow, targetHigh, it) }

        SettingsSectionHeader("Risk asymmetry")
        SettingsNote("Relative penalty for each failure direction. Punish hypo harder than hyper, or vice-versa.")
        DoubleStepper("Hypo weight", hypoWeight, "×", step = 0.5, min = 0.0) { onSetAsymmetry(it, hyperWeight) }
        DoubleStepper("Hyper weight", hyperWeight, "×", step = 0.5, min = 0.0) { onSetAsymmetry(hypoWeight, it) }

        SettingsSectionHeader("Candidate grid")
        DoubleStepper("Max bolus searched", gridMaxU, "U", step = 0.5, min = 0.0) { onSetGrid(it, gridStepU) }
        DoubleStepper("Grid step", gridStepU, "U", step = 0.1, min = 0.1) { onSetGrid(gridMaxU, it) }

        SettingsSectionHeader("Rails")
        ToggleRow("Freshness gate", railFreshness, "Refuse on a stale / interpolated anchor") { onSetRailFreshness(it) }
        ToggleRow("Predicted-low veto", railPredictedLow, "Block a dose whose lower band dips below the floor") { onSetRailPredictedLow(it) }
        DoubleStepper("Predicted-low floor", predictedLow, "mg/dL", step = 5.0, min = 0.0) { onSetPredictedLow(it) }
        ToggleRow("IOB ceiling", railIobCeiling, "Block when IOB + candidate exceeds the ceiling") { onSetRailIobCeiling(it) }
        DoubleStepper("IOB ceiling", iobCeiling, "U", step = 0.5, min = 0.0) { onSetIobCeiling(it) }
        ToggleRow("Mandatory confirmation", railConfirm, "Require an explicit acknowledgement to accept") { onSetRailConfirm(it) }
        ToggleRow("Hypo-treatment path", railHypoTreatment, "Switch to a carb rescue when low / predicted low") { onSetRailHypoTreatment(it) }
    }
}
