package com.t1dm.feature.meals

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.t1dm.core.model.GiChip
import com.t1dm.core.model.IobCobReadout
import com.t1dm.core.model.RecentMeal
import kotlin.math.roundToInt

// Carb slider bounds (Phase 7C, item 9): 5–120 g in 5 g steps ⇒ 24 stops ⇒ 22 interior Slider steps.
private const val CARB_MIN = 5.0
private const val CARB_MAX = 120.0
private const val CARB_STEPS = 22

/**
 * The Phase-4 carb entry surface (deliverable 1 — "manual carb entry"). Logs a meal
 * as grams + a glycemic index that parameterizes the **appearance (Ra)** gamma (model-io-curves.md:
 * carbs feed the model as grams-per-5-min Ra, juice ⇒ high early peak, bread ⇒ spread). `:app` writes
 * the self-describing `logged_meal` row (via `CurveEngine.Presets.carbGammaForGi`), folds grams into
 * the wide `sample`, and enqueues `PUT /v1/series/carbs`.
 *
 * Stateless + callback-driven, dependency-light (only `:core:*`) like the Phase-1 dashboard. The
 * live [previewCurve] sparkline shows the exact Ra the model will see. A "pick a saved meal / food
 * from the dictionary" affordance is a documented seam for the meal-builder work (deliverable 3).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MealsScreen(
    iobCob: IobCobReadout? = null,
    recentMeals: List<RecentMeal> = emptyList(),
    previewCurve: (suspend (grams: Double, gi: Double) -> DoubleArray)? = null,
    onLogMeal: (grams: Double, gi: Double) -> Unit = { _, _ -> },
) {
    var gramsText by remember { mutableStateOf("") }
    var gi by remember { mutableFloatStateOf(GiChip.MIXED.gi.toFloat()) }
    val grams = gramsText.toDoubleOrNull()

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        iobCob?.let { CobLine(it) }

        OutlinedTextField(
            value = gramsText,
            onValueChange = { gramsText = it.filter { c -> c.isDigit() || c == '.' } },
            label = { Text("Carbs (g)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        // Carb slider (5–120 g, 5 g steps) bound to the SAME grams value: dragging rewrites the text,
        // typing repositions the thumb (rounded/clamped into range). An out-of-range or empty text
        // simply parks the thumb at the low end without overwriting what the user typed.
        Slider(
            value = (grams ?: CARB_MIN).coerceIn(CARB_MIN, CARB_MAX).toFloat(),
            onValueChange = { gramsText = it.roundToInt().toString() },
            valueRange = CARB_MIN.toFloat()..CARB_MAX.toFloat(),
            steps = CARB_STEPS,
        )

        if (recentMeals.isNotEmpty()) {
            Text(
                "Recent meals",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                recentMeals.forEach { m ->
                    AssistChip(
                        onClick = { gramsText = m.grams.toInt().toString(); gi = m.gi.toFloat() },
                        label = { Text(m.label) },
                        leadingIcon = { Text("↺", style = MaterialTheme.typography.labelLarge) },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
            }
        }

        Text(
            "Glycemic index: ${gi.toInt()}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GiChip.entries.forEach { chip ->
                FilterChip(
                    selected = gi.toInt() == chip.gi.toInt(),
                    onClick = { gi = chip.gi.toFloat() },
                    label = { Text(chip.label) },
                )
            }
        }
        Slider(value = gi, onValueChange = { gi = it }, valueRange = 0f..100f)

        if (previewCurve != null && grams != null && grams > 0.0) {
            Text(
                "Appearance (Ra) — grams per 5 min",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            val curve by produceState(DoubleArray(0), grams, gi) {
                value = runCatching { previewCurve(grams, gi.toDouble()) }.getOrDefault(DoubleArray(0))
            }
            CurveSparkline(curve, MaterialTheme.colorScheme.secondary)
        }

        Button(
            onClick = { grams?.let { onLogMeal(it, gi.toDouble()) }; gramsText = "" },
            enabled = grams != null && grams > 0.0,
            modifier = Modifier.padding(top = 16.dp),
        ) { Text("Log meal") }
    }
}

@Composable
private fun CobLine(r: IobCobReadout) {
    Text(
        "COB ${"%.0f".format(r.cobG)} g   ·   IOB ${"%.2f".format(r.iobU)} U",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** A tiny filled sparkline of a per-5-min curve — a preview only, not the dashboard overlay. N7 — the
 *  rendered curve is 0 at t=0 with the first sample at t=+5 min: `values[i]` is the appearance/action
 *  over `[i·5, (i+1)·5)` min, so it is anchored at slot `i+1` and slot 0 is the zero baseline (mirrors
 *  `CurvePreview` / the dashboard overlay). Previously `values[0]` was drawn at x=0, so a high-GI Ra
 *  (whose +5 min sample is already ~65 % of the peak) appeared to start mid-rise / instantly. */
@Composable
internal fun CurveSparkline(values: DoubleArray, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(56.dp).padding(vertical = 4.dp)) {
        if (values.isEmpty()) return@Canvas
        val peak = values.maxOrNull()?.toFloat() ?: return@Canvas
        if (peak <= 0f) return@Canvas
        val n = values.size
        val dx = size.width / n
        val path = Path().apply {
            moveTo(0f, size.height) // t=0, value 0
            for (i in 0 until n) {
                lineTo((i + 1) * dx, size.height - (values[i].toFloat() / peak) * size.height * 0.9f)
            }
            lineTo(n * dx, size.height)
            close()
        }
        drawPath(path, color.copy(alpha = 0.25f))
    }
}
