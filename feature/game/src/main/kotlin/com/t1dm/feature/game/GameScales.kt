package com.t1dm.feature.game

/** TRANSCRIBED, NOT DERIVED: compile-time consts for JVM tests; t1dm-core::game is authority. */

/** `max_wheel_omega × wheel_radius` = 28 rad/s × 3.6 m. The limiter IS the top speed. */
internal const val TOP_SPEED_MS = 100.8f

/** IDLE_RPM + max_wheel_omega×RPM_PER_RAD_S + THROTTLE_RPM_BUMP; rpm is a synthesised proxy. */
internal const val TOP_RPM = 3_572f

/** ~19% headroom past TOP_SPEED_MS/TOP_RPM — needle pinned at full scale reads as broken. */
internal const val SPEED_FULL_SCALE_MS = 120f
internal const val RPM_FULL_SCALE = 4_200f
