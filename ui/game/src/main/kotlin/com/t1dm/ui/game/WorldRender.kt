package com.t1dm.ui.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.t1dm.ui.graph.ChalkPens
import com.t1dm.ui.graph.MIN_STROKE_PX
import com.t1dm.ui.graph.dotWithTool
import com.t1dm.ui.graph.strokeWithTool
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Draw the annotation layer where it was drawn — in the world, not on the glass.
 *
 * The camera is a pure affine: `screenX = (worldX − camLeft)·pxPerWorld`, `screenY = floorPx −
 * worldY·pxPerWorld` (the sign flip is the y-up world meeting a y-down canvas). Nothing is rebuilt as
 * the camera moves; a frame is two multiply-adds per point, one rewound [scratch] path shared by every
 * stroke, and one [ChalkPens] built by the caller — the same zero-allocation contract the BG panel's
 * own paint pass holds, for the same reason (this runs 60 times a second).
 *
 * CALL THIS BEFORE THE GROUND FILL. Everything the player scribbled below the trace is then covered by
 * the terrain and everything above it is sky scenery, with no depth test and no second pass: see
 * [appendGroundLine], the stroked curve it sits beneath.
 *
 * Two culls, mirroring `drawPaintFrame`. Whole strokes go in O(1) on their scanned world-x bounds;
 * inside a survivor, segments wholly outside the view (± one camera width of slack) are skipped and the
 * path is cut there, which also keeps emitted coordinates near the canvas instead of kilometres off it.
 */
fun DrawScope.drawWorldPaint(
    paint: WorldPaint,
    camLeft: Float,
    camWidth: Float,
    pxPerWorld: Float,
    floorPx: Float,
    scratch: Path,
    chalk: ChalkPens,
    pxPerWorldY: Float = pxPerWorld,
) {
    if (paint.isEmpty || camWidth <= 0f || pxPerWorld <= 0f) return
    val camRight = camLeft + camWidth
    val slackLo = camLeft - camWidth
    val slackHi = camRight + camWidth

    for (s in 0 until paint.strokeCount) {
        if (!paint.intersects(s, camLeft, camRight)) continue
        val a = paint.offsets[s]
        val b = paint.offsets[s + 1]
        val color = Color(paint.colors[s])
        val w = (paint.widths[s] * pxPerWorld).coerceAtLeast(MIN_STROKE_PX)
        val tool = paint.tools[s]

        if (b - a <= 1) {
            dotWithTool(
                Offset(
                    (paint.xs[a] - camLeft) * pxPerWorld,
                    floorPx - paint.ys[a] * pxPerWorldY,
                ),
                color, w, tool,
            )
            continue
        }

        scratch.rewind()
        var open = false
        var emitted = false
        for (i in a until b - 1) {
            val x0 = paint.xs[i]
            val x1 = paint.xs[i + 1]
            if ((x0 < slackLo && x1 < slackLo) || (x0 > slackHi && x1 > slackHi)) {
                open = false
                continue
            }
            if (!open) {
                scratch.moveTo((x0 - camLeft) * pxPerWorld, floorPx - paint.ys[i] * pxPerWorldY)
                open = true
            }
            scratch.lineTo((x1 - camLeft) * pxPerWorld, floorPx - paint.ys[i + 1] * pxPerWorldY)
            emitted = true
        }
        if (emitted) strokeWithTool(scratch, color, w, tool, chalk)
    }
}

/**
 * The terrain as an OPEN polyline — the BG curve itself, with nothing below it.
 *
 * Drive mode is a mode of the BG panel, so the curve is STROKED and nothing is painted beneath it —
 * a filled region under the trace reads as solid ground in a game but as a coloured mass under a
 * chart. Gaps stay gaps: a dropout breaks the line rather than bridging it.
 */
fun GameTrack.appendGroundLine(
    path: Path,
    camLeft: Float,
    camWidth: Float,
    pxPerWorld: Float,
    floorPx: Float,
    pxPerWorldY: Float = pxPerWorld,
) {
    path.rewind()
    val n = heights.size
    if (n == 0 || camWidth <= 0f || pxPerWorld <= 0f) return

    val iLo = floor((camLeft - dx) / dx).toInt().coerceIn(0, n - 1)
    val iHi = ceil((camLeft + camWidth + dx) / dx).toInt().coerceIn(0, n - 1)
    val stride = ceil(1f / (dx * pxPerWorld)).toInt().coerceIn(1, 8)

    var i = iLo
    var open = false
    while (i <= iHi) {
        val h = heights[i]
        if (h.isNaN()) {
            open = false
        } else {
            val px = (i * dx - camLeft) * pxPerWorld
            val py = floorPx - h * pxPerWorldY
            if (!open) { path.moveTo(px, py); open = true } else path.lineTo(px, py)
        }
        i = if (i == iHi) i + 1 else (i + stride).coerceAtMost(iHi)
    }
}
