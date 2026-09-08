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
import androidx.compose.runtime.remember
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
import com.t1dm.core.model.ScoredPoint
import com.t1dm.core.model.TrendMatrix
import com.t1dm.core.model.TREND_BINS
import com.t1dm.core.model.TREND_CATEGORIES
import com.t1dm.core.model.ZoneLattice
import com.t1dm.core.model.HorizonMetrics
import com.t1dm.core.model.PointBlock
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt

/** Every number is the core's, nothing recomputed; an undefined quantity is omitted, not zero. */

private val FigureHeight = 128.dp
private val RowHeight = 18.dp
private val RowGap = 9.dp
private val LabelSp = 9.sp

/** Persistence cap sits below the RMSE bar top when the model lost; no band, no MAE for it. */
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
                // Wider than its bar so the gap reads, but not so wide it reaches the MAE bar.
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

/** Realized band coverage against the nominal targets of `SPEC/invariants.md` §6.2. */
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

        // Over the bars: a bar must never hide the target.
        val dash = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))
        targets.forEach { t ->
            val ty = y(t)
            drawLine(cs.onSurface, Offset(plot.left, ty), Offset(plot.right, ty), 2.dp.toPx(), pathEffect = dash)
            label(measurer, fmtCov(t), targetStyle, plot.left - 4.dp.toPx(), ty, alignEnd = true)
        }
    }
}

/** §6.3: whole-window, no horizon label; each bar carries its own n, denominators differ widely. */
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
    // A region that held no point has no triple: the core reports null, not a zero.
    shares = listOfNotNull(r.apPct?.finite(), r.bePct?.finite(), r.epPct?.finite())
        .takeIf { it.size == 3 && r.n > 0 }
        .orEmpty(),
)

@Composable
internal fun ClarkeFigure(hs: List<HorizonMetrics>) {
    StackedFigure(
        rows = hs.map { StackRow("${it.horizonMin}m", "n=${it.n}", clarkeShares(it.band)) },
        colors = zoneRamp(),
        legend = ZONE_LETTERS,
    )
}

/** All five shares come off the core individually — no remainder, and no A∪B quantity to report. */
@Composable
internal fun DtsFigure(hs: List<HorizonMetrics>) {
    StackedFigure(
        rows = hs.map { StackRow("${it.horizonMin}m", "n=${it.n}", dtsShares(it.band)) },
        colors = zoneRamp(),
        legend = ZONE_LETTERS,
    )
}

/** Percent; empty where any input is non-finite, so a partial partition isn't rendered as data. */
internal fun dtsShares(b: PointBlock): List<Float> {
    val s = listOf(b.dtsA, b.dtsB, b.dtsC, b.dtsD, b.dtsE).map { it.finite() ?: return emptyList() }
    return s.map { it.coerceAtLeast(0f) }
}

/** Hue carries ordinal severity only (palette can't supply five); one ramp for both grids. */
@Composable
private fun zoneRamp(): List<Color> {
    val p = LocalT1dmSemantics.current
    return listOf(p.inRange, p.inRange.copy(alpha = 0.45f), p.low, p.high, p.urgentLow)
}

/** Percent from the FOUR the core publishes; B=A∪B-A, C is the remainder; non-finite is empty. */
internal fun clarkeShares(b: PointBlock): List<Float> {
    val a = b.clarkeA.finite() ?: return emptyList()
    val ab = b.clarkeAb.finite() ?: return emptyList()
    val d = b.clarkeD.finite() ?: return emptyList()
    val e = b.clarkeE.finite() ?: return emptyList()
    val zoneB = (ab - a).coerceAtLeast(0f)
    val zoneC = (100f - ab - d - e).coerceAtLeast(0f)
    return listOf(a.coerceAtLeast(0f), zoneB, zoneC, d.coerceAtLeast(0f), e.coerceAtLeast(0f))
}

private val GridHeight = 248.dp
private val ZoneLetterSp = 11.sp
private val DotRadius = 1.9.dp

private val ZONE_LETTERS = listOf("A", "B", "C", "D", "E")

/** Region tint/dot ink per zone A-E; OPACITY separates them, not hue (theme roles may collide). */
private val REGION_ALPHA = listOf(0.06f, 0.13f, 0.22f, 0.34f, 0.48f)
private val DOT_ALPHA = listOf(0.35f, 0.55f, 0.75f, 0.90f, 1.00f)

/** zoneOf/grid must be the SAME grid; basis is the median line, not the band projection. */
@Composable
internal fun ErrorGridFigure(
    horizonMin: Int,
    points: List<ScoredPoint>,
    zoneOf: (ScoredPoint) -> Int,
    grid: ZoneLattice,
) {
    val cs = MaterialTheme.colorScheme
    val p = LocalT1dmSemantics.current
    val measurer = rememberTextMeasurer()
    val shares = remember(points, zoneOf) { zoneShares(points, zoneOf) }
    val runs = remember(grid) { zoneRuns(grid) }
    val anchors = remember(grid) { zoneAnchors(grid) }
    val ramp = listOf(p.inRange, p.inRange, p.low, p.high, p.urgentLow)
    val fills = ramp.mapIndexed { i, c -> c.copy(alpha = REGION_ALPHA[i]) }
    val inks = ramp.mapIndexed { i, c -> c.copy(alpha = DOT_ALPHA[i]) }

    // Emitted outside the Canvas, so shares still name every zone when the canvas draws nothing.
    Legend(
        ZONE_LETTERS.mapIndexed { i, letter ->
            LegendItem(inks[i], if (shares.isEmpty()) letter else "$letter ${fmtAxis(shares[i])}")
        },
        trailing = if (shares.isEmpty()) null else "%",
    )
    Canvas(Modifier.fillMaxWidth().height(GridHeight)) {
        if (grid.isEmpty || points.isEmpty()) return@Canvas
        val axisStyle = TextStyle(color = cs.onSurfaceVariant, fontSize = LabelSp)
        val letterStyle = TextStyle(color = cs.onSurface.copy(alpha = 0.8f), fontSize = ZoneLetterSp)
        val axisMax = grid.axisMaxMgdl.toFloat()
        val ticks = listOf(0f, 100f, 200f, 300f, 400f).filter { it <= axisMax }
        val laid = ticks.map { measurer.measure(fmtAxis(it), axisStyle) }
        val gutter = laid.maxOf { it.size.width }.toFloat() + 5.dp.toPx()
        val lineH = laid.maxOf { it.size.height }.toFloat()
        val tickRow = lineH + 3.dp.toPx()
        val capRow = lineH + 2.dp.toPx()
        val bottom = size.height - tickRow - capRow
        // Square: unequal mg/dL per pixel would tilt the identity line and every boundary with it.
        val side = min(size.width - gutter, bottom - lineH / 2f)
        if (side <= 0f) return@Canvas
        val left = gutter + (size.width - gutter - side) / 2f
        val plotTop = bottom - side
        fun px(v: Float) = left + (v / axisMax) * side
        fun py(v: Float) = bottom - (v / axisMax) * side

        // Whole-pixel edges: a fractional edge antialiases into a hairline read as a boundary.
        fun snap(v: Float) = round(v)
        runs.forEach { r ->
            val x0 = snap(left + r.truthIndex * side / grid.cells)
            val x1 = snap(left + (r.truthIndex + 1) * side / grid.cells)
            val y0 = snap(bottom - r.predUntil * side / grid.cells)
            val y1 = snap(bottom - r.predFrom * side / grid.cells)
            if (x1 <= x0 || y1 <= y0) return@forEach
            drawRect(fills[r.zone], Offset(x0, y0), Size(x1 - x0, y1 - y0))
        }

        // No interior gridlines: over five tinted regions they read as zone boundaries.
        drawRect(cs.outlineVariant, Offset(left, plotTop), Size(side, side), style = Stroke(1f))
        drawLine(cs.onSurface.copy(alpha = 0.45f), Offset(left, bottom), Offset(left + side, plotTop), 1.dp.toPx())
        val tick = 3.dp.toPx()
        ticks.forEach { t ->
            val gx = px(t)
            val gy = py(t)
            drawLine(cs.outlineVariant, Offset(left - tick, gy), Offset(left, gy), 1f)
            drawLine(cs.outlineVariant, Offset(gx, bottom), Offset(gx, bottom + tick), 1f)
            label(measurer, fmtAxis(t), axisStyle, left - tick - 2.dp.toPx(), gy, alignEnd = true)
            label(measurer, fmtAxis(t), axisStyle, gx, bottom + tickRow - lineH, centreX = true)
        }

        // A pair off axis is DROPPED and counted, never clamped into a zone not classified.
        val r = DotRadius.toPx()
        var offScale = 0
        points.forEach { pt ->
            val t = pt.truth
            val q = pt.pred
            if (!t.isFinite() || !q.isFinite() || t < 0.0 || t > axisMax || q < 0.0 || q > axisMax) {
                offScale++
                return@forEach
            }
            drawCircle(inks[zoneOf(pt)], r, Offset(px(t.toFloat()), py(q.toFloat())))
        }

        // Letters last: a dense scatter must not bury the only exact naming of a region.
        anchors.forEach { a ->
            centredLabel(measurer, ZONE_LETTERS[a.zone], letterStyle, px(a.truthMgdl.toFloat()), py(a.predMgdl.toFloat()))
        }

        val caption = buildString {
            append("x truth · y pred · mg/dL · $horizonMin min · n=${points.size}")
            if (offScale > 0) append(" · $offScale off-scale")
        }
        label(measurer, caption, axisStyle, left + side / 2f, bottom + tickRow + 2.dp.toPx(), centreX = true)
    }
}

/** Percent counted off the per-point enums the core classified; empty for an empty series. */
internal fun zoneShares(points: List<ScoredPoint>, zoneOf: (ScoredPoint) -> Int): List<Float> {
    if (points.isEmpty()) return emptyList()
    val counts = IntArray(ZONE_LETTERS.size)
    points.forEach { counts[zoneOf(it)]++ }
    return counts.map { 100f * it / points.size }
}

/** One painted cell run: truth column, pred rows [predFrom,predUntil); zone is an ORDINAL. */
internal class ZoneRun(
    val truthIndex: Int,
    val predFrom: Int,
    val predUntil: Int,
    val zone: Int,
)

/** Run-length encoded down each truth column, losslessly: no edge the classifier did not draw. */
internal fun zoneRuns(grid: ZoneLattice): List<ZoneRun> {
    if (grid.isEmpty) return emptyList()
    val out = ArrayList<ZoneRun>(grid.cells * 6)
    for (ti in 0 until grid.cells) {
        var start = 0
        var zone = grid.ordinalAt(ti, 0)
        for (pi in 1 until grid.cells) {
            val z = grid.ordinalAt(ti, pi)
            if (z != zone) {
                out += ZoneRun(ti, start, pi, zone)
                start = pi
                zone = z
            }
        }
        out += ZoneRun(ti, start, grid.cells, zone)
    }
    return out
}

/** Where one zone letter may be printed, in mg/dL on both axes. [zone] is an ordinal. */
internal class ZoneAnchor(val zone: Int, val truthMgdl: Double, val predMgdl: Double)

/** A lobe smaller than this share of the lattice gets no letter — it could not hold one legibly. */
private const val ANCHOR_MIN_SHARE = 0.005

/** Two letters of one zone closer than this fraction of the axis are one lobe seen twice. */
private const val ANCHOR_MIN_SEPARATION = 0.22

/** Fraction of axis a letter must stand clear of; a fraction, not a cell count, survives CELLS. */
private const val ANCHOR_CLEARANCE = 0.03

internal fun anchorClearanceCells(cells: Int): Int = (ANCHOR_CLEARANCE * cells).roundToInt()

/** Lobes above/below identity anchored separately, at the nearest clear-neighbourhood cell. */
internal fun zoneAnchors(grid: ZoneLattice): List<ZoneAnchor> {
    if (grid.isEmpty) return emptyList()
    val n = grid.cells
    val zoneCount = grid.zoneCount
    val groups = zoneCount * 2
    fun groupOf(ti: Int, pi: Int) = grid.ordinalAt(ti, pi) * 2 + if (pi >= ti) 0 else 1

    val count = IntArray(groups)
    val sumT = LongArray(groups)
    val sumP = LongArray(groups)
    for (ti in 0 until n) for (pi in 0 until n) {
        val g = groupOf(ti, pi)
        count[g]++
        sumT[g] += ti
        sumP[g] += pi
    }
    // Edge cells are never clear, so a letter cannot overhang the plot either.
    val clear = anchorClearanceCells(n)
    fun isClear(ti: Int, pi: Int): Boolean {
        if (clear <= 0) return true
        val z = grid.ordinalAt(ti, pi)
        if (ti < clear || pi < clear || ti + clear >= n || pi + clear >= n) return false
        for (dt in -clear..clear) for (dp in -clear..clear) {
            if (grid.ordinalAt(ti + dt, pi + dp) != z) return false
        }
        return true
    }
    // One sweep: best clear candidate, plus nearest cell of any kind as the fallback.
    val bestD = DoubleArray(groups) { Double.MAX_VALUE }
    val bestT = IntArray(groups) { -1 }
    val bestP = IntArray(groups) { -1 }
    val anyD = DoubleArray(groups) { Double.MAX_VALUE }
    val anyT = IntArray(groups) { -1 }
    val anyP = IntArray(groups) { -1 }
    for (ti in 0 until n) for (pi in 0 until n) {
        val g = groupOf(ti, pi)
        if (count[g] == 0) continue
        val dt = ti - sumT[g].toDouble() / count[g]
        val dp = pi - sumP[g].toDouble() / count[g]
        val d = dt * dt + dp * dp
        if (d < anyD[g]) {
            anyD[g] = d
            anyT[g] = ti
            anyP[g] = pi
        }
        if (d < bestD[g] && isClear(ti, pi)) {
            bestD[g] = d
            bestT[g] = ti
            bestP[g] = pi
        }
    }
    for (g in 0 until groups) {
        if (bestT[g] < 0) {
            bestT[g] = anyT[g]
            bestP[g] = anyP[g]
        }
    }

    val total = n.toDouble() * n
    val sep = ANCHOR_MIN_SEPARATION * n
    val out = ArrayList<ZoneAnchor>(groups)
    for (z in 0 until zoneCount) {
        val kept = ArrayList<Int>(2)
        (0..1).map { z * 2 + it }
            .filter { count[it] > 0 && count[it] / total >= ANCHOR_MIN_SHARE }
            .sortedByDescending { count[it] }
            .forEach { g ->
                val far = kept.none { k ->
                    val dt = (bestT[k] - bestT[g]).toDouble()
                    val dp = (bestP[k] - bestP[g]).toDouble()
                    dt * dt + dp * dp < sep * sep
                }
                if (far) kept += g
            }
        kept.forEach { out += ZoneAnchor(z, grid.coordAt(bestT[it]), grid.coordAt(bestP[it])) }
    }
    return out
}

private val MatrixHeight = 190.dp
private val CellSp = 9.sp

/** Opacity of the fullest cell, capped short of 1 so the printed count stays legible. */
private const val CELL_ALPHA_MAX = 0.62f

/** Truth's bin on x, forecast's on y; shaded by COUNT never risk; empty binLabels bares axes. */
@Composable
internal fun TrendMatrixFigure(m: TrendMatrix, binLabels: List<String>) {
    val cs = MaterialTheme.colorScheme
    val p = LocalT1dmSemantics.current
    val measurer = rememberTextMeasurer()
    val ink = p.inRange

    Legend(listOf(LegendItem(ink.copy(alpha = CELL_ALPHA_MAX), "pairs")), trailing = "x truth · y forecast · mg/dL/min")
    Canvas(Modifier.fillMaxWidth().height(MatrixHeight)) {
        if (m.isEmpty) return@Canvas
        val axisStyle = TextStyle(color = cs.onSurfaceVariant, fontSize = LabelSp)
        val cellStyle = TextStyle(color = cs.onSurface, fontSize = CellSp)
        val labels = binLabels.takeIf { it.size == TREND_BINS }.orEmpty()
        val laid = labels.map { measurer.measure(it, axisStyle) }
        val gutter = (laid.maxOfOrNull { it.size.width } ?: 0).toFloat() + 4.dp.toPx()
        val lineH = (laid.maxOfOrNull { it.size.height } ?: measurer.measure("0", axisStyle).size.height).toFloat()
        val bottom = size.height - lineH - 3.dp.toPx()
        val side = min(size.width - gutter, bottom)
        if (side <= 0f) return@Canvas
        val left = gutter + (size.width - gutter - side) / 2f
        val cell = side / TREND_BINS
        val peak = m.peak.toFloat().takeIf { it > 0f } ?: return@Canvas

        for (tb in 0 until TREND_BINS) {
            for (pb in 0 until TREND_BINS) {
                val x = left + tb * cell
                val y = bottom - (pb + 1) * cell
                val n = m.countAt(tb, pb)
                if (n > 0) {
                    drawRect(ink.copy(alpha = CELL_ALPHA_MAX * n / peak), Offset(x, y), Size(cell, cell))
                }
                // The diagonal is category 1 in all three of the paper's tables — the no-risk run.
                val stroke = if (tb == pb) 1.6.dp.toPx() else 1f
                val edge = if (tb == pb) cs.onSurface.copy(alpha = 0.55f) else cs.outlineVariant
                drawRect(edge, Offset(x, y), Size(cell, cell), style = Stroke(stroke))
                if (n > 0) {
                    centredLabel(measurer, n.toString(), cellStyle, x + cell / 2f, y + cell / 2f)
                }
            }
        }
        labels.forEachIndexed { i, text ->
            // x: the truth's bin, under its column. y: the forecast's, beside its row.
            label(measurer, text, axisStyle, left + (i + 0.5f) * cell, bottom + 3.dp.toPx(), centreX = true)
            label(measurer, text, axisStyle, left - 3.dp.toPx(), bottom - (i + 0.5f) * cell, alignEnd = true)
        }
    }
}

@Composable
internal fun TrendCategoryFigure(hs: List<HorizonMetrics>) {
    val p = LocalT1dmSemantics.current
    StackedFigure(
        rows = hs.map { StackRow("${it.horizonMin}m", "n=${it.trend.n}", trendCategoryShares(it.trend)) },
        colors = listOf(p.inRange, p.low, p.high, p.urgentLow, p.urgentLow.copy(alpha = 0.7f))
            .take(TREND_CATEGORIES),
        legend = List(TREND_CATEGORIES) { "${it + 1}" },
    )
}

/** Percent. Empty for an empty matrix rather than a partition of nothing. */
internal fun trendCategoryShares(m: TrendMatrix): List<Float> {
    // Also keeps `StackedFigure`'s `colors[k]` in bounds, so it reads the shared count.
    if (m.categoryPct.size != TREND_CATEGORIES) return emptyList()
    return m.categoryPct.map { it.finite() ?: return emptyList() }
}

/** edges are the core's interior edges; a wrong-length list yields no labels, never a guess. */
internal fun trendBinLabels(edges: List<Double>): List<String> {
    if (edges.size != TREND_BINS - 1) return emptyList()
    val n = edges.map { fmtRate(it) }
    return List(TREND_BINS) { i ->
        when (i) {
            0 -> "<${n.first()}"
            TREND_BINS - 1 -> ">${n.last()}"
            else -> "${n[i - 1]}..${n[i]}"
        }
    }
}

private fun fmtRate(v: Double): String =
    if (v == round(v)) v.toInt().toString() else "%.1f".format(v)

internal class StackRow(val label: String, val note: String, val shares: List<Float>)

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

private fun DrawScope.centredLabel(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    x: Float,
    y: Float,
) {
    val laid = measurer.measure(text, style)
    drawText(laid, topLeft = Offset(x - laid.size.width / 2f, y - laid.size.height / 2f))
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
