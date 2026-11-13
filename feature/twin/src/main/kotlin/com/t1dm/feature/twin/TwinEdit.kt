package com.t1dm.feature.twin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

/** Inline under the twin, so the body stays in view; [onDrag] moves it live, [onCommit] stores. */
@Composable
internal fun TwinEditPanel(
    effective: TwinScan,
    edited: Set<String>,
    onDrag: (TwinTrait, Double) -> Unit,
    onCommit: (TwinTrait) -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth().background(cs.surface.copy(alpha = 0.92f))) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 8.dp)) {
            for (t in EDITABLE) {
                val v = effective[t] ?: (t.min + t.max) / 2
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        t.label.uppercase(Locale.ROOT) + if (t.key in edited) " ·" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (t.key in edited) cs.secondary else cs.onSurfaceVariant,
                    )
                    Text(editValue(t, v), style = MaterialTheme.typography.labelMedium, color = cs.primary)
                }
                Slider(
                    value = v.toFloat(),
                    onValueChange = { onDrag(t, it.toDouble()) },
                    onValueChangeFinished = { onCommit(t) },
                    valueRange = t.min.toFloat()..t.max.toFloat(),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onReset, enabled = edited.isNotEmpty()) { Text("Reset to scan") }
                TextButton(onClick = onDone) { Text("Done") }
            }
        }
    }
}

private fun editValue(t: TwinTrait, v: Double): String = when (t) {
    TwinTrait.SEX -> "%.2f P(male)".format(Locale.ROOT, v)
    TwinTrait.MUSCLE -> "%.2f".format(Locale.ROOT, v)
    else -> "%.1f %s".format(Locale.ROOT, v, t.unit)
}
