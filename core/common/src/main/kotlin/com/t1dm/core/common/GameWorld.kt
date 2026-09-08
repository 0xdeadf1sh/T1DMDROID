package com.t1dm.core.common

import com.t1dm.core.model.CarState

/** Terrain crosses FFI once at construction; Cleaner-freed — hold in remember{}, close it there. */
interface GameWorld : AutoCloseable {
    /** x of the finish line; fixed at construction. */
    val trackLength: Float

    /** [dtMs] wall-clock, fixed 1/120s ticks, surplus dropped; throttle/brake saturate [0,1]. */
    fun step(dtMs: Float, throttle: Float, brake: Float): CarState

    /** The current frame without advancing. */
    fun state(): CarState

    /** Start line, full tank, running. */
    fun reset(): CarState

    /** First solid ground at or after [x] (world metres), full tank, running. */
    fun resetAt(x: Float): CarState
}
