//! Hill-climb car physics on a glucose-trace terrain; cosmetic, never touches §3.6.

use std::sync::{Arc, Mutex};

use rapier2d::prelude::*;

use crate::terrain::{
    add_obstacles, build_ground, dec, internal, lerp, lerp_angle, normal_impulse, place_obstacles,
    sane01, solver_params, touching, validate_obstacles, validate_terrain, wrap_pi, Block, Obstacle,
    Terrain, TerrainSpec, FIXED_DT, IMPULSE_WARMSTART_BIAS, MAX_FRAME_DT_S, MAX_SPEED, MAX_SUBSTEPS,
    PI, TWO_PI,
};
use crate::CoreError;

/// rad/s — ~5 flips per second.
const MAX_ANGULAR: f32 = 32.0;
/// validate_tuning bounds by sign not magnitude; caps an absurd torque from overflowing to +inf.
const MAX_MOTOR_GAIN: f32 = 1.0e12;

/// Gain multiple saturating standstill torque; above 1, full torque till near the limiter.
const DRIVE_MOTOR_GAIN: f32 = 4.0;

/// Bodywork-scraping friction; combined with terrain's by Min, so it stays low on a grippy surface.
const CHASSIS_FRICTION: f32 = 0.35;

/// Air-drag stand-in (1/s).
const LINEAR_DAMPING: f32 = 0.02;
/// 1/s. Low enough that a launch still flips.
const ANGULAR_DAMPING: f32 = 0.35;
/// Grounded wheel bleed (1/s).
const WHEEL_SPIN_DAMPING: f32 = 0.6;
/// Airborne wheel bleed (1/s); only bearing slows it, contact-patch rate halts it mid-jump.
const WHEEL_SPIN_DAMPING_AIR: f32 = 0.06;
/// Contact-free ticks before airborne fires -- 16 = 133 ms; read as edge, ties to GROUNDED.
const AIRBORNE_ARM_TICKS: u32 = 16;
/// Airborne pedal pitch accel (rad/s2), a game mechanic; nulls 0.8 rad/s nose-down in 0.14s.
const AIR_PITCH_ACCEL: f32 = 6.0;

/// Wheel attach points sit at ±this fraction of the chassis half-length.
const WHEELBASE_FRAC: f32 = 0.90;
/// The driver's head, in chassis half-lengths behind centre and metres above the roof.
const HEAD_BACK_FRAC: f32 = 0.22;
const HEAD_ABOVE_ROOF: f32 = 0.42;
/// `wheels[REAR]` is the driven one; the FFI record's `rear_*` fields read from it.
const REAR: usize = 0;
const FRONT: usize = 1;

const IDLE_RPM: f32 = 800.0;
/// Wheel rad/s → RPM through a single notional reduction (60/2π × gear).
const RPM_PER_RAD_S: f32 = 9.5493 * 7.0;
/// Revving a stalled wheel still makes noise.
const THROTTLE_RPM_BUMP: f32 = 900.0;
const MAX_RPM: f32 = 9_000.0;

/// Tune sized to the world: 3 m/min terrain, so a 5-min reading is 15 m; anti-wheelie L/h = 2.25.
const D_MASS: f32 = 450.0;
/// Chassis half-length (m); the wheelbase is the whole anti-wheelie budget, a stubby car loops.
const D_HALF_LEN: f32 = 14.0;
/// Chassis half-height (m), sets h~5.84; RAISING IS A TRAP -- wheelie self-feeds, keep L/h large.
const D_HALF_HEIGHT: f32 = 1.6;
/// BIG wheels bridge a jagged trace; a small one drops into every notch narrower (see D_GRIP).
const D_WHEEL_RADIUS: f32 = 3.6;
const D_WHEEL_MASS: f32 = 26.0;
/// Long travel, short free length: sits under centre of mass; every cm of free length is wheelie.
const D_SUSP_REST: f32 = 1.6;
const D_SUSP_TRAVEL: f32 = 4.0;
/// Stiffness N/m, damping N*s/m (ForceBased); two-sided sag m*g/2k=0.96m is the DROOP budget.
const D_SUSP_STIFFNESS: f32 = 8_000.0;
const D_SUSP_DAMPING: f32 = 1_340.0;
/// Rear torque N*m, thrust/weight 1.17; CLIFF -- 75000 climbs, 76000 backflips (42deg ramp).
const D_MOTOR_TORQUE: f32 = 72_000.0;
const D_BRAKE_TORQUE: f32 = 72_000.0;
/// Rev limiter (rad/s) = ~101 m/s; velocity motor, so it IS top speed. Costs airtime: hang = 2v/g.
const D_MAX_WHEEL_OMEGA: f32 = 28.0;
/// Fenced both sides: below ~1.82 traction-limited, above ~2.35 a notch WELDS the wheel; 2.0 fits.
const D_GRIP: f32 = 2.0;
/// VESTIGIAL: grip alone sets the tyre; field stays for the frozen FFI record, bound to (0,1].
const D_TRACTION_RELAX: f32 = 0.9;
/// Exaggerated: a_max=g*L/h=76 m/s2 at the wheelie point; raising g costs jump hang time (2v/g).
const D_GRAVITY: f32 = 34.0;
/// rad. ~80°, steeper than any climbable slope, so a hill cannot masquerade as a crash.
const D_CRASH_TILT: f32 = 1.4;

/// Non-finite ⇒ 0.
#[inline]
fn gain(v: f32, hi: f32) -> f32 {
    if v.is_finite() {
        v.clamp(0.0, hi)
    } else {
        0.0
    }
}

/// Crashed/Finished are TERMINAL: step re-returns frozen state; reset is the only way back.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum RunState {
    Running,
    /// Rollover, or a fall past the kill plane.
    Crashed,
    /// Reached the right-hand edge of the heightfield.
    Finished,
}

/// The `D_*` constants [`default_car_tuning`] is built from carry the provenance of each number.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct CarTuning {
    pub chassis_mass: f32,
    pub chassis_half_len: f32,
    pub chassis_half_height: f32,
    pub wheel_radius: f32,
    pub wheel_mass: f32,
    /// Free length: attach point → wheel centre, unloaded.
    pub suspension_rest: f32,
    /// Maximum extension; beyond it the wheel has left the ground.
    pub suspension_travel: f32,
    pub suspension_stiffness: f32,
    pub suspension_damping: f32,
    /// N·m at full throttle.
    pub motor_torque: f32,
    pub brake_torque: f32,
    /// Rev limiter (rad/s): full torque until within `1/DRIVE_MOTOR_GAIN` of it, then fading.
    pub max_wheel_omega: f32,
    /// Tyre Coulomb friction.
    pub grip: f32,
    /// Unused; grip alone sets the tyre; still validated to (0,1] (see D_TRACTION_RELAX).
    pub traction_relax: f32,
    pub gravity: f32,
    /// Chassis tilt past which a head-to-ground contact is a rollover (rad).
    pub crash_tilt_rad: f32,
}

/// Angles: y-up, CCW-positive radians; rear/front_angle roll in DRIVING sense, negate for canvas.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct CarState {
    /// Chassis centre of mass.
    pub x: f32,
    pub y: f32,
    /// 0 is level, positive nose-up.
    pub angle: f32,
    pub vx: f32,
    pub vy: f32,
    pub angular_velocity: f32,

    pub rear_x: f32,
    pub rear_y: f32,
    pub rear_angle: f32,
    /// rad/s, positive rolling forward.
    pub rear_omega: f32,
    /// Carrying load, or within the solver's 0.2 m reach; instantaneous, unlike airborne.
    pub rear_contact: bool,

    pub front_x: f32,
    pub front_y: f32,
    pub front_angle: f32,
    pub front_omega: f32,
    pub front_contact: bool,

    /// NOT a crank speed: a monotone function of rear wheel speed plus a throttle bump.
    pub rpm: f32,
    /// Throttle actually delivered.
    pub throttle_applied: f32,
    /// Normal impulse over weight this step (N*s), rectified one-sided; the haptics amplitude.
    pub impact_impulse: f32,
    /// Mean terrain slope-change under the car, saturating at 1. The rumble amplitude.
    pub roughness: f32,
    /// Nothing touching for AIRBORNE_ARM_TICKS; read as an edge, so slow to arm, instant to clear.
    pub airborne: bool,
    /// Furthest x reached, from the start line. Monotone non-decreasing.
    pub distance_m: f32,
    pub run: RunState,
    /// Substeps actually run, not wall clock.
    pub elapsed_s: f32,
}

/// Exported so Kotlin never transcribes these numbers; the Rust is the single authority.
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

#[derive(Clone, Copy)]
struct Wheel {
    /// Driving sense: positive rolls the car toward +x (rad/s).
    spin: f32,
    /// Accumulated rolled angle, wrapped to (−2π, 2π). Render only.
    angle: f32,
    contact: bool,
    /// World-space wheel centre.
    cx: f32,
    cy: f32,
}

/// Prior tick, kept so snapshot can interpolate: a frame consumes 1 or 3 ticks, unequal without it.
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

/// Chassis pose and velocity, plus the suspension extension the wheels hang at.
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

/// Rebuilt wholesale by seat_car, not teleported: a teleport replays stale warm-start as a kick.
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
    fn build(ground: &Option<SharedShape>, blocks: &[Block], t: &CarTuning, seat: &Seat) -> Self {
        let mut bodies = RigidBodySet::new();
        let mut colliders = ColliderSet::new();
        let mut joints = ImpulseJointSet::new();

        // Absent only when no cell is solid; an empty polyline would carry an inverted AABB in.
        if let Some(ground) = ground {
            let ground_body = bodies.insert(RigidBodyBuilder::fixed());
            colliders.insert_with_parent(
                ColliderBuilder::new(ground.clone()).friction(t.grip).restitution(0.0),
                ground_body,
                &mut bodies,
            );
        }
        add_obstacles(&mut bodies, &mut colliders, blocks, t.grip, 0.0);

        let chassis = bodies.insert(
            RigidBodyBuilder::dynamic()
                .translation(Vector::new(seat.x, seat.y))
                .rotation(seat.ang)
                .linvel(Vector::new(seat.vx, seat.vy))
                .angvel(seat.av)
                .linear_damping(LINEAR_DAMPING)
                .angular_damping(ANGULAR_DAMPING)
                // LENGTH_UNIT=10: sleep threshold is 4 m/s; a sleeping island freezes haptics.
                .can_sleep(false)
                .ccd_enabled(false),
        );
        let chassis_col = colliders.insert_with_parent(
            ColliderBuilder::cuboid(t.chassis_half_len, t.chassis_half_height)
                // `mass` preserves the shape's I/m ratio: the inertia is `m(w² + h²)/12`.
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

            // Pin-slot: locking LIN_Y leaves wheel free along local_axis1; dist = suspension ext.
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

            // (body1, body2): axis1/anchor1 are CHASSIS coords; wheel first, car hangs off rim.
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

    fn touching(&self, col: ColliderHandle, tol: f32) -> bool {
        touching(&self.narrow_phase, col, tol)
    }

    fn normal_impulse(&self, col: ColliderHandle) -> f32 {
        normal_impulse(&self.narrow_phase, col)
    }
}

struct Sim {
    terrain: Terrain,
    tune: CarTuning,
    /// Arc: rebuild re-hangs the shape, not re-BVHs 200k segments; None if nowhere solid.
    ground: Option<SharedShape>,
    blocks: Vec<Block>,
    params: IntegrationParameters,
    gravity: Vector,
    phys: Phys,

    // Mirror refreshed each finite substep; renderer reads this, not bodies; poison freezes it.
    x: f32,
    y: f32,
    ang: f32,
    vx: f32,
    vy: f32,
    av: f32,
    /// `[REAR]`, `[FRONT]`.
    wheels: [Wheel; 2],
    /// One tick behind mirror, for interpolation; seat_car seeds it equal, no cross-run blend.
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
    /// Consecutive ticks with nothing touching.
    air_ticks: u32,

    /// Static-sag extension the car is seated at.
    sag_ext: f32,
    /// What one substep of standing still costs (N·s): the whole car's weight, wheels included.
    weight_impulse: f32,
}

impl Sim {
    fn new(terrain: Terrain, tune: CarTuning, obstacles: &[Obstacle]) -> Self {
        let ground = build_ground(&terrain.heights, terrain.dx);
        let blocks = place_obstacles(&terrain, obstacles);
        let params = solver_params();
        // The extension at which both springs carry half the weight each.
        let sag = if tune.suspension_stiffness > 0.0 {
            (0.5 * tune.chassis_mass * tune.gravity / tune.suspension_stiffness)
                .clamp(0.0, tune.suspension_rest)
        } else {
            0.0
        };
        let sag_ext = (tune.suspension_rest - sag).clamp(0.0, tune.suspension_travel);
        let seat = Seat { x: 0.0, y: 0.0, ang: 0.0, vx: 0.0, vy: 0.0, av: 0.0, ext: sag_ext };
        // Empty placeholder: `reset` below replaces it unconditionally, colliders and all.
        let phys = Phys::build(&None, &[], &tune, &seat);
        let mut s = Sim {
            terrain,
            tune,
            ground,
            blocks,
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

    /// Rebuilds world at seat, re-derives mirror; run bookkeeping untouched (reset_from's job).
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
        self.phys = Phys::build(&self.ground, &self.blocks, &self.tune, &seat);

        self.x = seat.x;
        self.y = seat.y;
        self.ang = wrap_pi(seat.ang);
        self.vx = seat.vx;
        self.vy = seat.vy;
        self.av = seat.av;
        // Derived, not asserted: a seat can be over a chasm; renderer/haptics read pre-physics.
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

    fn reset(&mut self) {
        self.reset_from(0.0);
    }

    /// Places the car on first solid ground at/after from_x; start is wherever the user tapped.
    fn reset_from(&mut self, from_x: f32) {
        let t = self.tune;
        let n = self.terrain.heights.len();
        let span = ((2.0 * t.chassis_half_len * self.terrain.inv_dx).ceil() as usize).max(1);
        let begin = if from_x.is_finite() {
            ((from_x * self.terrain.inv_dx).floor().max(0.0) as usize).min(n.saturating_sub(1))
        } else {
            0
        };
        // No landable run after the tap: falls back to first anywhere, seat always has ground.
        let first = self
            .terrain
            .first_run_from(begin, span)
            .or_else(|| self.terrain.first_run_from(0, span))
            .unwrap_or(begin);
        // first near the end can push sx past terrain.length; clamped here for every caller.
        let max_sx = (self.terrain.length - t.chassis_half_len).max(0.0);
        let sx = (first as f32 * self.terrain.dx + t.chassis_half_len).min(max_sx);
        let ext = self.sag_ext;

        // ALIGNED with local grade: a level chassis on a slope slams down, reading as a launch.
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
        // start_x is sx, not self.x: COM leads sx downhill, else a fresh seat reports false metres.
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
        // Railed: a burst of long frames cannot bank unbounded simulation debt.
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
            // Dropped rather than chased: a frame loop must never spiral.
            self.accumulator = 0.0;
        }
    }

    fn substep(&mut self, dt: f32, throttle: f32, brake: f32) {
        let t = self.tune;

        let thr = throttle;
        self.throttle_applied = thr;

        self.drive(thr, brake);
        for i in 0..2 {
            let damp = if self.wheels[i].contact { WHEEL_SPIN_DAMPING } else { WHEEL_SPIN_DAMPING_AIR };
            if let Some(b) = self.phys.bodies.get_mut(self.phys.wheels[i]) {
                b.set_angular_damping(damp);
            }
        }

        self.phys.step(&self.params, self.gravity);

        // rapier poisons state silently on a bad number; freezing here keeps the last finite pose.
        if !self.solver_finite() {
            self.run = RunState::Crashed;
            return;
        }
        self.rail_velocities();

        // The solver's own speculative reach, `LENGTH_UNIT`-scaled to this world.
        let tol = self.params.prediction_distance();
        let rear_c = self.phys.touching(self.phys.wheel_cols[REAR], tol);
        let front_c = self.phys.touching(self.phys.wheel_cols[FRONT], tol);
        let body_c = self.phys.touching(self.phys.chassis_col, tol);
        let any_contact = rear_c || front_c || body_c;
        self.air_ticks = if any_contact { 0 } else { self.air_ticks.saturating_add(1) };

        // Off ground, pedals become attitude control; keyed off RAW contact, not hysteresised flag.
        if !any_contact {
            let pitch = (thr - brake).clamp(-1.0, 1.0);
            if let Some(b) = self.phys.bodies.get_mut(self.phys.chassis) {
                let av = (b.angvel() + pitch * AIR_PITCH_ACCEL * dt).clamp(-MAX_ANGULAR, MAX_ANGULAR);
                b.set_angvel(av, true);
            }
        }

        self.mirror(dt, [rear_c, front_c], self.air_ticks < AIRBORNE_ARM_TICKS);

        let carried = self.phys.normal_impulse(self.phys.wheel_cols[REAR])
            + self.phys.normal_impulse(self.phys.wheel_cols[FRONT])
            + self.phys.normal_impulse(self.phys.chassis_col);
        self.impact += (carried * IMPULSE_WARMSTART_BIAS - self.weight_impulse).max(0.0);

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

        if self.x >= self.terrain.length {
            self.run = RunState::Finished;
        } else if self.y < self.terrain.kill_y {
            self.run = RunState::Crashed;
        } else if head_down && rolled {
            self.run = RunState::Crashed;
        }
    }

    /// Motor targets RELATIVE rate wheel-chassis; +x forward is NEGATIVE spin (y-up, CCW world).
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

    /// Only ever called after solver_finite has passed.
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
            // The FFI's sense is positive-rolling-forward, the solver's counter-clockwise.
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

    /// POSE interpolates the last two ticks by the accumulator; others are current-tick verbatim.
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

    /// Re-seats mid-run, leaves run bookkeeping alone; tests only, no FFI way to airdrop the car.
    #[cfg(test)]
    fn place_for_test(&mut self, ang: f32, y: f32, vy: f32) {
        let seat = Seat { x: self.x, y, ang, vx: self.vx, vy, av: self.av, ext: self.sag_ext };
        self.seat_car(seat);
    }
}

/// Arc-shared, locked; Kotlin's AutoCloseable frees Rust at next GC -- close in DisposableEffect.
#[derive(uniffi::Object)]
pub struct GameWorld {
    inner: Mutex<Sim>,
}

#[uniffi::export]
impl GameWorld {
    /// The ONE fallible, allocating, message-formatting entry point; `step` is not.
    #[uniffi::constructor]
    pub fn new(terrain: TerrainSpec, tuning: CarTuning) -> Result<Arc<Self>, CoreError> {
        Self::with_obstacles(terrain, tuning, Vec::new())
    }

    /// Fixed boxes on the ground line; one over a gap is dropped, a malformed one is an error.
    #[uniffi::constructor]
    pub fn with_obstacles(
        terrain: TerrainSpec,
        tuning: CarTuning,
        obstacles: Vec<Obstacle>,
    ) -> Result<Arc<Self>, CoreError> {
        let t = validate_terrain(terrain)?;
        validate_tuning(&tuning)?;
        validate_obstacles(&obstacles)?;
        Ok(Arc::new(Self { inner: Mutex::new(Sim::new(t, tuning, &obstacles)) }))
    }

    /// dt_ms consumes fixed 1/120s ticks, past MAX_SUBSTEPS dropped; throttle/brake saturate [0,1].
    pub fn step(&self, dt_ms: f32, throttle: f32, brake: f32) -> Result<CarState, CoreError> {
        let mut sim = self.lock()?;
        sim.advance(dt_ms, throttle, brake);
        Ok(sim.snapshot())
    }

    /// The current frame without advancing.
    pub fn state(&self) -> Result<CarState, CoreError> {
        Ok(self.lock()?.snapshot())
    }

    /// Put the car back on the start line, `Running`.
    pub fn reset(&self) -> Result<CarState, CoreError> {
        let mut sim = self.lock()?;
        sim.reset();
        Ok(sim.snapshot())
    }

    /// Re-places car at first solid ground at/after x (world metres), wherever the user tapped.
    pub fn reset_at(&self, x: f32) -> Result<CarState, CoreError> {
        let mut sim = self.lock()?;
        sim.reset_from(x);
        Ok(sim.snapshot())
    }

    /// x of the finish line.
    pub fn track_length(&self) -> Result<f32, CoreError> {
        Ok(self.lock()?.terrain.length)
    }
}

// Outside the export block: uniffi exports every impl method, rejects unlowerable signatures.
impl GameWorld {
    fn lock(&self) -> Result<std::sync::MutexGuard<'_, Sim>, CoreError> {
        self.inner.lock().map_err(|_| internal("game world lock poisoned"))
    }
}

/// Reject a tuning that would make the integrator meaningless before it reaches the step path.
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
    use crate::terrain::MAX_TERRAIN_SAMPLES;
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

    fn flat() -> TerrainSpec {
        TerrainSpec { heights: vec![10.0; 401], dx: 1.0, world_height: 40.0 }
    }

    /// Constant grade rising right, rise m/m; 2 km so no test's frame budget runs off top.
    fn ramp(rise: f32) -> TerrainSpec {
        let heights: Vec<f32> = (0..2001).map(|i| 10.0 + rise * i as f32).collect();
        TerrainSpec { heights, dx: 1.0, world_height: 2_000.0 }
    }

    /// A jagged glucose excursion. Deterministic, no RNG.
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

    /// `frames` at a nominal 60 fps.
    fn drive(w: &GameWorld, frames: u32, throttle: f32, brake: f32) -> CarState {
        let mut last = w.state().unwrap();
        for _ in 0..frames {
            last = w.step(1000.0 / 60.0, throttle, brake).unwrap();
        }
        last
    }

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
        // Bound is 5 not 0.1: SEAT TRANSIENT -- placed at sag, settles in ~1.7s, peaks 2.1 N*s.
        assert!(s.impact_impulse < 5.0, "resting impact = {}", s.impact_impulse);
        assert_close(s.roughness, 0.0, 1e-6, "flat roughness");
    }

    #[test]
    fn rolling_flat_ground_at_speed_registers_no_impacts() {
        // Collider's job, not haptics': an edge kick per cell vertex is a 16 Hz spike train.
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
        // airborne reads as an EDGE; compare the two flags, a hysteresised flag hides a strobe.
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
        assert_close(s.rear_y, 10.0 + t.wheel_radius, 0.02, "rear ride height");
        assert_close(s.front_y, 10.0 + t.wheel_radius, 0.02, "front ride height");
    }

    #[test]
    fn rolls_downhill_without_throttle() {
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

    #[test]
    fn climbs_a_modest_slope_under_throttle() {
        // 25 % grade ≈ 14°.
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
        // 90 % grade ≈ 42°: the steepest face the shipped tune takes at pinned throttle.
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
    fn a_wall_stops_the_car_and_a_wall_over_a_gap_does_not_exist() {
        let wall = Obstacle { x: 120.0, half_w: 2.0, h: 12.0, lift: 0.0 };
        let w = GameWorld::with_obstacles(flat(), default_car_tuning(), vec![wall]).unwrap();
        let s = drive(&w, 900, 1.0, 0.0);
        assert!(s.x < 120.0, "drove through the wall to {}", s.x);

        let mut gapped = flat();
        for h in &mut gapped.heights[110..130] {
            *h = f32::NAN;
        }
        let w = GameWorld::with_obstacles(gapped, default_car_tuning(), vec![wall]).unwrap();
        assert_eq!(w.lock().unwrap().blocks.len(), 0, "a box over a chasm has nothing to stand on");
    }

    #[test]
    fn rejects_a_malformed_obstacle() {
        let bad = Obstacle { x: 10.0, half_w: -1.0, h: 5.0, lift: 0.0 };
        assert!(GameWorld::with_obstacles(flat(), default_car_tuning(), vec![bad]).is_err());
        let nan = Obstacle { x: f32::NAN, half_w: 1.0, h: 5.0, lift: 0.0 };
        assert!(GameWorld::with_obstacles(flat(), default_car_tuning(), vec![nan]).is_err());
        let sunk = Obstacle { x: 10.0, half_w: 1.0, h: 5.0, lift: -1.0 };
        assert!(GameWorld::with_obstacles(flat(), default_car_tuning(), vec![sunk]).is_err());
    }

    #[test]
    fn brake_reverses_and_holds() {
        let w = world(flat());
        let s = drive(&w, 240, 0.0, 1.0);
        assert!(s.vx < -0.5, "brake must back the car up on the flat, vx = {}", s.vx);
    }

    #[test]
    fn reaching_the_right_edge_finishes() {
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
        // Solid for the first 40m, gap after: a tap into the gap has no landable run after it.
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
        // The start line is the WHEEL mid-point, not the chassis centre of mass.
        for rise in [-1.0f32, -0.25, 0.0, 0.25, 1.0] {
            let heights: Vec<f32> = (0..2001).map(|i| 2_000.0 + rise * i as f32).collect();
            let w = world(TerrainSpec { heights, dx: 1.0, world_height: 6_000.0 });
            assert_eq!(w.reset().unwrap().distance_m, 0.0, "grade {rise}");
        }
    }

    #[test]
    fn a_seat_over_a_chasm_reports_itself_airborne() {
        // No landable run anywhere, so the seat falls back to the tap and the car is in free fall.
        let w = world(TerrainSpec { heights: vec![-1.0; 200], dx: 1.0, world_height: 20.0 });
        let s = w.state().unwrap();
        assert!(s.airborne, "seated over a chasm but reporting grounded");
        assert!(!s.rear_contact && !s.front_contact);
    }

    #[test]
    fn rejects_a_dx_whose_reciprocal_overflows() {
        // Finite, positive dx but 1/dx=+inf; a signal reads 0*inf=NaN, clamp can't fix a NaN self.
        let spec =
            TerrainSpec { heights: vec![10.0; 401], dx: f32::from_bits(1), world_height: 40.0 };
        assert!(GameWorld::new(spec, default_car_tuning()).is_err());
        // The derived signal is total anyway, for a `dx` that slips past a future guard.
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
        // Released on a steep start, car rolls BACKWARDS off x=0; only air-drag slows it there.
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
        // Solid for 110 m, then nothing: wider than any launch speed clears.
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
        // A 100 % grade pitches the chassis 45°, well inside `crash_tilt_rad`.
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
        // Placement aligns with the local grade, so the pitch is the start line's.
        let flat_start = world(flat()).reset().unwrap();
        assert_eq!(flat_start.angle, 0.0);
        let again = drive(&w, 600, 1.0, 0.0);
        assert_eq!(again, driven);
    }

    #[test]
    fn identical_inputs_give_identical_states() {
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

            // Re-seating must not blend the new seat against the old run.
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
        // Consumes one tick, banks half of the next.
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
        assert_eq!(s.vx, vx, "velocity must be the current tick's, unblended");
    }

    #[test]
    fn the_shortest_arc_carries_the_wheel_across_the_wrap_seam() {
        // Hair short of a full turn blended toward a hair past it; long way spins a whole turn.
        let got = lerp_angle(TWO_PI - 0.05, -TWO_PI + 0.05, 0.5) % TWO_PI;
        assert_close(wrap_pi(got), 0.0, 1e-5, "seam crossing");
        assert_close(lerp_angle(3.0, -3.0, 0.0), 3.0, 1e-6, "t = 0 is the previous angle");
        assert_close(wrap_pi(lerp_angle(3.0, -3.0, 1.0)), -3.0, 1e-5, "t = 1 is the current one");
    }

    #[test]
    fn the_accumulator_makes_a_long_frame_bounded() {
        let a = world(flat());
        let sa = a.step(10_000.0, 1.0, 0.0).unwrap();
        assert!(sa.elapsed_s <= MAX_SUBSTEPS as f32 * FIXED_DT + 1e-6, "elapsed = {}", sa.elapsed_s);
        assert!(sa.x.is_finite() && sa.y.is_finite());
    }

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
        // validate_tuning bounds by sign not magnitude; derived loads overflow, NaN panics clamp.
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
        // No solid ground anywhere: the car falls to the kill plane. Nothing may panic.
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
        // Deterministic xorshift under panic=abort; free fns avoid closures colliding on state.
        fn xs(state: &mut u64) -> u64 {
            *state ^= *state << 13;
            *state ^= *state >> 7;
            *state ^= *state << 17;
            *state
        }
        /// A random 32-bit pattern as f32: NaNs, infinities, subnormals, absurd magnitudes.
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

    /// Diagnostic, not a gate.
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
        // A ramp ending in a cliff.
        let mut heights: Vec<f32> = (0..300).map(|i| 10.0 + 0.05 * i as f32).collect();
        heights.extend((0..400).map(|_| f32::NAN)); // the void past the lip
        let spec = TerrainSpec { heights, dx: 1.0, world_height: 400.0 };
        let w = GameWorld::new(spec, default_car_tuning()).unwrap();
        let mut s = w.state().unwrap();
        for _ in 0..600 {
            s = w.step(1000.0 / 60.0, 1.0, 0.0).unwrap();
            if s.airborne { break; }
        }
        assert!(s.airborne, "should have driven off the lip");
        let a0 = s.angle;
        for _ in 0..30 { s = w.step(1000.0 / 60.0, 1.0, 0.0).unwrap(); }
        assert!(s.angle > a0, "throttle should rotate the nose up in the air: {} -> {}", a0, s.angle);

        // Planted, the same input must not be an attitude control.
        let flat = TerrainSpec { heights: vec![10.0; 400], dx: 1.0, world_height: 400.0 };
        let g = GameWorld::new(flat, default_car_tuning()).unwrap();
        let mut p = g.state().unwrap();
        for _ in 0..30 { p = g.step(1000.0 / 60.0, 1.0, 0.0).unwrap(); }
        assert!(p.angle.abs() < 0.25, "planted car should not pitch on throttle: {}", p.angle);
    }

    /// Diagnostic: FRACTION of climb on both wheels sets torque; a 1-frame sample is a coin toss.
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

    /// Diagnostic: airtime over a track; clearance as a FRACTION OF WORLD HEIGHT, panel's unit.
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
            let drift_deg = ((3.0 * world_h / span) / mpm).atan().to_degrees();
            println!(
                "H={:5.0}  airborne {:3}/900 ({:4.1}%)  peak clear {:5.2} m = {:4.2}% of H  \
                 3 mg/dL/min slope {:4.1}°  peak v {:5.1}  crashed={}",
                world_h, air, 100.0 * air as f32 / 900.0, peak_clear,
                100.0 * peak_clear / world_h, drift_deg, peak_v, crashed
            );
        }
    }

    /// Regenerates game_golden.json on stdout; #[ignore]d so replacing the pin is a manual act.
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

