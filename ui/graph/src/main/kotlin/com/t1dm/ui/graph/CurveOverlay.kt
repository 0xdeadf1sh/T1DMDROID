package com.t1dm.ui.graph

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.t1dm.core.model.ModelPrediction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bucket i spans [gridStart+i*step,+step); grams/units incommensurable, own-peak scaled. */
class CurveOverlayFrame internal constructor(
    val gridStartMs: Long,
    val stepMs: Long,
    val carb: FloatArray,       // grams-per-step Ra
    val insulin: FloatArray,    // units-per-step action, bolus + basal COMBINED
    val carbMax: Float,
    val insulinMax: Float,
    // Rendering only: model consumes COMBINED insulin; basal ~1/300 of bolus peak, this scale.
    val basal: FloatArray = FloatArray(0),
    val basalMax: Float = 0f,
    /** Grams of carb EQUIVALENT per step; read from wide sample, what model was actually fed. */
    val exercise: FloatArray = FloatArray(0),
    val exerciseMax: Float = 0f,
) {
    val size: Int get() = carb.size
    val isEmpty: Boolean get() =
        carb.isEmpty() || (carbMax <= 0f && insulinMax <= 0f && exerciseMax <= 0f)

    /** Units-per-step at [ms]; 0 outside the grid or absent. */
    fun basalAt(ms: Long): Float = indexAt(ms).let { if (it < 0 || it >= basal.size) 0f else basal[it] }

    /** Absolute epoch-ms at the LEFT edge of bucket [i]. */
    fun tsAt(i: Int): Long = gridStartMs + i.toLong() * stepMs

    /** Bucket containing ms, or -1 outside grid; floorDiv not /, panel scrubs left of grid. */
    fun indexAt(ms: Long): Int {
        if (size == 0) return -1
        val i = Math.floorDiv(ms - gridStartMs, stepMs).toInt()
        return if (i in 0 until size) i else -1
    }

    /** Clamped into the array rather than answered as -1; bounds the draw's viewport cull. */
    internal fun clampedIndexAt(ms: Double): Int {
        if (size == 0) return 0
        val d = (ms - gridStartMs.toDouble()) / stepMs.toDouble()
        return when {
            d <= 0.0 -> 0
            d >= (size - 1).toDouble() -> size - 1
            else -> d.toInt()
        }
    }

    /** Grams-per-step at [ms]; 0 outside the grid. */
    fun carbAt(ms: Long): Float = indexAt(ms).let { if (it < 0) 0f else carb[it] }

    /** Units-per-step at [ms]; 0 outside the grid. */
    fun insulinAt(ms: Long): Float = indexAt(ms).let { if (it < 0) 0f else insulin[it] }

    /** Grams-per-step of carbohydrate equivalent at [ms]; 0 outside the grid or absent. */
    fun exerciseAt(ms: Long): Float =
        indexAt(ms).let { if (it < 0 || it >= exercise.size) 0f else exercise[it] }

    companion object {
        val EMPTY = CurveOverlayFrame(0L, 300_000L, FloatArray(0), FloatArray(0), 0f, 0f)
    }
}

/** Per-step series, index-aligned to one grid window; carb/insulin rebuilt, exercise read raw. */
class OverlayInput(
    val carb: DoubleArray,
    val insulin: DoubleArray,
    val basal: DoubleArray,
    val exercise: DoubleArray,
)

/** SPEC §2.3. [basal] is the basal-only sub-channel, for rendering; pass empty for none. */
suspend fun curveOverlayOf(
    carb: DoubleArray,
    insulin: DoubleArray,
    gridStartMs: Long,
    stepMs: Long = 300_000L,
    basal: DoubleArray = DoubleArray(0),
    exercise: DoubleArray = DoubleArray(0),
): CurveOverlayFrame = withContext(Dispatchers.Default) {
    buildCurveOverlay(carb, insulin, gridStartMs, stepMs, basal, exercise)
}

/** Pure; safe from a `@Preview` or a test. */
fun buildCurveOverlay(
    carb: DoubleArray,
    insulin: DoubleArray,
    gridStartMs: Long,
    stepMs: Long = 300_000L,
    basal: DoubleArray = DoubleArray(0),
    exercise: DoubleArray = DoubleArray(0),
): CurveOverlayFrame {
    val n = maxOf(maxOf(carb.size, insulin.size), exercise.size)
    if (n == 0) return CurveOverlayFrame.EMPTY
    val c = FloatArray(n) { i -> (carb.getOrElse(i) { 0.0 }).toFloat() }
    val ins = FloatArray(n) { i -> (insulin.getOrElse(i) { 0.0 }).toFloat() }
    val bas = FloatArray(n) { i -> (basal.getOrElse(i) { 0.0 }).toFloat() }
    val exr = FloatArray(n) { i -> (exercise.getOrElse(i) { 0.0 }).toFloat() }
    var cMax = 0f
    var iMax = 0f
    var bMax = 0f
    var eMax = 0f
    for (i in 0 until n) {
        if (c[i] > cMax) cMax = c[i]
        if (ins[i] > iMax) iMax = ins[i]
        if (bas[i] > bMax) bMax = bas[i]
        if (exr[i] > eMax) eMax = exr[i]
    }
    return CurveOverlayFrame(gridStartMs, stepMs, c, ins, cMax, iMax, bas, bMax, exr, eMax)
}

/** Units-per-step below which the insulin channel carries no action. */
const val INSULIN_EPS: Float = 1e-6f

/** How far ahead the no-future-insulin advisory looks when no forecast bounds it. */
const val NO_INSULIN_HORIZON_MS: Long = 3L * 3_600_000L

/** No insulin to horizon? Keys on size, NOT isEmpty: flat-zero buckets are the case to warn. */
fun noFutureInsulinOverForecast(
    frame: CurveOverlayFrame,
    predictions: List<ModelPrediction>,
    nowMs: Long,
): Boolean {
    if (frame.size == 0) return false
    val lastForecast = predictions.maxOfOrNull { it.anchorTsMs + it.horizonSteps.toLong() * it.stepMs }
    val horizonEnd = maxOf(lastForecast ?: 0L, nowMs + NO_INSULIN_HORIZON_MS)
    var i = frame.indexAt(nowMs).let { if (it < 0) 0 else it }
    while (i < frame.size) {
        val ts = frame.tsAt(i)
        if (ts > horizonEnd) break
        if (ts >= nowMs && frame.insulin[i] > INSULIN_EPS) return false
        i++
    }
    return true
}

data class CurveOverlayToggles(
    val carbs: Boolean = false,
    val insulin: Boolean = false,
    val exercise: Boolean = false,
) {
    val any: Boolean get() = carbs || insulin || exercise
}

/** Fill and roof are one command stream; only fill's runs close. Host test reads geometry. */
internal interface CurvePathSink {
    fun moveTo(x: Float, y: Float)
    fun lineTo(x: Float, y: Float)
    /** Closes the FILL polygon; the roof is left open. */
    fun endRun()
}

/** Held by the composition, never allocated per frame; [reset] before each channel. */
internal class CurveChannelPaths : CurvePathSink {
    val fill = Path()
    val roof = Path()
    fun reset() { fill.reset(); roof.reset() }
    override fun moveTo(x: Float, y: Float) { fill.moveTo(x, y); roof.moveTo(x, y) }
    override fun lineTo(x: Float, y: Float) { fill.lineTo(x, y); roof.lineTo(x, y) }
    override fun endRun() { fill.close() }
}

/** values[i] plotted at its RIGHT edge; run opens from floor at event instant; lo,hi is a cull. */
internal fun emitCurveChannel(
    values: FloatArray,
    peak: Float,
    floorY: Float,
    availH: Float,
    gridStartMs: Long,
    stepMs: Long,
    absToPx: AbsToPx,
    lo: Int,
    hi: Int,
    sink: CurvePathSink,
) {
    if (peak <= 0f || values.isEmpty()) return
    val first = lo.coerceIn(0, values.size - 1)
    val last = hi.coerceIn(first, values.size - 1)
    fun tsAt(i: Int): Double = (gridStartMs + i.toLong() * stepMs).toDouble()
    fun yOf(v: Float): Float = floorY - (v / peak) * availH * 0.92f

    var open = false
    for (i in first..last) {
        val v = values[i]
        val xRight = absToPx.of(tsAt(i) + stepMs)
        if (v <= 0f) {
            if (open) {
                sink.lineTo(absToPx.of(tsAt(i)), floorY)
                sink.endRun()
                open = false
            }
            continue
        }
        val y = yOf(v)
        if (!open) {
            val xLeft = absToPx.of(tsAt(i)) // the event instant
            sink.moveTo(xLeft, floorY)
            // Entering mid-run: rise to previous bucket's vertex, the segment a full scan draws.
            if (i == first && i > 0 && values[i - 1] > 0f) sink.lineTo(xLeft, yOf(values[i - 1]))
            sink.lineTo(xRight, y)
            open = true
        } else {
            sink.lineTo(xRight, y)
        }
    }
    if (open) {
        sink.lineTo(absToPx.of(tsAt(last) + stepMs), floorY)
        sink.endRun()
    }
}

/** absToPx maps epoch-ms to x; only strictly-positive buckets fill; paths is caller's scratch. */
internal fun DrawScope.drawCurveOverlay(
    frame: CurveOverlayFrame,
    toggles: CurveOverlayToggles,
    absToPx: AbsToPx,
    bandTop: Float,
    plotBottom: Float,
    carbColor: Color,
    insulinColor: Color,
    exerciseColor: Color,
    viewStartMs: Double,
    viewSpanMs: Double,
    paths: CurveChannelPaths,
) {
    if (frame.isEmpty || !toggles.any) return
    val bandH = (plotBottom - bandTop).coerceAtLeast(1f)
    val lo = frame.clampedIndexAt(viewStartMs - viewSpanMs)
    val hi = frame.clampedIndexAt(viewStartMs + 2.0 * viewSpanMs)

    fun drawChannel(values: FloatArray, peak: Float, color: Color) {
        if (peak <= 0f) return
        paths.reset()
        emitCurveChannel(
            values, peak, plotBottom, bandH,
            frame.gridStartMs, frame.stepMs, absToPx, lo, hi, paths,
        )
        drawPath(paths.fill, color.copy(alpha = 0.16f))
        drawPath(paths.roof, color.copy(alpha = 0.7f), style = Stroke(width = 1.6f))
    }

    if (toggles.carbs) drawChannel(frame.carb, frame.carbMax, carbColor)
    // One total-insulin curve: bolus and basal are already combined; no separate basal floor-strip.
    if (toggles.insulin) drawChannel(frame.insulin, frame.insulinMax, insulinColor)
    if (toggles.exercise) drawChannel(frame.exercise, frame.exerciseMax, exerciseColor)
}
