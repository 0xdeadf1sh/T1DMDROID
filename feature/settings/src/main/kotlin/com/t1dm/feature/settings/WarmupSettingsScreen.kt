package com.t1dm.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Settings → Inference warmup (inference-runtime.md — the WARMUP gate). Human-readable control over
 * how many hours of MEASURED (non-interpolated) BG must accrue before the dashboard forecast turns
 * on. Floored at the model MIN_CONTEXT (8 h) — the stepper cannot go below it — and capped at 72 h.
 * Pure/stateless: [hours] is the persisted value, [onChange] writes the new whole-hour value.
 */
@Composable
fun WarmupSettingsScreen(
    hours: Int,
    minHours: Int = 8,
    maxHours: Int = 72,
    onChange: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Before showing a forecast, the model waits for this much real (measured) BG history. " +
                "Interpolated fill and sensor warm-up readings do not count.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { onChange((hours - 1).coerceAtLeast(minHours)) },
                enabled = hours > minHours,
            ) { Text("−1 h") }

            Text(
                text = formatHours(hours),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )

            Button(
                onClick = { onChange((hours + 1).coerceAtMost(maxHours)) },
                enabled = hours < maxHours,
            ) { Text("+1 h") }
        }

        Text(
            "Minimum ${formatHours(minHours)} (the model's context floor) · maximum ${formatHours(maxHours)}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 20.dp),
        )
    }
}

private fun formatHours(h: Int): String = when {
    h % 24 == 0 && h >= 24 -> "${h / 24} day${if (h == 24) "" else "s"} (${h} h)"
    else -> "$h hours"
}
