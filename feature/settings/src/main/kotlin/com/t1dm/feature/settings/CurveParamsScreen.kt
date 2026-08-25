package com.t1dm.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.rememberHapticDetent
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.model.BezierCurve
import com.t1dm.ui.graph.CurveEditor
import com.t1dm.ui.graph.CurvePreview
import kotlin.math.roundToInt

/** The clinical insulin preset is not chosen here; the insulin panel picks it per dose. */
data class CurveParams(
    val basalKaPerHour: Double,
    val basalKePerHour: Double,
    val lantusDiaHours: Double,
    val tresibaDiaHours: Double,
    val carbHighGiK: Double,
    val carbHighGiTheta: Double,
    val carbLowGiK: Double,
    val carbLowGiTheta: Double,
    /** Fixed by SPEC §5. */
    val exerciseK: Double,
    val exerciseTheta: Double,
)

@Composable
fun CurveParamsScreen(
    params: CurveParams,
    carbCurve: BezierCurve,
    insulinCurve: BezierCurve,
    onSaveCarbCurve: (BezierCurve) -> Unit,
    onSaveInsulinCurve: (BezierCurve) -> Unit,
    exerciseCarbEquivPerMin: Double,
    exerciseCarbEquivRange: ClosedFloatingPointRange<Double>,
    onSetExerciseCarbEquivPerMin: (Double) -> Unit,
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

        SettingsSectionHeader("Exercise disposal (gamma)")
        SettingsAnchor(curveExerciseCarbEquiv) {
            CarbEquivSlider(exerciseCarbEquivPerMin, exerciseCarbEquivRange, onSetExerciseCarbEquivPerMin)
        }
        Kv("Shape k / θ", "%.1f / %.0f".format(params.exerciseK, params.exerciseTheta))
    }
}

/** Grams per minute. Committed on release, not per drag sample: the value is kv-backed.
 *  [range] is the store's own, so the thumb cannot reach a value the writer would clamp. */
@Composable
private fun CarbEquivSlider(
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    onSet: (Double) -> Unit,
) {
    // Keyed on `value`: it arrives from a cold flow; unkeyed strands the thumb on the placeholder.
    var draft by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val steps = ((range.endInclusive - range.start) / GRAIN).roundToInt()
    val detent = rememberHapticDetent()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Carb equivalent", style = MaterialTheme.typography.bodyMedium)
            Text(
                "%.1f g/min".format(draft),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        Slider(
            value = draft,
            onValueChange = { raw ->
                // Snap to the printed grain so thumb and read-out cannot disagree.
                val stop = (raw / GRAIN).roundToInt()
                detent.at(stop)
                draft = stop * GRAIN
            },
            onValueChangeFinished = { onSet(draft.toDouble()) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            steps = (steps - 1).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Matches the read-out's printed resolution. */
private const val GRAIN = 0.1f

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

private val curveExerciseCarbEquiv = SettingsKnob(
    id = "curves.exercise_carb_equiv",
    screen = SettingsScreenKey.CURVES,
    section = "Exercise disposal (gamma)",
    label = "Carb equivalent",
    subtitle = "Grams a minute of exercise disposes of; the bout's duration is what scales it",
    synonyms = listOf(
        "exercise", "workout", "walk", "run", "activity", "disposal", "glucose disposal",
        "carb equivalent", "carbohydrate equivalent", "g/min", "grams per minute", "gamma", "curve",
        "bout",
    ),
    // Not "sensitivity": §5 keeps the post-exercise sensitivity boost a separate mechanism.
)

internal val settingsCurveKnobs = listOf(
    curveCarbBezier,
    curveInsulinBezier,
    curveExerciseCarbEquiv,
)
