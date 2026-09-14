package com.t1dm.feature.game

import com.t1dm.core.model.BallState
import java.util.concurrent.atomic.AtomicLong

/** Flat scalars, PLAIN memory, outside Compose snapshot: the sim writes, nothing observes it. */
class BallFrame : WorldFrame() {
    var x = 0f
    var y = 0f

    /** Rolled angle, positive rolling toward +x. */
    var angle = 0f
    var vx = 0f
    var vy = 0f
    var atRest = false
    var airborne = false
    var strokes = 0
    var penalties = 0

    /** [com.t1dm.core.model.GolfRun.ordinal]; an int, so no object reference crosses threads. */
    var run = 0

    /** ballLiftM: above-settled height, WORLD metres; loop holds solver until the drop lands. */
    var ballShown = false
    var ballLiftM = 0f

    /** Launch velocity being aimed (world m/s); the preview arc is drawn from it. */
    var aiming = false
    var aimVx = 0f
    var aimVy = 0f

    /** Where the tee mark goes. */
    var teeX = 0f
    var teeY = 0f

    /** Last drowning, and how fresh it is in [0,1]; 0 draws nothing. */
    var splashX = 0f
    var splashY = 0f
    var splash = 0f

    /** [Golfer]'s outputs, written after [set] and before the commit; feet at golferX, golferY. */
    var golferShown = false
    var golferX = 0f
    var golferY = 0f

    /** +1 looking toward the hole, −1 back down the trace. */
    var golferFacing = 1f

    /** Radians of swing from address, positive BACK; the club lags the shoulders. */
    var shoulderRad = 0f
    var clubRad = 0f

    /** Radians of one full stride, and how far the arms are raised in [0,1]. */
    var legPhase = 0f
    var armsUp = 0f

    /** Radians the upper body tilts toward the hole. */
    var lean = 0f

    /** Knee flex and weight on the front foot, both [0,1]; 0.5 of the weight is even. */
    var kneeBend = 0f
    var weightFwd = 0.5f

    /** How much of the figure is walking, [0,1]: eased, so arriving does not snap the stance. */
    var golferWalk = 0f

    fun set(
        s: BallState,
        camera: GameCamera,
        camWidthM: Float,
        camHeightM: Float,
        shown: Boolean,
        liftM: Float,
        controls: GolfControls,
        teeXIn: Float,
        teeYIn: Float,
        splashXIn: Float,
        splashYIn: Float,
        splashIn: Float,
        progressIn: Float,
    ) {
        x = s.x
        y = s.y
        angle = s.angle
        vx = s.vx
        vy = s.vy
        atRest = s.atRest
        airborne = s.airborne
        strokes = s.strokes
        penalties = s.penalties
        run = s.run.ordinal
        camLeft = camera.left
        camBottom = camera.bottom
        camWidth = camWidthM
        camHeight = camHeightM
        ballShown = shown
        ballLiftM = liftM
        aiming = controls.aiming
        aimVx = controls.aimVx
        aimVy = controls.aimVy
        teeX = teeXIn
        teeY = teeYIn
        splashX = splashXIn
        splashY = splashYIn
        splash = splashIn
        progress = progressIn
    }
}

class GolfFrameBus : FrameBus<BallFrame>({ BallFrame() })

/** Plain volatile memory, not mutableStateOf: a drag would recompose on every pointer event. */
class GolfControls {
    @Volatile
    var aiming = false

    /** The launch the current pull would fire (world m/s); zero cancels the shot. */
    @Volatile
    var aimVx = 0f

    @Volatile
    var aimVy = 0f

    /** A pointer that never gets its up event must not leave an arc on the panel. */
    fun release() {
        aiming = false
        aimVx = 0f
        aimVy = 0f
    }
}

/** Plain memory: a restart is a press, not state composition has reason to observe. */
class GolfCommands {
    @Volatile
    var reset = false

    /** Two floats in one word, so the loop takes a whole shot or none of it. */
    private val pending = AtomicLong(0L)

    fun fire(vx: Float, vy: Float) {
        val bits = (vx.toRawBits().toLong() and 0xFFFF_FFFFL) shl 32 or
            (vy.toRawBits().toLong() and 0xFFFF_FFFFL)
        // Zero IS "nothing pending", and a zero-velocity shot is a no-op the solver refuses anyway.
        if (bits != 0L) pending.set(bits)
    }

    /** 0 when nothing is pending; the shot is consumed by reading it. */
    fun takeShot(): Long = pending.getAndSet(0L)

    companion object {
        fun shotVx(bits: Long): Float = Float.fromBits((bits ushr 32).toInt())

        fun shotVy(bits: Long): Float = Float.fromBits(bits.toInt())
    }
}
