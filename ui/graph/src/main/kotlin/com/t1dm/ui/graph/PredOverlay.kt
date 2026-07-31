package com.t1dm.ui.graph

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.UnitSpace
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The forecast-overlay model (Phase 2 deliverable 7): the selected model's median
 * line + 7-level quantile fan drawn over [GlucoseGraph], with the other running models drawn
 * faintly, and `DEGENERATE`/`STALE` states visibly flagged. Values are already unit-converted and
 * carried as absolute epoch-ms per forecast step, so the Canvas only maps them to pixels — the same
 * off-thread, immutable-primitive-array discipline as [GraphFrame].
 *
 * The fan is three nested bands from the ascending-τ columns: outer `.05/.95`, mid `.10/.90`, inner
 * `.25/.75`; [lo]/[hi] are ordered outer→inner. A degenerate forecast (finiteness / rail-pin /
 * collapsed band / mis-ordering, §3.6-B) is drawn dashed with no fan; a stale one (anchor past the
 * freshness gate, §3.6-D) is dimmed and dashed. Neither is eligible to drive a rail or alert.
 */
class PredSeries internal constructor(
    val modelId: String,
    val selected: Boolean,
    val degenerate: Boolean,
    val stale: Boolean,
    val tsMs: LongArray,
    val median: FloatArray,
    val lo: Array<FloatArray>,
    val hi: Array<FloatArray>,
) {
    val size: Int get() = tsMs.size
    val isEmpty: Boolean get() = tsMs.isEmpty()
}

/** Build the overlay off-thread from the cycle's predictions (§2.3). */
suspend fun predOverlayOf(
    predictions: List<ModelPrediction>,
    unit: UnitSpace = UnitSpace.MgDl,
    kovatchevF: ((Double) -> Double)? = null,
): List<PredSeries> = withContext(Dispatchers.Default) {
    predictions.mapNotNull { buildPredSeries(it, unit, kovatchevF) }
}

/** Pure transform (no coroutines) — safe from a `@Preview`/test. */
fun buildPredSeries(
    p: ModelPrediction,
    unit: UnitSpace,
    kovatchevF: ((Double) -> Double)?,
): PredSeries? {
    val n = p.horizonSteps
    if (n == 0 || p.bandsMgdl.size != n * p.nQuantiles) return null
    val q = p.nQuantiles
    fun conv(mgdl: Double): Float = when (unit) {
        UnitSpace.MgDl -> mgdl
        UnitSpace.MmolL -> mgdl / 18.0182
        UnitSpace.Kovatchev -> kovatchevF?.invoke(mgdl) ?: mgdl
    }.toFloat()

    // Prepend the ANCHOR point (the last MEASURED BG at anchorTsMs) as element 0 so the median AND
    // fan grow OUT OF the last CGM reading instead of floating one step ahead — otherwise the forecast
    // visibly starts above/below the trace it should continue from. The anchor's fan is zero-width
    // (lo == hi == lastBg), fanning open from there. Forecast steps 1..n follow at (i)·stepMs.
    val anchorVal = conv(p.lastBg)
    val ts = LongArray(n + 1) { i -> p.anchorTsMs + i.toLong() * p.stepMs }
    val median = FloatArray(n + 1) { i -> if (i == 0) anchorVal else conv(p.medianBg[i - 1]) }
    // Ascending-τ columns: 0=.05 1=.10 2=.25 3=.50 4=.75 5=.90 6=.95. Fan pairs (outer→inner).
    val loCols = intArrayOf(0, 1, 2)
    val hiCols = intArrayOf(q - 1, q - 2, q - 3)
    val lo = Array(3) { b -> FloatArray(n + 1) { i -> if (i == 0) anchorVal else conv(p.bandsMgdl[(i - 1) * q + loCols[b]]) } }
    val hi = Array(3) { b -> FloatArray(n + 1) { i -> if (i == 0) anchorVal else conv(p.bandsMgdl[(i - 1) * q + hiCols[b]]) } }
    return PredSeries(
        modelId = p.modelId,
        selected = p.selected,
        degenerate = p.status != ForecastStatus.OK,
        stale = p.stale,
        tsMs = ts,
        median = median,
        lo = lo,
        hi = hi,
    )
}

// The two dash patterns, held rather than rebuilt. Both are raw-pixel constants — no density, no theme,
// nothing of the series in them — so deriving them inside the draw allocated an effect and its float
// array once per series per frame, and this panel's draw phase re-runs at the display's refresh rate for
// as long as a committed log marker is breathing. A [PathEffect] is immutable; one instance serves every
// draw of every series.
private val NOT_ELIGIBLE_DASH: PathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 6f))
private val FLAG_TICK_DASH: PathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f))

/**
 * Draw one forecast series into the plot. [absToPx]/[valToPx] project absolute epoch-ms and a
 * unit-value onto the shared [GlucoseGraph] viewport; [clip] guards the pixel range. Non-selected
 * series are faint; degenerate/stale series are dashed and fan-less.
 */
internal fun DrawScope.drawPredSeries(
    s: PredSeries,
    absToPx: (Double) -> Float,
    valToPx: (Float) -> Float,
    plotTop: Float,
    plotBottom: Float,
    lineColor: Color,
    fanColor: Color,
    flagColor: Color,
) {
    if (s.isEmpty) return
    val degenerate = s.degenerate
    val faint = !s.selected
    val medAlpha = when {
        degenerate -> 0.5f
        s.stale -> 0.45f
        faint -> 0.35f
        else -> 1f
    }
    val dashed = degenerate || s.stale
    val effect = if (dashed) NOT_ELIGIBLE_DASH else null

    fun px(i: Int) = absToPx(s.tsMs[i].toDouble())

    // Quantile fan (skip entirely when degenerate — a collapsed/misordered band must not read as
    // confidence). Only the selected model shows the full fan; others get a single faint band.
    if (!degenerate) {
        val bands = if (s.selected) 3 else 1
        for (b in bands - 1 downTo 0) {
            val a = (if (s.selected) 0.06f + 0.05f * (2 - b) else 0.05f) * if (s.stale) 0.6f else 1f
            val path = Path()
            for (i in 0 until s.size) {
                val x = px(i); val y = valToPx(s.hi[b][i])
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            for (i in s.size - 1 downTo 0) path.lineTo(px(i), valToPx(s.lo[b][i]))
            path.close()
            drawPath(path, fanColor.copy(alpha = a))
        }
    }

    // Median polyline.
    for (i in 0 until s.size - 1) {
        drawLine(
            lineColor.copy(alpha = medAlpha),
            androidx.compose.ui.geometry.Offset(px(i), valToPx(s.median[i])),
            androidx.compose.ui.geometry.Offset(px(i + 1), valToPx(s.median[i + 1])),
            strokeWidth = if (s.selected) 2.4f else 1.6f,
            cap = StrokeCap.Round,
            pathEffect = effect,
        )
    }
    // Endpoint marker for the selected model so the 2 h horizon is legible.
    if (s.selected && !degenerate) {
        val li = s.size - 1
        drawCircle(lineColor.copy(alpha = medAlpha), 3.2f, androidx.compose.ui.geometry.Offset(px(li), valToPx(s.median[li])), style = Stroke(width = 1.6f))
    }
    // A small flag tick at the horizon start when the forecast is not eligible.
    if (degenerate || s.stale) {
        val x = px(0)
        drawLine(flagColor.copy(alpha = 0.8f), androidx.compose.ui.geometry.Offset(x, plotTop), androidx.compose.ui.geometry.Offset(x, plotBottom), 1f, pathEffect = FLAG_TICK_DASH)
    }
}

/** The furthest forecast timestamp across all series, for viewport extension; `null` if none. */
internal fun List<PredSeries>.maxTsMs(): Long? =
    mapNotNull { if (it.isEmpty) null else it.tsMs.last() }.maxOrNull()

/**
 * The approaching hypo/hyper crossings (item 16): the FIRST step at which the SELECTED, §3.6-eligible
 * forecast median (mg/dL) drops below [lowMgdl] or rises above [highMgdl], each with an ETA from
 * [nowMs]. Returns nothing for a degenerate/stale forecast or when nothing is selected — so a marker
 * is never fabricated. Computed in mg/dL (the thresholds' space) regardless of the display unit.
 */
fun excursionsOf(
    predictions: List<ModelPrediction>,
    lowMgdl: Int,
    highMgdl: Int,
    nowMs: Long = System.currentTimeMillis(),
): List<ExcursionMarker> {
    val p = predictions.firstOrNull { it.selected && it.eligible } ?: return emptyList()
    val out = ArrayList<ExcursionMarker>(2)
    var hypo = false
    var hyper = false
    for (i in p.medianBg.indices) {
        val ts = p.anchorTsMs + (i + 1L) * p.stepMs
        val v = p.medianBg[i]
        val eta = ((ts - nowMs) / 60_000L).coerceAtLeast(0L)
        val level = v.roundToInt()
        if (!hypo && v < lowMgdl) { out.add(ExcursionMarker(ts, hyper = false, thresholdMgdl = lowMgdl, etaMin = eta, levelMgdl = level)); hypo = true }
        if (!hyper && v > highMgdl) { out.add(ExcursionMarker(ts, hyper = true, thresholdMgdl = highMgdl, etaMin = eta, levelMgdl = level)); hyper = true }
        if (hypo && hyper) break
    }
    return out
}
