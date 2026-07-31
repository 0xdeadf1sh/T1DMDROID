package com.t1dm.ui.graph

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.LogMarker
import com.t1dm.core.model.LogState

/**
 * The BG panel's LOG MARKER layer: one icon in the plot's lower region for every logged carbohydrate
 * and insulin event, so the trace records when the user acted as well as what their glucose then did.
 *
 * A marker carries when / which channel / whether the server has accepted it yet ([LogMarker]) and
 * nothing else — no amount, no row id — so this layer can neither render a number it has no business
 * rendering nor mutate the row behind it. The COMMITTED/DELIVERED verdict is joined once, in `:app`,
 * and both this layer and the Logs panel read the same feed; neither re-derives it.
 *
 * **Two fixed lanes: insulin above, carbs below.** Lane position is INFORMATION — a glance at the
 * upper lane is a glance at the insulin history — so it is decided by the channel alone and never by
 * what happens to be in view. A lane that appeared only when its channel had something to show would
 * make the same y mean carbs on one screen and insulin on the next.
 *
 * **The lanes overlay the plot; they do not shrink it.** Every position here is measured DOWN from the
 * caller's `plotBottom`; nothing is subtracted from it, from the y-axis scale, or from the trace
 * geometry. The band the two lanes occupy ([LOG_MARKER_BAND_DP]) is borrowed from the plot's lower
 * region and given back the moment there is nothing to draw, so turning logging on and off cannot move
 * the glucose trace by a pixel.
 *
 * The geometry lives here rather than in the Canvas because it is pure arithmetic over the live
 * viewport and is worth pinning in a test.
 */

/** The marker glyph's edge, in dp. A third of a nav icon: large enough to read its silhouette against
 *  the trace, small enough that a dense evening of logs is a row of marks rather than a wall. */
internal const val LOG_MARKER_DP = 10f

/** Clear space between two marks that did NOT combine, in dp. Added to [LOG_MARKER_DP] it gives the
 *  clustering distance, so two drawn marks can never touch: single-linkage guarantees the gap between
 *  distinct clusters exceeds the separation, and each cluster's centroid lies within its own members. */
private const val LOG_MARKER_GAP_DP = 3f

/** How far the LOWER lane's foot sits above the plot floor, in dp — clear of the axis line without
 *  leaving the clipped data region. */
private const val LOG_MARKER_FOOT_DP = 2.5f

/** Clear space between the two lanes, in dp. Enough that a syringe and a burger standing at the same
 *  instant read as two marks in two channels rather than one tall composite. */
private const val LOG_MARKER_LANE_GAP_DP = 2.5f

/** The whole band the two lanes overlay, in dp, measured up from `plotBottom`. Stated as a constant
 *  because it is the layer's entire claim on the plot: an OVERLAY over the lower region, never a
 *  reduction of it. */
internal const val LOG_MARKER_BAND_DP =
    LOG_MARKER_FOOT_DP + LOG_MARKER_DP + LOG_MARKER_LANE_GAP_DP + LOG_MARKER_DP

/** A DELIVERED mark's fixed alpha: present and legible, but plainly quieter than a committed one at any
 *  point in its fade — including the static value motion-off holds. */
internal const val LOG_MARKER_DELIVERED_ALPHA = 0.5f

/** The committed pulse's floor and ceiling. The floor stays above [LOG_MARKER_DELIVERED_ALPHA]'s
 *  neighbourhood only at its peak — the whole point is that a committed mark BREATHES past a delivered
 *  one rather than sitting at a second fixed level. */
internal const val LOG_MARKER_PULSE_MIN_ALPHA = 0.3f
internal const val LOG_MARKER_PULSE_MAX_ALPHA = 1f

/**
 * What a committed mark holds when motion is OFF. Deliberately the pulse's CEILING and not a snapped
 * fade: `motionSpec` would collapse the animation to a snap, and a snapped 1→0 fade is invisible — the
 * marker would stop saying anything exactly when the user asked for a static UI (Pulse.kt makes the
 * same choice for the same reason). Held at the ceiling it is still unmistakably louder than
 * [LOG_MARKER_DELIVERED_ALPHA], it simply does not breathe.
 */
internal const val LOG_MARKER_STATIC_ALPHA = LOG_MARKER_PULSE_MAX_ALPHA

/** One leg of the committed fade. Slow enough to read as breathing rather than blinking. */
internal const val LOG_MARKER_PULSE_MS = 950

/** The pixel distance within which two marks in the SAME lane combine, at the current density. */
internal fun logMarkerSeparationPx(dpPx: Float): Float = (LOG_MARKER_DP + LOG_MARKER_GAP_DP) * dpPx

/**
 * The top edge of [kind]'s lane, in canvas pixels.
 *
 * [plotBottom] is the caller's plot floor — never the composable's own height, which moves by the
 * model-axis strip whenever the predicted clock appears. Nothing else is an input: no marker, no
 * viewport and no toggle can reach this, which is precisely what makes the two lanes fixed.
 */
internal fun logMarkerLaneTop(kind: CurveKind, plotBottom: Float, dpPx: Float): Float {
    val carbTop = plotBottom - (LOG_MARKER_FOOT_DP + LOG_MARKER_DP) * dpPx
    return when (kind) {
        CurveKind.CARB -> carbTop
        CurveKind.INSULIN -> carbTop - (LOG_MARKER_LANE_GAP_DP + LOG_MARKER_DP) * dpPx
    }
}

/**
 * A drawn mark: one logged event, or the several that collided into it.
 *
 * [xPx] is the members' mean x — for a lone event that is exactly its own instant, so a single log
 * always reads as a mark standing where it happened.
 *
 * A cluster deliberately does NOT carry how many events it stands for. The mark is an icon and only an
 * icon: a figure beside it would be the one number on this panel that no calculator, forecast or rail
 * ever sees, and the layer is not given the amounts that would make such a figure worth reading.
 */
internal data class MarkerCluster(
    val xPx: Float,
    /** True when ANY member is still awaiting the server, which is what makes the whole cluster pulse:
     *  a combined mark must not go quiet merely because most of what it stands for has landed. */
    val committed: Boolean,
)

/**
 * Combine colliding markers of ONE LANE, in PIXEL space against the live viewport.
 *
 * Pixels, not time, because collision IS a pixel fact: the same two events an hour apart are one mark
 * at a 30-day zoom and two at a 3-hour one, and a time-based threshold would have to be re-tuned for
 * every span and every screen width to say the same thing. Clustering against the projection makes the
 * behaviour identical on any zoom and any display by construction.
 *
 * One lane per call, so nothing here knows about channels: carbs and insulin cannot combine because
 * they are never handed to the same call, and the lanes are drawn at different heights anyway.
 *
 * Single-linkage: a marker joins the open cluster while it lies within [minSeparationPx] of the last
 * member admitted, so the gap between two emitted clusters always exceeds that distance and two drawn
 * marks can never overlap. A dense run therefore chains into one mark as the view widens, which is
 * exactly the intent.
 *
 * **[markers] must be ascending by `tsMs`** — the pass is linear and reads the projection as monotone.
 * Sort once where the list is collected, not per frame.
 *
 * Returns the lane's clusters ascending in x.
 */
internal fun clusterLogMarkers(
    markers: List<LogMarker>,
    viewStartMs: Double,
    viewSpanMs: Double,
    plotLeft: Float,
    plotRight: Float,
    minSeparationPx: Float,
): List<MarkerCluster> {
    if (markers.isEmpty() || viewSpanMs <= 0.0 || plotRight <= plotLeft) return emptyList()
    val ppm = (plotRight - plotLeft).toDouble() / viewSpanMs
    // Cull to what the plot can show, widened by one glyph on each side so a mark straddling an edge is
    // still drawn (the clip trims the overhang).
    val cullLo = plotLeft - minSeparationPx
    val cullHi = plotRight + minSeparationPx

    val out = ArrayList<MarkerCluster>(8)
    var count = 0
    var sumX = 0.0
    var lastX = 0f
    var committed = false
    fun flush() {
        if (count > 0) out.add(MarkerCluster((sumX / count).toFloat(), committed))
        count = 0
        sumX = 0.0
        committed = false
    }
    for (m in markers) {
        val x = (plotLeft + (m.tsMs - viewStartMs) * ppm).toFloat()
        if (x < cullLo) continue
        if (x > cullHi) break // ascending by ts ⇒ ascending in x; everything after is off the right edge
        if (count > 0 && x - lastX > minSeparationPx) flush()
        count++
        sumX += x
        lastX = x
        if (m.state == LogState.COMMITTED) committed = true
    }
    flush()
    return out
}

/**
 * Paint one lane's marks.
 *
 * Call from INSIDE the plot clip — markers belong to the data, so they pan and zoom with it and must
 * never spill over the local-time or model-time axes — and BEFORE the BG trace, so an icon can never
 * sit on top of the glucose line. A hypoglycaemic excursion drops into exactly the region these lanes
 * occupy, and it is the one thing on the panel that must never be occluded.
 *
 * [laneTopY] comes from [logMarkerLaneTop] and is the glyph's TOP edge; [painter] and [ink] are the
 * lane's own, so the drawing pass has nothing left to decide per mark.
 *
 * [committedAlpha] is the live pulse and must be read inside the Canvas's draw lambda, so a running
 * fade invalidates the draw phase alone and never a composition.
 */
internal fun DrawScope.drawLogMarkers(
    clusters: List<MarkerCluster>,
    painter: Painter,
    ink: Color,
    sizePx: Float,
    laneTopY: Float,
    committedAlpha: Float,
) {
    if (clusters.isEmpty()) return
    val glyph = Size(sizePx, sizePx)
    val tint = ColorFilter.tint(ink)
    for (c in clusters) {
        translate(c.xPx - sizePx / 2f, laneTopY) {
            with(painter) {
                draw(
                    glyph,
                    alpha = if (c.committed) committedAlpha else LOG_MARKER_DELIVERED_ALPHA,
                    colorFilter = tint,
                )
            }
        }
    }
}
