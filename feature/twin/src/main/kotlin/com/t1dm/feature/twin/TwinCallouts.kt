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

internal enum class Callout(val label: String, val trait: TwinTrait) {
    HEIGHT("HEIGHT", TwinTrait.HEIGHT),
    AGE("AGE", TwinTrait.AGE),
    SEX("SEX", TwinTrait.SEX),
    WEIGHT("WEIGHT", TwinTrait.WEIGHT),
}

/** One trait panel right of the body; values count up from the previous scan. */
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
        fun eased(delay: Float): Float {
            val p = if (animate) ((t - 1.0f - delay) / 0.6f).coerceIn(0f, 1f) else 1f
            return 1f - (1f - p) * (1f - p)
        }
        val rows = Callout.entries.mapIndexed { i, c ->
            val e = eased(i * 0.12f)
            TraitRow(c.label, valueText(c, scan, from, e), valueText(c, scan, from, 1f), e)
        }
        drawPanel(measurer, style, rows, eased(0f))
    }
}

/** [widest]: the settled value, so the panel never resizes while a value counts up. */
private class TraitRow(val name: String, val value: String, val widest: String, val progress: Float)

private const val SETTLE_NS = 3_000_000_000L

private class CalloutStyle(val line: Color, val accent: Color, val panel: Color, val name: TextStyle, val value: TextStyle)

/** Name left, value right-aligned, one row per trait; centred on the right edge. */
private fun DrawScope.drawPanel(measurer: TextMeasurer, s: CalloutStyle, rows: List<TraitRow>, progress: Float) {
    if (progress <= 0f) return
    val pad = 12.dp.toPx()
    val inner = 8.dp.toPx()
    val gap = 12.dp.toPx()
    val rowGap = 4.dp.toPx()
    val names = rows.map { measurer.measure(it.name, s.name) }
    val values = rows.map { measurer.measure(it.value, s.value) }
    val valueW = rows.maxOf { measurer.measure(it.widest, s.value).size.width }
    val nameW = names.maxOf { it.size.width }
    val rowH = rows.indices.map { maxOf(names[it].size.height, values[it].size.height) }
    val w = inner * 2 + nameW + gap + valueW
    val h = inner * 2 + rowH.sum() + rowGap * (rows.size - 1)
    val slide = (1f - progress) * 16.dp.toPx()
    val boxX = size.width - pad - w + slide
    val boxY = size.height * PANEL_CENTRE_Y - h / 2
    val corner = CornerRadius(4.dp.toPx())
    drawRoundRect(s.panel.copy(alpha = 0.9f * progress), Offset(boxX, boxY), Size(w, h), corner)
    drawRoundRect(s.line.copy(alpha = 0.55f * progress), Offset(boxX, boxY), Size(w, h), corner, style = Stroke(1.dp.toPx()))
    val tickX = boxX + w - 10.dp.toPx()
    drawLine(s.accent.copy(alpha = progress), Offset(tickX, boxY), Offset(tickX + 10.dp.toPx(), boxY), 2.dp.toPx())
    var y = boxY + inner
    for (i in rows.indices) {
        val a = rows[i].progress
        drawText(names[i], topLeft = Offset(boxX + inner, y + (rowH[i] - names[i].size.height) / 2), alpha = a)
        val vx = boxX + w - inner - values[i].size.width
        drawText(values[i], topLeft = Offset(vx, y + (rowH[i] - values[i].size.height) / 2), alpha = a)
        y += rowH[i] + rowGap
    }
}

/** Panel centre as a fraction of view height: level with the chest at the default framing. */
private const val PANEL_CENTRE_Y = 0.42f

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
