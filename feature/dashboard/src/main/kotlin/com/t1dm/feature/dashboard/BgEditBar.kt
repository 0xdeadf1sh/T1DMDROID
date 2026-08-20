package com.t1dm.feature.dashboard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.rememberHapticDetent
import com.t1dm.core.design.rememberT1dmHaptics
import kotlin.math.roundToInt

/**
 * What the selection under the finger can have done to it, and what the last act said.
 *
 * Everything is a fact about the selection rather than a mode: [cutCount] is how many stored
 * readings a cut would erase, [fillLabel] names the geometry the model would run, and the τ block
 * appears only for a drawn span that still has the fan to move a line through. A control that is
 * present but does nothing is worse than one that is absent, so absence is the default.
 */
internal data class BgEditState(
    val hasSelection: Boolean,
    val cutCount: Int,
    val fillLabel: String?,
    val spanStartMs: Long?,
    val spanPromoted: Boolean,
    val spanTauSweepable: Boolean,
    val tau: Double,
    val canUndo: Boolean,
    val busy: Boolean,
)

/**
 * The BG panel's edit bar — visible ONLY while edit mode is on, immediately under the chip row it
 * replaces, so the tools sit between the toggle and the trace they act on.
 *
 * **The bar acts on a selection; it never makes one.** A drag on the panel picks a stretch of time
 * and stops there, and every destructive thing this bar can do is a separate, deliberate press
 * afterwards. That separation is the whole point of the mode: an armed panel where the end of a
 * drag reconstructed on its own put fills where nobody aimed, with nothing to undo them.
 *
 * Stateless and callback-driven like the rest of `:feature:dashboard`: the selection lives in
 * [DashboardScreen], the undo stack and the store live behind `:app`'s callbacks, and this module
 * still knows of no store.
 */
@Composable
internal fun BgEditBar(
    state: BgEditState,
    note: String?,
    showSpans: Boolean,
    onDone: () -> Unit,
    onCut: () -> Unit,
    onFill: () -> Unit,
    onTau: (Double) -> Unit,
    onTauPreview: (Double) -> Unit,
    onPromote: () -> Unit,
    onDemote: () -> Unit,
    onDiscard: () -> Unit,
    onUndo: () -> Unit,
    onToggleSpans: (Boolean) -> Unit,
) {
    val haptics = rememberT1dmHaptics()
    val tauDetent = rememberHapticDetent(HapticEvent.ScrubTick)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistChip(
                onClick = { haptics.perform(HapticEvent.NavSwitch); onDone() },
                label = { Text("Done") },
            )
            // Named with its count: a button reading "Cut" alone is aimed at whatever the highlight
            // happens to cover, and this is the one control here that destroys measured data.
            TextButton(
                onClick = { haptics.perform(HapticEvent.Warn); onCut() },
                enabled = state.cutCount > 0 && !state.busy,
            ) { Text(if (state.cutCount > 0) "Cut ${state.cutCount}" else "Cut") }
            TextButton(
                onClick = { haptics.perform(HapticEvent.Commit); onFill() },
                enabled = state.fillLabel != null && !state.busy,
            ) { Text(state.fillLabel ?: "Fill") }
            if (state.spanStartMs != null) {
                if (state.spanPromoted) {
                    TextButton(
                        onClick = { haptics.perform(HapticEvent.Warn); onDemote() },
                        enabled = !state.busy,
                    ) { Text("Demote") }
                } else {
                    TextButton(
                        onClick = { haptics.perform(HapticEvent.Commit); onPromote() },
                        enabled = !state.busy,
                    ) { Text("Promote") }
                    TextButton(
                        onClick = { haptics.perform(HapticEvent.Warn); onDiscard() },
                        enabled = !state.busy,
                    ) { Text("Discard") }
                }
            }
            TextButton(onClick = onUndo, enabled = state.canUndo && !state.busy) { Text("Undo") }
            FilterChip(
                selected = showSpans,
                onClick = { haptics.toggled(!showSpans); onToggleSpans(!showSpans) },
                label = { Text("Fills") },
            )
        }
        // The τ sweep, on its own line so the slider has a width worth dragging.
        //
        // Every position is a level the model already emitted: the line traces the fan's τ-th
        // quantile, read in risk space and decoded through the descriptor that produced it. It moves
        // no median and invents nothing between the levels it was given.
        if (state.spanTauSweepable) {
            // The knob's position is LOCAL, the line PREVIEWS on every move, and the STORE is
            // written on release. Committing per frame would put a Room transaction on the critical
            // path for as long as a thumb is down; not previewing at all left the curve frozen
            // until lift-off, so the slider was aimed blind. Re-seeded whenever the stored level
            // changes under it, which is how it follows a span the selection has just moved to.
            var knob by remember(state.spanStartMs, state.tau) { mutableFloatStateOf(state.tau.toFloat()) }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "τ ${"%.2f".format(knob)}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(52.dp),
                )
                Slider(
                    value = knob,
                    onValueChange = {
                        // The LADDER step, never the raw float: the fan is only read at those
                        // levels, so that is what a tick should mark.
                        tauDetent.at((it * 20f).roundToInt())
                        knob = it
                        onTauPreview(it.toDouble())
                    },
                    onValueChangeFinished = { onTau(knob.toDouble()) },
                    // The published levels are the range. Outside them the crate clamps, and a
                    // slider that travels where the answer stops changing is a broken control.
                    valueRange = 0.05f..0.95f,
                    steps = 17,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        note?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}
