package com.t1dm.feature.twin

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.t1dm.core.model.TwinScan
import com.t1dm.core.model.TwinTrait
import java.util.Locale

private val PANEL_TRAITS = listOf(TwinTrait.HEIGHT, TwinTrait.AGE, TwinTrait.SEX, TwinTrait.WEIGHT)

internal class PanelAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val target: TutorialTarget? = null,
)

/** Traits, right of the body. */
@Composable
internal fun TwinPanel(scan: TwinScan, modifier: Modifier = Modifier) {
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
                val valueStyle = MaterialTheme.typography.labelLarge.copy(color = cs.primary)
                PANEL_TRAITS.forEach { trait ->
                    Text(
                        formatTrait(trait, scan[trait]),
                        style = valueStyle,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    )
                }
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

/** Twin actions, above the nav bar; scrolls sideways when they overflow. */
@Composable
internal fun TwinToolbar(actions: List<PanelAction>, tut: TutorialState, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(4.dp)
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEachIndexed { i, a ->
            Box(
                Modifier
                    .then(a.target?.let { Modifier.tutorialTarget(tut, it) } ?: Modifier)
                    .heightIn(min = 40.dp)
                    .clip(shape)
                    .border(1.dp, cs.primary.copy(alpha = 0.55f), shape)
                    .clickable(enabled = a.enabled, onClick = a.onClick)
                    .alpha(if (a.enabled) 1f else 0.38f)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    a.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (i == 0) cs.secondary else cs.onSurface,
                )
            }
        }
    }
}

/** Panel centre as a fraction of view height: level with the chest at the default framing. */
private const val PANEL_CENTRE_Y = 0.42f

/** Clears the source tag row above. */
private val PANEL_MIN_TOP = 48.dp
private val PANEL_EDGE = 12.dp

private fun formatTrait(t: TwinTrait, v: Double?): String = when {
    v == null -> "-"
    t == TwinTrait.SEX -> "%.2f P(male)".format(Locale.ROOT, v)
    else -> "%.1f %s".format(Locale.ROOT, v, t.unit)
}
