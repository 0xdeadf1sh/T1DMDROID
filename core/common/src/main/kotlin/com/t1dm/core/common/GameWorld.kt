package com.t1dm.core.common

import com.t1dm.core.model.CarState

/**
 * A live hill-climb world: terrain + car + run state, held across frames. Issued by
 * [NativeCore.createGameWorld]; this pure-JVM handle exists so `:core:common` never has to
 * name the uniffi object type (it has no JNA and no visibility of `uniffi.t1dm_core`).
 *
 * ONE call per frame. The whole point of the Rust solver is that the terrain crosses the FFI
 * once, at construction, and everything a frame needs comes back in a single [step]; calling
 * several methods per frame gives that back.
 *
 * Ownership is EXPLICIT. The backing object is refcounted in Rust and freed via a JVM
 * `Cleaner`, so merely dropping the reference leaks the world until a GC gets round to it.
 * Hold it in a `remember { }` and [close] it in a `DisposableEffect`.
 */
interface GameWorld : AutoCloseable {
    /** x of the finish line: the right-hand edge of the heightfield. Fixed at construction. */
    val trackLength: Float

    /**
     * Advance by one rendered frame. [dtMs] is the wall-clock delta, accumulated and consumed
     * in fixed 1/120 s ticks (two per 60 fps frame) with the surplus beyond an internal cap
     * dropped, so a stalled frame cannot cascade. [throttle] and [brake] saturate to `[0, 1]`;
     * a non-finite control reads as released. Total on any input, and a no-op re-read of the
     * frozen state once the run is terminal.
     */
    fun step(dtMs: Float, throttle: Float, brake: Float): CarState

    /** The current frame without advancing — a paused screen, or a fresh recomposition. */
    fun state(): CarState

    /** Back to the start line with a full tank and a `Running` run. */
    fun reset(): CarState

    /** Re-place the car at the first solid ground at or after [x] (world metres), full tank, running.
     *  The track spans the whole visible window so the curve is not cut off behind the car, so the
     *  start is NOT the track's origin — it is wherever the user tapped. */
    fun resetAt(x: Float): CarState
}
