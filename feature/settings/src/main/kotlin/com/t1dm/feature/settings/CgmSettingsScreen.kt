package com.t1dm.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.hapticClickable
import com.t1dm.core.design.SignalBars
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.model.CgmSourceDescriptor

@Composable
fun CgmSettingsScreen(
    activeSourceName: String?,
    activeStatus: String?,
    recordedSources: List<RecordedSource>,
    onRemoveSource: (String) -> Unit = {},
    onMakeAuthoritative: (String) -> Unit = {},
    onStartReading: (String) -> Unit = {},
    onStopReading: (String) -> Unit = {},
    activeRssi: Int? = null,
    /** Authoritative source's warm-up window, minutes; null when no source is on record. */
    warmupWindowMin: Int? = null,
    onSetWarmupMin: (Int) -> Unit = {},
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
                activeRssi?.let { SignalBars(it) }
            }
            Text("Recorded", style = MaterialTheme.typography.labelMedium)
            if (recordedSources.isEmpty()) {
                Text("none", style = MaterialTheme.typography.bodyMedium)
            } else {
                // Hoisted above the rows: a re-sighting rebuilds the list and would drop the ask.
                var confirming by remember { mutableStateOf<RecordedSource?>(null) }
                recordedSources.forEach { src ->
                    RecordedSourceRow(
                        src = src,
                        onMakeAuthoritative = onMakeAuthoritative,
                        onStartReading = onStartReading,
                        onStopReading = onStopReading,
                        onRequestRemove = { confirming = it },
                    )
                }
                confirming?.let { src ->
                    RemoveSourceDialog(
                        name = src.name,
                        onConfirm = { confirming = null; onRemoveSource(src.id) },
                        onDismiss = { confirming = null },
                    )
                }
            }
        }

        SettingsSectionHeader(WARMUP_SECTION)
        SettingsNote("No warm-up flag in the advert — this alone decides it; 0 = off")
        warmupWindowMin?.let {
            IntStepper(
                knob = cgmWarmup,
                value = it,
                unit = "min",
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

/** Several may be [active]; exactly one is [authoritative], and it is always also active. */
data class RecordedSource(
    val id: String,
    val name: String,
    val active: Boolean,
    val authoritative: Boolean,
)

@Composable
private fun RecordedSourceRow(
    src: RecordedSource,
    onMakeAuthoritative: (String) -> Unit,
    onStartReading: (String) -> Unit,
    onStopReading: (String) -> Unit,
    onRequestRemove: (RecordedSource) -> Unit,
) {
    val haptics = rememberT1dmHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .hapticClickable(HapticEvent.Confirm, enabled = !src.authoritative) {
                if (src.active) onMakeAuthoritative(src.id) else onStartReading(src.id)
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            src.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (src.authoritative) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        Text(
            when {
                src.authoritative -> "main"
                src.active -> "use"
                else -> "read"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (src.authoritative) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        // Stopping undoes in one press, so it doesn't ask; delisting can't undo, so it does.
        if (!src.authoritative) {
            val stops = src.active
            IconButton(
                onClick = {
                    haptics.perform(HapticEvent.Tap)
                    if (stops) onStopReading(src.id) else onRequestRemove(src)
                },
                // No TTS voice speaks U+2715; unlabelled otherwise.
                modifier = Modifier.size(40.dp).semantics {
                    contentDescription = if (stops) "Stop reading ${src.name}" else "Remove ${src.name}"
                },
            ) {
                Text(
                    "✕",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (stops) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun RemoveSourceDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val haptics = rememberT1dmHaptics()
    LaunchedEffect(name) { haptics.perform(HapticEvent.Warn) }
    AlertDialog(
        onDismissRequest = { haptics.perform(HapticEvent.Reject); onDismiss() },
        title = { Text("Remove $name?") },
        text = { Text("Readings are kept") },
        confirmButton = {
            TextButton(onClick = { haptics.perform(HapticEvent.Commit); onConfirm() }) { Text("Remove") }
        },
        dismissButton = {
            TextButton(onClick = { haptics.perform(HapticEvent.Reject); onDismiss() }) { Text("Cancel") }
        },
    )
}

private const val SOURCE_SECTION = "Source"
private const val WARMUP_SECTION = "Sensor warm-up"
private const val AGGRESSIVE_SECTION = "Aggressive background scanning"

/** The 5-minute CGM grid quantum. */
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
    listOf(cgmSource, cgmWarmup, cgmAggressive, cgmAggressiveShowBg, cgmAggressiveCharging)
