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
    val bolusGammaK: Double,
    val bolusGammaTheta: Double,
    val bolusDiaBaseHours: Double,
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
    SettingsScaffold("Curve & PK parameters") {
        SettingsNote(
            "How the app turns a logged meal or dose into the absorption / action curve the model and " +
                "calculator use (model-io-curves.md). The parametric presets below are the defaults; the " +
                "Bézier designers let you author a custom shape that normalises to the dose total.",
        )

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
        SettingsNote("Drag the control points; tap empty space to add one, long-press to remove. Grams-per-5-min, area = the meal total.")
        BezierDesigner(carbCurve, defaultDurationMin = 180.0, onSave = onSaveCarbCurve)

        SettingsSectionHeader("Custom insulin-action curve (Bézier)")
        SettingsNote("The PK action shape (not delivery) — units-per-5-min, area = the delivered dose.")
        BezierDesigner(insulinCurve, defaultDurationMin = 300.0, onSave = onSaveInsulinCurve)

        SettingsSectionHeader("Rapid-acting bolus preset (dose-scaled gamma)")
        Kv("Shape k", "%.2f".format(params.bolusGammaK))
        Kv("Scale θ (min)", "%.1f".format(params.bolusGammaTheta))
        Kv("Base duration of action", "%.1f h".format(params.bolusDiaBaseHours))

        SettingsSectionHeader("Long-acting basal preset (Bateman)")
        Kv("Absorption kₐ", "%.2f /h".format(params.basalKaPerHour))
        Kv("Elimination kₑ", "%.2f /h".format(params.basalKePerHour))
        Kv("Lantus duration", "%.0f h".format(params.lantusDiaHours))
        Kv("Tresiba duration", "%.0f h".format(params.tresibaDiaHours))

        SettingsSectionHeader("Carb appearance preset (GI → gamma)")
        SettingsNote("High-GI carbs peak early and sharp; low-GI carbs spread out.")
        Kv("High GI k / θ", "%.1f / %.0f".format(params.carbHighGiK, params.carbHighGiTheta))
        Kv("Low GI k / θ", "%.1f / %.0f".format(params.carbLowGiK, params.carbLowGiTheta))
    }
}

/**
 * The issue-19 clinical insulin preset picker. The DEFAULT (and every unchanged install) is the
 * in-distribution simulator curve, so forecasts never silently degrade; choosing a clinically-grounded
 * preset makes the calculator/IOB more clinically faithful. Each preset shows its published peak/DIA +
 * a one-line citation; selecting one is silent and immediate and applies to NEWLY-logged doses only
 * (past logs are self-describing and keep their original curve). Per the user's decision there is NO
 * off-distribution warning — only the neutral factual labels below.
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
    // Partition by family; the two in-distribution simulator defaults head their respective lists.
    val simBolus = catalog.firstOrNull { it.family == InsulinFamily.SimulatorGamma && it.label.startsWith("Simulator bolus") }
    val simBasal = catalog.firstOrNull { it.family == InsulinFamily.SimulatorGamma && it.label.startsWith("Simulator basal") }
    val rapids = listOfNotNull(simBolus) + catalog.filter { it.family == InsulinFamily.RapidExp }
    val basals = listOfNotNull(simBasal) + catalog.filter { it.family == InsulinFamily.BasalBateman }

    SettingsSectionHeader("Insulin action preset (clinical, selectable)")
    SettingsNote(
        "The default is the simulator-matched shape the model was trained on. The clinical presets encode " +
            "published population PK for the insulins you use — more faithful for IOB and the dosing " +
            "calculator. Each shows its peak/DIA and citation below.",
    )

    PresetGroup("Rapid-acting (bolus)", rapids, selectedRapidLabel, onSelectRapid, previewPreset)
    PresetGroup("Long-acting (basal)", basals, selectedBasalLabel, onSelectBasal, previewPreset)
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
    val selected = presets.firstOrNull { it.label == selectedLabel } ?: presets.first()
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
    presets.forEach { spec ->
        Row(
            Modifier.fillMaxWidth()
                .selectable(selected = spec.label == selected.label, onClick = { onSelect(spec.label) })
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = spec.label == selected.label, onClick = { onSelect(spec.label) })
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
    if (degenerate) {
        Text(
            "This shape has no positive area — it would normalise to nothing. Drag a control point above " +
                "the baseline before saving.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onSave(draft) }, enabled = !degenerate) { Text("Save custom curve") }
        OutlinedButton(onClick = { draft = BezierCurve.default(defaultDurationMin); resetKey++ }) { Text("Reset to hump") }
    }
}

@Composable
private fun Kv(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}
