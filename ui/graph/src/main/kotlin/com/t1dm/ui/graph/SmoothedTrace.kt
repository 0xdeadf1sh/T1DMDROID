package com.t1dm.ui.graph

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.UnitSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The SMOOTHED trace the model actually consumes (issue 13): the strictly-causal Savitzky-Golay
 * filtered BG series in mg/dL — i.e. the signal AFTER `t1dm-core::causal_smooth` (window 7, order 2,
 * clamps `[20,500]`) but BEFORE any Kovatchev risk transform. This is precisely the channel that is
 * then normalized and fed to the graph; overlaying it lets the raw sensor trace and the model's own
 * de-noised view of it be compared directly.
 *
 * Same immutable-primitive-array discipline as [GraphFrame]/[PredSeries]: the smooth is computed ONCE
 * off-thread (the native call is supplied as a lambda so `:ui:graph` keeps no JNI dependency), the
 * result carried here as absolute-ms + display-unit values, and the Canvas only maps it to pixels.
 * Values are projected into the active [UnitSpace] so the overlay lines up with the raw trace's axis;
 * the SOURCE is always the mg/dL smooth (the model input), not a risk-space quantity.
 */
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

/**
 * Build the smoothed overlay off the main thread. [smoothMgdl] is the causal SavGol smoother in
 * mg/dL (the `:core` native `causalSmooth` with clamps `[20,500]`); it is passed in so this module
 * never links the JNI seam. [kovatchevF] is only used to project into risk space when that unit is
 * active — the smooth itself is always computed in mg/dL.
 */
suspend fun smoothedTraceOf(
    readings: List<CgmReading>,
    unit: UnitSpace,
    smoothMgdl: (DoubleArray) -> DoubleArray,
    kovatchevF: ((Double) -> Double)? = null,
    maxGapMin: Float = 30f,
): SmoothedTrace = withContext(Dispatchers.Default) {
    buildSmoothedTrace(readings, unit, smoothMgdl, kovatchevF, maxGapMin)
}

/** Pure transform (no coroutines) — safe from a `@Preview`/test with an injected smoother. */
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
    if (sm.size != kept.size) return SmoothedTrace.EMPTY // defensive: smoother must be length-preserving

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
