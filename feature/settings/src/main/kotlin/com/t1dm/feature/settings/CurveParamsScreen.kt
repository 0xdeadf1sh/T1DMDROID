package com.t1dm.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.model.BezierCurve
import com.t1dm.core.model.InsulinFamily
import com.t1dm.core.model.InsulinPresetSpec
import com.t1dm.ui.graph.CurveEditor
import com.t1dm.ui.graph.CurvePreview

/**
 * Read-only view of the carb-appearance / insulin-action curve PRESET defaults (the parametric
 * gamma / Bateman shapes — model-io-curves.md) PLUS the Phase 7D BÉZIER custom-curve designers (item
 * 19). The parametric presets remain the default; the Bézier editors are the CUSTOM-EDIT mode — drag
 * the control points to author a smooth carb-appearance or insulin-action template that is
 * area-normalised to the dose total exactly like the presets, then saved. Fresh custom foods /
 * insulin types in the builders start from these templates.
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
    // Issue 19 — the selectable clinical insulin preset library.
    presetCatalog: List<InsulinPresetSpec> = emptyList(),
    selectedRapidLabel: String = "",
    selectedBasalLabel: String = "",
    onSelectRapid: (String) -> Unit = {},
    onSelectBasal: (String) -> Unit = {},
    previewPreset: (suspend (InsulinPresetSpec) -> DoubleArray)? = null,
) {
    SettingsScaffold(SettingsScreenKey.CURVES) {
        SettingsNote("How a dose becomes its curve")

        if (presetCatalog.isNotEmpty()) {
            InsulinPresetSection(
                catalog = presetCatalog,
                selectedRapidLabel = selectedRapidLabel,
                selectedBasalLabel = selectedBasalLabel,
                onSelectRapid = onSelectRapid,
                onSelectBasal = onSelectBasal,
                previewPreset = previewPreset,
            )
        }

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

/**
 * The issue-19 clinical insulin preset picker. Every preset is a clinically-grounded, published-PK
 * shape; the default is now the first clinical preset of each family (rapid/basal) rather than a
 * simulator curve. Each preset shows its published peak/DIA + a one-line citation; selecting one is
 * silent and immediate and applies to NEWLY-logged doses only (past logs are self-describing and keep
 * their original curve). Per the user's decision there is NO off-distribution warning — only the
 * neutral factual labels below.
 */
@Composable
private fun InsulinPresetSection(
    catalog: List<InsulinPresetSpec>,
    selectedRapidLabel: String,
    selectedBasalLabel: String,
    onSelectRapid: (String) -> Unit,
    onSelectBasal: (String) -> Unit,
    previewPreset: (suspend (InsulinPresetSpec) -> DoubleArray)?,
) {
    // Partition by family.
    val rapids = catalog.filter { it.family == InsulinFamily.RapidExp }
    val basals = catalog.filter { it.family == InsulinFamily.BasalBateman }

    SettingsSectionHeader("Insulin action preset (clinical, selectable)")
    SettingsNote("Population PK per insulin — peak/DIA and citation")

    SettingsAnchor(curveRapidPreset) {
        PresetGroup(curveRapidPreset.label, rapids, selectedRapidLabel, onSelectRapid, previewPreset)
    }
    SettingsAnchor(curveBasalPreset) {
        PresetGroup(curveBasalPreset.label, basals, selectedBasalLabel, onSelectBasal, previewPreset)
    }
}

@Composable
private fun PresetGroup(
    title: String,
    presets: List<InsulinPresetSpec>,
    selectedLabel: String,
    onSelect: (String) -> Unit,
    previewPreset: (suspend (InsulinPresetSpec) -> DoubleArray)?,
) {
    if (presets.isEmpty()) return
    val haptics = rememberT1dmHaptics()
    val selected = presets.firstOrNull { it.label == selectedLabel } ?: presets.first()
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
    presets.forEach { spec ->
        // The row and its radio are two entries into the same single-choice detent, so both speak the
        // one SegmentTick — never a Tap on the row and a tick on the dot.
        val pick = { haptics.perform(HapticEvent.SegmentTick); onSelect(spec.label) }
        Row(
            Modifier.fillMaxWidth()
                .selectable(selected = spec.label == selected.label, onClick = pick)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = spec.label == selected.label, onClick = pick)
            Column(Modifier.padding(start = 4.dp).weight(1f)) {
                Text(spec.label, style = MaterialTheme.typography.bodyMedium)
                val peakTxt = if (spec.peakMin > 0.0) "peak ${"%.0f".format(spec.peakMin)} min · " else ""
                Text(
                    "${peakTxt}DIA ${"%.1f".format(spec.diaMin / 60.0)} h — ${spec.citation}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    // Live preview of the selected preset's resolved action curve (5 U reference).
    if (previewPreset != null) {
        val curve by produceState(DoubleArray(0), selected) { value = previewPreset(selected) }
        if (curve.isNotEmpty()) {
            CurvePreview(
                values = curve.asList(),
                height = 90.dp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
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
// indexed, and their vocabulary is folded into the two preset entries instead.

private const val PRESET_SECTION = "Insulin action preset (clinical, selectable)"

private val curveRapidPreset = SettingsKnob(
    id = "curves.rapid_preset",
    screen = SettingsScreenKey.CURVES,
    section = PRESET_SECTION,
    label = "Rapid-acting (bolus)",
    subtitle = "The published-PK action shape used for IOB and dosing on newly-logged bolus insulin",
    synonyms = listOf(
        "rapid", "bolus", "fast acting", "insulin", "preset", "pk", "action curve", "iob",
        "humalog", "novorapid", "novolog", "apidra", "fiasp", "lyumjev", "aspart", "lispro",
        "peak", "dia", "duration of action",
    ),
)

private val curveBasalPreset = SettingsKnob(
    id = "curves.basal_preset",
    screen = SettingsScreenKey.CURVES,
    section = PRESET_SECTION,
    label = "Long-acting (basal)",
    subtitle = "The Bateman action shape used for newly-logged basal insulin",
    synonyms = listOf(
        "basal", "long acting", "background", "insulin", "preset", "bateman", "pk", "iob",
        "lantus", "tresiba", "levemir", "toujeo", "glargine", "degludec", "detemir",
        "absorption", "elimination", "dia", "duration",
    ),
)

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
    curveRapidPreset,
    curveBasalPreset,
    curveCarbBezier,
    curveInsulinBezier,
)
