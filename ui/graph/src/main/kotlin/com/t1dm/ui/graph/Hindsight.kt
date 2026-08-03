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

/**
 * Every forecast the selected model issued over a window, laid out so the scrub can sweep them.
 *
 * The panel's forecast overlay draws what the model believes NOW; this draws what it believed THEN.
 * Long-press and drag, and the fan issued at the cursor's own cycle is drawn forward over the trace
 * that actually happened — so a whole day's forecasts can be swept past the truth in one gesture.
 * DISPLAY-ONLY, exactly as the rolled forecast is: nothing here reaches an alarm, a rail, a
 * calculator or the wire, and these rows are the stored ones read back, never a re-forecast.
 *
 * **Why the layout is flat.** A sweep re-anchors on every pointer sample, and this panel is drawn on
 * a 165 Hz display, so the cursor→fan lookup sits in the hottest path the graph has. Held as a list
 * of per-cycle objects it would mean a binary search over boxed keys and seven `FloatArray`s of
 * indirection per frame; held as six flat primitive arrays it is one `lowerBoundLong` and pure index
 * arithmetic, allocating nothing between the build and the draw. Cycle `c`'s step `i` is
 * [median]`[c*span + i]`, and band `b` of it is [lo]/[hi]`[(b*nCycles + c)*span + i]` — band-major
 * over cycles so one band of one cycle is contiguous, which is the order [drawHindsightFan] winds
 * its path in.
 *
 * The block is RECTANGULAR: [span] and [stepMs] are fixed by the first accepted row and any row
 * disagreeing is dropped rather than reshaped, since a forecast at another horizon is not a shorter
 * version of this one. Step 0 of every cycle is the ANCHOR — the measured BG the forecast grew out
 * of, with a zero-width fan — mirroring what [buildPredSeries] prepends.
 *
 * **A cycle has TWO instants and they are not interchangeable.** [madeMs] is when the forecast was
 * issued; [anchorMs] is the measured reading it was issued FROM. They coincide while the CGM is
 * delivering, and they come apart exactly when it is not: the anchor is the newest MEASURED reading,
 * so through a dropout it freezes while cycles keep firing. The cursor is therefore matched against
 * [madeMs] — "the forecast issued at the instant under my thumb" is what the sweep means — and the
 * fan is DRAWN from [anchorMs], because that is where its step 0 sits. Keying both on the anchor
 * collapses every cycle of a dropout onto one instant: they become mutually unreachable, and their
 * zero gaps drag the measured cadence to zero with them.
 */
class HindsightFrame internal constructor(
    /** Ascending, strictly increasing ISSUE instants (`ModelPrediction.cycleTsMs`, the stored
     *  `made_at`); the binary-search key, and the only array the cursor is matched against. */
    val madeMs: LongArray,
    /** Per cycle: the measured reading the forecast grew out of, and so the x of its step 0. Equal to
     *  [madeMs] while the CGM is live; behind it, and repeating, across a dropout. */
    val anchorMs: LongArray,
    val stepMs: Long,
    /** Steps per cycle INCLUDING the prepended anchor point, i.e. `horizonSteps + 1`. */
    val span: Int,
    /** The MEDIAN gap between consecutive ISSUE instants — the cadence inference actually ran at over
     *  this window, measured rather than assumed. Half of it is the cursor's catchment. */
    val cadenceMs: Long,
    val median: FloatArray,
    val lo: FloatArray,
    val hi: FloatArray,
    /** Per cycle: the forecast was not `OK` when it was made, so it is drawn fan-less. */
    val degenerate: BooleanArray,
    /** Per cycle: its anchor was already past the freshness gate when it was issued (§3.6-D), so it
     *  was never eligible to drive anything and must not be redrawn as though it had been. */
    val stale: BooleanArray,
) {
    val cycles: Int get() = madeMs.size
    val isEmpty: Boolean get() = madeMs.isEmpty()

    /**
     * The cycle ISSUED nearest [ms], within half [cadenceMs], or −1 when the cursor falls between
     * cycles.
     *
     * The bound is the whole of what makes a sweep honest, and it is measured rather than fixed
     * because the cadence is not: adaptive mode forecasts on every reading and timed mode on a
     * configurable period, so half the observed median gap tiles either one seamlessly. Where
     * inference genuinely did not run — warmup, thermal pause, the phone off — the gap exceeds that
     * and the sweep goes blank over it, which is the truth. An unbounded nearest would instead pin
     * the last fan before the hole and drag it under the finger for as long as the hole lasts, and it
     * would look exactly like a forecast that had been issued there.
     */
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
}

/** How many bands [HindsightFrame] carries per cycle — the same three nested pairs the live overlay
 *  draws, since both are windings of one fan and the pairing is fixed in [buildPredSeries]. */
private const val BANDS = 3

/**
 * Flatten the stored rows into a [HindsightFrame], off-thread.
 *
 * The τ-column pairing and the unit conversion are NOT redone here: each row goes through
 * [buildPredSeries] — the one place that knows which quantile columns nest into which band and how a
 * value reaches the active unit — and only the flattening is this function's own. The intermediate
 * series are garbage the moment they are copied out, which is why this runs once per window rather
 * than per frame.
 *
 * [rows] must be ascending by `cycleTsMs` (the stored `made_at`); the DAO orders them so, and
 * re-sorting a day of cycles to re-discover that is work the query already did.
 */
suspend fun hindsightFrameOf(
    rows: List<ModelPrediction>,
    unit: UnitSpace = UnitSpace.MgDl,
    kovatchevF: ((Double) -> Double)? = null,
): HindsightFrame? = withContext(Dispatchers.Default) {
    if (rows.isEmpty()) return@withContext null

    // Two passes rather than growable lists: the first fixes the block's shape and counts the rows
    // that fit it, so the second writes straight into arrays sized exactly once. A day of cycles is
    // ~288 rows of ~175 floats, and growing six arrays through that is six reallocation chains.
    var span = 0
    var stepMs = 0L
    var kept = 0
    for (p in rows) {
        val n = p.horizonSteps
        if (n == 0 || p.nQuantiles < BANDS * 2 + 1 || p.bandsMgdl.size != n * p.nQuantiles) continue
        if (span == 0) { span = n + 1; stepMs = p.stepMs }
        if (n + 1 != span || p.stepMs != stepMs) continue
        kept++
    }
    if (kept == 0) return@withContext null

    // The two passes MUST admit exactly the same rows: the second writes at `c` into arrays sized by
    // the first, so a row the second let through and the first did not would run off their ends.
    fun admits(p: ModelPrediction): Boolean {
        val n = p.horizonSteps
        return n != 0 && p.nQuantiles >= BANDS * 2 + 1 && p.bandsMgdl.size == n * p.nQuantiles &&
            n + 1 == span && p.stepMs == stepMs
    }

    val madeMs = LongArray(kept)
    val anchorMs = LongArray(kept)
    val median = FloatArray(kept * span)
    val lo = FloatArray(BANDS * kept * span)
    val hi = FloatArray(BANDS * kept * span)
    val degenerate = BooleanArray(kept)
    val stale = BooleanArray(kept)

    var c = 0
    for (p in rows) {
        if (!admits(p)) continue
        val s = buildPredSeries(p, unit, kovatchevF) ?: continue
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
    // A row that passed the shape check but that [buildPredSeries] refused leaves a hole at the tail;
    // trim rather than draw a cycle of zeroes at epoch 0.
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

/**
 * The MEDIAN gap between consecutive ISSUE instants, falling back to [stepMs].
 *
 * The median rather than the mean or the minimum: a window almost always contains a few holes where
 * inference paused, and a mean would be dragged up by them until the catchment swallowed the holes
 * themselves, while a minimum would be pulled down by one tight pair until the sweep blinked between
 * every cycle. The median is whichever cadence actually prevailed.
 *
 * The fallback is [stepMs] and NOT a clamp to some small positive number. A non-positive median means
 * the keys are not telling cycles apart, and a 1 ms cadence would give a half-millisecond catchment —
 * which no finger can hit, so the whole sweep would go blank while looking exactly like "no forecasts
 * stored here". Measured on `made_at` this should be unreachable, since the store admits one row per
 * model per grid slot; it is guarded anyway, because the failure is silent and total.
 */
private fun cadenceOf(madeMs: LongArray, stepMs: Long): Long {
    if (madeMs.size < 2) return stepMs
    val gaps = LongArray(madeMs.size - 1) { madeMs[it + 1] - madeMs[it] }
    gaps.sort()
    val med = gaps[gaps.size / 2]
    return if (med > 0L) med else stepMs
}

/**
 * Draw cycle [c]'s fan and median — the forecast issued at that instant, over the trace that
 * followed it.
 *
 * **Why this does not call [drawPredSeries].** That routine takes a [PredSeries], and a sweep would
 * have to build one per pointer sample: seven `FloatArray`s and a wrapper, allocated and discarded
 * at pointer rate, for a fan whose numbers are already resident in [f]. The winding below is
 * therefore its own, reading the flat arrays directly and allocating nothing but the reuse of
 * [scratch]. The duplication is the price of that, and is deliberate — but it is duplication of a
 * LOOP, not of a fact: which τ columns nest into which band was decided in [buildPredSeries] and
 * baked into [f] when the frame was built, so there is no second copy of it to drift.
 *
 * Colours are the caller's, and are the SECOND accent rather than the live overlay's: two fans in
 * one hue is exactly the blend this feature exists to avoid.
 */
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
    // The fan is drawn from the ANCHOR, not from the cursor: step 0 is the measured reading the
    // forecast grew out of, so this is what makes the ghost sprout from the trace. Across a dropout
    // the anchor sits behind the issue instant, and the gap between the two is itself the picture —
    // a forecast made at 14:00 off a 12:00 reading should visibly start at 12:00.
    val t0 = f.anchorMs[c]
    val step = f.stepMs
    val mBase = c * span
    val degenerate = f.degenerate[c]
    val stale = f.stale[c]

    // The fan, outer band first so the inner ones stack over it. Skipped whole when the forecast was
    // degenerate the day it was made: a collapsed or misordered band must not read as confidence in
    // hindsight any more than it may live. A stale one keeps its fan but wears it thinner, mirroring
    // what the live overlay does with the same flag.
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

    // The median. Solid and full-width for a forecast that was actually in force — it is an assertion
    // the model once made, not the tentative thing a dash would suggest. Dashed and dimmed for one
    // that was NOT: a §3.6-D stale forecast was already past its freshness gate when it was issued
    // and was never eligible to drive anything, and hindsight is precisely where that would otherwise
    // be invisible — the live overlay flags it while it is current, and nothing else ever would.
    val notEligible = degenerate || stale
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
    // A ring at the horizon end, so how far forward the swept forecast reached stays legible when it
    // runs alongside the live fan. Withheld from a forecast that was not eligible, for the same reason
    // its line is dashed: the live overlay marks no endpoint on one either, and a ring reads as a
    // claim about where the forecast arrived.
    if (!notEligible) {
        drawCircle(lineColor.copy(alpha = 0.9f), 3.2f, androidx.compose.ui.geometry.Offset(px, py), style = HINDSIGHT_END_RING)
    }
}

private val HINDSIGHT_DEGENERATE_DASH: androidx.compose.ui.graphics.PathEffect =
    androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(7f, 6f))

private val HINDSIGHT_END_RING = Stroke(width = 1.6f)
