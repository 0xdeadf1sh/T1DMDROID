package com.t1dm.feature.meals

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
import androidx.compose.ui.graphics.ImageBitmap
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
import com.t1dm.core.model.GiChip
import com.t1dm.core.model.IobCobReadout
import com.t1dm.core.model.RecentMeal
import com.t1dm.core.model.SensitivityEstimate
import com.t1dm.core.model.UnitSpace
import kotlin.math.roundToInt

// 5–120 g in 5 g steps ⇒ 24 stops ⇒ 22 interior Slider steps.
private const val CARB_MIN = 5.0
private const val CARB_MAX = 120.0
private const val CARB_STEPS = 22

/**
 * Owns EXACTLY ONE vertical scroll, with [footer] inside it: a sibling placed after this screen in a
 * plain Column is measured with `maxHeight = 0` — undrawn, unhittable, no warning. A scrollable
 * Column is no escape either; two nested vertical scrolls throw at measure time.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MealsScreen(
    iobCob: IobCobReadout? = null,
    // Display-only: nothing on this screen may act on it.
    sensitivity: SensitivityEstimate? = null,
    unit: UnitSpace = UnitSpace.MgDl,
    recentMeals: List<RecentMeal> = emptyList(),
    previewCurve: (suspend (grams: Double, gi: Double) -> DoubleArray)? = null,
    photoThumbnail: ImageBitmap? = null,
    /** Must be the cell that gates the POST. [photoThumbnail] is only what the preview can draw;
     *  the two are set independently, and a failed decode leaves an upload still pending. */
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
    var pending by remember { mutableStateOf<PendingLog.Meal?>(null) }
    val haptics = rememberT1dmHaptics()
    // Keyed on the 5 g stop from the drag alone; on `grams` it would buzz once per keystroke.
    val gramsDetent = rememberHapticDetent()
    val giDetent = rememberHapticDetent(HapticEvent.ScrubTick)

    Column(
        Modifier.fillMaxSize().verticalScrollbar(scroll).fadingEdges(scroll).verticalScroll(scroll)
            .padding(16.dp),
    ) {
        iobCob?.let { IobCobLine(it, sensitivity, unit) }

        OutlinedTextField(
            value = gramsText,
            onValueChange = { gramsText = it.filter { c -> c.isDigit() || c == '.' } },
            label = { Text("Carbs (g)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
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
        // GI is continuous; the grain is imposed here, one tick per 5 points.
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
        // Gated on the ATTACHMENT, not the thumbnail: a photo whose preview will not decode still
        // uploads, so Remove must stay reachable.
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
