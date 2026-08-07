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

/**
 * The Models drill-down's figures, drawn on plain [Canvas] in the app's own idiom (AgpChart, the
 * glucose graph, the circadian dial) — palette roles read from the theme, text through a remembered
 * `TextMeasurer`, no charting dependency.
 *
 * Every number rendered here is the golden-gated core's. Nothing is recomputed: the only quantities
 * assembled on this side are [clarkeShares], which re-partitions figures the core already published,
 * and the CG-EGA and DTS triples/partitions, which the core already publishes whole. These are accuracy statements about a
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
    StackedFigure(
        rows = hs.map { StackRow("${it.horizonMin}m", "n=${it.n}", clarkeShares(it.band)) },
        colors = zoneRamp(),
        legend = ZONE_LETTERS,
    )
}

/**
 * The band-projected DTS Error Grid, A through E, per horizon.
 *
 * All five shares come off the core individually, so unlike [clarkeShares] this needs no remainder
 * — which is what makes the paper's instruction implementable: its panel declines to report a
 * combined A+B and names `pZA` alone as the metric, and there is no A∪B quantity anywhere here to
 * be tempted by.
 */
@Composable
internal fun DtsFigure(hs: List<HorizonMetrics>) {
    StackedFigure(
        rows = hs.map { StackRow("${it.horizonMin}m", "n=${it.n}", dtsShares(it.band)) },
        colors = zoneRamp(),
        legend = ZONE_LETTERS,
    )
}

/**
 * The five zone shares, in percent, from the five the core publishes.
 *
 * Returns empty where any input is non-finite, on the same reasoning [clarkeShares] states: a
 * partial partition renders as a plausible shape rather than as missing data.
 */
internal fun dtsShares(b: PointBlock): List<Float> {
    val s = listOf(b.dtsA, b.dtsB, b.dtsC, b.dtsD, b.dtsE).map { it.finite() ?: return emptyList() }
    return s.map { it.coerceAtLeast(0f) }
}

/**
 * The severity ramp both error grids' stacked bars use — A and B the same in-range ink (B paler),
 * then amber, orange, red.
 *
 * Not five arbitrary hues: the semantic palette cannot supply five separable ones (see
 * [REGION_ALPHA] for why), so hue carries only ordinal severity and the zone LETTER beside each
 * swatch carries the exact meaning. One ramp for both grids because the two are read against each
 * other — a zone-D share that moved between them should look like the same kind of thing.
 */
@Composable
private fun zoneRamp(): List<Color> {
    val p = LocalT1dmSemantics.current
    return listOf(p.inRange, p.inRange.copy(alpha = 0.45f), p.low, p.high, p.urgentLow)
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

// ── 5. Clarke error grid ───────────────────────────────────────────────────────────────────────

private val GridHeight = 248.dp
private val ZoneLetterSp = 11.sp
private val DotRadius = 1.9.dp

private val ZONE_LETTERS = listOf("A", "B", "C", "D", "E")

/**
 * Region tint and dot ink per zone, A→E, as fractions of one severity ramp.
 *
 * **Why not five hues.** The semantic palette cannot supply five separable ones: the glucose ramp is
 * deliberately hue-PAIRED at both ends (`urgentLow`/`urgentHigh`, `low`/`high` are near-identical on
 * every bundled palette, worst case ΔE76 ≈ 17), and an imported theme may legally set all five band
 * roles to the same colour — `parseThemeJson` requires the five fields and validates no distinctness
 * whatever. No fixed assignment of theme colours can be guaranteed legible, so none is attempted.
 *
 * **What is guaranteed instead.** Hue carries only ordinal severity, exactly as the stacked Clarke
 * figure's does, and the dimension that actually separates the five is OPACITY over the figure's own
 * surface — which no palette can collapse: a theme whose five band roles are one colour still yields
 * five distinct region tints and five distinct dot inks, monotone from A to E. That survives the
 * three bundled palettes and any imported one, because it does not depend on the palette at all.
 *
 * Colour is never the only thing naming a zone. The LETTER drawn in each region and the share beside
 * each legend swatch carry the exact meaning; the ramp carries "further from safe".
 */
private val REGION_ALPHA = listOf(0.06f, 0.13f, 0.22f, 0.34f, 0.48f)
private val DOT_ALPHA = listOf(0.35f, 0.55f, 0.75f, 0.90f, 1.00f)

/**
 * An error grid proper: one horizon's `(truth, prediction)` pairs scattered over the five lettered
 * zones, each point coloured by the zone the core put it in.
 *
 * **One figure, both grids.** Clarke and the DTS grid differ only in which lattice classifies the
 * plane and which column of [ScoredPoint] names each dot's zone; everything below — the run-length
 * painting, the letter placement, the axes, the off-scale accounting — is identical, and writing it
 * once is what keeps the two pictures literally comparable rather than merely similar. [zoneOf]
 * selects the column and [lattice] the regions; passing one grid's lattice with the other's selector
 * would paint dots outside their own regions, which is why the two always travel together from the
 * call site.
 *
 * **How the drawn regions are guaranteed to agree with the classifier.** They are not drawn from
 * boundaries at all. the core's `clarke_zone_grid` / `dts_zone_grid` classify a lattice of `(truth, pred)`
 * coordinates and this paints the cells it gets back, run-length encoded down each truth column
 * ([zoneRuns]); the outline a reader sees is therefore the classifier's own output rasterised, and
 * the only thing separating the painted edge from the true one is [ZoneLattice.CELLS]. Not one
 * inequality of `metrics.py::_clarke` is transcribed on this side, so no boundary drawn here can be
 * a boundary the core does not hold. The letters follow the same rule ([zoneAnchors]): each is
 * printed at a coordinate the classifier itself put in that zone, never at a remembered position.
 *
 * **What that guarantee is not.** It is agreement about the ALGEBRA, not about every pixel. The
 * regions are classified at cell CENTRES and the dots are placed at their exact coordinates, so a
 * pair within half a cell — [ZoneLattice.CELLS] over 400 mg/dL, i.e. 1.25 mg/dL — of a boundary
 * can be inked for one zone over a cell tinted for its neighbour. Around 1 % of a realistic
 * population sits that close, mostly along the A/B line the forecast crowds. The displacement is
 * bounded by that half-cell (about two pixels, against an eleven-pixel dot), and the dot's OWN
 * colour is the classifier's verdict either way: it is the tint under a boundary-hugging dot that
 * can be the neighbour's, never the dot's own zone.
 *
 * The BASIS is the median line, and the section header says so (§6.2). The band projection is the
 * normative basis for every level metric — and it is `clip(truth, lo, hi)`, a function OF the truth,
 * so on a scatter every pair whose truth fell inside the band lands EXACTLY on the identity diagonal
 * and unconditionally in zone A. At realized cov50 that is around half the points by construction:
 * the picture would be of band coverage wearing the look of a flawless forecast. The band's zone
 * SHARES are the stacked figure above, where they cannot mislead that way.
 *
 * Fails closed on everything. A horizon that did not pass `sufficient` never reaches here — the
 * caller refuses it by name, against its own `n`, and leaves the choice open rather than drawing a
 * neighbouring horizon under the refused one's label — and no lattice or no pairs draws nothing at
 * all rather than a grid with a region or a scatter missing.
 *
 * [horizonMin] and [points] MUST come off one `HorizonMetrics`. The caption states the horizon and
 * `points.size`, which is the very `n` the tables print for it (the core keeps one pair per scored
 * window), so a caller that resolved the two separately would publish a count against a horizon it
 * was not counted at. `clarkeGridPick` hands over the record for that reason.
 */
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

    // Emitted outside the Canvas, so the shares still name every zone when the canvas draws nothing.
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
        // Two text rows under the plot: the x ticks, then the caption naming the axes and the horizon.
        val tickRow = lineH + 3.dp.toPx()
        val capRow = lineH + 2.dp.toPx()
        val bottom = size.height - tickRow - capRow
        // Square, because a Clarke grid is read against its own diagonal: unequal mg/dL per pixel on
        // the two axes tilts the identity line and every zone boundary with it.
        val side = min(size.width - gutter, bottom - lineH / 2f)
        if (side <= 0f) return@Canvas
        val left = gutter + (size.width - gutter - side) / 2f
        val plotTop = bottom - side
        fun px(v: Float) = left + (v / axisMax) * side
        fun py(v: Float) = bottom - (v / axisMax) * side

        // (a) The regions — the lattice, painted, on WHOLE-PIXEL edges.
        //
        // A fractional cell (side / 160 is never an integer) leaves every rect edge mid-pixel, and
        // antialiasing then covers that pixel partially from each side: a hairline grid that reads as
        // a zone boundary. Widening each rect to overlap its neighbour would close it, but the fills
        // are translucent and composite SrcOver, so the overlap is painted twice — 1 − (1 − α)² — and
        // the hairline comes back at the opposite sign, darker rather than lighter. Snapping instead
        // makes adjacent runs share an edge exactly: every pixel is covered once, by one run, with no
        // seam to close and nothing composited twice. A column narrower than a pixel collapses to
        // nothing rather than to a gap — its neighbours already abut across it.
        fun snap(v: Float) = round(v)
        runs.forEach { r ->
            val x0 = snap(left + r.truthIndex * side / grid.cells)
            val x1 = snap(left + (r.truthIndex + 1) * side / grid.cells)
            val y0 = snap(bottom - r.predUntil * side / grid.cells)
            val y1 = snap(bottom - r.predFrom * side / grid.cells)
            if (x1 <= x0 || y1 <= y0) return@forEach
            drawRect(fills[r.zone], Offset(x0, y0), Size(x1 - x0, y1 - y0))
        }

        // (b) Frame, ticks and the identity diagonal. No interior gridlines: over five tinted
        // regions they read as zone boundaries, which is the one thing this figure may not fake.
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

        // (c) The pairs. A pair off the axis is DROPPED and counted, never clamped to the edge: a
        // clamped point sits in a zone it was not classified into, which is the figure lying.
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

        // (d) The letters, last, so a dense scatter cannot bury the only exact naming of a region.
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

/**
 * The five zone shares, in percent, counted off the per-point series the core classified.
 *
 * Unlike [clarkeShares] — which re-partitions four published totals because the stacked figure has
 * nothing else — this counts the very enums the core derived those totals from, so it needs no
 * remainder for C and cannot round into a negative slice. Empty for an empty series: no bar rather
 * than a partition of nothing.
 */
internal fun zoneShares(points: List<ScoredPoint>, zoneOf: (ScoredPoint) -> Int): List<Float> {
    if (points.isEmpty()) return emptyList()
    val counts = IntArray(ZONE_LETTERS.size)
    points.forEach { counts[zoneOf(it)]++ }
    return counts.map { 100f * it / points.size }
}

/** One painted cell run: truth column [truthIndex], prediction rows `[predFrom, predUntil)`.
 *  [zone] is an ORDINAL — this encoder is shared by both grids and names neither. */
internal class ZoneRun(
    val truthIndex: Int,
    val predFrom: Int,
    val predUntil: Int,
    val zone: Int,
)

/**
 * The lattice run-length encoded down each truth column — the shape the figure paints.
 *
 * A Clarke column crosses at most a handful of zones, so 160 columns collapse to a few hundred
 * rectangles rather than 25 600 cells, and the boundary between two differently tinted runs IS the
 * zone outline. The encoding is lossless: the runs of one column partition it exactly, so nothing
 * here can invent an edge the classifier did not draw.
 */
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

/**
 * How much of its own zone a letter must stand clear of, as a fraction of the axis on each side.
 *
 * A fraction rather than a cell count, because what has to fit is a glyph of fixed SIZE on a plot of
 * fixed size: the square side is around 213 dp over 400 mg/dL, and an 11 sp capital's ink reaches
 * some 4 dp from its centre — under 8 mg/dL, which 0.03 of the axis (12 mg/dL) covers with room for
 * the measured box's padding. Stated this way the guarantee survives a change to
 * [ZoneLattice.CELLS], which would otherwise silently shrink or inflate it.
 */
private const val ANCHOR_CLEARANCE = 0.03

/** [ANCHOR_CLEARANCE] in lattice cells, for a lattice of [cells] a side — the radius a letter's
 *  anchor must be uniform within. Derived here so nothing else has to restate the fraction. */
internal fun anchorClearanceCells(cells: Int): Int = (ANCHOR_CLEARANCE * cells).roundToInt()

/**
 * Where to print each zone's letter, derived from the lattice rather than remembered.
 *
 * Four of the five zones are two disjoint lobes — one above the identity line, one below — so the
 * candidates are grouped by that split; a single centroid would land B's letter in the middle of A.
 * Each group's anchor is the cell of that group NEAREST its own centroid, which is what makes this
 * honest: the letter is only ever printed at a coordinate the classifier itself put in that zone, so
 * it cannot name a region it does not sit in. Groups too small to hold a glyph are skipped, and two
 * anchors of one zone that end up close together (zone A's, which straddle the diagonal) collapse to
 * the larger.
 *
 * **A letter must also stand CLEAR of its zone, not merely inside it.** An anchor cell one cell from
 * a boundary satisfies "in its own zone" while the glyph drawn on it lies mostly over the
 * neighbouring region, and on the shipped lattice that is not hypothetical: zone B's below-diagonal
 * centroid falls in D, and the nearest B cell to it is 1.25 mg/dL from the truth ≥ 240 edge, so the
 * B was painted across the D lobe. So a candidate must have every cell within [ANCHOR_CLEARANCE] of
 * it — the glyph's own reach — in the same zone, and the nearest such cell to the centroid wins. A
 * group with no clear cell at all keeps the nearest one regardless: a thin lobe named imprecisely
 * still tells the reader more than a region with no letter on it.
 */
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
    // The glyph's reach in cells. A cell is CLEAR when the whole square of this radius around it —
    // truncated at no edge, so a letter never overhangs the plot either — carries the same zone.
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
    // Two passes in one sweep: the clear candidates, and the nearest cell of any kind as the
    // fallback for a lobe too thin to hold a glyph clear of its own boundary.
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

// ── 6. Trend Accuracy Matrix ───────────────────────────────────────────────────────────────────

private val MatrixHeight = 190.dp
private val CellSp = 9.sp

/** Opacity of the fullest cell; an empty cell keeps only the frame. A ramp rather than a solid so a
 *  count still reads through it, and capped short of 1 so the printed figure stays legible. */
private const val CELL_ALPHA_MAX = 0.62f

/**
 * The Trend Accuracy Matrix's `5 × 5` contingency table, as a heatmap — truth's rate bin on x,
 * the forecast's on y, so the diagonal runs bottom-left to top-right exactly as the identity line
 * does on the error grids above it and the whole screen reads with the truth on one axis.
 *
 * **Shaded by COUNT, never by risk category.** A cell holds points from every glycaemic region, and
 * the paper scores a cell's category from the point's own true BG through three different tables —
 * so a cell has no single category to be coloured by. Tinting one would invent a verdict the
 * statistic does not have. The category breakdown is the stacked bar beside this, where it is
 * counted per point and cannot be wrong.
 *
 * The diagonal is outlined because it means something exact: category 1 occupies precisely those
 * five cells in all three tables, so the outlined run IS the no-risk share, and a reader can see
 * what fraction of the mass sits on it without reading a number.
 *
 * [binLabels] come from the core's own bin edges. With none — a stub core — the axes go unlabelled
 * rather than captioned with edges this side guessed.
 */
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
                // The diagonal is category 1 in every one of the paper's three tables, so this
                // outline is the no-risk run made visible rather than a decoration.
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

/** The paper's five risk categories per horizon, stacked. */
@Composable
internal fun TrendCategoryFigure(hs: List<HorizonMetrics>) {
    val p = LocalT1dmSemantics.current
    StackedFigure(
        rows = hs.map { StackRow("${it.horizonMin}m", "n=${it.trend.n}", trendCategoryShares(it.trend)) },
        // Ordinal severity, as everywhere else on this screen: no risk, then the two ordinary
        // errors, then the two extreme ones. The legend numbers carry the exact meaning.
        colors = listOf(p.inRange, p.low, p.high, p.urgentLow, p.urgentLow.copy(alpha = 0.7f))
            .take(TREND_CATEGORIES),
        legend = List(TREND_CATEGORIES) { "${it + 1}" },
    )
}

/**
 * The five category shares of one matrix, in percent.
 *
 * Empty for an empty matrix — the core already returns no shares where no pair scored, and this
 * keeps that distinction rather than drawing a partition of nothing.
 */
internal fun trendCategoryShares(m: TrendMatrix): List<Float> {
    // The gate is also what keeps `StackedFigure`'s `colors[k]` in bounds, so it reads the SHARED
    // count rather than a local literal: a core that grew a sixth category would otherwise be
    // coloured from a five-entry ramp.
    if (m.categoryPct.size != TREND_CATEGORIES) return emptyList()
    return m.categoryPct.map { it.finite() ?: return emptyList() }
}

/**
 * Label each rate bin from the core's own interior [edges] — `< e0`, `e0..e1`, …, `> eLast`.
 *
 * Derived rather than written out, so a change to the binning in the crate relabels the axis
 * instead of leaving it captioning the old one. An edge list of the wrong length yields no labels
 * at all: an axis labelled from a guess is worse than one with no labels.
 */
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

/** Text centred on `(x, y)` — a mark placed INSIDE the plot rather than along an axis. */
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
