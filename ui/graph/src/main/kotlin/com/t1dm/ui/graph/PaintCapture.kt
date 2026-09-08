package com.t1dm.ui.graph

import com.t1dm.core.model.PaintStroke

/** Floor of the min-distance capture gate, dp; [paintMinStepPx] widens it for a wide nib. */
internal const val PAINT_MIN_STEP_DP = 2f

private const val PAINT_STEPS_PER_WIDTH = 8f

/** An eighth of the nib: the coarsest step a round join still swallows (sagitta = width/64). */
internal fun paintMinStepPx(widthDp: Float, dpPx: Float): Float =
    maxOf(PAINT_MIN_STEP_DP, widthDp / PAINT_STEPS_PER_WIDTH) * dpPx

/** Eraser reach in dp, beyond a stroke's own half-width. */
internal const val PAINT_ERASE_RADIUS_DP = 10f

/** A stroke longer than this is refused rather than truncated; see [StrokeCapture.add]. */
internal const val PAINT_MAX_POINTS = 8192

/** In-flight stroke in [PaintStroke] coords: abs epoch-ms, plot-box fraction. Gate is in PIXELS. */
internal class StrokeCapture {
    private var ts = LongArray(256)
    private var ys = FloatArray(256)

    var size: Int = 0
        private set

    /** Pen-down wall clock; also the paint order. */
    var createdAtMs: Long = 0L
        private set

    private var lastXPx = 0f
    private var lastYPx = 0f

    fun begin(nowMs: Long) {
        size = 0
        createdAtMs = nowMs
    }

    fun abandon() {
        size = 0
    }

    fun tsAt(i: Int): Long = ts[i]

    fun yFracAt(i: Int): Float = ys[i]

    /** True when accepted. Gated on distance from the last ACCEPTED sample, not the prior one. */
    fun add(xPx: Float, yPx: Float, tsMs: Long, yFrac: Float, minStepPx: Float): Boolean {
        if (size > 0) {
            val dx = xPx - lastXPx
            val dy = yPx - lastYPx
            if (dx * dx + dy * dy < minStepPx * minStepPx) return false
        }
        return append(xPx, yPx, tsMs, yFrac)
    }

    /** The lift-off sample, bypassing the distance gate. */
    fun addFinal(xPx: Float, yPx: Float, tsMs: Long, yFrac: Float): Boolean {
        if (size > 0 && xPx == lastXPx && yPx == lastYPx) return false
        return append(xPx, yPx, tsMs, yFrac)
    }

    private fun append(xPx: Float, yPx: Float, tsMs: Long, yFrac: Float): Boolean {
        if (size >= PAINT_MAX_POINTS) return false
        if (size == ts.size) {
            ts = ts.copyOf(ts.size * 2)
            ys = ys.copyOf(ys.size * 2)
        }
        ts[size] = tsMs
        ys[size] = yFrac
        size++
        lastXPx = xPx
        lastYPx = yPx
        return true
    }

    /** Null when nothing was captured. `id = 0`: the store mints the row id. */
    fun toStroke(tool: String, colorArgb: Int, widthDp: Float): PaintStroke? {
        if (size == 0) return null
        return PaintStroke(
            id = 0L,
            createdAtMs = createdAtMs,
            tool = tool,
            colorArgb = colorArgb,
            widthDp = widthDp,
            tsMs = ts.copyOf(size),
            yFrac = ys.copyOf(size),
        )
    }
}

/** No stroke was hit; row ids are `autoGenerate` and so always >= 1, leaving 0 free. */
internal const val NO_STROKE = 0L

/** TOPMOST stroke under (xPx,yPx), or [NO_STROKE]. Erases whole strokes, never part of one. */
internal fun hitTestPaint(
    paint: PaintFrame,
    xPx: Float,
    yPx: Float,
    radiusPx: Float,
    viewStartMs: Double,
    ppm: Double,
    plotLeft: Float,
    plotTop: Float,
    plotHeight: Float,
    dpPx: Float,
): Long {
    for (s in paint.strokeCount - 1 downTo 0) {
        val a = paint.offsets[s]
        val b = paint.offsets[s + 1]
        if (b <= a) continue
        val tol = radiusPx + (paint.widthsDp[s] * dpPx) / 2f
        val tol2 = tol * tol
        var px = paintXPx(paint.tsMs[a], viewStartMs, ppm, plotLeft)
        var py = paintYPx(paint.yFrac[a], plotTop, plotHeight)
        if (b - a == 1) {
            val dx = xPx - px
            val dy = yPx - py
            if (dx * dx + dy * dy <= tol2) return paint.ids[s]
            continue
        }
        for (i in a + 1 until b) {
            val qx = paintXPx(paint.tsMs[i], viewStartMs, ppm, plotLeft)
            val qy = paintYPx(paint.yFrac[i], plotTop, plotHeight)
            if (pointSegmentDistanceSq(xPx, yPx, px, py, qx, qy) <= tol2) return paint.ids[s]
            px = qx
            py = qy
        }
    }
    return NO_STROKE
}

internal fun pointSegmentDistanceSq(
    px: Float, py: Float,
    ax: Float, ay: Float,
    bx: Float, by: Float,
): Float {
    val vx = bx - ax
    val vy = by - ay
    val len2 = vx * vx + vy * vy
    val t = if (len2 <= 0f) 0f else (((px - ax) * vx + (py - ay) * vy) / len2).coerceIn(0f, 1f)
    val dx = px - (ax + t * vx)
    val dy = py - (ay + t * vy)
    return dx * dx + dy * dy
}
