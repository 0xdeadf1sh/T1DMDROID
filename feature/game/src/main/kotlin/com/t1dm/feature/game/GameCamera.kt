package com.t1dm.feature.game

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/** How much world is visible across the SHORT axis of a portrait phone, in metres. The car is ~2.8 m,
 *  so this sets its on-screen size: at 10 m it is well over a quarter of the width, legible enough to read the
 *  suspension working inside a 220 dp panel. Zooming the camera is the only honest lever — scaling the
 *  sprite alone would put the car out of proportion with the ground it drives on. */
const val VISIBLE_WIDTH_M = 10f

/** Where the car sits across the viewport at rest: a little left of centre, so most of the screen is
 *  the ground still to come. */
private const val ANCHOR_X = 0.38f

/**
 * Vertical DEAD BAND, as a fraction of the view height either side.
 *
 * The panel's natural state is the chart's own: the whole configured BG range, bottom at world zero,
 * axis labels where the user left them. So the vertical camera does nothing at all while the car is
 * comfortably inside the view — it is not a follow-cam, it is a rescue. Only when the car climbs past
 * the top band (a hyper excursion, or a jump off one) or drops below the bottom one does the view
 * shift, by exactly enough to bring it back to the band edge.
 *
 * Without this the view was pinned to world 0…worldHeight and a car above the axis ceiling simply left
 * the screen.
 */
private const val V_DEAD_BAND = 0.18f

/** Seconds of velocity the camera looks ahead by. Small: the lead is meant to open the corner, not to
 *  outrun the car and leave it drifting off the trailing edge at top speed. */
private const val LEAD_S = 0.45f

/** Ceiling on the lead, as a FRACTION of the visible span — a launch off a ramp must not fling the
 *  viewport somewhere the car is not.
 *
 *  Proportional for the same reason as [EDGE_SLACK_FRAC], and the limiter is what forced it: at
 *  101 m/s the raw lead wants 45 m, so a fixed 9 m cap was saturated at any speed worth leading and
 *  the term had stopped being a function of velocity at all. A fraction keeps the car at the same
 *  place on the glass whether the panel is showing 45 minutes or a day. */
private const val LEAD_MAX_FRAC = 0.12f

/** How briskly the camera closes on its target, as an exponential rate (1/s). ~6 is a settle time of
 *  a couple of hundred ms: enough to absorb suspension chatter, not enough to feel like lag. */
private const val FOLLOW_HZ = 6.5f

/** How far past either end of the heightfield the camera may show, as a FRACTION of the visible span,
 *  so the start line and the finish do not sit hard against the screen edge.
 *
 *  Proportional rather than a fixed distance because the car is drawn at a constant on-screen size
 *  while world metres per pixel changes with the window: at 6 h half a car is ~23 world metres, at 24 h
 *  ~93. A fixed slack that cleared one would clip the car in half at the other, which is exactly what a
 *  6 m constant did at the start line. */
private const val EDGE_SLACK_FRAC = 0.12f

/** Ground kept below the bottom edge when the car is near the world floor. */
private const val FLOOR_MARGIN_M = 2.5f

/** How briskly the zoom closes on its target, as an exponential rate (1/s). ~1.6 settles in about two
 *  seconds: slow enough to read as the world opening up rather than as a cut. */
private const val ZOOM_HZ = 1.6f

/** The drop, once the zoom has arrived. Short and high: a reveal that reads as the car ARRIVING after
 *  a zoom the player has just watched, rather than a fade. */
private const val REVEAL_S = 0.42f

private const val REVEAL_DROP_M = 6f

/** Extra view, as a fraction, at the rev limiter — so the world opens to 1.6× as the car winds up and
 *  closes again as it slows. Both axes, hence the car keeps its shape and simply shrinks; the value axis
 *  therefore shows MORE than the configured BG range at speed, which is honest and is why the axis
 *  labels are derived from the frame's own span rather than from `WORLD_HEIGHT_M`. */
private const val FOV_WIDEN = 0.6f

/**
 * The visible span, eased.
 *
 * Drive mode used to adopt the chart's span wholesale and keep it, on the principle that entering the
 * mode must not move the panel. The cost was that the car could not be drawn true-scale — at a 5 h
 * window one world metre is about a pixel — so it was a constant-size marker, and a constant-size car
 * on a variable-scale ground can only ever register ONE point against the terrain (see [drawCar]).
 * Everything else floated or sank.
 *
 * So the span is now a destination rather than a constant: it STARTS at the chart's, which preserves
 * the no-jump property exactly — the first frame of drive mode is the panel the user was looking at —
 * and then eases to the span at which the world's horizontal scale equals the car's own. There the car
 * is true-scale, the two axis scales agree to about a tenth, and every wheel meets the curve it is on.
 *
 * Eased GEOMETRICALLY, not linearly. The two spans differ by a factor of ten or more, and a linear ramp
 * across that spends its first half covering ground the eye reads as barely moving and its second half
 * as a rush. Interpolating the LOGARITHM holds the relative rate constant, which is what reads as an
 * even zoom.
 */
class GameZoom(
    private val rateHz: Float = ZOOM_HZ,
    /** Seconds the car takes to fall in once the span has arrived, and from how far above in WORLD
     *  metres. INJECTABLE for the same reason `runGameLoop` takes its pacer: the opening is a hold, so
     *  a test that wants to watch the solver step would otherwise have to spend two dozen frames
     *  getting past an animation it does not care about. Pass `revealS = 0f` and the opening costs
     *  exactly the frames placement always cost. */
    private val revealS: Float = REVEAL_S,
    private val dropM: Float = REVEAL_DROP_M,
) {
    var spanM = 0f
        private set

    /**
     * The speed widening, eased, 1 at rest.
     *
     * Applied to BOTH axes by the caller, and that is not optional: the car is drawn TRUE SCALE, so
     * widening only the time axis would leave the vertical scale where it was and squash every circle
     * on the car into an ellipse — at the limiter the wheels would be 2:1. Widening the value axis with
     * it keeps the world's aspect fixed, so the car keeps its shape and simply gets smaller, which is
     * what "see more" should mean.
     */
    var fov = 1f
        private set

    /** Whether the span has effectively arrived. */
    var settled = false
        private set

    /** The drop's progress, 0..1. Only advances once [settled]. */
    var reveal = 0f
        private set

    /**
     * The whole opening — zoom then drop. A HOLD on the solver while true: a car that drove during its
     * own reveal would arrive somewhere the camera had not been told about.
     *
     * LATCHED once the drop lands, and it has to be. [settled] compares the span against a target that
     * now MOVES with [fov], so a car accelerating away would drive the span out of tolerance, un-settle
     * the zoom, and re-arm this hold — freezing the solver mid-run, every time the player touched the
     * throttle. Only [seatAt] clears it.
     */
    val opening: Boolean get() = !opened

    private var opened = false

    /** The car is not drawn until the span has arrived. Drawn true-scale, a car at the chart's span is a
     *  few pixels wide and squashed by the axis anisotropy — spawning it early shows exactly the frame
     *  the zoom exists to avoid. */
    val carShown: Boolean get() = reveal > 0f

    /** How far above its settled pose the car still is. Quadratic, so it accelerates into the ground the
     *  way a fall does rather than easing onto it. */
    val liftM: Float get() = dropM * (1f - reveal) * (1f - reveal)

    /** Open on an explicit span — the chart's own, every placement. */
    fun seatAt(spanM: Float) {
        this.spanM = spanM
        settled = false
        reveal = 0f
        opened = false
        fov = 1f
    }

    /**
     * Advance one frame and return the span to use for it. [baseM] is the settled span — the one at which
     * the car is true-scale — and [speedMs] widens it.
     *
     * The widening is eased through [fov] rather than applied raw, so the view does not pump on every
     * throttle blip, and the span then eases toward `baseM · fov` on top of that. Two filters in series
     * is deliberate: the outer one is what makes a launch open the view smoothly instead of snapping it.
     */
    fun step(baseM: Float, speedMs: Float, dtS: Float): Float {
        if (dtS <= 0f) return spanM
        val a = (1f - exp(-rateHz * dtS)).coerceIn(0f, 1f)
        val speed = if (speedMs.isFinite()) abs(speedMs) else 0f
        val wantFov = 1f + FOV_WIDEN * (speed / TOP_SPEED_MS).coerceIn(0f, 1f)
        fov += (wantFov - fov) * a
        val targetM = baseM * fov
        if (spanM > 0f && targetM > 0f && targetM.isFinite()) {
            spanM = exp(ln(spanM) + (ln(targetM) - ln(spanM)) * a)
            // RELATIVE, because the ease is geometric and the two ends differ by an order of magnitude:
            // a fixed metre bound would read as arrived immediately at a day's span and never at half
            // an hour's.
            settled = abs(spanM - targetM) <= SETTLE_FRAC * targetM
        }
        if (settled) {
            reveal = if (revealS <= 0f) 1f else (reveal + dtS / revealS).coerceAtMost(1f)
            if (reveal >= 1f) opened = true
        }
        return spanM
    }

    private companion object {
        const val SETTLE_FRAC = 0.03f
    }
}

/**
 * The viewport's position over the world: the y-UP world coordinates of its bottom-left corner.
 *
 * Pure, frame-rate independent, and deliberately NOT a Compose animation — it is stepped on the game
 * thread beside the solver and published in the same frame buffer, so the draw phase receives a
 * finished transform and does no following of its own. Everything here is in world metres; the pixel
 * scale (`pxPerWorld = widthPx / `[VISIBLE_WIDTH_M]) belongs to the draw.
 *
 * The smoothing is `1 − e^(−rate·dt)` rather than a fixed per-frame fraction, so a dropped frame
 * moves the camera the same distance a run of short frames would: the same reason `t1dm-core::game`
 * consumes wall-clock time in fixed ticks instead of one step per frame.
 */
class GameCamera(
    private val followHz: Float = FOLLOW_HZ,
    private val leadSeconds: Float = LEAD_S,
) {
    var left = 0f
        private set

    var bottom = 0f
        private set

    /** Seat the camera at an explicit left edge — used to open on the chart's own viewport so the
     *  panel does not jump when drive mode starts. */
    fun seatAt(leftM: Float, carY: Float, viewH: Float) {
        left = leftM
        bottom = targetBottom(carY, viewH)
    }

    /** Place the camera where [follow] would eventually settle, with no lead — every frame after a
     *  reset. */
    fun snapTo(carX: Float, carY: Float, viewW: Float, viewH: Float, trackLength: Float) {
        left = targetLeft(carX, 0f, viewW, trackLength)
        bottom = targetBottom(carY, viewH)
    }

    /** Advance one frame of following. [dtS] is the same wall-clock delta handed to the solver. */
    fun follow(
        carX: Float,
        carY: Float,
        vx: Float,
        vy: Float,
        viewW: Float,
        viewH: Float,
        trackLength: Float,
        dtS: Float,
    ) {
        val a = smoothing(dtS)
        left += (targetLeft(carX, vx, viewW, trackLength) - left) * a
        // The vertical axis leads too, but only on the way UP: a fall is the one moment the player
        // most needs to see what is below, and a downward lead would show them sky.
        bottom += (targetBottom(carY + max(0f, vy) * leadSeconds, viewH) - bottom) * a
    }

    private fun smoothing(dtS: Float): Float =
        if (dtS <= 0f) 0f else (1f - exp(-followHz * dtS)).coerceIn(0f, 1f)

    private fun targetLeft(carX: Float, vx: Float, viewW: Float, trackLength: Float): Float {
        val leadCap = viewW * LEAD_MAX_FRAC
        val lead = (vx * leadSeconds).coerceIn(-leadCap, leadCap)
        val slack = viewW * EDGE_SLACK_FRAC
        val lo = -slack
        // A track shorter than the viewport has no room to pan: the bound collapses onto `lo` and the
        // whole of it stays on screen.
        val hi = max(lo, trackLength + slack - viewW)
        return (carX + lead - viewW * ANCHOR_X).coerceIn(lo, hi)
    }

    private fun targetBottom(carY: Float, viewH: Float): Float = targetBottomFrom(bottom, carY, viewH)

    /** Pure, so the dead-band rule can be tested without a camera instance. */
    internal fun targetBottomFrom(current: Float, carY: Float, viewH: Float): Float {
        val m = viewH * V_DEAD_BAND
        val lo = current + m
        val hi = current + viewH - m
        val want = when {
            carY > hi -> carY - viewH + m
            carY < lo -> carY - m
            else -> current
        }
        // Never scroll so far that the ground drops off the bottom of the panel entirely.
        return want.coerceAtLeast(-FLOOR_MARGIN_M)
    }
}
