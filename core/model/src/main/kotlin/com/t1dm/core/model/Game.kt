package com.t1dm.core.model

/** Hill-climb minigame car physics; terrain IS the glucose trace. Cosmetic, no fail-closed path. */

/** Which minigame the dashboard panel is in; both read the same trace and write nothing. */
enum class GameKind {
    Drive,
    Golf,
}

/** How thickly both games dress the trace with scenery; a Settings choice, stored by name. */
enum class GamePropDensity {
    Sparse,
    Busy,
}

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

/** A fixed box over the ground at [x]: [halfW] either side, [lift] up to its base, [h] tall. */
data class Obstacle(
    val x: Float,
    val halfW: Float,
    val h: Float,
    val lift: Float = 0f,
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

/** The same trace played as a hole. [Holed] is TERMINAL: the world freezes until it is re-teed. */
enum class GolfRun {
    Playing,
    Holed,
}

/** Rust `defaultGolfTuning()` is the authority for these numbers — do not transcribe them. */
data class GolfTuning(
    val ballRadius: Float,
    val ballMass: Float,
    val restitution: Float,
    val friction: Float,
    /** Linear rolling bleed (1/s), applied ONLY in ground contact — flight is drag-free. */
    val rollingDamping: Float,
    val gravity: Float,
    /** Speed cap on [GolfWorld.shoot]; direction is kept, magnitude clipped. */
    val maxLaunchSpeed: Float,
    val restSpeed: Float,
    val restHoldS: Float,
)

/** Cut at the last solid sample, so [x1] IS the present moment; both lips sit at [rimY]. */
data class GolfCup(
    val x0: Float,
    val x1: Float,
    val rimY: Float,
    val depth: Float,
)

/** Flight is drag-free BY CONTRACT: `x+vx·t, y+vy·t−g·t²/2`, so a preview arc needs no FFI. */
data class BallState(
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    /** Rolled angle, positive rolling toward +x. Render only. */
    val angle: Float,
    /** Settled: the only state a shot is honoured in. */
    val atRest: Boolean,
    /** True after 8 ticks (67 ms) clear, not simply !contact — a lip can clear for one tick. */
    val airborne: Boolean,
    val strokes: Int,
    /** Water drops. Each is a stroke's worth of score and a re-placement, not a stroke. */
    val penalties: Int,
    /** Normal impulse over step, excess of weight (N·s); rectified. Haptics amplitude. */
    val impact: Float,
    val run: GolfRun,
)
