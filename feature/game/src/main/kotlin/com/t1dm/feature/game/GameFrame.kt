package com.t1dm.feature.game

import com.t1dm.core.model.CarState
import com.t1dm.ui.game.WORLD_HEIGHT_M

/** What GameShell itself draws from: the camera window and the run's progress along the trace. */
open class WorldFrame {
    var camLeft = 0f
    var camBottom = 0f
    var camWidth = VISIBLE_WIDTH_M

    /** World metres visible DOWN the panel; draw derives vertical scale from this, not WorldMap. */
    var camHeight = WORLD_HEIGHT_M

    /** 0 at the drop, 1 at the finish. Measured from the SEAT, not the track's origin. */
    var progress = 0f

    /** Simulated seconds since the drop: the scenery's clock, so a hold freezes the sky too. */
    var simS = 0f
}

/** Flat scalars, PLAIN memory, outside Compose snapshot: the sim writes, nothing observes it. */
class CarFrame : WorldFrame() {
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

    /** carLiftM: above-settled height, WORLD metres; loop holds solver until the drop lands. */
    var carShown = false
    var carLiftM = 0f

    /** The solver's own `throttleApplied`, not the pedal. */
    var throttleApplied = 0f

    /** Emission phase in puff-intervals, loop-advanced; wrapped, never grows into float's range. */
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

/** Single writer (game), single reader (draw). THREE buffers: two lets writer catch the reader. */
open class FrameBus<T : WorldFrame>(factory: () -> T) {
    private val buffers = listOf(factory(), factory(), factory())
    private var writeIndex = 0

    @Volatile
    var published: T = buffers[2]
        private set

    private val ticks = androidx.compose.runtime.mutableLongStateOf(0L)

    /** DRAW-PHASE READ ONLY: read in composition, every frame recomposes. */
    val tick: Long get() = ticks.longValue

    /** Valid until the next [commit]. */
    fun back(): T = buffers[writeIndex]

    fun commit() {
        published = buffers[writeIndex]
        writeIndex = (writeIndex + 1) % buffers.size
        ticks.longValue = ticks.longValue + 1L
    }
}

class GameFrameBus : FrameBus<CarFrame>({ CarFrame() })

/** Plain volatile memory, not mutableStateOf: a resting finger would recompose on every event. */
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

    /** A pedal is boolean, fed raw to a ~1.4 thrust ratio motor: lifts the nose before it moves. */
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

/** Published out of layout for the game thread; plain memory, a rotation must not recompose it. */
class GameViewport {
    @Volatile
    var widthPx = 0f

    @Volatile
    var heightPx = 0f

    /** World metres visible ACROSS the panel: the graph window, METRES_PER_MINUTE a minute. */
    @Volatile
    var visibleWidthM = VISIBLE_WIDTH_M

    /** Pixels per CAR-LOCAL metre the art is authored against, and the plot's horizontal inset. */
    @Volatile
    var carScalePx = 0f

    @Volatile
    var plotInsetPx = 0f

    /** Settled span (m) where the play sets the scale, not an art size; 0 = use carScalePx. */
    @Volatile
    var settledWidthM = 0f

    val ready: Boolean get() = widthPx > 0f && heightPx > 0f

    /** The span GameZoom eases toward: world scale = carScalePx, car drawn at its authored size. */
    val zoomedWidthM: Float
        get() {
            if (settledWidthM > 0f) return settledWidthM
            val plotW = widthPx - plotInsetPx
            return if (plotW > 0f && carScalePx > 0f) plotW / carScalePx else visibleWidthM
        }

    /** World metres visible down the panel: the full value span, not an aspect-derived slice. */
    var worldHeightM = 0f
}
