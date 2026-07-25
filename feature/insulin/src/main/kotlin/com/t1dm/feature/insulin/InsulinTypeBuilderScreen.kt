package com.t1dm.feature.insulin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.ConfirmLogDialog
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.PendingLog
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.design.verticalScrollbar
import com.t1dm.core.model.InsulinKind
import com.t1dm.core.model.InsulinType
import com.t1dm.core.model.BezierCurve
import com.t1dm.ui.graph.CurveEditor
import com.t1dm.ui.graph.CurvePreview

/**
 * The insulin **type** builder (Phase 4 deliverable 4, `:feature:insulin`) — the
 * "pick a saved insulin type / draw a custom curve" seam the dose-entry [InsulinScreen] points at.
 * It lists the quick presets (Novorapid gamma; Lantus/Tresiba Bateman) plus any user-defined types,
 * shows the live **PK-action** preview for a trial dose ([onResolve]), lets a dose be logged against
 * the selected type, and defines a new custom type — either analytically (gamma `k`/`theta` for a
 * bolus, Bateman `ka`/`ke` for a basal) or by DRAWING a custom action curve with the reusable
 * [CurveEditor]. Stateless + callback-driven.
 */
@Composable
fun InsulinTypeBuilderScreen(
    types: List<InsulinType>,
    onResolve: suspend (InsulinType, Double) -> List<Double>,
    onSaveType: (InsulinType) -> Unit,
    onDeleteType: (Long) -> Unit,
    onLogDose: (InsulinType, Double) -> Unit = { _, _ -> },
) {
    var selectedId by remember(types) { mutableStateOf(types.firstOrNull()?.id) }
    val selected = types.firstOrNull { it.id == selectedId } ?: types.firstOrNull()
    var unitsText by remember { mutableStateOf("") }
    val units = unitsText.toDoubleOrNull()
    val scroll = rememberScrollState()
    // Which (type, units) pair the Log press proposes — the button is rendered per selected type, so
    // the confirmation has to carry the type with it rather than re-read the selection.
    var pending by remember { mutableStateOf<Pair<InsulinType, PendingLog.Dose>?>(null) }
    val haptics = rememberT1dmHaptics()

    Column(
        Modifier.fillMaxSize().verticalScrollbar(scroll).verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            types.forEach { t ->
                FilterChip(
                    selected = selected?.id == t.id,
                    onClick = { haptics.perform(HapticEvent.SegmentTick); selectedId = t.id },
                    label = { Text(if (t.builtin) t.name else "${t.name}*") },
                )
            }
        }

        selected?.let { type ->
            Text(
                "${kindLabel(type.kind)} · DIA ${(type.durationMin / 60).toInt()} h" +
                    (type.customCurve?.let { " · custom curve" } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
            OutlinedTextField(
                value = unitsText,
                onValueChange = { unitsText = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Trial dose (U)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (units != null && units > 0.0) {
                Text("PK action — units per 5 min", style = MaterialTheme.typography.labelMedium)
                val curve by produceState(emptyList<Double>(), type, units) {
                    value = runCatching { onResolve(type, units) }.getOrDefault(emptyList())
                }
                CurvePreview(values = curve)
                // Propose only; the dialog and the receipt carry the rest of the beat.
                Button(
                    onClick = {
                        haptics.perform(HapticEvent.Tap)
                        pending = type to PendingLog.Dose(units, type.kind, type.name)
                    },
                ) { Text("Log dose") }
            }
            if (!type.builtin) {
                TextButton(
                    onClick = { haptics.perform(HapticEvent.Reject); onDeleteType(type.id) },
                ) { Text("Delete type") }
            }
        }

        HorizontalDivider()
        CustomTypeBuilder(onSaveType)
    }

    pending?.let { (type, p) ->
        ConfirmLogDialog(
            pending = p,
            onConfirm = { onLogDose(type, p.units); unitsText = ""; pending = null },
            onDismiss = { pending = null },
        )
    }
}

@Composable
private fun CustomTypeBuilder(onSaveType: (InsulinType) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(InsulinKind.BOLUS) }
    var durText by remember { mutableStateOf("300") }
    var p1Text by remember { mutableStateOf("") } // k (bolus) or ka (basal)
    var p2Text by remember { mutableStateOf("") } // theta (bolus) or ke (basal)
    var drawCurve by remember { mutableStateOf(false) }
    var curve by remember { mutableStateOf(BezierCurve.default(300.0)) }
    val haptics = rememberT1dmHaptics()

    Text("Add a custom insulin type", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = name, onValueChange = { name = it }, label = { Text("Name") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = kind == InsulinKind.BOLUS,
            onClick = { haptics.perform(HapticEvent.SegmentTick); kind = InsulinKind.BOLUS },
            label = { Text("Bolus (gamma)") },
        )
        FilterChip(
            selected = kind == InsulinKind.BASAL,
            onClick = { haptics.perform(HapticEvent.SegmentTick); kind = InsulinKind.BASAL },
            label = { Text("Basal (Bateman)") },
        )
    }
    OutlinedTextField(
        value = durText,
        onValueChange = { durText = it.filter { c -> c.isDigit() } },
        label = { Text("Duration of action (min)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = drawCurve, onCheckedChange = { on -> haptics.toggled(on); drawCurve = on })
        Text(
            "Draw a custom action curve (overrides parameters)",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
    val curveDegenerate = drawCurve && curve.isDegenerate()
    // Felt at the drag that flattens the curve, not at the dead Save button noticed afterwards.
    LaunchedEffect(curveDegenerate) {
        if (curveDegenerate) haptics.perform(HapticEvent.Warn)
    }
    if (drawCurve) {
        CurveEditor(curve = curve, onChange = { curve = it }, resetKey = durText)
        if (curveDegenerate) {
            Text(
                "Flat curve encodes no insulin — draw a hump or turn it off",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = p1Text,
                onValueChange = { p1Text = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text(if (kind == InsulinKind.BOLUS) "shape k" else "ka /h") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = p2Text,
                onValueChange = { p2Text = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text(if (kind == InsulinKind.BOLUS) "scale θ" else "ke /h") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true, modifier = Modifier.weight(1f),
            )
        }
    }
    Button(
        onClick = {
            val dur = durText.toDoubleOrNull() ?: run {
                haptics.perform(HapticEvent.Reject)
                return@Button
            }
            haptics.perform(HapticEvent.Confirm)
            val p1 = p1Text.toDoubleOrNull()
            val p2 = p2Text.toDoubleOrNull()
            val type = InsulinType(
                id = 0L,
                name = name.trim(),
                kind = kind,
                durationMin = if (drawCurve) curve.durationMin else dur,
                k = if (!drawCurve && kind == InsulinKind.BOLUS) p1 else null,
                theta = if (!drawCurve && kind == InsulinKind.BOLUS) p2 else null,
                kaPerHour = if (!drawCurve && kind == InsulinKind.BASAL) p1 else null,
                kePerHour = if (!drawCurve && kind == InsulinKind.BASAL) p2 else null,
                customCurve = if (drawCurve) curve.sampleNormalized(1.0) else null,
                builtin = false,
            )
            onSaveType(type)
            name = ""; p1Text = ""; p2Text = ""; drawCurve = false
        },
        enabled = name.isNotBlank() && !curveDegenerate,
    ) { Text("Save type") }
}

private fun kindLabel(kind: InsulinKind): String =
    if (kind == InsulinKind.BOLUS) "Rapid-acting bolus" else "Long-acting basal"
