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

/**
 * The BG panel's drawing palette — visible ONLY while paint mode is on, immediately under the chip row
 * it is toggled from, so the controls sit between the toggle and the canvas they act on.
 *
 * Everything here is decoration. A tool, a colour and a width change nothing but the pixels of the
 * annotation layer: no stroke is ever read back by a calculator, a model channel, an alarm, or any
 * §3.6 rail, and the corridor the graph clips the layer out of guarantees the glucose trace stays
 * legible however much is drawn over it.
 *
 * Stateless and callback-driven like the rest of `:feature:dashboard`: the selection lives in
 * [DashboardScreen], the strokes live in Room behind `:app`'s callbacks, and this module still knows
 * of no store.
 */
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
        // The current ink at FULL opacity, as the picker's own swatches are drawn. A highlighter is
        // seeded at 22 % alpha, and composited straight onto the surface that reads as a faint grey
        // disc indistinguishable from the chip behind it — which defeats the one question this swatch
        // exists to answer. The alpha is dialled and read in Colour…, over the checkered ramp built
        // for exactly that.
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

/**
 * Colour + width, in one dialog so the palette row stays a row. The width slider lives here rather
 * than in the scrollable row on purpose: a horizontal slider inside a horizontally-scrolling container
 * is a gesture fight the user always loses.
 *
 * Selecting a tool SEEDS the width and the alpha from [PaintTool]; both remain fully overridable here,
 * which is why the tool itself contributes only cap/join geometry and texture at draw time.
 */
@Composable
internal fun PaintStyleDialog(
    colorArgb: Int,
    widthDp: Float,
    onColorChange: (Int) -> Unit,
    onWidthChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberT1dmHaptics()
    // No Warn on raise and no Reject on dismiss, unlike the log confirmations: this sheet asks nothing
    // and refuses nothing — it is a style palette whose every edit has already landed live under the
    // finger, so "Done" is a plain Tap and a scrim dismissal is not a cancellation of anything.
    // The width slider is continuous; its detent is therefore the WHOLE DP the label shows, so the
    // texture matches what the user can read rather than the Float behind it.
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

/**
 * Set by the panel the pen has to fit rather than by taste. The plot box is 164–178 dp tall (220 dp
 * less the 10 dp top inset — 24 dp once the model's predicted-clock axis is up — and the 32 dp bottom
 * one), so 120 dp floods it in two passes; past that the round cap's radius exceeds the box's
 * half-height and every tap lands as a full-panel dab, which is a worse implement, not a bigger one.
 *
 * Raising this is backward-safe by construction: width is persisted PER STROKE, so no existing row
 * changes meaning, and nothing downstream assumes an upper bound — the renderer's only width guard is
 * a one-pixel floor.
 */
internal const val PAINT_WIDTH_MAX_DP = 120f

/** The width and alpha a freshly-picked [tool] loads into the palette, applied to the current ink. */
internal fun seedInk(tool: PaintTool, colorArgb: Int): Int = argbWithAlpha(colorArgb, tool.defaultAlpha)
