//! Cosmetic golf physics on the car's heightfield; rapier poisons state on non-finite input.

use std::sync::{Arc, Mutex};

use rapier2d::prelude::*;

use crate::terrain::{
    add_obstacles, build_ground_to, dec, internal, lerp, lerp_angle, normal_impulse, place_obstacles,
    sane01, solid, solver_params, touching, validate_obstacles, validate_terrain, Block, Obstacle,
    Terrain, TerrainSpec, FIXED_DT, IMPULSE_WARMSTART_BIAS, MAX_FRAME_DT_S, MAX_SPEED, MAX_SUBSTEPS,
    RUN_OFF_LENGTH, TWO_PI,
};
use crate::CoreError;

/// rad/s. A ball rolling at [`MAX_SPEED`] on a 3 m radius spins at 67; this catches blow-up only.
const MAX_BALL_SPIN: f32 = 400.0;

/// Cup width in ball radii; under 2 the ball cannot enter, and a lip-out stops being a near miss.
const CUP_WIDTH_R: f32 = 3.2;
/// Cup depth in ball radii; deeper than one diameter, so a sunk ball sits clear under the rim.
const CUP_DEPTH_R: f32 = 2.4;

/// Ball centres this many radii under the world floor are unrecoverable: heights floor at 0.
const WATER_DEPTH_R: f32 = 3.0;

/// Share of weight the turf resists a rolling ball with: ×[`D_GRAVITY`] is 9 m/s².
const ROLL_STATIC_G: f32 = 0.15;

/// Steepest grade (rise/run) the turf holds a ball under `rest_speed` on: 0.45 ⇒ 24°.
const HOLD_GRADE: f32 = 0.45;

/// Contact-free ticks before airborne reports, 8 ⇒ 67 ms; an EDGE, tie breaks toward GROUNDED.
const AIRBORNE_ARM_TICKS: u32 = 8;

/// Metres; big enough to bridge the notches a 1 m heightfield of CGM noise is full of.
const D_BALL_RADIUS: f32 = 3.0;
/// kg. Sets the scale of `impact` only; the flight is mass-independent.
const D_BALL_MASS: f32 = 12.0;
/// Turf, not a green: a hard landing keeps about a third of its approach speed.
const D_RESTITUTION: f32 = 0.35;
const D_FRICTION: f32 = 0.9;
/// Linear rolling bleed (1/s) on top of [`ROLL_STATIC_G`]; together, 40 m/s runs out in ~49 m.
const D_ROLLING_DAMPING: f32 = 0.30;
/// Exaggerated like the car's, and the divisor of the carry: `v²/g` at 45°.
const D_GRAVITY: f32 = 60.0;
/// m/s ⇒ 180 m of carry, ~12 readings of trace, in 2.45 s of hang on a 100 m-tall world.
const D_MAX_LAUNCH_SPEED: f32 = 104.0;
/// m/s. Above the jitter a settled ball keeps, below anything still rolling out or carrying.
const D_REST_SPEED: f32 = 0.8;
/// Seconds held under [`D_REST_SPEED`]; long enough that a bounce apex cannot satisfy it.
const D_REST_HOLD_S: f32 = 0.35;

/// `Holed` is TERMINAL: `step` re-returns frozen state; `tee_at`/`reset` are the only way back.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum GolfRun {
    Playing,
    Holed,
}

/// The `D_*` constants [`default_golf_tuning`] is built from carry the provenance of each number.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct GolfTuning {
    pub ball_radius: f32,
    pub ball_mass: f32,
    /// Coefficient of restitution, [0, 1]; combined with the ground's by rapier's `Average`.
    pub restitution: f32,
    pub friction: f32,
    /// Linear rolling bleed (1/s), applied ONLY in ground contact — flight is drag-free.
    pub rolling_damping: f32,
    pub gravity: f32,
    /// Speed cap on `shoot`; direction is kept, magnitude clipped.
    pub max_launch_speed: f32,
    pub rest_speed: f32,
    pub rest_hold_s: f32,
}

/// Cut at the last solid sample; `x1` IS the present moment, and the right rim is the finish.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct GolfCup {
    pub x0: f32,
    pub x1: f32,
    /// World y of both lips; the cup is level even where the trace under it is not.
    pub rim_y: f32,
    pub depth: f32,
}

/// Flight is drag-free BY CONTRACT: `x+vx·t, y+vy·t−g·t²/2` is the arc, so a preview needs no FFI.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct BallState {
    pub x: f32,
    pub y: f32,
    pub vx: f32,
    pub vy: f32,
    /// Rolled angle, wrapped to (−2π, 2π), positive rolling toward +x. Render only.
    pub angle: f32,
    /// Settled: the only state `shoot` is honoured in.
    pub at_rest: bool,
    /// Nothing touching for [`AIRBORNE_ARM_TICKS`]; NOT !contact — edge: slow arm, instant clear.
    pub airborne: bool,
    pub strokes: u32,
    /// Water drops; each is a stroke's worth of score and a re-placement, not a stroke.
    pub penalties: u32,
    /// Normal impulse over step, excess of weight (N·s); rectified, one-sided. Haptics amplitude.
    pub impact: f32,
    pub run: GolfRun,
}

/// Exported so Kotlin never transcribes these numbers; the Rust is the single authority.
#[uniffi::export]
pub fn default_golf_tuning() -> GolfTuning {
    GolfTuning {
        ball_radius: D_BALL_RADIUS,
        ball_mass: D_BALL_MASS,
        restitution: D_RESTITUTION,
        friction: D_FRICTION,
        rolling_damping: D_ROLLING_DAMPING,
        gravity: D_GRAVITY,
        max_launch_speed: D_MAX_LAUNCH_SPEED,
        rest_speed: D_REST_SPEED,
        rest_hold_s: D_REST_HOLD_S,
    }
}

/// Prior tick, kept for [`Sim::snapshot`] to interpolate — frame ticks vary (1 or 3), pose jitters.
#[derive(Clone, Copy)]
struct Pose {
    x: f32,
    y: f32,
    angle: f32,
}

/// Rebuilt wholesale, not teleported: stale warm-start impulses would replay as a one-frame kick.
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
    ball: RigidBodyHandle,
    ball_col: ColliderHandle,
}

impl Phys {
    fn build(
        ground: &Option<SharedShape>,
        blocks: &[Block],
        t: &GolfTuning,
        x: f32,
        y: f32,
        vx: f32,
        vy: f32,
    ) -> Self {
        let mut bodies = RigidBodySet::new();
        let mut colliders = ColliderSet::new();

        // Absent only if no cell is solid; an empty polyline carries an inverted AABB downstream.
        if let Some(ground) = ground {
            let ground_body = bodies.insert(RigidBodyBuilder::fixed());
            colliders.insert_with_parent(
                ColliderBuilder::new(ground.clone())
                    .friction(t.friction)
                    .restitution(t.restitution),
                ground_body,
                &mut bodies,
            );
        }
        add_obstacles(&mut bodies, &mut colliders, blocks, t.friction, t.restitution);

        let ball = bodies.insert(
            RigidBodyBuilder::dynamic()
                .translation(Vector::new(x, y))
                .linvel(Vector::new(vx, vy))
                // Drag-free in flight: the preview arc is the solver's arc, not an approximation.
                .linear_damping(0.0)
                .angular_damping(0.0)
                // At LENGTH_UNIT=10 sleep is 4 m/s; a sleeping island freezes haptics' impulses.
                .can_sleep(false)
                .ccd_enabled(false),
        );
        let ball_col = colliders.insert_with_parent(
            ColliderBuilder::ball(t.ball_radius)
                .mass(t.ball_mass)
                .friction(t.friction)
                .restitution(t.restitution),
            ball,
            &mut bodies,
        );

        Phys {
            bodies,
            colliders,
            joints: ImpulseJointSet::new(),
            multibody_joints: MultibodyJointSet::new(),
            islands: IslandManager::new(),
            broad_phase: BroadPhaseBvh::new(),
            narrow_phase: NarrowPhase::new(),
            ccd: CCDSolver::new(),
            pipeline: PhysicsPipeline::new(),
            ball,
            ball_col,
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
}

struct Sim {
    terrain: Terrain,
    tune: GolfTuning,
    /// `Arc`: rebuild re-hangs the shape, not re-BVHs a 200000-segment polyline. `None` if all-gap.
    ground: Option<SharedShape>,
    cup: GolfCup,
    /// Last solid sample clear of the left lip; every fallback placement lands on it.
    approach: usize,
    blocks: Vec<Block>,
    params: IntegrationParameters,
    gravity: Vector,
    phys: Phys,
    /// Below this the ball is unrecoverable; always under the cup floor, so a sunk putt is safe.
    water_y: f32,
    /// What one substep of standing still costs (N·s).
    weight_impulse: f32,

    // Solver mirror, refreshed only on finite substeps; renderer reads it — poison freezes frame.
    x: f32,
    y: f32,
    vx: f32,
    vy: f32,
    /// Positive rolling toward +x, the negative of the solver's counter-clockwise ω.
    spin: f32,
    angle: f32,
    /// One tick behind mirror, for render interp; seeded equal by place, no cross-shot blend.
    prev: Pose,

    /// RAW contact last substep, not hysteresised; rolling resistance rides this.
    contact: bool,
    air_ticks: u32,
    rest_s: f32,
    at_rest: bool,
    last_rest_x: f32,
    last_rest_y: f32,

    strokes: u32,
    penalties: u32,
    run: GolfRun,
    tee_x: f32,
    /// What `tee_from` was ASKED for; `tee_x` is the snapped answer, and re-feeding that walks +r.
    tee_req: f32,
    accumulator: f32,
    impact: f32,
    elapsed: f32,
}

impl Sim {
    fn new(terrain: Terrain, tune: GolfTuning, obstacles: &[Obstacle]) -> Result<Self, CoreError> {
        let r = tune.ball_radius;
        let width = CUP_WIDTH_R * r;
        let depth = CUP_DEPTH_R * r;
        let last = terrain
            .last_solid()
            .ok_or_else(|| dec("terrain: no solid ground to cut a cup into"))?;
        let x1 = last as f32 * terrain.dx;
        let x0 = x1 - width;
        // A hair inside, so a cup whose lip lands exactly on a sample cannot make a zero segment.
        let limit = x0 - 1.0e-3 * terrain.dx;
        let approach = (0..last)
            .rev()
            .find(|i| solid(terrain.heights[*i]) && (*i as f32 * terrain.dx) < limit)
            .ok_or_else(|| {
                dec(format!("terrain: {x1} m of solid track leaves no room for a {width} m cup"))
            })?;
        let cup = GolfCup { x0, x1, rim_y: terrain.heights[approach], depth };

        let mut tail = Vec::with_capacity(5);
        tail.push(Vector::new(x0, cup.rim_y));
        tail.push(Vector::new(x0, cup.rim_y - depth));
        tail.push(Vector::new(x1, cup.rim_y - depth));
        tail.push(Vector::new(x1, cup.rim_y));
        // Mirrors the left run-off: past the hole is uncrossable, not a cliff to be lost over.
        tail.push(Vector::new(x1 + RUN_OFF_LENGTH, cup.rim_y));
        let ground = build_ground_to(&terrain.heights, terrain.dx, approach + 1, &tail);

        let water_y = (-(WATER_DEPTH_R * r)).max(terrain.kill_y).min(cup.rim_y - depth);
        let mut blocks = place_obstacles(&terrain, obstacles);
        // The caller cannot see the cup; a box over its mouth would make the hole unholeable.
        let lip = approach as f32 * terrain.dx;
        blocks.retain(|b| b[0] + b[2] < lip || b[0] - b[2] > cup.x1);
        // Empty placeholder: `tee_from` below replaces it unconditionally, colliders and all.
        let phys = Phys::build(&None, &[], &tune, 0.0, 0.0, 0.0, 0.0);
        let mut s = Sim {
            terrain,
            tune,
            ground,
            cup,
            approach,
            blocks,
            params: solver_params(),
            gravity: Vector::new(0.0, -tune.gravity),
            phys,
            water_y,
            weight_impulse: tune.ball_mass * tune.gravity * FIXED_DT,
            x: 0.0,
            y: 0.0,
            vx: 0.0,
            vy: 0.0,
            spin: 0.0,
            angle: 0.0,
            prev: Pose { x: 0.0, y: 0.0, angle: 0.0 },
            contact: false,
            air_ticks: AIRBORNE_ARM_TICKS,
            rest_s: 0.0,
            at_rest: false,
            last_rest_x: 0.0,
            last_rest_y: 0.0,
            strokes: 0,
            penalties: 0,
            run: GolfRun::Playing,
            tee_x: 0.0,
            tee_req: 0.0,
            accumulator: 0.0,
            impact: 0.0,
            elapsed: 0.0,
        };
        s.tee_from(0.0);
        Ok(s)
    }

    fn pose(&self) -> Pose {
        Pose { x: self.x, y: self.y, angle: self.angle }
    }

    /// Rebuilds the world around the ball at `(x, y)`; round bookkeeping is the caller's.
    fn place(&mut self, x: f32, y: f32, vx: f32, vy: f32) {
        let ok = x.is_finite() && y.is_finite() && vx.is_finite() && vy.is_finite();
        let (x, y, vx, vy) =
            if ok { (x, y, vx, vy) } else { (self.tee_x, self.cup.rim_y, 0.0, 0.0) };
        self.phys = Phys::build(&self.ground, &self.blocks, &self.tune, x, y, vx, vy);
        self.x = x;
        self.y = y;
        self.vx = vx;
        self.vy = vy;
        self.spin = 0.0;
        self.angle = 0.0;
        // Derived, not asserted: a placement can sit over a chasm, and the renderer reads this.
        let down = self
            .terrain
            .sample(x)
            .is_some_and(|h| y - h <= self.tune.ball_radius + self.params.prediction_distance());
        self.contact = down;
        self.air_ticks = if down { 0 } else { AIRBORNE_ARM_TICKS };
        self.rest_s = 0.0;
        self.at_rest = false;
        self.impact = 0.0;
        self.accumulator = 0.0;
        self.prev = self.pose();
    }

    /// Tees on the first solid run at/after `from_x`, never on the green side of the left lip.
    fn tee_from(&mut self, from_x: f32) {
        self.tee_req = from_x;
        let r = self.tune.ball_radius;
        let dx = self.terrain.dx;
        let n = self.terrain.heights.len();
        let span = ((2.0 * r * self.terrain.inv_dx).ceil() as usize).max(1);
        let begin = if from_x.is_finite() {
            ((from_x * self.terrain.inv_dx).floor().max(0.0) as usize).min(n - 1)
        } else {
            0
        };
        let first = self
            .terrain
            .first_run_from(begin, span)
            .or_else(|| self.terrain.first_run_from(0, span))
            .unwrap_or(self.approach);
        let want = first as f32 * dx + r;
        // The approach sample is solid by construction, so it is always a legal fallback tee.
        let (tx, h) = if want > self.cup.x0 - 2.0 * r {
            (self.approach as f32 * dx, self.terrain.heights[self.approach])
        } else {
            (want, self.terrain.sample(want).unwrap_or(self.terrain.heights[first]))
        };
        self.tee_x = tx;
        self.strokes = 0;
        self.penalties = 0;
        self.run = GolfRun::Playing;
        self.elapsed = 0.0;
        self.place(tx, h + r, 0.0, 0.0);
        self.last_rest_x = tx;
        self.last_rest_y = h + r;
        // Teed IS at rest: the first shot must not wait out `rest_hold_s` of held frames.
        self.rest_s = self.tune.rest_hold_s;
        self.at_rest = true;
    }

    fn shoot(&mut self, vx: f32, vy: f32) {
        if self.run != GolfRun::Playing || !self.at_rest {
            return;
        }
        if !vx.is_finite() || !vy.is_finite() {
            return;
        }
        let speed = (vx * vx + vy * vy).sqrt();
        if !(speed > 0.0) {
            return;
        }
        let k = if speed > self.tune.max_launch_speed {
            self.tune.max_launch_speed / speed
        } else {
            1.0
        };
        self.place(self.x, self.y, vx * k, vy * k);
        // The launch tick is drag-free even off the tee, so the preview arc is the arc flown.
        self.contact = false;
        self.strokes = self.strokes.saturating_add(1);
    }

    fn advance(&mut self, dt_ms: f32) {
        self.impact = 0.0;
        if self.run != GolfRun::Playing {
            return;
        }
        let dt_s = if dt_ms.is_finite() { (dt_ms * 1e-3).clamp(0.0, MAX_FRAME_DT_S) } else { 0.0 };
        // Railed: a burst of long frames cannot bank unbounded simulation debt.
        self.accumulator = (self.accumulator + dt_s).clamp(0.0, MAX_FRAME_DT_S + FIXED_DT);

        let mut n = 0u32;
        while self.accumulator >= FIXED_DT && n < MAX_SUBSTEPS {
            self.substep(FIXED_DT);
            self.accumulator -= FIXED_DT;
            self.elapsed += FIXED_DT;
            n += 1;
            if self.run != GolfRun::Playing {
                break;
            }
        }
        if n >= MAX_SUBSTEPS {
            // Dropped rather than chased: a frame loop must never spiral.
            self.accumulator = 0.0;
        }
    }

    fn substep(&mut self, dt: f32) {
        let t = self.tune;
        if self.contact {
            self.roll_resist(dt);
        }
        self.phys.step(&self.params, self.gravity);

        // rapier silently poisons state on bad numbers; mirror holds last finite frame, so freeze.
        if !self.solver_finite() {
            self.drop_back();
            return;
        }
        self.rail_velocities();

        // The solver's own speculative reach, `LENGTH_UNIT`-scaled to this world.
        let tol = self.params.prediction_distance();
        let down = touching(&self.phys.narrow_phase, self.phys.ball_col, tol);
        self.air_ticks = if down { 0 } else { self.air_ticks.saturating_add(1) };
        self.contact = down;
        // Pure rolling while down, so friction cannot re-accelerate what the turf just bled off.
        if down {
            let px = self.phys.bodies[self.phys.ball].translation().x;
            // Along the LIE: off `vx` alone it is short by cosθ, and the slip is μN of braking.
            let (tx, ty) = lie_tangent(&self.terrain, px);
            if let Some(b) = self.phys.bodies.get_mut(self.phys.ball) {
                let v = b.linvel();
                b.set_angvel(-(v.x * tx + v.y * ty) / t.ball_radius, true);
            }
        }
        self.mirror(dt);

        let carried = normal_impulse(&self.phys.narrow_phase, self.phys.ball_col);
        self.impact += (carried * IMPULSE_WARMSTART_BIAS - self.weight_impulse).max(0.0);

        let speed = (self.vx * self.vx + self.vy * self.vy).sqrt();
        if down && speed < t.rest_speed {
            self.rest_s += dt;
        } else {
            self.rest_s = 0.0;
        }
        self.at_rest = self.rest_s >= t.rest_hold_s;
        if self.at_rest {
            self.last_rest_x = self.x;
            self.last_rest_y = self.y;
            if self.x >= self.cup.x0 && self.x <= self.cup.x1 && self.y <= self.cup.rim_y {
                self.run = GolfRun::Holed;
            }
        }
        if self.y < self.water_y {
            self.penalties = self.penalties.saturating_add(1);
            self.drop_back();
        }
    }

    /// Turf: a fixed share of weight plus a linear bleed. Never a reversal — it only takes speed.
    fn roll_resist(&mut self, dt: f32) {
        let t = self.tune;
        let held = self.turf_holds(self.x);
        let Some(b) = self.phys.bodies.get_mut(self.phys.ball) else { return };
        let v = b.linvel();
        let s = (v.x * v.x + v.y * v.y).sqrt();
        if !(s > 1.0e-6) {
            return;
        }
        // Static hold: a slow ball on a gentle enough lie stops, else it rolls to the next one.
        if s < t.rest_speed && held {
            b.set_linvel(Vector::new(0.0, 0.0), true);
            return;
        }
        let cut = (ROLL_STATIC_G * t.gravity + t.rolling_damping * s) * dt;
        let k = ((s - cut) / s).max(0.0);
        b.set_linvel(Vector::new(v.x * k, v.y * k), true);
    }

    /// A gap on either side never holds.
    fn turf_holds(&self, x: f32) -> bool {
        grade_at(&self.terrain, x).is_some_and(|g| g.abs() <= HOLD_GRADE)
    }

    /// Back to where the ball last stood still, at rest. The one recovery from water or a poison.
    fn drop_back(&mut self) {
        let (x, y) = (self.last_rest_x, self.last_rest_y);
        self.place(x, y, 0.0, 0.0);
        self.rest_s = self.tune.rest_hold_s;
        self.at_rest = true;
    }

    fn solver_finite(&self) -> bool {
        let Some(b) = self.phys.bodies.get(self.phys.ball) else { return false };
        let p = b.translation();
        let v = b.linvel();
        p.x.is_finite()
            && p.y.is_finite()
            && v.x.is_finite()
            && v.y.is_finite()
            && b.angvel().is_finite()
    }

    fn rail_velocities(&mut self) {
        let Some(b) = self.phys.bodies.get_mut(self.phys.ball) else { return };
        let v = b.linvel();
        b.set_linvel(
            Vector::new(v.x.clamp(-MAX_SPEED, MAX_SPEED), v.y.clamp(-MAX_SPEED, MAX_SPEED)),
            true,
        );
        b.set_angvel(b.angvel().clamp(-MAX_BALL_SPIN, MAX_BALL_SPIN), true);
    }

    /// Only ever called after [`Sim::solver_finite`] has passed.
    fn mirror(&mut self, dt: f32) {
        self.prev = self.pose();
        let b = &self.phys.bodies[self.phys.ball];
        let p = b.translation();
        self.x = p.x;
        self.y = p.y;
        let v = b.linvel();
        self.vx = v.x;
        self.vy = v.y;
        // The FFI's sense is positive-rolling-forward, the solver's counter-clockwise.
        self.spin = -b.angvel();
        self.angle = (self.angle + self.spin * dt) % TWO_PI;
        if !self.angle.is_finite() {
            self.angle = 0.0;
        }
    }

    /// POSE interpolates between last two ticks; every other field is current-tick verbatim.
    fn snapshot(&self) -> BallState {
        let a = sane01(self.accumulator / FIXED_DT);
        let p = self.prev;
        BallState {
            x: lerp(p.x, self.x, a),
            y: lerp(p.y, self.y, a),
            vx: self.vx,
            vy: self.vy,
            angle: lerp_angle(p.angle, self.angle, a) % TWO_PI,
            at_rest: self.at_rest,
            airborne: self.air_ticks >= AIRBORNE_ARM_TICKS,
            strokes: self.strokes,
            penalties: self.penalties,
            impact: self.impact,
            run: self.run,
        }
    }
}

/// Grade under the ball (rise/run) from the samples either side of it; `None` at a gap.
fn grade_at(terrain: &Terrain, x: f32) -> Option<f32> {
    let dx = terrain.dx;
    match (terrain.sample(x - dx), terrain.sample(x + dx)) {
        (Some(a), Some(b)) => Some((b - a) / (2.0 * dx)),
        _ => None,
    }
}

/// Unit tangent of that grade, toward +x; a gap either side reads as flat.
fn lie_tangent(terrain: &Terrain, x: f32) -> (f32, f32) {
    let s = grade_at(terrain, x).unwrap_or(0.0);
    let inv = (1.0 + s * s).sqrt().recip();
    if inv.is_finite() {
        (inv, s * inv)
    } else {
        (1.0, 0.0)
    }
}

/// `Arc`-shared, locked. Kotlin AutoCloseable+Cleaner frees at next GC; close via DisposableEffect.
#[derive(uniffi::Object)]
pub struct GolfWorld {
    inner: Mutex<Sim>,
}

#[uniffi::export]
impl GolfWorld {
    /// The ONE fallible, allocating, message-formatting entry point; `step` is not.
    #[uniffi::constructor]
    pub fn new(terrain: TerrainSpec, tuning: GolfTuning) -> Result<Arc<Self>, CoreError> {
        Self::with_obstacles(terrain, tuning, Vec::new())
    }

    /// Fixed boxes on the ground line; one over a gap is dropped, a malformed one is an error.
    #[uniffi::constructor]
    pub fn with_obstacles(
        terrain: TerrainSpec,
        tuning: GolfTuning,
        obstacles: Vec<Obstacle>,
    ) -> Result<Arc<Self>, CoreError> {
        let t = validate_terrain(terrain)?;
        validate_tuning(&tuning)?;
        validate_obstacles(&obstacles)?;
        Ok(Arc::new(Self { inner: Mutex::new(Sim::new(t, tuning, &obstacles)?) }))
    }

    /// `dt_ms`: wall-clock delta, 1/120s ticks; past MAX_SUBSTEPS drops, non-finite = no time.
    pub fn step(&self, dt_ms: f32) -> Result<BallState, CoreError> {
        let mut sim = self.lock()?;
        sim.advance(dt_ms);
        Ok(sim.snapshot())
    }

    /// The current frame without advancing.
    pub fn state(&self) -> Result<BallState, CoreError> {
        Ok(self.lock()?.snapshot())
    }

    /// Ignored unless at rest and still `Playing`; the speed is capped, never scaled up.
    pub fn shoot(&self, vx: f32, vy: f32) -> Result<BallState, CoreError> {
        let mut sim = self.lock()?;
        sim.shoot(vx, vy);
        Ok(sim.snapshot())
    }

    /// Re-tees on the first solid ground at/after `x` (world m), scorecard back to zero.
    pub fn tee_at(&self, x: f32) -> Result<BallState, CoreError> {
        let mut sim = self.lock()?;
        sim.tee_from(x);
        Ok(sim.snapshot())
    }

    /// Replays the same hole: back to the tee this round started from.
    pub fn reset(&self) -> Result<BallState, CoreError> {
        let mut sim = self.lock()?;
        let tee = sim.tee_req;
        sim.tee_from(tee);
        Ok(sim.snapshot())
    }

    /// x of the present moment — the cup's right rim.
    pub fn track_length(&self) -> Result<f32, CoreError> {
        Ok(self.lock()?.terrain.length)
    }

    /// Geometry the renderer draws the hole from; fixed at construction.
    pub fn cup(&self) -> Result<GolfCup, CoreError> {
        Ok(self.lock()?.cup)
    }
}

// Outside the exported block: uniffi exports every method in an impl, rejects what it can't lower.
impl GolfWorld {
    fn lock(&self) -> Result<std::sync::MutexGuard<'_, Sim>, CoreError> {
        self.inner.lock().map_err(|_| internal("golf world lock poisoned"))
    }
}

/// Reject a tuning that would make the integrator meaningless before it reaches the step path.
fn validate_tuning(t: &GolfTuning) -> Result<(), CoreError> {
    let positive: [(&str, f32); 6] = [
        ("ball_radius", t.ball_radius),
        ("ball_mass", t.ball_mass),
        ("gravity", t.gravity),
        ("max_launch_speed", t.max_launch_speed),
        ("rest_speed", t.rest_speed),
        ("rest_hold_s", t.rest_hold_s),
    ];
    for (name, v) in positive {
        if !v.is_finite() || v <= 0.0 {
            return Err(dec(format!("tuning: {name} must be finite and > 0, got {v}")));
        }
    }
    let non_negative: [(&str, f32); 2] =
        [("friction", t.friction), ("rolling_damping", t.rolling_damping)];
    for (name, v) in non_negative {
        if !v.is_finite() || v < 0.0 {
            return Err(dec(format!("tuning: {name} must be finite and ≥ 0, got {v}")));
        }
    }
    if !t.restitution.is_finite() || !(0.0..=1.0).contains(&t.restitution) {
        return Err(dec(format!("tuning: restitution must be in [0, 1], got {}", t.restitution)));
    }
    if t.max_launch_speed > MAX_SPEED {
        return Err(dec(format!(
            "tuning: max_launch_speed must be ≤ {MAX_SPEED} m/s, got {}",
            t.max_launch_speed
        )));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    const GOLDEN: &str = include_str!("testdata/golf_golden.json");

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
        TerrainSpec { heights: vec![30.0; 801], dx: 1.0, world_height: 100.0 }
    }

    /// A jagged glucose excursion. Deterministic, no RNG.
    fn glucose_terrain() -> TerrainSpec {
        let heights: Vec<f32> = (0..601)
            .map(|i| {
                let x = i as f32;
                let slow = 18.0 * (x * 0.017).sin();
                let fast = 4.0 * (x * 0.11).sin();
                let jitter = 0.8 * ((i % 5) as f32 - 2.0);
                (44.0 + slow + fast + jitter).max(1.0)
            })
            .collect();
        TerrainSpec { heights, dx: 1.0, world_height: 100.0 }
    }

    fn world(spec: TerrainSpec) -> Arc<GolfWorld> {
        GolfWorld::new(spec, default_golf_tuning()).expect("world must build")
    }

    /// `frames` at a nominal 60 fps.
    fn run(w: &GolfWorld, frames: u32) -> BallState {
        let mut last = w.state().unwrap();
        for _ in 0..frames {
            last = w.step(1000.0 / 60.0).unwrap();
        }
        last
    }

    /// Steps until the ball settles again, or `cap` frames; the driver every shot test needs.
    fn settle(w: &GolfWorld, cap: u32) -> BallState {
        let mut last = w.state().unwrap();
        for _ in 0..cap {
            last = w.step(1000.0 / 60.0).unwrap();
            if last.at_rest || last.run != GolfRun::Playing {
                break;
            }
        }
        last
    }

    /// Sweeps putt speeds until one drops; the exact speed is a tuning detail, the drop is not.
    fn hole_out(w: &GolfWorld, from_x: f32) -> BallState {
        let mut last = w.state().unwrap();
        for speed in [14.0f32, 16.0, 18.0, 20.0, 22.0, 24.0, 26.0, 28.0, 30.0, 34.0, 38.0] {
            w.tee_at(from_x).unwrap();
            w.shoot(speed, 0.0).unwrap();
            last = settle(w, 1_800);
            if last.run == GolfRun::Holed {
                break;
            }
        }
        last
    }

    #[test]
    fn a_ball_rebounds_off_a_wall() {
        let wall = Obstacle { x: 60.0, half_w: 1.5, h: 30.0, lift: 0.0 };
        let w = GolfWorld::with_obstacles(flat(), default_golf_tuning(), vec![wall]).unwrap();
        w.tee_at(0.0).unwrap();
        w.shoot(40.0, 0.0).unwrap();
        let mut furthest = 0.0f32;
        let mut turned = false;
        for _ in 0..240 {
            let s = w.step(1000.0 / 60.0).unwrap();
            furthest = furthest.max(s.x);
            if s.vx < 0.0 {
                turned = true;
            }
        }
        assert!(furthest < 60.0, "reached {furthest} m, past the wall at 60");
        assert!(turned, "the wall must send the ball back");
    }

    #[test]
    fn a_lifted_box_lets_a_rolling_ball_under_it() {
        let canopy = Obstacle { x: 40.0, half_w: 6.0, h: 10.0, lift: 12.0 };
        let w = GolfWorld::with_obstacles(flat(), default_golf_tuning(), vec![canopy]).unwrap();
        w.tee_at(0.0).unwrap();
        w.shoot(40.0, 0.0).unwrap();
        let under = run(&w, 240);
        let free = world(flat());
        free.tee_at(0.0).unwrap();
        free.shoot(40.0, 0.0).unwrap();
        let open = run(&free, 240);
        assert!(under.x > 46.0, "stopped at {}: the canopy caught a ball on the ground", under.x);
        assert_close(under.x, open.x, 1e-3, "a box overhead must not touch the roll");
    }

    #[test]
    fn a_box_over_the_cup_is_dropped() {
        let cup = world(flat()).cup().unwrap();
        let over = Obstacle { x: 0.5 * (cup.x0 + cup.x1), half_w: 1.5, h: 30.0, lift: 0.0 };
        let w = GolfWorld::with_obstacles(flat(), default_golf_tuning(), vec![over]).unwrap();
        assert!(w.inner.lock().unwrap().blocks.is_empty(), "a box over the mouth must not stand");
        let last = hole_out(&w, cup.x0 - 25.0);
        assert_eq!(last.run, GolfRun::Holed, "the cup must still take a putt, last x = {}", last.x);
    }

    #[test]
    fn a_teed_ball_is_at_rest_and_unplayed() {
        let w = world(flat());
        let s = w.state().unwrap();
        assert!(s.at_rest, "a teed ball must be playable at once");
        assert!(!s.airborne);
        assert_eq!(s.strokes, 0);
        assert_eq!(s.penalties, 0);
        assert_eq!(s.run, GolfRun::Playing);
        let t = default_golf_tuning();
        assert_close(s.y, 30.0 + t.ball_radius, 0.05, "ride height");
    }

    #[test]
    fn it_rests_stably_on_flat_ground() {
        let w = world(flat());
        let start = w.state().unwrap();
        let s = run(&w, 300);
        assert_close(s.x, start.x, 0.2, "resting x drift");
        assert_close(s.y, start.y, 0.05, "resting y drift");
        assert!(s.at_rest, "must still read as settled");
        assert!(!s.airborne);
    }

    fn grade(g: f32) -> TerrainSpec {
        TerrainSpec { heights: (0..1201).map(|i| 400.0 - g * i as f32).collect(), dx: 1.0, world_height: 1_200.0 }
    }

    #[test]
    fn a_slow_ball_holds_on_a_grade_the_turf_can_hold() {
        let w = world(grade(0.3));
        w.tee_at(0.0).unwrap();
        let start = w.state().unwrap();
        let s = run(&w, 600);
        assert!(s.at_rest, "24° holds a 17° lie");
        assert!(s.x - start.x < 5.0, "crept {} m", s.x - start.x);
    }

    #[test]
    fn a_ball_rolls_off_a_grade_steeper_than_the_turf_holds() {
        let w = world(grade(0.6));
        w.tee_at(0.0).unwrap();
        let start = w.state().unwrap();
        let s = run(&w, 600);
        assert!(!s.at_rest, "31° must not hold");
        assert!(s.x - start.x > 5.0, "moved only {} m", s.x - start.x);
    }

    #[test]
    fn a_shot_carries_and_the_flight_is_drag_free() {
        let w = world(flat());
        let t = default_golf_tuning();
        let a = 45f32.to_radians();
        let v = t.max_launch_speed;
        let start = w.state().unwrap();
        let launched = w.shoot(v * a.cos(), v * a.sin()).unwrap();
        assert_eq!(launched.strokes, 1);
        assert!(!launched.at_rest, "a struck ball is not at rest");

        // Drag-free by contract: horizontal speed may not change until something is touched.
        let mut peak = start.y;
        let mut flight_vx = f32::NAN;
        for _ in 0..600 {
            let s = w.step(1000.0 / 60.0).unwrap();
            if s.y > peak {
                peak = s.y;
            }
            if s.airborne {
                if flight_vx.is_nan() {
                    flight_vx = s.vx;
                }
                assert_close(s.vx, flight_vx, 1e-3, "vx in flight");
            } else if !flight_vx.is_nan() {
                break;
            }
        }
        assert!(!flight_vx.is_nan(), "a full shot must leave the ground");
        // Under 1 %: one contact resolution as the ball leaves the turf, and nothing after it.
        assert!(
            (launched.vx - flight_vx).abs() < 0.01 * launched.vx,
            "the launch lost too much: {} -> {flight_vx}",
            launched.vx
        );
        // Apex of a 45° shot is a quarter of the flat carry: v²/4g.
        assert_close(peak - start.y, v * v / (4.0 * t.gravity), 8.0, "apex height");
        let s = settle(&w, 2_400);
        let carry = s.x - start.x;
        assert!(carry > 120.0, "a full shot must carry, travelled {carry} m");
        assert!(s.at_rest, "and must settle again");
    }

    #[test]
    fn the_launch_speed_is_capped_not_rejected() {
        let t = default_golf_tuning();
        let w = world(flat());
        let fast = w.shoot(10.0 * t.max_launch_speed, 0.0).unwrap();
        assert_eq!(fast.strokes, 1);
        let speed = (fast.vx * fast.vx + fast.vy * fast.vy).sqrt();
        assert_close(speed, t.max_launch_speed, 1e-3, "capped launch speed");
    }

    #[test]
    fn a_shot_keeps_its_direction_through_the_cap() {
        let t = default_golf_tuning();
        let w = world(flat());
        let s = w.shoot(-300.0, 400.0).unwrap();
        assert_close(s.vy / s.vx, -4.0 / 3.0, 1e-3, "direction through the cap");
        let speed = (s.vx * s.vx + s.vy * s.vy).sqrt();
        assert_close(speed, t.max_launch_speed, 1e-3, "capped");
    }

    #[test]
    fn shooting_a_moving_ball_is_ignored() {
        let w = world(flat());
        let first = w.shoot(30.0, 30.0).unwrap();
        assert_eq!(first.strokes, 1);
        run(&w, 10);
        let again = w.shoot(30.0, 30.0).unwrap();
        assert_eq!(again.strokes, 1, "a mid-flight shot must not count");
        let after = run(&w, 10);
        assert_eq!(after.strokes, 1);
    }

    #[test]
    fn a_zero_shot_is_not_a_stroke() {
        let w = world(flat());
        assert_eq!(w.shoot(0.0, 0.0).unwrap().strokes, 0);
        assert_eq!(w.shoot(f32::NAN, 1.0).unwrap().strokes, 0);
        assert_eq!(w.shoot(f32::INFINITY, f32::INFINITY).unwrap().strokes, 0);
        assert!(w.state().unwrap().at_rest, "and leaves the ball playable");
    }

    #[test]
    fn a_ball_rolled_into_the_cup_is_holed() {
        let w = world(flat());
        let cup = w.cup().unwrap();
        let teed = w.tee_at(cup.x0 - 25.0).unwrap();
        assert!(teed.x < cup.x0, "must tee short of the hole, x = {}", teed.x);
        let last = hole_out(&w, cup.x0 - 25.0);
        assert_eq!(last.run, GolfRun::Holed, "no putt in the sweep dropped, last x = {}", last.x);
        assert!(last.x >= cup.x0 && last.x <= cup.x1, "holed but at x = {}", last.x);
        assert!(last.y <= cup.rim_y, "holed but above the rim, y = {}", last.y);
        assert!(last.strokes >= 1);
    }

    #[test]
    fn a_holed_round_freezes() {
        let w = world(flat());
        let cup = w.cup().unwrap();
        let holed = hole_out(&w, cup.x0 - 25.0);
        assert_eq!(holed.run, GolfRun::Holed);
        let after = run(&w, 120);
        assert_eq!(after.run, GolfRun::Holed);
        assert_eq!(after.x, holed.x);
        assert_eq!(after.strokes, holed.strokes);
        // A terminal round refuses the club as well as the clock.
        assert_eq!(w.shoot(40.0, 40.0).unwrap().strokes, holed.strokes);
    }

    #[test]
    fn water_costs_a_penalty_and_drops_back() {
        // Solid for 200 m, a 90 m chasm, then solid again so the cup has ground to sit on.
        let mut heights = vec![40.0f32; 400];
        for h in heights.iter_mut().take(290).skip(200) {
            *h = -1.0; // gap marker
        }
        let w = world(TerrainSpec { heights, dx: 1.0, world_height: 100.0 });
        let teed = w.tee_at(150.0).unwrap();
        assert!(teed.x < 200.0, "must tee on the near bank, x = {}", teed.x);

        // Straight into the chasm, too flat to clear it.
        w.shoot(45.0, 6.0).unwrap();
        let mut last = teed;
        for _ in 0..3_000 {
            last = w.step(1000.0 / 60.0).unwrap();
            if last.penalties > 0 && last.at_rest {
                break;
            }
        }
        assert_eq!(last.penalties, 1, "a drowned ball must cost exactly one penalty");
        assert_eq!(last.strokes, 1, "and the shot itself is still one stroke");
        assert_close(last.x, teed.x, 0.5, "dropped back to the last rest point");
        assert!(last.at_rest, "and dropped back at rest, ready to play");
        assert_eq!(last.run, GolfRun::Playing);
    }

    #[test]
    fn the_left_run_off_cannot_be_driven_off() {
        let w = world(flat());
        w.tee_at(0.0).unwrap();
        w.shoot(-60.0, 5.0).unwrap();
        let s = settle(&w, 4_000);
        assert_eq!(s.run, GolfRun::Playing);
        assert_eq!(s.penalties, 0, "the left apron is ground, not water");
        assert!(s.x < 0.0, "should have run off the left of the start, x = {}", s.x);
        assert!(s.at_rest, "and must come to rest out there");
    }

    #[test]
    fn the_right_run_off_cannot_be_driven_off() {
        let w = world(flat());
        let cup = w.cup().unwrap();
        w.tee_at(cup.x0 - 60.0).unwrap();
        // Hard and flat: clears the cup entirely and lands on the apron past the hole.
        w.shoot(60.0, 20.0).unwrap();
        let s = settle(&w, 4_000);
        assert_eq!(s.penalties, 0, "past the hole is apron, not water");
        assert!(s.y > cup.rim_y - cup.depth, "should be on the apron, y = {}", s.y);
        assert!(s.at_rest);
    }

    #[test]
    fn identical_inputs_give_identical_states() {
        let script: Vec<f32> = (0..900u32).map(|i| 1000.0 / 60.0 + (i % 7) as f32 * 0.9).collect();
        let play = |script: &[f32]| {
            let w = world(glucose_terrain());
            w.tee_at(40.0).unwrap();
            w.shoot(42.0, 31.0).unwrap();
            let mut out = Vec::with_capacity(script.len());
            for &dt in script {
                out.push(w.step(dt).unwrap());
            }
            out
        };
        let a = play(&script);
        let b = play(&script);
        assert_eq!(a.len(), b.len());
        for (i, (x, y)) in a.iter().zip(b.iter()).enumerate() {
            assert_eq!(x, y, "frame {i} diverged: {x:?} vs {y:?}");
        }
    }

    #[test]
    fn a_settled_tee_reports_no_interpolation_artefact() {
        for w in [world(flat()), world(glucose_terrain())] {
            let teed = w.tee_at(0.0).unwrap();
            let mirrored = {
                let s = w.inner.lock().unwrap();
                assert_eq!(s.accumulator, 0.0, "a tee must leave nothing banked");
                s.pose()
            };
            assert_eq!(teed.x, mirrored.x);
            assert_eq!(teed.y, mirrored.y);
            assert_eq!(teed.angle, mirrored.angle);
            assert_eq!(w.state().unwrap(), teed, "state() must not drift from the tee");
        }
    }

    #[test]
    fn a_re_tee_clears_the_scorecard() {
        let w = world(glucose_terrain());
        w.shoot(40.0, 30.0).unwrap();
        let played = run(&w, 120);
        assert_eq!(played.strokes, 1);
        let fresh = w.reset().unwrap();
        assert_eq!(fresh.strokes, 0);
        assert_eq!(fresh.penalties, 0);
        assert_eq!(fresh.run, GolfRun::Playing);
        assert!(fresh.at_rest);
        assert_eq!(fresh.vx, 0.0);
        assert_eq!(fresh.vy, 0.0);
    }

    #[test]
    fn a_reset_replays_the_same_tee() {
        for w in [world(flat()), world(glucose_terrain())] {
            let first = w.tee_at(120.0).unwrap();
            for i in 0..10 {
                w.shoot(40.0, 30.0).unwrap();
                run(&w, 120);
                let again = w.reset().unwrap();
                assert_close(again.x, first.x, 1e-4, &format!("reset {i} walked the tee"));
                assert_close(again.y, first.y, 1e-4, &format!("reset {i} moved the lie"));
            }
        }
    }

    #[test]
    fn a_tee_past_the_hole_lands_short_of_the_lip() {
        let w = world(flat());
        let cup = w.cup().unwrap();
        for tap in [cup.x0, cup.x1, cup.x1 * 4.0, f32::MAX] {
            let s = w.tee_at(tap).expect("tee_at must accept any x");
            assert!(s.x < cup.x0, "teed at {} but the lip is {}", s.x, cup.x0);
            assert_eq!(s.run, GolfRun::Playing, "a fresh tee must not be Holed");
            let after = w.step(1000.0 / 60.0).unwrap();
            assert_eq!(after.run, GolfRun::Playing, "must survive its first simulated step");
        }
    }

    #[test]
    fn the_accumulator_makes_a_long_frame_bounded() {
        let w = world(flat());
        w.shoot(40.0, 30.0).unwrap();
        let before = w.state().unwrap();
        let s = w.step(10_000.0).unwrap();
        // Eight substeps of 1/120 s: a tenth of a second of flight, not ten seconds of it.
        assert!((s.x - before.x).abs() < 5.0, "one long frame moved {} m", s.x - before.x);
        assert!(s.x.is_finite() && s.y.is_finite());
    }

    #[test]
    fn rejects_a_degenerate_terrain() {
        let t = default_golf_tuning();
        let bad = [
            TerrainSpec { heights: vec![], dx: 1.0, world_height: 100.0 },
            TerrainSpec { heights: vec![30.0], dx: 1.0, world_height: 100.0 },
            TerrainSpec { heights: vec![30.0; 400], dx: 0.0, world_height: 100.0 },
            TerrainSpec { heights: vec![30.0; 400], dx: f32::NAN, world_height: 100.0 },
            TerrainSpec { heights: vec![30.0; 400], dx: 1.0, world_height: 0.0 },
            // All gap: nothing to cut a cup into.
            TerrainSpec { heights: vec![-1.0; 400], dx: 1.0, world_height: 100.0 },
            // Solid, but shorter than one cup.
            TerrainSpec { heights: vec![30.0; 4], dx: 1.0, world_height: 100.0 },
        ];
        for spec in bad {
            assert!(GolfWorld::new(spec.clone(), t).is_err(), "must reject {spec:?}");
        }
    }

    #[test]
    fn rejects_a_degenerate_tuning() {
        for mutate in [
            (|t: &mut GolfTuning| t.ball_radius = 0.0) as fn(&mut GolfTuning),
            |t: &mut GolfTuning| t.ball_radius = f32::NAN,
            |t: &mut GolfTuning| t.ball_mass = -1.0,
            |t: &mut GolfTuning| t.gravity = 0.0,
            |t: &mut GolfTuning| t.restitution = -0.1,
            |t: &mut GolfTuning| t.restitution = 1.5,
            |t: &mut GolfTuning| t.friction = f32::INFINITY,
            |t: &mut GolfTuning| t.rolling_damping = -1.0,
            |t: &mut GolfTuning| t.max_launch_speed = 0.0,
            |t: &mut GolfTuning| t.max_launch_speed = 1.0e9,
            |t: &mut GolfTuning| t.rest_speed = 0.0,
            |t: &mut GolfTuning| t.rest_hold_s = -1.0,
        ] {
            let mut t = default_golf_tuning();
            mutate(&mut t);
            assert!(GolfWorld::new(flat(), t).is_err(), "must reject tuning {t:?}");
        }
    }

    #[test]
    fn hostile_inputs_never_panic() {
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
            for &vx in &hostile {
                for &vy in &hostile {
                    let shot = w.shoot(vx, vy).unwrap();
                    assert!(shot.x.is_finite() && shot.y.is_finite());
                    let s = w.step(dt).unwrap();
                    assert!(s.x.is_finite(), "x went non-finite on dt={dt} vx={vx} vy={vy}");
                    assert!(s.y.is_finite() && s.angle.is_finite());
                    assert!(s.vx.is_finite() && s.vy.is_finite());
                    assert!(s.impact.is_finite() && s.impact >= 0.0);
                }
            }
            w.tee_at(0.0).unwrap();
        }
    }

    #[test]
    fn fuzz_never_panics() {
        // Deterministic xorshift, 60Hz panic=abort, nothing may abort. Free fns, not closures.
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

        let st = &mut 0x600F_BA11_DEAD_BEEFu64;
        for _ in 0..64 {
            let n = (xs(st) % 400) as usize + 2;
            let heights: Vec<f32> = (0..n)
                .map(|_| match xs(st) % 8 {
                    0 => xf(st),                         // anything, including NaN/inf
                    1 => -1.0,                           // gap
                    _ => (xs(st) % 10000) as f32 * 0.01, // 0..100 m of ground
                })
                .collect();
            let dx = match xs(st) % 4 {
                0 => xf(st),
                1 => 0.0,
                _ => 0.25 + (xs(st) % 400) as f32 * 0.01,
            };
            let spec = TerrainSpec { heights, dx, world_height: 100.0 };
            let w = match GolfWorld::new(spec, default_golf_tuning()) {
                Ok(w) => w,
                Err(CoreError::Decode { .. }) => continue,
                Err(e) => panic!("unexpected error variant: {e:?}"),
            };
            let _ = w.tee_at(xf(st));
            for _ in 0..400 {
                let dt = match xs(st) % 5 {
                    0 => xf(st),
                    1 => 0.0,
                    2 => 1e9,
                    _ => (xs(st) % 60) as f32,
                };
                if xs(st) % 32 == 0 {
                    let _ = w.shoot(xf(st), xf(st));
                }
                let s = w.step(dt).unwrap();
                assert!(s.x.is_finite() && s.y.is_finite() && s.angle.is_finite());
                assert!(s.impact.is_finite() && s.impact >= 0.0);
                if s.run != GolfRun::Playing {
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
        let w = GolfWorld::new(spec, default_golf_tuning()).unwrap();
        w.tee_at(g["tee_x"].as_f64().unwrap() as f32).unwrap();
        w.shoot(g["shot_vx"].as_f64().unwrap() as f32, g["shot_vy"].as_f64().unwrap() as f32)
            .unwrap();
        let tol = g["tolerance"].as_f64().unwrap() as f32;
        let mut frame = 0u32;
        for entry in g["frames"].as_array().unwrap() {
            let at = entry["frame"].as_u64().unwrap() as u32;
            while frame < at {
                w.step(1000.0 / 60.0).unwrap();
                frame += 1;
            }
            let s = w.state().unwrap();
            let want = |k: &str| entry[k].as_f64().unwrap() as f32;
            assert_close(s.x, want("x"), tol, &format!("frame {at} x"));
            assert_close(s.y, want("y"), tol, &format!("frame {at} y"));
            assert_close(s.vx, want("vx"), tol, &format!("frame {at} vx"));
            assert_close(s.vy, want("vy"), tol, &format!("frame {at} vy"));
            assert_close(s.angle, want("angle"), tol, &format!("frame {at} angle"));
            assert_eq!(s.strokes, entry["strokes"].as_u64().unwrap() as u32, "frame {at} strokes");
        }
    }

    /// Diagnostic. `cargo test -p t1dm-core carry_probe -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn carry_probe() {
        let t = default_golf_tuning();
        for deg in [20.0f32, 30.0, 40.0, 45.0, 50.0, 60.0, 70.0] {
            for frac in [0.4f32, 0.7, 1.0] {
                let w = world(flat());
                let start = w.state().unwrap();
                let a = deg.to_radians();
                let v = t.max_launch_speed * frac;
                w.shoot(v * a.cos(), v * a.sin()).unwrap();
                let mut peak = start.y;
                let mut land = start.x;
                let mut flight = 0u32;
                for i in 0..4_000 {
                    let s = w.step(1000.0 / 60.0).unwrap();
                    if s.y > peak {
                        peak = s.y;
                    }
                    if s.airborne {
                        land = s.x;
                        flight = i;
                    }
                    if s.at_rest {
                        break;
                    }
                }
                let s = w.state().unwrap();
                println!(
                    "{deg:>4.0}° v={v:>5.1}  carry {:>6.1} m  roll {:>6.1} m  apex {:>5.1} m  hang {:>4.2} s",
                    land - start.x,
                    s.x - land,
                    peak - start.y,
                    flight as f32 / 60.0,
                );
            }
        }
    }

    /// A true 1200 m of slope at any grade: a sample under 0 is a GAP, so the top rises with it.
    fn slope(grade: f32) -> TerrainSpec {
        let top = grade * 1_200.0 + 10.0;
        TerrainSpec {
            heights: (0..1201).map(|i| top - grade * i as f32).collect(),
            dx: 1.0,
            world_height: top + 100.0,
        }
    }

    #[test]
    fn a_steep_lie_rolls_away_at_pace() {
        let w = world(slope(0.6));
        w.tee_at(0.0).unwrap();
        let start = w.state().unwrap();
        let s = run(&w, 300);
        // Forced rolling off `vx` alone left this at ~2 m/s: minutes to leave a 31° lie.
        assert!(s.x - start.x >= 100.0, "31° rolled only {} m in 5 s", s.x - start.x);
    }

    /// Diagnostic. `cargo test -p t1dm-core rest_probe -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn rest_probe() {
        for grade in [0.0f32, 0.1, 0.2, 0.3, 0.45, 0.6, 0.9] {
            let w = GolfWorld::new(slope(grade), default_golf_tuning()).unwrap();
            w.tee_at(0.0).unwrap();
            let start = w.state().unwrap();
            let deg = grade.atan().to_degrees();
            let mut done = None;
            for i in 0..7_200 {
                let s = w.step(1000.0 / 60.0).unwrap();
                // Either terminus counts: a lie that holds settles, one that does not runs out.
                if (s.at_rest && i > 60) || s.run != GolfRun::Playing {
                    done = Some((i, s.x - start.x, s.run));
                    break;
                }
            }
            match done {
                Some((i, dist, run)) => println!(
                    "grade {grade:>4.2} ({deg:>4.1}°)  {} after {:>5.1} s, {dist:>7.1} m",
                    if run == GolfRun::Playing { "settled" } else { "holed out" },
                    i as f32 / 60.0
                ),
                None => println!(
                    "grade {grade:>4.2} ({deg:>4.1}°)  still rolling at 120.0 s, {:>7.1} m",
                    w.state().unwrap().x - start.x
                ),
            }
        }
    }

    /// Haptic thresholds. `cargo test -p t1dm-core impact_probe -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn impact_probe() {
        let t = default_golf_tuning();
        println!("weight {:.1} N·s per substep", t.ball_mass * t.gravity * FIXED_DT);
        for (name, spec) in [("flat", flat()), ("trace", glucose_terrain())] {
            for (shot, deg, frac) in
                [("putt", 0.0f32, 0.15f32), ("chip", 35.0, 0.45), ("drive", 45.0, 1.0)]
            {
                let w = world(spec.clone());
                w.tee_at(40.0).unwrap();
                let a = deg.to_radians();
                let v = t.max_launch_speed * frac;
                w.shoot(v * a.cos(), v * a.sin()).unwrap();
                let (mut land, mut roll) = (0.0f32, 0.0f32);
                // Frames since the ball last read airborne: the first few are the touchdown.
                let mut since = u32::MAX;
                for _ in 0..3_000 {
                    let s = w.step(1000.0 / 60.0).unwrap();
                    since = if s.airborne { 0 } else { since.saturating_add(1) };
                    if since <= 6 {
                        land = land.max(s.impact);
                    } else if since > 30 {
                        roll = roll.max(s.impact);
                    }
                    if s.at_rest {
                        break;
                    }
                }
                println!("{name:>5} {shot:>5} v={v:>5.1}  landing {land:>7.1}  rolling {roll:>7.1}");
            }
        }
    }

    /// Regen golden pin. `cargo test -p t1dm-core emit_golf_golden -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn emit_golf_golden() {
        let spec = glucose_terrain();
        let (tee_x, vx, vy) = (40.0f32, 42.0f32, 31.0f32);
        let w = GolfWorld::new(spec.clone(), default_golf_tuning()).unwrap();
        w.tee_at(tee_x).unwrap();
        w.shoot(vx, vy).unwrap();
        let mut rows = String::new();
        let mut frame = 0u32;
        for at in [0u32, 1, 15, 60, 150, 300, 600, 900, 1200, 1800] {
            while frame < at {
                w.step(1000.0 / 60.0).unwrap();
                frame += 1;
            }
            let s = w.state().unwrap();
            if !rows.is_empty() {
                rows.push_str(",\n");
            }
            rows.push_str(&format!(
                "  {{ \"frame\": {at}, \"x\": {:?}, \"y\": {:?}, \"vx\": {:?}, \"vy\": {:?}, \"angle\": {:?}, \"strokes\": {} }}",
                s.x, s.y, s.vx, s.vy, s.angle, s.strokes
            ));
        }
        let heights: Vec<String> = spec.heights.iter().map(|h| format!("{h:?}")).collect();
        println!(
            "{{\n \"_note\": \"Regression pin for t1dm-core::golf, generated by the #[ignore]d emit_golf_golden test. NOT an external oracle — see the module header.\",\n \"tolerance\": 0.001,\n \"tee_x\": {tee_x:?},\n \"shot_vx\": {vx:?},\n \"shot_vy\": {vy:?},\n \"terrain\": {{ \"dx\": {:?}, \"world_height\": {:?}, \"heights\": [{}] }},\n \"frames\": [\n{}\n ]\n}}",
            spec.dx,
            spec.world_height,
            heights.join(", "),
            rows
        );
    }
}
