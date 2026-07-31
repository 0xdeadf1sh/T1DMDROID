package com.t1dm.ui.graph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.LogMarker
import com.t1dm.core.model.LogState

/**
 * The BG panel's LOG MARKER layer: one mark at the foot of the plot for every logged carbohydrate and
 * insulin event, so the trace records when the user acted as well as what their glucose then did.
 *
 * A marker carries when / which channel / whether the server has accepted it yet ([LogMarker]) and
 * nothing else — no amount, no row id — so this layer can neither render a number it has no business
 * rendering nor mutate the row behind it. The COMMITTED/DELIVERED verdict is joined once, in `:app`,
 * and both this layer and the Logs panel read the same feed; neither re-derives it.
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

/** How far the glyph's foot sits above the plot floor, in dp — clear of the axis line without leaving
 *  the clipped data region. */
internal const val LOG_MARKER_FOOT_DP = 2.5f

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

private val LOG_MARKER_COUNT_SIZE = 8.sp

/** The pixel distance within which two marks of the SAME kind combine, at the current density. */
internal fun logMarkerSeparationPx(dpPx: Float): Float = (LOG_MARKER_DP + LOG_MARKER_GAP_DP) * dpPx

/**
 * A drawn mark: either one logged event ([count] == 1) or the several that collided into it.
 *
 * [xPx] is the members' mean x — for a lone event that is exactly its own instant, so a single log
 * always reads as a mark standing where it happened and never as a cluster of one.
 */
internal data class MarkerCluster(
    val kind: CurveKind,
    val xPx: Float,
    val count: Int,
    /** True when ANY member is still awaiting the server, which is what makes the whole cluster pulse:
     *  a combined mark must not go quiet merely because most of what it stands for has landed. */
    val committed: Boolean,
)

/**
 * Combine colliding markers, in PIXEL space against the live viewport.
 *
 * Pixels, not time, because collision IS a pixel fact: the same two events an hour apart are one mark
 * at a 30-day zoom and two at a 3-hour one, and a time-based threshold would have to be re-tuned for
 * every span and every screen width to say the same thing. Clustering against the projection makes the
 * behaviour identical on any zoom and any display by construction.
 *
 * Combining is PER KIND — carbs with carbs, insulin with insulin, never into each other — because the
 * two are different channels and a mark that meant "some of each" would say nothing either could act on.
 *
 * Single-linkage: a marker joins the open cluster while it lies within [minSeparationPx] of the last
 * member admitted, so the gap between two emitted clusters always exceeds that distance and two drawn
 * marks can never overlap. A dense run therefore chains into one mark as the view widens, which is
 * exactly the intent.
 *
 * **[markers] must be ascending by `tsMs`** — the pass is linear and reads the projection as monotone.
 * Sort once where the list is collected, not per frame.
 *
 * Returns clusters grouped by kind in [CurveKind] order, ascending in x within each kind.
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
    // still drawn (the clip trims the overhang). A mark fully outside contributes to no count: the
    // number on a cluster always states what is on screen.
    val cullLo = plotLeft - minSeparationPx
    val cullHi = plotRight + minSeparationPx

    val out = ArrayList<MarkerCluster>(8)
    for (kind in CurveKind.entries) {
        var count = 0
        var sumX = 0.0
        var lastX = 0f
        var committed = false
        fun flush() {
            if (count > 0) out.add(MarkerCluster(kind, (sumX / count).toFloat(), count, committed))
            count = 0
            sumX = 0.0
            committed = false
        }
        for (m in markers) {
            if (m.kind != kind) continue
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
    }
    return out
}

/**
 * Paint the marks. Call from INSIDE the plot clip: markers belong to the data, so they pan and zoom
 * with it and must never spill over the local-time or model-time axes.
 *
 * [bottomY] is the glyph's FOOT, measured from the caller's `plotBottom` — never from the composable's
 * own height, which moves by the model-axis strip whenever the predicted clock appears.
 *
 * [committedAlpha] is the live pulse and must be read inside the Canvas's draw lambda, so a running
 * fade invalidates the draw phase alone and never a composition.
 */
internal fun DrawScope.drawLogMarkers(
    clusters: List<MarkerCluster>,
    carbPainter: Painter,
    insulinPainter: Painter,
    carbInk: Color,
    insulinInk: Color,
    sizePx: Float,
    bottomY: Float,
    committedAlpha: Float,
    measurer: TextMeasurer,
) {
    if (clusters.isEmpty()) return
    val top = bottomY - sizePx
    val glyph = Size(sizePx, sizePx)
    for (c in clusters) {
        val carb = c.kind == CurveKind.CARB
        val ink = if (carb) carbInk else insulinInk
        val alpha = if (c.committed) committedAlpha else LOG_MARKER_DELIVERED_ALPHA
        val left = c.xPx - sizePx / 2f
        translate(left, top) {
            with(if (carb) carbPainter else insulinPainter) {
                draw(glyph, alpha = alpha, colorFilter = ColorFilter.tint(ink))
            }
        }
        // A lone event is the glyph and nothing else — a "1" beside every mark would be noise, and it
        // would make the one case that needs no reading look like the one that does.
        if (c.count > 1) {
            val label = measurer.measure(
                c.count.toString(),
                TextStyle(color = ink.copy(alpha = alpha), fontSize = LOG_MARKER_COUNT_SIZE),
            )
            drawText(
                label,
                topLeft = Offset(left + sizePx + 1f, top + (sizePx - label.size.height) / 2f),
            )
        }
    }
}
