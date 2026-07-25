package com.t1dm.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.rememberT1dmHaptics

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
    val haptics = rememberT1dmHaptics()
    SettingsScaffold(SettingsScreenKey.MODEL_COUNT) {
        SettingsNote("Models run per cycle")

        SettingsAnchor(modelCountRunning) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        haptics.perform(HapticEvent.SegmentTick)
                        onChange((count - 1).coerceAtLeast(minCount))
                    },
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
                    onClick = {
                        haptics.perform(HapticEvent.SegmentTick)
                        onChange((count + 1).coerceAtMost(maxCount))
                    },
                    enabled = count < maxCount,
                ) { Text("+1") }
            }
        }

        SettingsNote("1–8 · more models, more CPU")
    }
}

// ── search index (see SettingsIndex.kt) ───────────────────────────────────────────────────────────

private val modelCountRunning = SettingsKnob(
    id = "model_count.running",
    screen = SettingsScreenKey.MODEL_COUNT,
    section = "Models run at once",
    label = "Models run at once",
    subtitle = "How many discovered models forecast each cycle — more models cost more CPU",
    synonyms = listOf(
        "models", "how many models", "running set", "ensemble", "concurrent", "parallel",
        "simultaneous", "cap", "limit", "cpu", "battery", "load", "count",
    ),
)

internal val settingsModelCountKnobs = listOf(modelCountRunning)
