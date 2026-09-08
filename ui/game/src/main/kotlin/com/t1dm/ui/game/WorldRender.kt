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

/** Allocates nothing/frame: [scratch] rewinds; CALL BEFORE GROUND FILL, no depth test. */
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

/** Open polyline, never filled — the BG curve; a dropout breaks the line, not bridges it. */
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
