package com.t1dm.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.ColorPicker
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.argbWithAlpha
import com.t1dm.core.design.rememberHapticDetent
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.model.PaintTool
import kotlin.math.roundToInt

/** Decoration only: no stroke is read back by a calculator, a model channel, or an alarm. */
@Composable
internal fun PaintPalette(
    tool: PaintTool,
    erasing: Boolean,
    colorArgb: Int,
    canUndo: Boolean,
    canRedo: Boolean,
    onSelectTool: (PaintTool) -> Unit,
    onSelectEraser: () -> Unit,
    onOpenStyle: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    val haptics = rememberT1dmHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaintTool.entries.forEach { t ->
            FilterChip(
                selected = !erasing && tool == t,
                onClick = { haptics.perform(HapticEvent.Tap); onSelectTool(t) },
                label = { Text(t.displayName) },
            )
        }
        FilterChip(
            selected = erasing,
            onClick = { haptics.perform(HapticEvent.Tap); onSelectEraser() },
            label = { Text("Eraser") },
        )
        // Full opacity deliberately: a highlighter's own alpha reads as the chip behind it.
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(argbWithAlpha(colorArgb, 1f))),
            )
        }
        TextButton(onClick = { haptics.perform(HapticEvent.Tap); onOpenStyle() }) { Text("Colour…") }
        TextButton(onClick = onUndo, enabled = canUndo) { Text("Undo") }
        TextButton(onClick = onRedo, enabled = canRedo) { Text("Redo") }
    }
}

/** Width lives here, not in the palette row: a horizontal slider inside a horizontally-scrolling
 *  container is a gesture fight. */
@Composable
internal fun PaintStyleDialog(
    colorArgb: Int,
    widthDp: Float,
    onColorChange: (Int) -> Unit,
    onWidthChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberT1dmHaptics()
    // No Warn on raise, no Reject on dismiss, unlike the log confirmations: every edit has already
    // landed live. The detent is the whole dp the label shows, not the Float behind it.
    val widthDetent = rememberHapticDetent(HapticEvent.ScrubTick)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { haptics.perform(HapticEvent.Tap); onDismiss() }) { Text("Done") } },
        title = { Text("Colour & width") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ColorPicker(colorArgb = colorArgb, onColorChange = onColorChange)
                Spacer(Modifier.height(14.dp))
                Text(
                    "Width — ${"%.0f".format(widthDp)} dp",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = widthDp,
                    onValueChange = { widthDetent.at(it.roundToInt()); onWidthChange(it) },
                    valueRange = PAINT_WIDTH_MIN_DP..PAINT_WIDTH_MAX_DP,
                )
            }
        },
    )
}

internal const val PAINT_WIDTH_MIN_DP = 1f

/** The plot box is 164–178 dp tall: past 120 dp the round cap's radius exceeds its half-height and
 *  every tap lands as a full-panel dab. */
internal const val PAINT_WIDTH_MAX_DP = 120f

internal fun seedInk(tool: PaintTool, colorArgb: Int): Int = argbWithAlpha(colorArgb, tool.defaultAlpha)
