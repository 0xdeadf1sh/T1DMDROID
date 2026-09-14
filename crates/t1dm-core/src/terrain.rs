//! Heightfield, collider polyline, run-off and solver settings shared by both minigame worlds.

use rapier2d::parry::shape::{Polyline, PolylineFlags};
use rapier2d::prelude::*;

use crate::CoreError;

/// 1/120 s ⇒ two substeps per 60 fps frame.
pub(crate) const FIXED_DT: f32 = 1.0 / 120.0;
/// Surplus dropped, not chased: a frame-loop spiral wedges the UI thread; a drop is just a hitch.
pub(crate) const MAX_SUBSTEPS: u32 = 8;
/// Longest frame delta honoured; a longer gap is truncated so a body cannot tunnel.
pub(crate) const MAX_FRAME_DT_S: f32 = 0.25;

/// ~200 km at 1 m spacing; bounds the `Vec` so a hostile size is `Err`, not an alloc abort.
pub(crate) const MAX_TERRAIN_SAMPLES: usize = 200_000;
/// The minimum that defines a segment.
pub(crate) const MIN_TERRAIN_SAMPLES: usize = 2;
/// Metres; past ~1e6 f32 loses precision, and an infinite AABB drops ground pairs (falls through).
pub(crate) const MAX_TRACK_LENGTH: f32 = 1.0e6;
/// Run-off past an end (m), uncrossable: the car's 50 s drag decay caps a rail 10 km back.
pub(crate) const RUN_OFF_LENGTH: f32 = 10_000.0;

/// m/s. Far above anything reachable; catches a divergence early.
pub(crate) const MAX_SPEED: f32 = 200.0;

/// rapier scales metre tolerances by this; car is ~28 m on 3.6 m wheels, 10× human-scale.
const LENGTH_UNIT: f32 = 10.0;
/// TGS-Soft substeps/step; rapier divides `dt` by this (integration, not relaxation): 8 ⇒ 960 Hz.
const SOLVER_SUBSTEPS: usize = 8;
/// Pre-`LENGTH_UNIT` ⇒ 0.20 m; at default, a fast wheel outruns the solver's look-ahead.
const PREDICTION_DISTANCE: f32 = 0.02;
/// Before the `LENGTH_UNIT` scale ⇒ 0.01 m, which is what a resting wheel actually sinks.
const ALLOWED_LINEAR_ERROR: f32 = 0.001;
/// Undoes warm-start inflation: accumulator folds in prior residual; steady state = `(N+1)/N`.
pub(crate) const IMPULSE_WARMSTART_BIAS: f32 = 8.0 / 9.0; // SOLVER_SUBSTEPS / (SOLVER_SUBSTEPS + 1)

/// Samples either side of the body.
const ROUGH_HALF_WINDOW: usize = 6;
/// Mean slope-change per sample at which roughness saturates to 1.
const ROUGH_REF: f32 = 0.5;

pub(crate) const PI: f32 = std::f32::consts::PI;
pub(crate) const TWO_PI: f32 = std::f32::consts::TAU;

#[inline]
pub(crate) fn dec(reason: impl Into<String>) -> CoreError {
    CoreError::Decode { reason: reason.into() }
}

#[inline]
pub(crate) fn internal(reason: impl Into<String>) -> CoreError {
    CoreError::Internal { reason: reason.into() }
}

/// Finite and non-negative is ground; anything else is a GAP.
#[inline]
pub(crate) fn solid(h: f32) -> bool {
    h.is_finite() && h >= 0.0
}

/// Saturate a caller-supplied control. Non-finite ⇒ 0, released.
#[inline]
pub(crate) fn sane01(v: f32) -> f32 {
    if v.is_finite() {
        v.clamp(0.0, 1.0)
    } else {
        0.0
    }
}

/// To (−π, π]. Left unbounded across many flips, `ang` loses its fraction in f32.
#[inline]
pub(crate) fn wrap_pi(a: f32) -> f32 {
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
pub(crate) fn lerp(a: f32, b: f32, t: f32) -> f32 {
    a + (b - a) * t
}

/// SHORT way round: naive lerp across the wrap seam takes a whole-turn arc in one frame.
#[inline]
pub(crate) fn lerp_angle(a: f32, b: f32, t: f32) -> f32 {
    a + wrap_pi(b - a) * t
}

/// One integration setup: both worlds are the same scale, so both are solved the same way.
pub(crate) fn solver_params() -> IntegrationParameters {
    IntegrationParameters {
        dt: FIXED_DT,
        length_unit: LENGTH_UNIT,
        num_solver_iterations: SOLVER_SUBSTEPS,
        normalized_prediction_distance: PREDICTION_DISTANCE,
        normalized_allowed_linear_error: ALLOWED_LINEAR_ERROR,
        max_ccd_substeps: 0,
        ..Default::default()
    }
}

/// `heights[i]` at `x=i·dx`; negative/non-finite = GAP (no ground). world_height sets kill plane.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TerrainSpec {
    pub heights: Vec<f32>,
    pub dx: f32,
    pub world_height: f32,
}

/// Raw heightfield, gap markers intact (collider's copy is sanitised); ground-vs-dropout reads it.
pub(crate) struct Terrain {
    pub(crate) heights: Vec<f32>,
    pub(crate) dx: f32,
    pub(crate) inv_dx: f32,
    /// x of the last sample; reaching it is the end of the world.
    pub(crate) length: f32,
    /// Falling below this ends the run (one world-height under the floor).
    pub(crate) kill_y: f32,
}

impl Terrain {
    /// Ground height at `x`, `None` at a gap. Indices clamped: no `x`, even ±inf, can slice OOB.
    #[inline]
    pub(crate) fn sample(&self, x: f32) -> Option<f32> {
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

    /// Mean |2nd diff| over a window at `x`, per `dx`, normalised [0,1]; gaps skipped, not cliffs.
    pub(crate) fn roughness(&self, x: f32) -> f32 {
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
        // Only signal past both firewalls; crosses FFI as [0,1] — coerceIn won't catch a NaN.
        let r = (acc / cnt as f32) * self.inv_dx / ROUGH_REF;
        if r.is_finite() {
            r.clamp(0.0, 1.0)
        } else {
            0.0
        }
    }

    /// Index of the last solid sample; `None` when the whole field is gap.
    pub(crate) fn last_solid(&self) -> Option<usize> {
        self.heights.iter().rposition(|h| solid(*h))
    }

    /// Start of the first run of >`span` solid samples, from `from`; `None` if none left.
    pub(crate) fn first_run_from(&self, from: usize, span: usize) -> Option<usize> {
        let n = self.heights.len();
        let mut run = 0usize;
        for i in from..n {
            if solid(self.heights[i]) {
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
}

/// Load-bearing ground contact. NOT `has_any_active_contact` (true, no force); tol=speculative.
pub(crate) fn touching(np: &NarrowPhase, col: ColliderHandle, tol: f32) -> bool {
    np.contact_pairs_with(col).any(|pair| {
        pair.total_impulse_magnitude() > 0.0
            || pair.find_deepest_contact().is_some_and(|(_, c)| c.dist <= tol)
    })
}

/// Total normal impulse over step (N·s); a ball straddles segments, so manifolds fold together.
pub(crate) fn normal_impulse(np: &NarrowPhase, col: ColliderHandle) -> f32 {
    np.contact_pairs_with(col).map(|pair| pair.total_impulse_magnitude()).sum()
}

/// Polyline (not heightfield) avoids a kicked-airborne ball at seams; a gap poisons the AABB.
pub(crate) fn build_ground(raw: &[f32], dx: f32) -> Option<SharedShape> {
    build_ground_to(raw, dx, raw.len(), &[])
}

/// As [`build_ground`] but over the first `upto` samples, then chaining `tail` left-to-right on.
pub(crate) fn build_ground_to(
    raw: &[f32],
    dx: f32,
    upto: usize,
    tail: &[Vector],
) -> Option<SharedShape> {
    let raw = &raw[..upto.min(raw.len())];
    let seed = raw.iter().copied().find(|h| solid(*h))?;
    let mut carry = seed;
    let mut verts = Vec::with_capacity(raw.len() + tail.len() + 1);
    for (i, &h) in raw.iter().enumerate() {
        if solid(h) {
            carry = h;
        }
        verts.push(Vector::new(i as f32 * dx, carry));
    }
    let mut idx: Vec<[u32; 2]> = Vec::with_capacity(raw.len() + tail.len() + 1);
    for i in 0..raw.len() - 1 {
        if solid(raw[i]) && solid(raw[i + 1]) {
            idx.push([i as u32 + 1, i as u32]);
        }
    }
    // Chained unconditionally: the caller cut `upto` at a solid sample precisely to hang this on.
    let mut prev = (raw.len() - 1) as u32;
    for v in tail {
        let k = verts.len() as u32;
        verts.push(*v);
        idx.push([k, prev]);
        prev = k;
    }
    // Run-off: `Terrain::sample` clamps its index, reporting flat ground left of the start forever.
    if solid(raw[0]) {
        let apron = verts.len() as u32;
        verts.push(Vector::new(-RUN_OFF_LENGTH, raw[0]));
        idx.push([0, apron]);
    }
    if idx.is_empty() {
        return None;
    }
    Some(oriented(verts, idx))
}

/// Segments run right-to-left, so the outward normal `(dir.y, −dir.x)` points up over flat ground.
pub(crate) fn oriented(verts: Vec<Vector>, idx: Vec<[u32; 2]>) -> SharedShape {
    SharedShape::new(Polyline::with_flags(verts, Some(idx), PolylineFlags::ORIENTED))
}

/// The one gate a caller-supplied heightfield passes; both worlds' constructors go through it.
pub(crate) fn validate_terrain(terrain: TerrainSpec) -> Result<Terrain, CoreError> {
    let n = terrain.heights.len();
    if n < MIN_TERRAIN_SAMPLES {
        return Err(dec(format!("terrain: {n} samples (need ≥ {MIN_TERRAIN_SAMPLES})")));
    }
    if n > MAX_TERRAIN_SAMPLES {
        return Err(dec(format!("terrain: {n} samples (max {MAX_TERRAIN_SAMPLES})")));
    }
    // Reciprocal must be finite: sub-normal dx overflows inv_dx, 0*inf=NaN; clamp misses it.
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
    let dx = terrain.dx;
    Ok(Terrain {
        inv_dx: 1.0 / dx,
        length,
        kill_y: -terrain.world_height,
        heights: terrain.heights,
        dx,
    })
}
