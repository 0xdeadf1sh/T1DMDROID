package com.t1dm.feature.twin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.model.TwinScan
import com.t1dm.core.model.TwinSource
import java.util.Locale

/** [latest] null: no scan yet, and the twin stands at MakeHuman's neutral. */
@Composable
fun TwinScreen(
    latest: TwinScan?,
    scanCount: Int,
    onScan: () -> Unit,
    onMockScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberT1dmHaptics()
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            TwinView(latest, Modifier.fillMaxSize())
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val tag = when {
                    latest == null -> "NO SCAN"
                    latest.source == TwinSource.MOCK -> "MOCK"
                    else -> latest.source.uppercase(Locale.ROOT)
                }
                Text(tag, style = MaterialTheme.typography.labelMedium, color = cs.secondary)
                Text("$scanCount scans", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }
        }
        Row(
            Modifier.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { haptics.perform(HapticEvent.Tap); onScan() }) { Text("Scan face") }
            TextButton(onClick = { haptics.perform(HapticEvent.Tap); onMockScan() }) { Text("Mock") }
        }
    }
}
