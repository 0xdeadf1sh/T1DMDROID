package com.t1dm.ui.graph

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max

/** Pedometer count per grid bucket; NO_DATA isn't a measured zero; scale is frame's own max. */
class StepsFrame internal constructor(
    val gridStartMs: Long,
    val stepMs: Long,
    val steps: IntArray,
    val max: Int,
) {
    val size: Int get() = steps.size
    val isEmpty: Boolean get() = steps.isEmpty() || max <= 0

    /** Absolute epoch-ms at the LEFT edge of bucket [i]. */
    fun tsAt(i: Int): Long = gridStartMs + i.toLong() * stepMs

    /** Steps in bucket at ms; null off-grid/NO_DATA; measured 0 means the patient was still. */
    fun stepsAt(ms: Long): Int? {
        if (steps.isEmpty()) return null
        // floorDiv, not /: truncation toward zero reads bucket 0 an instant before the grid starts.
        val i = Math.floorDiv(ms - gridStartMs, stepMs).toInt()
        if (i !in steps.indices) return null
        return steps[i].takeIf { it != NO_DATA }
    }

    /** Bucket ms falls in, CLAMPED into the array, not a sentinel; bounds the cull only. */
    internal fun clampedIndexAt(ms: Double): Int {
        if (size == 0) return 0
        val d = (ms - gridStartMs.toDouble()) / stepMs.toDouble()
        return when {
            d <= 0.0 -> 0
            d >= (size - 1).toDouble() -> size - 1
            else -> d.toInt()
        }
    }

    companion object {
        /** Unmeasured bucket, distinct from 0 (a MEASUREMENT); negative so it never wins a peak. */
        const val NO_DATA: Int = -1

        val EMPTY = StepsFrame(0L, 300_000L, IntArray(0), 0)
    }
}

/** Off-thread; [steps] is already densified per bucket. */
suspend fun stepsFrameOf(
    steps: IntArray,
    gridStartMs: Long,
    stepMs: Long = 300_000L,
): StepsFrame = withContext(Dispatchers.Default) { buildStepsFrame(steps, gridStartMs, stepMs) }

/** Pure; safe from a `@Preview` or a test. */
fun buildStepsFrame(
    steps: IntArray,
    gridStartMs: Long,
    stepMs: Long = 300_000L,
): StepsFrame {
    if (steps.isEmpty()) return StepsFrame.EMPTY
    var max = 0
    for (v in steps) if (v > max) max = v
    return StepsFrame(gridStartMs, stepMs, steps, max)
}

/** Narrowest bar worth drawing, in dp. */
private const val MIN_BAR_DP: Float = 0.75f

/** Gap between adjacent bars, in dp. */
private const val BAR_GAP_DP: Float = 0.5f

/** Fraction of the band's height the tallest bar reaches. */
private const val BAR_HEADROOM: Float = 0.92f

/** A seam a host test can record geometry through; production sink is a per-composition scratch. */
internal interface StepBarSink {
    fun bar(left: Float, top: Float, right: Float, bottom: Float)
}

/** One reusable [Path] holding every visible bar; [reset] before each frame. */
internal class StepBarPath : StepBarSink {
    val path = Path()
    var count: Int = 0
        private set

    fun reset() {
        path.reset()
        count = 0
    }

    override fun bar(left: Float, top: Float, right: Float, bottom: Float) {
        // moveTo/lineTo rather than addRect: `Rect` would be one allocation per bar per frame.
        path.moveTo(left, bottom)
        path.lineTo(left, top)
        path.lineTo(right, top)
        path.lineTo(right, bottom)
        path.close()
        count++
    }
}

/** Step bars over [lo,hi] into sink, pure. Below MIN_BAR_DP buckets merge on MAX, not position. */
internal fun emitStepBars(
    frame: StepsFrame,
    absToPx: AbsToPx,
    bandTop: Float,
    plotBottom: Float,
    lo: Int,
    hi: Int,
    dpPx: Float,
    sink: StepBarSink,
) {
    if (frame.isEmpty || hi < lo) return
    // Pitch measured THROUGH the bars' own projection, so it cannot disagree with them.
    val firstX = absToPx.of(frame.tsAt(lo).toDouble())
    val pitch = absToPx.of(frame.tsAt(lo).toDouble() + frame.stepMs) - firstX
    if (pitch <= 0f) return

    // Floored at one PHYSICAL pixel, not MIN_BAR_DP: bound is pixel columns, not density-free.
    val minBarPx = max(MIN_BAR_DP * dpPx, 1f)
    val group = if (pitch >= minBarPx) 1 else ceil(minBarPx / pitch).toInt().coerceAtLeast(1)
    val slot = pitch * group
    // Never let the gap eat the bar: a dense window's pitch can be narrower than the gap itself.
    val barW = (slot - BAR_GAP_DP * dpPx).coerceAtLeast(slot * 0.5f)
    val availH = (plotBottom - bandTop).coerceAtLeast(1f) * BAR_HEADROOM
    val scale = availH / frame.max.toFloat()

    // Anchored on DATA not cull: grouping from lo would re-cut groups every pan, shimmering.
    var i = (lo / group) * group
    while (i <= hi) {
        val end = if (i + group - 1 < hi) i + group - 1 else hi
        var peak = 0
        var j = i
        while (j <= end) {
            val v = frame.steps[j]
            if (v > peak) peak = v
            j++
        }
        if (peak > 0) {
            val left = absToPx.of(frame.tsAt(i).toDouble())
            sink.bar(left, plotBottom - peak * scale, left + barW, plotBottom)
        }
        i = end + 1
    }
}

/** bars is caller's scratch, reset per frame; one drawPath call, nothing allocated here. */
internal fun DrawScope.drawStepsBars(
    frame: StepsFrame,
    absToPx: AbsToPx,
    bandTop: Float,
    plotBottom: Float,
    color: Color,
    viewStartMs: Double,
    viewSpanMs: Double,
    dpPx: Float,
    bars: StepBarPath,
) {
    if (frame.isEmpty) return
    bars.reset()
    emitStepBars(
        frame, absToPx, bandTop, plotBottom,
        lo = frame.clampedIndexAt(viewStartMs - viewSpanMs),
        hi = frame.clampedIndexAt(viewStartMs + 2.0 * viewSpanMs),
        dpPx = dpPx, sink = bars,
    )
    if (bars.count > 0) drawPath(bars.path, color)
}
