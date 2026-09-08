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

/** One-shot request to reveal a knob. Ambient, not a route arg: that replays the pulse on Back. */
@Stable
class SettingsFocusController {
    internal var pending by mutableStateOf<String?>(null)
        private set

    /** [anchorId] is a [SettingsKnob.id]; null clears. */
    fun request(anchorId: String?) {
        pending = anchorId
    }
}

val LocalSettingsFocus = staticCompositionLocalOf { SettingsFocusController() }

/** Only the hunted row reports position (fires every frame). ROOT-space, not positionInParent. */
@Stable
internal class SettingsAnchorRegistry {
    var wanted by mutableStateOf<String?>(null)
    var wantedRootY by mutableStateOf<Int?>(null)
    var originY by mutableStateOf(0)
    var focused by mutableStateOf<String?>(null)
}

internal val LocalSettingsAnchors = staticCompositionLocalOf<SettingsAnchorRegistry?> { null }

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

/** Anchors a bespoke knob; re-states the scaffold's own 12 dp rhythm. */
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
