package com.t1dm.core.common

import com.t1dm.core.model.CarState

/** Refcounted, freed by a JVM Cleaner; leaks until GC unless held in remember{} and closed. */
interface GameWorld : AutoCloseable {
    /** x of the finish line; fixed at construction. */
    val trackLength: Float

    /** [dtMs] wall-clock, fixed 1/120s ticks, surplus dropped. [throttle]/[brake] clamp [0,1]. */
    fun step(dtMs: Float, throttle: Float, brake: Float): CarState

    /** The current frame without advancing. */
    fun state(): CarState

    /** Start line, running. */
    fun reset(): CarState

    /** First solid ground at or after [x] (world metres), running. */
    fun resetAt(x: Float): CarState
}
