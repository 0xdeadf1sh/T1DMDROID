package com.t1dm.ui.graph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.t1dm.core.model.ReconstructedBg

/** In the forecasts hand: a run opens/closes into the trace at zero width, breaks at gaps. */
internal fun DrawScope.drawReconstruction(
    rows: List<ReconstructedBg>,
    xOf: (Long) -> Float,
    yOf: (Double) -> Float,
    ink: Color,
    fanColor: Color,
    plotLeft: Float,
    plotRight: Float,
    /** The trace's y in pixels at a grid slot; null where it draws nothing there. */
    anchorPxAt: (Long) -> Float?,
    /** Hoisted by caller, reused every band/run: Path is native, else 3 built per run per frame. */
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

        // The measured slot either side; zero width, since a measurement has no band.
        val leftTs = run.first().tsMs - GRID_MS
        val rightTs = run.last().tsMs + GRID_MS
        val leftPx = anchorPxAt(leftTs)?.let { Vertex(xOf(leftTs), it) }
        val rightPx = anchorPxAt(rightTs)?.let { Vertex(xOf(rightTs), it) }

        if (run.all { it.bands.size == FAN_LEVELS }) {
            // Innermost pair first, outermost last and heaviest, so the composite darkens inwards.
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
        // On the run's last slot, not the anchor past it: the anchor is a reading, drawn as one.
        val last = run.last()
        if (last.mgdl.isFinite()) {
            val x = xOf(last.tsMs)
            if (x >= plotLeft && x <= plotRight) {
                drawCircle(ink, 3.2f, Offset(x, yOf(last.mgdl)), style = Stroke(width = 1.6f))
            }
        }
    }
}

/** One filled τ pair. A non-finite edge breaks the polygon rather than bridging it. */
private inline fun DrawScope.drawBand(
    run: List<ReconstructedBg>,
    lower: (ReconstructedBg) -> Double,
    upper: (ReconstructedBg) -> Double,
    xOf: (Long) -> Float,
    yOf: (Double) -> Float,
    color: Color,
    /** Zero-width open; only the first stretch gets it. */
    openAt: Vertex?,
    /** Zero-width close; only the last stretch gets it. */
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
            // Only a stretch at the run's own end has anything measured bracketing it.
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

/** Already resolved to pixels. */
internal data class Vertex(val x: Float, val y: Float)

private fun usable(lo: Double, hi: Double): Boolean = lo.isFinite() && hi.isFinite() && hi >= lo

private const val GRID_MS = 300_000L

/** `SPEC/invariants.md` §6. */
private const val FAN_LEVELS = 7

// Pairs outer→inner over ascending-τ columns: 0=.05 1=.10 2=.25 3=.50 4=.75 5=.90 6=.95.
private const val BANDS = 3
private val LO_COLS = intArrayOf(0, 1, 2)
private val HI_COLS = intArrayOf(6, 5, 4)

internal data class ReconTween(
    val from: List<ReconstructedBg>,
    val to: List<ReconstructedBg>,
)

/** The only case a move interpolates: sliding between slot sets draws through unspoken-of slots. */
internal fun sameSlots(a: List<ReconstructedBg>, b: List<ReconstructedBg>): Boolean {
    if (a.size != b.size || a.isEmpty()) return false
    for (i in a.indices) if (a[i].tsMs != b[i].tsMs) return false
    return true
}

/** Only [ReconstructedBg.mgdl] moves: a τ sweep retraces an already-emitted fan, unchanged. */
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
