package com.t1dm.ui.graph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.t1dm.core.model.ModelPrediction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The dashboard curve OVERLAY model (Phase 4 — "dashboard curve overlays + IOB/COB"):
 * the carb **appearance (Ra)** curve and the insulin **PK-action** curve drawn UNDER the BG graph, in
 * a low band anchored at the plot floor, so the two model-input channels are legible against the
 * glucose trace without occluding it (model-io-curves.md: carbs = grams-per-5-min Ra; insulin =
 * units-per-5-min action, bolus gamma + auto-extended basal Bateman summed).
 *
 * Same off-thread, immutable-primitive-array discipline as [GraphFrame] / [PredSeries]: the two
 * channels are reconstructed from the logged carb/insulin events by the `CurveEngine`/`ChannelBuilder`
 * in `:data` (in `:app`, off the main thread), handed here as already-bucketed [DoubleArray]s, and
 * this class only maps them to pixels. The Canvas never touches a domain event or the JNI seam.
 *
 * Coordinates are grid-absolute: bucket `i` spans `[gridStartMs + i·stepMs, +stepMs)`, so the
 * overlay lines up with the BG viewport's absolute-ms projection exactly as [PredSeries] does, and
 * pan/zoom never forces a rebuild. Each channel keeps its own peak ([carbMax]/[insulinMax]) so the
 * two — grams and units, incommensurable — are auto-scaled independently within the band.
 */
class CurveOverlayFrame internal constructor(
    val gridStartMs: Long,
    val stepMs: Long,
    val carb: FloatArray,       // grams-per-step Ra (feat 1)
    val insulin: FloatArray,    // units-per-step action, bolus + basal COMBINED (feat 2, model channel)
    val carbMax: Float,
    val insulinMax: Float,
    // Issue 18: the BASAL-only sub-channel (auto-extended schedule + logged long-acting injections),
    // carried SEPARATELY purely for rendering. The model still consumes the COMBINED [insulin] above
    // (model-io-curves.md: basal + bolus summed) — this never changes that. A 24–42 h basal spreads
    // its dose so thinly (~1/300 of a bolus gamma peak) that on the shared insulin scale it vanishes;
    // giving it its own scale + baseline strip makes a logged/scheduled basal visibly represented.
    val basal: FloatArray = FloatArray(0),
    val basalMax: Float = 0f,
) {
    val size: Int get() = carb.size
    val isEmpty: Boolean get() = carb.isEmpty() || (carbMax <= 0f && insulinMax <= 0f)

    /** Basal action (units-per-step) at [ms]; 0 when outside the grid or absent. */
    fun basalAt(ms: Long): Float = indexAt(ms).let { if (it < 0 || it >= basal.size) 0f else basal[it] }

    /** Absolute epoch-ms at the LEFT edge of bucket [i]. */
    fun tsAt(i: Int): Long = gridStartMs + i.toLong() * stepMs

    /** Bucket index containing absolute epoch-ms [ms], or -1 when outside the grid. */
    fun indexAt(ms: Long): Int {
        if (size == 0) return -1
        val i = ((ms - gridStartMs) / stepMs).toInt()
        return if (i in 0 until size) i else -1
    }

    /** Carb Ra (grams-per-step) at [ms]; 0 when outside the grid. */
    fun carbAt(ms: Long): Float = indexAt(ms).let { if (it < 0) 0f else carb[it] }

    /** Insulin action (units-per-step) at [ms]; 0 when outside the grid. */
    fun insulinAt(ms: Long): Float = indexAt(ms).let { if (it < 0) 0f else insulin[it] }

    companion object {
        val EMPTY = CurveOverlayFrame(0L, 300_000L, FloatArray(0), FloatArray(0), 0f, 0f)
    }
}

/** Build the overlay off-thread from the reconstructed channels (SPEC §2.3, GraphFrame row).
 *  [basal] is the basal-only sub-channel for rendering (issue 18); pass empty for none. */
suspend fun curveOverlayOf(
    carb: DoubleArray,
    insulin: DoubleArray,
    gridStartMs: Long,
    stepMs: Long = 300_000L,
    basal: DoubleArray = DoubleArray(0),
): CurveOverlayFrame = withContext(Dispatchers.Default) {
    buildCurveOverlay(carb, insulin, gridStartMs, stepMs, basal)
}

/** Pure transform (no coroutines) — safe from a `@Preview`/test. */
fun buildCurveOverlay(
    carb: DoubleArray,
    insulin: DoubleArray,
    gridStartMs: Long,
    stepMs: Long = 300_000L,
    basal: DoubleArray = DoubleArray(0),
): CurveOverlayFrame {
    val n = maxOf(carb.size, insulin.size)
    if (n == 0) return CurveOverlayFrame.EMPTY
    val c = FloatArray(n) { i -> (carb.getOrElse(i) { 0.0 }).toFloat() }
    val ins = FloatArray(n) { i -> (insulin.getOrElse(i) { 0.0 }).toFloat() }
    val bas = FloatArray(n) { i -> (basal.getOrElse(i) { 0.0 }).toFloat() }
    var cMax = 0f
    var iMax = 0f
    var bMax = 0f
    for (i in 0 until n) {
        if (c[i] > cMax) cMax = c[i]
        if (ins[i] > iMax) iMax = ins[i]
        if (bas[i] > bMax) bMax = bas[i]
    }
    return CurveOverlayFrame(gridStartMs, stepMs, c, ins, cMax, iMax, bas, bMax)
}

/** Below this units-per-step the insulin channel is treated as carrying no action (item 16). */
const val INSULIN_EPS: Float = 1e-6f

/** How far ahead the no-future-insulin advisory (item 16) looks when no forecast bounds it. */
const val NO_INSULIN_HORIZON_MS: Long = 3L * 3_600_000L

/**
 * Item-16 advisory predicate (pure): does NO insulin action cover the window from [nowMs] out to the
 * forecast horizon? The horizon is the latest forecast end, or `nowMs + `[NO_INSULIN_HORIZON_MS] when
 * no forecast bounds it. The auto-extended basal is already summed into [CurveOverlayFrame.insulin]
 * (model-io-curves.md), so an active basal schedule — like a committed bolus tail — keeps this `false`.
 *
 * Fail-safe rule: when there is **no reconstructable channel at all** (`frame.size == 0`, e.g. a fresh
 * wipe with no readings yet) the answer is `false` — nothing to reason about, so do not warn. Note this
 * keys on `size`, NOT [CurveOverlayFrame.isEmpty]: a channel that HAS buckets but is flat-zero (readings
 * exist, yet genuinely no bolus and no basal cover the hours ahead) is precisely the case the user
 * SHOULD be warned about, so it must not be swallowed by the `carbMax<=0 && insulinMax<=0` short-circuit.
 * Advisory only — the app never actuates.
 */
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

/**
 * Which overlay channels are drawn — the dashboard toggle state, threaded through so the graph
 * itself stays stateless (a rebuild is never needed to flip a channel; the draw simply skips it).
 */
data class CurveOverlayToggles(val carbs: Boolean = false, val insulin: Boolean = false) {
    val any: Boolean get() = carbs || insulin
}

/**
 * Draw the overlay into the bottom band of the plot. [absToPx] maps absolute epoch-ms to x (shared
 * with the BG line + [PredSeries]); the band occupies `[bandTop, plotBottom]`. Each enabled channel
 * is a translucent filled area rising from the floor, auto-scaled to its own peak so a 2 g Ra tick
 * and a 6 U bolus both read. A thin roof-line caps each fill for legibility at low alpha.
 *
 * Only buckets with a strictly-positive value contribute a filled column, and runs are bridged, so a
 * long flat-zero stretch draws nothing rather than a baseline smear.
 */
internal fun DrawScope.drawCurveOverlay(
    frame: CurveOverlayFrame,
    toggles: CurveOverlayToggles,
    absToPx: (Double) -> Float,
    bandTop: Float,
    plotBottom: Float,
    carbColor: Color,
    insulinColor: Color,
) {
    if (frame.isEmpty || !toggles.any) return
    val bandH = (plotBottom - bandTop).coerceAtLeast(1f)

    // Anchoring (Phase 7A item 4): `values[i]` is the appearance/action integrated
    // over `[tsAt(i), tsAt(i)+step)` — the gamma sample at t = (i+1)·step from the event (which starts
    // at 0). Each bucket's value is plotted at its RIGHT edge, and a run of positive buckets opens from
    // `(tsAt(firstBucket), floorY)` — the event instant — so the curve begins at (logTime, 0) and rises.
    fun drawChannel(values: FloatArray, peak: Float, color: Color, floorY: Float, availH: Float) {
        if (peak <= 0f) return
        val fill = Path()
        val roof = Path()
        var open = false
        for (i in values.indices) {
            val v = values[i]
            val xRight = absToPx((frame.tsAt(i) + frame.stepMs).toDouble()) // right edge = t=(i+1)·step
            if (v <= 0f) {
                if (open) {
                    val xZero = absToPx(frame.tsAt(i).toDouble())
                    roof.lineTo(xZero, floorY)
                    fill.lineTo(xZero, floorY); fill.close()
                    open = false
                }
                continue
            }
            val y = floorY - (v / peak) * availH * 0.92f
            if (!open) {
                val xLeft = absToPx(frame.tsAt(i).toDouble()) // the event instant: curve is 0 here
                fill.moveTo(xLeft, floorY); fill.lineTo(xRight, y)
                roof.moveTo(xLeft, floorY); roof.lineTo(xRight, y)
                open = true
            } else {
                fill.lineTo(xRight, y)
                roof.lineTo(xRight, y)
            }
        }
        if (open) {
            val xEnd = absToPx((frame.tsAt(values.size - 1) + frame.stepMs).toDouble())
            roof.lineTo(xEnd, floorY)
            fill.lineTo(xEnd, floorY); fill.close()
        }
        drawPath(fill, color.copy(alpha = 0.16f))
        drawPath(roof, color.copy(alpha = 0.7f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6f))
    }

    // A faint baseline separating the overlay band from the BG plot.
    drawLine(carbColor.copy(alpha = 0.0f), Offset(0f, bandTop), Offset(0f, bandTop), 0f)
    if (toggles.carbs) drawChannel(frame.carb, frame.carbMax, carbColor, plotBottom, bandH)
    // The insulin channel is drawn as a SINGLE total-insulin curve (bolus + basal already COMBINED into
    // [frame.insulin], model-io-curves.md) — no separate basal floor-strip.
    if (toggles.insulin) drawChannel(frame.insulin, frame.insulinMax, insulinColor, plotBottom, bandH)
}
