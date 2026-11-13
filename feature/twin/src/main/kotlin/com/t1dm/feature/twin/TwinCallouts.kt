package com.t1dm.feature.twin

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.LocalAnimationsEnabled
import com.t1dm.core.model.TwinScan
import com.t1dm.core.model.TwinTrait
import java.util.Locale

/** [slot]: label centre y as a fraction of view height. */
internal enum class Callout(val label: String, val trait: TwinTrait, val slot: Float) {
    HEIGHT("HEIGHT", TwinTrait.HEIGHT, 0.2f),
    AGE("AGE", TwinTrait.AGE, 0.36f),
    SEX("SEX", TwinTrait.SEX, 0.52f),
    WEIGHT("WEIGHT", TwinTrait.WEIGHT, 0.68f),
}

/** Trait panels in a column right of the body; values count up from the previous scan. */
@Composable
internal fun TwinCallouts(scan: TwinScan?, modifier: Modifier = Modifier) {
    if (scan == null) return
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
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val cs = MaterialTheme.colorScheme
    val style = CalloutStyle(
        line = cs.primary,
        accent = cs.secondary,
        panel = cs.surface,
        name = MaterialTheme.typography.labelSmall.copy(color = cs.onSurfaceVariant),
        value = MaterialTheme.typography.labelLarge.copy(color = cs.primary),
    )

    Canvas(modifier) {
        frame.longValue
        val t = (System.nanoTime() - shownAt) / 1e9f
        for ((i, c) in Callout.entries.withIndex()) {
            val p = if (animate) ((t - 1.0f - i * 0.12f) / 0.6f).coerceIn(0f, 1f) else 1f
            if (p <= 0f) continue
            val e = 1f - (1f - p) * (1f - p)
            drawCallout(measurer, style, c.label, valueText(c, scan, from, e), c.slot * size.height, e)
        }
    }
}

private const val SETTLE_NS = 3_000_000_000L

private class CalloutStyle(val line: Color, val accent: Color, val panel: Color, val name: TextStyle, val value: TextStyle)

private fun DrawScope.drawCallout(
    measurer: TextMeasurer,
    s: CalloutStyle,
    name: String,
    value: String,
    labelY: Float,
    progress: Float,
) {
    val nameL = measurer.measure(name, s.name)
    val valueL = measurer.measure(value, s.value)
    val pad = 12.dp.toPx()
    val inner = 6.dp.toPx()
    val w = maxOf(nameL.size.width, valueL.size.width) + inner * 2
    val h = nameL.size.height + valueL.size.height + inner * 1.5f
    val slide = (1f - progress) * 16.dp.toPx()
    val boxX = size.width - pad - w + slide
    val boxY = labelY - h / 2
    val corner = CornerRadius(4.dp.toPx())
    drawRoundRect(s.panel.copy(alpha = 0.9f * progress), Offset(boxX, boxY), Size(w, h), corner)
    drawRoundRect(s.line.copy(alpha = 0.55f * progress), Offset(boxX, boxY), Size(w, h), corner, style = Stroke(1.dp.toPx()))
    val tickX = boxX + w - 10.dp.toPx()
    drawLine(s.accent.copy(alpha = progress), Offset(tickX, boxY), Offset(tickX + 10.dp.toPx(), boxY), 2.dp.toPx())
    val textX = boxX + inner
    drawText(nameL, topLeft = Offset(textX, boxY + inner * 0.75f), alpha = progress)
    drawText(valueL, topLeft = Offset(textX, boxY + inner * 0.75f + nameL.size.height), alpha = progress)
}

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
