package com.t1dm.ui.graph

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.UnitSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Stored forecasts read back, display-only, never re-forecast; step 0 is the anchor, zero fan. */
class HindsightFrame internal constructor(
    /** Ascending issue instants (stored made_at); the only array the cursor is matched against. */
    val madeMs: LongArray,
    /** Measured reading the forecast grew from, x of step 0; behind madeMs, repeats over gap. */
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
    /** Anchor already past freshness gate when issued (§3.6-D); must not redraw as if driven. */
    val stale: BooleanArray,
) {
    val cycles: Int get() = madeMs.size
    val isEmpty: Boolean get() = madeMs.isEmpty()

    /** Cycle issued nearest ms, within half cadenceMs; -1 between cycles, else drags over gap. */
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

/** rows ascending by cycleTsMs; calibrateFans applies §8.4 to the sweep, null keeps the raw fan. */
suspend fun hindsightFrameOf(
    rows: List<ModelPrediction>,
    unit: UnitSpace = UnitSpace.MgDl,
    kovatchevF: ((Double) -> Double)? = null,
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

    // Both passes must admit exactly the same rows, else the second writes off the first's arrays.
    fun admits(p: ModelPrediction): Boolean {
        val n = p.horizonSteps
        return n != 0 && p.nQuantiles >= BANDS * 2 + 1 && p.bandsMgdl.size == n * p.nQuantiles &&
            n + 1 == span && p.stepMs == stepMs && p.nQuantiles == nq
    }

    // §8.4 in one crossing, all or none: a disagreeing size leaves every fan raw (SPEC §6.2).
    val fanLen = (span - 1) * nq
    val calibrated: List<Double>? = calibrateFans?.let { calibrate ->
        // Lazy: on the common path there is no stored correction, so the batch is never flattened.
        val build = {
            val flat = ArrayList<Double>(kept * fanLen)
            for (p in rows) if (admits(p)) flat.addAll(p.bandsMgdl)
            flat as List<Double>
        }
        calibrate(build, span - 1, nq)?.takeIf { it.size == kept * fanLen }
    }

    val madeMs = LongArray(kept)
    val anchorMs = LongArray(kept)
    val median = FloatArray(kept * span)
    val lo = FloatArray(BANDS * kept * span)
    val hi = FloatArray(BANDS * kept * span)
    val degenerate = BooleanArray(kept)
    val stale = BooleanArray(kept)

    var c = 0
    var a = 0 // index among ADMITTED rows, which `calibrated` is laid out by; `c` skips refusals
    for (p in rows) {
        if (!admits(p)) continue
        // A view, not a copy: `buildPredSeries` only indexes into it.
        val fan = calibrated?.subList(a * fanLen, (a + 1) * fanLen)
        a++
        val s = buildPredSeries(p, unit, kovatchevF, fan) ?: continue
        if (s.size != span) continue
        madeMs[c] = p.cycleTsMs
        anchorMs[c] = p.anchorTsMs
        degenerate[c] = s.degenerate
        stale[c] = s.stale
        System.arraycopy(s.median, 0, median, c * span, span)
        for (b in 0 until BANDS) {
            val base = (b * kept + c) * span
            System.arraycopy(s.lo[b], 0, lo, base, span)
            System.arraycopy(s.hi[b], 0, hi, base, span)
        }
        c++
    }
    // A row that passed shape but was refused leaves a tail hole; trim it, not draw epoch-0 zeroes.
    if (c == 0) return@withContext null
    if (c == kept) {
        HindsightFrame(
            madeMs, anchorMs, stepMs, span, cadenceOf(madeMs, stepMs),
            median, lo, hi, degenerate, stale,
        )
    } else {
        val lo2 = FloatArray(BANDS * c * span)
        val hi2 = FloatArray(BANDS * c * span)
        for (b in 0 until BANDS) {
            System.arraycopy(lo, (b * kept) * span, lo2, (b * c) * span, c * span)
            System.arraycopy(hi, (b * kept) * span, hi2, (b * c) * span, c * span)
        }
        val trimmedMade = madeMs.copyOf(c)
        HindsightFrame(
            trimmedMade, anchorMs.copyOf(c), stepMs, span, cadenceOf(trimmedMade, stepMs),
            median.copyOf(c * span), lo2, hi2, degenerate.copyOf(c), stale.copyOf(c),
        )
    }
}

/** Median not mean/min: holes drag a mean up, a tight pair pulls a min down; falls to stepMs. */
private fun cadenceOf(madeMs: LongArray, stepMs: Long): Long {
    if (madeMs.size < 2) return stepMs
    val gaps = LongArray(madeMs.size - 1) { madeMs[it + 1] - madeMs[it] }
    gaps.sort()
    val med = gaps[gaps.size / 2]
    return if (med > 0L) med else stepMs
}

/** Own winding, not drawPredSeries (per-pointer sample); τ pairing baked into f at build. */
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

    // Dashed/dimmed when not eligible: only place a no-op forecast could otherwise look real.
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
