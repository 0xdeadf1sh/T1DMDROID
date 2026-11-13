package com.t1dm.feature.twin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.t1dm.core.model.TwinScan
import com.t1dm.core.model.TwinTrait
import java.util.Locale

/** The traits a user may hand-tune; each reshapes the body. */
internal val EDITABLE = listOf(TwinTrait.HEIGHT, TwinTrait.WEIGHT, TwinTrait.AGE, TwinTrait.SEX, TwinTrait.MUSCLE)

/** One view height of vertical drag sweeps this share of a trait's range. */
private const val RANGE_PER_HEIGHT = 0.8

/** [scan] with every [overrides] entry, then every [draft] entry, laid over its outputs. */
internal fun effectiveScan(scan: TwinScan?, overrides: Map<String, Double>, draft: Map<String, Double>): TwinScan? {
    if (scan == null) return null
    if (overrides.isEmpty() && draft.isEmpty()) return scan
    return scan.copy(outputs = scan.outputs + overrides + draft)
}

/** [up] is the drag as a fraction of the view height, + = finger moved up. */
internal fun nudged(t: TwinTrait, current: Double, up: Float): Double =
    (current + up * (t.max - t.min) * RANGE_PER_HEIGHT).coerceIn(t.min, t.max)

internal fun TwinTrait.mid(): Double = (min + max) / 2

/** One row: trait chips pick what a vertical drag on the body changes. */
@Composable
internal fun TwinEditBar(
    selected: TwinTrait,
    edited: Set<String>,
    onSelect: (TwinTrait) -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (t in EDITABLE) {
                FilterChip(
                    selected = t == selected,
                    onClick = { onSelect(t) },
                    label = { Text(t.label + if (t.key in edited) " ·" else "") },
                )
            }
        }
        TextButton(onClick = onReset, enabled = edited.isNotEmpty()) { Text("Reset") }
        TextButton(onClick = onDone) { Text("Done") }
    }
}

/** Over the body while editing: the trait's value up top, its place in range on the right edge. */
@Composable
internal fun TwinEditGauge(trait: TwinTrait, value: Double, edited: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(modifier) {
        Column(Modifier.align(Alignment.TopCenter).padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                trait.label.uppercase(Locale.ROOT) + if (edited) " · EDITED" else "",
                style = MaterialTheme.typography.labelMedium,
                color = if (edited) cs.secondary else cs.onSurfaceVariant,
            )
            Text(editValue(trait, value), style = MaterialTheme.typography.headlineSmall, color = cs.primary)
            Text("↕ drag body", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        val frac = ((value - trait.min) / (trait.max - trait.min)).toFloat().coerceIn(0f, 1f)
        Canvas(Modifier.align(Alignment.CenterEnd).padding(end = 14.dp).width(12.dp).fillMaxHeight(0.5f)) {
            val x = size.width / 2
            val y = size.height * (1f - frac)
            drawLine(cs.onSurfaceVariant.copy(alpha = 0.3f), Offset(x, 0f), Offset(x, size.height), 3.dp.toPx(), StrokeCap.Round)
            drawLine(cs.primary, Offset(x, size.height), Offset(x, y), 3.dp.toPx(), StrokeCap.Round)
            drawCircle(cs.primary, 6.dp.toPx(), Offset(x, y))
        }
    }
}

private fun editValue(t: TwinTrait, v: Double): String = when (t) {
    TwinTrait.SEX -> "%.2f P(male)".format(Locale.ROOT, v)
    TwinTrait.MUSCLE -> "%.2f".format(Locale.ROOT, v)
    else -> "%.1f %s".format(Locale.ROOT, v, t.unit)
}
