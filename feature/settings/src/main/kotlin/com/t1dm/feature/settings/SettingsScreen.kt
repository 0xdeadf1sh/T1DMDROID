package com.t1dm.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The Settings root (PLAN.private.md Phase 1 — the CGM sub-screen is the only live entry now;
 * Server/Watch/units/theme arrive in later phases). Rows are plain until their phase lands.
 */
@Composable
fun SettingsScreen(
    onOpenCgm: () -> Unit = {},
    onOpenServer: () -> Unit = {},
    onOpenWarmup: () -> Unit = {},
    onOpenWatch: () -> Unit = {},
    onOpenGraph: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        SettingRow("CGM", enabled = true, onClick = onOpenCgm)
        SettingRow("Server", enabled = true, onClick = onOpenServer)
        SettingRow("Warmup", enabled = true, onClick = onOpenWarmup)
        SettingRow("Watch", enabled = true, onClick = onOpenWatch)
        SettingRow("BG graph range", enabled = true, onClick = onOpenGraph)
        SettingRow("Units / theme / alerts", enabled = false)
    }
}

@Composable
private fun SettingRow(label: String, enabled: Boolean, onClick: () -> Unit = {}) {
    val modifier = Modifier
        .fillMaxWidth()
        .let { if (enabled) it.clickable(onClick = onClick) else it }
        .padding(vertical = 14.dp)
    Text(
        text = if (enabled) label else "$label (later)",
        modifier = modifier,
        style = MaterialTheme.typography.bodyLarge,
    )
}
