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

    /** The rolled median (already in the frame's unit) at the step nearest absolute [ms], or null when
     *  the cursor is outside the drawn span — so the scrub read-out reports the predicted BG exactly
     *  where the rolled line is, and nothing beyond it. */
    fun medianAt(ms: Double): Float? {
        if (isEmpty) return null
        val half = if (size >= 2) kotlin.math.abs(tsMs[1] - tsMs[0]) / 2.0 else 0.0
        if (ms < tsMs.first() - half || ms > tsMs.last() + half) return null
        var best = 0
        var bestD = Double.MAX_VALUE
        for (i in 0 until size) {
            val d = kotlin.math.abs(tsMs[i].toDouble() - ms)
            if (d < bestD) { bestD = d; best = i }
        }
        return median[best]
    }
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
 */
internal fun DrawScope.drawRolledSeries(
    s: RolledSeries,
    absToPx: (Double) -> Float,
    valToPx: (Float) -> Float,
    plotTop: Float,
    plotBottom: Float,
    lineColor: Color,
    hatchColor: Color,
) {
    if (s.isEmpty) return
    fun px(i: Int) = absToPx(s.tsMs[i].toDouble())
    val vStart = s.validatedSteps.coerceIn(0, s.size)

    // (1) Validated prefix — faint solid median (the on-graph forecast already carries the full fan).
    if (vStart >= 2) {
        for (i in 0 until vStart - 1) {
            drawLine(
                lineColor.copy(alpha = 0.30f),
                Offset(px(i), valToPx(s.median[i])),
                Offset(px(i + 1), valToPx(s.median[i + 1])),
                strokeWidth = 1.4f, cap = StrokeCap.Round,
            )
        }
    }

    // (2) Extrapolated tail — a hatched band + dashed median, starting from the validated boundary so
    //     the tail visually continues the forecast.
    if (s.size - vStart >= 1) {
        val from = (vStart - 1).coerceAtLeast(0) // start one step early so the band abuts the prefix
        val band = Path()
        for (i in from until s.size) {
            val x = px(i); val y = valToPx(s.hi[i])
            if (i == from) band.moveTo(x, y) else band.lineTo(x, y)
        }
        for (i in s.size - 1 downTo from) band.lineTo(px(i), valToPx(s.lo[i]))
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
                Offset(px(i), valToPx(s.median[i])),
                Offset(px(i + 1), valToPx(s.median[i + 1])),
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
