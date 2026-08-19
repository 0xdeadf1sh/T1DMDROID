package com.t1dm.feature.models

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.rememberHapticDetent
import com.t1dm.core.design.rememberT1dmHaptics
import androidx.compose.foundation.Canvas
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Lab: run any model over any masked set, on real history or a synthetic patient.
 *
 * The whole screen is one experiment. Nothing it produces is stored, synced, alarmed on, or read by
 * the dose calculator — which is what lets it mask real glucose, invent a week of history, and
 * attach an untried adapter without any of that reaching the patient's record.
 */
@Composable
fun LabScreen(
    state: LabUiState,
    gaps: List<LabGap>,
    gapNote: String?,
    onPickModel: (String) -> Unit,
    onToggleSynthetic: (Boolean) -> Unit,
    onSeed: (Long) -> Unit,
    onSpans: (List<LabSpan>) -> Unit,
    onToggleForecast: (Boolean) -> Unit,
    onPickAdapter: (Long?) -> Unit,
    onTau: (Double) -> Unit,
    onRun: () -> Unit,
    onFindGaps: () -> Unit,
    onRepair: (LabGap) -> Unit,
    onOpenAdapters: (String) -> Unit,
) {
    val haptics = rememberT1dmHaptics()
    val tauDetent = rememberHapticDetent(HapticEvent.ScrubTick)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Scrollable: the running set grows with what is pushed to the device, and the row ran
        // off the edge at three models — taking the Adapters button, the last item, with it.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.models.forEach { id ->
                FilterChip(
                    selected = id == state.modelId,
                    onClick = { haptics.perform(HapticEvent.SegmentTick); onPickModel(id) },
                    label = { Text(id, fontFamily = FontFamily.Monospace) },
                )
            }
            state.modelId?.let { id ->
                OutlinedButton(
                    onClick = { haptics.perform(HapticEvent.NavSwitch); onOpenAdapters(id) },
                ) { Text("Adapters") }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = state.synthetic,
                onCheckedChange = { haptics.perform(if (it) HapticEvent.ToggleOn else HapticEvent.ToggleOff); onToggleSynthetic(it) },
            )
            Text("Synthetic", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
            if (state.synthetic) {
                OutlinedButton(
                    onClick = { onSeed(state.seed + 1) },
                    modifier = Modifier.padding(start = 12.dp),
                ) { Text("Seed ${state.seed}") }
            } else {
                Text(
                    "${state.realPatches}/${state.contextPatches} patches",
                    Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = state.withForecast, onCheckedChange = onToggleForecast)
            Text("Forecast span", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
        }

        // ── the mask strip: drag to withhold, tap a span to drop it ──
        Text(
            "${state.spans.size} spans · ${state.maskedPatches} patches " +
                "(${halfHours(state.maskedPatches)}) · max ${state.maxMaskedPatches}",
            style = MaterialTheme.typography.bodySmall,
        )
        MaskStrip(
            contextPatches = state.contextPatches,
            spans = state.spans,
            onSpans = onSpans,
        )
        state.outOfDistribution?.let {
            Text(
                "Off-distribution: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (state.adapters.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = state.adapterId == null,
                    onClick = { haptics.perform(HapticEvent.SegmentTick); onPickAdapter(null) },
                    label = { Text("Frozen") },
                )
                state.adapters.forEach { a ->
                    FilterChip(
                        selected = a.id == state.adapterId,
                        onClick = { haptics.perform(HapticEvent.SegmentTick); onPickAdapter(a.id) },
                        label = { Text(a.name) },
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { haptics.perform(HapticEvent.Commit); onRun() },
                enabled = !state.running && state.blocked == null,
            ) { Text(if (state.running) "Running" else "Run") }
            state.blocked?.let {
                Text(
                    it,
                    Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        // ── gap repair ──
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onFindGaps, enabled = !state.running) { Text("Find gaps") }
            if (gaps.isNotEmpty()) {
                Text(
                    "${gaps.size} in 14 days",
                    Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        gapNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        gaps.take(6).forEach { gap ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${gap.label} · ${gap.steps * 5} min",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                if (gap.filled) {
                    Text("filled", style = MaterialTheme.typography.bodySmall)
                } else {
                    OutlinedButton(
                        onClick = { haptics.perform(HapticEvent.Commit); onRepair(gap) },
                        enabled = !state.running && state.modelId != null,
                    ) { Text("Fill") }
                }
            }
        }

        state.run?.let { run ->
            HorizontalDivider()
            Text(
                buildString {
                    append("τ ${"%.2f".format(state.tau)}")
                    append(" · ${run.latencyMs.roundToInt()} ms")
                    if (run.synthetic) append(" · synthetic")
                    run.adapterName?.let { append(" · $it") }
                    run.statusNote?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.bodySmall,
            )
            LabChart(run = run, tau = state.tau, modifier = Modifier.fillMaxWidth().height(220.dp))
            Slider(
                value = state.tau.toFloat(),
                onValueChange = {
                    // The LADDER step, never the raw float: the fan is only read at those levels,
                    // so that is what a tick should mark.
                    tauDetent.at((it * 20f).roundToInt())
                    onTau(it.toDouble())
                },
                valueRange = 0.05f..0.95f,
                steps = 17,
            )
        }
    }
}

/** Patches as a duration, since a patch is 30 minutes and nobody counts in patches. */
private fun halfHours(patches: Int): String {
    val h = patches / 2.0
    return if (h == h.toInt().toDouble()) "${h.toInt()} h" else "%.1f h".format(h)
}

/**
 * The mask strip: one cell per context patch. A horizontal drag paints a span, a tap on a painted
 * span removes it. Spans are kept non-abutting and in order — the model's own rule, enforced here
 * so an impossible set cannot be painted rather than refused after a run.
 */
@Composable
private fun MaskStrip(contextPatches: Int, spans: List<LabSpan>, onSpans: (List<LabSpan>) -> Unit) {
    if (contextPatches <= 0) return
    val haptics = rememberT1dmHaptics()
    val masked = MaterialTheme.colorScheme.error
    val visible = MaterialTheme.colorScheme.surfaceVariant
    var dragFrom by remember { mutableStateOf(-1) }
    // One tick per PATCH the drag crosses. Firing on every emit would fire on every pointer
    // event, which is a stream, not a gesture the user is making.
    val paintDetent = rememberHapticDetent(HapticEvent.SegmentTick)
    // The gesture handlers are keyed on the GEOMETRY only. Keying them on `spans` restarts the
    // detector on the first drag update — which is the update that changes `spans` — so a drag
    // would cancel itself after one frame and paint a single patch however far it travelled.
    // `rememberUpdatedState` is what lets a handler that outlives a recomposition still read the
    // current set.
    val current by rememberUpdatedState(spans)
    val emit by rememberUpdatedState(onSpans)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(contextPatches) {
                detectTapGestures { off ->
                    val p = patchAt(off.x, size.width, contextPatches)
                    val hit = current.firstOrNull { p >= it.startPatch && p < it.endPatch }
                    if (hit != null) {
                        haptics.perform(HapticEvent.Reject)   // a span removed
                        emit(current - hit)
                    }
                }
            }
            .pointerInput(contextPatches) {
                detectDragGestures(
                    onDragStart = { off ->
                        dragFrom = patchAt(off.x, size.width, contextPatches)
                        paintDetent.reset()
                        haptics.perform(HapticEvent.DragStart)
                    },
                    onDragEnd = { dragFrom = -1; haptics.perform(HapticEvent.DragEnd) },
                ) { change, _ ->
                    val from = dragFrom
                    if (from >= 0) {
                        val to = patchAt(change.position.x, size.width, contextPatches)
                        val lo = min(from, to)
                        val hi = max(from, to)
                        paintDetent.at(to)
                        emit(addSpan(current, LabSpan(lo, hi - lo + 1), contextPatches))
                    }
                }
            },
    ) {
        val w = size.width / contextPatches
        for (p in 0 until contextPatches) {
            val inSpan = spans.any { p >= it.startPatch && p < it.endPatch }
            drawRect(
                color = if (inSpan) masked else visible,
                topLeft = Offset(p * w, 0f),
                size = androidx.compose.ui.geometry.Size(max(1f, w - 0.5f), size.height),
            )
        }
    }
}

private fun patchAt(x: Float, width: Int, patches: Int): Int =
    ((x / max(1, width)) * patches).toInt().coerceIn(0, patches - 1)

/**
 * Merge a painted span into the set, keeping the model's rules: no overlap, no abutting neighbour
 * (one visible patch must separate them), and never touching the final patch, whose visibility is
 * what the trailing forecast anchors on.
 */
internal fun addSpan(spans: List<LabSpan>, add: LabSpan, contextPatches: Int): List<LabSpan> {
    if (add.patches < 1 || contextPatches < 2) return spans
    // A drag that runs off the end paints up to the last LEGAL patch rather than doing nothing:
    // the final patch must stay visible, since it is what the trailing forecast anchors on.
    val start = add.startPatch.coerceIn(0, contextPatches - 2)
    val end = min(add.endPatch, contextPatches - 1)
    if (end <= start) return spans
    val candidate = LabSpan(start, end - start)
    val kept = spans.filterNot { it.startPatch <= candidate.endPatch && candidate.startPatch <= it.endPatch }
    val merged = (kept + candidate).sortedBy { it.startPatch }
    // Drop any span that ends up abutting its neighbour rather than silently fusing them: fusing
    // would produce one longer span the user did not paint.
    return merged.filterIndexed { i, s ->
        val prev = merged.getOrNull(i - 1)
        prev == null || s.startPatch > prev.endPatch
    }
}

/**
 * The run, drawn: the context trace, each masked span's fan in place, and the line at the slider's
 * τ. A masked span is drawn as a gap in the trace, which is what the model was actually given.
 */
@Composable
private fun LabChart(run: LabRun, tau: Double, modifier: Modifier) {
    val trace = MaterialTheme.colorScheme.onSurface
    val forecast = MaterialTheme.colorScheme.primary
    val infill = MaterialTheme.colorScheme.tertiary
    Canvas(modifier.background(MaterialTheme.colorScheme.surface)) {
        val values = ArrayList<Double>()
        run.contextBg.filterNotNull().forEach { values.add(it) }
        run.fans.forEach { f -> values.addAll(f.lo); values.addAll(f.hi) }
        if (values.isEmpty()) return@Canvas
        val lo = (values.min() - 10).coerceAtLeast(0.0)
        val hi = values.max() + 10
        val totalSteps = run.contextBg.size + (run.fans.filter { it.isForecast }.maxOfOrNull { it.lo.size } ?: 0)
        if (totalSteps <= 1 || hi <= lo) return@Canvas
        val dx = size.width / (totalSteps - 1)
        fun y(v: Double) = (size.height * (1.0 - (v - lo) / (hi - lo))).toFloat()
        fun x(i: Int) = i * dx

        // The observed trace, broken wherever a patch was withheld — the gap IS what the model
        // was given, so drawing across it would show evidence that was not there.
        var segment = ArrayList<Pair<Float, Float>>()
        fun flush() {
            if (segment.size >= 2) {
                val p = Path()
                segment.forEachIndexed { i, (px, py) -> if (i == 0) p.moveTo(px, py) else p.lineTo(px, py) }
                drawPath(p, trace, style = Stroke(width = 2f, cap = StrokeCap.Round))
            }
            segment = ArrayList()
        }
        run.contextBg.forEachIndexed { i, v ->
            if (v == null) flush() else segment.add(x(i) to y(v))
        }
        flush()

        // Each span's fan, in place.
        run.fans.forEach { f ->
            val color = if (f.isForecast) forecast else infill
            val band = Path()
            f.hi.forEachIndexed { i, v ->
                val xi = x(f.startStep + i)
                if (i == 0) band.moveTo(xi, y(v)) else band.lineTo(xi, y(v))
            }
            for (i in f.lo.indices.reversed()) band.lineTo(x(f.startStep + i), y(f.lo[i]))
            band.close()
            drawPath(band, color.copy(alpha = 0.18f))
            val line = f.at(tau)
            val lp = Path()
            line.forEachIndexed { i, v ->
                val xi = x(f.startStep + i)
                if (i == 0) lp.moveTo(xi, y(v)) else lp.lineTo(xi, y(v))
            }
            drawPath(lp, color, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
        }
        drawTargetRails(lo, hi, ::y)
    }
}

/** The 70 and 180 mg/dL rails, so an excursion is readable without axis labels. */
private fun DrawScope.drawTargetRails(lo: Double, hi: Double, y: (Double) -> Float) {
    listOf(70.0, 180.0).forEach { v ->
        if (v in lo..hi) {
            drawLine(
                Color.Gray.copy(alpha = 0.35f),
                Offset(0f, y(v)),
                Offset(size.width, y(v)),
                strokeWidth = 1f,
            )
        }
    }
}
