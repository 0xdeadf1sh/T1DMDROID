package com.t1dm.core.design

import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * The ONE definition of what a themed panel is made of.
 *
 * A bare `Card` takes `CardDefaults.cardColors()`, whose container is Material's own
 * `FilledCardTokens.ContainerColor` = `surfaceContainerHighest` — a role [T1dmPalette.toColorScheme]
 * does not set. It therefore falls through to the Material baseline (`Neutral22` = #36343B on a dark
 * scheme, `Neutral90` = #E6E0E9 on a light one): one neutral-purple grey, identical under every
 * palette, carrying themed ink. That is the grey.
 *
 * The container is the `surfaceVariant` role, which every palette defines for itself and which the
 * Stats, CGM and Security panels already used by hand before this existed.
 *
 * The **content** colour is pinned rather than inferred, and that is the load-bearing part.
 * `CardDefaults.cardColors` derives content from `contentColorFor(container)`, a `when` over colour
 * *equality* whose `surfaceVariant → onSurfaceVariant` arm is tested before its
 * `surfaceContainerHighest → onSurface` arm. Letting it infer would hand every panel `inkMuted`
 * instead of `ink` — dimmer than today on the very text this fix is about, and dimmer than the
 * secondary lines the Logs, Stats and Insulin panels derive from it, which would invert their own
 * hierarchy. Pinning also removes the inference entirely: nothing here depends on two palette roles
 * happening to differ.
 *
 * Contrast is then guaranteed rather than assumed — see [legibleInkOver]. `ink` is used while it
 * clears WCAG AA against the panel, and an imported palette that puts the two too close falls back to
 * the one ink with an unconditional floor.
 *
 * **The guarantee extends to the panel's derived inks, and only because they are derived.** A
 * secondary line reading `MaterialTheme.colorScheme.onSurface.copy(alpha = …)` computes from the RAW
 * palette role and bypasses the check entirely, so where the fallback fires such a line keeps exactly
 * the colour the fallback rejected — a panel with guarded primary text over unguarded secondary text,
 * the two at opposite polarities. Every in-panel line therefore reads `LocalContentColor.current`,
 * which is the value pinned below.
 */
@Composable
fun panelCardColors(): CardColors {
    val cs = MaterialTheme.colorScheme
    val container = cs.surfaceVariant
    // Measured against the panel AS PAINTED. `surfaceVariant` and `onSurface` are both free roles in
    // an imported theme and either may carry alpha; what `Surface` then draws is the composite over
    // the page, and a ratio taken on the raw roles describes a panel that does not exist.
    val ink = legibleInkOver(cs.background, container, cs.onSurface)
    return CardDefaults.cardColors(
        containerColor = container,
        contentColor = ink,
        // Pinned too, or a disabled clickable card would drop back to the very grey this replaces.
        disabledContainerColor = container,
        disabledContentColor = ink.copy(alpha = 0.38f),
    )
}
