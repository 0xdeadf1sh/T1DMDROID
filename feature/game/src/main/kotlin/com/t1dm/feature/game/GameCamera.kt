package com.t1dm.feature.game

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/** World visible across the SHORT axis of a portrait phone, in metres. The car is ~2.8 m. */
const val VISIBLE_WIDTH_M = 10f

/** Fraction of the viewport the car sits at when at rest. */
private const val ANCHOR_X = 0.38f

/** Vertical dead band, fraction of view height either side; a rescue, not a follow-cam. */
private const val V_DEAD_BAND = 0.18f

/** Seconds of velocity the camera looks ahead by. */
private const val LEAD_S = 0.45f

/** Ceiling on the lead, fraction of visible span; proportional, else a fixed cap saturates. */
private const val LEAD_MAX_FRAC = 0.12f

/** Exponential rate, 1/s. ~6 settles in a couple of hundred ms. */
private const val FOLLOW_HZ = 6.5f

/** Slack past either end of heightfield, fraction of visible span; proportional to window. */
private const val EDGE_SLACK_FRAC = 0.12f

/** Ground kept below the bottom edge when the car is near the world floor. */
private const val FLOOR_MARGIN_M = 2.5f

/** Exponential rate, 1/s. ~1.6 settles in about two seconds. */
private const val ZOOM_HZ = 1.6f

/** The drop, once the zoom has arrived. */
private const val REVEAL_S = 0.42f

private const val REVEAL_DROP_M = 6f

/** Extra view at the rev limiter, both axes so the car keeps shape; value axis over-shows BG. */
private const val FOV_WIDEN = 0.6f

/** Visible span, eased; starts at the chart's own, eases to true-scale, on the LOGARITHM. */
class GameZoom(
    private val rateHz: Float = ZOOM_HZ,
    /** Seconds the car takes to fall in once span arrives; injectable, so tests can skip it. */
    private val revealS: Float = REVEAL_S,
    private val dropM: Float = REVEAL_DROP_M,
    /** Speed at which [fov] reaches full widening; each game's own ceiling, not the car's. */
    private val fullScaleSpeedMs: Float = TOP_SPEED_MS,
    private val fovWiden: Float = FOV_WIDEN,
) {
    var spanM = 0f
        private set

    /** Speed widening, eased, 1 at rest; caller must apply to BOTH axes or wheels squash. */
    var fov = 1f
        private set

    var settled = false
        private set

    /** 0..1; only advances once [settled]. */
    var reveal = 0f
        private set

    /** Zoom then drop; a hold on the solver while true, latched once landed; seatAt clears it. */
    val opening: Boolean get() = !opened

    private var opened = false

    /** Drawn true-scale, a car at the chart's span is a few pixels wide and squashed. */
    val carShown: Boolean get() = reveal > 0f

    /** How far above its settled pose the car is; quadratic, so it accelerates like a fall. */
    val liftM: Float get() = dropM * (1f - reveal) * (1f - reveal)

    fun seatAt(spanM: Float) {
        this.spanM = spanM
        settled = false
        reveal = 0f
        opened = false
        fov = 1f
    }

    /** baseM is the true-scale span, speedMs widens it; two filters, fov eases then span eases. */
    fun step(baseM: Float, speedMs: Float, dtS: Float): Float {
        if (dtS <= 0f) return spanM
        val a = (1f - exp(-rateHz * dtS)).coerceIn(0f, 1f)
        val speed = if (speedMs.isFinite()) abs(speedMs) else 0f
        val wantFov = 1f + fovWiden * (speed / fullScaleSpeedMs).coerceIn(0f, 1f)
        fov += (wantFov - fov) * a
        val targetM = baseM * fov
        if (spanM > 0f && targetM > 0f && targetM.isFinite()) {
            spanM = exp(ln(spanM) + (ln(targetM) - ln(spanM)) * a)
            // Relative, because the ease is geometric: a fixed metre bound would fit only one span.
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

/** Viewport's bottom-left, world metres; stepped on the game thread, not a Compose animation. */
class GameCamera(
    private val followHz: Float = FOLLOW_HZ,
    private val leadSeconds: Float = LEAD_S,
) {
    var left = 0f
        private set

    var bottom = 0f
        private set

    /** Opens on the chart's own viewport, so the panel does not jump when drive mode starts. */
    fun seatAt(leftM: Float, carY: Float, viewH: Float) {
        left = leftM
        bottom = targetBottom(carY, viewH)
    }

    /** Where [follow] would eventually settle, with no lead. */
    fun snapTo(carX: Float, carY: Float, viewW: Float, viewH: Float, trackLength: Float) {
        left = targetLeft(carX, 0f, viewW, trackLength)
        bottom = targetBottom(carY, viewH)
    }

    /** [dtS] is the same wall-clock delta handed to the solver. */
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
        // Leads up only: a downward lead would show sky during the fall.
        bottom += (targetBottom(carY + max(0f, vy) * leadSeconds, viewH) - bottom) * a
    }

    private fun smoothing(dtS: Float): Float =
        if (dtS <= 0f) 0f else (1f - exp(-followHz * dtS)).coerceIn(0f, 1f)

    private fun targetLeft(carX: Float, vx: Float, viewW: Float, trackLength: Float): Float {
        val leadCap = viewW * LEAD_MAX_FRAC
        val lead = (vx * leadSeconds).coerceIn(-leadCap, leadCap)
        val slack = viewW * EDGE_SLACK_FRAC
        val lo = -slack
        // A track shorter than the viewport has no room to pan.
        val hi = max(lo, trackLength + slack - viewW)
        return (carX + lead - viewW * ANCHOR_X).coerceIn(lo, hi)
    }

    private fun targetBottom(carY: Float, viewH: Float): Float = targetBottomFrom(bottom, carY, viewH)

    internal fun targetBottomFrom(current: Float, carY: Float, viewH: Float): Float {
        val m = viewH * V_DEAD_BAND
        val lo = current + m
        val hi = current + viewH - m
        val want = when {
            carY > hi -> carY - viewH + m
            carY < lo -> carY - m
            else -> current
        }
        // Never scroll the ground off the bottom of the panel.
        return want.coerceAtLeast(-FLOOR_MARGIN_M)
    }
}
