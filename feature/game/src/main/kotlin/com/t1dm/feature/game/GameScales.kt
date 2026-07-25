package com.t1dm.feature.game

/**
 * What the shipped car can actually reach. Every surface that normalises against a ceiling — the two
 * dials, the engine synth's harmonic content, the haptic engine bed — reads these rather than a
 * number of its own, because all three were wrong in the same way at once when the rev limiter last
 * moved: each had a ceiling transcribed from a tune two revisions old, so the needle sat in the first
 * half of its sweep, the drawn redline was on an arc nothing could reach, and the note never opened up.
 *
 * TRANSCRIBED, NOT DERIVED. `defaultCarTuning()` is exported precisely so Kotlin need not copy the
 * tune, but it is a uniffi call into the native library, and `GameFeel`/`GameSynth` want compile-time
 * constants that the JVM unit tests can reference without a `.so`. So these are copies, and the
 * derivation is written out beside each so a future limiter change has an obvious place to land.
 * `t1dm-core::game` remains the authority on every input.
 */

/** `max_wheel_omega × wheel_radius` = 28 rad/s × 3.6 m. The limiter IS the top speed — the drive is a
 *  velocity motor, so the car sits on it rather than creeping toward it. */
internal const val TOP_SPEED_MS = 100.8f

/** `IDLE_RPM + max_wheel_omega × RPM_PER_RAD_S + THROTTLE_RPM_BUMP`
 *  = 800 + 28 × (9.5493 × 7) + 900. `CarState.rpm` is a synthesised proxy, not a crank speed, and its
 *  own `MAX_RPM` rail of 9 000 is a guard against an absurd tuning rather than a range anything
 *  reaches. */
internal const val TOP_RPM = 3_572f

/** Both dials carry ~19 % of headroom past [TOP_SPEED_MS] / [TOP_RPM], because the terrain can push
 *  past the limiter — a steep descent overruns it — and a needle pinned at full scale reads as a
 *  broken gauge rather than as overspeed. The limiter then lands at 0.84 / 0.85 of the sweep, which
 *  is where an instrument's redline belongs. */
internal const val SPEED_FULL_SCALE_MS = 120f
internal const val RPM_FULL_SCALE = 4_200f
