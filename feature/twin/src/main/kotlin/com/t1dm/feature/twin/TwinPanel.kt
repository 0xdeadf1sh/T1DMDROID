package com.t1dm.feature.twin

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.LocalAnimationsEnabled
import com.t1dm.core.design.LocalT1dmSemantics
import com.t1dm.core.design.TwinAction
import com.t1dm.core.design.iconStyleForTheme
import com.t1dm.core.design.twinActionIcon
import com.t1dm.core.model.TwinScan
import com.t1dm.core.model.TwinTrait
import java.util.Locale

internal enum class Callout(val label: String, val trait: TwinTrait) {
    HEIGHT("HEIGHT", TwinTrait.HEIGHT),
    AGE("AGE", TwinTrait.AGE),
    SEX("SEX", TwinTrait.SEX),
    WEIGHT("WEIGHT", TwinTrait.WEIGHT),
}

internal class PanelAction(
    val action: TwinAction,
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val target: TutorialTarget? = null,
)

/** Traits over actions, right of the body; [scan] null shows the actions alone. */
@Composable
internal fun TwinPanel(
    scan: TwinScan?,
    actions: List<PanelAction>,
    tut: TutorialState,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val tick = cs.secondary
    Layout(
        content = {
            Column(
                Modifier
                    .width(IntrinsicSize.Max)
                    .drawBehind { drawTick(tick) }
                    .border(1.dp, cs.primary.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(8.dp),
            ) {
                if (scan != null) {
                    TraitRows(scan)
                    Box(
                        Modifier
                            .padding(vertical = 6.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .drawBehind { drawRect(cs.primary.copy(alpha = 0.3f)) },
                    )
                }
                actions.forEach { ActionRow(it, tut) }
            }
        },
        modifier = modifier,
    ) { measurables, c ->
        val panel = measurables.single().measure(c.copy(minWidth = 0, minHeight = 0))
        layout(c.maxWidth, c.maxHeight) {
            val top = PANEL_MIN_TOP.roundToPx()
            val y = (c.maxHeight * PANEL_CENTRE_Y - panel.height / 2f).toInt()
                .coerceIn(top, maxOf(top, c.maxHeight - panel.height))
            panel.place(c.maxWidth - PANEL_EDGE.roundToPx() - panel.width, y)
        }
    }
}

private fun DrawScope.drawTick(color: Color) {
    val w = 10.dp.toPx()
    drawLine(color, Offset(size.width - w, 0f), Offset(size.width, 0f), 2.dp.toPx())
}

/** Values count up from the previous scan. */
@Composable
private fun TraitRows(scan: TwinScan) {
    val animate = LocalAnimationsEnabled.current
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(scan.id, animate) {
        if (!animate) return@LaunchedEffect
        val start = System.nanoTime()
        while (System.nanoTime() - start < SETTLE_NS) withFrameNanos { frame.longValue = it }
    }
    var lastScan by remember { mutableStateOf<TwinScan?>(null) }
    val from = remember(scan.id) { lastScan }
    val shownAt = remember(scan.id) { System.nanoTime() }
    SideEffect { lastScan = scan }
    val cs = MaterialTheme.colorScheme
    val nameStyle = MaterialTheme.typography.labelSmall.copy(color = cs.onSurfaceVariant)
    val valueStyle = MaterialTheme.typography.labelLarge.copy(color = cs.primary)

    frame.longValue
    val t = (System.nanoTime() - shownAt) / 1e9f
    Callout.entries.forEachIndexed { i, c ->
        val p = if (animate) ((t - 1.0f - i * 0.12f) / 0.6f).coerceIn(0f, 1f) else 1f
        val e = 1f - (1f - p) * (1f - p)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp).alpha(e),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(c.label, style = nameStyle)
            Spacer(Modifier.width(12.dp))
            // The settled value sizes the slot, so the panel never resizes while a value counts up.
            Box(contentAlignment = Alignment.CenterEnd) {
                Text(valueText(c, scan, from, 1f), style = valueStyle, modifier = Modifier.alpha(0f))
                Text(valueText(c, scan, from, e), style = valueStyle)
            }
        }
    }
}

@Composable
private fun ActionRow(a: PanelAction, tut: TutorialState) {
    val cs = MaterialTheme.colorScheme
    val style = iconStyleForTheme(LocalT1dmSemantics.current.id)
    Row(
        Modifier
            .fillMaxWidth()
            .then(a.target?.let { Modifier.tutorialTarget(tut, it) } ?: Modifier)
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(enabled = a.enabled, onClick = a.onClick)
            .alpha(if (a.enabled) 1f else 0.38f)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            twinActionIcon(a.action, style),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (a.action == TwinAction.SCAN) cs.secondary else cs.primary,
        )
        Text(a.label, style = MaterialTheme.typography.labelLarge, color = cs.onSurface)
    }
}

private const val SETTLE_NS = 3_000_000_000L

/** Panel centre as a fraction of view height: level with the chest at the default framing. */
private const val PANEL_CENTRE_Y = 0.42f

/** Clears the source tag row above. */
private val PANEL_MIN_TOP = 48.dp
private val PANEL_EDGE = 12.dp

private fun valueText(c: Callout, to: TwinScan, from: TwinScan?, e: Float): String {
    fun v(t: TwinTrait): Double? {
        val end = to[t] ?: return null
        val start = from?.get(t) ?: end
        return start + (end - start) * e
    }
    return v(c.trait)?.let { formatTrait(c.trait, it) } ?: "-"
}

private fun formatTrait(t: TwinTrait, v: Double): String = when (t) {
    TwinTrait.SEX -> "%.2f P(male)".format(Locale.ROOT, v)
    else -> "%.1f %s".format(Locale.ROOT, v, t.unit)
}
