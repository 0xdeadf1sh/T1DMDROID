package com.t1dm.core.model

/** Hill-climb minigame car physics; terrain IS the glucose trace. Cosmetic, no fail-closed path. */

/** Every non-Running value is TERMINAL: world freezes, re-returns the same CarState until reset. */
enum class RunState {
    Running,

    /** Rollover past the tilt threshold, or a fall past the kill plane under a chasm. */
    Crashed,

    /** The right-hand edge of the heightfield — the present moment. */
    Finished,
}

/** heights: ground at x=i*dx, piecewise-linear; negative/non-finite is a GAP (dropout=chasm). */
data class TerrainSpec(
    val heights: List<Float>,
    val dx: Float,
    val worldHeight: Float,
)

/** Rust `defaultCarTuning()` is the single authority for these numbers — do not transcribe them. */
data class CarTuning(
    val chassisMass: Float,
    val chassisHalfLen: Float,
    val chassisHalfHeight: Float,
    val wheelRadius: Float,
    val wheelMass: Float,
    val suspensionRest: Float,
    val suspensionTravel: Float,
    val suspensionStiffness: Float,
    val suspensionDamping: Float,
    val motorTorque: Float,
    val brakeTorque: Float,
    val maxWheelOmega: Float,
    val grip: Float,
    val tractionRelax: Float,
    val gravity: Float,
    val crashTiltRad: Float,
)

/** Angles radians, y-up CCW-positive; rearAngle/frontAngle negated on canvas. Read vx, not dx. */
data class CarState(
    val x: Float,
    val y: Float,
    val angle: Float,
    val vx: Float,
    val vy: Float,
    val angularVelocity: Float,
    val rearX: Float,
    val rearY: Float,
    val rearAngle: Float,
    val rearOmega: Float,
    /** Carrying load or within 0.2m speculative reach of ground — near-contact, not geometric. */
    val rearContact: Boolean,
    val frontX: Float,
    val frontY: Float,
    val frontAngle: Float,
    val frontOmega: Float,
    val frontContact: Boolean,
    val rpm: Float,
    val throttleApplied: Float,
    val impactImpulse: Float,
    val roughness: Float,
    /** No contact for 8 ticks (67ms); not the negation of contact; slow to arm, fast to clear. */
    val airborne: Boolean,
    /** Furthest x reached, from the start line. Monotone non-decreasing. */
    val distanceM: Float,
    val run: RunState,
    /** Simulated seconds consumed — substeps actually run, not wall clock. */
    val elapsedS: Float,
)
