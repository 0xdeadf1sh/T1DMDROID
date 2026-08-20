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
    /** The fan's nested lower edges, outer→inner — the same three pairs [PredSeries] carries. A roll
     *  whose producer gave no interior levels has ONE pair, and draws as the single band it is. */
    val lo: Array<FloatArray>,
    val hi: Array<FloatArray>,
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
    // Ascending-τ columns: 0=.05 1=.10 2=.25 3=.50 4=.75 5=.90 6=.95. Fan pairs outer→inner, the
    // same three `buildPredSeries` takes — so the two fans on one panel are read the same way.
    val q = if (n > 0 && rolled.bandsMgdl.size % n == 0) rolled.bandsMgdl.size / n else 0
    val lo: Array<FloatArray>
    val hi: Array<FloatArray>
    if (q >= N_QUANTILES) {
        val loCols = intArrayOf(0, 1, 2)
        val hiCols = intArrayOf(q - 1, q - 2, q - 3)
        lo = Array(3) { b -> FloatArray(n) { i -> conv(rolled.bandsMgdl[i * q + loCols[b]]) } }
        hi = Array(3) { b -> FloatArray(n) { i -> conv(rolled.bandsMgdl[i * q + hiCols[b]]) } }
    } else {
        lo = arrayOf(FloatArray(n) { conv(rolled.lowerBg.getOrElse(it) { rolled.medianBg[it] }) })
        hi = arrayOf(FloatArray(n) { conv(rolled.upperBg.getOrElse(it) { rolled.medianBg[it] }) })
    }
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
internal fun RolledSeries.bandOpenLo(seam: RolledSeam?, band: Int): Float {
    val i = bandFromIndex()
    // Only the OUTERMOST pair meets the cycle fan's own outer edge; the inner ones open on their
    // own, since the seam carries one uncertainty and not a fan.
    return if (band == 0 && seam != null && seam.tsMs == tsMs[i]) seam.lo else lo[band][i]
}

/** The band's opening upper edge — see [bandOpenLo]. */
internal fun RolledSeries.bandOpenHi(seam: RolledSeam?, band: Int): Float {
    val i = bandFromIndex()
    return if (band == 0 && seam != null && seam.tsMs == tsMs[i]) seam.hi else hi[band][i]
}

// Raw-pixel dash constants, held rather than rebuilt inside the draw — see the same note in
// PredOverlay.kt. Neither depends on the density, the theme or the roll, and a [PathEffect] is
// immutable, so the two allocations they replace were pure per-frame waste.
private val EXTRAPOLATED_MEDIAN_DASH: PathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))

/**
 * Draw the on-demand rolled forecast in the same hand the cycle forecast is drawn in.
 *
 * **The same appearance as a forecast, deliberately.** The roll runs the same artifact on the same
 * fp32 path — it is the cycle's own forecast re-fed to itself — so hatching it, dashing it, ruling
 * a boundary through it and captioning it claimed a difference in kind that does not exist. The
 * band alpha, the stroke weight and the endpoint marker are all [drawPredSeries]'s.
 *
 * **A degenerate roll still loses its band**, exactly as a degenerate forecast does: a collapsed or
 * misordered band must not read as confidence, and that is part of the forecast's style rather than
 * an exception to it.
 *
 * The roll remains display-only, and structurally so: it is a [com.t1dm.core.model.RolledForecast],
 * a type `:calc` cannot accept, so no alert, rail or dose recommendation can read one however it is
 * painted.
 *
 * The same three nested pairs, from the same seven levels: [com.t1dm.calc.FanStep] now carries the
 * whole slot rather than projecting it down to its outer edges. A roll whose producer gave no
 * interior levels still draws as the single band it is, at the outermost pair's own weight.
 */
internal fun DrawScope.drawRolledSeries(
    s: RolledSeries,
    absToPx: AbsToPx,
    valToPx: ValToPx,
    lineColor: Color,
    fanColor: Color,
    seam: RolledSeam? = null,
    scratch: Path,
) {
    if (s.isEmpty) return
    fun px(i: Int) = absToPx.of(s.tsMs[i].toDouble())

    // The band starts where the cycle fan stops, opening from the fan's own edge when the two meet
    // at that instant: that x is where the series join and it may carry exactly one uncertainty.
    // Everything past it is the roll's own — no correction is fitted out there and none may be
    // invented. Over the prefix the cycle forecast draws its own fan, and a second one on top of it
    // would simply be twice the ink.
    if (!s.degenerate && s.size - s.bandFromIndex() >= 2) {
        val from = s.bandFromIndex()
        // `drawPredSeries`'s own order and alphas: innermost first, outermost last and heaviest, so
        // the composite darkens towards the centre exactly as the cycle fan beside it does.
        for (b in s.lo.size - 1 downTo 0) {
            val openLo = s.bandOpenLo(seam, b)
            val openHi = s.bandOpenHi(seam, b)
            fun lo(i: Int) = if (i == from) openLo else s.lo[b][i]
            fun hi(i: Int) = if (i == from) openHi else s.hi[b][i]
            // Reused across frames; see the note in `drawPredSeries`.
            val band = scratch.also { it.reset() }
            for (i in from until s.size) {
                val x = px(i); val y = valToPx.of(hi(i))
                if (i == from) band.moveTo(x, y) else band.lineTo(x, y)
            }
            for (i in s.size - 1 downTo from) band.lineTo(px(i), valToPx.of(lo(i)))
            band.close()
            // A roll with a single pair is the outermost one, so it takes the outermost weight.
            val alpha = if (s.lo.size == 1) 0.16f else 0.06f + 0.05f * (2 - b)
            drawPath(band, fanColor.copy(alpha = alpha))
        }
    }

    // One median across the whole roll. Drawn over the prefix too, so the line is continuous even
    // where no cycle forecast reaches — during warm-up there is none, and a roll that began in
    // mid-air would be unreadable.
    val alpha = if (s.degenerate) 0.5f else 1f
    val effect = if (s.degenerate) EXTRAPOLATED_MEDIAN_DASH else null
    for (i in 0 until s.size - 1) {
        drawLine(
            lineColor.copy(alpha = alpha),
            Offset(px(i), valToPx.of(s.median[i])),
            Offset(px(i + 1), valToPx.of(s.median[i + 1])),
            strokeWidth = 2.4f, cap = StrokeCap.Round, pathEffect = effect,
        )
    }
    if (!s.degenerate) {
        val li = s.size - 1
        drawCircle(lineColor, 3.2f, Offset(px(li), valToPx.of(s.median[li])), style = Stroke(width = 1.6f))
    }
}

/** The levels the head emits (`SPEC/invariants.md` §6) — the width a slot's fan must have before
 *  the outer→inner pairs can be taken from it. */
private const val N_QUANTILES = 7
