package com.t1dm.feature.twin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.KeyValueRow
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.model.TwinScan
import com.t1dm.core.model.TwinSource
import com.t1dm.core.model.TwinTrait
import java.util.Locale

/** [latest] null: no scan yet, and the twin stands at MakeHuman's neutral. */
@Composable
fun TwinScreen(latest: TwinScan?, scanCount: Int, onMockScan: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberT1dmHaptics()
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(480.dp)) {
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
        Button(onClick = { haptics.perform(HapticEvent.Tap); onMockScan() }) { Text("Mock scan") }
        if (latest != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                for (t in TwinTrait.entries) KeyValueRow(t.label, latest[t]?.let { format(t, it) }, numeric = true)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun format(t: TwinTrait, v: Double): String = when (t) {
    TwinTrait.SEX, TwinTrait.MUSCLE -> "%.2f".format(Locale.ROOT, v)
    TwinTrait.SURVIVAL_1Y, TwinTrait.SURVIVAL_5Y, TwinTrait.SURVIVAL_10Y, TwinTrait.SURVIVAL_20Y ->
        "%.1f %%".format(Locale.ROOT, v * 100)
    else -> "%.1f %s".format(Locale.ROOT, v, t.unit)
}
