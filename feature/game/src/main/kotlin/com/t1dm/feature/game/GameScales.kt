package com.t1dm.feature.game

/**
 * TRANSCRIBED, NOT DERIVED: `defaultCarTuning()` is a uniffi call, and these have to be compile-time
 * constants the JVM tests can reach without a `.so`. The derivation is written out beside each so a
 * limiter change has somewhere to land. `t1dm-core::game` remains the authority on every input.
 */

/** `max_wheel_omega × wheel_radius` = 28 rad/s × 3.6 m. The limiter IS the top speed. */
internal const val TOP_SPEED_MS = 100.8f

/** `IDLE_RPM + max_wheel_omega × RPM_PER_RAD_S + THROTTLE_RPM_BUMP` = 800 + 28 × (9.5493 × 7) + 900.
 *  `CarState.rpm` is a synthesised proxy, not a crank speed; its 9 000 rail is a guard, not a range. */
internal const val TOP_RPM = 3_572f

/** ~19 % headroom past [TOP_SPEED_MS] / [TOP_RPM]: a steep descent overruns the limiter, and a
 *  needle pinned at full scale reads as a broken gauge. */
internal const val SPEED_FULL_SCALE_MS = 120f
internal const val RPM_FULL_SCALE = 4_200f
