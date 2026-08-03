package com.t1dm.ui.graph

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.UnitSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An immutable, primitive-array snapshot of a glucose series ready to draw (
 * Phase 1 / §3.4). All heavy work — unit-transform, decimation, gap detection — is done ONCE, off
 * the main thread, in [buildGraphFrame]; [com.t1dm.ui.graph.GlucoseGraph] only maps these arrays to
 * pixels and paints. No boxing, no `List<CgmReading>` retained: the Canvas never touches domain
 * types.
 *
 * Coordinates are stored screen-independent so pan/zoom/scrub are pure viewport transforms and never
 * force a rebuild:
 *  - [xs] — minutes since [t0Ms] (ascending). Minutes keep the magnitudes small enough for `Float`
 *    to stay exact across a multi-day window (a week = 10 080 min ≪ 2^24).
 *  - [ys] — the glucose value already converted into [unit].
 *  - [flags] — one of [FLAG_MEASURED] / [FLAG_INTERPOLATED] / [FLAG_WARMUP], so the renderer can
 *    make fabricated and warm-up points visually distinct without re-deriving provenance.
 *  - [breakAfter] — true where a true dropout (no interpolation) follows point `i`; the polyline is
 *    cut there rather than bridging the gap with a fictitious straight line.
 */
class GraphFrame internal constructor(
    val t0Ms: Long,
    val tzOffsetMin: Int,
    val unit: UnitSpace,
    val xs: FloatArray,
    val ys: FloatArray,
    val flags: IntArray,
    val breakAfter: BooleanArray,
    val dataMinY: Float,
    val dataMaxY: Float,
) {
    val size: Int get() = xs.size
    val isEmpty: Boolean get() = xs.isEmpty()

    /** Absolute epoch-ms of point [i] (reconstituted from the minute offset). */
    fun absMs(i: Int): Double = t0Ms + xs[i].toDouble() * 60_000.0

    /** Index of the point nearest to absolute [ms]; -1 when empty. Used by the scrub cursor. */
    fun nearestIndex(ms: Double): Int {
        if (xs.isEmpty()) return -1
        val target = ((ms - t0Ms) / 60_000.0).toFloat()
        if (target <= xs[0]) return 0
        if (target >= xs[xs.size - 1]) return xs.size - 1
        var lo = 0
        var hi = xs.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) ushr 1
            if (xs[mid] <= target) lo = mid else hi = mid
        }
        return if (target - xs[lo] <= xs[hi] - target) lo else hi
    }

    companion object {
        const val FLAG_MEASURED = 0
        const val FLAG_INTERPOLATED = 1
        const val FLAG_WARMUP = 2

        val EMPTY = GraphFrame(
            t0Ms = 0L, tzOffsetMin = 0, unit = UnitSpace.MgDl,
            xs = FloatArray(0), ys = FloatArray(0), flags = IntArray(0),
            breakAfter = BooleanArray(0), dataMinY = 0f, dataMaxY = 0f,
        )
    }
}

/** Suspend wrapper: build a [GraphFrame] off the main thread (§2.3, GraphFrame row). */
suspend fun graphFrameOf(
    readings: List<CgmReading>,
    unit: UnitSpace = UnitSpace.MgDl,
    maxGapMin: Float = 30f,
    maxPoints: Int = 6000,
    kovatchevF: ((Double) -> Double)? = null,
): GraphFrame = withContext(Dispatchers.Default) {
    buildGraphFrame(readings, unit, maxGapMin, maxPoints, kovatchevF)
}

/**
 * Pure CPU transform (no coroutines) — safe to call directly from a `@Preview` or a test. Drops
 * value-less readings (INVALID / null bg), sorts by time, converts into [unit], marks genuine
 * dropout breaks on the RAW grid, then decimates with a min/max envelope if the count exceeds
 * [maxPoints] — carrying only the real breaks through so decimation never fabricates a gap.
 *
 * Kovatchev raw-space needs the native `f(g)` (§3.4); pass it as [kovatchevF]. When
 * absent, [UnitSpace.Kovatchev] falls back to mg/dL rather than fabricating a curve.
 */
fun buildGraphFrame(
    readings: List<CgmReading>,
    unit: UnitSpace = UnitSpace.MgDl,
    maxGapMin: Float = 30f,
    maxPoints: Int = 6000,
    kovatchevF: ((Double) -> Double)? = null,
): GraphFrame {
    val kept = readings.asSequence()
        .filter { it.bgMgdl != null && it.flag != ReadingFlag.INVALID }
        .sortedBy { it.tsMs }
        .toList()
    if (kept.isEmpty()) return GraphFrame.EMPTY

    val t0 = kept.first().tsMs
    val n = kept.size
    var xs = FloatArray(n)
    var ys = FloatArray(n)
    var flags = IntArray(n)
    for (i in 0 until n) {
        val r = kept[i]
        xs[i] = ((r.tsMs - t0).toDouble() / 60_000.0).toFloat()
        ys[i] = convert(r.bgMgdl!!.toDouble(), unit, kovatchevF).toFloat()
        flags[i] = when {
            r.flag == ReadingFlag.WARMUP -> GraphFrame.FLAG_WARMUP
            r.provenance == ReadingProvenance.INTERPOLATED -> GraphFrame.FLAG_INTERPOLATED
            else -> GraphFrame.FLAG_MEASURED
        }
    }

    // Genuine dropout breaks live on the RAW grid: decimation drops points and can widen apparent
    // spacing past maxGapMin with no real gap, so detect the breaks here and carry only these through.
    // breakPrefix[j] = number of true entries in rawBreak[0, j) — an O(1) "is there a real dropout in
    // this index range?" test for the decimated path below.
    val rawBreak = BooleanArray(n) { i -> i < n - 1 && (xs[i + 1] - xs[i]) > maxGapMin }
    val breakPrefix = IntArray(n + 1)
    for (j in 0 until n) breakPrefix[j + 1] = breakPrefix[j] + if (rawBreak[j]) 1 else 0

    var srcIdx: IntArray? = null
    if (n > maxPoints) {
        val d = decimateMinMax(xs, ys, flags, maxPoints)
        xs = d.xs; ys = d.ys; flags = d.flags; srcIdx = d.srcIdx
    }

    val m = xs.size
    val breakAfter = BooleanArray(m)
    var minY = Float.POSITIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    for (i in 0 until m) {
        if (ys[i] < minY) minY = ys[i]
        if (ys[i] > maxY) maxY = ys[i]
    }
    if (srcIdx == null) {
        // No decimation: the kept points ARE the raw grid, so carry rawBreak through verbatim.
        for (i in 0 until m - 1) breakAfter[i] = rawBreak[i]
    } else {
        // Decimated: a segment breaks iff a genuine raw dropout falls between its two kept source
        // indices (breakPrefix delta > 0), never merely because decimation spaced the points out.
        for (k in 0 until m - 1) breakAfter[k] = breakPrefix[srcIdx[k + 1]] - breakPrefix[srcIdx[k]] > 0
    }
    // The NEWEST reading's offset, not the oldest. The dashboard observes the whole store, so `first()`
    // froze the axis on whatever offset was in force when the record began: after a DST transition or a
    // move, every tick and the date row under it sat an hour off wall-clock for the life of the history.
    // The last reading is the one whose offset the phone is actually keeping now.
    return GraphFrame(t0, kept.last().tzOffsetMin, unit, xs, ys, flags, breakAfter, minY, maxY)
}

private fun convert(mgdl: Double, unit: UnitSpace, kovatchevF: ((Double) -> Double)?): Double =
    when (unit) {
        UnitSpace.MgDl -> mgdl
        UnitSpace.MmolL -> mgdl / 18.0182
        UnitSpace.Kovatchev -> kovatchevF?.invoke(mgdl) ?: mgdl
    }

/** A min/max-decimation result: the reduced parallel arrays plus [srcIdx], the ORIGINAL pre-
 *  decimation index of every kept point, so genuine raw dropouts can be re-mapped onto the reduced
 *  polyline (a break must reflect a real gap, not bucket spacing). */
private class Decimated(
    val xs: FloatArray, val ys: FloatArray, val flags: IntArray, val srcIdx: IntArray,
)

/**
 * Min/max bucket decimation: split the interior into ~[maxPoints]/2 buckets and keep each bucket's
 * lowest and highest sample (in time order). Preserves the visual envelope — spikes and nadirs
 * survive — where naive striding would drop them. Endpoints are always retained, and every kept
 * point records its source index (see [Decimated.srcIdx]).
 */
private fun decimateMinMax(
    xs: FloatArray, ys: FloatArray, flags: IntArray, maxPoints: Int,
): Decimated {
    val n = xs.size
    val buckets = (maxPoints / 2).coerceAtLeast(1)
    val ox = ArrayList<Float>(maxPoints + 2)
    val oy = ArrayList<Float>(maxPoints + 2)
    val of = ArrayList<Int>(maxPoints + 2)
    val oi = ArrayList<Int>(maxPoints + 2)

    fun push(i: Int) { ox.add(xs[i]); oy.add(ys[i]); of.add(flags[i]); oi.add(i) }

    push(0)
    val interior = (n - 2).toDouble()
    val step = interior / buckets
    for (b in 0 until buckets) {
        val lo = 1 + Math.floor(b * step).toInt()
        val hi = (1 + Math.floor((b + 1) * step).toInt()).coerceAtMost(n - 1)
        if (lo >= hi) continue
        var minI = lo
        var maxI = lo
        for (i in lo until hi) {
            if (ys[i] < ys[minI]) minI = i
            if (ys[i] > ys[maxI]) maxI = i
        }
        val a = minOf(minI, maxI)
        val c = maxOf(minI, maxI)
        push(a)
        if (c != a) push(c)
    }
    push(n - 1)
    return Decimated(ox.toFloatArray(), oy.toFloatArray(), of.toIntArray(), oi.toIntArray())
}
