package com.t1dm.core.model

/**
 * The hill-climb minigame's 2D car physics. The terrain IS the glucose trace, driven left→right,
 * forward in time toward now. Cosmetic only: nothing here touches the fail-closed path.
 */

/** Every non-[Running] value is TERMINAL: the world freezes and re-returns the same [CarState]
 *  until reset. */
enum class RunState {
    Running,

    /** Rollover past the tilt threshold, or a fall past the kill plane under a chasm. */
    Crashed,

    /** The right-hand edge of the heightfield — the present moment. */
    Finished,
}

/**
 * [heights] is ground height at `x = i · dx` in world units, piecewise-linear between samples; a
 * negative or non-finite sample is a GAP with no ground, which is how a CGM dropout becomes a
 * chasm. [worldHeight] only fixes the kill plane, one world-height below the floor.
 */
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

/**
 * Angles are radians, y-up and counter-clockwise-positive; [rearAngle]/[frontAngle] roll in the
 * driving sense, so a y-up canvas draws them negated. [rpm] drives synthesised audio, not a crank
 * speed. [impactImpulse] is normal impulse above the car's own weight (haptics amplitude);
 * [roughness] is local slope change saturating at 1 (rumble). Only the pose is interpolated between
 * ticks, so [x] can lag [vx] by one tick — read [vx], do not differentiate [x].
 */
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
    /** Carrying load, or within the solver's 0.2 m speculative reach of the ground — near-contact,
     *  not geometric contact. Instantaneous. */
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
    /** Nothing touching for eight physics ticks running (67 ms). Deliberately NOT the negation of
     *  the contact flags: read as an edge, and a wheel clears the 0.2 m tolerance for a single tick
     *  over any sharp lip. Slow to arm, instant to clear. */
    val airborne: Boolean,
    /** Furthest x reached, from the start line. Monotone non-decreasing. */
    val distanceM: Float,
    val run: RunState,
    /** Simulated seconds consumed — substeps actually run, not wall clock. */
    val elapsedS: Float,
)
