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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    overrides: Map<String, Double>,
    showTutorial: Boolean,
    onScan: () -> Unit,
    onMockScan: () -> Unit,
    onTrends: () -> Unit,
    onOverride: (key: String, value: Double?) -> Unit,
    onResetOverrides: () -> Unit,
    onTutorialDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberT1dmHaptics()
    val cs = MaterialTheme.colorScheme
    val draft = remember { mutableStateMapOf<String, Double>() }
    var editing by remember { mutableStateOf(false) }
    var tutorial by remember(showTutorial) { mutableStateOf(showTutorial) }
    // A draft yields only once the stored edit matches it, so the body never snaps back mid-write.
    LaunchedEffect(overrides) {
        draft.keys.toList().forEach { k -> if (overrides[k] == draft[k]) draft.remove(k) }
    }
    val effective = effectiveScan(latest, overrides, draft)

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                TwinView(effective, Modifier.fillMaxSize())
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val tag = when {
                        latest == null -> "NO SCAN"
                        latest.source == TwinSource.MOCK -> "MOCK"
                        else -> latest.source.uppercase(Locale.ROOT)
                    }
                    Text(
                        if (overrides.isEmpty()) tag else "$tag · EDITED",
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.secondary,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$scanCount scans", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        TextButton(onClick = { tutorial = true }) { Text("?") }
                    }
                }
            }
            if (editing && effective != null) {
                BackHandler { editing = false }
                TwinEditPanel(
                    effective = effective,
                    edited = overrides.keys + draft.keys,
                    onDrag = { t, v -> draft[t.key] = v },
                    onCommit = { t -> draft[t.key]?.let { onOverride(t.key, it) } },
                    onReset = {
                        draft.clear()
                        onResetOverrides()
                    },
                    onDone = { editing = false },
                )
            } else {
                Row(
                    Modifier.padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = { haptics.perform(HapticEvent.Tap); onScan() }) { Text("Scan face") }
                    TextButton(onClick = { editing = true }, enabled = latest != null) { Text("Edit") }
                    TextButton(onClick = onTrends, enabled = scanCount > 0) { Text("Trends") }
                    TextButton(onClick = { haptics.perform(HapticEvent.Tap); onMockScan() }) { Text("Mock") }
                }
            }
        }
        if (tutorial) {
            TwinTutorial(onDone = {
                tutorial = false
                onTutorialDone()
            })
        }
    }
}
