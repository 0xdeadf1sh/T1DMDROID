package com.t1dm.feature.game

/** TRANSCRIBED not derived: defaultCarTuning is uniffi; compile-time consts, JVM tests w/o .so. */

/** `max_wheel_omega × wheel_radius` = 28 rad/s × 3.6 m. The limiter IS the top speed. */
internal const val TOP_SPEED_MS = 100.8f

/** IDLE_RPM+ω·RPM_PER_RAD_S+BUMP=800+28×(9.5493×7)+900; rpm is synth, 9000 rail is a guard. */
internal const val TOP_RPM = 3_572f

/** ~19% headroom past [TOP_SPEED_MS]/[TOP_RPM]: descent overruns; pinned needle reads broken. */
internal const val SPEED_FULL_SCALE_MS = 120f
internal const val RPM_FULL_SCALE = 4_200f
