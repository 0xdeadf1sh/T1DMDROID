package com.t1dm.ui.graph

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.UnitSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Stored forecasts, display-only. Rectangular: median[c*span+i], band-major over cycles. */
class HindsightFrame internal constructor(
    /** Ascending issue instants (stored made_at); the only array the cursor matches against. */
    val madeMs: LongArray,
    /** The reading the forecast grew from, x of step 0; behind madeMs, repeats over gaps. */
    val anchorMs: LongArray,
    val stepMs: Long,
    /** Steps per cycle including the prepended anchor: `horizonSteps + 1`. */
    val span: Int,
    /** Median gap between issue instants over this window; half of it is the cursor's catchment. */
    val cadenceMs: Long,
    val median: FloatArray,
    val lo: FloatArray,
    val hi: FloatArray,
    /** The forecast was not `OK` when made, so it is drawn fan-less. */
    val degenerate: BooleanArray,
    /** Anchor was already past the freshness gate at issue (§3.6-D); must not redraw as if not. */
    val stale: BooleanArray,
) {
    val cycles: Int get() = madeMs.size
    val isEmpty: Boolean get() = madeMs.isEmpty()

    /** The cycle issued nearest ms, within half cadenceMs; -1 between cycles, not the last one. */
    fun cycleAt(ms: Double): Int {
        val n = madeMs.size
        if (n == 0) return -1
        val half = cadenceMs / 2.0
        if (ms < madeMs[0] - half || ms > madeMs[n - 1] + half) return -1
        val hi = lowerBoundLong(madeMs, kotlin.math.ceil(ms).toLong()).coerceIn(0, n - 1)
        val lo = (hi - 1).coerceAtLeast(0)
        val dLo = kotlin.math.abs(madeMs[lo] - ms)
        val dHi = kotlin.math.abs(madeMs[hi] - ms)
        val best = if (dLo <= dHi) lo else hi
        return if (kotlin.math.min(dLo, dHi) <= half) best else -1
    }

    /** One rule for everything that draws a cycle. */
    fun eligible(cycle: Int): Boolean =
        cycle in 0 until cycles && !degenerate[cycle] && !stale[cycle]
}

/** The three nested pairs [buildPredSeries] fixes. */
private const val BANDS = 3

/** rows must ascend by cycleTsMs; calibrateFans applies §8.4 to the sweep; null keeps raw fan. */
suspend fun hindsightFrameOf(
    rows: List<ModelPrediction>,
    unit: UnitSpace = UnitSpace.MgDl,
    kovatchevFClinicalBatch: ((DoubleArray) -> DoubleArray)? = null,
    calibrateFans: ((fansMgdl: () -> List<Double>, steps: Int, nQuantiles: Int) -> List<Double>?)? = null,
): HindsightFrame? = withContext(Dispatchers.Default) {
    if (rows.isEmpty()) return@withContext null

    // First pass fixes the block's shape and counts the rows that fit it.
    var span = 0
    var stepMs = 0L
    var nq = 0
    var kept = 0
    for (p in rows) {
        val n = p.horizonSteps
        if (n == 0 || p.nQuantiles < BANDS * 2 + 1 || p.bandsMgdl.size != n * p.nQuantiles) continue
        if (span == 0) { span = n + 1; stepMs = p.stepMs; nq = p.nQuantiles }
        if (n + 1 != span || p.stepMs != stepMs || p.nQuantiles != nq) continue
        kept++
    }
    if (kept == 0) return@withContext null

    // Both passes must admit the same rows: the second writes at c into arrays the first sized.
    fun admits(p: ModelPrediction): Boolean {
        val n = p.horizonSteps
        return n != 0 && p.nQuantiles >= BANDS * 2 + 1 && p.bandsMgdl.size == n * p.nQuantiles &&
            n + 1 == span && p.stepMs == stepMs && p.nQuantiles == nq
    }

    // §8.4 in one crossing: admitted rows share a shape/model id, hence a delta; all or none.
    val fanLen = (span - 1) * nq
    val calibrated: List<Double>? = calibrateFans?.let { calibrate ->
        // Lazy: on the common path there's no stored correction, so the batch is never flattened.
        val build = {
            val flat = ArrayList<Double>(kept * fanLen)
            for (p in rows) if (admits(p)) flat.addAll(p.bandsMgdl)
            flat as List<Double>
        }
        calibrate(build, span - 1, nq)?.takeIf { it.size == kept * fanLen }
    }

    val madeMs = LongArray(kept)
    val anchorMs = LongArray(kept)
    val degenerate = BooleanArray(kept)
    val stale = BooleanArray(kept)
    val laneLen = PRED_LANES * span
    val mgdl = DoubleArray(kept * laneLen)
    var c = 0
    for (p in rows) {
        if (!admits(p)) continue
        // A view, not a copy: `writePredLanes` only indexes into it.
        val fan = calibrated?.subList(c * fanLen, (c + 1) * fanLen)
        writePredLanes(p, fan ?: p.bandsMgdl, mgdl, c * laneLen)
        madeMs[c] = p.cycleTsMs
        anchorMs[c] = p.anchorTsMs
        degenerate[c] = p.status != ForecastStatus.OK
        stale[c] = p.stale
        c++
    }
    val v = toUnit(mgdl, unit, kovatchevFClinicalBatch) ?: return@withContext null

    val median = FloatArray(kept * span)
    val lo = FloatArray(BANDS * kept * span)
    val hi = FloatArray(BANDS * kept * span)
    for (r in 0 until kept) {
        val at = r * laneLen
        System.arraycopy(v, at, median, r * span, span)
        for (b in 0 until BANDS) {
            val base = (b * kept + r) * span
            System.arraycopy(v, at + (1 + b) * span, lo, base, span)
            System.arraycopy(v, at + (1 + BANDS + b) * span, hi, base, span)
        }
    }
    HindsightFrame(
        madeMs, anchorMs, stepMs, span, cadenceOf(madeMs, stepMs),
        median, lo, hi, degenerate, stale,
    )
}

/** Median, not mean/min: holes drag a mean up, a tight pair pulls a min down; falls to stepMs. */
private fun cadenceOf(madeMs: LongArray, stepMs: Long): Long {
    if (madeMs.size < 2) return stepMs
    val gaps = LongArray(madeMs.size - 1) { madeMs[it + 1] - madeMs[it] }
    gaps.sort()
    val med = gaps[gaps.size / 2]
    return if (med > 0L) med else stepMs
}

/** Own winding, not drawPredSeries (per-pointer). Colours are the second accent, avoids a blend. */
internal fun DrawScope.drawHindsightFan(
    f: HindsightFrame,
    c: Int,
    absToPx: AbsToPx,
    valToPx: ValToPx,
    lineColor: Color,
    fanColor: Color,
    scratch: Path,
) {
    val span = f.span
    if (span < 2) return
    // From the ANCHOR, not the cursor: a forecast at 14:00 off a 12:00 reading starts at 12:00.
    val t0 = f.anchorMs[c]
    val step = f.stepMs
    val mBase = c * span
    val degenerate = f.degenerate[c]
    val stale = f.stale[c]

    // Outer band first. Skipped whole when degenerate; a stale one keeps its fan, thinner.
    if (!degenerate) {
        val dim = if (stale) 0.6f else 1f
        for (b in BANDS - 1 downTo 0) {
            val base = (b * f.cycles + c) * span
            val path = scratch.also { it.reset() }
            for (i in 0 until span) {
                val x = absToPx.of((t0 + i.toLong() * step).toDouble())
                val y = valToPx.of(f.hi[base + i])
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            for (i in span - 1 downTo 0) {
                path.lineTo(absToPx.of((t0 + i.toLong() * step).toDouble()), valToPx.of(f.lo[base + i]))
            }
            path.close()
            drawPath(path, fanColor.copy(alpha = (0.07f + 0.05f * (BANDS - 1 - b)) * dim))
        }
    }

    // Dashed and dimmed when not eligible: hindsight is the only place a dead forecast reads live.
    val notEligible = !f.eligible(c)
    var px = absToPx.of(t0.toDouble())
    var py = valToPx.of(f.median[mBase])
    for (i in 1 until span) {
        val nx = absToPx.of((t0 + i.toLong() * step).toDouble())
        val ny = valToPx.of(f.median[mBase + i])
        drawLine(
            lineColor.copy(alpha = if (notEligible) 0.5f else 0.95f),
            androidx.compose.ui.geometry.Offset(px, py),
            androidx.compose.ui.geometry.Offset(nx, ny),
            strokeWidth = 2.2f,
            cap = StrokeCap.Round,
            pathEffect = if (notEligible) HINDSIGHT_DEGENERATE_DASH else null,
        )
        px = nx; py = ny
    }
    // Withheld from an ineligible cycle: a ring reads as a claim about where the forecast arrived.
    if (!notEligible) {
        drawCircle(lineColor.copy(alpha = 0.9f), 3.2f, androidx.compose.ui.geometry.Offset(px, py), style = HINDSIGHT_END_RING)
    }
}

private val HINDSIGHT_DEGENERATE_DASH: androidx.compose.ui.graphics.PathEffect =
    androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(7f, 6f))

private val HINDSIGHT_END_RING = Stroke(width = 1.6f)
