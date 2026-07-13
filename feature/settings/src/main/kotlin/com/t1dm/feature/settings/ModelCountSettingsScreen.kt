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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Settings → Models run at once (issues 1/2 — the running-set cap). Human-readable control over how
 * many discovered forecasting models run each cycle. Every running model forecasts and pushes its
 * prediction to the server tagged by its model id; the model selected on the Models screen is the one
 * whose forecast the dashboard draws. The stepper is coerced to [minCount, maxCount].
 * Pure/stateless: [count] is the persisted value, [onChange] writes the new whole-number value.
 */
@Composable
fun ModelCountSettingsScreen(
    count: Int,
    minCount: Int = 1,
    maxCount: Int = 8,
    onChange: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "How many forecasting models run at the same time. Every running model makes a forecast " +
                "each cycle and sends it to the server tagged by its model id; the model you pick on the " +
                "Models screen is the one whose forecast this dashboard shows.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { onChange((count - 1).coerceAtLeast(minCount)) },
                enabled = count > minCount,
            ) { Text("−1") }

            Text(
                text = count.toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )

            Button(
                onClick = { onChange((count + 1).coerceAtMost(maxCount)) },
                enabled = count < maxCount,
            ) { Text("+1") }
        }

        Text(
            "Minimum 1 · maximum 8. More models = more CPU each cycle.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 20.dp),
        )
    }
}
