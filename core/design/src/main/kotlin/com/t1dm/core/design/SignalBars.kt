package com.t1dm.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * The shared BLE signal-strength indicator (item 20, reused by I10): a four-bar RSSI meter plus the
 * raw dBm. It lives in `:core:design` so both the BG-panel header and the Settings → CGM source
 * screen render the SAME indicator rather than two drifting copies. Buckets follow the usual BLE
 * bands; colours are drawn from the active theme so it tracks each palette.
 */
@Composable
fun SignalBars(rssi: Int) {
    val filled = when {
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        rssi >= -90 -> 1
        else -> 0
    }
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        // The bars sit a touch high so their centres line up with the adjacent text baseline while the
        // ascending-bar bottoms stay aligned.
        Row(
            Modifier.offset(y = (-2).dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            for (i in 1..4) {
                androidx.compose.foundation.layout.Box(
                    Modifier.width(3.dp).height((3 + i * 2).dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (i <= filled) on else off),
                )
            }
        }
        Text(" ${rssi}dBm", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}
