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
import kotlin.math.abs

/** One fixed lane per channel, carbs floor to exercise top, from plotBottom; overlay only. */

/** Glyph edge, dp; tap reach and clustering distance derive from it, under the 48dp guideline. */
internal const val LOG_MARKER_DP = 15f

/** Clear space between two uncombined marks, dp; with LOG_MARKER_DP is the clustering distance. */
private const val LOG_MARKER_GAP_DP = 3f

/** Lower lane's foot above the plot floor, dp; a clearance against the axis, not scaled by DP. */
private const val LOG_MARKER_FOOT_DP = 2.5f

/** Clear space between the lanes, dp; a clearance not a proportion, every dp taken from plot. */
private const val LOG_MARKER_LANE_GAP_DP = 2.5f

/** One per [CurveKind]. */
private const val LOG_MARKER_LANES = 3f

/** Layer's whole claim on the plot, dp up from plotBottom; clearances measure from this. */
internal const val LOG_MARKER_BAND_DP =
    LOG_MARKER_FOOT_DP + LOG_MARKER_LANES * LOG_MARKER_DP + (LOG_MARKER_LANES - 1) * LOG_MARKER_LANE_GAP_DP

/** One alpha for every log: outbox has no SENT state, no per-mark delivery claim can be made. */
internal const val LOG_MARKER_ALPHA = 0.85f

/** Distance within which two marks of one lane combine. */
internal fun logMarkerSeparationPx(dpPx: Float): Float = (LOG_MARKER_DP + LOG_MARKER_GAP_DP) * dpPx

/** Half the clustering distance, so no point lies within reach of two marks of one lane. */
internal fun logMarkerTapReachPx(dpPx: Float): Float = logMarkerSeparationPx(dpPx) / 2f

/** plotBottom is the caller's plot floor, never the composable's height (axis strip moves it). */
internal fun logMarkerLaneTop(kind: CurveKind, plotBottom: Float, dpPx: Float): Float {
    // Bottom-up, carbs on the floor: the order the panel's legend reads.
    val lane = when (kind) {
        CurveKind.CARB -> 0
        CurveKind.INSULIN -> 1
        CurveKind.EXERCISE -> 2
    }
    val foot = LOG_MARKER_FOOT_DP + lane * (LOG_MARKER_LANE_GAP_DP + LOG_MARKER_DP)
    return plotBottom - (foot + LOG_MARKER_DP) * dpPx
}

/** One channel, ascending by tsMs; source[i] is marks[i]'s position, the only row identity. */
internal class MarkerLane(
    val marks: List<LogMarker>,
    val source: IntArray,
) {
    companion object {
        val EMPTY = MarkerLane(emptyList(), IntArray(0))
    }
}

internal fun markerLane(markers: List<LogMarker>, kind: CurveKind): MarkerLane {
    if (markers.isEmpty()) return MarkerLane.EMPTY
    val idx = ArrayList<Int>(markers.size)
    for (i in markers.indices) if (markers[i].kind == kind) idx.add(i)
    if (idx.isEmpty()) return MarkerLane.EMPTY
    // Stable: two logs in one 5-min slot keep the caller's own order.
    idx.sortBy { markers[it].tsMs }
    return MarkerLane(idx.map { markers[it] }, idx.toIntArray())
}

/** xPx is the mean x; from..to is the half-open run behind the glyph the tap resolves against. */
internal data class MarkerCluster(
    val xPx: Float,
    val from: Int,
    val to: Int,
) {
    val size: Int get() = to - from
}

/** One lane, pixel space; single-linkage vs minSeparationPx, markers must be ascending tsMs. */
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
    // Widened by one glyph each side so a mark straddling an edge still draws; the clip trims it.
    val cullLo = plotLeft - minSeparationPx
    val cullHi = plotRight + minSeparationPx

    val out = ArrayList<MarkerCluster>(8)
    var count = 0
    var sumX = 0.0
    var lastX = 0f
    // The run of `markers` the open cluster spans; the cull only drops a prefix and a suffix.
    var firstIdx = 0
    var lastIdx = 0
    fun flush() {
        if (count > 0) out.add(MarkerCluster((sumX / count).toFloat(), firstIdx, lastIdx + 1))
        count = 0
        sumX = 0.0
    }
    for (i in markers.indices) {
        val m = markers[i]
        val x = (plotLeft + (m.tsMs - viewStartMs) * ppm).toFloat()
        if (x < cullLo) continue
        if (x > cullHi) break // ascending in x, so everything after is off the right edge
        if (count > 0 && x - lastX > minSeparationPx) flush()
        if (count == 0) firstIdx = i
        count++
        sumX += x
        lastX = x
        lastIdx = i
    }
    flush()
    return out
}

/** Empty is a miss; y decides band, x decides marks per lane, unioned; bounded to left/right. */
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
    exerciseLane: MarkerLane = MarkerLane.EMPTY,
    exerciseClusters: List<MarkerCluster> = emptyList(),
): List<Int> {
    if (xPx < plotLeft || xPx > plotRight) return emptyList()
    if (yPx < plotBottom - LOG_MARKER_BAND_DP * dpPx || yPx > plotBottom) return emptyList()
    val reach = logMarkerTapReachPx(dpPx)
    val hits = ArrayList<Int>(4)
    collectHits(insulinLane, insulinClusters, xPx, reach, hits)
    collectHits(carbLane, carbClusters, xPx, reach, hits)
    collectHits(exerciseLane, exerciseClusters, xPx, reach, hits)
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

/** Consumes nothing; register it FIRST, since dispatch is reverse registration order. */
internal suspend fun PointerInputScope.detectLogMarkerTaps(onTap: (Offset) -> Unit) {
    awaitEachGesture {
        // Judged after not requireUnconsumed: parks mid-gesture; a claimed down is another overlay.
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

/** Call inside plot clip and BEFORE the BG trace, or a hypo drops into this region occluded. */
internal fun DrawScope.drawLogMarkers(
    clusters: List<MarkerCluster>,
    painter: Painter,
    tint: ColorFilter,
    sizePx: Float,
    laneTopY: Float,
) {
    if (clusters.isEmpty()) return
    val glyph = Size(sizePx, sizePx)
    for (c in clusters) {
        translate(c.xPx - sizePx / 2f, laneTopY) {
            with(painter) {
                draw(
                    glyph,
                    alpha = LOG_MARKER_ALPHA,
                    colorFilter = tint,
                )
            }
        }
    }
}
