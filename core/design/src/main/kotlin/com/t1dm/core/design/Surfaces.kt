package com.t1dm.core.design

import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/** Content colour PINNED, not inferred; contrast then guaranteed by [legibleInkOver]. */
@Composable
fun panelCardColors(): CardColors {
    val cs = MaterialTheme.colorScheme
    val container = cs.surfaceVariant
    // Measured against the panel AS PAINTED: raw roles may not describe the painted panel.
    val ink = legibleInkOver(cs.background, container, cs.onSurface)
    return CardDefaults.cardColors(
        containerColor = container,
        contentColor = ink,
        // Pinned too, or a disabled clickable card would drop back to the very grey this replaces.
        disabledContainerColor = container,
        disabledContentColor = ink.copy(alpha = 0.38f),
    )
}
