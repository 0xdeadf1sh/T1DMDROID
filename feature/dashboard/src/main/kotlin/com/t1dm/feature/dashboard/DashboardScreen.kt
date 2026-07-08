package com.t1dm.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.UnitSpace
import com.t1dm.ui.graph.GlucoseGraph
import com.t1dm.ui.graph.GraphFrame
import com.t1dm.ui.graph.PredSeries
import com.t1dm.ui.graph.graphFrameOf
import com.t1dm.ui.graph.predOverlayOf

/**
 * The live BG dashboard (PLAN.private.md Phase 1 — "Dashboard shows the live graph + current
 * BG/trend from the Repository Flow"). It is a pure function of the state `:app` collects from the
 * repository: a header with the latest measurement + trend + active source, and the reusable
 * [GlucoseGraph] over a [GraphFrame] built off-thread by [graphFrameOf]. No storage, no service,
 * no `:inference` — just the observed truth.
 */
@Composable
fun DashboardScreen(
    readings: List<CgmReading>,
    latest: CgmReading?,
    activeSourceName: String?,
    thresholds: AlertThresholds? = null,
    unit: UnitSpace = UnitSpace.MgDl,
    predictions: List<ModelPrediction> = emptyList(),
    kovatchevF: ((Double) -> Double)? = null,
) {
    val frame by produceState(GraphFrame.EMPTY, readings, unit) {
        value = graphFrameOf(readings, unit, kovatchevF = kovatchevF)
    }
    val overlay by produceState(emptyList<PredSeries>(), predictions, unit) {
        value = predOverlayOf(predictions, unit, kovatchevF = kovatchevF)
    }

    Column(Modifier.fillMaxSize()) {
        DashboardHeader(latest, activeSourceName, unit)
        GlucoseGraph(
            frame = frame,
            modifier = Modifier.fillMaxWidth().weight(1f),
            thresholds = thresholds,
            predictions = overlay,
        )
    }
}

@Composable
private fun DashboardHeader(latest: CgmReading?, activeSourceName: String?, unit: UnitSpace) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = formatBg(latest?.bgMgdl, unit),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = unitLabel(unit) + (latest?.let { statusSuffix(it) } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(text = trendArrow(latest?.trendTenthsPerMin), style = MaterialTheme.typography.headlineMedium)
            Text(text = activeSourceName ?: "no source", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun statusSuffix(r: CgmReading): String = when {
    r.flag == ReadingFlag.WARMUP -> "  • warmup"
    r.provenance == ReadingProvenance.INTERPOLATED -> "  • interpolated"
    else -> ""
}

private fun unitLabel(unit: UnitSpace): String = when (unit) {
    UnitSpace.MgDl -> "mg/dL"
    UnitSpace.MmolL -> "mmol/L"
    UnitSpace.Kovatchev -> "risk"
}

private fun formatBg(bgMgdl: Int?, unit: UnitSpace): String {
    if (bgMgdl == null) return "--"
    return when (unit) {
        UnitSpace.MgDl, UnitSpace.Kovatchev -> bgMgdl.toString()
        UnitSpace.MmolL -> String.format("%.1f", bgMgdl / 18.0182)
    }
}

/** Coarse trend glyph from the 0.1 mg/dL/min rate (real arrows are Phase 7 polish). */
private fun trendArrow(tenths: Int?): String = when {
    tenths == null -> "→"
    tenths >= 30 -> "⇈"
    tenths >= 10 -> "↗"
    tenths <= -30 -> "⇊"
    tenths <= -10 -> "↘"
    else -> "→"
}
