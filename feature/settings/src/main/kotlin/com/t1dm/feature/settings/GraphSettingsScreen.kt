package com.t1dm.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Settings → BG graph range (Phase 7A item 1). The glucose Y-axis always spans at
 * least [minMgdl]..[maxMgdl]; it grows ABOVE the ceiling to never clip a high reading. Both are
 * mg/dL and stepped in 5-mg/dL increments; [onChange] persists the new pair (`min < max` enforced
 * upstream). Pure/stateless — the caller owns the persisted value.
 */
@Composable
fun GraphSettingsScreen(
    minMgdl: Int,
    maxMgdl: Int,
    windowHours: Int,
    windowPresets: List<Int>,
    onChange: (Int, Int) -> Unit,
    onSetWindow: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "The glucose axis always covers this window. It expands automatically above the ceiling " +
                "so a high reading is never clipped off the top.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp),
        )

        Stepper(
            label = "Floor",
            value = minMgdl,
            onDown = { onChange((minMgdl - 5).coerceAtLeast(0), maxMgdl) },
            onUp = { onChange((minMgdl + 5).coerceAtMost(maxMgdl - 5), maxMgdl) },
        )
        Stepper(
            label = "Ceiling",
            value = maxMgdl,
            onDown = { onChange(minMgdl, (maxMgdl - 5).coerceAtLeast(minMgdl + 5)) },
            onUp = { onChange(minMgdl, maxMgdl + 5) },
        )

        Text(
            "Default 20–250 mg/dL.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 12.dp),
        )

        Text(
            "Default time window",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            windowPresets.forEach { h ->
                FilterChip(selected = h == windowHours, onClick = { onSetWindow(h) }, label = { Text("${h}h") })
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, onDown: () -> Unit, onUp: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        // I13 — the ± buttons keep their intrinsic size; the VALUE field flexes (weight) and centres, so
        // a wide value can never balloon the steppers.
        OutlinedButton(onClick = onDown) { Text("−5") }
        Text(
            text = "$value mg/dL",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onUp) { Text("+5") }
    }
}
