package com.t1dm.feature.game

import com.t1dm.core.model.CarState
import com.t1dm.ui.game.WORLD_HEIGHT_M

/** Flat scalars in PLAIN memory, outside Compose snapshot: reading in composition breaks that. */
class CarFrame {
    var x = 0f
    var y = 0f
    var angle = 0f
    var rearX = 0f
    var rearY = 0f
    var rearAngle = 0f
    var frontX = 0f
    var frontY = 0f
    var frontAngle = 0f
    var rearContact = false
    var frontContact = false
    var airborne = false
    var throttle = 0f
    var brake = 0f
    var distanceM = 0f

    /** Forward speed (m/s) and engine speed (rpm). */
    var speedMs = 0f
    var rpm = 0f

    /** [com.t1dm.core.model.RunState.ordinal]; an int, so no object reference crosses threads. */
    var run = 0

    var camLeft = 0f
    var camBottom = 0f
    var camWidth = VISIBLE_WIDTH_M

    /** World metres visible DOWN the panel; draw derives scale/labels from this, not `WorldMap`. */
    var camHeight = WORLD_HEIGHT_M

    /** [carLiftM]: WORLD metres above settled pose. Loop holds the solver until the drop lands. */
    var carShown = false
    var carLiftM = 0f

    /** The solver's own `throttleApplied`, not the pedal. */
    var throttleApplied = 0f

    /** 0 at the drop, 1 at the finish. Measured from the SEAT, not the track's origin. */
    var progress = 0f

    /** Emission phase in puff-intervals; wrapped, never into float's coarse range. */
    var exhaustPhase = 0f

    fun set(
        s: CarState,
        throttleIn: Float,
        brakeIn: Float,
        camera: GameCamera,
        camWidthM: Float,
        camHeightM: Float,
        carShownIn: Boolean,
        carLiftMIn: Float,
        exhaustPhaseIn: Float,
        progressIn: Float,
    ) {
        x = s.x
        y = s.y
        angle = s.angle
        rearX = s.rearX
        rearY = s.rearY
        rearAngle = s.rearAngle
        frontX = s.frontX
        frontY = s.frontY
        frontAngle = s.frontAngle
        rearContact = s.rearContact
        frontContact = s.frontContact
        airborne = s.airborne
        throttle = throttleIn
        brake = brakeIn
        distanceM = s.distanceM
        speedMs = s.vx
        rpm = s.rpm
        run = s.run.ordinal
        camLeft = camera.left
        camBottom = camera.bottom
        camWidth = camWidthM
        camHeight = camHeightM
        carShown = carShownIn
        carLiftM = carLiftMIn
        throttleApplied = s.throttleApplied
        exhaustPhase = exhaustPhaseIn
        progress = progressIn
    }
}

/** THREE buffers, not two: with two, the writer's 2nd swap hands back the reader's own buffer. */
class GameFrameBus {
    private val buffers = arrayOf(CarFrame(), CarFrame(), CarFrame())
    private var writeIndex = 0

    @Volatile
    var published: CarFrame = buffers[2]
        private set

    private val ticks = androidx.compose.runtime.mutableLongStateOf(0L)

    /** DRAW-PHASE READ ONLY: read in composition, every frame recomposes. */
    val tick: Long get() = ticks.longValue

    /** Valid until the next [commit]. */
    fun back(): CarFrame = buffers[writeIndex]

    fun commit() {
        published = buffers[writeIndex]
        writeIndex = (writeIndex + 1) % buffers.size
        ticks.longValue = ticks.longValue + 1L
    }
}

/** Plain volatile memory, not `mutableStateOf`: a resting finger else recomposes the screen. */
class GameControls {
    /** 1 while held, 0 while not. Written by the composition. */
    @Volatile
    var throttleTarget = 0f

    @Volatile
    var brakeTarget = 0f

    /** The target, ramped. Owned by the game thread. */
    var throttle = 0f
        private set

    var brake = 0f
        private set

    /** Raw boolean fed to a ~1.4 thrust/weight motor lifts the nose first. Press over [PRESS_S]. */
    fun ramp(dtS: Float) {
        throttle = approach(throttle, throttleTarget, dtS)
        brake = approach(brake, brakeTarget, dtS)
    }

    /** A pointer that never gets its up event must not leave the car pinned. */
    fun release() {
        throttleTarget = 0f
        brakeTarget = 0f
        throttle = 0f
        brake = 0f
    }

    private fun approach(now: Float, target: Float, dtS: Float): Float {
        if (dtS <= 0f) return now
        val rate = if (target > now) 1f / PRESS_S else 1f / RELEASE_S
        val step = rate * dtS
        return if (target > now) minOf(target, now + step) else maxOf(target, now - step)
    }

    private companion object {
        const val PRESS_S = 0.28f
        const val RELEASE_S = 0.12f
    }
}

/** Published from layout, for the game thread. Plain like [GameControls]: no frame recompose. */
class GameViewport {
    @Volatile
    var widthPx = 0f

    @Volatile
    var heightPx = 0f

    /** World metres visible ACROSS the panel — the graph window, one metre per minute. */
    @Volatile
    var visibleWidthM = VISIBLE_WIDTH_M

    /** Pixels per CAR-LOCAL metre the art is authored against, and the plot's horizontal inset. */
    @Volatile
    var carScalePx = 0f

    @Volatile
    var plotInsetPx = 0f

    val ready: Boolean get() = widthPx > 0f && heightPx > 0f

    /** [GameZoom]'s eased target: scale = [carScalePx], so a true-scale car matches the art. */
    val zoomedWidthM: Float
        get() {
            val plotW = widthPx - plotInsetPx
            return if (plotW > 0f && carScalePx > 0f) plotW / carScalePx else visibleWidthM
        }

    /** World metres visible down the panel: the full value span, not an aspect-derived slice. */
    var worldHeightM = 0f
}
