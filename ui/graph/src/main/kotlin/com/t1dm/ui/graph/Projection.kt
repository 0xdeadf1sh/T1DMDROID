package com.t1dm.ui.graph

/**
 * The two coordinate projections the overlays call, as `fun interface`s rather than function types.
 *
 * **Why this is not a style preference.** A Kotlin function type is `Function1<P, R>`, whose single
 * method is erased to `invoke(Object): Object`. Passing `(Double) -> Float` therefore compiles every
 * call into `Double.valueOf(x)` → `invoke` → `Number.floatValue()`: two heap allocations per projected
 * coordinate, and neither `java.lang.Double` nor `java.lang.Float` has a small-value cache to absorb
 * them. The overlays project on the order of 350 coordinates for one forecast fan and ~900 more with
 * the curve overlay on, so at a 120 Hz refresh that was six figures of garbage per second for
 * arithmetic that allocates nothing.
 *
 * A `fun interface` declared over primitives compiles its method as `(D)F` and `(F)F`, so the same
 * call sites box nothing. The lambda syntax at the call site is unchanged — SAM conversion applies —
 * which is why this costs one import and no readability.
 *
 * These are also monomorphic per draw: one implementation of each reaches the overlays, so the JIT
 * can inline through them, which it could not reliably do through the three distinct `Function1`
 * instances that shared these parameters before.
 */

/** Absolute epoch-ms → x pixel. */
internal fun interface AbsToPx {
    fun of(tsMs: Double): Float
}

/** A value in the plot's y unit → y pixel. */
internal fun interface ValToPx {
    fun of(v: Float): Float
}

