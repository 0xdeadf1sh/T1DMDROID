package com.t1dm.feature.models

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.t1dm.core.design.LocalT1dmSemantics
import com.t1dm.core.model.BAND_COV50_TARGET
import com.t1dm.core.model.BAND_COV90_TARGET
import com.t1dm.core.model.CgEga
import com.t1dm.core.model.CgEgaRegion
import com.t1dm.core.model.HorizonMetrics
import com.t1dm.core.model.PointBlock
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow

/**
 * The Models drill-down's figures, drawn on plain [Canvas] in the app's own idiom (AgpChart, the
 * glucose graph, the circadian dial) — palette roles read from the theme, text through a remembered
 * `TextMeasurer`, no charting dependency.
 *
 * Every number rendered here is the golden-gated core's. Nothing is recomputed: the only quantities
 * assembled on this side are [clarkeShares], which re-partitions figures the core already published,
 * and the CG-EGA triple, which is already a partition. These are accuracy statements about a
 * FORECAST — advisory, never a dosing claim.
 *
 * **A figure is only ever handed horizons that passed `sufficient`.** The caller filters and states
 * why the rest are missing; nothing here draws an empty axis, and a bar whose quantity is undefined
 * is omitted rather than plotted as a zero.
 */

private val FigureHeight = 128.dp
private val RowHeight = 18.dp
private val RowGap = 9.dp
private val LabelSp = 9.sp

// ── 1. Error vs horizon ────────────────────────────────────────────────────────────────────────

/**
 * Band-projected RMSE and MAE per horizon, with the persistence baseline's RMSE as a cap over the
 * RMSE bar so the skill score is READ as the gap between them rather than taken on trust. The cap
 * sits below the bar top exactly when the model lost to persistence, which is the one reading of
 * this figure that must never be quiet.
 *
 * Persistence has no band and no MAE in the suite ([HorizonMetrics.rmsePersistPoint] is the whole
 * baseline), so only the RMSE bar carries a cap.
 */
@Composable
internal fun ErrorByHorizonFigure(hs: List<HorizonMetrics>) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(color = cs.onSurfaceVariant, fontSize = LabelSp)
    val rmseInk = cs.primary
    val maeInk = cs.secondary
    val persistInk = cs.onSurface

    Legend(
        listOf(
            LegendItem(rmseInk, "RMSE"),
            LegendItem(maeInk, "MAE"),
            LegendItem(persistInk, "persist", line = true),
        ),
        trailing = "mg/dL",
    )
    Canvas(Modifier.fillMaxWidth().height(FigureHeight)) {
        val peak = hs.flatMap { listOf(it.band.rmsePoint, it.band.maePoint, it.rmsePersistPoint) }
            .filter { it.isFinite() && it > 0.0 }
            .maxOrNull()?.toFloat() ?: return@Canvas
        val yMax = niceCeil(peak)
        val plot = plotFrame(measurer, axisStyle, listOf(fmtAxis(yMax), fmtAxis(yMax / 2f)))
        fun y(v: Float) = plot.bottom - (v / yMax) * plot.height

        for (t in listOf(0f, yMax / 2f, yMax)) {
            val gy = y(t)
            drawLine(cs.outlineVariant, Offset(plot.left, gy), Offset(plot.right, gy), 1f)
            label(measurer, fmtAxis(t), axisStyle, plot.left - 4.dp.toPx(), gy, alignEnd = true)
        }

        val (slot, bw) = groupGeometry(plot, hs.size)
        hs.forEachIndexed { i, h ->
            val cx = plot.left + slot * (i + 0.5f)
            val gap = 3.dp.toPx()
            val rmseX = cx - bw - gap / 2f
            val maeX = cx + gap / 2f
            h.band.rmsePoint.finite()?.let { bar(rmseX, bw, y(min(it, yMax)), plot.bottom, rmseInk) }
            h.band.maePoint.finite()?.let { bar(maeX, bw, y(min(it, yMax)), plot.bottom, maeInk) }
            h.rmsePersistPoint.finite()?.let { p ->
                val py = y(min(p, yMax))
                // Wider than the bar it caps, so the gap is legible, but not so wide that it
                // reaches over the MAE bar and reads as a cap on that one too.
                val half = bw * 0.62f
                drawLine(
                    persistInk,
                    Offset(rmseX + bw / 2f - half, py),
                    Offset(rmseX + bw / 2f + half, py),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            label(measurer, "${h.horizonMin}m", axisStyle, cx, plot.bottom + 3.dp.toPx(), centreX = true)
        }
    }
}

// ── 2. Calibration ─────────────────────────────────────────────────────────────────────────────

/**
 * Realized band coverage against the nominal targets of `SPEC/invariants.md` §6.2 — the figure that
 * says whether the bands are honest. The two targets are the axis: they are the only labelled
 * gridlines, drawn heavy and dashed straight across, so a bar's distance from its own line is the
 * whole reading. A band widened until it swallows every truth overshoots here while its error
 * figures look flawless, which is exactly what §6.2 requires be visible.
 */
@Composable
internal fun CalibrationFigure(hs: List<HorizonMetrics>) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(color = cs.onSurfaceVariant, fontSize = LabelSp)
    val targetStyle = TextStyle(color = cs.onSurface, fontSize = LabelSp)
    val inner = cs.primary
    val outer = cs.secondary

    Legend(
        listOf(
            LegendItem(inner, "cov50"),
            LegendItem(outer, "cov90"),
            LegendItem(cs.onSurface, "target", line = true, dashed = true),
        ),
    )
    Canvas(Modifier.fillMaxWidth().height(FigureHeight)) {
        val targets = listOf(BAND_COV50_TARGET.toFloat(), BAND_COV90_TARGET.toFloat())
        val plot = plotFrame(measurer, targetStyle, targets.map { fmtCov(it) })
        fun y(v: Float) = plot.bottom - v.coerceIn(0f, 1f) * plot.height

        drawLine(cs.outlineVariant, Offset(plot.left, plot.bottom), Offset(plot.right, plot.bottom), 1f)

        val (slot, bw) = groupGeometry(plot, hs.size)
        hs.forEachIndexed { i, h ->
            val cx = plot.left + slot * (i + 0.5f)
            val gap = 3.dp.toPx()
            h.bandCov50.finite()?.let { bar(cx - bw - gap / 2f, bw, y(it), plot.bottom, inner) }
            h.bandCov90.finite()?.let { bar(cx + gap / 2f, bw, y(it), plot.bottom, outer) }
            label(measurer, "${h.horizonMin}m", axisStyle, cx, plot.bottom + 3.dp.toPx(), centreX = true)
        }

        // Last, and over the bars: the target must never be the thing a bar hides.
        val dash = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))
        targets.forEach { t ->
            val ty = y(t)
            drawLine(cs.onSurface, Offset(plot.left, ty), Offset(plot.right, ty), 2.dp.toPx(), pathEffect = dash)
            label(measurer, fmtCov(t), targetStyle, plot.left - 4.dp.toPx(), ty, alignEnd = true)
        }
    }
}

// ── 3. CG-EGA by region ────────────────────────────────────────────────────────────────────────

/**
 * The whole-window CG-EGA of §6.3, one stacked accurate/benign/erroneous bar per glycaemic region.
 *
 * Every bar carries its own `n`. The three denominators differ by an order of magnitude — hypo and
 * hyper hold a small fraction of the points euglycaemia does — so a shape without its count invites
 * the reader to weigh a handful of points as heavily as a thousand.
 *
 * No horizon label anywhere: this statistic has none (§6.3).
 */
@Composable
internal fun CgEgaFigure(cg: CgEga) {
    val p = LocalT1dmSemantics.current
    StackedFigure(
        rows = listOf(
            cgEgaRow("hypo", cg.hypo),
            cgEgaRow("eu", cg.eu),
            cgEgaRow("hyper", cg.hyper),
        ),
        colors = listOf(p.inRange, p.low, p.urgentLow),
        legend = listOf("AP", "BE", "EP"),
    )
}

internal fun cgEgaRow(name: String, r: CgEgaRegion): StackRow = StackRow(
    label = name,
    note = "n=${r.n}",
    // A region that held no point has no triple — the core reports null rather than a zero, and an
    // empty track says the same thing without inventing a shape for it.
    shares = listOfNotNull(r.apPct?.finite(), r.bePct?.finite(), r.epPct?.finite())
        .takeIf { it.size == 3 && r.n > 0 }
        .orEmpty(),
)

// ── 4. Clarke zones ────────────────────────────────────────────────────────────────────────────

/** The band-projected Clarke Error Grid, A through E, per horizon. */
@Composable
internal fun ClarkeFigure(hs: List<HorizonMetrics>) {
    val p = LocalT1dmSemantics.current
    StackedFigure(
        rows = hs.map { StackRow("${it.horizonMin}m", "n=${it.n}", clarkeShares(it.band)) },
        // A severity ramp, not five arbitrary hues: A and B are the same in-range ink (B the paler),
        // then amber, orange, red. The zone letters carry the exact meaning; the colour carries only
        // "further from safe", which is the one thing a glance should take from a stacked bar.
        colors = listOf(p.inRange, p.inRange.copy(alpha = 0.45f), p.low, p.high, p.urgentLow),
        legend = listOf("A", "B", "C", "D", "E"),
    )
}

/**
 * The five Clarke shares, in percent, from the four the core publishes.
 *
 * `t1dm-core::accuracy::clarke_zones` assigns every pair exactly one zone, so the five partition the
 * window: B is `A∪B − A`, and C is whatever the other four leave. Reconstructing them here rather
 * than widening the uniffi record keeps one owner of the zone algebra — this only re-partitions
 * published totals, and cannot disagree with them by more than float noise.
 *
 * Returns empty where any input is non-finite: a partial partition would render as a plausible
 * shape rather than as missing data.
 */
internal fun clarkeShares(b: PointBlock): List<Float> {
    val a = b.clarkeA.finite() ?: return emptyList()
    val ab = b.clarkeAb.finite() ?: return emptyList()
    val d = b.clarkeD.finite() ?: return emptyList()
    val e = b.clarkeE.finite() ?: return emptyList()
    val zoneB = (ab - a).coerceAtLeast(0f)
    val zoneC = (100f - ab - d - e).coerceAtLeast(0f)
    return listOf(a.coerceAtLeast(0f), zoneB, zoneC, d.coerceAtLeast(0f), e.coerceAtLeast(0f))
}

// ── shared drawing ─────────────────────────────────────────────────────────────────────────────

/** One stacked row: its axis label, the count beside it, and the shares that partition it. */
internal class StackRow(val label: String, val note: String, val shares: List<Float>)

/** Stacked 100 % rows — the shape figures. Horizontal, because the row labels are words and the
 *  counts belong beside the bar they qualify rather than under it. */
@Composable
private fun StackedFigure(rows: List<StackRow>, colors: List<Color>, legend: List<String>) {
    if (rows.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = cs.onSurface, fontSize = LabelSp)
    val noteStyle = TextStyle(color = cs.onSurfaceVariant, fontSize = LabelSp)

    Legend(legend.mapIndexed { i, name -> LegendItem(colors[i], name) })
    Canvas(Modifier.fillMaxWidth().height(RowHeight * rows.size + RowGap * (rows.size - 1))) {
        val labelW = rows.maxOf { measurer.measure(it.label, labelStyle).size.width }.toFloat()
        val noteW = rows.maxOf { measurer.measure(it.note, noteStyle).size.width }.toFloat()
        val pad = 6.dp.toPx()
        val barLeft = labelW + pad
        val barW = size.width - barLeft - noteW - pad
        if (barW <= 0f) return@Canvas
        val rowH = RowHeight.toPx()
        val step = rowH + RowGap.toPx()

        rows.forEachIndexed { i, row ->
            val top = i * step
            val mid = top + rowH / 2f
            label(measurer, row.label, labelStyle, 0f, mid)
            label(measurer, row.note, noteStyle, size.width, mid, alignEnd = true)
            drawRect(cs.outlineVariant.copy(alpha = 0.4f), Offset(barLeft, top), Size(barW, rowH), style = Stroke(1f))
            val total = row.shares.sum()
            if (total <= 0f) return@forEachIndexed
            var x = barLeft
            row.shares.forEachIndexed { k, share ->
                val w = barW * (share / total)
                if (w > 0f) drawRect(colors[k], Offset(x, top), Size(w, rowH))
                x += w
            }
        }
    }
}

/** The plot rectangle, once the y labels and the x label row have taken their gutters. */
private class Plot(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

private fun DrawScope.plotFrame(
    measurer: TextMeasurer,
    style: TextStyle,
    yLabels: List<String>,
): Plot {
    val m = yLabels.map { measurer.measure(it, style) }
    val gutter = (m.maxOfOrNull { it.size.width } ?: 0).toFloat() + 6.dp.toPx()
    val lineH = (m.maxOfOrNull { it.size.height } ?: 0).toFloat()
    return Plot(gutter, lineH / 2f, size.width, size.height - lineH - 3.dp.toPx())
}

/** Slot width per group and the width of one bar within it. */
private fun DrawScope.groupGeometry(plot: Plot, groups: Int): Pair<Float, Float> {
    val slot = plot.width / groups
    return slot to min(slot * 0.3f, 20.dp.toPx())
}

private fun DrawScope.bar(x: Float, w: Float, top: Float, bottom: Float, color: Color) {
    val h = bottom - top
    if (h <= 0f || w <= 0f) return
    drawRect(color, Offset(x, top), Size(w, h))
}

private fun DrawScope.label(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    x: Float,
    y: Float,
    alignEnd: Boolean = false,
    centreX: Boolean = false,
) {
    val laid = measurer.measure(text, style)
    val left = when {
        alignEnd -> x - laid.size.width
        centreX -> x - laid.size.width / 2f
        else -> x
    }
    // `y` is the row's centre for a side label and its top for an axis label under the plot.
    val top = if (centreX) y else y - laid.size.height / 2f
    drawText(laid, topLeft = Offset(left, top))
}

private class LegendItem(
    val color: Color,
    val label: String,
    val line: Boolean = false,
    val dashed: Boolean = false,
)

@Composable
private fun Legend(items: List<LegendItem>, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { item ->
            Canvas(Modifier.size(11.dp, 8.dp)) {
                if (item.line) {
                    drawLine(
                        item.color,
                        Offset(0f, size.height / 2f),
                        Offset(size.width, size.height / 2f),
                        strokeWidth = 2.dp.toPx(),
                        pathEffect = if (item.dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null,
                    )
                } else {
                    drawRect(item.color)
                }
            }
            Text(
                item.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 3.dp, end = 9.dp),
            )
        }
        trailing?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The value, or null where it is not a number the figure may draw. */
private fun Double.finite(): Float? = if (isFinite()) toFloat() else null

private fun niceCeil(v: Float): Float {
    if (!v.isFinite() || v <= 0f) return 1f
    val mag = 10.0.pow(floor(log10(v.toDouble()))).toFloat()
    val n = v / mag
    val step = when {
        n <= 1f -> 1f
        n <= 2f -> 2f
        n <= 5f -> 5f
        else -> 10f
    }
    return step * mag
}

private fun fmtAxis(v: Float): String = if (v >= 10f || v == 0f) v.toInt().toString() else "%.1f".format(v)

private fun fmtCov(v: Float): String = "%.2f".format(v).removePrefix("0")
