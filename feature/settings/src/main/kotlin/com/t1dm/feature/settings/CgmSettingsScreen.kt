package com.t1dm.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.t1dm.core.model.CgmSourceDescriptor

/**
 * Settings → CGM source. Surfaces the auto-adopted active source and every recorded source (manual
 * re-selection lands with the multi-source work).
 *  - I10: the ACTIVE source's live BLE signal strength (RSSI dBm + bars), reusing the BG-panel meter.
 *  - I11: a USER-ENTERED sensor lifetime. Because the AiDEX X is a passive advertisement listener we
 *    cannot read the sensor's true age, so the user enters the remaining life (days + hours + minutes);
 *    we store an absolute expiry instant and count it down live here and in the BG panel. It is plainly
 *    a user estimate, not read from the sensor, with a renew/reset for a new sensor.
 *  - The ACTIVE source's warm-up window. Unlike the lifetime this one IS anchored on something the
 *    sensor reports — `minFromStart`, its own minutes-since-activation — but the advert carries no
 *    warm-up flag, so the configured duration is the entire warm-up rule and the BG panel's warm-up
 *    countdown is derived from it.
 */
@Composable
fun CgmSettingsScreen(
    activeSourceName: String?,
    activeStatus: String?,
    allSourceNames: List<String>,
    activeRssi: Int? = null,
    sensorExpiryMs: Long? = null,
    /** The ACTIVE source's configured warm-up window (minutes), or null when no source is on record —
     *  the window is per-source, so with nothing active there is nothing to edit. */
    warmupWindowMin: Int? = null,
    onSetWarmupMin: (Int) -> Unit = {},
    onSetSensorLifetime: (days: Int, hours: Int, minutes: Int) -> Unit = { _, _, _ -> },
    onClearSensorLifetime: () -> Unit = {},
    aggressiveEnabled: Boolean = false,
    aggressiveShowGlucose: Boolean = true,
    aggressiveOnlyCharging: Boolean = false,
    hasOverlayPermission: Boolean = true,
    onSetAggressiveEnabled: (Boolean) -> Unit = {},
    onSetAggressiveShowGlucose: (Boolean) -> Unit = {},
    onSetAggressiveOnlyCharging: (Boolean) -> Unit = {},
    onRequestOverlay: () -> Unit = {},
) {
    SettingsScaffold(SettingsScreenKey.CGM) {
        SettingsAnchor(cgmSource) {
            SettingsSectionHeader(SOURCE_SECTION)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = activeSourceName?.let { "$it${activeStatus?.let { s -> " • $s" } ?: ""}" }
                        ?: "none yet — scanning",
                    style = MaterialTheme.typography.bodyLarge,
                )
                // I10 — the active source's live signal strength, the same meter shown in the BG panel.
                activeRssi?.let { SignalBars(it) }
            }
            Text("Recorded", style = MaterialTheme.typography.labelMedium)
            if (allSourceNames.isEmpty()) {
                Text("none", style = MaterialTheme.typography.bodyMedium)
            } else {
                allSourceNames.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }

        SettingsAnchor(cgmLifetime) {
            SettingsSectionHeader(LIFETIME_SECTION)
            SettingsNote("A passive listener can't read the sensor's age — this is your estimate")
            SensorLifetimeSection(sensorExpiryMs, onSetSensorLifetime, onClearSensorLifetime)
        }

        SettingsSectionHeader(WARMUP_SECTION)
        SettingsNote("No warm-up flag in the advert — this alone decides it; 0 = off")
        // The window belongs to the ACTIVE source, so with none on record there is nothing to edit and
        // the stepper is absent rather than showing an invented default.
        warmupWindowMin?.let {
            IntStepper(
                knob = cgmWarmup,
                value = it,
                unit = "min",
                // The 5-minute CGM grid quantum: every stop is a slot the pipeline can distinguish.
                step = WARMUP_STEP_MIN,
                min = CgmSourceDescriptor.WARMUP_WINDOW_RANGE.first,
                max = CgmSourceDescriptor.WARMUP_WINDOW_RANGE.last,
                onChange = onSetWarmupMin,
            )
        }

        SettingsSectionHeader(AGGRESSIVE_SECTION)
        SettingsNote("HyperOS suspends the scan when the screen sleeps; this holds the display dark but on")
        ToggleRow(cgmAggressive, aggressiveEnabled, onSetAggressiveEnabled)
        if (aggressiveEnabled) {
            if (!hasOverlayPermission) {
                Text(
                    "Needs “Display over other apps” to raise the dark screen while locked",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = onRequestOverlay) { Text("Grant") }
            }
            ToggleRow(cgmAggressiveShowBg, aggressiveShowGlucose, onSetAggressiveShowGlucose)
            ToggleRow(cgmAggressiveCharging, aggressiveOnlyCharging, onSetAggressiveOnlyCharging)
        }
    }
}

@Composable
private fun SensorLifetimeSection(
    expiryMs: Long?,
    onSet: (Int, Int, Int) -> Unit,
    onClear: () -> Unit,
) {
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
            if (remainingMs <= 0L) "Elapsed — renew for a new sensor" else "Remaining: ${fullRemaining(remainingMs)}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (remainingMs <= 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }

    var days by remember { mutableStateOf(10) }
    var hours by remember { mutableStateOf(0) }
    var minutes by remember { mutableStateOf(0) }

    DurationStepper("Days", days, max = 30) { days = it }
    DurationStepper("Hours", hours, max = 23) { hours = it }
    DurationStepper("Minutes", minutes, max = 59, step = 5) { minutes = it }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = { onSet(days, hours, minutes) },
            enabled = days > 0 || hours > 0 || minutes > 0,
        ) { Text(if (expiryMs == null) "Set" else "Renew") }
        if (expiryMs != null) {
            OutlinedButton(onClick = onClear) { Text("Clear") }
        }
    }
}

@Composable
private fun DurationStepper(label: String, value: Int, min: Int = 0, max: Int, step: Int = 1, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
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

// ── search index (see SettingsIndex.kt) ───────────────────────────────────────────────────────────

private const val SOURCE_SECTION = "Source"
private const val LIFETIME_SECTION = "Sensor lifetime"
private const val WARMUP_SECTION = "Sensor warm-up"
private const val AGGRESSIVE_SECTION = "Aggressive background scanning"

/** Stepper grain for the warm-up window: the 5-minute CGM grid quantum. */
private const val WARMUP_STEP_MIN = 5

private val cgmSource = SettingsKnob(
    id = "cgm.source",
    screen = SettingsScreenKey.CGM,
    section = SOURCE_SECTION,
    label = "Active CGM source",
    subtitle = "The sensor being listened to, its signal, and every source seen before",
    synonyms = listOf(
        "cgm", "sensor", "aidex", "linx", "glucose sensor", "transmitter", "source", "device",
        "signal", "rssi", "bluetooth", "ble", "scan",
    ),
)

private val cgmLifetime = SettingsKnob(
    id = "cgm.sensor_lifetime",
    screen = SettingsScreenKey.CGM,
    section = LIFETIME_SECTION,
    label = "Sensor lifetime",
    subtitle = "Remaining life you enter yourself; counted down here and in the BG panel",
    synonyms = listOf(
        "lifetime", "life", "expiry", "expires", "remaining", "age", "days left", "renew",
        "new sensor", "replace", "countdown", "wear time",
    ),
)

private val cgmWarmup = SettingsKnob(
    id = "cgm.warmup_window",
    screen = SettingsScreenKey.CGM,
    section = WARMUP_SECTION,
    label = "Warm-up window",
    subtitle = "How long after activation readings are flagged warm-up and kept out of forecasts and alarms",
    synonyms = listOf(
        "warmup", "warm up", "warm-up", "settling", "new sensor", "first hour", "startup",
        "suppress", "minutes", "window", "grace", "insertion",
    ),
)

private val cgmAggressive = SettingsKnob(
    id = "cgm.aggressive_scan",
    screen = SettingsScreenKey.CGM,
    section = AGGRESSIVE_SECTION,
    label = "Keep scanning while locked",
    subtitle = "Holds the display on but dark so the scan survives screen-off; heavy on battery",
    synonyms = listOf(
        "aggressive", "background", "screen off", "locked", "always on", "aod", "keep alive",
        "hyperos", "suspend", "doze", "overlay", "battery",
    ),
)

private val cgmAggressiveShowBg = SettingsKnob(
    id = "cgm.aggressive_show_glucose",
    screen = SettingsScreenKey.CGM,
    section = AGGRESSIVE_SECTION,
    label = "Show glucose on the dark screen",
    subtitle = "Render a dim read-out instead of pure black",
    synonyms = listOf("glucose", "show", "dark screen", "aod", "display", "readout", "dim"),
)

private val cgmAggressiveCharging = SettingsKnob(
    id = "cgm.aggressive_only_charging",
    screen = SettingsScreenKey.CGM,
    section = AGGRESSIVE_SECTION,
    label = "Only while charging",
    subtitle = "Restrict the mode to the charger to bound its battery cost",
    synonyms = listOf("charging", "charger", "plugged in", "battery", "power", "only"),
)

internal val settingsCgmKnobs =
    listOf(cgmSource, cgmLifetime, cgmWarmup, cgmAggressive, cgmAggressiveShowBg, cgmAggressiveCharging)
