//! Split-conformal recalibration of the BG band fan — `SPEC/inference.md` §8.4's `delta` and its
//! apply. No exported descriptor carries a delta, so the cohort is one patient and the fit runs
//! on device over their own matured windows.
//!
//! The order statistic is SIDE-AWARE: `ceil((n+1)·τ)` above the median, `floor` below; `ceil` on
//! a lower edge is anti-conservative on the hypo edge. The rule is normative and shared with
//! `T1DMAI/conformal.py`, which publishes the same coverage — change §8.4, never one side alone.

use crate::accuracy::{tau_index, ForecastWindow, QUANTILE_LEVELS};
use crate::CoreError;

/// Resolved to a column by lookup, like every level of `SPEC/invariants.md` §6.1 — never a
/// literal index.
const MEDIAN_TAU: f64 = 0.5;

/// Calibration share of the chronologically ordered windows; the rest is held out and scored.
/// Fixed rather than exposed: at 1.0 the only coverage evidence is the residuals that made it.
const CAL_FRACTION: f64 = 0.7;

/// The band whose held-out coverage is reported; resolved by lookup, as [`MEDIAN_TAU`] is.
const REPORT_TAU_LO: f64 = 0.05;
const REPORT_TAU_HI: f64 = 0.95;

/// `delta` is `steps · n_quantiles`, step-major in ascending τ — [`ForecastWindow::bands_mgdl`]'s
/// layout. All zeros whenever `sufficient` is false. `cov90_*` and `mean_width90_*` are raw
/// against corrected on the HELD-OUT split, `None` when it is empty; §6.2 requires the widths.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ConformalFit {
    pub delta: Vec<f64>,
    pub steps: u32,
    pub n_quantiles: u32,
    pub n_windows: u32,
    pub n_cal: u32,
    pub n_eval: u32,
    pub n_rejected: u32,
    pub min_cal_windows: u32,
    pub sufficient: bool,
    pub max_abs_delta_mgdl: f64,
    pub cov90_raw: Option<f64>,
    pub cov90_cal: Option<f64>,
    pub mean_width90_raw: Option<f64>,
    pub mean_width90_cal: Option<f64>,
}

impl ConformalFit {
    fn refused(steps: usize, n_windows: u32, n_cal: u32, n_rejected: u32, min_cal: u32) -> Self {
        ConformalFit {
            delta: vec![0.0; steps * QUANTILE_LEVELS.len()],
            steps: steps as u32,
            n_quantiles: QUANTILE_LEVELS.len() as u32,
            n_windows,
            n_cal,
            n_eval: 0,
            n_rejected,
            min_cal_windows: min_cal,
            sufficient: false,
            max_abs_delta_mgdl: 0.0,
            cov90_raw: None,
            cov90_cal: None,
            mean_width90_raw: None,
            mean_width90_cal: None,
        }
    }
}

/// The smallest calibration count at which no level's order statistic clamps — derived from
/// [`QUANTILE_LEVELS`], 19 for the seven levels of §6. Below it the extreme levels' offsets are
/// the sample minimum and maximum. An arithmetic floor, not a recommendation: ask for more.
#[uniffi::export]
pub fn conformal_min_cal_windows() -> u32 {
    let median = tau_index(MEDIAN_TAU);
    let mut need = 1u32;
    for (k, &tau) in QUANTILE_LEVELS.iter().enumerate() {
        if Some(k) == median {
            continue;
        }
        let mut n = 1u32;
        while n < 10_000 {
            let idx = order_index(n as usize, tau);
            if idx.is_some() {
                break;
            }
            n += 1;
        }
        need = need.max(n);
    }
    need
}

/// 1-indexed order-statistic index for `tau` over `n` residuals; `None` when it falls outside
/// `[1, n]`, i.e. `n` is too small to resolve this level without clamping.
fn order_index(n: usize, tau: f64) -> Option<usize> {
    if n == 0 {
        return None;
    }
    let scaled = (n + 1) as f64 * tau;
    // Lower and upper edges round opposite ways; see the module note.
    let idx = if tau < MEDIAN_TAU {
        scaled.floor()
    } else {
        scaled.ceil()
    };
    if idx < 1.0 || idx > n as f64 {
        None
    } else {
        Some(idx as usize)
    }
}

/// `sorted` must be ascending.
fn conformal_offset(sorted: &[f64], tau: f64) -> f64 {
    match order_index(sorted.len(), tau) {
        Some(i) => sorted[i - 1],
        // Below the floor: the nearest extreme, rather than an out-of-bounds index.
        None if tau < MEDIAN_TAU => sorted[0],
        None => sorted[sorted.len() - 1],
    }
}

/// Fit a per-`(step, τ)` additive band correction from matured windows (§8.4).
///
/// `windows` must be in the order they were made: the split is CHRONOLOGICAL. `min_cal_windows`
/// is raised to [`conformal_min_cal_windows`]; under it the delta is all zeros, i.e. the raw fan.
/// Windows are rejected on [`crate::forecast_metrics_suite`]'s terms.
#[uniffi::export]
pub fn fit_quantile_conformal(
    windows: Vec<ForecastWindow>,
    min_cal_windows: u32,
) -> Result<ConformalFit, CoreError> {
    let nq = QUANTILE_LEVELS.len();
    let bad = |reason: String| CoreError::Internal { reason };
    let median_idx = tau_index(MEDIAN_TAU)
        .ok_or_else(|| bad(format!("MEDIAN_TAU {MEDIAN_TAU} is not a fan level")))?;
    let idx_lo = tau_index(REPORT_TAU_LO)
        .ok_or_else(|| bad(format!("REPORT_TAU_LO {REPORT_TAU_LO} is not a fan level")))?;
    let idx_hi = tau_index(REPORT_TAU_HI)
        .ok_or_else(|| bad(format!("REPORT_TAU_HI {REPORT_TAU_HI} is not a fan level")))?;
    let min_cal = min_cal_windows.max(conformal_min_cal_windows());

    if windows.is_empty() {
        return Ok(ConformalFit::refused(0, 0, 0, 0, min_cal));
    }
    let steps = windows[0].realized_bg.len();
    if steps == 0 {
        return Ok(ConformalFit::refused(0, windows.len() as u32, 0, 0, min_cal));
    }

    let mut kept: Vec<&ForecastWindow> = Vec::with_capacity(windows.len());
    let mut n_rejected = 0u32;
    for w in &windows {
        if w.realized_bg.len() != steps || w.bands_mgdl.len() != steps * nq {
            n_rejected += 1;
            continue;
        }
        let finite = w.realized_bg.iter().all(|v| v.is_finite())
            && w.bands_mgdl.iter().all(|v| v.is_finite());
        let ascending = finite
            && (0..steps).all(|s| {
                let row = s * nq;
                (1..nq).all(|k| w.bands_mgdl[row + k] >= w.bands_mgdl[row + k - 1])
            });
        if !ascending {
            n_rejected += 1;
            continue;
        }
        kept.push(w);
    }

    let n_kept = kept.len();
    let n_cal = ((n_kept as f64) * CAL_FRACTION).floor() as usize;
    if (n_cal as u32) < min_cal {
        return Ok(ConformalFit::refused(
            steps,
            n_kept as u32,
            n_cal as u32,
            n_rejected,
            min_cal,
        ));
    }
    let (cal, eval) = kept.split_at(n_cal);

    // One one-sided order statistic per (step, level), each from its own residuals.
    let mut delta = vec![0.0f64; steps * nq];
    let mut residuals: Vec<f64> = Vec::with_capacity(cal.len());
    let mut max_abs = 0.0f64;
    for s in 0..steps {
        for (k, &tau) in QUANTILE_LEVELS.iter().enumerate() {
            if k == median_idx {
                continue; // §8.4: the median is held fixed.
            }
            residuals.clear();
            for w in cal {
                let r = w.realized_bg[s] - w.bands_mgdl[s * nq + k];
                if r.is_finite() {
                    residuals.push(r);
                }
            }
            if residuals.is_empty() {
                continue;
            }
            residuals.sort_by(|a, b| a.partial_cmp(b).expect("residuals are finite"));
            let d = conformal_offset(&residuals, tau);
            delta[s * nq + k] = d;
            max_abs = max_abs.max(d.abs());
        }
    }

    let mut cov_raw = 0u64;
    let mut cov_cal = 0u64;
    let mut width_raw = 0.0f64;
    let mut width_cal = 0.0f64;
    let mut n_points = 0u64;
    for w in eval {
        let calibrated = apply_delta(&w.bands_mgdl, &delta, steps, nq, median_idx);
        for s in 0..steps {
            let t = w.realized_bg[s];
            let row = s * nq;
            let (rl, rh) = (w.bands_mgdl[row + idx_lo], w.bands_mgdl[row + idx_hi]);
            let (cl, ch) = (calibrated[row + idx_lo], calibrated[row + idx_hi]);
            cov_raw += ((t >= rl) && (t <= rh)) as u64;
            cov_cal += ((t >= cl) && (t <= ch)) as u64;
            width_raw += rh - rl;
            width_cal += ch - cl;
            n_points += 1;
        }
    }
    let per_point = |v: f64| (n_points > 0).then(|| v / n_points as f64);

    Ok(ConformalFit {
        delta,
        steps: steps as u32,
        n_quantiles: nq as u32,
        n_windows: n_kept as u32,
        n_cal: n_cal as u32,
        n_eval: eval.len() as u32,
        n_rejected,
        min_cal_windows: min_cal,
        sufficient: true,
        max_abs_delta_mgdl: max_abs,
        cov90_raw: per_point(cov_raw as f64),
        cov90_cal: per_point(cov_cal as f64),
        mean_width90_raw: per_point(width_raw),
        mean_width90_cal: per_point(width_cal),
    })
}

/// Apply a fitted `delta` (§8.4): add, restore the median exactly, then clamp outward from it so
/// the fan cannot cross. Both arrays are `steps · 7`, step-major in ascending τ. A length
/// mismatch, a non-finite value, or a delta that moves the median is `Err`, never a drawn fan.
#[uniffi::export]
pub fn apply_quantile_conformal(
    bands_mgdl: Vec<f64>,
    delta: Vec<f64>,
) -> Result<Vec<f64>, CoreError> {
    let nq = QUANTILE_LEVELS.len();
    let bad = |reason: String| CoreError::Internal { reason };
    let median_idx = tau_index(MEDIAN_TAU)
        .ok_or_else(|| bad(format!("MEDIAN_TAU {MEDIAN_TAU} is not a fan level")))?;
    if bands_mgdl.is_empty() || bands_mgdl.len() % nq != 0 {
        return Err(bad(format!(
            "fan length {} is not a multiple of {nq}",
            bands_mgdl.len()
        )));
    }
    if delta.len() != bands_mgdl.len() {
        return Err(bad(format!(
            "delta length {} does not match the fan's {}",
            delta.len(),
            bands_mgdl.len()
        )));
    }
    if !bands_mgdl.iter().all(|v| v.is_finite()) {
        return Err(bad("fan carries a non-finite value".into()));
    }
    let (steps, identity) = check_delta(&delta, median_idx, nq)?;
    if identity {
        return Ok(bands_mgdl); // §8.4: an all-zero delta is the identity.
    }
    Ok(apply_delta(&bands_mgdl, &delta, steps, nq, median_idx))
}

/// [`apply_quantile_conformal`] over many fans in one FFI crossing (§8.4).
///
/// `fans_mgdl` is `n_fans · steps · nq`, fan-major, each fan in the single-fan layout; one `delta`
/// corrects all. Fail-closed for the WHOLE batch, never a mix of corrected and raw fans.
#[uniffi::export]
pub fn apply_quantile_conformal_batch(
    fans_mgdl: Vec<f64>,
    delta: Vec<f64>,
) -> Result<Vec<f64>, CoreError> {
    let nq = QUANTILE_LEVELS.len();
    let bad = |reason: String| CoreError::Internal { reason };
    let median_idx = tau_index(MEDIAN_TAU)
        .ok_or_else(|| bad(format!("MEDIAN_TAU {MEDIAN_TAU} is not a fan level")))?;
    let (steps, identity) = check_delta(&delta, median_idx, nq)?;
    let fan_len = delta.len();
    if fans_mgdl.is_empty() || fans_mgdl.len() % fan_len != 0 {
        return Err(bad(format!(
            "batch length {} is not a whole number of {fan_len}-value fans",
            fans_mgdl.len()
        )));
    }
    if !fans_mgdl.iter().all(|v| v.is_finite()) {
        return Err(bad("batch carries a non-finite value".into()));
    }
    if identity {
        return Ok(fans_mgdl); // §8.4: an all-zero delta is the identity.
    }
    let mut out = vec![0.0f64; fans_mgdl.len()];
    for (fan, dst) in fans_mgdl
        .chunks_exact(fan_len)
        .zip(out.chunks_exact_mut(fan_len))
    {
        apply_delta_into(dst, fan, &delta, steps, nq, median_idx);
    }
    Ok(out)
}

/// The `delta`-side invariants of §8.4, shared by both applies. Returns `(steps, is_identity)`.
/// A non-zero median column would move the point forecast the dose calculator scores off, and
/// every band drawn around it would still look well-formed.
fn check_delta(delta: &[f64], median_idx: usize, nq: usize) -> Result<(usize, bool), CoreError> {
    let bad = |reason: String| CoreError::Internal { reason };
    if delta.is_empty() || delta.len() % nq != 0 {
        return Err(bad(format!(
            "delta length {} is not a multiple of {nq}",
            delta.len()
        )));
    }
    if !delta.iter().all(|v| v.is_finite()) {
        return Err(bad("delta carries a non-finite value".into()));
    }
    let steps = delta.len() / nq;
    if (0..steps).any(|s| delta[s * nq + median_idx] != 0.0) {
        return Err(bad("delta moves the median column".into()));
    }
    Ok((steps, delta.iter().all(|&d| d == 0.0)))
}

/// Unvalidated: the fit reuses it on fans it has already checked.
fn apply_delta(
    bands: &[f64],
    delta: &[f64],
    steps: usize,
    nq: usize,
    median_idx: usize,
) -> Vec<f64> {
    let mut out = vec![0.0f64; bands.len()];
    apply_delta_into(&mut out, bands, delta, steps, nq, median_idx);
    out
}

/// [`apply_delta`] into a caller-owned slice. `out` and `bands` are both `steps · nq`.
fn apply_delta_into(
    out: &mut [f64],
    bands: &[f64],
    delta: &[f64],
    steps: usize,
    nq: usize,
    median_idx: usize,
) {
    for s in 0..steps {
        let row = s * nq;
        for k in 0..nq {
            out[row + k] = bands[row + k] + delta[row + k];
        }
        out[row + median_idx] = bands[row + median_idx]; // exactly preserved, not merely +0.0
        for k in (0..median_idx).rev() {
            out[row + k] = out[row + k].min(out[row + k + 1]);
        }
        for k in (median_idx + 1)..nq {
            out[row + k] = out[row + k].max(out[row + k - 1]);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn window(bands: Vec<f64>, realized: Vec<f64>) -> ForecastWindow {
        let steps = realized.len();
        let median = tau_index(MEDIAN_TAU).unwrap();
        ForecastWindow {
            median_bg: (0..steps)
                .map(|s| bands[s * QUANTILE_LEVELS.len() + median])
                .collect(),
            bands_mgdl: bands,
            realized_bg: realized,
            last_bg: 100.0,
        }
    }

    /// `halves` innermost first.
    fn fan1(m: f64, halves: [f64; 3]) -> Vec<f64> {
        vec![
            m - halves[2],
            m - halves[1],
            m - halves[0],
            m,
            m + halves[0],
            m + halves[1],
            m + halves[2],
        ]
    }

    /// One-step windows whose truth is `100 + offsets[i]`, all on the same fan.
    fn set(offsets: &[f64]) -> Vec<ForecastWindow> {
        offsets
            .iter()
            .map(|&o| window(fan1(100.0, [5.0, 10.0, 15.0]), vec![100.0 + o]))
            .collect()
    }

    #[test]
    fn min_cal_windows_is_the_no_clamp_floor_of_the_level_tuple() {
        let n = conformal_min_cal_windows();
        assert_eq!(n, 19, "the seven levels of §6 resolve without clamping at n=19");
        let median = tau_index(MEDIAN_TAU).unwrap();
        for (k, &tau) in QUANTILE_LEVELS.iter().enumerate() {
            if k == median {
                continue;
            }
            assert!(order_index(n as usize, tau).is_some(), "tau {tau} clamps at n={n}");
        }
        assert!(
            QUANTILE_LEVELS
                .iter()
                .enumerate()
                .any(|(k, &t)| k != median && order_index(n as usize - 1, t).is_none()),
            "n-1 must clamp somewhere, else the floor is not tight",
        );
    }

    #[test]
    fn order_index_rounds_opposite_ways_across_the_median() {
        assert_eq!(order_index(19, 0.05), Some(1));
        assert_eq!(order_index(19, 0.95), Some(19));
        assert_eq!(order_index(20, 0.05), Some(1)); // 21·.05 = 1.05, floored
        assert_eq!(order_index(20, 0.95), Some(20)); // 21·.95 = 19.95, ceiled
        assert_eq!(order_index(40, 0.10), Some(4)); // floor(41·.10) = 4, ceil would be 5
        assert_eq!(order_index(40, 0.90), Some(37)); // ceil(41·.90) = 37, floor would be 36
    }

    #[test]
    fn offset_is_the_named_order_statistic_of_the_residuals() {
        let sorted: Vec<f64> = (1..=19).map(|i| i as f64).collect();
        assert_eq!(conformal_offset(&sorted, 0.05), 1.0);
        assert_eq!(conformal_offset(&sorted, 0.95), 19.0);
        assert_eq!(conformal_offset(&sorted, 0.25), 5.0); // floor(20·.25) = 5
        assert_eq!(conformal_offset(&sorted, 0.75), 15.0); // ceil(20·.75) = 15
    }

    #[test]
    fn fit_recovers_a_known_shift() {
        // Truth is the median + 3, so every level's residuals are one constant.
        let windows = set(&[3.0; 40]);
        let fit = fit_quantile_conformal(windows, 0).unwrap();
        assert!(fit.sufficient);
        assert_eq!(fit.steps, 1);
        let expect = [3.0 + 15.0, 3.0 + 10.0, 3.0 + 5.0, 0.0, 3.0 - 5.0, 3.0 - 10.0, 3.0 - 15.0];
        for (k, want) in expect.iter().enumerate() {
            assert!(
                (fit.delta[k] - want).abs() < 1e-12,
                "level {k}: got {}, want {want}",
                fit.delta[k],
            );
        }
    }

    #[test]
    fn fit_widens_the_hypo_edge_harder_when_the_truth_undershoots() {
        // Mostly on the median, with a long LOW tail.
        let mut offsets: Vec<f64> = vec![0.0; 60];
        for (i, o) in offsets.iter_mut().take(20).enumerate() {
            *o = -40.0 - i as f64;
        }
        offsets.rotate_left(7); // interleave so the split does not isolate the tail
        let fit = fit_quantile_conformal(set(&offsets), 0).unwrap();
        assert!(fit.sufficient);
        assert!(fit.delta[0] < 0.0, "tau .05 must move down, got {}", fit.delta[0]);
        assert!(
            fit.delta[0].abs() > fit.delta[6].abs(),
            "the hypo edge must move further than the hyper edge: {} vs {}",
            fit.delta[0],
            fit.delta[6],
        );
    }

    #[test]
    fn fit_refuses_below_the_minimum_and_returns_a_zero_delta() {
        // 20 windows ⇒ a 14-window calibration split, under the 19 the arithmetic needs.
        let fit = fit_quantile_conformal(set(&[3.0; 20]), 0).unwrap();
        assert!(!fit.sufficient);
        assert_eq!(fit.n_cal, 14);
        assert_eq!(fit.min_cal_windows, conformal_min_cal_windows());
        assert!(fit.delta.iter().all(|&d| d == 0.0), "a refusal must carry no correction");
        assert_eq!(fit.max_abs_delta_mgdl, 0.0);
        assert!(fit.cov90_raw.is_none());
    }

    #[test]
    fn fit_honours_a_caller_threshold_above_the_arithmetic_floor() {
        // 40 windows ⇒ a 28-window calibration split: enough for the arithmetic, short of 100.
        let fit = fit_quantile_conformal(set(&[3.0; 40]), 100).unwrap();
        assert!(!fit.sufficient);
        assert_eq!(fit.n_cal, 28);
        assert_eq!(fit.min_cal_windows, 100);
        assert!(fit.delta.iter().all(|&d| d == 0.0));
    }

    #[test]
    fn fit_never_moves_the_median_column() {
        let mut offsets: Vec<f64> = Vec::new();
        for i in 0..90 {
            offsets.push(((i * 37) % 61) as f64 - 30.0); // a spread with no symmetry
        }
        let fit = fit_quantile_conformal(set(&offsets), 0).unwrap();
        assert!(fit.sufficient);
        let median = tau_index(MEDIAN_TAU).unwrap();
        for s in 0..fit.steps as usize {
            assert_eq!(fit.delta[s * QUANTILE_LEVELS.len() + median], 0.0);
        }
    }

    #[test]
    fn fit_rejects_misordered_and_non_finite_windows_without_scoring_them() {
        let mut windows = set(&[3.0; 40]);
        let nq = QUANTILE_LEVELS.len();
        windows[0].bands_mgdl[nq - 1] = f64::NAN;
        windows[1].bands_mgdl.swap(0, nq - 1); // now descending
        windows[2].realized_bg[0] = f64::INFINITY;
        let fit = fit_quantile_conformal(windows, 0).unwrap();
        assert_eq!(fit.n_rejected, 3);
        assert_eq!(fit.n_windows, 37);
    }

    #[test]
    fn fit_is_total_on_empty_and_ragged_input() {
        let empty = fit_quantile_conformal(Vec::new(), 0).unwrap();
        assert!(!empty.sufficient);
        assert_eq!(empty.n_windows, 0);

        let mut ragged = set(&[1.0; 40]);
        ragged[3].realized_bg = vec![100.0, 101.0]; // two steps where the set has one
        let fit = fit_quantile_conformal(ragged, 0).unwrap();
        assert_eq!(fit.n_rejected, 1);
    }

    #[test]
    fn apply_of_a_zero_delta_is_the_identity() {
        let bands = [fan1(100.0, [5.0, 10.0, 15.0]), fan1(110.0, [6.0, 12.0, 20.0])].concat();
        let out = apply_quantile_conformal(bands.clone(), vec![0.0; bands.len()]).unwrap();
        assert_eq!(out, bands);
    }

    #[test]
    fn apply_holds_the_median_fixed() {
        let bands = [fan1(100.0, [5.0, 10.0, 15.0]), fan1(110.0, [6.0, 12.0, 20.0])].concat();
        let nq = QUANTILE_LEVELS.len();
        let median = tau_index(MEDIAN_TAU).unwrap();
        let mut delta = vec![0.0f64; bands.len()];
        for s in 0..2 {
            for k in 0..nq {
                if k != median {
                    delta[s * nq + k] = if k < median { -40.0 } else { 55.0 };
                }
            }
        }
        let out = apply_quantile_conformal(bands.clone(), delta).unwrap();
        for s in 0..2 {
            assert_eq!(out[s * nq + median], bands[s * nq + median]);
        }
    }

    #[test]
    fn apply_keeps_the_fan_monotone_under_a_crossing_delta() {
        let bands = fan1(100.0, [5.0, 10.0, 15.0]);
        let nq = QUANTILE_LEVELS.len();
        let median = tau_index(MEDIAN_TAU).unwrap();
        let delta = vec![90.0, 80.0, 70.0, 0.0, -70.0, -80.0, -90.0];
        let out = apply_quantile_conformal(bands, delta).unwrap();
        for k in 1..nq {
            assert!(out[k] >= out[k - 1], "fan crossed at {k}: {out:?}");
        }
        assert_eq!(out[median], 100.0);
        assert!(out[..median].iter().all(|&v| v == 100.0), "{out:?}");
        assert!(out[median + 1..].iter().all(|&v| v == 100.0), "{out:?}");
    }

    #[test]
    fn apply_refuses_a_delta_that_moves_the_median() {
        let bands = fan1(100.0, [5.0, 10.0, 15.0]);
        let mut delta = vec![0.0f64; bands.len()];
        delta[tau_index(MEDIAN_TAU).unwrap()] = 0.5;
        assert!(apply_quantile_conformal(bands, delta).is_err());
    }

    #[test]
    fn apply_refuses_a_shape_or_value_it_cannot_trust() {
        let bands = fan1(100.0, [5.0, 10.0, 15.0]);
        assert!(apply_quantile_conformal(bands.clone(), vec![0.0; 3]).is_err());
        assert!(apply_quantile_conformal(vec![1.0, 2.0, 3.0], vec![0.0; 3]).is_err());
        assert!(apply_quantile_conformal(Vec::new(), Vec::new()).is_err());
        let mut delta = vec![0.0f64; bands.len()];
        delta[0] = f64::NAN;
        assert!(apply_quantile_conformal(bands, delta).is_err());
    }

    #[test]
    fn batch_agrees_fan_for_fan_with_the_single_apply() {
        let fans: Vec<Vec<f64>> = vec![
            [fan1(100.0, [5.0, 10.0, 15.0]), fan1(110.0, [6.0, 12.0, 20.0])].concat(),
            [fan1(70.0, [4.0, 9.0, 14.0]), fan1(180.0, [8.0, 16.0, 25.0])].concat(),
            [fan1(250.0, [9.0, 18.0, 30.0]), fan1(55.0, [3.0, 7.0, 11.0])].concat(),
        ];
        let fan_len = fans[0].len();
        // Asymmetric and crossing on the low edge, so the clamp is exercised.
        let delta = vec![
            -30.0, -12.0, -4.0, 0.0, 6.0, 14.0, 33.0, // step 0
            -18.0, -9.0, 40.0, 0.0, 3.0, 11.0, 27.0, // step 1: .25 pushed past the median
        ];
        let batched = apply_quantile_conformal_batch(fans.concat(), delta.clone()).unwrap();
        assert_eq!(batched.len(), fans.len() * fan_len);
        for (i, fan) in fans.iter().enumerate() {
            let one = apply_quantile_conformal(fan.clone(), delta.clone()).unwrap();
            assert_eq!(&batched[i * fan_len..(i + 1) * fan_len], &one[..], "fan {i}");
        }
    }

    #[test]
    fn batch_of_a_zero_delta_is_the_identity() {
        let fans = [
            fan1(100.0, [5.0, 10.0, 15.0]),
            fan1(110.0, [6.0, 12.0, 20.0]),
            fan1(120.0, [7.0, 14.0, 22.0]),
        ]
        .concat();
        let out = apply_quantile_conformal_batch(fans.clone(), vec![0.0; QUANTILE_LEVELS.len()]);
        assert_eq!(out.unwrap(), fans);
    }

    #[test]
    fn batch_refuses_the_whole_batch_rather_than_correcting_part_of_it() {
        let fan = fan1(100.0, [5.0, 10.0, 15.0]);
        let delta = vec![-10.0, -5.0, -2.0, 0.0, 2.0, 5.0, 10.0];
        // Not a whole number of fans.
        let ragged = [fan.clone(), fan[..3].to_vec()].concat();
        assert!(apply_quantile_conformal_batch(ragged, delta.clone()).is_err());
        let mut poisoned = [fan.clone(), fan.clone(), fan.clone()].concat();
        poisoned[QUANTILE_LEVELS.len() + 1] = f64::INFINITY;
        assert!(apply_quantile_conformal_batch(poisoned, delta.clone()).is_err());
        assert!(apply_quantile_conformal_batch(Vec::new(), delta).is_err());
        let mut moves_median = vec![0.0f64; QUANTILE_LEVELS.len()];
        moves_median[tau_index(MEDIAN_TAU).unwrap()] = 0.5;
        assert!(apply_quantile_conformal_batch(fan, moves_median).is_err());
    }

    #[test]
    fn a_fitted_delta_applied_to_its_own_calibration_set_hits_the_nominal_rate() {
        // A spread far wider than the fan's ±15, its period dividing both splits exactly, so the
        // held-out distribution IS the calibration one rather than a sampling accident.
        let offsets: Vec<f64> = (0..400).map(|i| ((i % 40) as f64 - 19.5) * 3.0).collect();
        let fit = fit_quantile_conformal(set(&offsets), 0).unwrap();
        assert!(fit.sufficient);
        let raw = fit.cov90_raw.unwrap();
        let cal = fit.cov90_cal.unwrap();
        assert!(raw < 0.5, "the raw fan should be badly over-confident here, got {raw}");
        assert!(
            (cal - 0.90).abs() < 0.10,
            "the calibrated band should approach its nominal 0.90, got {cal} (raw {raw})",
        );
        assert!(fit.mean_width90_cal.unwrap() > fit.mean_width90_raw.unwrap());
    }
}
