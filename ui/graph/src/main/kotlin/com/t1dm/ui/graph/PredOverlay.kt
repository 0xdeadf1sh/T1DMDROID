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

/** One model's forecast, ready to draw; tsMs absolute epoch-ms, values already unit-converted. */
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

/** Off-thread (§2.3); calibrateBands is §8.4, applied here last, stored/pushed/alarms read raw. */
suspend fun predOverlayOf(
    predictions: List<ModelPrediction>,
    unit: UnitSpace = UnitSpace.MgDl,
    kovatchevFClinicalBatch: ((DoubleArray) -> DoubleArray)? = null,
    calibrateBands: ((ModelPrediction) -> List<Double>?)? = null,
): List<PredSeries> = withContext(Dispatchers.Default) {
    predictions.mapNotNull { buildPredSeries(it, unit, kovatchevFClinicalBatch, calibrateBands?.invoke(it)) }
}

/** Lanes of horizonSteps+1 values each: median, lo outer→inner, hi outer→inner. */
internal const val PRED_LANES = 7

/** [p]'s mg/dL lanes into [dst] from [at]; [bands] is bandsMgdl's layout, raw or §8.4. */
internal fun writePredLanes(p: ModelPrediction, bands: List<Double>, dst: DoubleArray, at: Int) {
    val n = p.horizonSteps
    val q = p.nQuantiles
    val w = n + 1
    // Element 0 is the ANCHOR: last measured BG at anchorTsMs, fan zero-width, grows out of trace.
    for (lane in 0 until PRED_LANES) dst[at + lane * w] = p.lastBg
    // Ascending-τ columns: 0=.05 1=.10 2=.25 3=.50 4=.75 5=.90 6=.95. Fan pairs (outer→inner).
    for (i in 0 until n) {
        dst[at + 1 + i] = p.medianBg[i]
        for (b in 0 until 3) {
            dst[at + (1 + b) * w + 1 + i] = bands[i * q + b]
            dst[at + (4 + b) * w + 1 + i] = bands[i * q + q - 1 - b]
        }
    }
}

/** Pure. calibratedBandsMgdl is §8.4 fan in bandsMgdl's own layout; a mismatch draws raw. */
fun buildPredSeries(
    p: ModelPrediction,
    unit: UnitSpace,
    kovatchevFClinicalBatch: ((DoubleArray) -> DoubleArray)?,
    calibratedBandsMgdl: List<Double>? = null,
): PredSeries? {
    val n = p.horizonSteps
    if (n == 0 || p.bandsMgdl.size != n * p.nQuantiles) return null
    val bands = calibratedBandsMgdl?.takeIf { it.size == n * p.nQuantiles } ?: p.bandsMgdl
    val w = n + 1
    val src = DoubleArray(PRED_LANES * w).also { writePredLanes(p, bands, it, 0) }
    val v = toUnit(src, unit, kovatchevFClinicalBatch) ?: return null
    fun lane(k: Int) = v.copyOfRange(k * w, (k + 1) * w)
    return PredSeries(
        modelId = p.modelId,
        selected = p.selected,
        degenerate = p.status != ForecastStatus.OK,
        stale = p.stale,
        tsMs = LongArray(w) { i -> p.anchorTsMs + i.toLong() * p.stepMs },
        median = lane(0),
        lo = Array(3) { lane(1 + it) },
        hi = Array(3) { lane(4 + it) },
    )
}

// Raw-pixel and series-independent, so one immutable effect serves every draw of every series.
private val NOT_ELIGIBLE_DASH: PathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 6f))
private val FLAG_TICK_DASH: PathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f))

internal fun DrawScope.drawPredSeries(
    s: PredSeries,
    absToPx: AbsToPx,
    valToPx: ValToPx,
    plotTop: Float,
    plotBottom: Float,
    lineColor: Color,
    fanColor: Color,
    flagColor: Color,
    scratch: Path,
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

    fun px(i: Int) = absToPx.of(s.tsMs[i].toDouble())

    // Skipped when degenerate: a collapsed or misordered band must not read as confidence.
    if (!degenerate) {
        val bands = if (s.selected) 3 else 1
        for (b in bands - 1 downTo 0) {
            val a = (if (s.selected) 0.06f + 0.05f * (2 - b) else 0.05f) * if (s.stale) 0.6f else 1f
            // One scratch path for all three bands and every frame; `reset()` keeps its capacity.
            val path = scratch.also { it.reset() }
            for (i in 0 until s.size) {
                val x = px(i); val y = valToPx.of(s.hi[b][i])
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            for (i in s.size - 1 downTo 0) path.lineTo(px(i), valToPx.of(s.lo[b][i]))
            path.close()
            drawPath(path, fanColor.copy(alpha = a))
        }
    }

    for (i in 0 until s.size - 1) {
        drawLine(
            lineColor.copy(alpha = medAlpha),
            androidx.compose.ui.geometry.Offset(px(i), valToPx.of(s.median[i])),
            androidx.compose.ui.geometry.Offset(px(i + 1), valToPx.of(s.median[i + 1])),
            strokeWidth = if (s.selected) 2.4f else 1.6f,
            cap = StrokeCap.Round,
            pathEffect = effect,
        )
    }
    if (s.selected && !degenerate) {
        val li = s.size - 1
        drawCircle(lineColor.copy(alpha = medAlpha), 3.2f, androidx.compose.ui.geometry.Offset(px(li), valToPx.of(s.median[li])), style = Stroke(width = 1.6f))
    }
    if (degenerate || s.stale) {
        val x = px(0)
        drawLine(flagColor.copy(alpha = 0.8f), androidx.compose.ui.geometry.Offset(x, plotTop), androidx.compose.ui.geometry.Offset(x, plotBottom), 1f, pathEffect = FLAG_TICK_DASH)
    }
}

internal fun List<PredSeries>.maxTsMs(): Long? =
    mapNotNull { if (it.isEmpty) null else it.tsMs.last() }.maxOrNull()

/** First crossing of lowMgdl/highMgdl by the SELECTED, §3.6-eligible median, ETA from nowMs. */
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
