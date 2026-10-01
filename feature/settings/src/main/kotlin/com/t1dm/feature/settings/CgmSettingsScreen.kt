package com.t1dm.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.SignalBars
import com.t1dm.core.design.rememberHapticDetent
import com.t1dm.core.model.CgmSourceDescriptor
import kotlin.math.roundToInt

@Composable
fun CgmSettingsScreen(
    activeSourceName: String?,
    activeStatus: String?,
    activeRssi: Int? = null,
    /** Null with no sensor on record. */
    warmupMin: Int? = null,
    onSetWarmupMin: (Int) -> Unit = {},
) {
    SettingsScaffold(SettingsScreenKey.CGM) {
        Text("Active source", style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = activeSourceName?.let { "$it${activeStatus?.let { s -> " • $s" } ?: ""}" }
                    ?: "none yet — scanning",
                style = MaterialTheme.typography.bodyLarge,
            )
            activeRssi?.let { SignalBars(it) }
        }

        Text(
            "Sensor controls live in the CGM panel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 16.dp),
        )

        // Absent, not defaulted: with no sensor on record the slider would edit nothing.
        warmupMin?.let { SettingsAnchor(cgmWarmup) { SensorWarmupSection(it, onSetWarmupMin) } }
    }
}

/** Minutes. Governs only readings that arrive without a sensor-reported window. */
@Composable
private fun SensorWarmupSection(warmupMin: Int, onSet: (Int) -> Unit) {
    Text("Sensor warm-up", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 16.dp))
    Text(
        "Sensor overrides this",
        style = MaterialTheme.typography.bodySmall,
        color = LocalContentColor.current.copy(alpha = 0.7f),
    )
    Text(
        if (warmupMin == 0) "off" else "$warmupMin min",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    val minuteDetent = rememberHapticDetent()
    val range = CgmSourceDescriptor.WARMUP_WINDOW_RANGE
    Slider(
        value = warmupMin.toFloat(),
        onValueChange = {
            val minutes = (it / WARMUP_STEP_MIN).roundToInt() * WARMUP_STEP_MIN
            val clamped = minutes.coerceIn(range)
            minuteDetent.at(clamped)
            onSet(clamped)
        },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = (range.last - range.first) / WARMUP_STEP_MIN - 1,
    )
}

/** The 5-minute CGM grid quantum. */
private const val WARMUP_STEP_MIN = 5

private val cgmSource = SettingsKnob(
    id = "cgm.source",
    screen = SettingsScreenKey.CGM,
    section = "Devices & sync",
    label = "CGM source",
    subtitle = "The active sensor and where its controls live (the CGM panel in the bottom nav)",
    synonyms = listOf(
        "cgm", "sensor", "aidex", "glucose sensor", "transmitter", "source", "connect", "pair",
        "activate", "reset sensor", "bluetooth", "gatt", "signal", "rssi", "device",
    ),
    anchored = false,
)

private val cgmWarmup = SettingsKnob(
    id = "cgm.warmup",
    screen = SettingsScreenKey.CGM,
    section = "Devices & sync",
    label = "Sensor warm-up",
    subtitle = "A sensor that reports its own overrides this",
    synonyms = listOf(
        "warm-up", "warmup", "warm up", "settling", "startup", "first readings", "grace",
    ),
)

internal val settingsCgmKnobs = listOf(cgmSource, cgmWarmup)
