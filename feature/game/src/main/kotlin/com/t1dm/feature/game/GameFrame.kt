package com.t1dm.feature.game

import com.t1dm.core.model.CarState
import com.t1dm.ui.game.WORLD_HEIGHT_M

/**
 * One finished frame: the car, the run, and the camera transform that goes with it — flat scalars in
 * PLAIN memory, deliberately outside the Compose snapshot system.
 *
 * This is the whole isolation trick, and it is the same one `GlucoseGraph` uses for its live stroke
 * buffer: the simulation writes here and nothing observes it, so a frame invalidates NOTHING. What the
 * draw phase subscribes to is a single monotone counter ([GameFrameBus.tick]) read inside the draw
 * lambda and nowhere else — so a new frame redraws and never recomposes. Read this object anywhere in
 * composition scope and that guarantee is gone.
 *
 * The camera rides in the frame rather than being followed by the renderer because it is a function of
 * the same timestep the solver consumed: computing it in the draw would either need the delta again or
 * settle at whatever rate the draw happened to run at.
 */
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

    /** Forward speed (m/s) and engine speed (rpm) — the two gauges. Carried on the frame like every
     *  other drawn quantity so the dials repaint in the draw phase, never as a recomposition. */
    var speedMs = 0f
    var rpm = 0f

    /** [com.t1dm.core.model.RunState.ordinal] — an int so the frame stays free of object references
     *  the writer would otherwise be publishing across threads. */
    var run = 0

    var camLeft = 0f
    var camBottom = 0f
    var camWidth = VISIBLE_WIDTH_M

    /** World metres visible DOWN the panel. Carried per frame because the speed widening opens both axes
     *  (`GameZoom.fov`) — so the value axis shows more than the configured BG range at speed, and the
     *  draw must derive its vertical scale and its axis labels from this rather than from
     *  `WorldMap.worldHeight`. */
    var camHeight = WORLD_HEIGHT_M

    /** The opening, carried on the frame rather than animated in composition. [carShown] is false for
     *  the whole zoom — see [GameZoom.settled] — and [carLiftM] is how far above its settled pose the
     *  car is still falling, in WORLD metres, so the drop rides the vertical scale like everything else.
     *  Both live here because the loop owns the timing: it holds the solver until the drop lands, and a
     *  composition-side animation could not be kept in step with a hold it cannot see. */
    var carShown = false
    var carLiftM = 0f

    /** The solver's own `throttleApplied` rather than the pedal, so the exhaust follows what the engine
     *  was actually given. */
    var throttleApplied = 0f

    /** How far along the run the car is, 0 at the drop and 1 at the finish. Computed in the loop rather
     *  than the draw because it is measured from the SEAT — the tap, not the track's origin — and the
     *  draw has no way to know where that was. */
    var progress = 0f

    /** The exhaust's emission phase, in puff-intervals. Advanced by the LOOP at a rate set by
     *  [throttleApplied], so the trail is stateless in the draw: a puff's age is a function of this
     *  number and its index, and nothing has to be stored between frames. Wrapped, so it never grows
     *  into float's coarse range. */
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

/**
 * The single-writer / single-reader hand-off between the game thread and the draw phase.
 *
 * THREE buffers, not two. With two, the writer's second swap after the reader has taken a reference
 * hands it back the buffer being read — a torn frame under a scheduling hiccup. With three the writer
 * has to complete two whole frames inside one draw before it can catch up, which at a 60 fps cap it
 * cannot; the cost is one extra flat object for the life of the screen.
 *
 * [tick] is the ONE piece of snapshot state in the frame path. It is `mutableLongStateOf`-backed so a
 * frame boxes nothing, and it must be read inside the draw lambda only.
 */
class GameFrameBus {
    private val buffers = arrayOf(CarFrame(), CarFrame(), CarFrame())
    private var writeIndex = 0

    @Volatile
    var published: CarFrame = buffers[2]
        private set

    private val ticks = androidx.compose.runtime.mutableLongStateOf(0L)

    /** DRAW-PHASE READ ONLY. Reading this in composition subscribes the composable and every frame
     *  then recomposes — see [CarFrame]. */
    val tick: Long get() = ticks.longValue

    /** The buffer to fill for the frame being computed. Valid until the next [commit]. */
    fun back(): CarFrame = buffers[writeIndex]

    /** Publish the filled buffer and invalidate the draw. */
    fun commit() {
        published = buffers[writeIndex]
        writeIndex = (writeIndex + 1) % buffers.size
        ticks.longValue = ticks.longValue + 1L
    }
}

/**
 * Throttle and brake, in plain volatile memory.
 *
 * The pedals are pointer handlers that write here directly instead of hoisting a `mutableStateOf`: a
 * finger resting on the throttle would otherwise recompose the whole screen on every pointer event,
 * and the pressed state is drawn from this same memory on the next frame anyway (which is at most
 * 16 ms away).
 */
class GameControls {
    /** What the pedal is asking for: 1 while held, 0 while not. Written by the composition. */
    @Volatile
    var throttleTarget = 0f

    @Volatile
    var brakeTarget = 0f

    /** What the solver is actually given — the target RAMPED. Owned by the game thread. */
    var throttle = 0f
        private set

    var brake = 0f
        private set

    /**
     * Ease the applied controls toward their targets.
     *
     * A pedal is a boolean, and feeding that straight to a motor with a thrust-to-weight of ~1.4 means
     * every brush of the glass is a full-power step from rest — which lifts the nose before it moves
     * the car. Pressing ramps in over [PRESS_S]; releasing is quicker ([RELEASE_S]) because a control
     * that lingers after the finger has gone feels broken rather than smooth.
     */
    fun ramp(dtS: Float) {
        throttle = approach(throttle, throttleTarget, dtS)
        brake = approach(brake, brakeTarget, dtS)
    }

    /** Cut both instantly — a pointer that never gets its up event must not leave the car pinned. */
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

/** The canvas size, published out of layout for the game thread to derive the camera's world extent
 *  from. Plain memory for the same reason as [GameControls]: a rotation or an inset change must not
 *  be a frame-path recomposition. */
class GameViewport {
    @Volatile
    var widthPx = 0f

    @Volatile
    var heightPx = 0f

    /** World metres visible ACROSS the panel — the user's own graph window (one metre per minute), so
     *  the game shows exactly the span the chart does rather than a zoom of its own. */
    @Volatile
    var visibleWidthM = VISIBLE_WIDTH_M

    /** Pixels per CAR-LOCAL metre the art is authored against, and the plot's total horizontal inset.
     *  Published out of composition purely so the loop can derive [zoomedWidthM] without knowing
     *  anything about density, insets or the tune. */
    @Volatile
    var carScalePx = 0f

    @Volatile
    var plotInsetPx = 0f

    val ready: Boolean get() = widthPx > 0f && heightPx > 0f

    /** The span [GameZoom] eases toward: the one at which the world's horizontal scale EQUALS
     *  [carScalePx], so the car drawn true-scale is exactly the size the art was designed at. Falls
     *  back to the chart's own span before layout has reported a width, so the zoom never targets zero. */
    val zoomedWidthM: Float
        get() {
            val plotW = widthPx - plotInsetPx
            return if (plotW > 0f && carScalePx > 0f) plotW / carScalePx else visibleWidthM
        }

    /** World metres visible down the panel. The vertical scale is the world's full value span — the
     *  configured BG range — not an aspect-derived slice, because value maps down the height
     *  independently of time across the width, exactly as the graph does. */
    var worldHeightM = 0f
}
