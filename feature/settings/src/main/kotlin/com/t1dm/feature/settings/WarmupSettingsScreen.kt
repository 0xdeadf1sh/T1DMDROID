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
    val haptics = rememberT1dmHaptics()
    SettingsScaffold(SettingsScreenKey.WARMUP) {
        SettingsNote("Measured BG the forecast waits for; fill doesn't count")

        SettingsAnchor(warmupHours) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        haptics.perform(HapticEvent.SegmentTick)
                        onChange((hours - 1).coerceAtLeast(minHours))
                    },
                    enabled = hours > minHours,
                ) { Text("−1 h") }

                // I13 — the ±1 h buttons keep their intrinsic size; the value field flexes (weight) and
                // centres, so a wide value like "1 day (24 h)" can never balloon the steppers.
                Text(
                    text = formatHours(hours),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )

                Button(
                    onClick = {
                        haptics.perform(HapticEvent.SegmentTick)
                        onChange((hours + 1).coerceAtMost(maxHours))
                    },
                    enabled = hours < maxHours,
                ) { Text("+1 h") }
            }
        }

        SettingsNote("Min ${formatHours(minHours)} (the model's context floor) · max ${formatHours(maxHours)}.")
    }
}

private fun formatHours(h: Int): String = when {
    h % 24 == 0 && h >= 24 -> "${h / 24} day${if (h == 24) "" else "s"} (${h} h)"
    else -> "$h hours"
}

// ── search index (see SettingsIndex.kt) ───────────────────────────────────────────────────────────

private val warmupHours = SettingsKnob(
    id = "warmup.hours",
    screen = SettingsScreenKey.WARMUP,
    section = "Forecast warmup",
    label = "Forecast warmup",
    subtitle = "How much real, measured BG history must accrue before a forecast is shown",
    synonyms = listOf(
        "warmup", "warm up", "warm-up", "history", "context", "hours", "before forecasting",
        "startup", "settle", "min context", "no forecast", "waiting", "first run", "delay",
    ),
)

internal val settingsWarmupKnobs = listOf(warmupHours)
