package com.t1dm.ui.graph

import com.t1dm.core.model.PaintStroke

/**
 * The authoring half of the BG panel's annotation layer: the growable buffer a live stroke is captured
 * into, the minimum-distance decimation applied AS it is captured, and the eraser's hit test.
 *
 * Deliberately Android-free so all of it is unit-tested on the host JVM ([PaintCaptureTest]) — the
 * Canvas and the pointer handler above it contribute only pixels and events. Nothing here reads or
 * writes anything but the annotation layer: painting is decorative, and no stroke has ever been an
 * input to a calculator, a model channel, an alarm, or any §3.6 rail.
 */

/**
 * Two consecutive pointer samples closer than this on screen are the same point to the eye. A pointer
 * reports at display rate, so a finger drawn slowly — or held still — deposits hundreds of coincident
 * samples per second; gating on distance rather than on time keeps a fast stroke's detail while
 * refusing to pay for a slow one. The FLOOR of that gate, and its whole value for every narrow tool;
 * [paintMinStepPx] widens it in proportion once the pen outgrows it.
 *
 * This is the FIRST of two thinnings and the one that matters for storage: it decides what reaches the
 * blob. [buildPaintFrame]'s pass is a second, render-side sweep over whatever a past build (or another
 * device's backup) happened to write.
 */
internal const val PAINT_MIN_STEP_DP = 2f

/** How many samples a pen is allowed per nib width; see [paintMinStepPx]. */
private const val PAINT_STEPS_PER_WIDTH = 8f

/**
 * The gate for a pen [widthDp] wide. [PAINT_MIN_STEP_DP] is a FLOOR rather than the whole rule because
 * 2 dp was sized for a 2–18 dp nib: at the flood pen's 96 dp it keeps some fifty samples per nib width,
 * and every one of them is re-tessellated into a stroke outline on every draw pass — for the live
 * stroke, which has no viewport cull at all, and for the committed one on every frame of a pan. Round
 * joins tessellate with the radius, so the cost of that redundancy grows with the very width that makes
 * it redundant.
 *
 * An eighth of the nib is the coarsest step whose faceting the round join still swallows: the sagitta
 * of a chord that long, on a turn as tight as the pen itself, is a sixty-fourth of the width — under
 * two dp at the widest setting, which is to say inside the ink. A brush cannot show detail finer than
 * its own nib, so nothing the user drew is lost; only samples they could never have seen.
 */
internal fun paintMinStepPx(widthDp: Float, dpPx: Float): Float =
    maxOf(PAINT_MIN_STEP_DP, widthDp / PAINT_STEPS_PER_WIDTH) * dpPx

/** The eraser's reach, in dp, beyond a stroke's own half-width — a fingertip is far wider than a line. */
internal const val PAINT_ERASE_RADIUS_DP = 10f

/** A stroke longer than this is refused rather than truncated; see [StrokeCapture.add]. */
internal const val PAINT_MAX_POINTS = 8192

/**
 * The in-flight stroke: parallel primitive arrays that grow amortised, in the SAME coordinates the
 * store keeps ([PaintStroke] — absolute epoch-ms and plot-box height fraction), so the live overlay and
 * the persisted stroke are projected through one function and cannot disagree.
 *
 * The min-distance gate is evaluated in PIXELS, because "far enough apart to be worth keeping" is a
 * property of the screen, not of the data: the same finger movement spans four hours at a 24 h window
 * and four minutes at a 15-minute one, and decimating in ms would make a zoomed-in stroke coarse.
 *
 * Reused across strokes — [begin] rewinds rather than reallocating, so a session of drawing allocates
 * only when a stroke outgrows the buffer.
 */
internal class StrokeCapture {
    private var ts = LongArray(256)
    private var ys = FloatArray(256)

    /** Points accepted so far. */
    var size: Int = 0
        private set

    /** Wall clock at pen-down — the stroke's authoring instant, and its paint order. */
    var createdAtMs: Long = 0L
        private set

    private var lastXPx = 0f
    private var lastYPx = 0f

    fun begin(nowMs: Long) {
        size = 0
        createdAtMs = nowMs
    }

    /** Discard the in-flight stroke without emitting it (a second finger landed; the gesture is a pan). */
    fun abandon() {
        size = 0
    }

    fun tsAt(i: Int): Long = ts[i]

    fun yFracAt(i: Int): Float = ys[i]

    /**
     * Offer a sample. Accepted — and reported true — only when it is at least [minStepPx] from the last
     * ACCEPTED sample (the first always is), which is what keeps a slow drag from bloating the blob.
     * Distance is measured from the last accepted point rather than from the previous *sample*, so a
     * dawdling finger cannot accumulate its way past the gate one sub-pixel at a time.
     *
     * Past [PAINT_MAX_POINTS] the stroke stops growing rather than being strided down: a stroke that
     * long is a finger left on the glass, and silently resampling it would move points the user drew.
     */
    fun add(xPx: Float, yPx: Float, tsMs: Long, yFrac: Float, minStepPx: Float): Boolean {
        if (size > 0) {
            val dx = xPx - lastXPx
            val dy = yPx - lastYPx
            if (dx * dx + dy * dy < minStepPx * minStepPx) return false
        }
        return append(xPx, yPx, tsMs, yFrac)
    }

    /**
     * Force-accept the lift-off sample, bypassing the gate: a stroke must end where the finger left the
     * glass, not at the last point that happened to clear the distance test. An exact repeat of the
     * last accepted point is still refused — it would draw nothing and cost 12 bytes.
     */
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

    /**
     * The stroke to persist, or null when nothing was captured. `id = 0`: the row id is minted by the
     * store, so the caller must fold the returned id back into its undo stack rather than assume one.
     */
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

/** No stroke was hit. Row ids are `autoGenerate` and therefore always ≥ 1, so 0 is free to mean this. */
internal const val NO_STROKE = 0L

/**
 * Which stroke does the point `(xPx, yPx)` erase?
 *
 * TOPMOST FIRST — the frame is in paint order, so walking it backwards erases what the user sees on
 * top, which is the only answer that matches the screen. The test is distance to the stroke's
 * SEGMENTS, not to its points: a long straight stroke has two points metres apart on screen, and a
 * nearest-point test would refuse to erase it anywhere in between.
 *
 * The tolerance is [radiusPx] plus the stroke's own half-width, so a broad highlighter is erasable
 * anywhere it is visible while a fine line still demands a reasonably deliberate touch. That the
 * segment-distance test tracks the visible extent EXACTLY, rather than over-reaching by a growing
 * margin, is a property of the round cap: a flat-nibbed pen paints nothing past its endpoints yet
 * measures as if it did, which is a few dp of slop for the highlighter and would be tens of dp for
 * [com.t1dm.core.model.PaintTool.BROAD] — the reason that pen is round-capped.
 *
 * Topmost-first has a consequence worth stating for an OPAQUE pen: one broad pass covers, and thereby
 * shadows, every stroke it crosses, so the eraser can no longer reach them where they are hidden. That
 * is the contract working — it erases what the user sees — but it makes undo, not the eraser, the way
 * back out of a pass laid down in the wrong place.
 *
 * Erasing removes WHOLE strokes rather than cutting holes in them. That is what keeps undo a stack of
 * strokes instead of a stack of geometry edits, and it is why the eraser is a palette mode rather than
 * a [com.t1dm.core.model.PaintTool].
 */
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

/** Squared distance from `(px, py)` to the segment `(ax, ay) → (bx, by)`; a degenerate segment is a point. */
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
