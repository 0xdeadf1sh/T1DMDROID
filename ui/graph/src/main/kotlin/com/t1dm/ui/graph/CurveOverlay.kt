package com.t1dm.ui.graph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The dashboard curve OVERLAY model (PLAN.private.md Phase 4 — "dashboard curve overlays + IOB/COB"):
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
    val insulin: FloatArray,    // units-per-step action, bolus + basal (feat 2)
    val carbMax: Float,
    val insulinMax: Float,
) {
    val size: Int get() = carb.size
    val isEmpty: Boolean get() = carb.isEmpty() || (carbMax <= 0f && insulinMax <= 0f)

    /** Absolute epoch-ms at the LEFT edge of bucket [i]. */
    fun tsAt(i: Int): Long = gridStartMs + i.toLong() * stepMs

    companion object {
        val EMPTY = CurveOverlayFrame(0L, 300_000L, FloatArray(0), FloatArray(0), 0f, 0f)
    }
}

/** Build the overlay off-thread from the two reconstructed channels (PLAN §2.3, GraphFrame row). */
suspend fun curveOverlayOf(
    carb: DoubleArray,
    insulin: DoubleArray,
    gridStartMs: Long,
    stepMs: Long = 300_000L,
): CurveOverlayFrame = withContext(Dispatchers.Default) {
    buildCurveOverlay(carb, insulin, gridStartMs, stepMs)
}

/** Pure transform (no coroutines) — safe from a `@Preview`/test. */
fun buildCurveOverlay(
    carb: DoubleArray,
    insulin: DoubleArray,
    gridStartMs: Long,
    stepMs: Long = 300_000L,
): CurveOverlayFrame {
    val n = maxOf(carb.size, insulin.size)
    if (n == 0) return CurveOverlayFrame.EMPTY
    val c = FloatArray(n) { i -> (carb.getOrElse(i) { 0.0 }).toFloat() }
    val ins = FloatArray(n) { i -> (insulin.getOrElse(i) { 0.0 }).toFloat() }
    var cMax = 0f
    var iMax = 0f
    for (i in 0 until n) {
        if (c[i] > cMax) cMax = c[i]
        if (ins[i] > iMax) iMax = ins[i]
    }
    return CurveOverlayFrame(gridStartMs, stepMs, c, ins, cMax, iMax)
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
    val half = frame.stepMs / 2.0

    fun drawChannel(values: FloatArray, peak: Float, color: Color) {
        if (peak <= 0f) return
        val fill = Path()
        val roof = Path()
        var open = false
        for (i in values.indices) {
            val v = values[i]
            val cx = absToPx(frame.tsAt(i) + half) // centre of the bucket
            if (v <= 0f) {
                if (open) { fill.lineTo(cx, plotBottom); fill.close(); open = false }
                continue
            }
            val y = plotBottom - (v / peak) * bandH * 0.92f
            if (!open) {
                fill.moveTo(cx, plotBottom); fill.lineTo(cx, y)
                roof.moveTo(cx, y)
                open = true
            } else {
                fill.lineTo(cx, y)
                roof.lineTo(cx, y)
            }
        }
        if (open) { fill.lineTo(absToPx(frame.tsAt(values.size - 1) + half), plotBottom); fill.close() }
        drawPath(fill, color.copy(alpha = 0.16f))
        drawPath(roof, color.copy(alpha = 0.7f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6f))
    }

    // A faint baseline separating the overlay band from the BG plot.
    drawLine(carbColor.copy(alpha = 0.0f), Offset(0f, bandTop), Offset(0f, bandTop), 0f)
    if (toggles.carbs) drawChannel(frame.carb, frame.carbMax, carbColor)
    if (toggles.insulin) drawChannel(frame.insulin, frame.insulinMax, insulinColor)
}
