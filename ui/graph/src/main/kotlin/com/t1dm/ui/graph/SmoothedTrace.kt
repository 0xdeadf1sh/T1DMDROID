package com.t1dm.ui.graph

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.UnitSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** mg/dL after causal_smooth (order 2, clamps [20,500]), pre-Kovatchev, into active unit. */
class SmoothedTrace internal constructor(
    /** Absolute epoch-ms per point (ascending). */
    val tsMs: LongArray,
    /** Smoothed value already converted into the active unit. */
    val ys: FloatArray,
    /** True where a real dropout (> maxGapMin) follows point `i`; the polyline is cut there. */
    val breakAfter: BooleanArray,
) {
    val size: Int get() = tsMs.size
    val isEmpty: Boolean get() = tsMs.isEmpty()

    companion object {
        val EMPTY = SmoothedTrace(LongArray(0), FloatArray(0), BooleanArray(0))
    }
}

/** Index range drawn: visible window widened by one span each side. Empty if nothing in reach. */
internal fun SmoothedTrace.visibleRange(viewStartMs: Double, viewSpanMs: Double): IntRange {
    if (isEmpty) return IntRange.EMPTY
    val lo = lowerBoundLong(tsMs, kotlin.math.ceil(viewStartMs - viewSpanMs).toLong())
    val hi = lowerBoundLong(tsMs, kotlin.math.floor(viewStartMs + 2.0 * viewSpanMs).toLong() + 1L) - 1
    return lo..hi
}

/** Off main thread. [smoothMgdl] causal SavGol, mg/dL, so this module avoids the JNI seam. */
suspend fun smoothedTraceOf(
    readings: List<CgmReading>,
    unit: UnitSpace,
    smoothMgdl: (DoubleArray) -> DoubleArray,
    kovatchevF: ((Double) -> Double)? = null,
    maxGapMin: Float = 30f,
): SmoothedTrace = withContext(Dispatchers.Default) {
    buildSmoothedTrace(readings, unit, smoothMgdl, kovatchevF, maxGapMin)
}

/** Pure; safe from a `@Preview` or a test with an injected smoother. */
fun buildSmoothedTrace(
    readings: List<CgmReading>,
    unit: UnitSpace,
    smoothMgdl: (DoubleArray) -> DoubleArray,
    kovatchevF: ((Double) -> Double)? = null,
    maxGapMin: Float = 30f,
): SmoothedTrace {
    val kept = readings.asSequence()
        .filter { it.bgMgdl != null && it.flag != ReadingFlag.INVALID }
        .sortedBy { it.tsMs }
        .toList()
    if (kept.isEmpty()) return SmoothedTrace.EMPTY

    val raw = DoubleArray(kept.size) { kept[it].bgMgdl!!.toDouble() }
    val sm = smoothMgdl(raw)
    if (sm.size != kept.size) return SmoothedTrace.EMPTY // fail closed rather than draw misaligned

    val ts = LongArray(kept.size) { kept[it].tsMs }
    val ys = FloatArray(kept.size) { i ->
        val v = sm[i]
        (when (unit) {
            UnitSpace.MgDl -> v
            UnitSpace.MmolL -> v / 18.0182
            UnitSpace.Kovatchev -> kovatchevF?.invoke(v) ?: v
        }).toFloat()
    }
    val gapMs = maxGapMin.toDouble() * 60_000.0
    val breakAfter = BooleanArray(kept.size)
    for (i in 0 until kept.size - 1) breakAfter[i] = (ts[i + 1] - ts[i]) > gapMs
    return SmoothedTrace(ts, ys, breakAfter)
}
