package com.t1dm.ui.graph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import com.t1dm.core.model.RolledForecast
import com.t1dm.core.model.UnitSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The DISPLAY-ONLY rolled-forecast overlay (issue I2): the ephemeral, on-demand autoregressive roll
 * drawn over [GlucoseGraph]. It is a DISTINCT render model from [PredSeries] — it is built from a
 * [RolledForecast], never a `ModelPrediction`, so it can never reach the top-bar HYPO/HYPER indicator,
 * the notification countdown, or `:calc` (all of which read `ModelPrediction` / `PredFan`).
 *
 * The first [validatedSteps] coincide with the validated 2 h forecast; everything past it is
 * EXTRAPOLATED and drawn distinctly — a hatched, dimmed band with a boundary marker and a legend — so
 * a viewer can never mistake the compounding self-fed tail for a validated forecast.
 */
class RolledSeries internal constructor(
    val tsMs: LongArray,
    val median: FloatArray,
    val lo: FloatArray,
    val hi: FloatArray,
    /** The prefix length inside the validated 2 h horizon; steps past it are extrapolated. */
    val validatedSteps: Int,
    val degenerate: Boolean,
    val requestedHours: Double,
) {
    val size: Int get() = tsMs.size
    val isEmpty: Boolean get() = tsMs.isEmpty()
    val extrapolatedSteps: Int get() = (size - validatedSteps).coerceAtLeast(0)
    val maxTsMs: Long? get() = if (isEmpty) null else tsMs.last()

    /** The rolled step nearest absolute [ms], or -1 when the cursor is outside the drawn span — so the
     *  scrub read-out reports the predicted BG exactly where the rolled line is, and nothing beyond it.
     *  The INDEX rather than the value, because the caller needs [extrapolatedAt] for the same step:
     *  a number taken from past [validatedSteps] must be labelled, never printed like a validated one. */
    fun nearestIndex(ms: Double): Int = nearestWithinHalfStep(tsMs, ms)

    /** True when step [i] lies in the EXTRAPOLATED tail rather than the validated prefix — the same
     *  boundary the overlay draws its hatch, its dashed median and its dashed marker at. */
    fun extrapolatedAt(i: Int): Boolean = i >= validatedSteps
}

/** Build the rolled overlay off-thread, unit-converting the mg/dL fan once (mirrors [predOverlayOf]). */
suspend fun rolledSeriesOf(
    rolled: RolledForecast?,
    unit: UnitSpace = UnitSpace.MgDl,
    kovatchevF: ((Double) -> Double)? = null,
): RolledSeries? = withContext(Dispatchers.Default) { buildRolledSeries(rolled, unit, kovatchevF) }

/** Pure transform (no coroutines) — safe from a `@Preview`/test. Null when there is nothing to draw. */
fun buildRolledSeries(
    rolled: RolledForecast?,
    unit: UnitSpace,
    kovatchevF: ((Double) -> Double)?,
): RolledSeries? {
    if (rolled == null || rolled.isEmpty) return null
    val n = rolled.size
    fun conv(mgdl: Double): Float = when (unit) {
        UnitSpace.MgDl -> mgdl
        UnitSpace.MmolL -> mgdl / 18.0182
        UnitSpace.Kovatchev -> kovatchevF?.invoke(mgdl) ?: mgdl
    }.toFloat()
    val ts = LongArray(n) { i -> rolled.anchorTsMs + (i + 1L) * rolled.stepMs }
    val median = FloatArray(n) { conv(rolled.medianBg[it]) }
    val lo = FloatArray(n) { conv(rolled.lowerBg.getOrElse(it) { rolled.medianBg[it] }) }
    val hi = FloatArray(n) { conv(rolled.upperBg.getOrElse(it) { rolled.medianBg[it] }) }
    return RolledSeries(
        tsMs = ts, median = median, lo = lo, hi = hi,
        validatedSteps = rolled.validatedSteps.coerceIn(0, n),
        degenerate = rolled.degenerate,
        requestedHours = rolled.requestedHours,
    )
}

/**
 * The forecast fan's terminal outer edge, for the ONE instant the rolled band shares with it.
 *
 * The hatched band opens at the validated boundary so it abuts the forecast, and the forecast's fan
 * ENDS at that same boundary — so at that x two renderings state an uncertainty for one instant. It
 * is the same quantity (τ.05/.95 of the same model at the same step) but not necessarily the same
 * number: the fan carries the `SPEC/inference.md` §8.4 band correction, applied at the last point
 * before pixels, and the roll — built by `:calc` for the dose search and never entitled to a display
 * correction — does not. The visible result is a step in the drawn uncertainty exactly where the
 * dashed boundary rule tells the reader the two series join.
 *
 * Drawing the shared vertex once, from the fan, closes it whether or not a correction is in force,
 * and does so without extrapolating a correction into the tail, where none is fitted and none may be
 * invented. [tsMs] is checked rather than assumed: a model whose horizon differs from the roll's
 * validated prefix does not meet it there, and the roll then draws its own edge.
 */
class RolledSeam(val tsMs: Long, val lo: Float, val hi: Float)

/** The index at which the hatched band opens — one step before the validated boundary, so the tail
 *  abuts the prefix rather than starting a step late. */
internal fun RolledSeries.bandFromIndex(): Int =
    (validatedSteps.coerceIn(0, size) - 1).coerceAtLeast(0)

/** The band's opening lower edge: the fan's, when [seam] falls on that same instant; else the
 *  roll's own. Split from [bandOpenHi] rather than returned as a pair — this runs inside the draw. */
internal fun RolledSeries.bandOpenLo(seam: RolledSeam?): Float {
    val i = bandFromIndex()
    return if (seam != null && seam.tsMs == tsMs[i]) seam.lo else lo[i]
}

/** The band's opening upper edge — see [bandOpenLo]. */
internal fun RolledSeries.bandOpenHi(seam: RolledSeam?): Float {
    val i = bandFromIndex()
    return if (seam != null && seam.tsMs == tsMs[i]) seam.hi else hi[i]
}

// Raw-pixel dash constants, held rather than rebuilt inside the draw — see the same note in
// PredOverlay.kt. Neither depends on the density, the theme or the roll, and a [PathEffect] is
// immutable, so the two allocations they replace were pure per-frame waste.
private val EXTRAPOLATED_MEDIAN_DASH: PathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))
private val VALIDATED_BOUNDARY_DASH: PathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f))

/**
 * Draw the rolled forecast. The validated prefix (first [RolledSeries.validatedSteps]) is a thin,
 * dimmed median line — it duplicates the on-graph 2 h forecast, so it is kept faint. The EXTRAPOLATED
 * tail is drawn distinctly: a hatched translucent band between the .05/.95 edges, a dashed median, a
 * dashed vertical boundary at the 2 h mark, and a small "extrapolated" legend. A degenerate roll ends
 * at its valid prefix with a plain flag.
 *
 * [seam], when it lands on the boundary instant, supplies the band's opening edge — see [RolledSeam].
 */
internal fun DrawScope.drawRolledSeries(
    s: RolledSeries,
    absToPx: AbsToPx,
    valToPx: ValToPx,
    plotTop: Float,
    plotBottom: Float,
    lineColor: Color,
    hatchColor: Color,
    seam: RolledSeam? = null,
    scratch: Path,
) {
    if (s.isEmpty) return
    fun px(i: Int) = absToPx.of(s.tsMs[i].toDouble())
    val vStart = s.validatedSteps.coerceIn(0, s.size)

    // (1) Validated prefix — faint solid median (the on-graph forecast already carries the full fan).
    if (vStart >= 2) {
        for (i in 0 until vStart - 1) {
            drawLine(
                lineColor.copy(alpha = 0.30f),
                Offset(px(i), valToPx.of(s.median[i])),
                Offset(px(i + 1), valToPx.of(s.median[i + 1])),
                strokeWidth = 1.4f, cap = StrokeCap.Round,
            )
        }
    }

    // (2) Extrapolated tail — a hatched band + dashed median, starting from the validated boundary so
    //     the tail visually continues the forecast.
    if (s.size - vStart >= 1) {
        val from = s.bandFromIndex() // start one step early so the band abuts the prefix
        // The opening vertex is the forecast fan's, when the fan reaches this same instant: that x is
        // where the two series meet, and it may carry exactly one uncertainty. Everything past it is
        // the roll's own — no correction is fitted out there and none may be invented.
        val openLo = s.bandOpenLo(seam)
        val openHi = s.bandOpenHi(seam)
        fun lo(i: Int) = if (i == from) openLo else s.lo[i]
        fun hi(i: Int) = if (i == from) openHi else s.hi[i]
        // Reused across frames; see the note in `drawPredSeries`.
        val band = scratch.also { it.reset() }
        for (i in from until s.size) {
            val x = px(i); val y = valToPx.of(hi(i))
            if (i == from) band.moveTo(x, y) else band.lineTo(x, y)
        }
        for (i in s.size - 1 downTo from) band.lineTo(px(i), valToPx.of(lo(i)))
        band.close()
        // Translucent fill + diagonal hatch clipped to the band = the "unvalidated" texture.
        drawPath(band, hatchColor.copy(alpha = 0.08f))
        clipPath(band) {
            val x0 = px(from); val x1 = px(s.size - 1)
            val step = 10f
            var x = x0 - (plotBottom - plotTop)
            while (x < x1 + (plotBottom - plotTop)) {
                drawLine(
                    hatchColor.copy(alpha = 0.22f),
                    Offset(x, plotBottom), Offset(x + (plotBottom - plotTop), plotTop),
                    strokeWidth = 1f,
                )
                x += step
            }
        }
        // Dashed extrapolated median.
        for (i in from until s.size - 1) {
            drawLine(
                lineColor.copy(alpha = if (s.degenerate) 0.5f else 0.85f),
                Offset(px(i), valToPx.of(s.median[i])),
                Offset(px(i + 1), valToPx.of(s.median[i + 1])),
                strokeWidth = 2f, cap = StrokeCap.Round, pathEffect = EXTRAPOLATED_MEDIAN_DASH,
            )
        }
        // Dashed vertical boundary at the 2 h mark.
        if (vStart in 1 until s.size) {
            val bx = px(vStart - 1)
            drawLine(
                hatchColor.copy(alpha = 0.6f),
                Offset(bx, plotTop), Offset(bx, plotBottom),
                strokeWidth = 1f, pathEffect = VALIDATED_BOUNDARY_DASH,
            )
        }
    }
}
