package com.t1dm.core.common

import com.t1dm.core.model.CarState

/** ONE [step] per frame: the terrain crosses the FFI once, at construction. Refcounted in Rust and
 *  freed by a JVM `Cleaner`, so dropping the reference leaks the world until a GC — hold it in a
 *  `remember { }` and [close] it in a `DisposableEffect`. */
interface GameWorld : AutoCloseable {
    /** x of the finish line; fixed at construction. */
    val trackLength: Float

    /** [dtMs] is wall-clock, consumed in fixed 1/120 s ticks with the surplus beyond a cap dropped.
     *  [throttle] and [brake] saturate to `[0, 1]`; non-finite reads as released. Total. */
    fun step(dtMs: Float, throttle: Float, brake: Float): CarState

    /** The current frame without advancing. */
    fun state(): CarState

    /** Start line, full tank, running. */
    fun reset(): CarState

    /** First solid ground at or after [x] (world metres), full tank, running. */
    fun resetAt(x: Float): CarState
}
