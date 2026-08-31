package com.t1dm.feature.insulin

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.ConfirmLogDialog
import com.t1dm.core.design.CurveSparkline
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.IobCobLine
import com.t1dm.core.design.PendingLog
import com.t1dm.core.design.fadingEdges
import com.t1dm.core.design.rememberHapticDetent
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.design.verticalScrollbar
import com.t1dm.core.model.InsulinFamily
import com.t1dm.core.model.InsulinKind
import com.t1dm.core.model.InsulinPresetSpec
import com.t1dm.core.model.IobCobReadout
import com.t1dm.core.model.SensitivityEstimate
import com.t1dm.core.model.UnitSpace
import kotlin.math.roundToInt

private enum class Tab { BOLUS, BASAL }

// 1–20 U in 1 U steps ⇒ 20 stops ⇒ 18 interior steps.
private const val DOSE_MIN = 1.0
private const val DOSE_MAX = 20.0
private const val DOSE_STEPS = 18

/** Far short of the ~309 digits that reach +Infinity. */
private const val MAX_UNITS_CHARS = 8

/** Finite, not just `> 0.0`: +Infinity poisons IOB and defeats the §3.6-C ceiling, since every
 *  comparison against NaN is false. */
private fun Double?.loggableDose(): Double? = this?.takeIf { it.isFinite() && it > 0.0 }

/**
 * Owns EXACTLY ONE vertical scroll, with [footer] inside it: a sibling placed after this screen in a
 * plain Column is measured with `maxHeight = 0`.
 * [initialRapidLabel]/[initialBasalLabel] seed each tab from the insulin last logged of that kind.
 */
@Composable
fun InsulinScreen(
    iobCob: IobCobReadout? = null,
    // Display-only: the dose calculator searches the model directly and never reads these.
    sensitivity: SensitivityEstimate? = null,
    unit: UnitSpace = UnitSpace.MgDl,
    presetCatalog: List<InsulinPresetSpec> = emptyList(),
    initialRapidLabel: String? = null,
    initialBasalLabel: String? = null,
    previewCurve: (suspend (units: Double, preset: InsulinPresetSpec) -> DoubleArray)? = null,
    onLogBolus: (units: Double, presetLabel: String) -> Unit = { _, _ -> },
    onLogBasal: (units: Double, presetLabel: String) -> Unit = { _, _ -> },
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    var tab by remember { mutableStateOf(Tab.BOLUS) }
    val scroll = rememberScrollState()
    val haptics = rememberT1dmHaptics()
    val rapids = remember(presetCatalog) { presetCatalog.filter { it.family == InsulinFamily.RapidExp } }
    val basals = remember(presetCatalog) { presetCatalog.filter { it.family == InsulinFamily.BasalBateman } }

    Column(
        Modifier.fillMaxSize().verticalScrollbar(scroll).fadingEdges(scroll).verticalScroll(scroll)
            .padding(16.dp),
    ) {
        iobCob?.let { IobCobLine(it, sensitivity, unit, provenance = iobProvenance(it)) }

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Tab.entries.forEachIndexed { i, t ->
                SegmentedButton(
                    selected = tab == t,
                    onClick = { haptics.perform(HapticEvent.SegmentTick); tab = t },
                    shape = SegmentedButtonDefaults.itemShape(i, Tab.entries.size),
                ) { Text(if (t == Tab.BOLUS) "Bolus" else "Basal") }
            }
        }

        when (tab) {
            Tab.BOLUS -> DoseEntry(InsulinKind.BOLUS, rapids, initialRapidLabel, previewCurve, onLogBolus)
            Tab.BASAL -> DoseEntry(InsulinKind.BASAL, basals, initialBasalLabel, previewCurve, onLogBasal)
        }

        footer()
    }
}

/** Disabled until a preset exists as well as a dose: the catalogue arrives asynchronously, and the
 *  confirmation has to name an insulin. */
@Composable
private fun DoseEntry(
    kind: InsulinKind,
    presets: List<InsulinPresetSpec>,
    initialLabel: String?,
    previewCurve: (suspend (Double, InsulinPresetSpec) -> DoubleArray)?,
    onLog: (Double, String) -> Unit,
) {
    var unitsText by remember { mutableStateOf("") }
    var selectedLabel by remember(presets, initialLabel) {
        mutableStateOf(presets.firstOrNull { it.label == initialLabel }?.label ?: presets.firstOrNull()?.label)
    }
    val preset = presets.firstOrNull { it.label == selectedLabel } ?: presets.firstOrNull()
    val dose = unitsText.toDoubleOrNull().loggableDose()
    var pending by remember { mutableStateOf<PendingLog.Dose?>(null) }
    val haptics = rememberT1dmHaptics()
    val presetScroll = rememberScrollState()

    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        UnitsField(unitsText) { unitsText = it }

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(presetScroll),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presets.forEach { p ->
                FilterChip(
                    selected = p.label == preset?.label,
                    onClick = { haptics.perform(HapticEvent.SegmentTick); selectedLabel = p.label },
                    label = { Text(p.label, maxLines = 1) },
                )
            }
        }
        preset?.let {
            Text(
                it.citation,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (previewCurve != null && dose != null && preset != null) {
            Text(
                if (kind == InsulinKind.BOLUS) {
                    "PK action — units per 5 min"
                } else {
                    "PK action — units per 5 min (broad + near-flat by design)"
                },
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            val curve by produceState(DoubleArray(0), dose, preset) {
                value = runCatching { previewCurve(dose, preset) }.getOrDefault(DoubleArray(0))
            }
            CurveSparkline(
                curve,
                if (kind == InsulinKind.BOLUS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
            )
        }

        if (kind == InsulinKind.BASAL) {
            Text(
                "Logs a one-off injection — schedule + basal-rate search in Settings → Basal",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Button(
            onClick = {
                haptics.perform(HapticEvent.Tap)
                if (dose != null && preset != null) pending = PendingLog.Dose(dose, kind, preset.label)
            },
            enabled = dose != null && preset != null,
            modifier = Modifier.padding(top = 16.dp),
        ) { Text(if (kind == InsulinKind.BOLUS) "Log bolus" else "Log basal") }
    }

    pending?.let { p ->
        ConfirmLogDialog(
            pending = p,
            // `p.typeLabel`, not the chip row's label now: the dialog restated this one.
            onConfirm = { onLog(p.units, p.typeLabel); unitsText = ""; pending = null },
            onDismiss = { pending = null },
        )
    }
}

@Composable
private fun UnitsField(value: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it.filter { c -> c.isDigit() || c == '.' }.take(MAX_UNITS_CHARS)) },
            label = { Text("Units (U)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val units = value.toDoubleOrNull()
        // Keyed off the drag, not `units` — typing moves that too.
        val doseDetent = rememberHapticDetent()
        Slider(
            value = (units ?: DOSE_MIN).coerceIn(DOSE_MIN, DOSE_MAX).toFloat(),
            onValueChange = { doseDetent.at(it.roundToInt()); onChange(it.roundToInt().toString()) },
            valueRange = DOSE_MIN.toFloat()..DOSE_MAX.toFloat(),
            steps = DOSE_STEPS,
        )
    }
}

/** §3.6-F. */
private fun iobProvenance(r: IobCobReadout): String =
    r.minsSinceLastLoggedInsulin
        ?.let { "logged doses only · last logged $it min ago" }
        ?: "no insulin logged yet"
