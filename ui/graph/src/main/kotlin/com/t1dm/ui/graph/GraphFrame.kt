package com.t1dm.ui.graph

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.UnitSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Screen-independent snapshot, off main thread; xs minutes since t0Ms, breakAfter cuts gaps. */
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

    /** Epoch-ms of point [i]. */
    fun absMs(i: Int): Double = t0Ms + xs[i].toDouble() * 60_000.0

    /** Nearest point to absolute [ms]; -1 when empty. */
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

        /** Own flag: pixel-identical to sensor signal, markers suppress past 6h, line carries. */
        const val FLAG_RECONSTRUCTED = 3

        val EMPTY = GraphFrame(
            t0Ms = 0L, tzOffsetMin = 0, unit = UnitSpace.MgDl,
            xs = FloatArray(0), ys = FloatArray(0), flags = IntArray(0),
            breakAfter = BooleanArray(0), dataMinY = 0f, dataMaxY = 0f,
        )
    }
}

suspend fun graphFrameOf(
    readings: List<CgmReading>,
    unit: UnitSpace = UnitSpace.MgDl,
    maxGapMin: Float = 30f,
    maxPoints: Int = 6000,
    kovatchevF: ((Double) -> Double)? = null,
): GraphFrame = withContext(Dispatchers.Default) {
    buildGraphFrame(readings, unit, maxGapMin, maxPoints, kovatchevF)
}

/** Pure CPU, callable from @Preview or a test; missing kovatchevF falls to mg/dL, never fakes. */
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
            r.provenance == ReadingProvenance.RECONSTRUCTED -> GraphFrame.FLAG_RECONSTRUCTED
            else -> GraphFrame.FLAG_MEASURED
        }
    }

    // Breaks belong to the RAW grid: decimation can widen spacing past maxGapMin with no real gap.
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
        for (i in 0 until m - 1) breakAfter[i] = rawBreak[i]
    } else {
        // Breaks iff a raw dropout falls between the two kept source indices, not from spacing.
        for (k in 0 until m - 1) breakAfter[k] = breakPrefix[srcIdx[k + 1]] - breakPrefix[srcIdx[k]] > 0
    }
    // Newest offset, not oldest: oldest freezes axis on record start offset, wrong after DST/move.
    return GraphFrame(t0, kept.last().tzOffsetMin, unit, xs, ys, flags, breakAfter, minY, maxY)
}

internal fun convert(mgdl: Double, unit: UnitSpace, kovatchevF: ((Double) -> Double)?): Double =
    when (unit) {
        UnitSpace.MgDl -> mgdl
        UnitSpace.MmolL -> mgdl / 18.0182
        UnitSpace.Kovatchev -> kovatchevF?.invoke(mgdl) ?: mgdl
    }

/** [srcIdx] is each kept point's pre-decimation index, for re-mapping real dropouts. */
private class Decimated(
    val xs: FloatArray, val ys: FloatArray, val flags: IntArray, val srcIdx: IntArray,
)

/** Keeps each bucket's min/max in time order, so spikes/nadirs survive striding; endpoints kept. */
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
