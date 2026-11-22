package com.t1dm.feature.twin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.model.SensorPlacement
import com.t1dm.core.model.TwinScan

/** Tap the skin to pin [sensorId]; its pin glows live while the others hold their own. */
@Composable
fun TwinPlaceScreen(
    sensorId: String,
    sensorName: String,
    latest: TwinScan?,
    overrides: Map<String, Double>,
    sensors: List<TwinSensor>,
    staleAfterMs: Long,
    onSave: (SensorPlacement) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberT1dmHaptics()
    val cs = MaterialTheme.colorScheme
    var pick by remember { mutableStateOf<SensorPlacement?>(null) }
    val shown = sensors.map { s ->
        if (s.id != sensorId) s.copy(focus = false) else s.copy(placement = pick ?: s.placement, focus = true)
    }
    BackHandler(onBack = onCancel)
    Column(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            TwinView(
                effectiveScan(latest, overrides, emptyMap()),
                Modifier.fillMaxSize(),
                sensors = shown,
                staleAfterMs = staleAfterMs,
                onPlace = {
                    haptics.perform(HapticEvent.Tap)
                    pick = it.copy(sensorId = sensorId)
                },
            )
            Text(
                "Tap to place $sensorName",
                style = MaterialTheme.typography.labelMedium,
                color = cs.secondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            OutlinedButton(onClick = { haptics.perform(HapticEvent.Reject); onCancel() }) { Text("Cancel") }
            Button(
                onClick = { pick?.let { haptics.perform(HapticEvent.Commit); onSave(it) } },
                enabled = pick != null,
            ) { Text("Save") }
        }
    }
}
