package com.t1dm.feature.models

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.rememberT1dmHaptics

/** Nothing produced here is stored, synced, alarmed on, or read by the dose calculator. */
@Composable
fun LabScreen(
    state: LabUiState,
    onPickModel: (String) -> Unit,
    onSeed: (Long) -> Unit,
    onGenerate: () -> Unit,
    onOpenAdapters: (String) -> Unit,
) {
    val haptics = rememberT1dmHaptics()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
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

        // The model is picked for its window LENGTH and nothing else — no inference runs here.
        Text(
            "${state.realPatches}/${state.contextPatches} patches real",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { haptics.perform(HapticEvent.Commit); onGenerate() },
                enabled = !state.generating && state.blocked == null,
            ) { Text(if (state.generating) "Generating" else "Generate") }
            OutlinedButton(onClick = { onSeed(state.seed + 1) }) { Text("Seed ${state.seed}") }
        }
        state.blocked?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        state.generated?.let { synth ->
            HorizontalDivider()
            Text(
                "seed ${synth.seed} · ${synth.bg.size} steps · ${synth.syntheticSteps} invented",
                style = MaterialTheme.typography.bodySmall,
            )
            SynthChart(synth, Modifier.fillMaxWidth().height(220.dp))
        }
    }
}

/** Invented stretches in the accent, measured ones in the surface ink. */
@Composable
private fun SynthChart(synth: LabSynth, modifier: Modifier) {
    val measured = MaterialTheme.colorScheme.onSurface
    val invented = MaterialTheme.colorScheme.tertiary
    Canvas(modifier.background(MaterialTheme.colorScheme.surface)) {
        val bg = synth.bg
        if (bg.size <= 1) return@Canvas
        val finite = bg.filter { it.isFinite() }
        if (finite.isEmpty()) return@Canvas
        val lo = (finite.min() - 10).coerceAtLeast(0.0)
        val hi = finite.max() + 10
        if (hi <= lo) return@Canvas
        val dx = size.width / (bg.size - 1)
        fun y(v: Double) = (size.height * (1.0 - (v - lo) / (hi - lo))).toFloat()
        fun x(i: Int) = i * dx

        // Segmented by provenance; a non-finite step breaks the run rather than bridging it.
        var start = 0
        while (start < bg.size) {
            if (!bg[start].isFinite()) { start++; continue }
            val real = synth.real.getOrElse(start) { false }
            var stop = start
            while (stop < bg.size && bg[stop].isFinite() &&
                synth.real.getOrElse(stop) { false } == real
            ) {
                stop++
            }
            if (stop - start >= 2) {
                val p = Path()
                for (i in start until stop) {
                    if (i == start) p.moveTo(x(i), y(bg[i])) else p.lineTo(x(i), y(bg[i]))
                }
                drawPath(
                    p,
                    if (real) measured else invented,
                    style = Stroke(width = 2f, cap = StrokeCap.Round),
                )
            }
            start = if (stop == start) start + 1 else stop
        }
        drawTargetRails(lo, hi, ::y)
    }
}

/** The 70 and 180 mg/dL rails. */
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
