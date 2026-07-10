package com.t1dm.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Settings → CGM sub-screen stub (Phase 1 — "exposes the active-source pick even
 * though auto-active"). Read-only for now: it surfaces the auto-adopted active source and every
 * recorded source. Manual re-selection UI (the exactly-one-active pick) lands with the multi-source
 * work; the seam (`CgmSourceRegistry.setActive`) already exists.
 */
@Composable
fun CgmSettingsScreen(
    activeSourceName: String?,
    activeStatus: String?,
    allSourceNames: List<String>,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("CGM", style = MaterialTheme.typography.headlineSmall)
        Text("Active source", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 16.dp))
        Text(
            text = activeSourceName?.let { "$it${activeStatus?.let { s -> " • $s" } ?: ""}" }
                ?: "none yet — scanning",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text("Recorded sources", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 24.dp))
        if (allSourceNames.isEmpty()) {
            Text("none", style = MaterialTheme.typography.bodyMedium)
        } else {
            allSourceNames.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp)) }
        }
    }
}
