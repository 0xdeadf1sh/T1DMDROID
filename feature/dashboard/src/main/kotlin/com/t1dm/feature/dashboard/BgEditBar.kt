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

/** [cutCount] is how many stored readings a cut would erase. */
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

/** Acts on a selection; never makes one. Every destructive act here is a separate press. */
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
            // Named with its count: the one control here that destroys measured data.
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
        // Every position is a level the model emitted; nothing is interpolated between them.
        if (state.spanTauSweepable) {
            // Local knob; preview on every move, store on release, re-seeded when the level moves.
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
                        // Ladder step, never the raw float: the fan is only read at those levels.
                        tauDetent.at((it * 20f).roundToInt())
                        knob = it
                        onTauPreview(it.toDouble())
                    },
                    onValueChangeFinished = { onTau(knob.toDouble()) },
                    // The published levels are the range; outside them the crate clamps.
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
