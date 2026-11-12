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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
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

/** Anchor [x], [y]: fractions of height off centre and feet. [slot]: label y / view height. */
internal enum class Callout(
    val label: String,
    val trait: TwinTrait,
    val left: Boolean,
    val x: Float,
    val y: Float,
    val slot: Float,
) {
    HEIGHT("HEIGHT", TwinTrait.HEIGHT, true, 0f, 1f, 0.16f),
    AGE("AGE", TwinTrait.AGE, false, 0f, 0.95f, 0.32f),
    SEX("SEX", TwinTrait.SEX, true, 0f, 0.77f, 0.5f),
    WEIGHT("WEIGHT", TwinTrait.WEIGHT, false, 0.02f, 0.6f, 0.66f),
}

/** Per [Callout], the front-most neutral vertex near its target; the crown takes the topmost. */
internal fun pickAnchors(body: TwinBody): IntArray {
    val p = body.neutral
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    var top = 0
    for (v in 0 until body.vertexCount) {
        val y = p[v * 3 + 1]
        if (y < minY) minY = y
        if (y > maxY) {
            maxY = y
            top = v
        }
    }
    val h = maxY - minY
    return IntArray(Callout.entries.size) { i ->
        val c = Callout.entries[i]
        if (c == Callout.HEIGHT) return@IntArray top
        val tx = c.x * h
        val ty = minY + c.y * h
        val r2 = (0.035f * h) * (0.035f * h)
        var best = -1
        var bestZ = -Float.MAX_VALUE
        var nearest = 0
        var nearestD = Float.MAX_VALUE
        for (v in 0 until body.vertexCount) {
            val dx = p[v * 3] - tx
            val dy = p[v * 3 + 1] - ty
            val d = dx * dx + dy * dy
            if (d < nearestD) {
                nearestD = d
                nearest = v
            }
            if (d < r2 && p[v * 3 + 2] > bestZ) {
                bestZ = p[v * 3 + 2]
                best = v
            }
        }
        if (best >= 0) best else nearest
    }
}

/** Callouts follow their anchors every frame; values count up from the previous scan. */
@Composable
internal fun TwinCallouts(renderer: TwinRenderer, scan: TwinScan?, modifier: Modifier = Modifier) {
    if (scan == null) return
    val animate = LocalAnimationsEnabled.current
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { frame.longValue = it } }
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
        val a = renderer.anchorScreen
        if (a.size < Callout.entries.size * 3) return@Canvas
        val t = (System.nanoTime() - shownAt) / 1e9f
        for ((i, c) in Callout.entries.withIndex()) {
            val p = if (animate) ((t - 1.0f - i * 0.12f) / 0.6f).coerceIn(0f, 1f) else 1f
            if (p <= 0f) continue
            val e = 1f - (1f - p) * (1f - p)
            drawCallout(
                measurer, style, c.label, valueText(c, scan, from, e),
                anchor = Offset(a[i * 3], a[i * 3 + 1]),
                front = a[i * 3 + 2] > 0.5f,
                labelY = c.slot * size.height,
                left = c.left,
                progress = e,
                pulse = if (animate) (t * 0.6f + i * 0.17f) % 1f else 0f,
            )
        }
    }
}

private class CalloutStyle(val line: Color, val accent: Color, val panel: Color, val name: TextStyle, val value: TextStyle)

private fun DrawScope.drawCallout(
    measurer: TextMeasurer,
    s: CalloutStyle,
    name: String,
    value: String,
    anchor: Offset,
    front: Boolean,
    labelY: Float,
    left: Boolean,
    progress: Float,
    pulse: Float,
) {
    val fade = if (front) 1f else 0.35f
    val nameL = measurer.measure(name, s.name)
    val valueL = measurer.measure(value, s.value)
    val pad = 12.dp.toPx()
    val inner = 6.dp.toPx()
    val w = maxOf(nameL.size.width, valueL.size.width) + inner * 2
    val h = nameL.size.height + valueL.size.height + inner * 1.5f
    val boxX = if (left) pad else size.width - pad - w
    val boxY = labelY - h / 2
    val joinX = if (left) boxX + w else boxX
    val elbowX = joinX + if (left) 14.dp.toPx() else -14.dp.toPx()

    val path = Path().apply {
        moveTo(anchor.x, anchor.y)
        lineTo(elbowX, labelY)
        lineTo(joinX, labelY)
    }
    val measure = PathMeasure().apply { setPath(path, false) }
    val partial = Path()
    measure.getSegment(0f, measure.length * progress, partial, true)
    drawPath(partial, s.line.copy(alpha = 0.7f * fade), style = Stroke(1.dp.toPx()))

    val r = 3.dp.toPx()
    drawCircle(s.line.copy(alpha = fade), r, anchor)
    drawCircle(
        s.line.copy(alpha = (1f - pulse) * 0.6f * fade), r + pulse * 9.dp.toPx(), anchor,
        style = Stroke(1.dp.toPx()),
    )

    if (progress < 0.6f) return
    val boxA = (progress - 0.6f) / 0.4f * fade
    val corner = CornerRadius(4.dp.toPx())
    drawRoundRect(s.panel.copy(alpha = 0.9f * boxA), Offset(boxX, boxY), Size(w, h), corner)
    drawRoundRect(s.line.copy(alpha = 0.55f * boxA), Offset(boxX, boxY), Size(w, h), corner, style = Stroke(1.dp.toPx()))
    val tickX = if (left) boxX else boxX + w - 10.dp.toPx()
    drawLine(s.accent.copy(alpha = boxA), Offset(tickX, boxY), Offset(tickX + 10.dp.toPx(), boxY), 2.dp.toPx())
    val textX = boxX + inner
    drawText(nameL, topLeft = Offset(textX, boxY + inner * 0.75f), alpha = boxA)
    drawText(valueL, topLeft = Offset(textX, boxY + inner * 0.75f + nameL.size.height), alpha = boxA)
}

private fun valueText(c: Callout, to: TwinScan, from: TwinScan?, e: Float): String {
    fun v(t: TwinTrait): Double? {
        val end = to[t] ?: return null
        val start = from?.get(t) ?: end
        return start + (end - start) * e
    }
    return v(c.trait)?.let { formatTrait(c.trait, it) } ?: "—"
}

private fun formatTrait(t: TwinTrait, v: Double): String = when (t) {
    TwinTrait.SEX -> "%.2f P(male)".format(Locale.ROOT, v)
    else -> "%.1f %s".format(Locale.ROOT, v, t.unit)
}
