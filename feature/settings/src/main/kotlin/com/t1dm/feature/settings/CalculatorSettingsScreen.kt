package com.t1dm.feature.settings

import androidx.compose.runtime.Composable

/**
 * Settings → Dose calculator (Phase 7C item 14; §3.6). The full
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
    SettingsScaffold(SettingsScreenKey.CALCULATOR) {
        DangerBanner(
            "Advisory only — the app never actuates insulin; it recommends a dose you administer. " +
                "Thresholds are unbounded; enabled rails still refuse on stale or degenerate data, " +
                "disabling one removes that protection.",
        )

        SettingsSectionHeader("Objective")
        ChipPicker(calcObjective, objectiveOptions, objective) { onSetObjective(it) }

        SettingsSectionHeader("Target range (mg/dL)")
        DoubleStepper(calcTargetLow, targetLow, "mg/dL", step = 5.0, min = 0.0) { onSetTarget(it, targetHigh, targetMid) }
        DoubleStepper(calcTargetHigh, targetHigh, "mg/dL", step = 5.0, min = 0.0) { onSetTarget(targetLow, it, targetMid) }
        DoubleStepper(calcAimPoint, targetMid, "mg/dL", step = 5.0, min = 0.0) { onSetTarget(targetLow, targetHigh, it) }

        SettingsSectionHeader("Risk asymmetry")
        SettingsNote("Relative penalty per direction — punish hypo harder than hyper, or the reverse.")
        DoubleStepper(calcHypoWeight, hypoWeight, "×", step = 0.5, min = 0.0) { onSetAsymmetry(it, hyperWeight) }
        DoubleStepper(calcHyperWeight, hyperWeight, "×", step = 0.5, min = 0.0) { onSetAsymmetry(hypoWeight, it) }

        SettingsSectionHeader("Candidate grid")
        DoubleStepper(calcGridMax, gridMaxU, "U", step = 0.5, min = 0.0) { onSetGrid(it, gridStepU) }
        DoubleStepper(calcGridStep, gridStepU, "U", step = 0.1, min = 0.1) { onSetGrid(gridMaxU, it) }

        SettingsSectionHeader("Rails")
        ToggleRow(calcRailFreshness, railFreshness) { onSetRailFreshness(it) }
        ToggleRow(calcRailPredictedLow, railPredictedLow) { onSetRailPredictedLow(it) }
        DoubleStepper(calcPredictedLowFloor, predictedLow, "mg/dL", step = 5.0, min = 0.0) { onSetPredictedLow(it) }
        ToggleRow(calcRailIobCeiling, railIobCeiling) { onSetRailIobCeiling(it) }
        DoubleStepper(calcIobCeiling, iobCeiling, "U", step = 0.5, min = 0.0) { onSetIobCeiling(it) }
        ToggleRow(calcRailConfirm, railConfirm) { onSetRailConfirm(it) }
        ToggleRow(calcRailHypoTreatment, railHypoTreatment) { onSetRailHypoTreatment(it) }
    }
}

// ── search index (see SettingsIndex.kt) ───────────────────────────────────────────────────────────
//
// "IOB ceiling" names BOTH the rail switch and the threshold it trips on; the ids are what separate
// them, and the subtitle is what tells the two search hits apart on screen.

private val calcObjective = SettingsKnob(
    id = "calc.objective",
    screen = SettingsScreenKey.CALCULATOR,
    section = "Objective",
    label = "Score the forecast by",
    subtitle = "Minimum Kovatchev risk, minimum time out of range, or hitting the aim point at 1 h",
    synonyms = listOf(
        "objective", "score", "scoring", "risk", "kovatchev", "cost", "criterion", "optimise",
        "optimize", "time out of range", "tor", "hit target", "goal", "strategy",
    ),
)

private val calcTargetLow = SettingsKnob(
    id = "calc.target_low",
    screen = SettingsScreenKey.CALCULATOR,
    section = "Target range (mg/dL)",
    label = "Low",
    subtitle = "The bottom of the band the dose search aims to keep the forecast inside",
    synonyms = listOf("target low", "low", "band", "target range", "bottom", "floor", "dose target", "calculator"),
)

private val calcTargetHigh = SettingsKnob(
    id = "calc.target_high",
    screen = SettingsScreenKey.CALCULATOR,
    section = "Target range (mg/dL)",
    label = "High",
    subtitle = "The top of the band the dose search aims to keep the forecast inside",
    synonyms = listOf("target high", "high", "band", "target range", "top", "ceiling", "dose target", "calculator"),
)

private val calcAimPoint = SettingsKnob(
    id = "calc.aim_point",
    screen = SettingsScreenKey.CALCULATOR,
    section = "Target range (mg/dL)",
    label = "Aim point",
    subtitle = "The single value the hit-target objective steers towards",
    synonyms = listOf("aim", "aim point", "midpoint", "mid", "centre", "center", "setpoint", "ideal", "target"),
)

private val calcHypoWeight = SettingsKnob(
    id = "calc.hypo_weight",
    screen = SettingsScreenKey.CALCULATOR,
    section = "Risk asymmetry",
    label = "Hypo weight",
    subtitle = "How much harder a predicted low is punished than a predicted high",
    synonyms = listOf(
        "hypo", "hypoglycemia", "hypoglycaemia", "low", "weight", "penalty", "asymmetry",
        "conservative", "cautious", "bias", "risk",
    ),
)

private val calcHyperWeight = SettingsKnob(
    id = "calc.hyper_weight",
    screen = SettingsScreenKey.CALCULATOR,
    section = "Risk asymmetry",
    label = "Hyper weight",
    subtitle = "How much a predicted high is punished relative to a predicted low",
    synonyms = listOf(
        "hyper", "hyperglycemia", "hyperglycaemia", "high", "weight", "penalty", "asymmetry",
        "aggressive", "bias", "risk",
    ),
)

private val calcGridMax = SettingsKnob(
    id = "calc.grid_max",
    screen = SettingsScreenKey.CALCULATOR,
    section = "Candidate grid",
    label = "Max bolus searched",
    subtitle = "The largest candidate dose the search will consider, in units",
    synonyms = listOf(
        "max bolus", "maximum dose", "largest", "cap", "units", "search space", "candidate",
        "grid", "upper", "limit",
    ),
)

private val calcGridStep = SettingsKnob(
    id = "calc.grid_step",
    screen = SettingsScreenKey.CALCULATOR,
    section = "Candidate grid",
    label = "Grid step",
    subtitle = "The dose increment between candidates, in units",
    synonyms = listOf("grid", "step", "increment", "resolution", "granularity", "units", "search", "quantisation"),
)

private val calcRailFreshness = SettingsKnob(
    id = "calc.rail_freshness",
    screen = SettingsScreenKey.CALCULATOR,
    section = "Rails",
    label = "Freshness gate",
    subtitle = "Refuse on a stale / interpolated anchor",
    synonyms = listOf("freshness", "stale", "rail", "gate", "anchor", "old reading", "interpolated", "refuse", "safety"),
)

private val calcRailPredictedLow = SettingsKnob(
    id = "calc.rail_predicted_low",
    screen = SettingsScreenKey.CALCULATOR,
    section = "Rails",
    label = "Predicted-low veto",
    subtitle = "Block a dose whose lower band dips below the floor",
    synonyms = listOf(
        "predicted low", "veto", "rail", "block", "hypo", "lower band", "forecast low",
        "safety", "refuse",
    ),
)

private val calcPredictedLowFloor = SettingsKnob(
    id = "calc.predicted_low_floor",
    screen = SettingsScreenKey.CALCULATOR,
    section = "Rails",
    label = "Predicted-low floor",
    subtitle = "The mg/dL the forecast's lower band must not dip below",
    synonyms = listOf("predicted low", "floor", "threshold", "hypo", "lower band", "mgdl", "veto level", "rail"),
)

private val calcRailIobCeiling = SettingsKnob(
    id = "calc.rail_iob_ceiling",
    screen = SettingsScreenKey.CALCULATOR,
    section = "Rails",
    label = "IOB ceiling",
    subtitle = "Block when IOB + candidate exceeds the ceiling",
    synonyms = listOf(
        "iob", "insulin on board", "stacking", "rail", "ceiling", "block", "cap", "safety", "veto",
    ),
)

private val calcIobCeiling = SettingsKnob(
    id = "calc.iob_ceiling_units",
    screen = SettingsScreenKey.CALCULATOR,
    section = "Rails",
    label = "IOB ceiling",
    subtitle = "The units of IOB plus candidate dose that may never be exceeded",
    synonyms = listOf(
        "iob", "insulin on board", "stacking", "ceiling", "units", "maximum iob", "threshold", "cap",
    ),
)

private val calcRailConfirm = SettingsKnob(
    id = "calc.rail_confirm",
    screen = SettingsScreenKey.CALCULATOR,
    section = "Rails",
    label = "Mandatory confirmation",
    subtitle = "Require an explicit acknowledgement to accept",
    synonyms = listOf(
        "confirm", "confirmation", "acknowledge", "double check", "are you sure", "dialog",
        "rail", "accept", "safety",
    ),
)

private val calcRailHypoTreatment = SettingsKnob(
    id = "calc.rail_hypo_treatment",
    screen = SettingsScreenKey.CALCULATOR,
    section = "Rails",
    label = "Hypo-treatment path",
    subtitle = "Switch to a carb rescue when low / predicted low",
    synonyms = listOf(
        "hypo treatment", "rescue", "carbs", "sugar", "juice", "glucose tabs", "low",
        "rail", "path", "treat",
    ),
)

internal val settingsCalculatorKnobs = listOf(
    calcObjective,
    calcTargetLow,
    calcTargetHigh,
    calcAimPoint,
    calcHypoWeight,
    calcHyperWeight,
    calcGridMax,
    calcGridStep,
    calcRailFreshness,
    calcRailPredictedLow,
    calcPredictedLowFloor,
    calcRailIobCeiling,
    calcIobCeiling,
    calcRailConfirm,
    calcRailHypoTreatment,
)
