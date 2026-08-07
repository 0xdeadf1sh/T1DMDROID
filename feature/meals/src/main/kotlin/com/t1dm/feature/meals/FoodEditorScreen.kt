package com.t1dm.feature.meals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.fadingEdges
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.design.verticalScrollbar
import com.t1dm.core.model.BezierCurve
import com.t1dm.core.model.Food
import com.t1dm.ui.graph.CurveEditor
import com.t1dm.ui.graph.CurvePreview
import java.util.Locale

/**
 * The custom-food editor (`meals/builder/food/{id}`) — the other half of what `Food.custom` has
 * always promised ("editable/deletable"); only the delete path existed before.
 *
 * The edit is confined to the dictionary row. Saved meals and already-logged meals built from this
 * food keep the carbs/GI/curve they SNAPSHOTTED at add time (`MealComponent`) — that snapshot is
 * precisely what lets a stored meal survive this edit and the food's later deletion, so nothing here
 * re-resolves them. The one line of copy on screen states that consequence, because it is the only
 * thing about the edit a competent user cannot infer from the fields.
 *
 * A stored [Food.customCurve] is a NORMALIZED SAMPLED shape (per-5-min buckets summing to 1.0) with
 * no inverse back to the [BezierCurve] the editor speaks. So a previously drawn curve can be kept
 * verbatim or redrawn from scratch — never nudged. Only a redraw re-samples; keeping it writes the
 * stored buckets straight back.
 */
@Composable
fun FoodEditorScreen(
    food: Food,
    onSave: (Food) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember(food.id) { mutableStateOf(food.name) }
    var carbsText by remember(food.id) { mutableStateOf(numText(food.carbsPer100g)) }
    var giText by remember(food.id) {
        mutableStateOf(food.giOrNull?.let { String.format(Locale.ROOT, "%.0f", it) } ?: "")
    }
    var useCurve by remember(food.id) { mutableStateOf(food.customCurve != null) }
    // Null while the stored shape is kept verbatim; non-null once the user elects to redraw (and from
    // the outset for a food that has no stored shape to keep).
    var drawn by remember(food.id) {
        mutableStateOf(if (food.customCurve == null) BezierCurve.default(180.0) else null)
    }
    val haptics = rememberT1dmHaptics()
    val scroll = rememberScrollState()

    val carbs = carbsText.toDoubleOrNull()
    val degenerate = useCurve && drawn?.isDegenerate() == true
    LaunchedEffect(degenerate) { if (degenerate) haptics.perform(HapticEvent.Warn) }

    Column(
        Modifier.fillMaxSize().verticalScrollbar(scroll).fadingEdges(scroll).verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Saved meals keep their own copy",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        OutlinedTextField(
            value = name, onValueChange = { name = it }, label = { Text("Name") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = carbsText,
                onValueChange = { carbsText = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Carbs / 100 g") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = giText,
                onValueChange = { giText = it.filter { c -> c.isDigit() } },
                label = { Text("GI (opt.)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = useCurve, onCheckedChange = { on -> haptics.toggled(on); useCurve = on })
            Text(
                "Custom appearance curve (overrides GI)",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (useCurve) {
            val kept = drawn
            if (kept == null) {
                CurvePreview(values = food.customCurve.orEmpty())
                TextButton(
                    onClick = {
                        haptics.perform(HapticEvent.Tap)
                        drawn = BezierCurve.default((food.customCurve?.size ?: 36) * 5.0)
                    },
                ) { Text("Redraw") }
            } else {
                CurveEditor(curve = kept, onChange = { drawn = it })
                if (degenerate) {
                    Text(
                        "Flat curve encodes no carbs — draw a hump or turn it off",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    haptics.perform(HapticEvent.Commit)
                    val shape = drawn
                    onSave(
                        food.copy(
                            name = name.trim(),
                            carbsPer100g = carbs ?: food.carbsPer100g,
                            giOrNull = giText.toDoubleOrNull(),
                            // Only a REDRAW re-samples; a kept shape is written back bucket for bucket,
                            // since there is no inverse from the samples to a curve to round-trip through.
                            customCurve = when {
                                !useCurve -> null
                                shape != null -> shape.sampleNormalized(1.0)
                                else -> food.customCurve
                            },
                        ),
                    )
                },
                enabled = name.isNotBlank() && carbs != null && !degenerate,
            ) { Text("Save") }
            OutlinedButton(
                onClick = { haptics.perform(HapticEvent.Reject); onCancel() },
            ) { Text("Cancel") }
            TextButton(
                onClick = { haptics.perform(HapticEvent.Reject); onDelete() },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Delete") }
        }
    }
}

/**
 * A double as the user typed it: no trailing `.0`, no spurious precision.
 *
 * [Locale.ROOT], because this seeds a field that is read back with `toDoubleOrNull` — which knows only
 * `'.'` — and whose input filter strips everything else. Under a comma-decimal locale the default
 * would hand the field a value it can neither parse nor let the user retype.
 */
private fun numText(v: Double): String =
    String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }
