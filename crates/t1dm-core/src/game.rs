//! 2D arcade car physics for the in-app hill-climb minigame — the terrain is a *glucose
//! trace*: the car drives LEFT→RIGHT, i.e. forward in time toward now, and a jagged
//! excursion is a jagged hill. Purely cosmetic: nothing here touches the §3.6 fail-closed
//! path, no reading, dose or alarm depends on it, and the module is deliberately isolated
//! from every safety seam (SPEC.private.md §3.6 is untouched by this file).
//!
//! WHY IT LIVES IN RUST. A frame loop is the one place the JNA/uniffi call cost actually
//! bites, so the whole world is a **uniffi Object**: the heightfield is marshalled ONCE per
//! track and each frame is a single [`GameWorld::step`] returning one flat scalar
//! [`CarState`] record. The step path does not allocate in steady state — rapier reuses its
//! island, contact and constraint buffers across steps — but that is a property of the
//! engine's reuse, not a guarantee this module can make. Construction and [`GameWorld::reset`]
//! do allocate; both are once-per-track events, not per-frame ones.
//!
//! THE MODEL. rapier2d, in its ordinary vehicle configuration: a dynamic chassis cuboid, two
//! dynamic ball wheels, and one **pin-slot joint** per wheel — Box2D's wheel joint by another
//! name. Locking only the joint's `LIN_Y` leaves the wheel free to slide along the chassis-down
//! axis (that slide IS the suspension stroke, a position motor at `suspension_rest` with the
//! tuning's stiffness and damping, hard-stopped by a `[0, suspension_travel]` limit) and free
//! to spin about it (a velocity motor on `ANG_X`, whose target is the rev limiter and whose
//! force ceiling is the motor or brake torque). The chassis takes the exact equal-and-opposite
//! reaction of whatever the drive motor delivers, so a throttle stab lifts the nose because
//! the solver says so rather than because a fudge factor was tuned to make it.
//!
//! WHAT THIS REPLACED, AND WHY. The previous solver was hand-derived: raycast wheels sampling
//! three points across a footprint, a bespoke tyre impulse, four penalty-spring chassis
//! corners. It chattered, structurally. Which of the three footprint samples was highest
//! switched abruptly as the car moved, so the solved suspension extension jumped
//! discontinuously; that jump was divided by `dt` and fed to a 1 500 N·s/m damper, spiking the
//! suspension force every time the maximum changed hands. Three point samples cannot represent
//! a circle rolling over a kink. Ball-versus-polyline contact can, exactly and continuously,
//! and the wheels no longer fight over the chassis because contacts and joints are solved
//! together with warm starting instead of one wheel at a time against a locally-derived
//! effective mass.
//!
//! TERRAIN. `heights[i]` is the ground height at `x = i·dx`, piecewise-linear between samples,
//! collided as an **oriented polyline** — see [`build_ground`] for why a polyline and not the
//! obvious `HeightField`. A **negative or non-finite sample is a GAP** — no ground at all —
//! which is the natural encoding for a CGM dropout: a stretch the sensor never reported becomes
//! a chasm you fall into, expressed by simply omitting that cell's segment. Falling past
//! `kill_y` (one world-height below the floor) ends the run.
//!
//! The module keeps the raw heights alongside the collider, because the collider's copy has been
//! sanitised and the gap markers are gone from it. `sample`, `roughness` and the seating search
//! all read the raw Vec.
//!
//! HONESTY CAVEAT on the golden. Unlike `curve`/`preproc`/`stats`, this module has **no
//! external numeric authority** — there is no `simulator.py` for a made-up arcade car, so
//! `game_golden.json` is a *regression pin generated from this code*, not a correctness
//! oracle. It proves only that a refactor cannot silently change the tuned feel. The
//! correctness weight is carried by the behavioural tests below (rests on flat ground,
//! rolls downhill, climbs under throttle, crashes inverted, a
//! chasm ends the run) and by the determinism + fuzz tests.
//!
//! Everything is total on hostile input. Release builds are `panic = "abort"`, so a panic
//! inside a 60 Hz frame loop would take the whole app down: `step` clamps a non-finite or
//! absurd `dt`, saturates throttle/brake, caps the substep count, rails every velocity, and
//! freezes the run as `Crashed` rather than letting a non-finite pose reach the frame. Nothing
//! in rapier panics on non-finite numeric input — it silently poisons the state instead — so
//! the guard is a finiteness firewall on the way in (`dx` is rejected unless its reciprocal is
//! finite too, and heights are sanitised before the collider is built), a finiteness check on
//! the way out of every substep, and a finiteness fold on each signal `snapshot` derives.

use std::sync::{Arc, Mutex};

use rapier2d::parry::shape::{Polyline, PolylineFlags};
use rapier2d::prelude::*;

use crate::CoreError;

// ── Fixed-step integrator (SPEC-free; a rendering concern) ──────────────────────────────
/// Physics tick. 1/120 s ⇒ exactly two substeps per 60 fps frame, four per 30 fps.
const FIXED_DT: f32 = 1.0 / 120.0;
/// Hard cap on substeps consumed by one [`GameWorld::step`]. Beyond this the surplus
/// accumulator is DROPPED rather than chased — a spiral of death in a frame loop would
/// wedge the UI thread, and a dropped tick is merely a hitch.
const MAX_SUBSTEPS: u32 = 8;
/// Longest frame delta honoured (250 ms). A longer gap (app backgrounded, GC pause) is
/// truncated so the car does not teleport through the terrain.
const MAX_FRAME_DT_S: f32 = 0.25;

// ── Allocation / index guards (the `curve::MAX_GRID_STEPS` precedent) ───────────────────
/// Upper bound on heightfield samples (~200 k ⇒ 200 km at the default 1 m spacing). A
/// larger field is a caller bug; refusing it keeps the one `Vec` bounded so a hostile size
/// is the documented `Err` rather than an abort on a failed allocation.
const MAX_TERRAIN_SAMPLES: usize = 200_000;
/// Two samples is the minimum that defines a segment.
const MIN_TERRAIN_SAMPLES: usize = 2;
/// Longest track accepted, in world metres. Vertices are absolute, so past ~1e6 an f32 loses
/// metres between neighbouring samples; and an infinite span (a huge `dx` times a large sample
/// count) makes the collider's AABB infinite, at which point the broad phase stops producing
/// ground pairs at all and the car falls through the world.
const MAX_TRACK_LENGTH: f32 = 1.0e6;
/// How far the flat run-off left of the start line extends (m). Sized so it cannot be crossed
/// inside a session rather than merely to look generous: the only thing slowing a car coasting
/// backwards on the flat is [`LINEAR_DAMPING`], which decays a velocity with a 1/0.02 = 50 s time
/// constant, so the furthest a car railed at [`MAX_SPEED`] can ever travel is 200 × 50 = 10 km.
/// At `length` — what this used to be — a 60 m track gave 60 m of apron, crossed in about three
/// seconds, after which the car fell to `kill_y` and the run read `Crashed` with no visible cause.
const RUN_OFF_LENGTH: f32 = 10_000.0;

// ── Numeric rails: every one of these exists to keep the integrator finite ──────────────
/// Linear speed cap (m/s). Far above any reachable speed; catches a divergence early.
const MAX_SPEED: f32 = 200.0;
/// Angular speed cap (rad/s) — ~5 flips per second.
const MAX_ANGULAR: f32 = 32.0;
/// Ceiling on any motor gain handed to rapier. `validate_tuning` bounds the tuning by sign,
/// not by magnitude, so a legal-but-absurd torque could otherwise overflow the derived
/// damping coefficient to +inf and poison the solve.
const MAX_MOTOR_GAIN: f32 = 1.0e12;

// ── Solver configuration ────────────────────────────────────────────────────────────────
/// rapier expresses its tolerances in metres and scales them by this. The car is ~28 m long
/// on wheels of 3.6 m radius — geometrically a 10× human-scale vehicle — so the tolerances
/// have to be scaled with it or the allowed penetration and the prediction distance are a
/// tenth of a percent of the wheel.
const LENGTH_UNIT: f32 = 10.0;
/// TGS-Soft substeps per `step` (rapier divides `dt` by this, so it is an integration rate,
/// not a relaxation count): 8 ⇒ an effective 960 Hz inner rate.
const SOLVER_SUBSTEPS: usize = 8;
/// Speculative-contact distance, before the `LENGTH_UNIT` scale ⇒ 0.20 m. At rapier's default
/// (0.02 m here) a wheel landing near the rev limiter travels further in one substep than the
/// solver will look ahead, buries itself, and is thrown back out by depenetration alone.
const PREDICTION_DISTANCE: f32 = 0.02;
/// Penetration the solver does not chase, before the `LENGTH_UNIT` scale ⇒ 0.01 m. This is
/// what a resting wheel actually sinks — 0.3 % of its radius.
const ALLOWED_LINEAR_ERROR: f32 = 0.001;
/// Contact impulses are written back as `impulse_accumulator + impulse`, and the accumulator
/// folds in the previous step's residual before it is warmstart-scaled — so a steady-state
/// reading is inflated by exactly `(N+1)/N` over `N` substeps. This undoes it.
const IMPULSE_WARMSTART_BIAS: f32 = 8.0 / 9.0; // SOLVER_SUBSTEPS / (SOLVER_SUBSTEPS + 1)
/// Proportional gain of the drive motor, as a multiple of the gain that would exactly saturate
/// the torque ceiling from a standstill. Above 1 the motor holds full torque until the wheel is
/// within `1/GAIN` of the rev limiter and then fades — which is the old model's explicit
/// `fwd_fade`/`rev_fade` ramp, obtained here for free from the constraint.
const DRIVE_MOTOR_GAIN: f32 = 4.0;

// ── Chassis contact ─────────────────────────────────────────────────────────────────────
/// Coulomb friction of bodywork scraping along the ground. Combined with the terrain's by
/// `Min` so the bodywork keeps its own low value against a deliberately grippy surface.
const CHASSIS_FRICTION: f32 = 0.35;

// ── Aerodynamic / rolling losses ────────────────────────────────────────────────────────
/// Linear velocity bleed per second (air drag stand-in).
const LINEAR_DAMPING: f32 = 0.02;
/// Angular velocity bleed per second. Low enough that a launch still flips.
const ANGULAR_DAMPING: f32 = 0.35;
/// Free-spinning wheel bleed per second (grounded wheels wind down).
const WHEEL_SPIN_DAMPING: f32 = 0.6;
/// Spin damping while AIRBORNE (1/s). A wheel off the ground has only its bearing to slow it, so it
/// should keep turning through a jump; damping it at the contact-patch rate visibly halts it in
/// mid-air, which reads as the tyre seizing.
const WHEEL_SPIN_DAMPING_AIR: f32 = 0.06;
/// Consecutive contact-free ticks before [`CarState::airborne`] is reported — 16 ⇒ 133 ms.
///
/// The per-wheel flags are instantaneous by design; `airborne` is not, because it is the one the
/// sensory layer reads as an EDGE. `!airborne` after `airborne` is a touchdown: it fires the landing
/// one-shot and un-hushes the haptic bed. A wheel genuinely clears the solver's 0.2 m tolerance for
/// a single tick as it crests a sharp lip, and calling that a flight makes every lip a landing —
/// measured at 3.3 airborne episodes a second on the deliberately sawtoothed `glucose_terrain`
/// fixture, 48 of 49 of them two ticks or shorter. That is the failure commit 3252335 closed on the
/// impulse channel arriving through the contact one.
///
/// The threshold is a function of the LIMITER, and it moved when the limiter did. It has to sit above
/// the longest lip clearance and below the shortest genuine flight, and both distributions shift up
/// with speed: the launch velocity a fixed kink imparts scales with how fast the kink is crossed, so
/// the same lip that lofted the car for 6 ticks at 50 m/s lofts it for around 10 at 101. At 8 the two
/// 1-tick blips visible in `airborne_does_not_strobe_on_a_jagged_trace` were lip clearances promoted
/// to flights — each one a false touchdown, i.e. a click in the hand and a hole in the bed.
///
/// 16 clears them and still reports every genuine flight that fixture produces (the shortest survives
/// as 2 frames of a 5-frame episode; the rest run 10–24 frames). The tie stays broken toward GROUNDED
/// deliberately: a suppressed 130 ms hop costs a landing cue nobody would have noticed, whereas a
/// false one is felt. A seat placed clear of the ground arms the counter outright — a car dropped into
/// mid-air is not a transient.
const AIRBORNE_ARM_TICKS: u32 = 16;
/// Angular acceleration (rad/s²) the pedals apply to the chassis while airborne. A game mechanic,
/// not a physical claim — hence an acceleration applied straight to the chassis' angular velocity
/// rather than a torque, so it is independent of the tune's inertia.
///
/// It has to be sized against what a launch already put into the car, not against a car at rest.
/// Driving off a lip at 49 m/s leaves the chassis rotating nose-DOWN at 0.8 rad/s, because the
/// front wheel drops into the void while the rear still drives; at 3.2 rad/s² a third of a second
/// of the half-second flight went on merely cancelling that, and the pedal read as inert. 6.0 nulls
/// it in 0.14 s and still leaves authority to trim the landing — and a full turn in a one-second
/// jump, which is the outer limit of what this world's airtime allows.
const AIR_PITCH_ACCEL: f32 = 6.0;

// ── Geometry derived from the tuning rather than exposed as more knobs ──────────────────
/// Wheel attach points sit at ±this fraction of the chassis half-length.
const WHEELBASE_FRAC: f32 = 0.90;
/// The driver's head, in chassis half-lengths behind centre and metres above the roof.
const HEAD_BACK_FRAC: f32 = 0.22;
const HEAD_ABOVE_ROOF: f32 = 0.42;
/// `wheels[REAR]` is the driven one; the FFI record's `rear_*` fields read from it.
const REAR: usize = 0;
const FRONT: usize = 1;

// ── Presentation-only derived signals ───────────────────────────────────────────────────
/// Idle engine speed of the RPM proxy.
const IDLE_RPM: f32 = 800.0;
/// Wheel rad/s → RPM through a single notional reduction (60/2π × gear).
const RPM_PER_RAD_S: f32 = 9.5493 * 7.0;
/// Revving against a stalled wheel still makes noise: throttle adds this much RPM.
const THROTTLE_RPM_BUMP: f32 = 900.0;
const MAX_RPM: f32 = 9_000.0;
/// Half-width, in samples, of the roughness window read under the car.
const ROUGH_HALF_WINDOW: usize = 6;
/// Mean slope-change per sample that reads as "maximally rough" (roughness saturates at 1).
const ROUGH_REF: f32 = 0.5;

// ── Offroad default tune (see `default_car_tuning`) ─────────────────────────────────────
/// The tune is sized to the WORLD, not to a real vehicle. The terrain is the glucose trace at
/// `METRES_PER_MINUTE` (3 m per minute), so one 5-minute reading interval is 15 world metres: a
/// 2.8 m car met every reading step as a cliff and tripped on ordinary data. This car is ~28 m long,
/// spanning about two reading intervals, so a single step is a bump it bridges and a genuine
/// excursion is a hill it has to climb.
///
/// The whole tune hangs off ONE number: the anti-wheelie ratio `L/h`, half the wheelbase over the
/// centre of mass' height above the contact patch. It is 12.6 / 5.6 = 2.25 here. Everything the car
/// is allowed to do — how much torque, how much gravity, how steep a face it takes at pinned
/// throttle — is bounded by it, because the front wheel lifts once thrust-to-weight reaches
/// `cos θ · L/h` on a face of angle θ. Nothing else in the tune can buy past that.
///
/// Chassis mass (kg). HEAVY on purpose: at 320 kg the car read as a toy that skittered off every
/// bump. It buys heft — momentum through a dip, a suspension that settles rather than pings — and
/// costs nothing in acceleration, since the mass cancels out of both the thrust-to-weight the
/// torque is derived from and the wheelie condition that bounds it.
const D_MASS: f32 = 450.0;
/// Half-length / half-height of the chassis box (m). LONG: the wheelbase is the entire
/// anti-wheelie budget, and a stubby car loops on the first steep face.
const D_HALF_LEN: f32 = 14.0;
/// Half-height of the chassis box (m). It sets the centre of mass height `h` = `wheel_radius + (rest −
/// sag) + half_height` ≈ 5.84, which is the only term either ceiling in [`D_MOTOR_TORQUE`] responds to.
///
/// RAISING IT WAS TRIED AND IS WRONG, and the static algebra that recommends it is a trap worth
/// recording. On paper the two ceilings move oppositely in `h` — the wheelie ceiling `L/h` falls as the
/// tub rises, the rear-drive traction ceiling `(μ/2)/(1 − μ·h/2L)` climbs, because more weight
/// transfers onto the driven wheel — and they cross at `h = L/μ` = 6.3 where both equal μ. That
/// argument says 1.6 is below the optimum and 2.05 sits on it, worth 11 % of thrust.
///
/// Measured on the 42° ramp, 2.05 was WORSE at every torque: the fraction of the climb spent on both
/// wheels fell from 89.5 % to 75.7 % at unchanged torque, and the collapse moved DOWN, to 3.5 % planted
/// at a thrust 1.6 sustains at 82 %. The static crossing ignores what the derating clause below already
/// warns of — a wheelie is self-feeding, so what binds is not the ceiling but the pitch angle at which
/// the ceiling starts receding faster than the car can recover, and a taller `h` reaches that angle
/// sooner. `L/h` is the number to keep large. See [`steep_traction_probe`].
const D_HALF_HEIGHT: f32 = 1.6;
/// BIG wheels: the contact is exact now, but a large wheel still bridges a jagged trace where a
/// small one drops into every notch narrower than its own diameter — and a notch is where a ball
/// gets pinched between opposing facets and welded in place (see [`D_GRIP`]).
const D_WHEEL_RADIUS: f32 = 3.6;
const D_WHEEL_MASS: f32 = 26.0;
/// LONG travel over a short free length. Kept as short as the ride allows, because it sits
/// directly under the centre of mass and every centimetre of it is wheelie.
const D_SUSP_REST: f32 = 1.6;
const D_SUSP_TRAVEL: f32 = 4.0;
/// Stiffness (N/m) and damping (N·s/m). The spring is a two-sided position motor at
/// `suspension_rest`, so it PULLS the wheel back up once the stroke passes the free length — which
/// means the static sag `m·g/2k` is not just ride height, it is the entire DROOP budget: the wheel
/// leaves the ground the moment the chassis rises that far above it. 8 kN/m puts the sag at 0.96 m,
/// a quarter of the travel (motocross's own number) and 0.076 rad of pitch the front wheel can
/// absorb before it unloads — four times what 15.3 kN/m allowed, and the difference between a front
/// wheel that follows a jagged trace and one that skips over it.
///
/// Damping is half-critical for the 225 kg each spring carries: `c_crit = 2√(k·m)` = 2.68 kN·s/m,
/// so 1.34 settles a landing in about one overshoot rather than pinging. The joint motor runs
/// `MotorModel::ForceBased`, under which both keep exactly these units regardless of the masses
/// either side of the joint.
const D_SUSP_STIFFNESS: f32 = 8_000.0;
const D_SUSP_DAMPING: f32 = 1_340.0;
/// Rear-wheel torque (N·m). `τ/r` = 20.0 kN of thrust against 17.1 kN of weight (chassis and both
/// wheels, at `D_GRAVITY`) — a thrust-to-weight of 1.17, which is 40 m/s², four earth-g, so it
/// leaves the line. Braking is matched to it: a brake that cannot out-decelerate the motor's
/// acceleration feels broken. (Brake is also reverse: hold it on the flat and the car backs up.)
///
/// Why not more. Two ceilings sit above this number and the LOWER one binds. The wheelie ceiling is
/// `L/h` = 2.16, derated by the pitch it produces — at a nose-up δ the arms become
/// `L cos δ − h sin δ` over `h cos δ + L sin δ`, already 1.65 at δ = 0.1 rad — so a wheelie is
/// self-feeding once started. On paper the tyre saturates first: with load transfer onto the driven
/// rear, the traction ceiling on a grade θ is `(μ/2)/(1 − μ·h/2L) · cos θ`, which at μ = 2.0 is 1.80 g
/// on the flat and only 1.34 g on the 42° face `climbs_a_steep_slope_under_throttle` demands.
///
/// That reading is WRONG about which ceiling binds, and the sweep below is why: the car survives 1.22
/// on that face — comfortably past the 1.34 the traction bound would put it under, but nowhere near
/// it — and then does not degrade at 1.24, it BACKFLIPS. What binds is the self-feeding wheelie, and it
/// binds discontinuously. Treat the algebra as an upper bound and the sweep as the tune.
///
/// The 1.5 an arcade climber usually wants is off the table, and doubly so. The friction available on a
/// face of angle θ cannot exceed `μ · cos θ` however the weight is distributed — at μ = 2.0 on 42° that
/// is 1.49, attained only with the front wheel exactly weightless — and raising μ is the only lever,
/// which [`D_GRIP`] has 11 % of before the car welds itself to the terrain. But the wheelie gives out
/// at 1.24 regardless, well under even that, and geometry does not buy past it either: raising `h`
/// toward the algebraic optimum makes the flip come SOONER, measured (see [`D_HALF_HEIGHT`]).
///
/// WHERE THE CLIFF ACTUALLY IS, measured rather than derived, because the derivation above locates a
/// soft ceiling and the real one is a cliff. Swept on the 42° ramp by the fraction of the climb spent
/// on BOTH wheels — the only robust statistic, since `climbs_a_steep_slope_under_throttle` samples
/// contact at one frame and near the limit that is a coin toss on a chattering wheel:
///
/// ```text
///   τ=70 000  T/W 1.14   89.5 % planted   430 m climbed
///   τ=72 000  T/W 1.17   86.8 % planted   444 m
///   τ=74 000  T/W 1.20   81.7 % planted   458 m
///   τ=75 000  T/W 1.22   75.2 % planted   465 m
///   τ=76 000  T/W 1.24    6.5 % planted   170 m — CRASHED
/// ```
///
/// One thousand N·m — 1.3 % — separates the best climb this car has ever managed from a backflip. That
/// is the self-feeding wheelie: there is no gentle degradation to tune against, so the number is set by
/// how much margin the cliff deserves rather than by how much thrust the tyre will pass. 72 000 sits
/// 5.3 % below it and still climbs 3 % further than 70 000 did.
///
/// The return on more would be small regardless, since the rev limiter and not the torque sets the top
/// speed. The launch is not what suffers: 1.17 is 40 m/s², four earth-g, and the car passes 16 m/s
/// inside a second from rest. Reproduce with [`steep_traction_probe`].
const D_MOTOR_TORQUE: f32 = 72_000.0;
const D_BRAKE_TORQUE: f32 = 72_000.0;
/// Rev limiter (rad/s) ⇒ ~101 m/s at `D_WHEEL_RADIUS`, crossing the ~45-minute viewport (135 world
/// m) in about 1.4 s. Not a soft asymptote: the drive is a velocity motor with `DRIVE_MOTOR_GAIN`
/// behind it, so the car sits ON the limiter within a few seconds of level ground rather than
/// creeping toward it, and the limiter IS the top speed.
///
/// It is the one number in the tune that buys speed for nothing. Top speed is set here and not by
/// [`D_MOTOR_TORQUE`], which only sets how fast the limiter is reached, so raising it costs neither
/// of the two ceilings that bound acceleration (see [`D_MOTOR_TORQUE`]). What it does cost is
/// AIRTIME: hang time is `2v/g` and the launch speed off a kink scales with v, so doubling this
/// doubles how far a ripple throws the car. The hand-rolled solver could not hold 34 for that
/// reason — at 110 m/s it spent the trace airborne — and the margin here is why 28 is the ceiling
/// rather than a waypoint.
const D_MAX_WHEEL_OMEGA: f32 = 28.0;
/// Grip is fenced on BOTH sides, and 2.0 sits nearly centred in the only window that works.
///
/// The floor is a derivation. The traction ceiling on a grade θ is `(μ/2)/(1 − μ·h/2L) · cos θ`,
/// which falls to the 1.14 the motor asks for at μ = 1.82 on the 42° face. Below that the steep
/// climb stops being torque-limited and turns traction-limited — it degrades softly rather than
/// failing (μ = 1.6 still covers 229 m of the 42° face against 265 m here), but the tune's central
/// claim, that torque and not the tyre is what bounds this car, does not survive it.
///
/// The ceiling is a cliff. A notch narrower than the wheel pinches the ball between two facets whose
/// normals oppose horizontally; the pair squeezes with large, near-cancelling normal forces, each
/// licensing `μ ×` itself in friction, so the pinch is a WELD that grows with μ and with nothing the
/// car can push back against. The oriented polyline's pseudo-normals do not reach this case — they
/// clamp a contact normal into the cone the two incident segments span, which collapses the
/// *collinear* artefact but leaves a genuine V exactly as sharp as it is. Swept over
/// `glucose_terrain` at pinned throttle: μ = 2.35 runs the whole 600 m and finishes; μ = 2.38 never
/// leaves the spawn, driven wheel on the limiter throughout.
///
/// The window is therefore [1.82, 2.35] and 2.0 lies +18 % / −9 % inside it. Grip has little room to
/// move in EITHER direction, which is the point of writing both walls down. What the weld needs is a
/// near-VERTICAL facet, not merely sampling finer than the wheel: swept over triangular waves of
/// fixed 0.4 and 0.8 slope with the wavelength taken from 30 m down to 2 m — a fifth of the wheel's
/// diameter — nothing welds even at μ = 2.4, and the shortest wavelengths are the FASTEST terrain of
/// the lot, because the wheel bridges them into a smooth ramp. `glucose_terrain`'s 5-sample sawtooth
/// supplies what a triangle cannot: a 1.4 m rise thrown away in a single 1 m step. This is also why
/// the hand-rolled solver could afford 3.2 and this one cannot — one raycast per wheel has no notch
/// to be pinched in.
const D_GRIP: f32 = 2.0;
/// VESTIGIAL. The hand-rolled solver cancelled this fraction of the tyre's slip velocity itself,
/// each substep; rapier's Coulomb friction does the same job from `grip` alone and takes no such
/// knob. The field stays because the FFI record is frozen, and `validate_tuning` still holds it to
/// (0, 1] so a caller cannot tell it has stopped meaning anything.
const D_TRACTION_RELAX: f32 = 0.9;
/// Exaggerated gravity — and the reason the car can accelerate as hard as it does.
///
/// At the wheelie point `thrust × CoM height = weight × half-wheelbase`; substituting `T = m·a` the
/// MASS CANCELS, leaving a ceiling of `a_max = g·L/h` — 76 m/s² at g = 34 and `L/h` = 2.25 —
/// regardless of how much torque or mass is thrown at it. Torque alone therefore cannot buy
/// acceleration past that point; it only buys wheelspin and a backflip.
///
/// Gravity was raised to 60 to buy that ceiling, and it cost the JUMPS: hang time is `2v/g`, so at
/// 60 the car was slammed back down and never left the ground. Airtime and hard acceleration pull
/// against each other through this one constant.
///
/// The way out is the OTHER term. Dropping the centre of mass — smaller wheels, a lower tub, less ride
/// height — took `L/h` from 1.87 to 2.25, so a ceiling that needed g = 60 before now holds at g = 34.
/// That is a 76 % longer hang time for the same acceleration. Mass is not part of it in either
/// direction: a projectile's arc is mass-independent, and the mass cancels out of the wheelie
/// condition entirely. It was halved because a lighter car is thrown further by the same spring, which
/// is what makes a bump launch rather than absorb.
///
/// But that lever is SPENT — pulling it further now costs what it used to buy. Lowering `h` raises the
/// wheelie ceiling and lowers the rear-drive traction ceiling `(μ/2)/(1 − μ·h/2L) · cos θ`, since less
/// load transfers onto the driven wheel; the two cross at `h = L/μ` = 6.3 m. At h = 5.6 the car is
/// already past the crossing and traction is the binding ceiling (1.80 g flat against the wheelie's
/// 2.25), so every further centimetre off the roof line lowers the ceiling that actually binds.
const D_GRAVITY: f32 = 34.0;
/// Rollover threshold (rad). ~80°: steeper than any climbable slope, so a hill cannot
/// masquerade as a crash.
const D_CRASH_TILT: f32 = 1.4;

const PI: f32 = std::f32::consts::PI;
const TWO_PI: f32 = std::f32::consts::TAU;

#[inline]
fn dec(reason: impl Into<String>) -> CoreError {
    CoreError::Decode { reason: reason.into() }
}
#[inline]
fn internal(reason: impl Into<String>) -> CoreError {
    CoreError::Internal { reason: reason.into() }
}

/// A heightfield sample is solid ground only if it is finite and non-negative; anything
/// else is a GAP (no ground). See the module header — a CGM dropout is a chasm.
#[inline]
fn solid(h: f32) -> bool {
    h.is_finite() && h >= 0.0
}

/// Saturate a caller-supplied 0..1 control. Non-finite ⇒ 0 (released), never propagated.
#[inline]
fn sane01(v: f32) -> f32 {
    if v.is_finite() {
        v.clamp(0.0, 1.0)
    } else {
        0.0
    }
}

/// Clamp a derived solver gain into `[0, hi]`, mapping a non-finite value to 0.
#[inline]
fn gain(v: f32, hi: f32) -> f32 {
    if v.is_finite() {
        v.clamp(0.0, hi)
    } else {
        0.0
    }
}

/// Wrap an angle to (−π, π]. Keeps `ang` from growing without bound across many flips,
/// where f32 would start losing the fractional part.
#[inline]
fn wrap_pi(a: f32) -> f32 {
    if !a.is_finite() {
        return 0.0;
    }
    let mut a = a % TWO_PI;
    if a > PI {
        a -= TWO_PI;
    } else if a <= -PI {
        a += TWO_PI;
    }
    a
}

#[inline]
fn lerp(a: f32, b: f32, t: f32) -> f32 {
    a + (b - a) * t
}

/// Blend two angles the SHORT way round. A naive lerp across the wrap seam takes the long
/// arc — the car flips through a whole turn in one frame — and both the chassis pitch and the
/// wheels' rolled angle are wrapped values that cross it constantly.
#[inline]
fn lerp_angle(a: f32, b: f32, t: f32) -> f32 {
    a + wrap_pi(b - a) * t
}

// ── FFI types ───────────────────────────────────────────────────────────────────────────

/// How the current run ended, or that it has not. `Crashed` and `Finished` are
/// all TERMINAL: once set, [`GameWorld::step`] is a no-op that re-returns the frozen state,
/// which keeps the contract deterministic and the frame loop free (the renderer plays its
/// own outro). [`GameWorld::reset`] is the only way back to `Running`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum RunState {
    Running,
    /// Rollover (driver's head on the ground past the tilt threshold) or a fall past the
    /// kill plane at the bottom of a chasm.
    Crashed,
    /// Reached the right-hand edge of the heightfield — the present moment.
    Finished,
}

/// The terrain the car drives over. `heights[i]` is the ground height at `x = i·dx`, in
/// world units, and the ground is piecewise-linear between samples. A **negative or
/// non-finite** sample marks a GAP with no ground at all.
///
/// `world_height` is the nominal vertical extent of the play area; it only fixes the kill
/// plane at `−world_height` (one full world-height below the floor), which is what a fall
/// through a gap has to cross to end the run.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TerrainSpec {
    pub heights: Vec<f32>,
    pub dx: f32,
    pub world_height: f32,
}

/// Everything about the car a caller may bend. [`default_car_tuning`] is the offroad build
/// the game ships; the constants it is built from carry the provenance of each number.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct CarTuning {
    pub chassis_mass: f32,
    pub chassis_half_len: f32,
    pub chassis_half_height: f32,
    pub wheel_radius: f32,
    pub wheel_mass: f32,
    /// Free length of the suspension: attach point → wheel centre, unloaded.
    pub suspension_rest: f32,
    /// Maximum extension; beyond it the wheel has left the ground.
    pub suspension_travel: f32,
    pub suspension_stiffness: f32,
    pub suspension_damping: f32,
    /// Rear-wheel drive torque at full throttle (N·m).
    pub motor_torque: f32,
    pub brake_torque: f32,
    /// Rev limiter (rad/s): the drive motor holds full torque until the wheel is within
    /// `1/DRIVE_MOTOR_GAIN` of this, then fades to zero at it.
    pub max_wheel_omega: f32,
    /// Coulomb friction coefficient of the tyres against the terrain.
    pub grip: f32,
    /// Unused since the solver became rapier; `grip` alone sets the tyre now. Still validated to
    /// (0, 1] so the record's contract is unchanged. See [`D_TRACTION_RELAX`].
    pub traction_relax: f32,
    pub gravity: f32,
    /// Chassis tilt past which a head-to-ground contact is a rollover (rad).
    pub crash_tilt_rad: f32,
}

/// One frame of everything the renderer, the haptics and the synthesised engine audio need.
/// Flat scalars on purpose: a `uniffi::Record` of scalars costs one buffer read per frame,
/// whereas a `Vec<f32>` would box every element on the Kotlin side.
///
/// Angles are radians in a **y-up, counter-clockwise-positive** world. `rear_angle` /
/// `front_angle` are rolled angles in the *driving* sense (increasing = rolling forward
/// toward +x); a y-up canvas draws them negated.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct CarState {
    /// Chassis centre of mass.
    pub x: f32,
    pub y: f32,
    /// Chassis pitch; 0 is level, positive is nose-up.
    pub angle: f32,
    pub vx: f32,
    pub vy: f32,
    pub angular_velocity: f32,

    pub rear_x: f32,
    pub rear_y: f32,
    pub rear_angle: f32,
    /// Rear wheel spin (rad/s), positive rolling forward.
    pub rear_omega: f32,
    /// Carrying load, or within the solver's speculative reach of the ground — 0.2 m here, 5 % of
    /// the wheel's own radius. Instantaneous, unlike [`CarState::airborne`]. See [`Phys::touching`].
    pub rear_contact: bool,

    pub front_x: f32,
    pub front_y: f32,
    pub front_angle: f32,
    pub front_omega: f32,
    pub front_contact: bool,

    /// Synthesised engine speed. NOT a physical crank speed — a monotone function of rear
    /// wheel speed plus a throttle bump so revving a stalled wheel still sounds like work.
    pub rpm: f32,
    /// Throttle actually delivered. Drives the exhaust art.
    pub throttle_applied: f32,
    /// Normal impulse in excess of simply carrying the car's weight, accumulated over this step
    /// (N·s). Rectified, so it is one-sided: 6e-5 settled on flat ground and exactly 0 rolling it at
    /// the limiter, but a couple of newton-seconds while a fresh seat is still settling. Large on a
    /// landing or a bodywork slam — the haptics amplitude.
    pub impact_impulse: f32,
    /// Mean terrain slope-change under the car, saturating at 1. The rumble amplitude.
    pub roughness: f32,
    /// Nothing touching — neither wheel, nor bodywork — for [`AIRBORNE_ARM_TICKS`] running. NOT the
    /// instantaneous negation of the contact flags: this one is read as an edge, so it is
    /// deliberately slow to arm and instant to clear. See [`AIRBORNE_ARM_TICKS`].
    pub airborne: bool,
    /// Furthest x reached, measured from the start line. Monotone non-decreasing.
    pub distance_m: f32,
    /// Fraction of the tank left, in [0, 1].
    pub run: RunState,
    /// Simulated seconds consumed so far — substeps actually run, not wall clock.
    pub elapsed_s: f32,
}

/// The offroad build: large wheels, long travel, high torque, strong grip. Tuned so a
/// jagged glucose excursion is climbable rather than a trap. Exported so Kotlin never has
/// to transcribe these numbers — the Rust remains the single authority.
#[uniffi::export]
pub fn default_car_tuning() -> CarTuning {
    CarTuning {
        chassis_mass: D_MASS,
        chassis_half_len: D_HALF_LEN,
        chassis_half_height: D_HALF_HEIGHT,
        wheel_radius: D_WHEEL_RADIUS,
        wheel_mass: D_WHEEL_MASS,
        suspension_rest: D_SUSP_REST,
        suspension_travel: D_SUSP_TRAVEL,
        suspension_stiffness: D_SUSP_STIFFNESS,
        suspension_damping: D_SUSP_DAMPING,
        motor_torque: D_MOTOR_TORQUE,
        brake_torque: D_BRAKE_TORQUE,
        max_wheel_omega: D_MAX_WHEEL_OMEGA,
        grip: D_GRIP,
        traction_relax: D_TRACTION_RELAX,
        gravity: D_GRAVITY,
        crash_tilt_rad: D_CRASH_TILT,
    }
}

// ── Terrain ─────────────────────────────────────────────────────────────────────────────

/// The raw heightfield, gap markers intact. The collider's own copy is sanitised (see
/// [`build_ground`]), so every query that has to distinguish ground from a dropout reads this
/// one instead.
struct Terrain {
    heights: Vec<f32>,
    dx: f32,
    inv_dx: f32,
    /// x of the last sample; reaching it is `Finished`.
    length: f32,
    /// Falling below this ends the run (one world-height under the floor).
    kill_y: f32,
}

impl Terrain {
    /// Ground height at `x`, or `None` where the field has a gap. Both indices are clamped
    /// into range before any `heights[…]`, so no `x` — including ±inf — can slice OOB.
    #[inline]
    fn sample(&self, x: f32) -> Option<f32> {
        if !x.is_finite() {
            return None;
        }
        let n = self.heights.len(); // ≥ MIN_TERRAIN_SAMPLES by construction
        let last = n - 1;
        // A negative or NaN cast saturates to 0 in Rust; the clamp makes that explicit.
        let u = (x * self.inv_dx).clamp(0.0, last as f32);
        let i = (u as usize).min(last);
        let j = (i + 1).min(last);
        let (h0, h1) = (self.heights[i], self.heights[j]);
        if !solid(h0) || !solid(h1) {
            return None;
        }
        let f = (u - i as f32).clamp(0.0, 1.0);
        Some(h0 + (h1 - h0) * f)
    }

    /// Mean |second difference| of the heightfield over a fixed window centred on `x`,
    /// divided by `dx` (a mean slope change per sample) and normalised to [0, 1]. Gaps are
    /// skipped rather than treated as cliffs — a dropout is not a washboard.
    fn roughness(&self, x: f32) -> f32 {
        let n = self.heights.len();
        if n < 3 || !x.is_finite() {
            return 0.0;
        }
        let last = n - 1;
        let c = ((x * self.inv_dx).clamp(0.0, last as f32) as usize).min(last);
        let lo = c.saturating_sub(ROUGH_HALF_WINDOW).max(1);
        let hi = (c + ROUGH_HALF_WINDOW).min(n - 2);
        if hi < lo {
            return 0.0;
        }
        let mut acc = 0.0f32;
        let mut cnt = 0u32;
        for i in lo..=hi {
            let (a, b, d) = (self.heights[i - 1], self.heights[i], self.heights[i + 1]);
            if !solid(a) || !solid(b) || !solid(d) {
                continue;
            }
            acc += (a - 2.0 * b + d).abs();
            cnt += 1;
        }
        if cnt == 0 {
            return 0.0;
        }
        // Belt as well as braces: the constructor already rejects a `dx` whose reciprocal is not
        // finite, but this is the one number `snapshot` derives downstream of both the input
        // firewall and the per-substep finiteness check, and it crosses the FFI documented as
        // [0, 1]. Kotlin's `coerceIn` passes a NaN through unchanged, so a NaN here would reach
        // the rumble amplitude.
        let r = (acc / cnt as f32) * self.inv_dx / ROUGH_REF;
        if r.is_finite() {
            r.clamp(0.0, 1.0)
        } else {
            0.0
        }
    }
}

/// Build the collider shape for `raw` at `dx` spacing, plus the flat run-off left of the start
/// line. `None` when the trace has no solid cell anywhere — there is then no ground to collide
/// with at all, which is what an all-dropout track means.
///
/// WHY A POLYLINE AND NOT A HEIGHTFIELD. parry's 2D `HeightField` is the obvious fit and it is
/// the wrong one: it hands the narrow phase a bare `Segment` per cell with **no normal
/// constraints**, so a ball straddling a shared vertex picks up a second, *radial* contact from
/// the neighbouring segment — a normal tilted off the true surface even where the two cells are
/// exactly collinear. That is the classic internal-edge artefact, and on flat ground at 48 m/s
/// it kicked the wheel 44 cm into the air once per cell crossed (measured: clearance ranged over
/// [−0.013, +0.436] m, one lift-off per vertex, the rate tracking v/dx exactly). Invisible at
/// this world's scale but not inaudible: the rectified excess-over-weight that feeds the haptics
/// turned it into a spike train at ~16 crossings a second on ground with no bumps in it.
///
/// `Polyline` with [`PolylineFlags::ORIENTED`] is parry's fix for exactly that: it computes an
/// outward pseudo-normal at every vertex and clamps each contact normal into the cone the two
/// incident segments span, so the collinear case collapses to the face normal and there is
/// nothing left to kick. Same measurement on the same ground: [−0.0139, −0.0135] m, a 0.4 mm
/// envelope. 2D heightfields have no such flag — `HeightFieldFlags::FIX_INTERNAL_EDGES` is
/// `heightfield3.rs` only — so this is a shape choice, not a tuning one.
///
/// `ccw_face_normal([a, b])` is `(b − a)` rotated −90°, so the outward side is to the RIGHT of
/// the winding: to point the ground's normal up, the segments have to run right-to-LEFT. Hence
/// index pairs `[i + 1, i]` over vertices in natural order — the vertex array stays indexable
/// by sample while the winding is reversed.
///
/// A GAP is simply a cell with no index pair. Nothing is emitted for it, so it produces no
/// contact — the exact encoding a CGM dropout wants. The gap sample's *height* still matters
/// though: the vertex is in the array whether or not a segment references it, and a non-finite
/// marker there poisons the polyline's AABB and the broad phase stops reporting ground at all.
/// Carrying the last solid height forward keeps the AABB tight and leaves the live segment
/// abutting a hole ending at a plausible lip.
fn build_ground(raw: &[f32], dx: f32) -> Option<SharedShape> {
    let seed = raw.iter().copied().find(|h| solid(*h))?;
    let mut carry = seed;
    let mut verts = Vec::with_capacity(raw.len() + 1);
    for (i, &h) in raw.iter().enumerate() {
        if solid(h) {
            carry = h;
        }
        verts.push(Vector::new(i as f32 * dx, carry));
    }
    let mut idx: Vec<[u32; 2]> = Vec::with_capacity(raw.len());
    for i in 0..raw.len() - 1 {
        if solid(raw[i]) && solid(raw[i + 1]) {
            idx.push([i as u32 + 1, i as u32]);
        }
    }
    // The run-off: `Terrain::sample` clamps its index, so the ground it reports extends flat
    // forever to the left of the start line, and the car does use it — released on a steep start
    // it rolls backwards off x = 0 and has to keep rolling rather than fall out of the world.
    // Only when the first sample is solid, which is exactly when `sample` reports ground there.
    if solid(raw[0]) {
        let apron = verts.len() as u32;
        verts.push(Vector::new(-RUN_OFF_LENGTH, raw[0]));
        idx.push([0, apron]);
    }
    if idx.is_empty() {
        return None;
    }
    Some(SharedShape::new(Polyline::with_flags(verts, Some(idx), PolylineFlags::ORIENTED)))
}

// ── Simulation state ────────────────────────────────────────────────────────────────────

/// The renderer's view of one wheel, mirrored out of the rigid body after each substep.
#[derive(Clone, Copy)]
struct Wheel {
    /// Spin in the driving sense: positive rolls the car toward +x (rad/s).
    spin: f32,
    /// Accumulated rolled angle, wrapped to (−2π, 2π) (render only).
    angle: f32,
    contact: bool,
    /// World-space wheel centre.
    cx: f32,
    cy: f32,
}

/// Everything drawn about the car at one tick boundary. Kept for the tick BEFORE the current
/// one so [`Sim::snapshot`] can interpolate.
///
/// Frames arrive every ~16.7 ms and ticks are 1/120 s, so the accumulator's residue makes a
/// frame consume one tick or three where it usually consumes two. Reporting whichever tick
/// happened to land last therefore moves the car unequal distances on equal-duration frames —
/// judder that has nothing to do with the physics. Blending the two bracketing ticks by the
/// residue restores the correspondence between wall-clock and drawn motion.
#[derive(Clone, Copy)]
struct Pose {
    x: f32,
    y: f32,
    ang: f32,
    /// `[REAR]`, `[FRONT]`.
    wheels: [WheelPose; 2],
}

#[derive(Clone, Copy)]
struct WheelPose {
    cx: f32,
    cy: f32,
    angle: f32,
}

/// Where a rebuild puts the car: chassis pose and velocity, plus the suspension extension the
/// wheels are hung at.
#[derive(Clone, Copy)]
struct Seat {
    x: f32,
    y: f32,
    ang: f32,
    vx: f32,
    vy: f32,
    av: f32,
    ext: f32,
}

/// The rapier world. Rebuilt wholesale by [`Sim::seat_car`] rather than teleported: a teleport
/// leaves the narrow phase's warm-start impulses and the joint accumulators live, so the first
/// step after a reposition replays the previous run's impulse as a one-frame kick.
struct Phys {
    bodies: RigidBodySet,
    colliders: ColliderSet,
    joints: ImpulseJointSet,
    multibody_joints: MultibodyJointSet,
    islands: IslandManager,
    broad_phase: BroadPhaseBvh,
    narrow_phase: NarrowPhase,
    ccd: CCDSolver,
    pipeline: PhysicsPipeline,
    chassis: RigidBodyHandle,
    chassis_col: ColliderHandle,
    wheels: [RigidBodyHandle; 2],
    wheel_cols: [ColliderHandle; 2],
    susp: [ImpulseJointHandle; 2],
}

impl Phys {
    fn build(ground: &Option<SharedShape>, t: &CarTuning, seat: &Seat) -> Self {
        let mut bodies = RigidBodySet::new();
        let mut colliders = ColliderSet::new();
        let mut joints = ImpulseJointSet::new();

        // Absent only when the trace has no solid cell anywhere; an empty polyline would carry an
        // inverted AABB into the broad phase, and "no ground" is better said by saying nothing.
        if let Some(ground) = ground {
            let ground_body = bodies.insert(RigidBodyBuilder::fixed());
            colliders.insert_with_parent(
                ColliderBuilder::new(ground.clone()).friction(t.grip).restitution(0.0),
                ground_body,
                &mut bodies,
            );
        }

        let chassis = bodies.insert(
            RigidBodyBuilder::dynamic()
                .translation(Vector::new(seat.x, seat.y))
                .rotation(seat.ang)
                .linvel(Vector::new(seat.vx, seat.vy))
                .angvel(seat.av)
                .linear_damping(LINEAR_DAMPING)
                .angular_damping(ANGULAR_DAMPING)
                // At `LENGTH_UNIT` = 10 the sleep threshold is 4 m/s, so a creeping car would
                // be eligible — and a sleeping island freezes its contact impulses, which the
                // haptics read. Three bodies cost nothing to keep awake.
                .can_sleep(false)
                .ccd_enabled(false),
        );
        let chassis_col = colliders.insert_with_parent(
            ColliderBuilder::cuboid(t.chassis_half_len, t.chassis_half_height)
                // `mass` preserves the shape's I/m ratio, so the inertia is the solid box's
                // `m(w² + h²)/12` — the same figure the hand-rolled solver derived.
                .mass(t.chassis_mass)
                .friction(CHASSIS_FRICTION)
                .friction_combine_rule(CoefficientCombineRule::Min)
                .restitution(0.0),
            chassis,
            &mut bodies,
        );

        let (sin_a, cos_a) = seat.ang.sin_cos();
        let fwd = Vector::new(cos_a, sin_a);
        let up = Vector::new(-sin_a, cos_a);
        let com = Vector::new(seat.x, seat.y);

        let mut wheels = [RigidBodyHandle::invalid(); 2];
        let mut wheel_cols = [ColliderHandle::invalid(); 2];
        let mut susp = [ImpulseJointHandle::invalid(); 2];
        let wb = t.chassis_half_len * WHEELBASE_FRAC;

        for (i, lx) in [-wb, wb].into_iter().enumerate() {
            let anchor = Vector::new(lx, -t.chassis_half_height);
            let at = com + fwd * anchor.x + up * anchor.y - up * seat.ext;
            let r = at - com;
            let body = bodies.insert(
                RigidBodyBuilder::dynamic()
                    .translation(at)
                    .linvel(Vector::new(seat.vx - seat.av * r.y, seat.vy + seat.av * r.x))
                    .angular_damping(WHEEL_SPIN_DAMPING)
                    .can_sleep(false)
                    .ccd_enabled(false),
            );
            wheel_cols[i] = colliders.insert_with_parent(
                ColliderBuilder::ball(t.wheel_radius)
                    .mass(t.wheel_mass)
                    .friction(t.grip)
                    .restitution(0.0),
                body,
                &mut bodies,
            );

            // Pin-slot. Locking only LIN_Y leaves the wheel free along the joint's x — which
            // `local_axis1` points down the chassis — and free to spin about it. `dist` along
            // that axis IS the suspension extension, so the position motor's target and the
            // limit are both in metres below the anchor, and the locked perpendicular is the
            // chassis' fore/aft, which is what stops the wheel walking along the body.
            // `ForceBased` is what keeps the tuning's stiffness in N/m and damping in N·s/m
            // independent of the masses either side.
            let joint = GenericJointBuilder::new(JointAxesMask::LIN_Y)
                .local_axis1(-Vector::Y)
                .local_axis2(-Vector::Y)
                .local_anchor1(anchor)
                .local_anchor2(Vector::ZERO)
                .limits(JointAxis::LinX, [0.0, t.suspension_travel])
                .motor_model(JointAxis::LinX, MotorModel::ForceBased)
                .motor_position(
                    JointAxis::LinX,
                    t.suspension_rest,
                    t.suspension_stiffness,
                    t.suspension_damping,
                )
                .motor_model(JointAxis::AngX, MotorModel::ForceBased)
                .motor_velocity(JointAxis::AngX, 0.0, 0.0)
                .motor_max_force(JointAxis::AngX, 0.0)
                .contacts_enabled(false)
                .build();

            // Argument order is (body1, body2), and `local_axis1`/`local_anchor1` above are
            // written in CHASSIS coordinates — pass the wheel first and the car hangs off the
            // wheel's rim instead.
            susp[i] = joints.insert(chassis, body, joint, true);
            wheels[i] = body;
        }

        Phys {
            bodies,
            colliders,
            joints,
            multibody_joints: MultibodyJointSet::new(),
            islands: IslandManager::new(),
            broad_phase: BroadPhaseBvh::new(),
            narrow_phase: NarrowPhase::new(),
            ccd: CCDSolver::new(),
            pipeline: PhysicsPipeline::new(),
            chassis,
            chassis_col,
            wheels,
            wheel_cols,
            susp,
        }
    }

    fn step(&mut self, params: &IntegrationParameters, gravity: Vector) {
        self.pipeline.step(
            gravity,
            params,
            &mut self.islands,
            &mut self.broad_phase,
            &mut self.narrow_phase,
            &mut self.bodies,
            &mut self.colliders,
            &mut self.joints,
            &mut self.multibody_joints,
            &mut self.ccd,
            &(),
            &(),
        );
    }

    /// Is this collider carrying, or all but carrying, the ground?
    ///
    /// NOT `has_any_active_contact`: that is true whenever a solver contact exists, and solver
    /// contacts are speculative, so it reports a wheel hovering clear with no force through it.
    /// A pair that carried impulse, or whose deepest contact is within `tol`, is the honest test.
    /// When the collider is genuinely clear the whole pair disappears and the iterator is empty.
    ///
    /// `tol` cannot be the solver's penetration slop (0.01 m). A planted wheel does not rest AT the
    /// surface: speculative contacts settle it a little above, measured at +13.9 mm on a straight
    /// ramp at 47 m/s (over a bob of only 0.6 mm — the ride is quiet, the standoff is not zero).
    /// That standoff alone exceeds the slop, so at that tolerance the test falls back to "did this
    /// pair carry impulse", which goes false on the unloaded half of every oscillation. The caller
    /// passes the speculative distance instead — 0.2 m, 5 % of the wheel's own radius.
    fn touching(&self, col: ColliderHandle, tol: f32) -> bool {
        self.narrow_phase.contact_pairs_with(col).any(|pair| {
            pair.total_impulse_magnitude() > 0.0
                || pair.find_deepest_contact().is_some_and(|(_, c)| c.dist <= tol)
        })
    }

    /// Total normal impulse through this collider over the step (N·s). A ball on the terrain
    /// polyline straddles several segments and so produces several manifolds;
    /// `total_impulse_magnitude` folds over all of them.
    fn normal_impulse(&self, col: ColliderHandle) -> f32 {
        self.narrow_phase
            .contact_pairs_with(col)
            .map(|pair| pair.total_impulse_magnitude())
            .sum()
    }
}

struct Sim {
    terrain: Terrain,
    tune: CarTuning,
    /// The terrain collider's shape, run-off included. An `Arc` under the hood, so a rebuild
    /// re-hangs it rather than re-deriving (and re-BVH-ing) a polyline that can be 200 000
    /// segments long. `None` when the trace is solid nowhere.
    ground: Option<SharedShape>,
    params: IntegrationParameters,
    gravity: Vector,
    phys: Phys,

    // Mirror of the solver, refreshed after every substep that produced finite state. The
    // renderer reads this, never the bodies — so a poisoned solve freezes the last good frame
    // instead of handing a NaN across the FFI.
    x: f32,
    y: f32,
    ang: f32,
    vx: f32,
    vy: f32,
    av: f32,
    /// `[REAR]` = rear (driven), `[FRONT]` = front.
    wheels: [Wheel; 2],
    /// The pose one tick behind the mirror, for the render interpolation. Seeded equal to the
    /// mirror by [`Sim::seat_car`] so a fresh seat never blends against a previous run.
    prev: Pose,

    run: RunState,
    start_x: f32,
    max_x: f32,
    elapsed: f32,
    accumulator: f32,
    /// Excess normal impulse accumulated since the last `step` boundary.
    impact: f32,
    throttle_applied: f32,
    /// The REPORTED grounded state: `!airborne`, after [`AIRBORNE_ARM_TICKS`] of hysteresis.
    any_contact: bool,
    /// Consecutive ticks with nothing touching, for that hysteresis.
    air_ticks: u32,

    /// Static-sag extension the car is seated at.
    sag_ext: f32,
    /// Impulse one substep of merely standing still costs (N·s) — the whole car's weight, wheels
    /// included, since the wheels are rigid bodies now rather than massless raycasts.
    weight_impulse: f32,
}

impl Sim {
    fn new(terrain: Terrain, tune: CarTuning) -> Self {
        let ground = build_ground(&terrain.heights, terrain.dx);
        let params = IntegrationParameters {
            dt: FIXED_DT,
            length_unit: LENGTH_UNIT,
            num_solver_iterations: SOLVER_SUBSTEPS,
            normalized_prediction_distance: PREDICTION_DISTANCE,
            normalized_allowed_linear_error: ALLOWED_LINEAR_ERROR,
            max_ccd_substeps: 0,
            ..Default::default()
        };
        // Static sag: the extension at which both springs carry half the weight each.
        let sag = if tune.suspension_stiffness > 0.0 {
            (0.5 * tune.chassis_mass * tune.gravity / tune.suspension_stiffness)
                .clamp(0.0, tune.suspension_rest)
        } else {
            0.0
        };
        let sag_ext = (tune.suspension_rest - sag).clamp(0.0, tune.suspension_travel);
        let seat = Seat { x: 0.0, y: 0.0, ang: 0.0, vx: 0.0, vy: 0.0, av: 0.0, ext: sag_ext };
        let phys = Phys::build(&ground, &tune, &seat);
        let mut s = Sim {
            terrain,
            tune,
            ground,
            params,
            gravity: Vector::new(0.0, -tune.gravity),
            phys,
            x: 0.0,
            y: 0.0,
            ang: 0.0,
            vx: 0.0,
            vy: 0.0,
            av: 0.0,
            wheels: [Wheel { spin: 0.0, angle: 0.0, contact: false, cx: 0.0, cy: 0.0 }; 2],
            prev: Pose {
                x: 0.0,
                y: 0.0,
                ang: 0.0,
                wheels: [WheelPose { cx: 0.0, cy: 0.0, angle: 0.0 }; 2],
            },
            run: RunState::Running,
            start_x: 0.0,
            max_x: 0.0,
            elapsed: 0.0,
            accumulator: 0.0,
            impact: 0.0,
            throttle_applied: 0.0,
            any_contact: false,
            air_ticks: 0,
            sag_ext,
            weight_impulse: (tune.chassis_mass + 2.0 * tune.wheel_mass) * tune.gravity * FIXED_DT,
        };
        s.reset();
        s
    }

    /// Rebuild the world with the car at `seat`, and re-derive the mirror from it. Bookkeeping
    /// (run state, distance, elapsed) is deliberately untouched — [`Sim::reset_from`]
    /// owns that.
    fn seat_car(&mut self, seat: Seat) {
        let ok = seat.x.is_finite()
            && seat.y.is_finite()
            && seat.ang.is_finite()
            && seat.vx.is_finite()
            && seat.vy.is_finite()
            && seat.av.is_finite()
            && seat.ext.is_finite();
        let seat = if ok {
            seat
        } else {
            Seat { x: 0.0, y: 0.0, ang: 0.0, vx: 0.0, vy: 0.0, av: 0.0, ext: self.sag_ext }
        };
        self.phys = Phys::build(&self.ground, &self.tune, &seat);

        self.x = seat.x;
        self.y = seat.y;
        self.ang = wrap_pi(seat.ang);
        self.vx = seat.vx;
        self.vy = seat.vy;
        self.av = seat.av;
        // Derive the contact flags from where the car actually landed rather than asserting
        // them. A seat can legitimately be over a chasm — `reset_from` falls back to the tap
        // when no landable run exists at all — and the renderer, the haptics bed and the audio
        // all read this frame before any physics has run, so asserting contact painted one frame
        // of a planted car that was in fact in free fall.
        let mut any = false;
        for i in 0..2 {
            let at = self.phys.bodies[self.phys.wheels[i]].translation();
            let down = self
                .terrain
                .sample(at.x)
                .is_some_and(|h| at.y - h <= self.tune.wheel_radius + self.params.prediction_distance());
            any |= down;
            self.wheels[i] = Wheel { spin: 0.0, angle: 0.0, contact: down, cx: at.x, cy: at.y };
        }
        self.any_contact = any;
        self.air_ticks = if any { 0 } else { AIRBORNE_ARM_TICKS };
        self.prev = self.pose();
    }

    fn pose(&self) -> Pose {
        let w = |i: usize| {
            let w = self.wheels[i];
            WheelPose { cx: w.cx, cy: w.cy, angle: w.angle }
        };
        Pose { x: self.x, y: self.y, ang: self.ang, wheels: [w(REAR), w(FRONT)] }
    }

    /// Place the car, settled, on the first stretch of solid ground long enough to hold it.
    fn reset(&mut self) {
        self.reset_from(0.0);
    }

    /// Index at which the first run of more than `span` consecutive solid samples begins,
    /// searching forward from `from`. `None` when no such run exists — there is no landable
    /// ground left.
    fn landable_from(&self, from: usize, span: usize) -> Option<usize> {
        let n = self.terrain.heights.len();
        let mut run = 0usize;
        for i in from..n {
            if solid(self.terrain.heights[i]) {
                run += 1;
                if run > span {
                    return Some(i + 1 - run);
                }
            } else {
                run = 0;
            }
        }
        None
    }

    /// Place the car on the first stretch of solid ground at or after `from_x`.
    ///
    /// The track spans the whole visible window so the curve is not cut off behind the car, which
    /// means the START is no longer the track's origin — it is wherever the user tapped. Searching
    /// forward from there (rather than from zero) is what puts the car under the finger.
    fn reset_from(&mut self, from_x: f32) {
        let t = self.tune;
        let n = self.terrain.heights.len();
        let span = ((2.0 * t.chassis_half_len * self.terrain.inv_dx).ceil() as usize).max(1);
        let begin = if from_x.is_finite() {
            ((from_x * self.terrain.inv_dx).floor().max(0.0) as usize).min(n.saturating_sub(1))
        } else {
            0
        };
        // No landable run at or after the tap — a tap past the last solid ground, or onto a chasm
        // that never closes — used to leave the car wherever the finger fell. Fall back to the first
        // landable run anywhere on the track instead, so a seat is always ground the car can sit on.
        let first = self
            .landable_from(begin, span)
            .or_else(|| self.landable_from(0, span))
            .unwrap_or(begin);
        // The seat has to leave the car ON the track. `first` can sit at the very end of the
        // heightfield, and adding the chassis half-length then put `sx` at or past `terrain.length`
        // — which IS the finish line, so the run was Finished on its first simulated step and the
        // car never moved. Clamping here rather than only at the call site keeps the FFI sound for
        // any caller, `reset_at` included.
        let max_sx = (self.terrain.length - t.chassis_half_len).max(0.0);
        let sx = (first as f32 * self.terrain.dx + t.chassis_half_len).min(max_sx);
        let ext = self.sag_ext;

        // Land the car ALIGNED with the local grade, not level. A level chassis dropped on a
        // slope steeper than `suspension_travel / wheelbase` starts with a wheel hanging in
        // space; it then slams down and the recovery reads as a launch — which is exactly
        // how a merely steep start line turns into an instant backflip.
        let wb = t.chassis_half_len * WHEELBASE_FRAC;
        let hr = self.terrain.sample(sx - wb);
        let hf = self.terrain.sample(sx + wb);
        let (ang, mid_y) = match (hr, hf) {
            (Some(a), Some(b)) => (((b - a) / (2.0 * wb)).atan(), 0.5 * (a + b) + t.wheel_radius),
            _ => (0.0, self.terrain.sample(sx).unwrap_or(0.0) + t.wheel_radius),
        };
        let (sin_a, cos_a) = ang.sin_cos();
        let lift = t.chassis_half_height + ext;

        self.seat_car(Seat {
            x: sx + -sin_a * lift,
            y: mid_y + cos_a * lift,
            ang,
            vx: 0.0,
            vy: 0.0,
            av: 0.0,
            ext,
        });
        self.run = RunState::Running;
        self.start_x = sx;
        // The same reference as the start line, NOT `self.x`: the chassis centre of mass sits at
        // `sx − sin(ang)·lift`, so on a downhill grade it is already ahead of the start and a
        // fresh seat would report metres travelled before the car had moved (1.59 m on a −1.0
        // grade), while on an uphill one the `.max(0.0)` would silently eat the same distance.
        self.max_x = sx;
        self.elapsed = 0.0;
        self.accumulator = 0.0;
        self.impact = 0.0;
        self.throttle_applied = 0.0;
    }

    fn advance(&mut self, dt_ms: f32, throttle: f32, brake: f32) {
        self.impact = 0.0;
        if self.run != RunState::Running {
            self.throttle_applied = 0.0;
            return;
        }
        let thr = sane01(throttle);
        let brk = sane01(brake);
        let dt_s = if dt_ms.is_finite() { (dt_ms * 1e-3).clamp(0.0, MAX_FRAME_DT_S) } else { 0.0 };
        // The accumulator itself is railed: a burst of long frames cannot bank unbounded
        // simulation debt.
        self.accumulator = (self.accumulator + dt_s).clamp(0.0, MAX_FRAME_DT_S + FIXED_DT);

        let mut n = 0u32;
        while self.accumulator >= FIXED_DT && n < MAX_SUBSTEPS {
            self.substep(FIXED_DT, thr, brk);
            self.accumulator -= FIXED_DT;
            self.elapsed += FIXED_DT;
            n += 1;
            if self.run != RunState::Running {
                break;
            }
        }
        if n >= MAX_SUBSTEPS {
            // Drop the surplus rather than chase it: a frame loop must never spiral.
            self.accumulator = 0.0;
        }
    }

    fn substep(&mut self, dt: f32, throttle: f32, brake: f32) {
        let t = self.tune;

        let thr = throttle;
        self.throttle_applied = thr;

        self.drive(thr, brake);
        // A grounded wheel is slowed at the contact patch; an airborne one has only its
        // bearing, and damping it at the contact rate visibly halts it mid-jump.
        for i in 0..2 {
            let damp = if self.wheels[i].contact { WHEEL_SPIN_DAMPING } else { WHEEL_SPIN_DAMPING_AIR };
            if let Some(b) = self.phys.bodies.get_mut(self.phys.wheels[i]) {
                b.set_angular_damping(damp);
            }
        }

        self.phys.step(&self.params, self.gravity);

        // ── the non-finite rail: freeze rather than propagate a NaN into the frame ─────
        // Nothing in rapier panics on a hostile number; it poisons the state silently. The
        // mirror still holds the last finite frame, so freezing here is what the old solver's
        // roll-back was: the run ends, and the renderer plays its crash outro over a sane pose.
        if !self.solver_finite() {
            self.run = RunState::Crashed;
            return;
        }
        self.rail_velocities();

        // The solver's own speculative reach: inside it a contact is still being constrained, and
        // it is scaled by `LENGTH_UNIT` so it tracks this world rather than a metre-scale one.
        let tol = self.params.prediction_distance();
        let rear_c = self.phys.touching(self.phys.wheel_cols[REAR], tol);
        let front_c = self.phys.touching(self.phys.wheel_cols[FRONT], tol);
        let body_c = self.phys.touching(self.phys.chassis_col, tol);
        let any_contact = rear_c || front_c || body_c;
        self.air_ticks = if any_contact { 0 } else { self.air_ticks.saturating_add(1) };

        // AIRBORNE PITCH CONTROL (the hill-climber's signature move): with no wheel on the ground the
        // pedals stop being drive and become attitude — throttle rotates the nose up, brake rotates it
        // down — so a jump can be landed level instead of being a coin toss. Keyed off the RAW
        // contact, not the hysteresised flag: the pedal has to bite the moment the ground is gone,
        // and a stray tick of it is 0.05 rad/s.
        if !any_contact {
            let pitch = (thr - brake).clamp(-1.0, 1.0);
            if let Some(b) = self.phys.bodies.get_mut(self.phys.chassis) {
                let av = (b.angvel() + pitch * AIR_PITCH_ACCEL * dt).clamp(-MAX_ANGULAR, MAX_ANGULAR);
                b.set_angvel(av, true);
            }
        }

        self.mirror(dt, [rear_c, front_c], self.air_ticks < AIRBORNE_ARM_TICKS);

        // Excess over merely holding the car up — the haptics signal, ~0 at rest, large on a
        // landing or a bodywork slam. Every ground contact counts, wheels and bodywork alike.
        let carried = self.phys.normal_impulse(self.phys.wheel_cols[REAR])
            + self.phys.normal_impulse(self.phys.wheel_cols[FRONT])
            + self.phys.normal_impulse(self.phys.chassis_col);
        self.impact += (carried * IMPULSE_WARMSTART_BIAS - self.weight_impulse).max(0.0);

        // ── rollover: the driver's head touching ground past the tilt threshold ────────
        let (sin_a, cos_a) = self.ang.sin_cos();
        let hx_l = -HEAD_BACK_FRAC * t.chassis_half_len;
        let hy_l = t.chassis_half_height + HEAD_ABOVE_ROOF;
        let hx = self.x + cos_a * hx_l + -sin_a * hy_l;
        let hy = self.y + sin_a * hx_l + cos_a * hy_l;
        let head_down = match self.terrain.sample(hx) {
            Some(h) => hy <= h,
            None => false,
        };
        let rolled = self.ang.abs() > t.crash_tilt_rad;

        // ── run rules, in priority order ───────────────────────────────────────────────
        if self.x >= self.terrain.length {
            self.run = RunState::Finished;
        } else if self.y < self.terrain.kill_y {
            self.run = RunState::Crashed;
        } else if head_down && rolled {
            self.run = RunState::Crashed;
        }
    }

    /// Point the rear joint's spin motor at the rev limiter, bounded by the pedal's torque.
    ///
    /// The motor targets the RELATIVE rate `ω_wheel − ω_chassis`, which is what a drivetrain
    /// physically commands, and the chassis takes the exact equal-and-opposite reaction. Forward
    /// motion toward +x is a NEGATIVE spin in a y-up, counter-clockwise-positive world, so the
    /// forward target is negative and brake — which is also reverse — is positive.
    fn drive(&mut self, throttle: f32, brake: f32) {
        let t = self.tune;
        let target = (brake - throttle) * t.max_wheel_omega;
        let tau = gain(throttle * t.motor_torque + brake * t.brake_torque, MAX_MOTOR_GAIN);
        let factor = gain(DRIVE_MOTOR_GAIN * tau / t.max_wheel_omega, MAX_MOTOR_GAIN);
        let target = if target.is_finite() { target } else { 0.0 };
        if let Some(j) = self.phys.joints.get_mut(self.phys.susp[REAR], true) {
            j.data
                .set_motor_velocity(JointAxis::AngX, target, factor)
                .set_motor_max_force(JointAxis::AngX, tau);
        }
    }

    fn solver_finite(&self) -> bool {
        let finite = |v: Vector| v.x.is_finite() && v.y.is_finite();
        let Some(c) = self.phys.bodies.get(self.phys.chassis) else { return false };
        if !(finite(c.translation())
            && finite(c.linvel())
            && c.angvel().is_finite()
            && c.rotation().angle().is_finite())
        {
            return false;
        }
        self.phys
            .wheels
            .iter()
            .all(|h| self.phys.bodies.get(*h).is_some_and(|b| finite(b.translation())))
    }

    fn rail_velocities(&mut self) {
        if let Some(b) = self.phys.bodies.get_mut(self.phys.chassis) {
            let v = b.linvel();
            b.set_linvel(
                Vector::new(v.x.clamp(-MAX_SPEED, MAX_SPEED), v.y.clamp(-MAX_SPEED, MAX_SPEED)),
                true,
            );
            b.set_angvel(b.angvel().clamp(-MAX_ANGULAR, MAX_ANGULAR), true);
        }
        for h in self.phys.wheels {
            if let Some(b) = self.phys.bodies.get_mut(h) {
                let v = b.linvel();
                b.set_linvel(
                    Vector::new(v.x.clamp(-MAX_SPEED, MAX_SPEED), v.y.clamp(-MAX_SPEED, MAX_SPEED)),
                    true,
                );
                b.set_angvel(b.angvel().clamp(-8.0 * MAX_ANGULAR, 8.0 * MAX_ANGULAR), true);
            }
        }
    }

    /// Copy the solved state into the renderer's mirror. Only ever called after
    /// [`Sim::solver_finite`] has passed.
    fn mirror(&mut self, dt: f32, contact: [bool; 2], any_contact: bool) {
        self.prev = self.pose();
        let c = &self.phys.bodies[self.phys.chassis];
        let p = c.translation();
        self.x = p.x;
        self.y = p.y;
        self.ang = wrap_pi(c.rotation().angle());
        let v = c.linvel();
        self.vx = v.x;
        self.vy = v.y;
        self.av = c.angvel();

        for i in 0..2 {
            let b = &self.phys.bodies[self.phys.wheels[i]];
            let at = b.translation();
            let w = &mut self.wheels[i];
            // The FFI's sense is positive-rolling-forward; the solver's is counter-clockwise.
            w.spin = -b.angvel();
            w.angle = (w.angle + w.spin * dt) % TWO_PI;
            if !w.angle.is_finite() {
                w.angle = 0.0;
            }
            w.cx = at.x;
            w.cy = at.y;
            w.contact = contact[i];
        }
        self.any_contact = any_contact;
        self.max_x = self.max_x.max(self.x);
    }

    /// The frame the renderer draws. The POSE is interpolated between the last two ticks by
    /// however much of a tick the accumulator is still holding — see [`Pose`]. Everything else
    /// is the current tick verbatim: a velocity, an impulse or a run verdict blended with its
    /// predecessor would be a lie about the state the physics is actually in, and the haptics
    /// and the audio read those.
    fn snapshot(&self) -> CarState {
        let rear = self.wheels[REAR];
        let front = self.wheels[FRONT];
        let rpm = (IDLE_RPM
            + rear.spin.abs() * RPM_PER_RAD_S
            + self.throttle_applied * THROTTLE_RPM_BUMP)
            .clamp(0.0, MAX_RPM);
        let a = sane01(self.accumulator / FIXED_DT);
        let p = self.prev;
        let (pr, pf) = (p.wheels[REAR], p.wheels[FRONT]);
        CarState {
            x: lerp(p.x, self.x, a),
            y: lerp(p.y, self.y, a),
            angle: wrap_pi(lerp_angle(p.ang, self.ang, a)),
            vx: self.vx,
            vy: self.vy,
            angular_velocity: self.av,
            rear_x: lerp(pr.cx, rear.cx, a),
            rear_y: lerp(pr.cy, rear.cy, a),
            rear_angle: lerp_angle(pr.angle, rear.angle, a) % TWO_PI,
            rear_omega: rear.spin,
            rear_contact: rear.contact,
            front_x: lerp(pf.cx, front.cx, a),
            front_y: lerp(pf.cy, front.cy, a),
            front_angle: lerp_angle(pf.angle, front.angle, a) % TWO_PI,
            front_omega: front.spin,
            front_contact: front.contact,
            rpm,
            throttle_applied: self.throttle_applied,
            impact_impulse: self.impact,
            roughness: self.terrain.roughness(self.x),
            airborne: !self.any_contact,
            distance_m: (self.max_x - self.start_x).max(0.0),
            run: self.run,
            elapsed_s: self.elapsed,
        }
    }

    /// Re-seat the car mid-run at a chosen pitch, height and descent rate, leaving the run's
    /// bookkeeping alone. Only the behavioural tests use it — a drop test needs to put the car
    /// in the air, which the FFI deliberately offers no way to do.
    #[cfg(test)]
    fn place_for_test(&mut self, ang: f32, y: f32, vy: f32) {
        let seat = Seat { x: self.x, y, ang, vx: self.vx, vy, av: self.av, ext: self.sag_ext };
        self.seat_car(seat);
    }
}

// ── FFI surface ─────────────────────────────────────────────────────────────────────────

/// One hill-climb world: terrain + car + run state. `Arc`-shared and internally locked, so
/// a Compose frame loop can hold it in a `remember { }` and the renderer, the haptics and
/// the audio all read one [`CarState`] per frame.
///
/// Own it deterministically: the generated Kotlin object is `AutoCloseable` behind a JVM
/// `Cleaner`, so dropping the reference frees the Rust world only at the next GC. Close it
/// in a `DisposableEffect` when the screen goes away.
#[derive(uniffi::Object)]
pub struct GameWorld {
    inner: Mutex<Sim>,
}

#[uniffi::export]
impl GameWorld {
    /// Build a world. Validates the heightfield and every tuning field up front — this is
    /// the ONE fallible, allocating, message-formatting entry point; `step` is not.
    #[uniffi::constructor]
    pub fn new(terrain: TerrainSpec, tuning: CarTuning) -> Result<Arc<Self>, CoreError> {
        let n = terrain.heights.len();
        if n < MIN_TERRAIN_SAMPLES {
            return Err(dec(format!("terrain: {n} samples (need ≥ {MIN_TERRAIN_SAMPLES})")));
        }
        if n > MAX_TERRAIN_SAMPLES {
            return Err(dec(format!("terrain: {n} samples (max {MAX_TERRAIN_SAMPLES})")));
        }
        // The reciprocal has to be finite too. A sub-normal `dx` (1.4e-45, say) is finite and
        // positive and gives a finite `length`, but `inv_dx` overflows to +inf — and every
        // derived signal that multiplies by it then reads `0 · inf = NaN`, which `clamp` does
        // NOT sanitise: it rejects a NaN *bound*, never a NaN *self*.
        if !terrain.dx.is_finite() || terrain.dx <= 0.0 || !(1.0 / terrain.dx).is_finite() {
            return Err(dec(format!("terrain: dx must be finite and > 0, got {}", terrain.dx)));
        }
        if !terrain.world_height.is_finite() || terrain.world_height <= 0.0 {
            return Err(dec(format!(
                "terrain: world_height must be finite and > 0, got {}",
                terrain.world_height
            )));
        }
        let length = (n - 1) as f32 * terrain.dx;
        if !length.is_finite() || length <= 0.0 || length > MAX_TRACK_LENGTH {
            return Err(dec(format!(
                "terrain: track length must be in (0, {MAX_TRACK_LENGTH}] m, got {length}"
            )));
        }
        validate_tuning(&tuning)?;

        let dx = terrain.dx;
        let t = Terrain {
            inv_dx: 1.0 / dx,
            length,
            kill_y: -terrain.world_height,
            heights: terrain.heights,
            dx,
        };
        Ok(Arc::new(Self { inner: Mutex::new(Sim::new(t, tuning)) }))
    }

    /// Advance by one rendered frame and return everything the frame needs.
    ///
    /// `dt_ms` is the wall-clock frame delta; it is accumulated and consumed in fixed
    /// 1/120 s ticks (two per 60 fps frame), with the surplus beyond [`MAX_SUBSTEPS`]
    /// dropped so a stalled frame cannot cascade. `throttle` and `brake` are saturated to
    /// [0, 1]; a non-finite control reads as released. Once the run is terminal this is a
    /// pure re-read of the frozen state.
    pub fn step(&self, dt_ms: f32, throttle: f32, brake: f32) -> Result<CarState, CoreError> {
        let mut sim = self.lock()?;
        sim.advance(dt_ms, throttle, brake);
        Ok(sim.snapshot())
    }

    /// The current frame without advancing — for a paused screen or a fresh recomposition.
    pub fn state(&self) -> Result<CarState, CoreError> {
        Ok(self.lock()?.snapshot())
    }

    /// Put the car back on the start line with a full tank and a `Running` run.
    pub fn reset(&self) -> Result<CarState, CoreError> {
        let mut sim = self.lock()?;
        sim.reset();
        Ok(sim.snapshot())
    }

    /// Re-place the car at the first solid ground at or after `x` (world metres) and report the new
    /// state. Used to drop the car where the user tapped, in a track that spans the whole visible
    /// window rather than beginning under the car.
    pub fn reset_at(&self, x: f32) -> Result<CarState, CoreError> {
        let mut sim = self.lock()?;
        sim.reset_from(x);
        Ok(sim.snapshot())
    }

    /// x of the finish line: the right-hand edge of the heightfield.
    pub fn track_length(&self) -> Result<f32, CoreError> {
        Ok(self.lock()?.terrain.length)
    }
}

// Helpers live OUTSIDE the exported block: uniffi tries to export every method in an
// annotated `impl` and rejects signatures it cannot lower (the `watch::WatchSession`
// precedent).
impl GameWorld {
    fn lock(&self) -> Result<std::sync::MutexGuard<'_, Sim>, CoreError> {
        self.inner.lock().map_err(|_| internal("game world lock poisoned"))
    }
}

/// Reject a tuning that would make the integrator meaningless (or divide by zero) before it
/// can ever reach the step path.
fn validate_tuning(t: &CarTuning) -> Result<(), CoreError> {
    let positive: [(&str, f32); 9] = [
        ("chassis_mass", t.chassis_mass),
        ("chassis_half_len", t.chassis_half_len),
        ("chassis_half_height", t.chassis_half_height),
        ("wheel_radius", t.wheel_radius),
        ("wheel_mass", t.wheel_mass),
        ("suspension_travel", t.suspension_travel),
        ("suspension_stiffness", t.suspension_stiffness),
        ("max_wheel_omega", t.max_wheel_omega),
        ("gravity", t.gravity),
    ];
    for (name, v) in positive {
        if !v.is_finite() || v <= 0.0 {
            return Err(dec(format!("tuning: {name} must be finite and > 0, got {v}")));
        }
    }
    let non_negative: [(&str, f32); 4] = [
        ("suspension_damping", t.suspension_damping),
        ("motor_torque", t.motor_torque),
        ("brake_torque", t.brake_torque),
        ("grip", t.grip),
    ];
    for (name, v) in non_negative {
        if !v.is_finite() || v < 0.0 {
            return Err(dec(format!("tuning: {name} must be finite and ≥ 0, got {v}")));
        }
    }
    if !t.suspension_rest.is_finite() || t.suspension_rest <= 0.0 || t.suspension_rest > t.suspension_travel {
        return Err(dec(format!(
            "tuning: suspension_rest must be in (0, suspension_travel], got {}",
            t.suspension_rest
        )));
    }
    if !t.traction_relax.is_finite() || t.traction_relax <= 0.0 || t.traction_relax > 1.0 {
        return Err(dec(format!(
            "tuning: traction_relax must be in (0, 1], got {}",
            t.traction_relax
        )));
    }
    if !t.crash_tilt_rad.is_finite() || t.crash_tilt_rad <= 0.0 || t.crash_tilt_rad > PI {
        return Err(dec(format!(
            "tuning: crash_tilt_rad must be in (0, π], got {}",
            t.crash_tilt_rad
        )));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    const GOLDEN: &str = include_str!("testdata/game_golden.json");

    fn golden() -> Value {
        serde_json::from_str(GOLDEN).unwrap()
    }

    fn assert_close(got: f32, want: f32, tol: f32, what: &str) {
        assert!(
            (got - want).abs() <= tol,
            "{what}: got {got}, want {want} (|Δ|={:.3e} > {tol:.1e})",
            (got - want).abs()
        );
    }

    /// Flat ground, 400 m at 1 m spacing.
    fn flat() -> TerrainSpec {
        TerrainSpec { heights: vec![10.0; 401], dx: 1.0, world_height: 40.0 }
    }

    /// A constant grade rising to the right; `rise` metres per metre.
    /// A constant grade, long enough that the car cannot reach the end of it inside any test's frame
    /// budget. It is 2 km rather than 400 m because the shipped tune covers 400 m in about five
    /// seconds: on the shorter ramp the climb tests were not measuring a climb at all by the end, they
    /// were measuring a car that had run off the top and was airborne over the void past it.
    fn ramp(rise: f32) -> TerrainSpec {
        let heights: Vec<f32> = (0..2001).map(|i| 10.0 + rise * i as f32).collect();
        TerrainSpec { heights, dx: 1.0, world_height: 2_000.0 }
    }

    /// The shape the game actually renders: a jagged glucose excursion. Deterministic —
    /// two sines plus a fixed 5-sample sawtooth jitter, no RNG.
    fn glucose_terrain() -> TerrainSpec {
        let heights: Vec<f32> = (0..601)
            .map(|i| {
                let x = i as f32;
                let slow = 6.0 * (x * 0.017).sin();
                let fast = 1.8 * (x * 0.11).sin();
                let jitter = 0.35 * ((i % 5) as f32 - 2.0);
                (14.0 + slow + fast + jitter).max(0.5)
            })
            .collect();
        TerrainSpec { heights, dx: 1.0, world_height: 40.0 }
    }

    fn world(spec: TerrainSpec) -> Arc<GameWorld> {
        GameWorld::new(spec, default_car_tuning()).expect("world must build")
    }

    /// Drive for `frames` at a nominal 60 fps and return the last frame.
    fn drive(w: &GameWorld, frames: u32, throttle: f32, brake: f32) -> CarState {
        let mut last = w.state().unwrap();
        for _ in 0..frames {
            last = w.step(1000.0 / 60.0, throttle, brake).unwrap();
        }
        last
    }

    // ── the car sits still on flat ground ───────────────────────────────────────────────

    #[test]
    fn rests_stably_on_flat_ground() {
        let w = world(flat());
        let start = w.state().unwrap();
        let s = drive(&w, 240, 0.0, 0.0); // 4 s
        assert_eq!(s.run, RunState::Running);
        assert_close(s.x, start.x, 0.05, "resting x drift");
        assert_close(s.y, start.y, 0.02, "resting y drift");
        assert_close(s.angle, 0.0, 0.01, "resting pitch");
        assert!(s.vx.abs() < 0.05, "resting vx = {}", s.vx);
        assert!(s.vy.abs() < 0.05, "resting vy = {}", s.vy);
        assert!(s.rear_contact && s.front_contact, "both wheels must stay down");
        assert!(!s.airborne);
        // Nothing is landing, so the excess-normal impulse must stay near zero. The bound is 5 and
        // not 0.1 because the four seconds this drives cover the SEAT TRANSIENT: the car is placed
        // at the analytic static sag and takes ~1.7 s to settle into the solver's own equilibrium,
        // peaking at 2.1 N·s on the way — 1.5 % of one substep's weight impulse. Past 3.3 s the
        // floor is 6e-5, so what this bound really rules out is a car that never settles.
        assert!(s.impact_impulse < 5.0, "resting impact = {}", s.impact_impulse);
        assert_close(s.roughness, 0.0, 1e-6, "flat roughness");
    }

    #[test]
    fn rolling_flat_ground_at_speed_registers_no_impacts() {
        // Ground with no bumps in it must produce no bumps. It is the collider's job, not the
        // haptics': the 2D heightfield this replaced kicked the wheel 44 cm up at every cell vertex
        // — invisible against a 40 m world, but a 16 Hz spike train through `impact_impulse`, which
        // `GameFeel` fires a cue on the rising edge of.
        let w = world(TerrainSpec { heights: vec![10.0; 4001], dx: 1.0, world_height: 40.0 });
        drive(&w, 600, 1.0, 0.0); // reach the limiter
        let mut peak = 0.0f32;
        for _ in 0..600 {
            peak = peak.max(w.step(1000.0 / 60.0, 1.0, 0.0).unwrap().impact_impulse);
        }
        let s = w.state().unwrap();
        assert!(s.vx > 40.0, "should be on the limiter, vx = {}", s.vx);
        assert!(peak < 5.0, "flat ground at {} m/s registered impacts, peak = {peak}", s.vx);
        assert!(!s.airborne && s.rear_contact && s.front_contact, "and never left the ground");
    }

    #[test]
    fn airborne_does_not_strobe_on_a_jagged_trace() {
        // `airborne` is read as an EDGE — the touchdown that fires the landing one-shot — so a wheel
        // clearing a sharp lip for a tick must not read as a flight. On this fixture (a 1.4 m rise
        // thrown away in a single 1 m step) the unhysteresised flag produced 49 "flights" in 15 s,
        // 48 of them two frames or shorter: a train of thuds, which is the failure 3252335 closed on
        // the impulse channel arriving through this one.
        // What this asserts is the MECHANISM, not a headcount, because neither a count nor a duration
        // can see a strobe once the flag is hysteresised. A count cannot: at the shipped limiter this
        // fixture launches the car for real, so more episodes is as likely to mean genuine airtime as
        // chatter. A duration cannot either, and that is the subtler trap — `airborne` only arms after
        // [`AIRBORNE_ARM_TICKS`], so even a ONE-FRAME report already implies ~150 ms of unbroken air.
        // Under hysteresis a short report is a short flight, never a blip.
        //
        // So compare the two flags directly: the per-wheel ones are instantaneous, and the fixture
        // breaks them constantly (a 1.4 m rise thrown away in a single 1 m step). The property worth
        // pinning is that `airborne` passes only a small fraction of those through, since each one it
        // passes ends in a touchdown edge that fires the landing one-shot. That is the failure commit
        // 3252335 closed on the impulse channel, arriving through this one.
        let w = world(glucose_terrain());
        let (mut raw, mut reported) = (0u32, 0u32);
        let (mut raw_in, mut rep_in) = (false, false);
        for _ in 0..900 {
            let s = w.step(1000.0 / 60.0, 1.0, 0.0).unwrap();
            if s.run != RunState::Running {
                break;
            }
            let off = !s.rear_contact && !s.front_contact;
            if off && !raw_in {
                raw += 1;
            }
            if s.airborne && !rep_in {
                reported += 1;
            }
            raw_in = off;
            rep_in = s.airborne;
        }
        assert!(raw > 20, "fixture no longer breaks contact often enough to be a test: {raw}");
        assert!(
            reported * 3 <= raw,
            "hysteresis is not suppressing: {reported} reported flights from {raw} contact losses"
        );
    }

    #[test]
    fn rests_on_flat_ground_without_sinking_or_floating() {
        let w = world(flat());
        let s = drive(&w, 600, 0.0, 0.0);
        let t = default_car_tuning();
        // Wheel centres sit exactly one radius above the ground.
        assert_close(s.rear_y, 10.0 + t.wheel_radius, 0.02, "rear ride height");
        assert_close(s.front_y, 10.0 + t.wheel_radius, 0.02, "front ride height");
    }

    // ── gravity does its job ────────────────────────────────────────────────────────────

    #[test]
    fn rolls_downhill_without_throttle() {
        // Falling to the right: −12 % grade.
        let w = world(ramp(-0.12));
        let s = drive(&w, 300, 0.0, 0.0); // 5 s
        assert!(s.distance_m > 3.0, "should have rolled downhill, distance = {}", s.distance_m);
        assert!(s.vx > 1.0, "should be gaining speed downhill, vx = {}", s.vx);
        assert!(s.rear_omega > 0.0, "wheels should be rolling forward, ω = {}", s.rear_omega);
        assert_eq!(s.throttle_applied, 0.0);
    }

    #[test]
    fn does_not_roll_uphill_on_its_own() {
        let w = world(ramp(0.12));
        let s = drive(&w, 300, 0.0, 0.0);
        assert!(s.vx <= 0.05, "must not creep uphill unpowered, vx = {}", s.vx);
    }

    // ── the whole point: it climbs ──────────────────────────────────────────────────────

    #[test]
    fn climbs_a_modest_slope_under_throttle() {
        // 25 % grade (~14°) — a routine glucose excursion.
        let w = world(ramp(0.25));
        let s = drive(&w, 420, 1.0, 0.0); // 7 s
        assert_eq!(s.run, RunState::Running, "a 25 % grade must not end the run");
        assert!(s.distance_m > 10.0, "should have climbed, distance = {}", s.distance_m);
        assert!(s.y > 10.0 + 2.0, "should have gained height, y = {}", s.y);
        assert!(s.throttle_applied > 0.0);
        assert!(s.rpm > IDLE_RPM);
    }

    #[test]
    fn climbs_a_steep_slope_under_throttle() {
        // 90 % grade (~42°): the steepest face the shipped tune takes at pinned throttle.
        let w = world(ramp(0.9));
        let s = drive(&w, 600, 1.0, 0.0);
        assert_ne!(s.run, RunState::Crashed, "a 42° face must not loop the car");
        assert!(s.rear_contact && s.front_contact, "should still be planted on the climb");
        assert!(s.distance_m > 5.0, "steep climb stalled, distance = {}", s.distance_m);
    }

    #[test]
    fn a_jagged_glucose_excursion_is_climbable_not_a_trap() {
        let w = world(glucose_terrain());
        let s = drive(&w, 1800, 1.0, 0.0); // 30 s
        assert!(
            s.distance_m > 60.0,
            "the tune must carry the car across a jagged trace, distance = {} (run {:?})",
            s.distance_m,
            s.run
        );
        assert!(s.roughness > 0.0, "a jagged trace must read as rough");
    }

    #[test]
    fn brake_reverses_and_holds() {
        let w = world(flat());
        let s = drive(&w, 240, 0.0, 1.0);
        assert!(s.vx < -0.5, "brake must back the car up on the flat, vx = {}", s.vx);
    }

    // ── run rules ───────────────────────────────────────────────────────────────────────

    #[test]
    fn reaching_the_right_edge_finishes() {
        // A short, gently downhill track the car can run out in a few seconds.
        let heights: Vec<f32> = (0..60).map(|i| 20.0 - 0.15 * i as f32).collect();
        let w = world(TerrainSpec { heights, dx: 1.0, world_height: 40.0 });
        let mut last = w.state().unwrap();
        for _ in 0..1200 {
            last = w.step(1000.0 / 60.0, 1.0, 0.0).unwrap();
            if last.run != RunState::Running {
                break;
            }
        }
        assert_eq!(last.run, RunState::Finished, "should have reached the finish, x = {}", last.x);
    }

    #[test]
    fn reset_at_the_right_edge_seats_the_car_short_of_the_finish() {
        // Tapping the far right of the BG panel is the natural "start me at now" gesture, and it used
        // to seat the car at or past `terrain.length` — which IS the finish — so the run ended on its
        // first step, having travelled nothing.
        let w = world(flat());
        let len = w.track_length().unwrap();
        for tap in [len, len * 2.0, f32::MAX] {
            let seated = w.reset_at(tap).expect("reset_at must accept any x");
            assert!(seated.x < len, "seated at {} but the finish is {len}", seated.x);
            assert_eq!(seated.run, RunState::Running, "a fresh seat must not be Finished");
            let after = w.step(1000.0 / 60.0, 0.0, 0.0).unwrap();
            assert_eq!(after.run, RunState::Running, "must survive its first simulated step");
        }
    }

    #[test]
    fn reset_at_a_bottomless_tap_falls_back_to_landable_ground() {
        // Solid for the first 40 m, gap for the rest: a tap into the gap has no landable run after
        // it, so the seat falls back to real ground rather than dropping the car into the chasm.
        let mut heights = vec![12.0f32; 200];
        for h in heights.iter_mut().skip(40) {
            *h = -1.0; // gap marker
        }
        let w = world(TerrainSpec { heights, dx: 1.0, world_height: 30.0 });
        let seated = w.reset_at(150.0).expect("reset_at must accept a tap over a gap");
        assert!(seated.x < 40.0, "must seat on the solid prefix, got x = {}", seated.x);
        assert_eq!(seated.run, RunState::Running);
    }

    #[test]
    fn a_fresh_seat_has_travelled_nothing_on_any_grade() {
        // `distance_m` is measured from the start line, and the start line is the WHEEL mid-point.
        // Seeding the counter from the chassis centre of mass instead put it ahead of the line on a
        // descent (1.59 m before the car had moved) and behind it on a climb, where the `.max(0.0)`
        // ate the same distance silently for the whole run.
        for rise in [-1.0f32, -0.25, 0.0, 0.25, 1.0] {
            let heights: Vec<f32> = (0..2001).map(|i| 2_000.0 + rise * i as f32).collect();
            let w = world(TerrainSpec { heights, dx: 1.0, world_height: 6_000.0 });
            assert_eq!(w.reset().unwrap().distance_m, 0.0, "grade {rise}");
        }
    }

    #[test]
    fn a_seat_over_a_chasm_reports_itself_airborne() {
        // No landable run anywhere, so the seat falls back to the tap and the car is in free fall.
        // The renderer, the haptic bed and the audio all read this frame before any physics has run.
        let w = world(TerrainSpec { heights: vec![-1.0; 200], dx: 1.0, world_height: 20.0 });
        let s = w.state().unwrap();
        assert!(s.airborne, "seated over a chasm but reporting grounded");
        assert!(!s.rear_contact && !s.front_contact);
    }

    #[test]
    fn rejects_a_dx_whose_reciprocal_overflows() {
        // Finite, positive, and gives a finite track length — but `1/dx` is +inf, so every derived
        // signal that scales by it reads `0 · inf = NaN`, and `clamp` does not sanitise a NaN self.
        let spec =
            TerrainSpec { heights: vec![10.0; 401], dx: f32::from_bits(1), world_height: 40.0 };
        assert!(GameWorld::new(spec, default_car_tuning()).is_err());
        // And the derived signal is total anyway, for a `dx` that slips past any future guard.
        let t = Terrain {
            heights: vec![10.0; 401],
            dx: f32::MIN_POSITIVE,
            inv_dx: f32::INFINITY,
            length: 1.0,
            kill_y: -40.0,
        };
        assert_eq!(t.roughness(5.0), 0.0);
    }

    #[test]
    fn the_run_off_cannot_be_driven_off() {
        // Released on a steep start line the car rolls BACKWARDS off x = 0, and the only thing
        // slowing it on the flat apron is the air-drag stand-in. An apron as long as the track — what
        // this used to be — is crossed in seconds on a short one, after which the car falls to the
        // kill plane and the run reads Crashed with nothing on screen to explain it.
        let heights: Vec<f32> = (0..60).map(|i| 10.0 + 0.9 * i as f32).collect();
        let w = world(TerrainSpec { heights, dx: 1.0, world_height: 400.0 });
        let mut last = w.state().unwrap();
        for _ in 0..3600 {
            last = w.step(1000.0 / 60.0, 0.0, 0.0).unwrap();
            if last.run != RunState::Running {
                break;
            }
        }
        assert_eq!(last.run, RunState::Running, "rolled off the world at x = {}", last.x);
        assert!(last.x < 0.0, "should have rolled back past the start line, x = {}", last.x);
    }

    #[test]
    fn a_chasm_ends_the_run() {
        // Solid for 110 m, then nothing — a CGM dropout wide enough that no launch speed
        // clears it before the fall reaches the kill plane.
        let mut heights = vec![12.0f32; 300];
        for h in heights.iter_mut().skip(110) {
            *h = -1.0; // gap marker
        }
        let w = world(TerrainSpec { heights, dx: 1.0, world_height: 30.0 });
        let mut last = w.state().unwrap();
        for _ in 0..3000 {
            last = w.step(1000.0 / 60.0, 1.0, 0.0).unwrap();
            if last.run != RunState::Running {
                break;
            }
        }
        assert_eq!(last.run, RunState::Crashed, "falling into the gap must end the run");
        assert!(last.y < 0.0, "should have fallen well below the floor, y = {}", last.y);
    }

    #[test]
    fn inverted_landing_crashes() {
        // Drop the car in upside-down: the head hits ground past the tilt threshold.
        let w = world(flat());
        {
            let mut sim = w.inner.lock().unwrap();
            sim.place_for_test(PI, 12.0, -6.0); // fully inverted, dropping
        }
        let mut last = w.state().unwrap();
        for _ in 0..600 {
            last = w.step(1000.0 / 60.0, 0.0, 0.0).unwrap();
            if last.run != RunState::Running {
                break;
            }
        }
        assert_eq!(last.run, RunState::Crashed, "an inverted landing must crash");
    }

    #[test]
    fn upright_landing_does_not_crash() {
        // The same drop, right way up, must NOT trip the rollover rule.
        let w = world(flat());
        {
            let mut sim = w.inner.lock().unwrap();
            sim.place_for_test(0.0, 14.0, -8.0);
        }
        let s = drive(&w, 600, 0.0, 0.0);
        assert_eq!(s.run, RunState::Running, "a hard but upright landing is not a crash");
    }

    #[test]
    fn a_steep_hill_is_not_mistaken_for_a_rollover() {
        // Sitting on a 100 % grade pitches the chassis 45° — well inside `crash_tilt_rad`,
        // so terrain pitch alone must never read as a rollover.
        let w = world(ramp(1.0));
        let s = drive(&w, 600, 0.0, 0.0);
        assert_ne!(s.run, RunState::Crashed, "a 45° hill must not read as a rollover");
    }

    #[test]
    fn a_landing_registers_an_impact_impulse() {
        let w = world(flat());
        {
            let mut sim = w.inner.lock().unwrap();
            sim.place_for_test(0.0, 16.0, -10.0);
        }
        let mut peak = 0.0f32;
        for _ in 0..300 {
            let s = w.step(1000.0 / 60.0, 0.0, 0.0).unwrap();
            peak = peak.max(s.impact_impulse);
        }
        assert!(peak > 100.0, "a 10 m/s landing must register, peak impulse = {peak}");
    }

    #[test]
    fn airborne_flag_tracks_the_wheels() {
        let w = world(flat());
        assert!(!w.state().unwrap().airborne, "starts on the ground");
        {
            let mut sim = w.inner.lock().unwrap();
            sim.place_for_test(0.0, 30.0, 4.0);
        }
        let s = w.step(1000.0 / 60.0, 0.0, 0.0).unwrap();
        assert!(s.airborne, "lofted 20 m up, both wheels must be free");
        assert!(!s.rear_contact && !s.front_contact);
    }

    // ── terminal runs ───────────────────────────────────────────────────────────────────

    /// Inherited from the deleted `running_dry_ends_the_run_and_cuts_the_throttle`. Fuel is gone, but
    /// the property that test ALSO carried is not about fuel: once a run is terminal the solver freezes
    /// and releases the throttle, whatever ended it. Re-pinned against the finish line.
    #[test]
    fn a_terminal_run_freezes_and_releases_the_throttle() {
        let heights: Vec<f32> = (0..60).map(|i| 20.0 - 0.15 * i as f32).collect();
        let w = world(TerrainSpec { heights, dx: 1.0, world_height: 40.0 });
        let mut last = w.state().unwrap();
        for _ in 0..1200 {
            last = w.step(1000.0 / 60.0, 1.0, 0.0).unwrap();
            if last.run != RunState::Running {
                break;
            }
        }
        assert_eq!(last.run, RunState::Finished, "should have reached the finish, x = {}", last.x);
        let after = w.step(1000.0 / 60.0, 1.0, 0.0).unwrap();
        assert_eq!(after.run, RunState::Finished);
        assert_eq!(after.throttle_applied, 0.0);
        assert_eq!(after.x, last.x);
        assert_eq!(after.elapsed_s, last.elapsed_s);
    }

    #[test]
    fn reset_puts_the_car_back() {
        let w = world(glucose_terrain());
        let driven = drive(&w, 600, 1.0, 0.0);
        assert!(driven.distance_m > 0.0);
        let fresh = w.reset().unwrap();
        assert_eq!(fresh.run, RunState::Running);
        assert_eq!(fresh.distance_m, 0.0);
        assert_eq!(fresh.elapsed_s, 0.0);
        assert_eq!(fresh.rear_omega, 0.0);
        assert_eq!(fresh.vx, 0.0);
        // Placement aligns with the local grade, so the pitch is the start line's, not zero.
        let flat_start = world(flat()).reset().unwrap();
        assert_eq!(flat_start.angle, 0.0);
        // Re-driving from a reset must retrace the first run exactly.
        let again = drive(&w, 600, 1.0, 0.0);
        assert_eq!(again, driven);
    }

    // ── determinism ─────────────────────────────────────────────────────────────────────

    #[test]
    fn identical_inputs_give_identical_states() {
        // A ragged but fixed control script, including uneven frame deltas.
        let script: Vec<(f32, f32, f32)> = (0..900u32)
            .map(|i| {
                let dt = 1000.0 / 60.0 + (i % 7) as f32 * 0.9;
                let thr = if (i / 37) % 2 == 0 { 1.0 } else { 0.15 };
                let brk = if (i / 121) % 5 == 0 { 0.8 } else { 0.0 };
                (dt, thr, brk)
            })
            .collect();
        let run = |script: &[(f32, f32, f32)]| {
            let w = world(glucose_terrain());
            let mut out = Vec::with_capacity(script.len());
            for &(dt, thr, brk) in script {
                out.push(w.step(dt, thr, brk).unwrap());
            }
            out
        };
        let a = run(&script);
        let b = run(&script);
        assert_eq!(a.len(), b.len());
        for (i, (x, y)) in a.iter().zip(b.iter()).enumerate() {
            assert_eq!(x, y, "frame {i} diverged: {x:?} vs {y:?}");
        }
    }

    #[test]
    fn a_settled_seat_reports_no_interpolation_artefact() {
        for w in [world(flat()), world(glucose_terrain())] {
            let seated = w.reset().unwrap();
            let mirrored = {
                let s = w.inner.lock().unwrap();
                assert_eq!(s.accumulator, 0.0, "a reset must leave nothing banked");
                s.pose()
            };
            assert_eq!(seated.x, mirrored.x);
            assert_eq!(seated.y, mirrored.y);
            assert_eq!(seated.angle, mirrored.ang);
            assert_eq!(seated.rear_x, mirrored.wheels[REAR].cx);
            assert_eq!(seated.rear_y, mirrored.wheels[REAR].cy);
            assert_eq!(seated.front_x, mirrored.wheels[FRONT].cx);
            assert_eq!(seated.front_y, mirrored.wheels[FRONT].cy);
            assert_eq!(w.state().unwrap(), seated, "state() must not drift from the seat");

            // Driving away and re-seating must not blend the new seat against the old run.
            drive(&w, 120, 1.0, 0.0);
            let again = w.reset_at(0.0).unwrap();
            let mirrored = w.inner.lock().unwrap().pose();
            assert_eq!((again.x, again.y, again.angle), (mirrored.x, mirrored.y, mirrored.ang));
        }
    }

    #[test]
    fn a_partial_tick_reports_a_pose_between_the_two_it_brackets() {
        let w = world(ramp(-0.25));
        drive(&w, 240, 1.0, 0.0); // get it moving; leaves the accumulator empty
        // One and a half ticks: consumes one, banks half of the next.
        let s = w.step(1000.0 * FIXED_DT * 1.5, 1.0, 0.0).unwrap();
        let (prev, cur, alpha, vx) = {
            let g = w.inner.lock().unwrap();
            (g.prev, g.pose(), g.accumulator / FIXED_DT, g.vx)
        };
        assert!((0.4..0.6).contains(&alpha), "half a tick should be banked, alpha = {alpha}");
        assert!(prev.x != cur.x && prev.y != cur.y, "the two ticks must actually differ");

        let between = |got: f32, a: f32, b: f32, what: &str| {
            let (lo, hi) = if a < b { (a, b) } else { (b, a) };
            assert!(got > lo && got < hi, "{what}: {got} not strictly inside ({lo}, {hi})");
        };
        between(s.x, prev.x, cur.x, "chassis x");
        between(s.y, prev.y, cur.y, "chassis y");
        between(s.rear_x, prev.wheels[REAR].cx, cur.wheels[REAR].cx, "rear x");
        between(s.front_x, prev.wheels[FRONT].cx, cur.wheels[FRONT].cx, "front x");
        // Only the pose is blended.
        assert_eq!(s.vx, vx, "velocity must be the current tick's, unblended");
    }

    #[test]
    fn the_shortest_arc_carries_the_wheel_across_the_wrap_seam() {
        // A wheel a hair short of a full turn, blended toward one a hair past it: the long way
        // round spins it backwards through the whole revolution.
        let got = lerp_angle(TWO_PI - 0.05, -TWO_PI + 0.05, 0.5) % TWO_PI;
        assert_close(wrap_pi(got), 0.0, 1e-5, "seam crossing");
        assert_close(lerp_angle(3.0, -3.0, 0.0), 3.0, 1e-6, "t = 0 is the previous angle");
        assert_close(wrap_pi(lerp_angle(3.0, -3.0, 1.0)), -3.0, 1e-5, "t = 1 is the current one");
    }

    #[test]
    fn the_accumulator_makes_a_long_frame_bounded() {
        // One absurd frame must not teleport the car: the substep cap bounds the advance.
        let a = world(flat());
        let sa = a.step(10_000.0, 1.0, 0.0).unwrap();
        assert!(sa.elapsed_s <= MAX_SUBSTEPS as f32 * FIXED_DT + 1e-6, "elapsed = {}", sa.elapsed_s);
        assert!(sa.x.is_finite() && sa.y.is_finite());
    }

    // ── hostile input ───────────────────────────────────────────────────────────────────

    #[test]
    fn rejects_a_degenerate_terrain() {
        let t = default_car_tuning();
        assert!(GameWorld::new(TerrainSpec { heights: vec![], dx: 1.0, world_height: 10.0 }, t).is_err());
        assert!(GameWorld::new(TerrainSpec { heights: vec![1.0], dx: 1.0, world_height: 10.0 }, t).is_err());
        assert!(GameWorld::new(TerrainSpec { heights: vec![1.0; 4], dx: 0.0, world_height: 10.0 }, t).is_err());
        assert!(GameWorld::new(TerrainSpec { heights: vec![1.0; 4], dx: f32::NAN, world_height: 10.0 }, t).is_err());
        assert!(GameWorld::new(TerrainSpec { heights: vec![1.0; 4], dx: 1.0, world_height: 0.0 }, t).is_err());
        assert!(GameWorld::new(
            TerrainSpec { heights: vec![1.0; MAX_TERRAIN_SAMPLES + 1], dx: 1.0, world_height: 10.0 },
            t
        )
        .is_err());
    }

    #[test]
    fn rejects_a_degenerate_tuning() {
        for mutate in [
            (|t: &mut CarTuning| t.chassis_mass = 0.0) as fn(&mut CarTuning),
            |t: &mut CarTuning| t.chassis_mass = f32::NAN,
            |t: &mut CarTuning| t.wheel_radius = -1.0,
            |t: &mut CarTuning| t.suspension_rest = 0.0,
            |t: &mut CarTuning| t.suspension_rest = 10.0, // > travel
            |t: &mut CarTuning| t.traction_relax = 0.0,
            |t: &mut CarTuning| t.traction_relax = 1.5,
            |t: &mut CarTuning| t.crash_tilt_rad = 0.0,
            |t: &mut CarTuning| t.crash_tilt_rad = 4.0,
            |t: &mut CarTuning| t.grip = f32::INFINITY,
            |t: &mut CarTuning| t.gravity = -9.0,
        ] {
            let mut t = default_car_tuning();
            mutate(&mut t);
            assert!(GameWorld::new(flat(), t).is_err(), "must reject tuning {t:?}");
        }
    }

    #[test]
    fn a_tuning_that_overflows_the_normal_load_does_not_abort() {
        // Every field here is finite, positive and correctly ordered, so `validate_tuning` (which
        // bounds by sign, not magnitude) accepts it — yet MAX_SUSPENSION_G · mass · gravity
        // overflows to +inf, the suspension clamp no longer caps anything, and `grip · normal` is
        // 0 · inf = NaN. A NaN bound makes `f32::clamp` panic, and `panic = "abort"` in release
        // would take the app down from inside the frame loop. `createGameWorld` takes a
        // caller-built tuning, so this is reachable across the seam.
        let mut t = default_car_tuning();
        t.chassis_mass = 1e20;
        t.gravity = 1e20;
        t.suspension_stiffness = 3e38;
        t.suspension_rest = 2.0;
        t.suspension_travel = 2.0;
        t.grip = 0.0;
        let w = GameWorld::new(flat(), t).expect("the tuning passes validation");
        for _ in 0..120 {
            let _ = w.step(1000.0 / 60.0, 1.0, 0.0).unwrap();
        }
    }

    #[test]
    fn a_terrain_of_pure_gap_does_not_panic() {
        // No solid ground anywhere: the car is placed at the origin and falls to the kill
        // plane. The invariant is that nothing panics and the run terminates.
        let w = world(TerrainSpec { heights: vec![-1.0; 200], dx: 1.0, world_height: 20.0 });
        let mut last = w.state().unwrap();
        for _ in 0..2000 {
            last = w.step(1000.0 / 60.0, 1.0, 0.5).unwrap();
            if last.run != RunState::Running {
                break;
            }
        }
        assert_eq!(last.run, RunState::Crashed);
    }

    #[test]
    fn hostile_controls_and_dt_never_panic() {
        let w = world(glucose_terrain());
        let hostile = [
            f32::NAN,
            f32::INFINITY,
            f32::NEG_INFINITY,
            -1e30,
            1e30,
            -1.0,
            0.0,
            1e-30,
            1e6,
            16.666,
        ];
        for &dt in &hostile {
            for &thr in &hostile {
                for &brk in &hostile {
                    let s = w.step(dt, thr, brk).unwrap();
                    assert!(s.x.is_finite(), "x went non-finite on dt={dt} thr={thr} brk={brk}");
                    assert!(s.y.is_finite());
                    assert!(s.angle.is_finite());
                    assert!(s.rpm.is_finite());
                    assert!((0.0..=1.0).contains(&s.throttle_applied));
                    assert!((0.0..=1.0).contains(&s.roughness));
                }
            }
            w.reset().unwrap();
        }
    }

    #[test]
    fn fuzz_never_panics() {
        // Deterministic xorshift over BOTH the terrain and the control stream. This module
        // is reached from a 60 Hz frame loop under `panic = "abort"`, so the invariant is
        // simply: nothing here can ever abort the process.
        // Free fns rather than closures: two closures over the same `state` would collide.
        fn xs(state: &mut u64) -> u64 {
            *state ^= *state << 13;
            *state ^= *state >> 7;
            *state ^= *state << 17;
            *state
        }
        /// A uniformly random 32-bit pattern reinterpreted as f32 — NaNs, infinities,
        /// subnormals and absurd magnitudes all appear.
        fn xf(state: &mut u64) -> f32 {
            f32::from_bits((xs(state) >> 32) as u32)
        }

        let st = &mut 0x0BAD_F00D_DEAD_BEEFu64;
        for _ in 0..64 {
            let n = (xs(st) % 300) as usize + 2;
            let heights: Vec<f32> = (0..n)
                .map(|_| match xs(st) % 8 {
                    0 => xf(st),                        // anything, including NaN/inf
                    1 => -1.0,                          // gap
                    _ => (xs(st) % 4000) as f32 * 0.01, // 0..40 m of ground
                })
                .collect();
            let dx = match xs(st) % 4 {
                0 => xf(st),
                1 => 0.0,
                _ => 0.25 + (xs(st) % 400) as f32 * 0.01,
            };
            let spec = TerrainSpec { heights, dx, world_height: 30.0 };
            let w = match GameWorld::new(spec, default_car_tuning()) {
                Ok(w) => w,
                Err(CoreError::Decode { .. }) => continue,
                Err(e) => panic!("unexpected error variant: {e:?}"),
            };
            for _ in 0..400 {
                let dt = match xs(st) % 5 {
                    0 => xf(st),
                    1 => 0.0,
                    2 => 1e9,
                    _ => (xs(st) % 60) as f32,
                };
                let (thr, brk) = (xf(st), xf(st));
                let s = w.step(dt, thr, brk).unwrap();
                assert!(s.x.is_finite() && s.y.is_finite() && s.angle.is_finite());
                assert!(s.distance_m.is_finite() && s.distance_m >= 0.0);
                assert!(s.impact_impulse.is_finite() && s.impact_impulse >= 0.0);
                if s.run != RunState::Running {
                    break;
                }
            }
        }
    }

    // ── regression pin (see the module header's honesty caveat) ─────────────────────────

    #[test]
    fn golden_trace_is_pinned() {
        let g = golden();
        let spec = {
            let t = &g["terrain"];
            TerrainSpec {
                heights: t["heights"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .map(|v| v.as_f64().unwrap() as f32)
                    .collect(),
                dx: t["dx"].as_f64().unwrap() as f32,
                world_height: t["world_height"].as_f64().unwrap() as f32,
            }
        };
        let w = GameWorld::new(spec, default_car_tuning()).unwrap();
        let tol = g["tolerance"].as_f64().unwrap() as f32;
        let mut frame = 0u32;
        for entry in g["frames"].as_array().unwrap() {
            let at = entry["frame"].as_u64().unwrap() as u32;
            while frame < at {
                w.step(1000.0 / 60.0, 1.0, 0.0).unwrap();
                frame += 1;
            }
            let s = w.state().unwrap();
            let want = |k: &str| entry[k].as_f64().unwrap() as f32;
            assert_close(s.x, want("x"), tol, &format!("frame {at} x"));
            assert_close(s.y, want("y"), tol, &format!("frame {at} y"));
            assert_close(s.angle, want("angle"), tol, &format!("frame {at} angle"));
            assert_close(s.vx, want("vx"), tol, &format!("frame {at} vx"));
            assert_close(s.rear_omega, want("rear_omega"), tol, &format!("frame {at} rear_omega"));
            assert_close(s.distance_m, want("distance_m"), tol, &format!("frame {at} distance_m"));
        }
    }

    /// Diagnostic, not a gate: how much of a full-throttle run over the real terrain shape is spent
    /// airborne, and how hard it accelerates. Run with
    ///   `cargo test -p t1dm-core airtime_probe -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn airtime_probe() {
        let w = GameWorld::new(glucose_terrain(), default_car_tuning()).unwrap();
        let (mut air, mut n, mut peak) = (0u32, 0u32, 0.0f32);
        let mut at_1s = 0.0f32;
        for i in 0..900 {
            let s = w.step(1000.0 / 60.0, 1.0, 0.0).unwrap();
            if !s.rear_contact && !s.front_contact { air += 1; }
            if s.vx > peak { peak = s.vx; }
            if i == 59 { at_1s = s.vx; }
            n += 1;
        }
        println!(
            "airborne {}/{} frames ({:.1}%)  v@1s={:.1} m/s  peak={:.1} m/s",
            air, n, 100.0 * air as f32 / n as f32, at_1s, peak
        );
    }


    #[test]
    fn pedals_rotate_the_car_only_while_airborne() {
        // A ramp that ends in a cliff: drive off it, then hold throttle in the air.
        let mut heights: Vec<f32> = (0..300).map(|i| 10.0 + 0.05 * i as f32).collect();
        heights.extend((0..400).map(|_| f32::NAN)); // the void past the lip
        let spec = TerrainSpec { heights, dx: 1.0, world_height: 400.0 };
        let w = GameWorld::new(spec, default_car_tuning()).unwrap();
        // Reach the lip.
        let mut s = w.state().unwrap();
        for _ in 0..600 {
            s = w.step(1000.0 / 60.0, 1.0, 0.0).unwrap();
            if s.airborne { break; }
        }
        assert!(s.airborne, "should have driven off the lip");
        let a0 = s.angle;
        for _ in 0..30 { s = w.step(1000.0 / 60.0, 1.0, 0.0).unwrap(); }
        assert!(s.angle > a0, "throttle should rotate the nose up in the air: {} -> {}", a0, s.angle);

        // Planted, the same input must NOT be an attitude control.
        let flat = TerrainSpec { heights: vec![10.0; 400], dx: 1.0, world_height: 400.0 };
        let g = GameWorld::new(flat, default_car_tuning()).unwrap();
        let mut p = g.state().unwrap();
        for _ in 0..30 { p = g.step(1000.0 / 60.0, 1.0, 0.0).unwrap(); }
        assert!(p.angle.abs() < 0.25, "planted car should not pitch on throttle: {}", p.angle);
    }

    /// Diagnostic: how solidly the front wheel stays down on the steepest face the tune must hold, as
    /// a function of torque. `climbs_a_steep_slope_under_throttle` samples contact at ONE frame, which
    /// near the traction ceiling is a coin toss on a wheel that is chattering — so the number that
    /// actually decides the torque is the FRACTION of the climb spent on both wheels, and this is
    /// where it is read off.
    ///   `cargo test -p t1dm-core steep_traction_probe -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn steep_traction_probe() {
        let weight = (D_MASS + 2.0 * D_WHEEL_MASS) * D_GRAVITY;
        for tau in [70_000.0f32, 72_000.0, 74_000.0, 75_000.0, 76_000.0, 78_000.0] {
            let tune = CarTuning { motor_torque: tau, brake_torque: tau, ..default_car_tuning() };
            let w = GameWorld::new(ramp(0.9), tune).expect("world must build");
            let (mut planted, mut frames) = (0u32, 0u32);
            let mut last = w.state().unwrap();
            for _ in 0..600 {
                last = w.step(1000.0 / 60.0, 1.0, 0.0).unwrap();
                if last.run != RunState::Running {
                    break;
                }
                frames += 1;
                if last.rear_contact && last.front_contact {
                    planted += 1;
                }
            }
            println!(
                "τ={tau:>6.0}  T/W={:.2}  planted {:>5.1}%  climbed {:>6.1} m  run {:?}",
                tau / D_WHEEL_RADIUS / weight,
                100.0 * planted as f32 / frames.max(1) as f32,
                last.distance_m,
                last.run,
            );
        }
    }

    /// Diagnostic: airtime over a REALISTIC track — 5-minute readings joined linearly, the shape the
    /// panel actually draws — rather than the deliberately jagged `glucose_terrain` fixture. Reports
    /// peak clearance both in world metres and as a FRACTION OF THE WORLD HEIGHT, which is what
    /// decides whether a jump is visible: the panel maps the whole world height onto the plot, so a
    /// hop of 0.5 % of it is a few pixels however impressive the metres sound.
    ///   `cargo test -p t1dm-core smooth_airtime_probe -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn smooth_airtime_probe() {
        let bg = [
            118.0f32, 121.0, 119.0, 124.0, 133.0, 148.0, 166.0, 181.0, 189.0, 186.0, 178.0, 168.0,
            159.0, 152.0, 147.0, 141.0, 138.0, 134.0, 131.0, 129.0, 126.0, 124.0, 121.0, 119.0,
        ];
        let span = 230.0f32;
        let mpm = 3.0f32;
        let per_reading = 5.0 * mpm;
        for world_h in [22.0f32, 45.0, 70.0, 100.0, 140.0, 190.0] {
            let n = ((bg.len() - 1) as f32 * per_reading) as usize;
            let heights: Vec<f32> = (0..=n)
                .map(|i| {
                    let t = i as f32 / per_reading;
                    let k = (t.floor() as usize).min(bg.len() - 2);
                    let f = t - k as f32;
                    let v = bg[k] * (1.0 - f) + bg[k + 1] * f;
                    (v - 40.0) / span * world_h
                })
                .collect();
            let spec = TerrainSpec { heights, dx: 1.0, world_height: world_h };
            let w = GameWorld::new(spec, default_car_tuning()).unwrap();
            let (mut air, mut peak_clear, mut peak_v, mut crashed) = (0u32, 0.0f32, 0.0f32, false);
            for _ in 0..900 {
                let s = w.step(1000.0 / 60.0, 1.0, 0.0).unwrap();
                if s.vx > peak_v { peak_v = s.vx; }
                if s.run == RunState::Crashed { crashed = true; }
                if !s.rear_contact && !s.front_contact {
                    air += 1;
                    let t = s.x / per_reading;
                    let k = (t.floor().max(0.0) as usize).min(bg.len() - 2);
                    let f = (t - k as f32).clamp(0.0, 1.0);
                    let v = bg[k] * (1.0 - f) + bg[k + 1] * f;
                    let clear = s.y - (v - 40.0) / span * world_h;
                    if clear > peak_clear { peak_clear = clear; }
                }
            }
            // Routine drift slope at this exaggeration, for context.
            let drift_deg = ((3.0 * world_h / span) / mpm).atan().to_degrees();
            println!(
                "H={:5.0}  airborne {:3}/900 ({:4.1}%)  peak clear {:5.2} m = {:4.2}% of H  \
                 3 mg/dL/min slope {:4.1}°  peak v {:5.1}  crashed={}",
                world_h, air, 100.0 * air as f32 / 900.0, peak_clear,
                100.0 * peak_clear / world_h, drift_deg, peak_v, crashed
            );
        }
    }

    /// Regenerate `testdata/game_golden.json` on stdout. `#[ignore]`d: the fixture is a
    /// deliberate pin, so replacing it is a manual act, never a side effect of `cargo test`.
    ///   `cargo test -p t1dm-core emit_game_golden -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn emit_game_golden() {
        let spec = glucose_terrain();
        let w = GameWorld::new(spec.clone(), default_car_tuning()).unwrap();
        let mut rows = String::new();
        let mut frame = 0u32;
        for at in [0u32, 1, 15, 60, 150, 300, 600, 900, 1200, 1800] {
            while frame < at {
                w.step(1000.0 / 60.0, 1.0, 0.0).unwrap();
                frame += 1;
            }
            let s = w.state().unwrap();
            if !rows.is_empty() {
                rows.push_str(",\n");
            }
            rows.push_str(&format!(
                "  {{ \"frame\": {at}, \"x\": {:?}, \"y\": {:?}, \"angle\": {:?}, \"vx\": {:?}, \"rear_omega\": {:?}, \"distance_m\": {:?} }}",
                s.x, s.y, s.angle, s.vx, s.rear_omega, s.distance_m
            ));
        }
        let heights: Vec<String> = spec.heights.iter().map(|h| format!("{h:?}")).collect();
        println!(
            "{{\n \"_note\": \"Regression pin for t1dm-core::game, generated by the #[ignore]d emit_game_golden test. NOT an external oracle — see the module header.\",\n \"tolerance\": 0.001,\n \"terrain\": {{ \"dx\": {:?}, \"world_height\": {:?}, \"heights\": [{}] }},\n \"frames\": [\n{}\n ]\n}}",
            spec.dx,
            spec.world_height,
            heights.join(", "),
            rows
        );
    }
}

