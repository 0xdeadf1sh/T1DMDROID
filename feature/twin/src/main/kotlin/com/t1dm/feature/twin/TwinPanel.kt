package com.t1dm.feature.twin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.t1dm.core.model.TwinScan
import com.t1dm.core.model.TwinTrait
import java.util.Locale

internal class PanelAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val target: TutorialTarget? = null,
)

/** Editable traits, above the body; a tap opens the editor on that trait, a second closes it. */
@Composable
internal fun TwinTraitBar(
    scan: TwinScan,
    selected: TwinTrait?,
    edited: Set<String>,
    onPick: (TwinTrait) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    ChipRow(modifier) {
        EDITABLE.forEach { t ->
            val on = t == selected
            val label = formatTrait(t, scan[t]) + if (t.key in edited) " *" else ""
            Chip(label, if (on) cs.secondary else cs.primary, { onPick(t) }, enabled, selected = on)
        }
    }
}

/** Twin actions, above the nav bar; each takes an equal share of the width. */
@Composable
internal fun TwinToolbar(actions: List<PanelAction>, tut: TutorialState, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    ChipRow(modifier, scroll = false) {
        actions.forEachIndexed { i, a ->
            Chip(
                a.label,
                if (i == 0) cs.secondary else cs.onSurface,
                a.onClick,
                a.enabled,
                Modifier.weight(1f).then(a.target?.let { Modifier.tutorialTarget(tut, it) } ?: Modifier),
            )
        }
    }
}

@Composable
private fun ChipRow(modifier: Modifier, scroll: Boolean = true, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun Chip(
    label: String,
    color: Color,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .then(if (selected) Modifier.background(cs.secondary.copy(alpha = 0.18f)) else Modifier)
            .border(1.dp, if (selected) cs.secondary else cs.primary.copy(alpha = 0.55f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

private fun formatTrait(t: TwinTrait, v: Double?): String = when {
    v == null -> "-"
    t == TwinTrait.SEX -> "%.2f P(male)".format(Locale.ROOT, v)
    t == TwinTrait.MUSCLE -> "%.2f muscle".format(Locale.ROOT, v)
    else -> "%.1f %s".format(Locale.ROOT, v, t.unit)
}
