package com.t1dm.feature.insulin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.t1dm.core.model.BasalPreset
import com.t1dm.core.model.BolusPreset
import com.t1dm.core.model.IobCobReadout
import kotlin.math.roundToInt

private enum class Tab { BOLUS, BASAL }

// Insulin slider bounds (Phase 7C, item 11): 1–20 U in 1 U steps ⇒ 20 stops ⇒ 18 interior steps.
private const val DOSE_MIN = 1.0
private const val DOSE_MAX = 20.0
private const val DOSE_STEPS = 18

/**
 * The Phase-4 insulin entry surface (PLAN.private.md deliverable 1 — "manual bolus/basal entry").
 * Both channels feed the model as a **PK action** rate (model-io-curves.md): a bolus is a gamma
 * peaking ~50 min ([BolusPreset]); a basal is a broad, near-flat Bateman ([BasalPreset]). `:app`
 * writes the self-describing `logged_dose` row (gamma params from `CurveEngine.Presets`
 * .bolusGammaParams / Bateman rates), folds units into the wide `sample` (bolusU / basalU), and
 * enqueues `PUT /v1/series/{bolus,basal}`.
 *
 * Stateless + callback-driven, dependency-light. IOB is surfaced at the top with its §3.6-F
 * provenance ("from logged doses only; last logged N min ago") so a nonzero dose taken after a long
 * logging gap is visibly under-counted — the same fact the calculator's decision card will gate on.
 * A "pick a saved insulin type / draw a custom curve" affordance is a seam for the curve-editor work
 * (deliverable 4).
 */
@Composable
fun InsulinScreen(
    iobCob: IobCobReadout? = null,
    previewBolus: (suspend (units: Double) -> DoubleArray)? = null,
    onLogBolus: (units: Double, preset: BolusPreset) -> Unit = { _, _ -> },
    onLogBasal: (units: Double, preset: BasalPreset) -> Unit = { _, _ -> },
) {
    var tab by remember { mutableStateOf(Tab.BOLUS) }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Log insulin", style = MaterialTheme.typography.titleLarge)
        iobCob?.let { IobLine(it) }

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Tab.entries.forEachIndexed { i, t ->
                SegmentedButton(
                    selected = tab == t,
                    onClick = { tab = t },
                    shape = SegmentedButtonDefaults.itemShape(i, Tab.entries.size),
                ) { Text(if (t == Tab.BOLUS) "Bolus" else "Basal") }
            }
        }

        when (tab) {
            Tab.BOLUS -> BolusEntry(previewBolus, onLogBolus)
            Tab.BASAL -> BasalEntry(onLogBasal)
        }
    }
}

@Composable
private fun BolusEntry(
    previewBolus: (suspend (units: Double) -> DoubleArray)?,
    onLogBolus: (Double, BolusPreset) -> Unit,
) {
    var unitsText by remember { mutableStateOf("") }
    val preset = BolusPreset.NOVORAPID
    val units = unitsText.toDoubleOrNull()

    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        UnitsField(unitsText) { unitsText = it }
        Text(preset.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))

        if (previewBolus != null && units != null && units > 0.0) {
            Text(
                "PK action — units per 5 min",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            val curve by produceState(DoubleArray(0), units) {
                value = runCatching { previewBolus(units) }.getOrDefault(DoubleArray(0))
            }
            CurveSparkline(curve, MaterialTheme.colorScheme.primary)
        }

        Button(
            onClick = { units?.let { onLogBolus(it, preset) }; unitsText = "" },
            enabled = units != null && units > 0.0,
            modifier = Modifier.padding(top = 16.dp),
        ) { Text("Log bolus") }
    }
}

@Composable
private fun BasalEntry(onLogBasal: (Double, BasalPreset) -> Unit) {
    var unitsText by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf(BasalPreset.TRESIBA) }
    val units = unitsText.toDoubleOrNull()

    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        UnitsField(unitsText) { unitsText = it }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasalPreset.entries.forEach { p ->
                FilterChip(selected = preset == p, onClick = { preset = p }, label = { Text(p.label) })
            }
        }
        Text(
            "Logged as a discrete long-acting injection. A repeating daily schedule + the day-long " +
                "basal-rate search live in the dose calculator (Settings → Basal).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(
            onClick = { units?.let { onLogBasal(it, preset) }; unitsText = "" },
            enabled = units != null && units > 0.0,
            modifier = Modifier.padding(top = 16.dp),
        ) { Text("Log basal") }
    }
}

@Composable
private fun UnitsField(value: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it.filter { c -> c.isDigit() || c == '.' }) },
            label = { Text("Units (U)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // Insulin slider (1–20 U, 1 U steps) bound to the SAME dose value: dragging rewrites the
        // text, typing repositions the thumb (rounded/clamped). Empty/out-of-range text parks the
        // thumb at 1 U without clobbering what the user typed.
        val units = value.toDoubleOrNull()
        Slider(
            value = (units ?: DOSE_MIN).coerceIn(DOSE_MIN, DOSE_MAX).toFloat(),
            onValueChange = { onChange(it.roundToInt().toString()) },
            valueRange = DOSE_MIN.toFloat()..DOSE_MAX.toFloat(),
            steps = DOSE_STEPS,
        )
    }
}

@Composable
private fun IobLine(r: IobCobReadout) {
    val gap = r.minsSinceLastLoggedInsulin
    val provenance = when {
        gap == null -> "no insulin logged yet"
        else -> "logged doses only · last logged ${gap} min ago"
    }
    Column(Modifier.padding(top = 4.dp)) {
        Text(
            "IOB ${"%.2f".format(r.iobU)} U   ·   COB ${"%.0f".format(r.cobG)} g",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
        Text(
            provenance,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
    }
}

/** A tiny filled sparkline of a per-5-min PK curve (preview only). */
@Composable
internal fun CurveSparkline(values: DoubleArray, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(56.dp).padding(vertical = 4.dp)) {
        if (values.isEmpty()) return@Canvas
        val peak = values.maxOrNull()?.toFloat() ?: return@Canvas
        if (peak <= 0f) return@Canvas
        val n = values.size
        val dx = size.width / (n - 1).coerceAtLeast(1)
        val path = Path().apply {
            moveTo(0f, size.height)
            for (i in 0 until n) {
                lineTo(i * dx, size.height - (values[i].toFloat() / peak) * size.height * 0.9f)
            }
            lineTo((n - 1) * dx, size.height)
            close()
        }
        drawPath(path, color.copy(alpha = 0.25f))
    }
}
