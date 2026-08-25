package com.t1dm.core.design

import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * The content colour is PINNED, not inferred: `contentColorFor(surfaceVariant)` would hand every panel
 * `inkMuted` instead of `ink`. Contrast is then guaranteed by [legibleInkOver], so every in-panel line
 * must read `LocalContentColor.current`; a raw `colorScheme.onSurface` bypasses the guarantee.
 */
@Composable
fun panelCardColors(): CardColors {
    val cs = MaterialTheme.colorScheme
    val container = cs.surfaceVariant
    // Measured against the panel AS PAINTED: either role may carry alpha in an imported theme, and a
    // ratio taken on the raw roles describes a panel that does not exist.
    val ink = legibleInkOver(cs.background, container, cs.onSurface)
    return CardDefaults.cardColors(
        containerColor = container,
        contentColor = ink,
        // Pinned too, or a disabled clickable card would drop back to the very grey this replaces.
        disabledContainerColor = container,
        disabledContentColor = ink.copy(alpha = 0.38f),
    )
}
