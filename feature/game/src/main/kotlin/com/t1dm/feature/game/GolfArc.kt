package com.t1dm.feature.game

import com.t1dm.ui.game.GameTrack
import kotlin.math.hypot

/** Pull, as a fraction of the panel's short side, that fires at full launch speed. */
internal const val DRAG_FULL_FRAC = 0.28f

/** Pull under this is a cancel, not a shot; a stray tap must not dribble the ball. */
internal const val DRAG_DEAD_PX = 14f

/** Press radius in ball radii: a fingertip is wider than the ball at any settled zoom. */
internal const val GRAB_RADII = 5f

/** Seconds between preview samples; 96 of them covers the longest hang at the longest carry. */
internal const val ARC_STEP_S = 0.05f

/** Sampled points the preview may draw. */
internal const val ARC_POINTS = 96

/** Slingshot aim, reused in place; the drag path allocates nothing. */
class GolfAim {
    var vx = 0f
        private set

    var vy = 0f
        private set

    /** Pull back from the ball (screen y DOWN); the launch is its opposite, capped at maxSpeed. */
    fun set(dxPx: Float, dyPx: Float, fullPx: Float, maxSpeed: Float) {
        val pull = hypot(dxPx, dyPx)
        val span = fullPx - DRAG_DEAD_PX
        if (!pull.isFinite() || pull <= DRAG_DEAD_PX || span <= 0f || maxSpeed <= 0f) {
            clear()
            return
        }
        val speed = maxSpeed * ((pull - DRAG_DEAD_PX) / span).coerceIn(0f, 1f)
        val k = speed / pull
        vx = -dxPx * k
        // Screen y grows down, world y up: pulling the finger downward launches the ball upward.
        vy = dyPx * k
    }

    fun clear() {
        vx = 0f
        vy = 0f
    }
}

/** Pull length that fires at full speed, for a panel this size. */
internal fun dragFullPx(widthPx: Float, heightPx: Float): Float =
    (minOf(widthPx, heightPx) * DRAG_FULL_FRAC).coerceAtLeast(DRAG_DEAD_PX * 2f)

/** A press this close to the ball starts an aim. */
internal fun grabRadiusPx(ballRadiusM: Float, pxPerXM: Float, minPx: Float): Float =
    (ballRadiusM * pxPerXM * GRAB_RADII).coerceAtLeast(minPx)

internal fun nearBall(dxPx: Float, dyPx: Float, grabPx: Float): Boolean =
    hypot(dxPx, dyPx) <= grabPx

/** Flight is drag-free in the solver, so this closed form IS the arc, not an approximation. */
internal fun ballisticArc(
    x0: Float,
    y0: Float,
    vx: Float,
    vy: Float,
    gravity: Float,
    stepS: Float,
    /** Ball radius: the centre lands this far above the ground line. */
    clearanceM: Float,
    track: GameTrack,
    /** Filled with world x,y pairs; the return is the point count, not the float count. */
    out: FloatArray,
): Int {
    val cap = out.size / 2
    if (cap < 2 || !(gravity > 0f) || !(stepS > 0f)) return 0
    if (!vx.isFinite() || !vy.isFinite() || !x0.isFinite() || !y0.isFinite()) return 0
    if (vx == 0f && vy == 0f) return 0
    out[0] = x0
    out[1] = y0
    var n = 1
    var t = 0f
    while (n < cap) {
        t += stepS
        val nx = x0 + vx * t
        val ny = y0 + vy * t - 0.5f * gravity * t * t
        if (!nx.isFinite() || !ny.isFinite()) break
        val ground = track.groundAt(nx)
        val floor = ground + clearanceM
        val landed = !ground.isNaN() && ny <= floor
        out[2 * n] = nx
        out[2 * n + 1] = if (landed) floor else ny
        n++
        // A gap swallows the ball, so the preview ends there too rather than drawing over nothing.
        if (landed || ground.isNaN()) break
    }
    return n
}
