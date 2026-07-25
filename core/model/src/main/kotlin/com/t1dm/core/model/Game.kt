package com.t1dm.core.model

/**
 * Types crossing the Rust `t1dm-core::game` seam — the 2D car physics behind the hill-climb
 * minigame, whose terrain IS the glucose trace (the car drives left→right, i.e. forward in
 * time toward now). These mirror the uniffi records one-to-one; `:core:native` projects the
 * generated `uniffi.t1dm_core.*` types onto them so no consumer depends on the binding.
 *
 * Cosmetic only: nothing here touches the §3.6 fail-closed path, and no reading, dose or
 * alarm depends on it. The solver lives in Rust because a frame loop is the one place the
 * FFI call cost bites — the terrain is marshalled once per track and each frame is a single
 * [com.t1dm.core.common.NativeCore]-issued `step`.
 */

/** How the run ended, or that it has not. Every non-[Running] value is TERMINAL: the world
 *  freezes and re-returns the same [CarState] until reset. */
enum class RunState {
    Running,

    /** Rollover past the tilt threshold, or a fall past the kill plane under a chasm. */
    Crashed,

    /** Reached the right-hand edge of the heightfield — the present moment. */
    Finished,
}

/**
 * The terrain. [heights] is the ground height at `x = i · dx` in world units, piecewise-linear
 * between samples; a **negative or non-finite** sample marks a GAP with no ground at all, which
 * is how a CGM dropout becomes a chasm. [worldHeight] only fixes the kill plane, one full
 * world-height below the floor.
 */
data class TerrainSpec(
    val heights: List<Float>,
    val dx: Float,
    val worldHeight: Float,
)

/** Everything about the car a caller may bend. The Rust `defaultCarTuning()` is the offroad
 *  build the game ships and the single authority for these numbers — do not transcribe them. */
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
 * One frame of everything the renderer, the haptics and the synthesised engine audio need —
 * flat scalars, so a frame costs one buffer read rather than a boxed list.
 *
 * Angles are radians in a **y-up, counter-clockwise-positive** world; [rearAngle] / [frontAngle]
 * are rolled angles in the driving sense (increasing = rolling toward +x), so a y-up canvas
 * draws them negated. [rpm] is a synthesised audio driver, not a physical crank speed.
 * [impactImpulse] is the normal impulse in excess of merely carrying the car's weight since the
 * last step — ~0 while resting or rolling, large on a landing — and is the haptics amplitude;
 * [roughness] is the terrain's local slope change, saturating at 1, and is the rumble amplitude.
 *
 * **The POSE is interpolated; nothing else is.** [x], [y], [angle] and the two wheels' positions and
 * rolled angles are blended between the last two physics ticks by however much of a tick the solver
 * is still holding, so equal-duration frames move the car equal distances even though a frame
 * consumes one tick or three. Every other field — the velocities, [impactImpulse], the contact flags,
 * [distanceM], [run] — is the current tick verbatim, because blending a velocity or a verdict with
 * its predecessor would be a lie about the state the physics is in. The consequence to know: [x] can
 * lag [vx] by up to one tick (~0.4 m at the limiter), so differentiating [x] frame to frame does not
 * reproduce [vx]; read [vx].
 *
 * [tractionRelax] on [CarTuning] is vestigial — rapier's Coulomb friction does that job from `grip`
 * alone — but is still validated to (0, 1] so the record's contract is unchanged.
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
    /** Carrying load, or within the solver's 0.2 m speculative reach of the ground — 5 % of the
     *  wheel's own radius, so this is near-contact rather than geometric contact. Instantaneous. */
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
    /** Nothing touching — neither wheel, nor bodywork — for eight physics ticks running (67 ms).
     *  Deliberately NOT the instantaneous negation of the contact flags: this one is read as an edge
     *  (`airborne` → `!airborne` is a touchdown), and a wheel clears the 0.2 m tolerance for a single
     *  tick over any sharp lip. Slow to arm, instant to clear. */
    val airborne: Boolean,
    /** Furthest x reached, from the start line. Monotone non-decreasing. */
    val distanceM: Float,
    /** Fraction of the tank left, in [0, 1]. */
    val run: RunState,
    /** Simulated seconds consumed — substeps actually run, not wall clock. */
    val elapsedS: Float,
)
