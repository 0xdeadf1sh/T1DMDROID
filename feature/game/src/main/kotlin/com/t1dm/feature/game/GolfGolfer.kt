package com.t1dm.feature.game

import com.t1dm.core.model.GolfRun
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAU = (2.0 * Math.PI).toFloat()

/** Stance behind the ball in ball radii: from here the club reaches it. */
private const val STANCE_R = 1.3f

/** m/s floor on the walk, and the seconds any walk is budgeted to take; distance sets the pace. */
private const val RUN_MIN_MS = 18f
internal const val ARRIVE_S = 3f

/** Seconds of a still-rolling ball watched before the figure gives up and follows it. */
internal const val WATCH_MAX_S = 6f

/** Ground covered by one full stride at [RUN_MIN_MS], in ball radii; it grows with the pace. */
private const val STRIDE_R = 2.4f

/** Close enough to the stance to play from; under it the figure snaps rather than shuffles. */
private const val ARRIVE_R = 0.1f

/** Radians of idle sway at address, and its rate (Hz). */
private const val SWAY_RAD = 0.05f
private const val SWAY_HZ = 0.5f

/** Radians of shoulder turn at a full-power pull; 45°, since the arm has no wrist to hinge. */
internal const val BACKSWING_RAD = 0.79f

/** Knee flex per act, in [0,1]; the draw stands the figure on [ADDRESS_BEND] to reach the ball. */
internal const val ADDRESS_BEND = 0.55f
private const val WALK_BEND = 0.30f
private const val WATCH_BEND = 0.15f
private const val CHEER_BEND = 0.25f

/** Weight off the front foot at a full pull, and what is left on it once the ball is gone. */
private const val WEIGHT_BACK = 0.30f
private const val WEIGHT_WATCH = 0.80f

/** Seconds for a pose scalar to reach its act's value, and for the walk blend to let go. */
private const val POSE_S = 0.12f
private const val WALK_BLEND_S = 0.18f

/** Radians the arms carry past the ball; the ball is struck at 0. */
private const val FOLLOW_RAD = -0.79f

/** Seconds from the top of the backswing through the ball, then held before the ball is watched. */
private const val DOWNSWING_S = 0.12f
private const val FOLLOW_HOLD_S = 0.40f

/** Seconds for the club to catch the shoulders; the lag IS the whip. */
private const val CLUB_LAG_S = 0.05f

/** Seconds to raise an arm to shade the eyes, and to unwind a cancelled backswing. */
private const val ARMS_S = 0.22f
private const val RELAX_S = 0.30f

/** Longest step taken in one advance; a long frame must not teleport the figure across the hole. */
private const val MAX_STEP_S = 0.1f

/** Plays the hole: loop-thread state machine; the draw reads its pose off [BallFrame]. */
internal class Golfer(private val ballRadiusM: Float, private val maxLaunchSpeed: Float) {
    internal enum class Act { Walk, Address, Backswing, Swing, Watch, Cheer }

    var act = Act.Address
        private set

    /** One per stroke: the handle on "the swing fired once, and once only". */
    var swings = 0
        private set

    private var x = 0f
    private var y = 0f
    private var facing = 1f
    private var shoulder = 0f
    private var club = 0f
    private var legPhase = 0f
    private var armsUp = 0f
    private var lean = 0f
    private var kneeBend = 0f
    private var weightFwd = 0.5f
    private var walk = 0f

    /** Screen distance the feet stand left of the ball; the draw's club head lands there. */
    @Volatile
    var stancePx = 0f
    private var stanceM = STANCE_R * ballRadiusM

    /** m/s held for the current walk; only ever raised, so a ball rolling away is still caught. */
    private var walkSpeed = 0f

    /** Seconds in the current act, and of sway; the sway wraps, so neither grows unbounded. */
    private var actS = 0f
    private var swayS = 0f

    /** Where the shoulders were when the downswing began. */
    private var swingTop = 0f

    private var strokes = 0
    private var penalties = 0

    /** Last FINITE ground under the feet: a stance over a gap keeps the height it walked in at. */
    private var ground = 0f
    private var placed = false

    /** The pull the frame is aiming, as a share of a full-power launch; 0 when it is not. */
    fun pullOf(f: BallFrame): Float {
        if (!f.aiming || maxLaunchSpeed <= 0f) return 0f
        val pull = hypot(f.aimVx, f.aimVy) / maxLaunchSpeed
        return if (pull.isFinite()) pull.coerceIn(0f, 1f) else 0f
    }

    /** A tee or a restart: stood at the stance at once, addressing, with no walk to play out. */
    fun place(f: BallFrame, groundAt: (Float) -> Float, pxPerXM: Float = 0f) {
        rescale(pxPerXM)
        x = stanceOf(f)
        facing = 1f
        shoulder = 0f
        club = 0f
        legPhase = 0f
        armsUp = 0f
        lean = 0f
        kneeBend = ADDRESS_BEND
        weightFwd = 0.5f
        walk = 0f
        walkSpeed = 0f
        actS = 0f
        swayS = 0f
        act = Act.Address
        strokes = f.strokes
        penalties = f.penalties
        placed = true
        settle(f, groundAt)
    }

    /** [f] AFTER the solver step; [pullFrac] is [pullOf], 0 when not aiming. */
    fun advance(f: BallFrame, pullFrac: Float, dtS: Float, groundAt: (Float) -> Float, pxPerXM: Float = 0f) {
        if (!placed) {
            place(f, groundAt, pxPerXM)
            return
        }
        rescale(pxPerXM)
        val dt = if (dtS.isFinite()) dtS.coerceIn(0f, MAX_STEP_S) else 0f
        val stance = stanceOf(f)
        val arrive = ARRIVE_R * ballRadiusM
        val pull = if (pullFrac.isFinite()) pullFrac.coerceIn(0f, 1f) else 0f
        val stroked = f.strokes != strokes
        val drowned = f.penalties != penalties
        strokes = f.strokes
        penalties = f.penalties

        // Terminal first, then the stroke: a ball that has dropped in is cheered, not swung at.
        if (f.run != GolfRun.Playing.ordinal) {
            if (act != Act.Cheer) enter(Act.Cheer)
        } else if (stroked) {
            swingTop = shoulder
            swings++
            enter(Act.Swing)
        } else if (drowned) {
            enter(Act.Walk)
        }

        actS += dt
        when (act) {
            Act.Walk -> {
                val dx = stance - x
                // Budgeted by distance, never lowered: 400 m of trace takes as long as 50 m does.
                val need = abs(dx) / ARRIVE_S
                if (need > walkSpeed) walkSpeed = maxOf(RUN_MIN_MS, need)
                val step = walkSpeed * dt
                if (abs(dx) <= step) {
                    x = stance
                    // A rolling ball is followed, not addressed: the stance re-aims every frame.
                    if (f.atRest) enter(Act.Address)
                } else {
                    val d = if (dx > 0f) step else -step
                    x += d
                    facing = if (dx > 0f) 1f else -1f
                    // Stride grows with pace, so the cadence rises as its root, not into a blur.
                    val stride = STRIDE_R * ballRadiusM * sqrt((walkSpeed / RUN_MIN_MS).coerceAtLeast(1f))
                    legPhase = (legPhase + TAU * abs(d) / stride.coerceAtLeast(1e-3f)) % TAU
                }
                lean = 0f
                pose(WALK_BEND, 0.5f, dt)
                relax(dt)
            }

            Act.Address -> {
                x = stance
                facing = 1f
                legPhase = 0f
                swayS = (swayS + dt) % (1f / SWAY_HZ)
                lean = SWAY_RAD * sin(TAU * SWAY_HZ * swayS)
                pose(ADDRESS_BEND, 0.5f, dt)
                relax(dt)
                if (f.aiming) {
                    enter(Act.Backswing)
                } else if (!f.atRest) {
                    enter(Act.Watch)
                }
            }

            Act.Backswing -> {
                x = stance
                facing = 1f
                legPhase = 0f
                // Weight back in proportion to the turn, so a full pull is visibly loaded.
                lean = -2f * SWAY_RAD * pull
                shoulder = pull * BACKSWING_RAD
                club = approach(club, shoulder, dt / CLUB_LAG_S)
                armsUp = approach(armsUp, 0f, dt / ARMS_S)
                pose(ADDRESS_BEND, 0.5f - WEIGHT_BACK * pull, dt)
                if (!f.aiming) enter(Act.Address)
            }

            Act.Swing -> {
                // NOT re-stanced: the ball is already gone, and the swing stays where it was made.
                facing = 1f
                legPhase = 0f
                lean = 0f
                val t = (actS / DOWNSWING_S).coerceIn(0f, 1f)
                shoulder = swingTop + (FOLLOW_RAD - swingTop) * t * t * (3f - 2f * t)
                club = approach(club, shoulder, dt / CLUB_LAG_S)
                armsUp = approach(armsUp, 0f, dt / ARMS_S)
                kneeBend = approach(kneeBend, ADDRESS_BEND, dt / POSE_S)
                // Driven by the strike, not eased toward it: the transfer IS the downswing.
                weightFwd = (0.5f - WEIGHT_BACK) + (0.5f + WEIGHT_BACK) * t
                if (actS >= DOWNSWING_S + FOLLOW_HOLD_S) enter(Act.Watch)
            }

            Act.Watch -> {
                facing = 1f
                legPhase = 0f
                lean = 0f
                shoulder = approach(shoulder, 0f, dt / RELAX_S)
                club = approach(club, shoulder, dt / CLUB_LAG_S)
                armsUp = approach(armsUp, 1f, dt / ARMS_S)
                pose(WATCH_BEND, WEIGHT_WATCH, dt)
                if (f.atRest) {
                    enter(if (abs(stance - x) > arrive) Act.Walk else Act.Address)
                } else if (actS >= WATCH_MAX_S) {
                    // Minutes of creep are not watched: the figure walks to wherever it has got to.
                    enter(Act.Walk)
                }
            }

            Act.Cheer -> {
                facing = 1f
                legPhase = 0f
                lean = 0f
                shoulder = approach(shoulder, 0f, dt / RELAX_S)
                club = approach(club, shoulder, dt / CLUB_LAG_S)
                // NOT eased: a holed round freezes the loop, so this is the last frame published.
                armsUp = 1f
                kneeBend = CHEER_BEND
                weightFwd = 0.5f
            }
        }
        walk = approach(walk, if (act == Act.Walk) 1f else 0f, dt / WALK_BLEND_S)
        settle(f, groundAt)
    }

    private fun stanceOf(f: BallFrame): Float {
        val s = f.x - stanceM
        return if (s.isFinite()) s else x
    }

    /** Pixels off the ball become metres at this zoom; unset, the stance is a few ball radii. */
    private fun rescale(pxPerXM: Float) {
        val m = stancePx / pxPerXM
        stanceM = if (stancePx > 0f && pxPerXM > 0f && m.isFinite()) m else STANCE_R * ballRadiusM
    }

    private fun enter(next: Act) {
        act = next
        actS = 0f
        // Re-budgeted on the distance the new walk actually faces, not the one before it.
        if (next == Act.Walk) walkSpeed = 0f
    }

    /** Knees and weight toward the act's own stance; both are shapes, not events. */
    private fun pose(bend: Float, fwd: Float, dt: Float) {
        kneeBend = approach(kneeBend, bend, dt / POSE_S)
        weightFwd = approach(weightFwd, fwd, dt / POSE_S)
    }

    /** Everything the swing owns, back toward address: the unwind a cancelled aim reads as. */
    private fun relax(dt: Float) {
        shoulder = approach(shoulder, 0f, dt / RELAX_S)
        club = approach(club, shoulder, dt / CLUB_LAG_S)
        armsUp = approach(armsUp, 0f, dt / ARMS_S)
    }

    /** Feet onto the ground, then the whole pose onto the frame. */
    private fun settle(f: BallFrame, groundAt: (Float) -> Float) {
        val g = groundAt(x)
        if (g.isFinite()) ground = g
        y = ground
        f.golferShown = true
        f.golferX = x
        f.golferY = y
        f.golferFacing = facing
        f.shoulderRad = shoulder
        f.clubRad = club
        f.legPhase = legPhase
        f.armsUp = armsUp
        f.lean = lean
        f.kneeBend = kneeBend
        f.weightFwd = weightFwd
        f.golferWalk = walk
    }

    private fun approach(now: Float, target: Float, k: Float): Float =
        now + (target - now) * k.coerceIn(0f, 1f)
}
