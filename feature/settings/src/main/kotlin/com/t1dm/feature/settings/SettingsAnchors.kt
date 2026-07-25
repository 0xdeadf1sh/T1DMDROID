package com.t1dm.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.pulseHighlight

/**
 * How a search result reaches the knob it matched: the row is scrolled into view and pulsed once.
 *
 * The request is NOT carried on the route. `crumbsFor` matches route literals exactly, so registering
 * `settings/display?focus={focus}` would make `destination.route` return the pattern, drop the
 * breadcrumb through to its `else` branch, and print the raw pattern to the user; and a route argument
 * replays the pulse on every Back-navigation into the screen, which is not what "one-shot" means.
 *
 * Instead `:app` hoists a [SettingsFocusController] above the NavHost and provides it here, exactly as
 * the haptics engine arrives through `LocalT1dmHaptics` — ambient, never a parameter, so not one of
 * the twenty screen signatures grows a pair of plumbing arguments and the module stays as stateless
 * and as callback-driven as it was.
 */
@Stable
class SettingsFocusController {
    internal var pending by mutableStateOf<String?>(null)
        private set

    /** Ask for [anchorId] (a [SettingsKnob.id]) to be revealed on whichever screen owns it; null clears. */
    fun request(anchorId: String?) {
        pending = anchorId
    }
}

val LocalSettingsFocus = staticCompositionLocalOf { SettingsFocusController() }

/**
 * Per-page anchor bookkeeping, owned by [SettingsScaffold] and read by [settingsAnchor].
 *
 * Only the ONE row the scaffold is currently hunting reports its position, and only for the second or
 * so the landing takes. `onGloballyPositioned` fires on every scroll frame, so an always-on map of
 * eighty anchors would write eighty snapshot records per frame for a measurement nobody is reading —
 * a scrolling cost paid forever to serve an interaction that happens rarely.
 *
 * The position is recorded in ROOT space, not parent space: `positionInParent()` is only content-space
 * for a DIRECT child of the scrolling column, and while every knob row happens to be one today, an
 * anchor inside a wrapper would silently start measuring against the wrong origin. Subtracting the
 * scaffold's own root Y and adding the live scroll offset is correct wherever the anchor sits.
 */
@Stable
internal class SettingsAnchorRegistry {
    /** The anchor whose position the scaffold is waiting on; null the rest of the time. */
    var wanted by mutableStateOf<String?>(null)
    var wantedRootY by mutableStateOf<Int?>(null)
    var originY by mutableStateOf(0)
    var focused by mutableStateOf<String?>(null)
}

internal val LocalSettingsAnchors = staticCompositionLocalOf<SettingsAnchorRegistry?> { null }

/** Report this node's position while it is the hunted anchor, and wash it while it is the focused one. */
@Composable
internal fun Modifier.settingsAnchor(anchorId: String): Modifier {
    val registry = LocalSettingsAnchors.current ?: return this
    val hunted = registry.wanted == anchorId
    return this
        .then(
            if (hunted) {
                Modifier.onGloballyPositioned { registry.wantedRootY = it.positionInRoot().y.toInt() }
            } else {
                Modifier
            },
        )
        .pulseHighlight(registry.focused == anchorId)
}

/**
 * Anchor a knob that is not one of the four shared controls — a bespoke stepper, a Bézier designer, a
 * button, a text field. The wrapper re-states the scaffold's own 12 dp rhythm so a multi-part block
 * keeps the spacing it had as a loose run of siblings.
 */
@Composable
fun SettingsAnchor(
    knob: SettingsKnob,
    modifier: Modifier = Modifier.fillMaxWidth(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.settingsAnchor(knob.id),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}
