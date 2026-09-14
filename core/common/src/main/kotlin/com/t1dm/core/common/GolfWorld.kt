package com.t1dm.core.common

import com.t1dm.core.model.BallState
import com.t1dm.core.model.GolfCup

/** Refcounted, freed by a JVM Cleaner; leaks until GC unless held in remember{} and closed. */
interface GolfWorld : AutoCloseable {
    /** x of the present moment — the cup's right rim. Fixed at construction. */
    val trackLength: Float

    /** Geometry the hole is drawn from; fixed at construction. */
    val cup: GolfCup

    /** [dtMs] wall-clock, fixed 1/120s ticks, surplus dropped. */
    fun step(dtMs: Float): BallState

    /** The current frame without advancing. */
    fun state(): BallState

    /** Ignored unless the ball is at rest and the round is still playing; speed is capped. */
    fun shoot(vx: Float, vy: Float): BallState

    /** First solid ground at or after [x] (world metres), scorecard back to zero. */
    fun teeAt(x: Float): BallState

    /** Replays the same hole from the tee this round started at. */
    fun reset(): BallState
}
