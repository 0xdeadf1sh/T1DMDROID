package com.t1dm.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.model.BezierCurve
import com.t1dm.ui.graph.CurveEditor
import com.t1dm.ui.graph.CurvePreview

/**
 * Read-only view of the carb-appearance / insulin-action curve PRESET defaults (the parametric
 * gamma / Bateman shapes — model-io-curves.md) PLUS the Phase 7D BÉZIER custom-curve designers (item
 * 19). The parametric presets remain the default; the Bézier editors are the CUSTOM-EDIT mode — drag
 * the control points to author a smooth carb-appearance or insulin-action template that is
 * area-normalised to the dose total exactly like the presets, then saved. Fresh custom foods /
 * insulin types in the builders start from these templates.
 *
 * The clinical insulin preset library is NOT chosen here. It is chosen on the insulin panel, at the
 * moment of the dose, and the writer commits what the panel picked. A picker on this screen was the
 * previous arrangement and it made the panel's own presets decorative: the two surfaces named
 * different insulins and only the confirmation dialog could see the disagreement.
 */
data class CurveParams(
    val basalKaPerHour: Double,
    val basalKePerHour: Double,
    val lantusDiaHours: Double,
    val tresibaDiaHours: Double,
    val carbHighGiK: Double,
    val carbHighGiTheta: Double,
    val carbLowGiK: Double,
    val carbLowGiTheta: Double,
)

@Composable
fun CurveParamsScreen(
    params: CurveParams,
    carbCurve: BezierCurve,
    insulinCurve: BezierCurve,
    onSaveCarbCurve: (BezierCurve) -> Unit,
    onSaveInsulinCurve: (BezierCurve) -> Unit,
) {
    SettingsScaffold(SettingsScreenKey.CURVES) {
        SettingsNote("How a dose becomes its curve")

        SettingsSectionHeader("Custom carb-appearance curve (Bézier)")
        SettingsNote("Drag to shape; area = meal total")
        SettingsAnchor(curveCarbBezier) {
            BezierDesigner(carbCurve, defaultDurationMin = 180.0, onSave = onSaveCarbCurve)
        }

        SettingsSectionHeader("Custom insulin-action curve (Bézier)")
        SettingsNote("PK action shape (not delivery) — units-per-5-min, area = the dose.")
        SettingsAnchor(curveInsulinBezier) {
            BezierDesigner(insulinCurve, defaultDurationMin = 300.0, onSave = onSaveInsulinCurve)
        }

        SettingsSectionHeader("Long-acting basal preset (Bateman)")
        Kv("Absorption kₐ", "%.2f /h".format(params.basalKaPerHour))
        Kv("Elimination kₑ", "%.2f /h".format(params.basalKePerHour))
        Kv("Lantus duration", "%.0f h".format(params.lantusDiaHours))
        Kv("Tresiba duration", "%.0f h".format(params.tresibaDiaHours))

        SettingsSectionHeader("Carb appearance preset (GI → gamma)")
        SettingsNote("High-GI carbs peak early and sharp; low-GI spread out.")
        Kv("High GI k / θ", "%.1f / %.0f".format(params.carbHighGiK, params.carbHighGiTheta))
        Kv("Low GI k / θ", "%.1f / %.0f".format(params.carbLowGiK, params.carbLowGiTheta))
    }
}

@Composable
private fun BezierDesigner(initial: BezierCurve, defaultDurationMin: Double, onSave: (BezierCurve) -> Unit) {
    var resetKey by remember { mutableIntStateOf(0) }
    var draft by remember(resetKey) { mutableStateOf(initial) }
    val haptics = rememberT1dmHaptics()
    CurveEditor(curve = draft, onChange = { draft = it }, resetKey = resetKey)
    val sampled = draft.sampleNormalized(1.0)
    val degenerate = sampled.all { it <= 0.0 }
    CurvePreview(values = sampled, height = 100.dp, modifier = Modifier.padding(top = 4.dp))
    val peak = sampled.indices.maxByOrNull { sampled[it] } ?: 0
    Text(
        "Peak ≈ ${(peak + 1) * 5} min · duration ${"%.0f".format(draft.durationMin)} min",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = FontFamily.Monospace,
    )
    // The drag that flattens the curve is the moment the shape stops being savable, and the finger is
    // still on the glass — so the warning is felt exactly when it becomes true, and only then.
    androidx.compose.runtime.LaunchedEffect(degenerate) {
        if (degenerate) haptics.perform(HapticEvent.Warn)
    }
    if (degenerate) {
        Text(
            "No positive area — raise a point above baseline",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { haptics.perform(HapticEvent.Confirm); onSave(draft) },
            enabled = !degenerate,
        ) { Text("Save custom curve") }
        OutlinedButton(
            // Discarding the drawing is a refusal of what was drawn, not a confirmation of anything.
            onClick = {
                haptics.perform(HapticEvent.Reject)
                draft = BezierCurve.default(defaultDurationMin)
                resetKey++
            },
        ) { Text("Reset to hump") }
    }
}

@Composable
private fun Kv(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

// ── search index (see SettingsIndex.kt) ───────────────────────────────────────────────────────────
//
// The Bateman / gamma read-outs below the designers are derived facts, not knobs — they are not
// indexed. Neither is the clinical insulin preset: it is not a setting at all, it is picked per dose
// on the insulin panel, and an index entry pointing here would send someone looking for "humalog" to
// a screen that cannot change it.

private val curveCarbBezier = SettingsKnob(
    id = "curves.carb_bezier",
    screen = SettingsScreenKey.CURVES,
    section = "Custom carb-appearance curve (Bézier)",
    label = "Custom carb-appearance curve (Bézier)",
    subtitle = "Draw the grams-per-5-min appearance shape; its area is the meal total",
    synonyms = listOf(
        "carb", "carbs", "carbohydrate", "meal", "appearance", "ra", "absorption", "bezier",
        "curve", "designer", "custom curve", "gi", "glycemic index", "gamma", "draw", "shape",
    ),
)

private val curveInsulinBezier = SettingsKnob(
    id = "curves.insulin_bezier",
    screen = SettingsScreenKey.CURVES,
    section = "Custom insulin-action curve (Bézier)",
    label = "Custom insulin-action curve (Bézier)",
    subtitle = "Draw the units-per-5-min PK action shape (not delivery); its area is the dose",
    synonyms = listOf(
        "insulin", "action", "pk", "bezier", "curve", "designer", "custom curve", "iob",
        "pharmacokinetics", "draw", "shape", "duration", "peak",
    ),
)

internal val settingsCurveKnobs = listOf(
    curveCarbBezier,
    curveInsulinBezier,
)
