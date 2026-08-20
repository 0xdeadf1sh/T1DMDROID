package com.t1dm.ui.graph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.t1dm.core.model.ReconstructedBg

/**
 * Draw a model's reconstruction of a stretch of the BG curve, in the same hand the forecast is
 * drawn in: its quantile fan, then its line over the top.
 *
 * **The same appearance as a forecast, deliberately.** A fill and a forecast are the same artifact
 * under different inputs — one objective, one head, one fan — so drawing them in two visual
 * languages claimed a difference the model does not make. The pairs, the alphas, the stroke weight
 * and the endpoint marker are all [drawPredSeries]'s, and the ink is the caller's forecast ink.
 * What still separates a reconstruction from a MEASUREMENT is what always did: the trace's own
 * glucose colours are not used here, and no reconstruction is ever drawn as a reading.
 *
 * **A run OPENS FROM the trace and CLOSES INTO it.** [anchorPxAt] resolves whatever the panel draws
 * at the slot either side of a run, and that point is added to the line and to every band edge at
 * zero width — the idiom `buildPredSeries` uses to grow a forecast out of the last CGM reading
 * instead of leaving it floating one step ahead. Without it a reconstruction was drawn only over its
 * own slots, so a gap of one grid step sat at each end; sweeping τ off the median then lifted the
 * whole line away from the trace and the gap became a visible step.
 *
 * The zero width is a statement about where the RECONSTRUCTION begins and ends, not a claim that the
 * neighbour is certain: the join is to the curve the eye is following, and that point keeps its own
 * provenance styling in the trace beneath. The caller does refuse to join onto another
 * RECONSTRUCTED point, which would pinch two guesses together as though they met at something known.
 *
 * **A run is broken at every discontinuity.** The polygons are built per contiguous stretch of grid
 * slots: closing one across a hole would fill a region the model never spoke about.
 *
 * **A span with no fan is drawn as the single band it is.** That state is routine, not exotic: the
 * wire carries a boolean and no fan, so a promoted value that comes back from a re-mirror, or from
 * a restore onto a second phone, has lost its uncertainty for good, and a row written before the
 * fan was kept never had one. Its `lo90`/`hi90` pair is drawn at the outer band's own weight and
 * nothing is invented between the edges.
 */
internal fun DrawScope.drawReconstruction(
    rows: List<ReconstructedBg>,
    xOf: (Long) -> Float,
    yOf: (Double) -> Float,
    ink: Color,
    fanColor: Color,
    plotLeft: Float,
    plotRight: Float,
    /** The drawn trace's y in PIXELS at a grid slot, or null where it draws nothing there. */
    anchorPxAt: (Long) -> Float?,
    /** Hoisted by the caller and reused for every band of every run, as `drawPredSeries` does with
     *  its own: a `Path` is a native object with a `NativeAllocationRegistry` finalizer, and this
     *  built three per run per frame. `reset()` keeps the capacity, so the vertex arrays are
     *  allocated once for the life of the composition. */
    scratch: Path,
) {
    if (rows.isEmpty()) return

    var i = 0
    while (i < rows.size) {
        var end = i + 1
        while (end < rows.size && rows[end].tsMs - rows[end - 1].tsMs == GRID_MS) end++
        val run = rows.subList(i, end)
        i = end
        if (run.none { xOf(it.tsMs) >= plotLeft && xOf(it.tsMs) <= plotRight }) continue

        // The measured slot either side, in pixels. A zero-width vertex: a measurement has no band.
        val leftTs = run.first().tsMs - GRID_MS
        val rightTs = run.last().tsMs + GRID_MS
        val leftPx = anchorPxAt(leftTs)?.let { Vertex(xOf(leftTs), it) }
        val rightPx = anchorPxAt(rightTs)?.let { Vertex(xOf(rightTs), it) }

        if (run.all { it.bands.size == FAN_LEVELS }) {
            // `drawPredSeries`'s own order and alphas: innermost pair first, outermost last and
            // heaviest over the top, so the composite darkens towards the centre.
            for (b in BANDS - 1 downTo 0) {
                drawBand(
                    run = run,
                    lower = { it.bands[LO_COLS[b]] },
                    upper = { it.bands[HI_COLS[b]] },
                    xOf = xOf,
                    yOf = yOf,
                    color = fanColor.copy(alpha = 0.06f + 0.05f * (2 - b)),
                    openAt = leftPx,
                    closeAt = rightPx,
                    scratch = scratch,
                )
            }
        } else {
            drawBand(
                run = run,
                lower = ReconstructedBg::lo90,
                upper = ReconstructedBg::hi90,
                xOf = xOf,
                yOf = yOf,
                color = fanColor.copy(alpha = 0.16f),
                openAt = leftPx,
                closeAt = rightPx,
                scratch = scratch,
            )
        }

        // The line, at the forecast median's own weight and solidity, joined to the trace at both
        // ends so it is continuous with the curve it stands in for.
        val line = ArrayList<Vertex>(run.size + 2)
        leftPx?.let { line.add(it) }
        for (r in run) if (r.mgdl.isFinite()) line.add(Vertex(xOf(r.tsMs), yOf(r.mgdl)))
        rightPx?.let { line.add(it) }
        for (j in 0 until line.size - 1) {
            val a = line[j]
            val b = line[j + 1]
            if (b.x < plotLeft || a.x > plotRight) continue
            drawLine(ink, Offset(a.x, a.y), Offset(b.x, b.y), strokeWidth = 2.4f, cap = StrokeCap.Round)
        }
        // The endpoint marker the forecast carries, so a run's far edge is legible. On the run's own
        // last slot rather than the anchor past it: the anchor is a reading and already drawn as one.
        val last = run.last()
        if (last.mgdl.isFinite()) {
            val x = xOf(last.tsMs)
            if (x >= plotLeft && x <= plotRight) {
                drawCircle(ink, 3.2f, Offset(x, yOf(last.mgdl)), style = Stroke(width = 1.6f))
            }
        }
    }
}

/**
 * One filled τ pair over a contiguous run.
 *
 * Slots whose edges are not finite are skipped, and skipping one BREAKS the polygon rather than
 * bridging it — a fan closed across a slot it says nothing about draws a region the model never
 * emitted.
 */
private inline fun DrawScope.drawBand(
    run: List<ReconstructedBg>,
    lower: (ReconstructedBg) -> Double,
    upper: (ReconstructedBg) -> Double,
    xOf: (Long) -> Float,
    yOf: (Double) -> Float,
    color: Color,
    /** The measured point the fan opens from, at zero width. Only the FIRST stretch gets it. */
    openAt: Vertex?,
    /** The measured point it closes into. Only the LAST stretch gets it. */
    closeAt: Vertex?,
    scratch: Path,
) {
    var start = 0
    var first = true
    while (start < run.size) {
        while (start < run.size && !usable(lower(run[start]), upper(run[start]))) start++
        var stop = start
        while (stop < run.size && usable(lower(run[stop]), upper(run[stop]))) stop++
        if (stop - start >= 1) {
            // A stretch touching the run's own ends carries the anchor; one that starts or stops at
            // a hole inside the run does not, since nothing measured brackets it there.
            val open = if (first && start == 0) openAt else null
            val close = if (stop == run.size) closeAt else null
            if (stop - start >= 2 || (open != null && close != null)) {
                val path = scratch.also { it.reset() }
                open?.let { path.moveTo(it.x, it.y) }
                for (k in start until stop) {
                    val x = xOf(run[k].tsMs)
                    val y = yOf(upper(run[k]))
                    if (k == start && open == null) path.moveTo(x, y) else path.lineTo(x, y)
                }
                close?.let { path.lineTo(it.x, it.y) }
                for (k in stop - 1 downTo start) {
                    path.lineTo(xOf(run[k].tsMs), yOf(lower(run[k])))
                }
                open?.let { path.lineTo(it.x, it.y) }
                path.close()
                drawPath(path, color)
            }
            first = false
        }
        start = if (stop == start) start + 1 else stop
    }
}

/** A point already resolved to pixels — the trace's own y at a slot the fan meets. */
internal data class Vertex(val x: Float, val y: Float)

private fun usable(lo: Double, hi: Double): Boolean = lo.isFinite() && hi.isFinite() && hi >= lo

private const val GRID_MS = 300_000L

/** The levels the head emits (`SPEC/invariants.md` §6). */
private const val FAN_LEVELS = 7

// The three pairs `buildPredSeries` draws a forecast fan from, outer→inner. Ascending-τ columns:
// 0=.05 1=.10 2=.25 3=.50 4=.75 5=.90 6=.95.
private const val BANDS = 3
private val LO_COLS = intArrayOf(0, 1, 2)
private val HI_COLS = intArrayOf(6, 5, 4)

/** A reconstruction as it is DRAWN, between the levels it was at and the levels it is moving to. */
internal data class ReconTween(
    val from: List<ReconstructedBg>,
    val to: List<ReconstructedBg>,
)

/**
 * Whether two row sets describe the SAME slots — the only case a move may be interpolated through.
 *
 * A τ sweep moves the line at slots that all still exist. A fill landing, a span being discarded or
 * the window scrolling changes which slots there are, and sliding one set into another would draw a
 * curve through slots the model never spoke about.
 */
internal fun sameSlots(a: List<ReconstructedBg>, b: List<ReconstructedBg>): Boolean {
    if (a.size != b.size || a.isEmpty()) return false
    for (i in a.indices) if (a[i].tsMs != b[i].tsMs) return false
    return true
}

/**
 * The line between two readings of the same fan.
 *
 * Only [ReconstructedBg.mgdl] moves: a τ sweep changes which level of an already-emitted fan is
 * traced, and the fan itself is the same numbers throughout. Interpolating the band edges too would
 * be animating a shape that never changed.
 */
internal fun lerpReconstruction(
    from: List<ReconstructedBg>,
    to: List<ReconstructedBg>,
    t: Float,
): List<ReconstructedBg> {
    if (t >= 1f || !sameSlots(from, to)) return to
    val p = t.toDouble().coerceIn(0.0, 1.0)
    return List(to.size) { i ->
        val a = from[i].mgdl
        val b = to[i].mgdl
        if (!a.isFinite() || !b.isFinite()) to[i] else to[i].copy(mgdl = a + (b - a) * p)
    }
}
