package com.t1dm.ui.graph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
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

    // Issue 18: when the insulin channel is shown AND a basal component exists, reserve a thin strip
    // at the FLOOR for the basal on its OWN scale, and draw the bolus above it — so a long-acting
    // basal (whose per-step action is ~1/300 of a bolus peak) is never crushed to an invisible sliver
    // on the shared insulin scale. The strip is skipped when there is no basal, so a bolus-only view
    // is unchanged. This is a RENDERING split only; the model still sees the combined channel.
    val hasBasal = toggles.insulin && frame.basalMax > 0f
    val basalStripH = if (hasBasal) bandH * 0.32f else 0f
    val bolusFloor = plotBottom - basalStripH

    // Anchoring (Phase 7A item 4): `values[i]` is the appearance/action integrated
    // over `[tsAt(i), tsAt(i)+step)` — the gamma sample at t = (i+1)·step from the event (which starts
    // at 0). Each bucket's value is plotted at its RIGHT edge, and a run of positive buckets opens from
    // `(tsAt(firstBucket), floorY)` — the event instant — so the curve begins at (logTime, 0) and rises.
    fun drawChannel(values: FloatArray, peak: Float, color: Color, floorY: Float, availH: Float, dashed: Boolean = false) {
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
        val stroke = if (dashed) {
            androidx.compose.ui.graphics.drawscope.Stroke(
                width = 1.4f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f),
            )
        } else {
            androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6f)
        }
        drawPath(fill, color.copy(alpha = 0.16f))
        drawPath(roof, color.copy(alpha = 0.7f), style = stroke)
    }

    // A faint baseline separating the overlay band from the BG plot.
    drawLine(carbColor.copy(alpha = 0.0f), Offset(0f, bandTop), Offset(0f, bandTop), 0f)
    if (toggles.carbs) drawChannel(frame.carb, frame.carbMax, carbColor, plotBottom, bandH)
    if (toggles.insulin) {
        if (hasBasal) {
            // Bolus = combined − basal (both were summed into the same channel), drawn above the strip.
            val n = frame.insulin.size
            val bolus = FloatArray(n) { i -> (frame.insulin[i] - frame.basal.getOrElse(i) { 0f }).coerceAtLeast(0f) }
            var bolusMax = 0f
            for (v in bolus) if (v > bolusMax) bolusMax = v
            drawChannel(bolus, bolusMax, insulinColor, bolusFloor, bandH - basalStripH)
            // The basal on its own scale, in the floor strip, dashed to signal a distinct axis.
            drawChannel(frame.basal, frame.basalMax, insulinColor, plotBottom, basalStripH, dashed = true)
            // A faint divider marking the top of the basal strip.
            drawLine(insulinColor.copy(alpha = 0.25f), Offset(0f, bolusFloor), Offset(size.width, bolusFloor), 1f)
        } else {
            drawChannel(frame.insulin, frame.insulinMax, insulinColor, plotBottom, bandH)
        }
    }
}
