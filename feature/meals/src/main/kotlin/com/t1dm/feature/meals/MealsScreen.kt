package com.t1dm.feature.meals

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.ConfirmLogDialog
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.IobCobLine
import com.t1dm.core.design.PendingLog
import com.t1dm.core.design.rememberHapticDetent
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.design.verticalScrollbar
import com.t1dm.core.model.GiChip
import com.t1dm.core.model.IobCobReadout
import com.t1dm.core.model.RecentMeal
import com.t1dm.core.model.SensitivityEstimate
import com.t1dm.core.model.UnitSpace
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
 * from the dictionary" affordance is a documented seam for the meal-builder work (deliverable 3),
 * reached through the [footer] slot.
 *
 * N10 — the screen owns EXACTLY ONE vertical scroll container, and everything it shows (including
 * [footer]) lives inside it. A caller must never wrap it in a bare `Column` with siblings after it:
 * `verticalScroll` reports the full incoming `maxHeight` once its content overflows, and a Column
 * measures unweighted children against the *remaining* main axis, so any sibling placed after this
 * screen is measured with `maxHeight = 0` — zero-height, undrawn and unhittable, with no clipping
 * warning to say so. That is precisely how the meal-builder link went missing. Wrapping it in a
 * scrollable Column is not an escape either: nesting two vertical scrolls throws at measure time.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MealsScreen(
    iobCob: IobCobReadout? = null,
    // The model-probed ISF/ICR, shown beside IOB/COB exactly as the BG and Insulin panels show it —
    // see `:core:design` OnBoardReadout for why all three read from one definition. Null renders as
    // "N/A" rather than vanishing; DISPLAY-ONLY, and nothing on this screen may act on it.
    sensitivity: SensitivityEstimate? = null,
    unit: UnitSpace = UnitSpace.MgDl,
    recentMeals: List<RecentMeal> = emptyList(),
    previewCurve: (suspend (grams: Double, gi: Double) -> DoubleArray)? = null,
    photoThumbnail: ImageBitmap? = null,
    /**
     * Whether a photo will actually be uploaded with this meal.
     *
     * Distinct from [photoThumbnail], which is only what the preview can DRAW: the two cells are set
     * independently, so a decode that failed left a meal whose photo the §3.6-G dialog denied while
     * the POST still went out — to a server with no delete endpoint — and a cancelled re-take
     * promised an upload that never fired. This must be the same cell that gates the POST.
     */
    photoAttached: Boolean = photoThumbnail != null,
    onTakePhoto: () -> Unit = {},
    onChoosePhoto: () -> Unit = {},
    onClearPhoto: () -> Unit = {},
    uploadStatus: String? = null,
    onLogMeal: (grams: Double, gi: Double) -> Unit = { _, _ -> },
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    var gramsText by remember { mutableStateOf("") }
    var gi by remember { mutableFloatStateOf(GiChip.MIXED.gi.toFloat()) }
    val grams = gramsText.toDoubleOrNull()
    val scroll = rememberScrollState()
    // The meal the Log press proposes, held until the confirmation resolves. Nullable-payload state,
    // the same shape the model-removal confirm uses. Nothing is written and nothing is cleared while
    // it is non-null — see the deferred field clear on the Button below.
    var pending by remember { mutableStateOf<PendingLog.Meal?>(null) }
    val haptics = rememberT1dmHaptics()
    // The carb slider is two-way bound to the text field above it — dragging rewrites the text and
    // TYPING repositions the thumb — so the detent is driven from `onValueChange`, which only the drag
    // calls, and keyed on the 5 g stop rather than the raw Float. Keying it on `grams` instead would
    // buzz once per keystroke.
    val gramsDetent = rememberHapticDetent()
    val giDetent = rememberHapticDetent(HapticEvent.ScrubTick)

    Column(Modifier.fillMaxSize().verticalScrollbar(scroll).verticalScroll(scroll).padding(16.dp)) {
        iobCob?.let { IobCobLine(it, sensitivity, unit) }

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
            onValueChange = { gramsDetent.at(it.roundToInt()); gramsText = it.roundToInt().toString() },
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
                        onClick = {
                            haptics.perform(HapticEvent.Tap)
                            gramsText = m.grams.toInt().toString()
                            gi = m.gi.toFloat()
                        },
                        label = { Text(m.label) },
                        leadingIcon = { Text("↺", style = MaterialTheme.typography.labelLarge) },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
            }
        }

        Text(
            "GI ${gi.toInt()}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GiChip.entries.forEach { chip ->
                FilterChip(
                    selected = gi.toInt() == chip.gi.toInt(),
                    onClick = { haptics.perform(HapticEvent.SegmentTick); gi = chip.gi.toFloat() },
                    label = { Text(chip.label) },
                )
            }
        }
        // GI is continuous, so its grain is imposed here: one tick per whole 5 GI points, which is
        // about the resolution the number below it is read at.
        Slider(
            value = gi,
            onValueChange = { giDetent.at((it / 5f).roundToInt()); gi = it },
            valueRange = 0f..100f,
        )

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

        // Issue 7 — attach a photo of the meal. The capture/pick is driven by the app-level launchers;
        // the log button carries the upload (Navigation reads the pending Uri after logCarb).
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 16.dp),
        ) {
            OutlinedButton(
                onClick = { haptics.perform(HapticEvent.Tap); onTakePhoto() },
            ) { Text("Take photo") }
            OutlinedButton(
                onClick = { haptics.perform(HapticEvent.Tap); onChoosePhoto() },
            ) { Text("Choose photo") }
        }
        // Gated on the ATTACHMENT, not the thumbnail: a photo whose preview would not decode is still
        // going to be uploaded, so Remove has to stay reachable — it was the only way to call it off,
        // and it used to vanish with the preview.
        if (photoAttached) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                if (photoThumbnail != null) {
                    Image(
                        bitmap = photoThumbnail,
                        contentDescription = "Attached meal photo",
                        modifier = Modifier.size(64.dp),
                    )
                } else {
                    Text("Photo attached", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { haptics.perform(HapticEvent.Reject); onClearPhoto() },
                ) { Text("Remove") }
            }
        }
        uploadStatus?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // The press only PROPOSES the meal; `gramsText` survives until the dialog is confirmed, so a
        // Cancel does not silently wipe what was typed (it used to clear synchronously, here).
        // The press only PROPOSES: it raises ConfirmLogDialog, which owns the Warn/Confirm/Reject beat,
        // and `:app`'s receipt owns the Commit once a row actually exists. A Tap here and nothing more,
        // or the same act would speak three times.
        Button(
            onClick = {
                haptics.perform(HapticEvent.Tap)
                grams?.let {
                    pending = PendingLog.Meal(
                        grams = it,
                        gi = gi.toDouble(),
                        photoAttached = photoAttached,
                    )
                }
            },
            enabled = grams != null && grams > 0.0,
            modifier = Modifier.padding(top = 16.dp),
        ) { Text("Log meal") }

        footer()
    }

    pending?.let { p ->
        ConfirmLogDialog(
            pending = p,
            onConfirm = {
                onLogMeal(p.grams, p.gi ?: GiChip.MIXED.gi)
                gramsText = ""
                pending = null
            },
            onDismiss = { pending = null },
        )
    }
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
