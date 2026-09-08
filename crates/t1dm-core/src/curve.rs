//! Curve/PK (SPEC §3.3; INFERENCE.md §9): carbs=Ra, insulin=PK; basal+bolus → `insulin_combined`.

use crate::CoreError;

/// Minutes per curve step. `SPEC/invariants.md` §1's grid; the one copy in this crate.
pub const DT_MINUTES: f64 = 5.0;
/// Milliseconds per curve/grid step. `values[]` are amount-per-this-step.
pub const STEP_MS: i64 = 300_000;

/// ~2.85y of steps; bounds [`bucketize`]'s alloc so a hostile size returns `Err`, not an abort.
const MAX_GRID_STEPS: i32 = 300_000;

// The simulator's central values, per-patient and per-event RNG noise switched off.

/// `simulator.BOLUS_GAMMA_K`. Peak time = (k-1)·θ ⇒ ~50 min at the 5 U reference dose.
pub const BOLUS_GAMMA_K: f64 = 3.0;
/// At the reference dose. `simulator.BOLUS_GAMMA_THETA`.
pub const BOLUS_GAMMA_THETA: f64 = 25.0;
/// Hours, at the 5 U reference dose. `simulator.BOLUS_DIA_BASE_HOURS`.
pub const BOLUS_DIA_BASE_HOURS: f64 = 2.5;
/// Hours of DIA per unit of `sqrt(dose) - sqrt(5)`. `simulator.BOLUS_DIA_DOSE_SCALE`.
pub const BOLUS_DIA_DOSE_SCALE: f64 = 0.6;
/// Hours. `simulator.BOLUS_DIA_MIN_HOURS` / `_MAX_HOURS`.
pub const BOLUS_DIA_MIN_HOURS: f64 = 2.0;
pub const BOLUS_DIA_MAX_HOURS: f64 = 7.5;
/// θ drift per unit of `sqrt(dose) - sqrt(5)`. `simulator.BOLUS_THETA_DOSE_SLOPE`.
pub const BOLUS_THETA_DOSE_SLOPE: f64 = 0.06;

/// Bateman ka/ke (1/h): `simulator.BASAL_KA_PER_HOUR`/`_KE_PER_HOUR`; tmax=ln(ka/ke)/(ka-ke)≈6.3h.
pub const BASAL_KA_PER_HOUR: f64 = 0.30;
pub const BASAL_KE_PER_HOUR: f64 = 0.07;
/// Tail-taper (h) so doses join seamlessly. `simulator.BASAL_TAIL_CLIP_HOURS`.
pub const BASAL_TAIL_CLIP_HOURS: f64 = 5.0;

/// Nominal durations of action (minutes): Lantus ~24 h, Tresiba ~42 h.
pub const LANTUS_DIA_MIN: f64 = 24.0 * 60.0;
pub const TRESIBA_DIA_MIN: f64 = 42.0 * 60.0;

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum CurveKind {
    Carb,
    Insulin,
    /// Carbohydrate-equivalent glucose disposal, g/step, positive. `SPEC/invariants.md` §5.
    Exercise,
}

/// `values` sum to `total`: Ra grams for carbs, PK action-units for insulin.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct CurveEvent {
    /// Epoch ms of `values[0]`.
    pub start_ms: i64,
    /// Always [`STEP_MS`].
    pub step_ms: i64,
    pub kind: CurveKind,
    pub total: f64,
    /// Amount-per-step, oldest→newest.
    pub values: Vec<f64>,
}

/// One injection of a daily-repeating schedule.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct BasalDoseSpec {
    /// Minutes from local midnight.
    pub time_of_day_min: i32,
    pub dose_u: f64,
    pub duration_min: f64,
    pub ka_per_hour: f64,
    pub ke_per_hour: f64,
}

/// SPEC §3.6: `tz_offset_min` maps epoch-ms to the local midnight `time_of_day_min` is from.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct BasalSchedule {
    pub tz_offset_min: i32,
    pub doses: Vec<BasalDoseSpec>,
}

/// Amount/`dt`-step, sum=total_amount; = `simulator.gamma_curve` (t^(k-1)e^(-t/θ)); n≤0⇒[0.0].
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
        let t = (i as f64 + 1.0) * DT_MINUTES;
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

/// Amount/`dt`-step summing to total; `simulator.basal_curve`, smootherstep-tapered; `values[0]`=0.
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
        let t_h = i as f64 * (DT_MINUTES / 60.0);
        let v = (-ke * t_h).exp() - (-ka * t_h).exp();
        *c = v.max(0.0);
    }

    let tail_steps = (BASAL_TAIL_CLIP_HOURS * 60.0 / DT_MINUTES) as i64;
    if tail_steps > 0 && (tail_steps as usize) < n {
        let ts = tail_steps as usize;
        let start = n - ts;
        for j in 0..ts {
            // linspace(1, 0, ts); the last is exactly 0.
            let s = if ts == 1 {
                1.0
            } else if j == ts - 1 {
                0.0
            } else {
                1.0 - j as f64 / (ts as f64 - 1.0)
            };
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

// Loop/OpenAPS exp model (oref0 `lib/iob/calculate.js`), opt-in: off the distribution above.

/// Amount/`dt`-step, sum==total; peaks `peak_min`; `[0.0]` unless dia_min≥dt, 0<peak_min<dia_min/2.
#[uniffi::export]
pub fn exp_action_curve(total: f64, peak_min: f64, dia_min: f64) -> Vec<f64> {
    let n_steps = (dia_min / DT_MINUTES) as i64;
    if n_steps <= 0 || peak_min <= 0.0 || peak_min >= dia_min / 2.0 {
        return vec![0.0];
    }
    let n = n_steps as usize;
    let tp = peak_min;
    let td = dia_min;
    let tau = tp * (1.0 - tp / td) / (1.0 - 2.0 * tp / td);
    let a = 2.0 * tau / td;
    let s = 1.0 / (1.0 - a + (1.0 + a) * (-td / tau).exp());
    let mut values = vec![0.0f64; n];
    let mut area = 0.0f64;
    for (i, v) in values.iter_mut().enumerate() {
        let t = (i as f64 + 1.0) * DT_MINUTES;
        let ia = (s / (tau * tau)) * t * (1.0 - t / td) * (-t / tau).exp();
        let ia = ia.max(0.0);
        *v = ia;
        area += ia;
    }
    if area > 0.0 {
        let scale = total / area;
        for v in values.iter_mut() {
            *v *= scale;
        }
    }
    values
}

/// Opt-in clinical shapes, every one off the training distribution.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum InsulinPreset {
    AspartNovorapid,
    FiaspFasterAspart,
    LisproHumalog,
    LisproLyumjev,
    GlargineU100Lantus,
    GlargineU300Toujeo,
    DegludecTresiba,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum InsulinFamily {
    /// → [`exp_action_curve`].
    RapidExp,
    /// → [`bateman`].
    BasalBateman,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct InsulinPresetSpec {
    pub preset: InsulinPreset,
    pub family: InsulinFamily,
    pub label: String,
    /// Minutes.
    pub peak_min: f64,
    /// Minutes.
    pub dia_min: f64,
    /// 1/h; meaningful only for `BasalBateman`.
    pub ka_per_hour: f64,
    pub ke_per_hour: f64,
    /// True for every catalogue entry; nothing reads it today.
    pub off_distribution: bool,
    /// Rendered verbatim beneath the selected chip. Keep it public-safe.
    pub citation: String,
}

/// The basals' `ka`/`ke` are a modeling choice for the flatness; each citation covers the DIA only.
#[uniffi::export]
pub fn insulin_preset_catalog() -> Vec<InsulinPresetSpec> {
    fn rapid(preset: InsulinPreset, label: &str, peak: f64, dia: f64, cite: &str) -> InsulinPresetSpec {
        InsulinPresetSpec {
            preset,
            family: InsulinFamily::RapidExp,
            label: label.to_string(),
            peak_min: peak,
            dia_min: dia,
            ka_per_hour: 0.0,
            ke_per_hour: 0.0,
            off_distribution: true,
            citation: cite.to_string(),
        }
    }
    fn basal(preset: InsulinPreset, label: &str, dia_h: f64, ka: f64, ke: f64, cite: &str) -> InsulinPresetSpec {
        InsulinPresetSpec {
            preset,
            family: InsulinFamily::BasalBateman,
            label: label.to_string(),
            peak_min: (ka.ln() - ke.ln()) / (ka - ke) * 60.0, // Bateman tmax; nothing reads it
            dia_min: dia_h * 60.0,
            ka_per_hour: ka,
            ke_per_hour: ke,
            off_distribution: true,
            citation: cite.to_string(),
        }
    }
    vec![
        rapid(
            InsulinPreset::AspartNovorapid,
            "Aspart · NovoRapid/Novolog",
            75.0,
            360.0,
            "Loop/OpenAPS rapid-acting adult exponential: peak 75 min, DIA 6 h",
        ),
        rapid(
            InsulinPreset::FiaspFasterAspart,
            "Faster aspart · Fiasp",
            55.0,
            360.0,
            "Loop `.fiasp` exponential preset: peak 55 min, DIA 6 h",
        ),
        rapid(
            InsulinPreset::LisproHumalog,
            "Lispro · Humalog",
            75.0,
            360.0,
            "Loop/OpenAPS rapid-acting adult exponential: peak 75 min, DIA 6 h",
        ),
        rapid(
            InsulinPreset::LisproLyumjev,
            "Ultra-rapid lispro · Lyumjev",
            45.0,
            300.0,
            "Ultra-rapid class (Fiasp-like); Bionic Wookiee 2022 peak ≈45 min, DIA 5 h",
        ),
        basal(
            InsulinPreset::GlargineU100Lantus,
            "Glargine U100 · Lantus",
            24.0,
            0.30,
            0.07,
            "Glargine U100 duration ~24 h (Healio ultra-long-acting review)",
        ),
        basal(
            InsulinPreset::GlargineU300Toujeo,
            "Glargine U300 · Toujeo",
            36.0,
            0.18,
            0.05,
            "Glargine U300 duration ~36 h, flatter GIR than U100 (Healio review)",
        ),
        basal(
            InsulinPreset::DegludecTresiba,
            "Degludec · Tresiba",
            42.0,
            0.12,
            0.04,
            "Degludec duration ~42 h, flat profile, t½ >25 h (Healio review)",
        ),
    ]
}

/// Sums `kind`-events onto `[grid_start_ms,+n_steps·STEP_MS)`; a pre-grid event's tail counts.
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
    if n_steps > MAX_GRID_STEPS {
        return Err(CoreError::Internal {
            reason: format!("bucketize n_steps {n_steps} exceeds cap {MAX_GRID_STEPS}"),
        });
    }
    let n = n_steps as usize;
    let mut grid = vec![0.0f64; n];
    bucketize_into(&events, grid_start_ms, kind, &mut grid);
    Ok(grid)
}

/// [`bucketize`] into a caller-owned slice. `out` is ADDED to, not cleared.
pub(crate) fn bucketize_into(
    events: &[CurveEvent],
    grid_start_ms: i64,
    kind: CurveKind,
    out: &mut [f64],
) {
    let n = out.len();
    for ev in events {
        if ev.kind != kind {
            continue;
        }
        let offset = (((ev.start_ms - grid_start_ms) as f64) / STEP_MS as f64).round() as i64;
        for (j, &val) in ev.values.iter().enumerate() {
            let idx = offset + j as i64;
            if idx >= 0 && (idx as usize) < n {
                out[idx as usize] += val;
            }
        }
    }
}

/// Remaining tail at `at_ms`: sum of not-yet-started `values[j]`; total before onset, 0 after DIA.
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

const DAY_MS: i64 = 86_400_000;
const MIN_MS: i64 = 60_000;

/// Expands [`BasalSchedule`] to Bateman events overlapping `[from_ms,to_ms)`, sorted by `start_ms`.
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
        // Bracket the local-day indices that can produce an overlapping start, then filter.
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

    #[test]
    fn exp_action_matches_reference() {
        for c in golden()["exp_action"].as_array().unwrap() {
            let got = exp_action_curve(
                c["total"].as_f64().unwrap(),
                c["peak"].as_f64().unwrap(),
                c["dia"].as_f64().unwrap(),
            );
            assert_close(&got, &f64s(&c["values"]), 1e-9, "exp_action");
        }
    }

    #[test]
    fn exp_action_non_negative_sums_to_dose_and_peaks_at_citation() {
        for (peak, dia) in [(75.0, 360.0), (55.0, 360.0), (45.0, 300.0)] {
            let v = exp_action_curve(5.0, peak, dia);
            assert!(v.iter().all(|&x| x >= 0.0), "negative activity for peak {peak}");
            assert!((v.iter().sum::<f64>() - 5.0).abs() < 1e-9, "sum != dose for peak {peak}");
            let argmax = v.iter().enumerate().max_by(|a, b| a.1.partial_cmp(b.1).unwrap()).unwrap().0;
            let peak_t = (argmax as f64 + 1.0) * DT_MINUTES;
            assert!((peak_t - peak).abs() <= DT_MINUTES, "peak at {peak_t} min, cited {peak}");
            assert!(*v.last().unwrap() < 0.02 * v[argmax], "tail not decayed by DIA for peak {peak}");
        }
    }

    #[test]
    fn exp_action_degenerate_inputs_are_total() {
        assert_eq!(exp_action_curve(5.0, 0.0, 300.0), vec![0.0]);   // non-positive peak
        assert_eq!(exp_action_curve(5.0, 200.0, 300.0), vec![0.0]); // peak >= dia/2 (τ singular)
        assert_eq!(exp_action_curve(5.0, 30.0, 0.0), vec![0.0]);    // non-positive dia
    }

    #[test]
    fn bucketize_places_and_sums() {
        let g0 = 1_000_000_000_000i64;
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
        assert_eq!(out, vec![0.0, 0.0, 11.0, 2.0, 0.0, 0.0]);
    }

    #[test]
    fn bucketize_carries_pre_grid_tail() {
        let g0 = 0i64;
        // Starts one step before the grid; only values[1..] land in-window.
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

    #[test]
    fn bucketize_rejects_oversized_n() {
        assert!(bucketize(vec![], 0, i32::MAX, CurveKind::Carb).is_err());
        assert!(bucketize(vec![], 0, MAX_GRID_STEPS + 1, CurveKind::Carb).is_err());
        assert!(bucketize(vec![], 0, MAX_GRID_STEPS, CurveKind::Carb).is_ok());
    }

    #[test]
    fn on_board_is_remaining_area() {
        let ev = CurveEvent {
            start_ms: 0,
            step_ms: STEP_MS,
            kind: CurveKind::Insulin,
            total: 6.0,
            values: vec![1.0, 2.0, 3.0], // steps start at 0, 5, 10 min
        };
        assert!((on_board(vec![ev.clone()], -1, CurveKind::Insulin) - 6.0).abs() < 1e-12);
        assert!((on_board(vec![ev.clone()], 0, CurveKind::Insulin) - 6.0).abs() < 1e-12);
        assert!((on_board(vec![ev.clone()], 1, CurveKind::Insulin) - 5.0).abs() < 1e-12);
        assert!((on_board(vec![ev.clone()], 2 * STEP_MS, CurveKind::Insulin) - 3.0).abs() < 1e-12);
        assert_eq!(on_board(vec![ev.clone()], 100 * STEP_MS, CurveKind::Insulin), 0.0);
        assert_eq!(on_board(vec![ev], 0, CurveKind::Carb), 0.0);
    }

    #[test]
    fn extend_basal_tiles_daily() {
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
        // The previous day's tail plus days 0, 1, 2.
        assert_eq!(evs.len(), 4);
        assert!(evs.windows(2).all(|w| w[0].start_ms <= w[1].start_ms));
        for e in &evs {
            assert_eq!(e.kind, CurveKind::Insulin);
            assert!((e.values.iter().sum::<f64>() - 24.0).abs() < 1e-9);
        }
    }

    #[test]
    fn extend_basal_background_is_near_flat() {
        // Degludec's 42h DIA ≫ 24h cadence: doses overlap, no seam trough (Lantus q24h troughs).
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
        let g0 = 4 * day;
        let n = (day / STEP_MS) as i32;
        let bg = bucketize(evs, g0, n, CurveKind::Insulin).unwrap();
        let daily: f64 = bg.iter().sum();
        let mean = daily / bg.len() as f64;
        let (mn, mx) = bg.iter().fold((f64::MAX, f64::MIN), |(a, b), &v| (a.min(v), b.max(v)));
        assert!(mn > 0.0, "degludec background should never trough to zero: min {mn}");
        assert!(mx < 3.0 * mean, "background implausibly peaky: min {mn} max {mx} mean {mean}");
        assert!((daily - 24.0).abs() < 1e-6, "mid-window daily basal integral {daily} != dose");
    }

    #[test]
    fn counterfactual_more_carbs_larger_channel() {
        let g0 = 0i64;
        let small = bolus_gamma_carb(30.0);
        let big = bolus_gamma_carb(60.0);
        let cs = bucketize(vec![small], g0, 48, CurveKind::Carb).unwrap();
        let cb = bucketize(vec![big], g0, 48, CurveKind::Carb).unwrap();
        assert!(cs.iter().zip(&cb).all(|(a, b)| b >= a));
        assert!(cb.iter().sum::<f64>() > cs.iter().sum::<f64>());
    }

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
