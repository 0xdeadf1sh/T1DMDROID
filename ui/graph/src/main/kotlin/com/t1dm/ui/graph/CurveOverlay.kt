package com.t1dm.ui.graph

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.t1dm.core.model.ModelPrediction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bucket `i` spans `[gridStartMs + i·stepMs, +stepMs)`, so the overlay lines up with the BG
 *  viewport's absolute-ms projection and pan/zoom never forces a rebuild. Grams and units are
 *  incommensurable, so each channel is scaled to its own peak. */
class CurveOverlayFrame internal constructor(
    val gridStartMs: Long,
    val stepMs: Long,
    val carb: FloatArray,       // grams-per-step Ra
    val insulin: FloatArray,    // units-per-step action, bolus + basal COMBINED
    val carbMax: Float,
    val insulinMax: Float,
    // Rendering only: the model consumes the COMBINED [insulin] above. A 24-42 h basal is ~1/300 of a
    // bolus gamma peak, so on the shared insulin scale it vanishes.
    val basal: FloatArray = FloatArray(0),
    val basalMax: Float = 0f,
    /** Grams of carbohydrate EQUIVALENT disposed per step, positive. Read from the wide sample, so
     *  it carries recorded bouts and replays alike — what the model was actually fed. */
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

    /** Bucket containing [ms], or -1 outside the grid. `floorDiv`, not `/`: truncation toward zero
     *  would resolve the five minutes before `gridStartMs` to bucket 0, and the panel is scrubbable
     *  to the left of the grid. */
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

/**
 * The per-step series the overlay is built from, index-aligned to one grid window. [carb] and
 * [insulin] are reconstructed from logged events; [exercise] is READ from the wide sample, so it
 * carries recorded bouts as well as replays. Not a value type — it is carried, never compared.
 */
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

/** Does no insulin action cover [nowMs] out to the forecast horizon? With no channel at all the
 *  answer is `false` — nothing to reason about. Keys on `size`, NOT [CurveOverlayFrame.isEmpty]: a
 *  channel with buckets but flat zero is exactly the case to warn about. Advisory only. */
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

/** Fill and roof are one command stream; only the fill's runs are closed. The seam a host test
 *  records the geometry through. */
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

/** `values[i]` covers `[tsAt(i), tsAt(i)+step)` and is plotted at its RIGHT edge; a run opens from
 *  the floor at the event instant. `[lo, hi]` is a viewport cull: a run still open at `lo` is
 *  re-opened at `(x(tsAt(lo)), y(values[lo-1]))`, the vertex a full scan draws there, not back-scanned. */
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
            // Entering mid-run: rise to the previous bucket's vertex, the segment a full scan draws.
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

/** [absToPx] maps absolute epoch-ms to x; the band occupies `[bandTop, plotBottom]` and each channel
 *  is scaled to its own peak. Only strictly-positive buckets fill, so a flat-zero stretch draws
 *  nothing. [paths] is the caller's scratch, not two allocations per channel per frame. */
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
