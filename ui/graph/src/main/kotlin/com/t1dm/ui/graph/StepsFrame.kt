package com.t1dm.ui.graph

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max

/**
 * The STEPS overlay's render model: the pedometer count in each 5-min grid bucket, drawn as vertical
 * bars in the same bottom band the carb/insulin curves occupy.
 *
 * Same off-thread, immutable-primitive-array discipline as [CurveOverlayFrame]: `:app` reads the
 * sparse `sample.steps` buckets out of Room and densifies them into one [IntArray] before this is
 * built, so the Canvas never touches a Room row, a boxed `Int?`, or a list.
 *
 * Coordinates are grid-absolute exactly as [CurveOverlayFrame]'s are — bucket `i` spans
 * `[gridStartMs + i·stepMs, +stepMs)` — so the bars line up with the BG viewport's projection and a
 * pan or a zoom never forces a rebuild.
 *
 * The scale is the frame's own [max], not a global one: steps-per-five-minutes has no meaningful
 * absolute ceiling, and a window whose busiest bucket is a walk to the kitchen should read as
 * legibly as one containing a run.
 */
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

    /**
     * The bucket [ms] falls in, CLAMPED into the array rather than answered as a sentinel — so a
     * viewport wholly before or after the grid still yields a legal (degenerate) range. Used only to
     * bound the draw's viewport cull, exactly as [CurveOverlayFrame.clampedIndexAt] is.
     */
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
        val EMPTY = StepsFrame(0L, 300_000L, IntArray(0), 0)
    }
}

/** Build the steps frame off-thread from the densified per-bucket counts. */
suspend fun stepsFrameOf(
    steps: IntArray,
    gridStartMs: Long,
    stepMs: Long = 300_000L,
): StepsFrame = withContext(Dispatchers.Default) { buildStepsFrame(steps, gridStartMs, stepMs) }

/** Pure transform (no coroutines) — safe from a `@Preview`/test. */
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

/** Narrowest bar worth drawing, in dp. Below this a bar is a hairline the eye reads as noise. */
private const val MIN_BAR_DP: Float = 0.75f

/** The gap between adjacent bars, in dp, so a run of busy buckets reads as bars and not as a block. */
private const val BAR_GAP_DP: Float = 0.5f

/** Fraction of the band's height the tallest bar reaches, matching the curve overlay's headroom. */
private const val BAR_HEADROOM: Float = 0.92f

/**
 * Where one band's bar rectangles go.
 *
 * The same seam [CurvePathSink] is, and for the same reason: it gives the geometry something a host
 * test can record through, while the drawing itself stays unreachable from a unit test. The
 * production implementation is a scratch object the composition holds across frames, so it costs
 * nothing at runtime.
 */
internal interface StepBarSink {
    fun bar(left: Float, top: Float, right: Float, bottom: Float)
}

/** The production sink: one reusable [Path] holding every visible bar, [reset] before each frame. */
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

/**
 * The visible step bars over `[lo, hi]`, emitted into [sink]. Pure — no Compose, no DrawScope.
 *
 * **The emitted count is bounded by the plot's width, not by the history's length.** `[lo, hi]` is
 * already the viewport widened by a full span on each side (the caller's cull, the same one
 * [drawCurveOverlay] uses). On top of that, when the bucket pitch falls below [MIN_BAR_DP] adjacent
 * buckets are merged into one bar carrying their MAX — the reduction [buildGraphFrame] decimates the
 * BG trace with, chosen here for the same reason: at a pinch wide enough to need it, a mean would
 * flatten the peak of a walk into the sedentary hours either side of it, while the max keeps the walk
 * visible. Without the merge a fortnight's grid behind a wide pinch emits some four thousand
 * sub-pixel bars, every frame, at the panel's refresh rate.
 *
 * A merged bar spans its whole group, so merging changes a bar's width and never its position.
 */
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
    // The bucket pitch, measured THROUGH the projection the bars are drawn with rather than
    // re-derived from the viewport — one extra call, and it cannot disagree with the geometry.
    val firstX = absToPx.of(frame.tsAt(lo).toDouble())
    val pitch = absToPx.of(frame.tsAt(lo).toDouble() + frame.stepMs) - firstX
    if (pitch <= 0f) return

    // Floored at one PHYSICAL pixel, not just at [MIN_BAR_DP]: below 1 dp/px the dp figure alone
    // would still admit sub-pixel bars, and the bound this merge exists to give — never more bars
    // than there are pixel columns to put them in — is a pixel bound, not a density-independent one.
    val minBarPx = max(MIN_BAR_DP * dpPx, 1f)
    val group = if (pitch >= minBarPx) 1 else ceil(minBarPx / pitch).toInt().coerceAtLeast(1)
    val slot = pitch * group
    // Never let the gap eat the bar: on a dense window the pitch can be narrower than the gap itself.
    val barW = (slot - BAR_GAP_DP * dpPx).coerceAtLeast(slot * 0.5f)
    val availH = (plotBottom - bandTop).coerceAtLeast(1f) * BAR_HEADROOM
    val scale = availH / frame.max.toFloat()

    // Anchor the groups on the DATA, not on the cull. `lo` moves with the viewport, so grouping from
    // it would re-cut the groups on every pan — a bar's width and the buckets it covers would change
    // as the window slid under it, and a merged band would visibly shimmer while the finger moved.
    // Snapping the start down to a multiple of `group` makes the grouping a property of the frame, so
    // a pan slides the same bars across the screen. Starting up to `group - 1` buckets before `lo` is
    // safe: `clampedIndexAt` never returns a negative, and those buckets are inside both the array and
    // the already-generous cull.
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

/**
 * Draw the step bars into the bottom band of the plot, under the carb/insulin curves and under the BG
 * trace. [absToPx] maps absolute epoch-ms to x — the same projection the trace and the curve overlay
 * use, so the bars cannot drift from either.
 *
 * **Nothing is allocated here.** [bars] is the caller's scratch, reset per frame; the whole band is
 * issued as a SINGLE `drawPath` rather than one `drawRect` per bucket, and the colour arrives already
 * resolved so no alpha is derived inside the loop. The geometry, the cull and the merge are
 * [emitStepBars]'.
 */
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
