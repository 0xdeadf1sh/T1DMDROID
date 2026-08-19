//! A seeded synthetic patient, on the five-minute grid.
//!
//! The newer models read up to seven days of context. A phone that has been running for two
//! days cannot give them one, and padding the gap with zeros would announce a flat 10 mg/dL
//! history the model has never seen. This generates a plausible one instead: meals with the
//! same gamma appearance the app reconstructs its own carbohydrate channel with, boluses on a
//! carbohydrate ratio, a constant basal, occasional exercise bouts, and a glucose trace driven
//! by those three inputs rather than drawn independently of them.
//!
//! **It is not a simulator and it is not the patient.** The trace is a shaped plausibility, fit
//! for exercising a model over a long window and for nothing else. What follows from that is
//! not a style rule but the safety boundary: synthetic samples are never stored, never
//! synced, never fed to an alarm, a dose calculator, a statistic or a conformal fit. The
//! caller keeps them in memory for one run.
//!
//! Deterministic in the seed, so a run is reproducible and two runs of the same experiment
//! differ only in what the caller changed.

use crate::head::Rng;
use crate::CoreError;

/// Steps per hour on the five-minute grid.
const STEPS_PER_HOUR: usize = 12;
const STEPS_PER_DAY: usize = 24 * STEPS_PER_HOUR;

/// The knobs a caller may turn. Every default is a population figure, not this patient's:
/// the point is a plausible history, not a fitted one.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct SynthParams {
    /// Fasting glucose the trace returns to, mg/dL.
    pub baseline_bg: f64,
    /// Grams of carbohydrate in a main meal, before jitter.
    pub meal_grams: f64,
    /// Grams of carbohydrate per unit of insulin.
    pub carb_ratio: f64,
    /// Basal insulin, units per hour.
    pub basal_u_per_hour: f64,
    /// Probability a given day carries an exercise bout.
    pub exercise_prob: f64,
    /// Carbohydrate-equivalent disposal per minute of exercise (`invariants.md` §5's rate).
    pub exercise_carb_equiv_per_min: f64,
    /// Standard deviation of the CGM noise, mg/dL.
    pub cgm_noise_sd: f64,
    /// Probability a meal's bolus is forgotten entirely — the excursion the model most needs
    /// to have seen, and the one a tidy generator never produces.
    pub missed_bolus_prob: f64,
}

impl Default for SynthParams {
    fn default() -> Self {
        SynthParams {
            baseline_bg: 110.0,
            meal_grams: 55.0,
            carb_ratio: 10.0,
            basal_u_per_hour: 0.9,
            exercise_prob: 0.4,
            exercise_carb_equiv_per_min: 0.5,
            cgm_noise_sd: 2.5,
            missed_bolus_prob: 0.08,
        }
    }
}

/// The default parameters, for a caller that has no opinion.
#[uniffi::export]
pub fn synth_default_params() -> SynthParams {
    SynthParams::default()
}

/// Four channels on the grid, in the units the model reads: BG mg/dL, carbohydrate
/// appearance g/step, combined insulin U/step, exercise carbohydrate-equivalent g/step.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SynthSeries {
    pub bg: Vec<f64>,
    pub carb: Vec<f64>,
    pub insulin: Vec<f64>,
    pub exercise: Vec<f64>,
    /// What the generator put in, for the panel to show: it is the difference between a
    /// trace that looks plausible and one whose events a reader can point at.
    pub n_meals: i32,
    pub n_boluses: i32,
    pub n_bouts: i32,
}

/// A gamma appearance curve over `span_min` minutes, per five-minute step, summing to `total`.
///
/// This is `crate::curve::gamma` — §5's curve, in the one place the crate implements it. A second
/// spelling here would be a second copy of a shared formula, and the two would agree only until
/// one of them was tuned.
fn gamma_steps(total: f64, k: f64, theta_min: f64, span_min: f64) -> Vec<f64> {
    crate::curve::gamma(total, k, theta_min, span_min)
}

fn add_at(dst: &mut [f64], start: usize, src: &[f64]) {
    for (i, v) in src.iter().enumerate() {
        if start + i < dst.len() {
            dst[start + i] += v;
        }
    }
}

/// Generate `n_steps` of synthetic history ending at the caller's "now".
///
/// `start_hour_of_day` is the local clock hour at step 0, which is what puts breakfast at
/// breakfast time; a generator that ignores it produces meals uniformly around the clock and
/// teaches the time probe nothing it should believe.
#[uniffi::export]
pub fn synth_series(
    n_steps: i32,
    start_hour_of_day: f64,
    params: SynthParams,
    seed: i64,
) -> Result<SynthSeries, CoreError> {
    let n = n_steps.max(0) as usize;
    if n < STEPS_PER_HOUR || n > 64 * STEPS_PER_DAY {
        return Err(CoreError::Internal {
            reason: format!("n_steps {n} outside one hour .. 64 days"),
        });
    }
    let p = params;
    for (name, v) in [
        ("baseline_bg", p.baseline_bg),
        ("meal_grams", p.meal_grams),
        ("carb_ratio", p.carb_ratio),
        ("basal_u_per_hour", p.basal_u_per_hour),
        ("exercise_carb_equiv_per_min", p.exercise_carb_equiv_per_min),
        ("cgm_noise_sd", p.cgm_noise_sd),
    ] {
        if !v.is_finite() || v < 0.0 {
            return Err(CoreError::Internal {
                reason: format!("synth parameter {name} = {v} must be finite and >= 0"),
            });
        }
    }
    if p.carb_ratio <= 0.0 || p.baseline_bg <= 0.0 {
        return Err(CoreError::Internal {
            reason: "carb_ratio and baseline_bg must be > 0".into(),
        });
    }
    if !(0.0..=1.0).contains(&p.exercise_prob) || !(0.0..=1.0).contains(&p.missed_bolus_prob) {
        return Err(CoreError::Internal {
            reason: "exercise_prob and missed_bolus_prob must lie in [0, 1]".into(),
        });
    }

    if !start_hour_of_day.is_finite() {
        return Err(CoreError::Internal {
            reason: format!("start_hour_of_day {start_hour_of_day} must be finite"),
        });
    }
    let mut rng = Rng::new(seed as u64 ^ 0x5EED_1DEA_0BAD_F00D);
    let mut carb = vec![0.0f64; n];
    let mut insulin = vec![p.basal_u_per_hour / STEPS_PER_HOUR as f64; n];
    let mut exercise = vec![0.0f64; n];
    let (mut n_meals, mut n_boluses, mut n_bouts) = (0i32, 0i32, 0i32);

    // Meals at plausible local hours, jittered, with a bolus that is usually there.
    let start_step_of_day = (start_hour_of_day.rem_euclid(24.0) * STEPS_PER_HOUR as f64) as i64;
    let n_days = n.div_ceil(STEPS_PER_DAY) + 1;
    for day in 0..n_days as i64 {
        for (hour, portion) in [(7.5, 0.8), (12.5, 1.0), (19.0, 1.1), (22.0, 0.35)] {
            let jitter = (rng.uniform() - 0.5) * 1.5 * STEPS_PER_HOUR as f64;
            let at = day * STEPS_PER_DAY as i64
                + (hour * STEPS_PER_HOUR as f64) as i64
                - start_step_of_day
                + jitter as i64;
            if at < 0 || at as usize >= n {
                continue;
            }
            let grams = p.meal_grams * portion * (0.75 + 0.5 * rng.uniform());
            // Carbohydrate appearance: the §5 gamma, peak near 45 min, tail to 4 h.
            add_at(&mut carb, at as usize, &gamma_steps(grams, 2.0, 45.0, 240.0));
            n_meals += 1;
            if rng.uniform() >= p.missed_bolus_prob {
                let units = grams / p.carb_ratio;
                // The bolus lands somewhere between 15 min early and 20 min late, and its action
                // is the same gamma family §5 fixes for a bolus — peak near an hour, tail to five.
                let offset = ((rng.uniform() - 0.4) * 7.0) as i64;
                let at_ins = (at + offset).max(0) as usize;
                add_at(&mut insulin, at_ins, &gamma_steps(units, 2.0, 55.0, 300.0));
                n_boluses += 1;
            }
        }
        if rng.uniform() < p.exercise_prob {
            let hour = 6.0 + rng.uniform() * 12.0;
            let at = day * STEPS_PER_DAY as i64 + (hour * STEPS_PER_HOUR as f64) as i64
                - start_step_of_day;
            if at >= 0 && (at as usize) < n {
                let duration_min = 20.0 + rng.uniform() * 40.0;
                let total = duration_min * p.exercise_carb_equiv_per_min;
                // §5's disposal gamma: k = 3, θ = 15, running `duration + 90` minutes.
                add_at(
                    &mut exercise,
                    at as usize,
                    &gamma_steps(total, 3.0, 15.0, duration_min + 90.0),
                );
                n_bouts += 1;
            }
        }
    }

    // Glucose: a one-compartment response to the three inputs, plus a dawn term and AR(1)
    // sensor noise.
    //
    // The one thing that has to be right is the BALANCE. Insulin acts on its EXCESS over
    // basal, so a resting patient on their own basal drifts nowhere; a coefficient set that
    // lets basal itself push glucose produces a trace that walks into the floor over a week
    // and looks, step by step, entirely plausible. The rest is shaping: a bolused meal roughly
    // cancels, an unbolused one runs to about +190 mg/dL, and exercise removes what its
    // carbohydrate equivalent says it removes — which is what "carbohydrate equivalent" means
    // and why its coefficient is the carbohydrate one.
    let basal_per_step = p.basal_u_per_hour / STEPS_PER_HOUR as f64;
    let mut bg = vec![0.0f64; n];
    let mut g = p.baseline_bg;
    let mut ins_effect = basal_per_step;
    let mut noise = 0.0f64;
    let k_carb = 3.6; // mg/dL per gram appearing in a step
    let k_ins = 35.0; // mg/dL per unit of insulin acting ABOVE basal in a step
    let k_ex = 3.6; // carbohydrate equivalent, so the carbohydrate coefficient
    let k_return = 0.02; // pull back to baseline per step (~4 h time constant)
    for t in 0..n {
        ins_effect += 0.16 * (insulin[t] - ins_effect);
        let hour = ((start_step_of_day + t as i64) as f64 / STEPS_PER_HOUR as f64).rem_euclid(24.0);
        // Dawn phenomenon: a gentle rise through the small hours.
        let dawn = if (3.0..8.0).contains(&hour) { 0.35 } else { 0.0 };
        // Counter-regulation: below ~75 mg/dL the liver answers, and the answer strengthens the
        // lower glucose goes. Without it a bolus with no meal behind it walks the trace into the
        // physical floor and PARKS there — an hour of exactly 40 mg/dL, which is the clamp
        // showing through rather than a hypo, and which the app's own degeneracy guard would call
        // a rail-pinned forecast if a model produced it.
        let counter = if g < 80.0 { 0.16 * (80.0 - g) } else { 0.0 };
        let d = k_carb * carb[t] - k_ins * (ins_effect - basal_per_step) - k_ex * exercise[t]
            + dawn
            + counter
            - k_return * (g - p.baseline_bg);
        g = (g + d).clamp(40.0, 400.0);
        noise = 0.75 * noise + rng.normal() * p.cgm_noise_sd * 0.66;
        bg[t] = (g + noise).clamp(40.0, 400.0);
    }

    Ok(SynthSeries {
        bg,
        carb,
        insulin,
        exercise,
        n_meals,
        n_boluses,
        n_bouts,
    })
}

/// Fill the gaps in a real history from a synthetic one, keeping every real sample.
///
/// This is what "synthetic mode" does: real data wins wherever it exists, and the synthetic
/// trace only reaches the steps that have none. `real_bg` carries `NaN` where the grid has no
/// reading. The dose channels are reconstructions rather than observations, so they are
/// filled on the SAME steps the BG was filled on — filling them independently would pair a
/// real meal with a synthetic glucose response.
#[uniffi::export]
pub fn synth_fill_gaps(
    real_bg: Vec<f64>,
    real_carb: Vec<f64>,
    real_insulin: Vec<f64>,
    real_exercise: Vec<f64>,
    synth: &SynthSeries,
) -> Result<SynthSeries, CoreError> {
    let n = real_bg.len();
    if n == 0
        || real_carb.len() != n
        || real_insulin.len() != n
        || real_exercise.len() != n
        || synth.bg.len() != n
        || synth.carb.len() != n
        || synth.insulin.len() != n
        || synth.exercise.len() != n
    {
        return Err(CoreError::Internal {
            reason: format!(
                "gap fill needs four real channels and a synthetic series of one length: \
                 {n}/{}/{}/{}/{}/{}/{}/{}",
                real_carb.len(),
                real_insulin.len(),
                real_exercise.len(),
                synth.bg.len(),
                synth.carb.len(),
                synth.insulin.len(),
                synth.exercise.len()
            ),
        });
    }
    let mut out = SynthSeries {
        bg: real_bg.clone(),
        carb: real_carb,
        insulin: real_insulin,
        exercise: real_exercise,
        n_meals: synth.n_meals,
        n_boluses: synth.n_boluses,
        n_bouts: synth.n_bouts,
    };
    let mut filled = 0i32;
    for t in 0..n {
        if real_bg[t].is_nan() {
            out.bg[t] = synth.bg[t];
            out.carb[t] = synth.carb[t];
            out.insulin[t] = synth.insulin[t];
            out.exercise[t] = synth.exercise[t];
            filled += 1;
        }
    }
    // The counts must describe what the caller is LOOKING AT, not what the generator produced:
    // only the events inside a filled stretch survive, so count them there. Reporting the
    // generator's totals overstates a window that is mostly real.
    //
    // An event is one RUN of consecutive live steps on its channel. Boluses ride the insulin
    // channel over a constant basal, so the run has to clear that floor to be one.
    fn runs(real_bg: &[f64], channel: &[f64], floor: f64) -> i32 {
        let mut n = 0i32;
        let mut inside = false;
        for t in 0..channel.len() {
            let live = real_bg[t].is_nan() && channel[t] > floor;
            if live && !inside {
                n += 1;
            }
            inside = live;
        }
        n
    }
    let basal = synth
        .insulin
        .iter()
        .cloned()
        .fold(f64::INFINITY, f64::min)
        .max(0.0);
    out.n_meals = runs(&real_bg, &synth.carb, 0.0);
    out.n_bouts = runs(&real_bg, &synth.exercise, 0.0);
    out.n_boluses = runs(&real_bg, &synth.insulin, basal + 1e-12);
    let _ = filled;
    Ok(out)
}

/// The steps of `bg` that carry no reading, as `[start, end)` runs — the gaps a repair can
/// offer to fill. `NaN` marks an absent sample.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct GapRun {
    pub start: i32,
    pub end: i32,
}

/// Find the absent-sample runs in a gridded BG series, longest first.
///
/// `min_steps` drops the one- and two-sample dropouts every CGM produces; those are noise in
/// the record, not a gap anyone wants a model to reconstruct.
#[uniffi::export]
pub fn find_gaps(bg: Vec<f64>, min_steps: i32) -> Vec<GapRun> {
    let min = min_steps.max(1) as usize;
    let mut runs = Vec::new();
    let mut start: Option<usize> = None;
    for (t, v) in bg.iter().enumerate() {
        match (v.is_nan(), start) {
            (true, None) => start = Some(t),
            (false, Some(s)) => {
                if t - s >= min {
                    runs.push(GapRun { start: s as i32, end: t as i32 });
                }
                start = None;
            }
            _ => {}
        }
    }
    if let Some(s) = start {
        if bg.len() - s >= min {
            runs.push(GapRun { start: s as i32, end: bg.len() as i32 });
        }
    }
    runs.sort_by_key(|r| -(r.end - r.start));
    runs
}

#[cfg(test)]
mod tests {
    use super::*;

    const DAY: i32 = (STEPS_PER_DAY) as i32;

    #[test]
    fn a_seed_reproduces_the_trace_exactly() {
        let a = synth_series(3 * DAY, 6.0, SynthParams::default(), 1234).unwrap();
        let b = synth_series(3 * DAY, 6.0, SynthParams::default(), 1234).unwrap();
        assert_eq!(a, b);
        let c = synth_series(3 * DAY, 6.0, SynthParams::default(), 1235).unwrap();
        assert_ne!(a.bg, c.bg, "two seeds produced the same trace");
    }

    #[test]
    fn the_trace_stays_physiological_and_carries_its_events() {
        let s = synth_series(7 * DAY, 0.0, SynthParams::default(), 77).unwrap();
        assert_eq!(s.bg.len(), (7 * DAY) as usize);
        assert!(s.bg.iter().all(|v| v.is_finite() && (40.0..=400.0).contains(v)));
        let mean = s.bg.iter().sum::<f64>() / s.bg.len() as f64;
        assert!((90.0..200.0).contains(&mean), "mean BG {mean} is not plausible");
        // It must actually move: a flat line would train nothing and mask a broken generator.
        let lo = s.bg.iter().cloned().fold(f64::INFINITY, f64::min);
        let hi = s.bg.iter().cloned().fold(f64::NEG_INFINITY, f64::max);
        assert!(hi - lo > 40.0, "the trace spans only {} mg/dL", hi - lo);
        // And it must never SIT on a clamp. A trace that walks into the floor and stays there
        // still passes a mean and a span check if it did enough before arriving, and a model fed
        // hours of a flat 40 learns something that never happens.
        for seed in [77i64, 1, 2, 3, 4, 5] {
            let t = synth_series(7 * DAY, 0.0, SynthParams::default(), seed).unwrap();
            let mut run = 0;
            let mut worst = 0;
            for v in &t.bg {
                run = if *v <= 40.5 || *v >= 399.5 { run + 1 } else { 0 };
                worst = worst.max(run);
            }
            assert!(worst == 0, "seed {seed}: the trace sits on a clamp for {worst} steps");
            let m = t.bg.iter().sum::<f64>() / t.bg.len() as f64;
            assert!((90.0..200.0).contains(&m), "seed {seed}: mean BG {m} is not plausible");
        }
        // Roughly four eating occasions a day, and a bolus for most of them.
        assert!(s.n_meals >= 7 * 3, "only {} meals in a week", s.n_meals);
        assert!(s.n_boluses > 0 && s.n_boluses <= s.n_meals);
        // Every channel is non-negative, and insulin never falls below basal.
        let basal_step = SynthParams::default().basal_u_per_hour / STEPS_PER_HOUR as f64;
        assert!(s.carb.iter().all(|v| *v >= 0.0));
        assert!(s.exercise.iter().all(|v| *v >= 0.0));
        assert!(s.insulin.iter().all(|v| *v >= basal_step - 1e-12));
    }

    #[test]
    fn meals_land_at_meal_times() {
        // Step 0 is local midnight, so the first day's carbohydrate must appear in the waking
        // half of the day rather than uniformly around the clock.
        let s = synth_series(DAY, 0.0, SynthParams::default(), 5).unwrap();
        let night: f64 = s.carb[..(4 * STEPS_PER_HOUR)].iter().sum();
        let day: f64 = s.carb[(6 * STEPS_PER_HOUR)..(22 * STEPS_PER_HOUR)].iter().sum();
        assert!(day > night * 10.0, "carbohydrate is not concentrated in the day: {day} vs {night}");
    }

    #[test]
    fn parameters_are_guarded() {
        let p = SynthParams::default();
        assert!(synth_series(0, 0.0, p, 1).is_err());
        assert!(synth_series(10_000_000, 0.0, p, 1).is_err());
        assert!(synth_series(DAY, 0.0, SynthParams { carb_ratio: 0.0, ..p }, 1).is_err());
        assert!(synth_series(DAY, 0.0, SynthParams { baseline_bg: -1.0, ..p }, 1).is_err());
        assert!(synth_series(DAY, 0.0, SynthParams { exercise_prob: 2.0, ..p }, 1).is_err());
        assert!(synth_series(DAY, 0.0, SynthParams { cgm_noise_sd: f64::NAN, ..p }, 1).is_err());
        // An hour is the shortest run that means anything, and it is accepted.
        assert!(synth_series(STEPS_PER_HOUR as i32, 0.0, p, 1).is_ok());
    }

    #[test]
    fn gap_fill_never_overwrites_a_real_sample() {
        let n = 6 * STEPS_PER_HOUR;
        let synth = synth_series(n as i32, 8.0, SynthParams::default(), 3).unwrap();
        let mut bg: Vec<f64> = (0..n).map(|i| 100.0 + i as f64 * 0.1).collect();
        for t in 20..35 {
            bg[t] = f64::NAN;
        }
        let carb = vec![1.0; n];
        let insulin = vec![0.02; n];
        let exercise = vec![0.0; n];
        let out = synth_fill_gaps(bg.clone(), carb, insulin, exercise, &synth).unwrap();
        for t in 0..n {
            if bg[t].is_nan() {
                assert_eq!(out.bg[t], synth.bg[t], "gap at {t} was not filled");
                // The dose channels follow the SAME steps, so a real meal is never paired
                // with a synthetic glucose response.
                assert_eq!(out.carb[t], synth.carb[t]);
                assert_eq!(out.insulin[t], synth.insulin[t]);
                assert_eq!(out.exercise[t], synth.exercise[t]);
            } else {
                assert_eq!(out.bg[t], bg[t], "real sample at {t} was overwritten");
                assert_eq!(out.carb[t], 1.0);
                assert_eq!(out.insulin[t], 0.02);
            }
        }
        assert!(out.bg.iter().all(|v| !v.is_nan()));
    }

    #[test]
    fn gap_fill_rejects_ragged_input() {
        let synth = synth_series(STEPS_PER_HOUR as i32, 0.0, SynthParams::default(), 1).unwrap();
        let n = STEPS_PER_HOUR;
        assert!(synth_fill_gaps(vec![1.0; n], vec![0.0; n - 1], vec![0.0; n], vec![0.0; n], &synth).is_err());
        assert!(synth_fill_gaps(vec![], vec![], vec![], vec![], &synth).is_err());
        assert!(synth_fill_gaps(vec![1.0; n + 1], vec![0.0; n + 1], vec![0.0; n + 1], vec![0.0; n + 1], &synth).is_err());
    }

    #[test]
    fn find_gaps_reports_the_runs_a_repair_can_offer() {
        let mut bg = vec![100.0; 100];
        for t in 10..14 {
            bg[t] = f64::NAN; // 4 steps
        }
        bg[40] = f64::NAN; // a single dropout
        for t in 70..90 {
            bg[t] = f64::NAN; // 20 steps
        }
        let runs = find_gaps(bg.clone(), 3);
        assert_eq!(runs.len(), 2, "a 1-step dropout is not a gap");
        assert_eq!(runs[0], GapRun { start: 70, end: 90 }, "longest first");
        assert_eq!(runs[1], GapRun { start: 10, end: 14 });
        // A gap running to the end of the series is still a gap.
        let mut tail = vec![100.0; 20];
        for v in tail.iter_mut().skip(15) {
            *v = f64::NAN;
        }
        assert_eq!(find_gaps(tail, 3), vec![GapRun { start: 15, end: 20 }]);
        assert!(find_gaps(vec![100.0; 20], 3).is_empty());
    }
}

