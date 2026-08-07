package com.t1dm.feature.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.fadingEdges
import com.t1dm.core.design.hapticClickable
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.model.BASELINE_MODEL_ID
import com.t1dm.core.model.BaselineFit
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.ModelMeta
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.RunningModel
import com.t1dm.core.model.displayName
import kotlinx.coroutines.launch

/**
 * The Models panel (Phase 7C — item 7): the loaded running set, each row now carrying
 * the size-reasoning META (parameter count, on-disk `.pte` size, and the key arch dims from the
 * descriptor) so a model's footprint is legible at a glance, plus this cycle's forecast status. Tapping
 * a row opens its PERFORMANCE drill-down ([ModelDetailScreen], item 24). Selection is on a long-press-
 * free single tap in the pre-7D layout via the caret; the fp32-authoritative pick is [onSelect].
 */
@Composable
fun ModelsScreen(
    state: InferenceState,
    onSelect: (String) -> Unit,
    onOpen: (String) -> Unit,
    pendingUpdates: Set<String> = emptySet(),
    onApplyUpdate: (String) -> Unit = {},
    onDelete: (String) -> Unit = {},
    /** Fits the classical baseline from the patient's own history. Null ⇒ no baseline wired, and
     *  the control is absent rather than disabled. */
    onFitBaseline: (suspend () -> Result<BaselineFit>)? = null,
) {
    // The delete confirmation is hoisted to the screen (not per-row) so a row recycling out of the
    // LazyColumn viewport can't drop the pending confirmation mid-gesture.
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val haptics = rememberT1dmHaptics()
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(16.dp).fadingEdges(listState),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                // N1 — the "Models" title lives in the breadcrumb; no duplicate in-view header.
                Text(
                    "${state.running.size} loaded · tap a row for detail",
                    style = MaterialTheme.typography.bodySmall,
                )
                state.note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }

            if (state.running.isEmpty()) {
                item {
                    Text(
                        "No models loaded — sync from Settings → Server",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                items(state.running, key = { it.modelId }) { model ->
                    ModelRow(
                        model = model,
                        prediction = state.predictions.firstOrNull { it.modelId == model.modelId },
                        meta = state.metaOf(model.modelId),
                        updateAvailable = model.modelId in pendingUpdates,
                        onSelect = onSelect,
                        onOpen = onOpen,
                        onApplyUpdate = onApplyUpdate,
                        onRequestDelete = { confirmDelete = it },
                    )
                    HorizontalDivider()
                }
            }

            if (onFitBaseline != null) {
                item { BaselineFitControl(state, onFitBaseline) }
            }
        }
        confirmDelete?.let { id ->
            // The three-beat every dialog in the app keeps: Warn on raise, Commit on the destructive
            // accept (a deleted artifact is not recoverable, and losing the SELECTED model stops
            // forecasting and dose advice outright), Reject on either way out.
            LaunchedEffect(id) { haptics.perform(HapticEvent.Warn) }
            AlertDialog(
                onDismissRequest = { haptics.perform(HapticEvent.Reject); confirmDelete = null },
                title = { Text("Remove model?") },
                text = {
                    Text(
                        "Delete \"$id\" and its artifact? Removing the selected model stops forecast and dose advice.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { haptics.perform(HapticEvent.Commit); onDelete(id); confirmDelete = null },
                    ) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { haptics.perform(HapticEvent.Reject); confirmDelete = null },
                    ) { Text("Cancel") }
                },
            )
        }
    }
}

/**
 * The manual fit for the classical baseline, and the one line of evidence it produces.
 *
 * The button is the ONLY entry point to the first fit: the baseline is not in `state.running` until
 * a fit exists, so it has no row to hang an action off yet. Afterwards it is a refit.
 *
 * The result line quotes the 30-minute held-out RMSE against zero-order hold, because that pair is
 * the entire reason the baseline exists — a model that cannot beat persistence has not earned its
 * place, and neither has the neural one it is there to measure.
 */
@Composable
private fun BaselineFitControl(
    state: InferenceState,
    onFitBaseline: suspend () -> Result<BaselineFit>,
) {
    val haptics = rememberT1dmHaptics()
    val scope = rememberCoroutineScope()
    var fitting by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val exists = state.running.any { it.modelId == BASELINE_MODEL_ID }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        OutlinedButton(
            enabled = !fitting,
            onClick = {
                haptics.perform(HapticEvent.Commit)
                fitting = true
                scope.launch {
                    // `fitting` must be cleared on EVERY exit. The lambda is typed Result, but it
                    // still reaches suspending IO that can throw, and a throw here would otherwise
                    // leave the button disabled for the life of the screen with no way back.
                    val r = try {
                        onFitBaseline()
                    } catch (t: Throwable) {
                        fitting = false
                        note = "Fit failed"
                        throw t
                    }
                    note = r.fold(
                        onSuccess = { fit ->
                            // Index 5 is the 6th step = 30 min on the five-minute grid.
                            val rmse = fit.holdoutRmseMgdl.getOrNull(5)
                            val zoh = fit.persistenceRmseMgdl.getOrNull(5)
                            when {
                                !fit.model.calibrated -> "Fitted — band needs more history"
                                rmse != null && zoh != null && rmse.isFinite() && zoh.isFinite() ->
                                    "RMSE %.1f vs ZOH %.1f @30 min".format(rmse, zoh)
                                else -> "Fitted"
                            }
                        },
                        onFailure = { "Not enough history" },
                    )
                    fitting = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (fitting) "Fitting…" else if (exists) "Refit baseline" else "Fit baseline")
        }
        note?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ModelRow(
    model: RunningModel,
    prediction: ModelPrediction?,
    meta: ModelMeta?,
    updateAvailable: Boolean,
    onSelect: (String) -> Unit,
    onOpen: (String) -> Unit,
    onApplyUpdate: (String) -> Unit,
    onRequestDelete: (String) -> Unit,
) {
    // N3 — tapping ANYWHERE on the row (name included) opens the detail; "select this model" is now an
    // explicit RadioButton, never an invisible tap target on the title. Previously the name carried its
    // own `onSelect` clickable that consumed the tap, so only the description below opened the detail.
    val haptics = rememberT1dmHaptics()
    Column(
        Modifier
            .fillMaxWidth()
            .hapticClickable(HapticEvent.NavSwitch) { onOpen(model.modelId) }
            .padding(vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // The radio is the DOSING model picker (§3.6-E), not a nav affordance — it changes which
            // forecast the dashboard and the calculator read. A detent, and audibly not the NavSwitch
            // the row around it plays.
            RadioButton(
                selected = model.selected,
                onClick = { haptics.perform(HapticEvent.SegmentTick); onSelect(model.modelId) },
                modifier = Modifier.size(28.dp),
            )
            Text(
                model.modelId,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (model.selected) FontWeight.Bold else FontWeight.Normal,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            Text(statusLabel(prediction), style = MaterialTheme.typography.labelMedium, color = statusColor(prediction))
            IconButton(
                onClick = { haptics.perform(HapticEvent.Tap); onRequestDelete(model.modelId) },
                modifier = Modifier.size(40.dp),
            ) {
                Text("✕", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            }
        }
        Text(
            "${model.backend.displayName()} · ${model.precision.name}" +
                (prediction?.let { " · anchor ${it.lastBg.toInt()} mg/dL" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Size-reasoning meta line (item 7): real param count + on-disk size + arch dims.
        meta?.let { m ->
            Text(
                metaLine(m),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Auto-download / manual-apply (product decision 2): the running model has a newer artifact
        // staged from the server. Applying it swaps + re-selects — never done silently for the dosing model.
        if (updateAvailable) {
            Text(
                "Update downloaded — not applied",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
            // Swapping the artifact under a running (possibly dosing) model — never done silently, and
            // never felt as an ordinary tap.
            OutlinedButton(
                onClick = { haptics.perform(HapticEvent.Commit); onApplyUpdate(model.modelId) },
            ) { Text("Apply update") }
        }
    }
}

internal fun metaLine(m: ModelMeta): String {
    val parts = buildList {
        m.paramCount?.let { add("${fmtParams(it)} params") }
        m.diskBytes?.let { add(fmtBytes(it)) }
        val dims = listOfNotNull(
            m.dModel?.let { "d$it" },
            m.nLayers?.let { "L$it" },
            m.nHeads?.let { "H$it" },
        )
        if (dims.isNotEmpty()) add(dims.joinToString("·"))
    }
    return if (parts.isEmpty()) "meta n/a (no descriptor model_card)" else parts.joinToString(" · ")
}

internal fun fmtParams(n: Long): String = when {
    n >= 1_000_000 -> "%.2fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}

internal fun fmtBytes(b: Long): String = when {
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.1f KB".format(b / (1L shl 10).toDouble())
    else -> "$b B"
}

@Composable
private fun statusColor(p: ModelPrediction?) = when {
    p == null -> MaterialTheme.colorScheme.onSurfaceVariant
    p.stale || p.status != ForecastStatus.OK -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.tertiary
}

private fun statusLabel(p: ModelPrediction?): String = when {
    p == null -> "no forecast"
    p.stale -> "STALE"
    p.status != ForecastStatus.OK -> p.status.name
    else -> "OK"
}
