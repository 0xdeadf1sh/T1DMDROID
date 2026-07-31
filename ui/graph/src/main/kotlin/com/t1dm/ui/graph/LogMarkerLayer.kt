package com.t1dm.ui.graph

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.LogMarker
import com.t1dm.core.model.LogState
import kotlin.math.abs

/**
 * The BG panel's LOG MARKER layer: one icon in the plot's lower region for every logged carbohydrate
 * and insulin event, so the trace records when the user acted as well as what their glucose then did.
 *
 * A marker carries when / which channel / whether the server has accepted it yet ([LogMarker]) and
 * nothing else — no amount, no row id — so this layer can neither render a number it has no business
 * rendering nor mutate the row behind it. The COMMITTED/DELIVERED verdict is joined once, in `:app`,
 * and both this layer and the Logs panel read the same feed; neither re-derives it.
 *
 * **A tap is answered by POSITION, never by identity.** [hitTestLogMarkers] returns indices into the
 * very list the caller handed in ([MarkerLane.source]), so the caller resolves them against its own
 * richer feed and this layer still learns nothing it could render or act on. That is what lets a mark
 * name every entry standing behind it without the amounts ever crossing into the drawing layer.
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

/** The marker glyph's edge, in dp. Larger than a nav icon on purpose: the mark is read at a glance
 *  across a busy evening and is also the panel's only TAP TARGET, and a glyph small enough to be
 *  elegant was too small to be either. The clustering below is what keeps a dense day a row of marks
 *  rather than a wall — it scales with this constant, so the two cannot disagree. */
internal const val LOG_MARKER_DP = 30f

/** Clear space between two marks that did NOT combine, in dp. Added to [LOG_MARKER_DP] it gives the
 *  clustering distance, so two drawn marks can never touch: single-linkage guarantees the gap between
 *  distinct clusters exceeds the separation, and each cluster's centroid lies within its own members. */
private const val LOG_MARKER_GAP_DP = 3f

/** How far the LOWER lane's foot sits above the plot floor, in dp — clear of the axis line without
 *  leaving the clipped data region. A clearance against the axis, not a fraction of the glyph, so it
 *  does not grow with [LOG_MARKER_DP]. */
private const val LOG_MARKER_FOOT_DP = 2.5f

/** Clear space between the two lanes, in dp. Enough that a syringe and a burger standing at the same
 *  instant read as two marks in two channels rather than one tall composite — which the two
 *  silhouettes, one round-topped and one needle-tipped, already carry most of the way. Like the foot,
 *  a clearance rather than a proportion: every dp here is taken from the plot. */
private const val LOG_MARKER_LANE_GAP_DP = 2.5f

/** The whole band the two lanes overlay, in dp, measured up from `plotBottom`. Stated as a constant
 *  because it is the layer's entire claim on the plot: an OVERLAY over the lower region, never a
 *  reduction of it. Everything that must stand clear of the lanes — the rolled-forecast caption, the
 *  tap band below — is measured from THIS, so a change to the glyph size carries them all with it. */
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
 * How far from a mark's centre a tap still counts as a tap ON it, at the current density.
 *
 * Exactly HALF the clustering distance, and derived from it rather than chosen: two emitted clusters
 * are always more than [logMarkerSeparationPx] apart, so no point on the panel can lie within reach of
 * two marks of one lane. Every tap therefore has at most one answer per lane — the ambiguity is
 * excluded by arithmetic rather than resolved by a tie-break — and the reach is still the widest one
 * that guarantees it.
 */
internal fun logMarkerTapReachPx(dpPx: Float): Float = logMarkerSeparationPx(dpPx) / 2f

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
 * ONE channel's markers, ascending by `tsMs`, each remembering where it came from.
 *
 * [source] is the whole of this layer's answer to "which log is this?": `source[i]` is the position of
 * `marks[i]` in the list the caller passed to [markerLane]. The layer neither holds nor wants a row id
 * — an index into the caller's own feed is enough for the caller to name the entry, and carries
 * nothing back the drawing pass could render.
 *
 * Split and sorted ONCE per feed, never per frame: [clusterLogMarkers] is a linear pass that reads the
 * projection as monotone, so a re-sort in the draw phase would put an O(n log n) allocation in every
 * frame of a pan.
 */
internal class MarkerLane(
    val marks: List<LogMarker>,
    val source: IntArray,
) {
    companion object {
        val EMPTY = MarkerLane(emptyList(), IntArray(0))
    }
}

/** [markers]' [kind] channel as a [MarkerLane] — filtered, ordered, and index-tracked in one pass. */
internal fun markerLane(markers: List<LogMarker>, kind: CurveKind): MarkerLane {
    if (markers.isEmpty()) return MarkerLane.EMPTY
    val idx = ArrayList<Int>(markers.size)
    for (i in markers.indices) if (markers[i].kind == kind) idx.add(i)
    if (idx.isEmpty()) return MarkerLane.EMPTY
    // Stable: two logs sharing a 5-min slot keep the caller's own order, which is already a total one.
    idx.sortBy { markers[it].tsMs }
    return MarkerLane(idx.map { markers[it] }, idx.toIntArray())
}

/**
 * A drawn mark: one logged event, or the several that collided into it.
 *
 * [xPx] is the members' mean x — for a lone event that is exactly its own instant, so a single log
 * always reads as a mark standing where it happened.
 *
 * [from]..[to] is the half-open run of its lane ([MarkerLane.marks]) the mark stands for. The members
 * are contiguous because clustering is a single ascending pass, and naming them is what lets a tap
 * list every entry behind a combined glyph rather than guess at one from its timestamp — two logs can
 * share a 5-min slot, so a timestamp is not an identity.
 *
 * The DRAWING still renders no count: the mark is an icon and only an icon, and a figure beside it
 * would be the one number on this panel that no calculator, forecast or rail ever sees. The run is for
 * the tap to resolve, not for the Canvas to print.
 */
internal data class MarkerCluster(
    val xPx: Float,
    /** True when ANY member is still awaiting the server, which is what makes the whole cluster pulse:
     *  a combined mark must not go quiet merely because most of what it stands for has landed. */
    val committed: Boolean,
    val from: Int,
    val to: Int,
) {
    /** How many logs this one glyph stands for. */
    val size: Int get() = to - from
}

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
 * Sort once where the list is collected ([markerLane]), not per frame.
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
    // The run of `markers` the open cluster spans. Contiguous by construction: the cull only ever drops
    // a prefix and a suffix of an ascending list.
    var firstIdx = 0
    var lastIdx = 0
    fun flush() {
        if (count > 0) out.add(MarkerCluster((sumX / count).toFloat(), committed, firstIdx, lastIdx + 1))
        count = 0
        sumX = 0.0
        committed = false
    }
    for (i in markers.indices) {
        val m = markers[i]
        val x = (plotLeft + (m.tsMs - viewStartMs) * ppm).toFloat()
        if (x < cullLo) continue
        if (x > cullHi) break // ascending by ts ⇒ ascending in x; everything after is off the right edge
        if (count > 0 && x - lastX > minSeparationPx) flush()
        if (count == 0) firstIdx = i
        count++
        sumX += x
        lastX = x
        lastIdx = i
        if (m.state == LogState.COMMITTED) committed = true
    }
    flush()
    return out
}

/**
 * What a tap at ([xPx], [yPx]) stands on: the positions, in the caller's own feed, of every log behind
 * every mark in that column — or an empty list, which is a MISS and must open nothing.
 *
 * **Both lanes answer, not the nearer one.** A carb mark and an insulin mark at the same instant stand
 * one above the other, and a tap on the column means "what did I log here", not "which of the two did
 * my thumb land closer to". So the vertical coordinate decides only whether the tap is in the band at
 * all ([LOG_MARKER_BAND_DP] up from [plotBottom]); which marks answer is decided by x, in each lane
 * independently, and the two answers are unioned. The lanes' centroids drift apart as soon as either
 * absorbs a neighbour, which is precisely why the column cannot be resolved by a single hit test.
 *
 * **The tap must land in the PLOT, [plotLeft]..[plotRight].** The pointer node spans the whole
 * composable, so without this the y-axis label gutter and the right margin are live tap targets — and
 * the drawing is clipped to the plot rect, so a mark whose centroid sits outside it is invisible while
 * [clusterLogMarkers] still emits it (deliberately: a straddling glyph must draw its visible half).
 * Bounding x here is what keeps the two agreeing: no tap can name a mark it cannot also see. The reach
 * is half the clustering distance and the glyph half that again, so the widest overhang still
 * answerable has all but 1.5 dp of its glyph inside the clip.
 *
 * Within a lane at most one mark can be in reach ([logMarkerTapReachPx]); the nearest is taken anyway,
 * so the function stays total if that ever ceases to hold.
 *
 * Returned ascending, so the caller's own ordering of its feed — newest first, totally ordered —
 * survives into whatever it renders.
 */
internal fun hitTestLogMarkers(
    xPx: Float,
    yPx: Float,
    plotLeft: Float,
    plotRight: Float,
    plotBottom: Float,
    dpPx: Float,
    insulinLane: MarkerLane,
    insulinClusters: List<MarkerCluster>,
    carbLane: MarkerLane,
    carbClusters: List<MarkerCluster>,
): List<Int> {
    if (xPx < plotLeft || xPx > plotRight) return emptyList()
    if (yPx < plotBottom - LOG_MARKER_BAND_DP * dpPx || yPx > plotBottom) return emptyList()
    val reach = logMarkerTapReachPx(dpPx)
    val hits = ArrayList<Int>(4)
    collectHits(insulinLane, insulinClusters, xPx, reach, hits)
    collectHits(carbLane, carbClusters, xPx, reach, hits)
    hits.sort()
    return hits
}

private fun collectHits(
    lane: MarkerLane,
    clusters: List<MarkerCluster>,
    xPx: Float,
    reachPx: Float,
    out: MutableList<Int>,
) {
    var best: MarkerCluster? = null
    var bestDx = Float.MAX_VALUE
    for (c in clusters) {
        val dx = abs(xPx - c.xPx)
        if (dx <= reachPx && dx < bestDx) {
            best = c
            bestDx = dx
        }
    }
    val hit = best ?: return
    for (i in hit.from until hit.to) out.add(lane.source[i])
}

/**
 * The marker layer's TAP, as a detector that can be co-resident with the panel's pan, pinch and scrub
 * without taking anything from them.
 *
 * **It consumes nothing, ever.** Not the down, not a move, not the up — so no peer detector can observe
 * the slightest difference in the event stream whether this one is registered or not, and the question
 * of what it might steal does not arise. It only ever ADDS a reading of a gesture the panel was already
 * throwing away: a stationary press-and-release shorter than a long press is, today, a no-op for both
 * the transform detector (zero pan, unit zoom) and the scrub (its long press never fires).
 *
 * The converse — that a peer steals from IT — is what the loop below watches for, and the three exits
 * are the three ways a gesture turns out to have been something else: a consumed change (the pan or the
 * scrub has claimed it), a second finger (a pinch), or movement past the touch slop (a drag). The
 * duration test at the end is the fourth: past the long-press timeout the scrub has already announced
 * itself, and a modal must not open on top of a read-out the user deliberately raised.
 *
 * Register it FIRST among the node's detectors. The main pass walks handlers in REVERSE registration
 * order, so first-registered is last-dispatched — which is exactly what this one wants: by the time it
 * sees an event, any peer that meant to claim it already has, in that same pass, and the consumption is
 * visible without a Final-pass round trip.
 */
internal suspend fun PointerInputScope.detectLogMarkerTaps(onTap: (Offset) -> Unit) {
    awaitEachGesture {
        // Taken unconsumed-or-not and judged immediately after, rather than by `requireUnconsumed`:
        // that flag would leave this detector parked mid-gesture, waiting for an unconsumed down that
        // will not arrive until the finger has lifted and pressed again. Aborting instead re-arms it
        // cleanly on the next gesture. A claimed down is somebody else's gesture — an overlay above
        // this panel, which is exactly how drive mode's tap-to-place takes the panel over.
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.isConsumed) return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        var up: PointerInputChange? = null
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.any { it.isConsumed }) return@awaitEachGesture
            if (event.changes.size > 1) return@awaitEachGesture
            val ch = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if ((ch.position - down.position).getDistance() > slop) return@awaitEachGesture
            if (!ch.pressed) {
                up = ch
                break
            }
        }
        val lifted = up ?: return@awaitEachGesture
        if (lifted.uptimeMillis - down.uptimeMillis <= viewConfiguration.longPressTimeoutMillis) {
            onTap(lifted.position)
        }
    }
}

/**
 * Paint one lane's marks.
 *
 * Call from INSIDE the plot clip — markers belong to the data, so they pan and zoom with it and must
 * never spill over the local-time or model-time axes — and BEFORE the BG trace, so an icon can never
 * sit on top of the glucose line. A hypoglycaemic excursion drops into exactly the region these lanes
 * occupy, and it is the one thing on the panel that must never be occluded.
 *
 * [laneTopY] comes from [logMarkerLaneTop] and is the glyph's TOP edge; [painter] and [tint] are the
 * lane's own, so the drawing pass has nothing left to decide per mark. The tint is passed already built
 * rather than as a colour because this runs inside the draw lambda, which the committed pulse re-enters
 * at the display's refresh rate.
 *
 * [committedAlpha] is the live pulse and must be read inside the Canvas's draw lambda, so a running
 * fade invalidates the draw phase alone and never a composition.
 */
internal fun DrawScope.drawLogMarkers(
    clusters: List<MarkerCluster>,
    painter: Painter,
    tint: ColorFilter,
    sizePx: Float,
    laneTopY: Float,
    committedAlpha: Float,
) {
    if (clusters.isEmpty()) return
    val glyph = Size(sizePx, sizePx)
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
