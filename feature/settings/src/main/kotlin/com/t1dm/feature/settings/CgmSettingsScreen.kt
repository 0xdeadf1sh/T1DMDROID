package com.t1dm.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.SignalBars

/**
 * Settings → CGM source (Phase 1 stub, extended). Surfaces the auto-adopted active source and every
 * recorded source (manual re-selection lands with the multi-source work). Phase-7 polish:
 *  - I10: the ACTIVE source's live BLE signal strength (RSSI dBm + bars), reusing the BG-panel meter.
 *  - I11: a USER-ENTERED sensor lifetime. Because the AiDEX X is a passive advertisement listener we
 *    cannot read the sensor's true age, so the user enters the remaining life (days + hours + minutes);
 *    we store an absolute expiry instant and count it down live here and in the BG panel. It is plainly
 *    a user estimate, not read from the sensor, with a renew/reset for a new sensor.
 */
@Composable
fun CgmSettingsScreen(
    activeSourceName: String?,
    activeStatus: String?,
    allSourceNames: List<String>,
    activeRssi: Int? = null,
    sensorExpiryMs: Long? = null,
    onSetSensorLifetime: (days: Int, hours: Int, minutes: Int) -> Unit = { _, _, _ -> },
    onClearSensorLifetime: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Active source", style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = activeSourceName?.let { "$it${activeStatus?.let { s -> " • $s" } ?: ""}" }
                    ?: "none yet — scanning",
                style = MaterialTheme.typography.bodyLarge,
            )
            // I10 — the active source's live signal strength, the same meter shown in the BG panel.
            activeRssi?.let { SignalBars(it) }
        }

        Text("Recorded sources", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 24.dp))
        if (allSourceNames.isEmpty()) {
            Text("none", style = MaterialTheme.typography.bodyMedium)
        } else {
            allSourceNames.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp)) }
        }

        SensorLifetimeSection(sensorExpiryMs, onSetSensorLifetime, onClearSensorLifetime)
    }
}

@Composable
private fun SensorLifetimeSection(
    expiryMs: Long?,
    onSet: (Int, Int, Int) -> Unit,
    onClear: () -> Unit,
) {
    Text("Sensor lifetime", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 28.dp))
    Text(
        "A passive listener cannot read the sensor's true age, so enter the remaining life yourself. " +
            "This is a user estimate, not read from the sensor.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    )

    if (expiryMs != null) {
        val now by produceState(System.currentTimeMillis(), expiryMs) {
            while (true) {
                value = System.currentTimeMillis()
                val remaining = expiryMs - value
                kotlinx.coroutines.delay(if (remaining in 1..60_000L) 1_000L else 60_000L)
            }
        }
        val remainingMs = expiryMs - now
        Text(
            if (remainingMs <= 0L) "Estimated lifetime elapsed — renew for a new sensor."
            else "Estimated remaining: ${fullRemaining(remainingMs)}.",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (remainingMs <= 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    var days by remember { mutableStateOf(10) }
    var hours by remember { mutableStateOf(0) }
    var minutes by remember { mutableStateOf(0) }

    DurationStepper("Days", days, max = 30) { days = it }
    DurationStepper("Hours", hours, max = 23) { hours = it }
    DurationStepper("Minutes", minutes, max = 59, step = 5) { minutes = it }

    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = { onSet(days, hours, minutes) },
            enabled = days > 0 || hours > 0 || minutes > 0,
        ) { Text(if (expiryMs == null) "Set lifetime" else "Renew (new sensor)") }
        if (expiryMs != null) {
            OutlinedButton(onClick = onClear) { Text("Clear") }
        }
    }
}

@Composable
private fun DurationStepper(label: String, value: Int, min: Int = 0, max: Int, step: Int = 1, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { onChange((value - step).coerceAtLeast(min)) }, enabled = value > min) { Text("−$step") }
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 1,
        )
        Button(onClick = { onChange((value + step).coerceAtMost(max)) }, enabled = value < max) { Text("+$step") }
    }
}

/** A full breakdown (all non-zero units) for the settings read-out, e.g. "9 d 3 h 20 m". */
private fun fullRemaining(ms: Long): String {
    val totalMin = ms / 60_000L
    val d = totalMin / 1440
    val h = (totalMin % 1440) / 60
    val m = totalMin % 60
    return buildList {
        if (d > 0) add("${d} d")
        if (h > 0) add("${h} h")
        if (m > 0 || isEmpty()) add("${m} m")
    }.joinToString(" ")
}
