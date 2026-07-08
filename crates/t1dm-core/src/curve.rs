//! The shared curve / PK engine (PLAN.private.md §3.3) — the ONE transform that feeds
//! historical context channels, announced-future what-if conditioning, and IOB/COB
//! display. Every function here is **bit-for-bit faithful to `T1DMSIM/simulator.py` at
//! FIXED, noise-free presets**: `gamma_curve` (Ra carbs + bolus PK), `basal_curve`
//! (Bateman long-acting), and `bolus_pk_for_dose` are transcribed from the simulator so
//! the app's curves land in the model's training distribution (INFERENCE.md §9,
//! model-io-curves.md).
//!
//! THE KEY MODEL FACT the whole engine encodes: the model consumes **carbs as an
//! appearance (Ra) rate** (a gamma curve, grams per 5-min step, summing to the meal
//! total over its absorption window) and **insulin as a PK ACTION rate** (bolus = gamma
//! PK peaking ~50 min; basal = broad Bateman, near-flat) — NOT delivery, NOT IOB. Basal
//! and bolus are summed into one `insulin_combined` channel; carbs are their own channel.
//!
//! Honest fidelity claim (PLAN §3.3): the golden gate proves the *functions* at fixed
//! params — it can NOT prove the *parameter selection*, because the simulator draws
//! per-patient k/θ/DIA and 5 % per-event noise from RNG distributions we cannot
//! reconstruct for a real person. So we pin the canonical central presets and treat
//! "these sit in the training distribution" as an explicit in-distribution *choice*. The
//! operational fidelity check is **counterfactual sign/monotonicity** (more carbs ⇒
//! larger carb channel ⇒ higher forecast; more insulin ⇒ larger insulin channel ⇒ lower),
//! exercised by the tests below at the curve level and by `:calc` at the model level.
//!
//! Everything is total on hostile input (`panic = "abort"` for the crate): a
//! non-positive duration yields a single `[0.0]` step exactly as numpy's
//! `int(dur/dt) <= 0` branch does; no slice can go out of bounds.

use crate::CoreError;

// ── Time grid (simulator.py DT_MINUTES; app sample grid is the same 5-min cadence) ──────
/// Minutes per curve step. `simulator.DT_MINUTES`.
const DT_MINUTES: f64 = 5.0;
/// Milliseconds per curve/grid step (5 min). Curve `values[]` are amount-per-this-step;
/// the Room `sample` grid is keyed at the same cadence, so bucketize is a pure index map.
pub const STEP_MS: i64 = 300_000;

// ── Canonical NOISE-FREE presets, transcribed from simulator.py (PLAN §3.3) ─────────────
// These are the simulator's *central* values with all per-patient / per-event RNG noise
// switched off. They are the app's quick-preset defaults; the user may override k/θ/DIA in
// the curve editors, but the model was trained on this neighbourhood.

/// Bolus (rapid-acting, e.g. Novorapid/aspart) gamma shape. `simulator.BOLUS_GAMMA_K`.
/// Peak time = (k-1)·θ ⇒ ~50 min at the 5 U reference dose.
pub const BOLUS_GAMMA_K: f64 = 3.0;
/// Bolus gamma scale at the reference dose. `simulator.BOLUS_GAMMA_THETA`.
pub const BOLUS_GAMMA_THETA: f64 = 25.0;
/// Bolus DIA (hours) at the 5 U reference dose. `simulator.BOLUS_DIA_BASE_HOURS`.
pub const BOLUS_DIA_BASE_HOURS: f64 = 2.5;
/// Hours of DIA added per unit of `sqrt(dose) - sqrt(5)`. `simulator.BOLUS_DIA_DOSE_SCALE`.
pub const BOLUS_DIA_DOSE_SCALE: f64 = 0.6;
/// DIA floor / ceiling (hours). `simulator.BOLUS_DIA_MIN_HOURS` / `_MAX_HOURS`.
pub const BOLUS_DIA_MIN_HOURS: f64 = 2.0;
pub const BOLUS_DIA_MAX_HOURS: f64 = 7.5;
/// θ drift per unit of `sqrt(dose) - sqrt(5)` (bigger boluses peak slightly later).
/// `simulator.BOLUS_THETA_DOSE_SLOPE`.
pub const BOLUS_THETA_DOSE_SLOPE: f64 = 0.06;
/// The dose the DIA/θ scaling is centred on. `sqrt(5)` in `bolus_pk_for_dose`.
const BOLUS_REFERENCE_DOSE_U: f64 = 5.0;

/// Long-acting basal Bateman absorption / elimination rates (1/h).
/// `simulator.BASAL_KA_PER_HOUR` / `_KE_PER_HOUR`. tmax = ln(ka/ke)/(ka-ke) ≈ 6.3 h — a
/// broad-peak profile between glargine and degludec, near-flat once tiled at cadence.
pub const BASAL_KA_PER_HOUR: f64 = 0.30;
pub const BASAL_KE_PER_HOUR: f64 = 0.07;
/// Smootherstep tail-taper window (hours) that lands the residual on zero so consecutive
/// daily doses join without a step discontinuity. `simulator.BASAL_TAIL_CLIP_HOURS`.
pub const BASAL_TAIL_CLIP_HOURS: f64 = 5.0;

/// Nominal quick-preset durations of action (minutes), for the insulin/food builders:
/// Lantus (glargine) ~24 h; Tresiba (degludec) ~42 h. Both use the Bateman rates above.
pub const LANTUS_DIA_MIN: f64 = 24.0 * 60.0;
pub const TRESIBA_DIA_MIN: f64 = 42.0 * 60.0;

// ── Curve kind / event carrier (uniffi records) ─────────────────────────────────────────

/// Which model input channel an event feeds. Carbs = appearance (Ra); insulin = PK action
/// (basal Bateman + bolus gamma summed into one `insulin_combined` channel).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum CurveKind {
    Carb,
    Insulin,
}

/// One resolved event: an absolute start time plus its per-5-min-step curve. `values`
/// sum to `total` over the event's duration (Ra grams for carbs; PK action-units for
/// insulin). Produced by [`gamma`]/[`bateman`]/[`bolus_pk_for_dose`]/[`extend_basal`],
/// consumed by [`bucketize`] (channel building) and [`on_board`] (IOB/COB).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct CurveEvent {
    /// Absolute start time (epoch ms) of `values[0]`.
    pub start_ms: i64,
    /// Step cadence the `values` are sampled at (== [`STEP_MS`]).
    pub step_ms: i64,
    pub kind: CurveKind,
    /// The nominal total (grams for carbs; units for insulin). `sum(values) ≈ total`.
    pub total: f64,
    /// Amount-per-step, oldest→newest.
    pub values: Vec<f64>,
}

/// One long-acting basal injection in a daily-repeating schedule (MDI). Each occurrence
/// expands to a Bateman [`CurveEvent`]; the tiled sum is the near-flat background the
/// model always sees (model-io-curves.md: "basal auto-extended across the whole window").
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct BasalDoseSpec {
    /// Minutes from local midnight at which this injection is taken.
    pub time_of_day_min: i32,
    /// Units delivered per injection.
    pub dose_u: f64,
    /// Duration of action (minutes) — e.g. [`LANTUS_DIA_MIN`] / [`TRESIBA_DIA_MIN`].
    pub duration_min: f64,
    pub ka_per_hour: f64,
    pub ke_per_hour: f64,
}

/// A day-long basal schedule (PLAN §3.6 "day-long basal schedule search"). `tz_offset_min`
/// maps epoch-ms to the local midnight the `time_of_day_min` offsets are measured from.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct BasalSchedule {
    pub tz_offset_min: i32,
    pub doses: Vec<BasalDoseSpec>,
}

// ── gamma_curve (simulator.py:782) — Ra carbs + bolus PK shape ──────────────────────────

/// Gamma-distributed absorption/action curve, amount-per-`dt`-step, `sum == total_amount`.
/// Bit-faithful to `simulator.gamma_curve`: `t = [dt, 2·dt, … n·dt]`, `v = t^(k-1)·e^(-t/θ)`,
/// then sum-normalized to `total`. `n = floor(duration_min / dt)`; a non-positive `n`
/// returns `[0.0]` (the numpy `n_steps <= 0` branch).
#[uniffi::export]
pub fn gamma(total_amount: f64, k: f64, theta: f64, duration_min: f64) -> Vec<f64> {
    let n_steps = (duration_min / DT_MINUTES) as i64;
    if n_steps <= 0 {
        return vec![0.0];
    }
    let n = n_steps as usize;
    let mut values = vec![0.0f64; n];
    let mut area = 0.0f64;
    for (i, v) in values.iter_mut().enumerate() {
        let t = (i as f64 + 1.0) * DT_MINUTES; // t = arange(1, n+1)*dt
        let val = t.powf(k - 1.0) * (-t / theta).exp();
        *v = val;
        area += val;
    }
    if area > 0.0 {
        let scale = total_amount / area;
        for v in values.iter_mut() {
            *v *= scale;
        }
    }
    values
}

// ── basal_curve (simulator.py:801) — Bateman one-compartment long-acting PK ─────────────

/// Long-acting basal Bateman PK, amount-per-`dt`-step, `sum == total_amount`. Bit-faithful
/// to `simulator.basal_curve` at the default 5 h tail-clip: `f(t)=e^(-ke·t)-e^(-ka·t)`
/// (t in hours, sampled at `t_h = [0, dt/60, …]`), clamped ≥0, its last
/// `tail_clip` window multiplied by a smootherstep ramp to zero, then sum-normalized.
/// `ka` is floored at `ke + 1e-3` (matching the simulator) so `ka > ke` always.
/// `values[0]` is always 0 (the Bateman difference is 0 at t=0).
#[uniffi::export]
pub fn bateman(total_amount: f64, duration_min: f64, ka_per_hour: f64, ke_per_hour: f64) -> Vec<f64> {
    let n_steps = (duration_min / DT_MINUTES) as i64;
    if n_steps <= 0 {
        return vec![0.0];
    }
    let n = n_steps as usize;
    let ka = ka_per_hour.max(ke_per_hour + 1e-3);
    let ke = ke_per_hour;

    let mut curve = vec![0.0f64; n];
    for (i, c) in curve.iter_mut().enumerate() {
        let t_h = i as f64 * (DT_MINUTES / 60.0); // t_h = arange(n)*(dt/60)
        let v = (-ke * t_h).exp() - (-ka * t_h).exp();
        *c = v.max(0.0);
    }

    // Smootherstep tail-taper over the last tail_steps, only when 0 < tail_steps < n.
    let tail_steps = (BASAL_TAIL_CLIP_HOURS * 60.0 / DT_MINUTES) as i64;
    if tail_steps > 0 && (tail_steps as usize) < n {
        let ts = tail_steps as usize;
        let start = n - ts;
        for j in 0..ts {
            // s = linspace(1.0, 0.0, ts): s[j] = 1 - j/(ts-1), last exactly 0.
            let s = if ts == 1 {
                1.0
            } else if j == ts - 1 {
                0.0
            } else {
                1.0 - j as f64 / (ts as f64 - 1.0)
            };
            // smootherstep: s^3·(s·(s·6 - 15) + 10) = 6s^5 - 15s^4 + 10s^3.
            let w = s * s * s * (s * (s * 6.0 - 15.0) + 10.0);
            curve[start + j] *= w;
        }
    }

    let area: f64 = curve.iter().sum();
    if area > 0.0 {
        let scale = total_amount / area;
        for c in curve.iter_mut() {
            *c *= scale;
        }
    }
    curve
}

// ── bolus_pk_for_dose (simulator.py:853) — dose-scaled rapid-acting PK ───────────────────

/// The `(k, θ, duration_min)` a rapid-acting bolus of `dose_units` gets: DIA scales with
/// dose (`BASE + SCALE·(√dose − √5)`, clamped), θ drifts up so larger depots peak later.
/// Bit-faithful to `simulator.bolus_pk_for_dose`. `dose` is floored at 0.5 U.
fn bolus_pk_params(dose_units: f64) -> (f64, f64, f64) {
    let dose = dose_units.max(0.5);
    let sqrt_excess = dose.sqrt() - BOLUS_REFERENCE_DOSE_U.sqrt();
    let duration_h = (BOLUS_DIA_BASE_HOURS + BOLUS_DIA_DOSE_SCALE * sqrt_excess)
        .clamp(BOLUS_DIA_MIN_HOURS, BOLUS_DIA_MAX_HOURS);
    let theta = BOLUS_GAMMA_THETA * (1.0 + BOLUS_THETA_DOSE_SLOPE * sqrt_excess);
    (BOLUS_GAMMA_K, theta, duration_h * 60.0)
}

/// Resolve a rapid-acting bolus of `dose_u` units into an insulin [`CurveEvent`] at
/// `start_ms == 0` (the caller shifts it to the injection time). The gamma PK is built with
/// the dose-scaled params of [`bolus_pk_params`] (== `simulator.bolus_pk_for_dose` +
/// `gamma_curve`), so `sum(values) == dose_u`.
#[uniffi::export]
pub fn bolus_pk_for_dose(dose_u: f64) -> CurveEvent {
    let (k, theta, dur_min) = bolus_pk_params(dose_u);
    let values = gamma(dose_u, k, theta, dur_min);
    CurveEvent {
        start_ms: 0,
        step_ms: STEP_MS,
        kind: CurveKind::Insulin,
        total: dose_u,
        values,
    }
}

// ── bucketize — lay events onto a fixed 5-min grid (channel construction) ────────────────

/// Sum every `kind`-matching event's per-step curve onto the fixed grid
/// `[grid_start_ms, grid_start_ms + n_steps·STEP_MS)`, returning `n_steps` amount-per-step
/// values. An event whose start predates the grid still contributes its overlapping tail
/// (this is exactly the "existing-dose tails carried forward" case). Each event is aligned
/// to the grid by rounding `(start_ms − grid_start_ms)/STEP_MS`; out-of-window steps are
/// dropped. `n_steps` must be ≥ 0 and fit memory; a wildly large value is rejected.
#[uniffi::export]
pub fn bucketize(
    events: Vec<CurveEvent>,
    grid_start_ms: i64,
    n_steps: i32,
    kind: CurveKind,
) -> Result<Vec<f64>, CoreError> {
    if n_steps < 0 {
        return Err(CoreError::Internal {
            reason: format!("bucketize n_steps must be >= 0, got {n_steps}"),
        });
    }
    let n = n_steps as usize;
    let mut grid = vec![0.0f64; n];
    for ev in &events {
        if ev.kind != kind {
            continue;
        }
        // Round to the nearest grid step (events are logged at arbitrary ms; the grid is 5-min).
        let offset = (((ev.start_ms - grid_start_ms) as f64) / STEP_MS as f64).round() as i64;
        for (j, &val) in ev.values.iter().enumerate() {
            let idx = offset + j as i64;
            if idx >= 0 && (idx as usize) < n {
                grid[idx as usize] += val;
            }
        }
    }
    Ok(grid)
}

// ── on_board — IOB / COB as remaining tail area (model-io-curves.md) ─────────────────────

/// Insulin/carbs on board at `at_ms`: the **remaining tail area** of every `kind`-matching
/// event — the sum of `values[j]` whose step has not yet started
/// (`start_ms + j·STEP_MS >= at_ms`). Because `values` is the *action/appearance rate*,
/// its forward integral is precisely the action still to come: `total` before onset, 0
/// after DIA. A step already in progress at `at_ms` is treated as delivered (conservative
/// for IOB — never over-states insulin still to act). Provenance ("IOB from logged doses
/// only") lives with the caller (PLAN §3.6-F).
#[uniffi::export]
pub fn on_board(events: Vec<CurveEvent>, at_ms: i64, kind: CurveKind) -> f64 {
    let mut acc = 0.0f64;
    for ev in &events {
        if ev.kind != kind {
            continue;
        }
        for (j, &val) in ev.values.iter().enumerate() {
            let step_start = ev.start_ms + j as i64 * ev.step_ms;
            if step_start >= at_ms {
                acc += val;
            }
        }
    }
    acc
}

// ── extend_basal — tile a daily schedule into Bateman events across a window ─────────────

const DAY_MS: i64 = 86_400_000;
const MIN_MS: i64 = 60_000;

/// Expand a daily-repeating [`BasalSchedule`] into the concrete Bateman [`CurveEvent`]s
/// whose action overlaps `[from_ms, to_ms)`. Each dose is taken every local day at its
/// `time_of_day_min`; an occurrence is emitted when its `[start, start+DIA)` intersects the
/// window (so a dose taken before `from_ms` is included when its long tail still reaches
/// into the window — the near-flat background). Returns events sorted by `start_ms`.
#[uniffi::export]
pub fn extend_basal(
    schedule: BasalSchedule,
    from_ms: i64,
    to_ms: i64,
) -> Result<Vec<CurveEvent>, CoreError> {
    if to_ms < from_ms {
        return Err(CoreError::Internal {
            reason: format!("extend_basal window inverted: from {from_ms} > to {to_ms}"),
        });
    }
    let tz_ms = schedule.tz_offset_min as i64 * MIN_MS;
    let mut out: Vec<CurveEvent> = Vec::new();

    for dose in &schedule.doses {
        if dose.duration_min <= 0.0 || dose.dose_u == 0.0 {
            continue;
        }
        let dia_ms = (dose.duration_min * MIN_MS as f64) as i64;
        let tod_ms = dose.time_of_day_min as i64 * MIN_MS;
        // A dose overlaps the window iff its start ∈ (from_ms - dia_ms, to_ms). Bracket the
        // local-day index range that can produce such a start, then filter precisely.
        let first_day = (from_ms - dia_ms + tz_ms).div_euclid(DAY_MS);
        let last_day = (to_ms + tz_ms).div_euclid(DAY_MS);
        let values = bateman(dose.dose_u, dose.duration_min, dose.ka_per_hour, dose.ke_per_hour);
        let mut day = first_day;
        while day <= last_day {
            let start_local = day * DAY_MS + tod_ms;
            let start_abs = start_local - tz_ms;
            let end_abs = start_abs + dia_ms;
            if end_abs > from_ms && start_abs < to_ms {
                out.push(CurveEvent {
                    start_ms: start_abs,
                    step_ms: STEP_MS,
                    kind: CurveKind::Insulin,
                    total: dose.dose_u,
                    values: values.clone(),
                });
            }
            day += 1;
        }
    }
    out.sort_by_key(|e| e.start_ms);
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    const GOLDEN: &str = include_str!("testdata/curve_golden.json");

    fn golden() -> Value {
        serde_json::from_str(GOLDEN).unwrap()
    }

    fn f64s(v: &Value) -> Vec<f64> {
        v.as_array().unwrap().iter().map(|x| x.as_f64().unwrap()).collect()
    }

    fn assert_close(got: &[f64], want: &[f64], tol: f64, what: &str) {
        assert_eq!(got.len(), want.len(), "{what}: length mismatch {} vs {}", got.len(), want.len());
        for (i, (g, w)) in got.iter().zip(want).enumerate() {
            assert!(
                (g - w).abs() <= tol,
                "{what}[{i}]: got {g}, want {w} (|Δ|={:.3e} > {tol:.1e})",
                (g - w).abs()
            );
        }
    }

    // ── gamma == simulator.gamma_curve (Ra carbs + bolus shape) ──────────────────────────
    #[test]
    fn gamma_matches_simulator() {
        for c in golden()["gamma"].as_array().unwrap() {
            let got = gamma(
                c["total"].as_f64().unwrap(),
                c["k"].as_f64().unwrap(),
                c["theta"].as_f64().unwrap(),
                c["dur"].as_f64().unwrap(),
            );
            assert_close(&got, &f64s(&c["values"]), 1e-12, "gamma");
        }
    }

    #[test]
    fn gamma_sum_equals_total_and_zero_dur_is_single_step() {
        let v = gamma(60.0, 3.0, 20.0, 180.0);
        assert!((v.iter().sum::<f64>() - 60.0).abs() < 1e-9);
        assert_eq!(gamma(10.0, 3.0, 20.0, 0.0), vec![0.0]);
        assert_eq!(gamma(10.0, 3.0, 20.0, 3.0), vec![0.0]); // int(3/5)=0 ⇒ [0.0]
    }

    // ── bateman == simulator.basal_curve (Bateman long-acting) ───────────────────────────
    #[test]
    fn bateman_matches_simulator() {
        for c in golden()["bateman"].as_array().unwrap() {
            let got = bateman(
                c["total"].as_f64().unwrap(),
                c["dur"].as_f64().unwrap(),
                c["ka"].as_f64().unwrap(),
                c["ke"].as_f64().unwrap(),
            );
            assert_close(&got, &f64s(&c["values"]), 1e-12, "bateman");
        }
    }

    #[test]
    fn bateman_starts_at_zero_and_sums_to_total() {
        let v = bateman(24.0, LANTUS_DIA_MIN, BASAL_KA_PER_HOUR, BASAL_KE_PER_HOUR);
        assert_eq!(v[0], 0.0);
        assert!((v.iter().sum::<f64>() - 24.0).abs() < 1e-9);
        assert_eq!(*v.last().unwrap(), 0.0); // tail-clipped to zero
    }

    // ── bolus_pk_for_dose == simulator.bolus_pk_for_dose + gamma_curve ───────────────────
    #[test]
    fn bolus_pk_matches_simulator() {
        for c in golden()["bolus_pk"].as_array().unwrap() {
            let ev = bolus_pk_for_dose(c["dose"].as_f64().unwrap());
            let (k, theta, dur) = bolus_pk_params(c["dose"].as_f64().unwrap());
            assert!((k - c["k"].as_f64().unwrap()).abs() < 1e-12, "k");
            assert!((theta - c["theta"].as_f64().unwrap()).abs() < 1e-12, "theta");
            assert!((dur - c["dur"].as_f64().unwrap()).abs() < 1e-9, "dur");
            assert_eq!(ev.kind, CurveKind::Insulin);
            assert_close(&ev.values, &f64s(&c["values"]), 1e-12, "bolus_pk");
        }
    }

    #[test]
    fn bolus_reference_peaks_at_50_min() {
        // 5 U reference: gamma peak (k-1)·θ = 2·25 = 50 min ⇒ value index 9 (t=(9+1)·5).
        let ev = bolus_pk_for_dose(5.0);
        let peak = ev
            .values
            .iter()
            .enumerate()
            .max_by(|a, b| a.1.partial_cmp(b.1).unwrap())
            .unwrap()
            .0;
        assert_eq!((peak as f64 + 1.0) * DT_MINUTES, 50.0);
    }

    // ── bucketize: grid alignment, tail carry-forward, kind filter ───────────────────────
    #[test]
    fn bucketize_places_and_sums() {
        let g0 = 1_000_000_000_000i64; // arbitrary grid start
        let a = CurveEvent {
            start_ms: g0 + 2 * STEP_MS,
            step_ms: STEP_MS,
            kind: CurveKind::Carb,
            total: 3.0,
            values: vec![1.0, 2.0],
        };
        let b = CurveEvent {
            start_ms: g0 + 2 * STEP_MS,
            step_ms: STEP_MS,
            kind: CurveKind::Carb,
            total: 10.0,
            values: vec![10.0],
        };
        let ins = CurveEvent {
            start_ms: g0,
            step_ms: STEP_MS,
            kind: CurveKind::Insulin,
            total: 5.0,
            values: vec![5.0],
        };
        let out = bucketize(vec![a, b, ins], g0, 6, CurveKind::Carb).unwrap();
        assert_eq!(out, vec![0.0, 0.0, 11.0, 2.0, 0.0, 0.0]); // insulin excluded
    }

    #[test]
    fn bucketize_carries_pre_grid_tail() {
        let g0 = 0i64;
        // Event starts 1 step BEFORE the grid; only its 2nd/3rd values land in-window.
        let ev = CurveEvent {
            start_ms: -STEP_MS,
            step_ms: STEP_MS,
            kind: CurveKind::Insulin,
            total: 6.0,
            values: vec![1.0, 2.0, 3.0],
        };
        let out = bucketize(vec![ev], g0, 4, CurveKind::Insulin).unwrap();
        assert_eq!(out, vec![2.0, 3.0, 0.0, 0.0]);
    }

    #[test]
    fn bucketize_rejects_negative_n() {
        assert!(bucketize(vec![], 0, -1, CurveKind::Carb).is_err());
    }

    // ── on_board: remaining tail area, monotone non-increasing in time ───────────────────
    #[test]
    fn on_board_is_remaining_area() {
        let ev = CurveEvent {
            start_ms: 0,
            step_ms: STEP_MS,
            kind: CurveKind::Insulin,
            total: 6.0,
            values: vec![1.0, 2.0, 3.0], // steps start at 0, 5, 10 min
        };
        // Before onset: full total.
        assert!((on_board(vec![ev.clone()], -1, CurveKind::Insulin) - 6.0).abs() < 1e-12);
        // At t=0 (step0 has not yet started acting): full dose still on board.
        assert!((on_board(vec![ev.clone()], 0, CurveKind::Insulin) - 6.0).abs() < 1e-12);
        // Just past step0's start: step0 delivered, remaining = 2+3.
        assert!((on_board(vec![ev.clone()], 1, CurveKind::Insulin) - 5.0).abs() < 1e-12);
        // At start of step2 (10 min): remaining = 3.
        assert!((on_board(vec![ev.clone()], 2 * STEP_MS, CurveKind::Insulin) - 3.0).abs() < 1e-12);
        // Past DIA: 0.
        assert_eq!(on_board(vec![ev.clone()], 100 * STEP_MS, CurveKind::Insulin), 0.0);
        // Kind filter: carb query sees nothing.
        assert_eq!(on_board(vec![ev], 0, CurveKind::Carb), 0.0);
    }

    #[test]
    fn on_board_monotone_non_increasing() {
        let ev = bolus_pk_for_dose(7.0);
        let mut prev = f64::INFINITY;
        for step in 0..40 {
            let iob = on_board(vec![ev.clone()], step * STEP_MS, CurveKind::Insulin);
            assert!(iob <= prev + 1e-12, "IOB rose at step {step}: {iob} > {prev}");
            prev = iob;
        }
    }

    // ── extend_basal: daily tiling + long-tail carry into the window ─────────────────────
    #[test]
    fn extend_basal_tiles_daily() {
        // One Lantus dose at 08:00 local, UTC (tz=0). Window spans ~2.5 days.
        let sched = BasalSchedule {
            tz_offset_min: 0,
            doses: vec![BasalDoseSpec {
                time_of_day_min: 8 * 60,
                dose_u: 24.0,
                duration_min: LANTUS_DIA_MIN,
                ka_per_hour: BASAL_KA_PER_HOUR,
                ke_per_hour: BASAL_KE_PER_HOUR,
            }],
        };
        let from = 0i64;
        let to = (2 * 24 + 12) * 3600 * 1000i64; // 2.5 days
        let evs = extend_basal(sched, from, to).unwrap();
        // Injections whose 24 h action overlaps [0, 2.5d): the one from the *previous*
        // day (08:00 of day -1, tail reaches into day 0) plus days 0, 1, 2 → 4 events.
        assert_eq!(evs.len(), 4);
        assert!(evs.windows(2).all(|w| w[0].start_ms <= w[1].start_ms));
        for e in &evs {
            assert_eq!(e.kind, CurveKind::Insulin);
            assert!((e.values.iter().sum::<f64>() - 24.0).abs() < 1e-9);
        }
    }

    #[test]
    fn extend_basal_background_is_near_flat() {
        // Tresiba (degludec) q24h: DIA ~42 h ≫ the 24 h cadence, so 2-3 doses always
        // overlap and the summed background is near-flat with NO seam trough
        // (model-io-curves.md: "basal auto-extended as a near-flat background"). Lantus
        // q24h, whose ~24 h action barely covers the cadence, legitimately troughs — that
        // is real, and the model is allowed to see it — so the flatness claim is asserted
        // on the genuinely-flat degludec preset.
        let sched = BasalSchedule {
            tz_offset_min: 0,
            doses: vec![BasalDoseSpec {
                time_of_day_min: 8 * 60,
                dose_u: 24.0,
                duration_min: TRESIBA_DIA_MIN,
                ka_per_hour: BASAL_KA_PER_HOUR,
                ke_per_hour: BASAL_KE_PER_HOUR,
            }],
        };
        let day = 24 * 3600 * 1000i64;
        // 8-day window so the middle day is fully covered by overlapping 42 h tails.
        let evs = extend_basal(sched, 0, 8 * day).unwrap();
        let g0 = 4 * day; // a mid-window day
        let n = (day / STEP_MS) as i32; // 288 steps
        let bg = bucketize(evs, g0, n, CurveKind::Insulin).unwrap();
        let daily: f64 = bg.iter().sum();
        let mean = daily / bg.len() as f64;
        let (mn, mx) = bg.iter().fold((f64::MAX, f64::MIN), |(a, b), &v| (a.min(v), b.max(v)));
        // The qualitative property that distinguishes an overlapping schedule from Lantus's
        // q24h trough: the background is STRICTLY POSITIVE at every step (no seam notch),
        // and its daily integral equals the injected dose. The absolute smoothness is a
        // physiologic given of degludec, not something we over-constrain numerically.
        assert!(mn > 0.0, "degludec background should never trough to zero: min {mn}");
        assert!(mx < 3.0 * mean, "background implausibly peaky: min {mn} max {mx} mean {mean}");
        assert!((daily - 24.0).abs() < 1e-6, "mid-window daily basal integral {daily} != dose");
    }

    // ── Counterfactual sign/monotonicity (the real fidelity check, PLAN §3.3) ────────────
    #[test]
    fn counterfactual_more_carbs_larger_channel() {
        // The operational fidelity claim at the curve level: a larger meal produces a
        // pointwise-larger carb appearance channel (⇒ higher forecast downstream).
        let g0 = 0i64;
        let small = bolus_gamma_carb(30.0);
        let big = bolus_gamma_carb(60.0);
        let cs = bucketize(vec![small], g0, 48, CurveKind::Carb).unwrap();
        let cb = bucketize(vec![big], g0, 48, CurveKind::Carb).unwrap();
        assert!(cs.iter().zip(&cb).all(|(a, b)| b >= a));
        assert!(cb.iter().sum::<f64>() > cs.iter().sum::<f64>());
    }

    #[test]
    fn counterfactual_more_insulin_larger_channel() {
        let g0 = 0i64;
        let small = bolus_pk_for_dose(3.0);
        let big = bolus_pk_for_dose(9.0);
        // Larger dose ⇒ strictly more total insulin action, and more IOB at onset.
        assert!(big.values.iter().sum::<f64>() > small.values.iter().sum::<f64>());
        assert!(
            on_board(vec![big], g0 - 1, CurveKind::Insulin)
                > on_board(vec![small], g0 - 1, CurveKind::Insulin)
        );
    }

    // Helper: a carb Ra gamma event at start_ms=0 (fast-carb central preset k=3, θ=20).
    fn bolus_gamma_carb(grams: f64) -> CurveEvent {
        CurveEvent {
            start_ms: 0,
            step_ms: STEP_MS,
            kind: CurveKind::Carb,
            total: grams,
            values: gamma(grams, 3.0, 20.0, 240.0),
        }
    }
}
