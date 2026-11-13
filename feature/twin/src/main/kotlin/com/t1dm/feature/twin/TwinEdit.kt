package com.t1dm.feature.twin

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.t1dm.core.model.TwinScan
import com.t1dm.core.model.TwinTrait
import java.util.Locale

/** The traits a user may hand-tune; each reshapes the body. */
internal val EDITABLE = listOf(TwinTrait.HEIGHT, TwinTrait.WEIGHT, TwinTrait.AGE, TwinTrait.SEX, TwinTrait.MUSCLE)

/** [scan] with every [overrides] entry, then every [draft] entry, laid over its outputs. */
internal fun effectiveScan(scan: TwinScan?, overrides: Map<String, Double>, draft: Map<String, Double>): TwinScan? {
    if (scan == null) return null
    if (overrides.isEmpty() && draft.isEmpty()) return scan
    return scan.copy(outputs = scan.outputs + overrides + draft)
}

internal fun TwinTrait.mid(): Double = (min + max) / 2

/** Chips pick one trait; the slider below sets it, live via [onDrag], stored on release. */
@Composable
internal fun TwinEditBar(
    selected: TwinTrait,
    value: Double,
    edited: Set<String>,
    onSelect: (TwinTrait) -> Unit,
    onDrag: (Double) -> Unit,
    onCommit: () -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                editValue(selected, value),
                style = MaterialTheme.typography.titleMedium,
                color = if (selected.key in edited) cs.secondary else cs.primary,
                modifier = Modifier.padding(end = 12.dp),
            )
            Slider(
                value = value.toFloat(),
                onValueChange = { onDrag(it.toDouble()) },
                onValueChangeFinished = onCommit,
                valueRange = selected.min.toFloat()..selected.max.toFloat(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun editValue(t: TwinTrait, v: Double): String = when (t) {
    TwinTrait.SEX -> "%.2f".format(Locale.ROOT, v)
    TwinTrait.MUSCLE -> "%.2f".format(Locale.ROOT, v)
    else -> "%.0f %s".format(Locale.ROOT, v, t.unit)
}
