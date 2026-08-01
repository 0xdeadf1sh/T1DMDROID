//! Split-conformal quantile recalibration of the BG band fan — `SPEC/inference.md` §8.4's
//! `delta` and its apply, fitted ON DEVICE from the patient's own matured windows.
//!
//! §8.4 is written for a delta the CHECKPOINT carries, fit on the simulator distribution, and it
//! says in the same breath that "for real-world CGM it must be re-fit per cohort or omitted". No
//! exported descriptor carries one and the export path drops it, so on a real sensor the fan the
//! phone draws is the raw fan. This module is the other half of that sentence: the cohort is one
//! patient, the calibration set is their own `(forecast, realized)` history, and the fit happens
//! here rather than upstream.
//!
//! What §8.4 fixes, and what this owes it:
//!
//! * the delta is **per-`(step, τ)`, additive, in mg/dL, downstream of `f_inv`** — so it is laid
//!   out exactly like [`ForecastWindow::bands_mgdl`] (`steps × 7`, step-major, ascending τ);
//! * the **median is held fixed** (`delta[.., median] = 0`), so the point forecast the dose
//!   calculator scores off cannot move;
//! * the fan **stays monotone** — no quantile crossing;
//! * an **all-zero delta is the identity**.
//!
//! The order statistic itself is the split-conformal one, and it is SIDE-AWARE: an upper edge
//! takes `ceil((n+1)·τ)` and a lower edge `floor((n+1)·τ)`. Using `ceil` on a lower edge sits that
//! edge one order statistic too high and lets realized BG escape BELOW it more often than the
//! nominal rate — anti-conservative on exactly the hypo edge, and worst at small calibration `n`.
//!
//! That rounding rule is **normative and shared**: §8.4 states it, this module implements it on
//! device, and `T1DMAI/conformal.py` implements it off-device. Neither implementation may be
//! adjusted on its own — both publish τ.05–.95 coverage under one name, and a rule that differed
//! between them would make those two different statistics. §8.4 is the copy to change.
//!
//! **Validity rests on exchangeability between the calibration set and the forecasts the delta is
//! later applied to**, which is why the fit here splits its input chronologically and reports the
//! coverage the correction achieved on windows it never saw. Two caveats a reader should carry:
//! consecutive windows on the five-minute grid overlap by 23 of their 24 steps and are therefore
//! anything but independent, and a patient whose behaviour changes invalidates a delta fitted
//! before it. Neither is repairable here; both are why the held-out figure is reported rather
//! than assumed.

use crate::accuracy::{tau_index, ForecastWindow, QUANTILE_LEVELS};
use crate::CoreError;

/// The level whose column the correction may never touch. Resolved to a position by lookup, like
/// every other level in `SPEC/invariants.md` §6.1 — never a literal 3.
const MEDIAN_TAU: f64 = 0.5;

/// Fraction of the (chronologically ordered) window set that becomes the CALIBRATION split; the
/// remainder is held out and scored, never fitted on.
///
/// Fixed here rather than exposed: it is part of the method, and a caller free to pass 1.0 would
/// get a delta whose only coverage evidence came from the residuals that produced it.
const CAL_FRACTION: f64 = 0.7;

/// The outer band whose realized coverage the held-out split reports — the extreme levels of the
/// fan, resolved by lookup for the same reason [`MEDIAN_TAU`] is.
const REPORT_TAU_LO: f64 = 0.05;
const REPORT_TAU_HI: f64 = 0.95;

/// A fitted per-`(step, τ)` correction and the evidence for it.
///
/// `delta` is `steps · n_quantiles`, step-major, ascending τ — the layout of
/// [`ForecastWindow::bands_mgdl`] and of `preproc::Forecast::bands_mgdl`, so a caller applies it
/// to a fan without transposing. It is all zeros whenever `sufficient` is false: the fail-closed
/// result is NO correction, never a correction extrapolated from too little history.
///
/// `cov90_raw` / `cov90_cal` are the realized τ.05–.95 coverage on the HELD-OUT split, before and
/// after the correction, pooled over every step of every held-out window. They are `None` when
/// the held-out split is empty. Their target is `REPORT_TAU_HI − REPORT_TAU_LO` = 0.90, and they
/// are the only reading under which a correction that merely inflated the band is distinguishable
/// from one that fixed it — hence `mean_width90_raw`/`_cal` beside them, which
/// `SPEC/invariants.md` §6.2 requires to travel with any band figure.
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
    /// The fail-closed result: no correction, and the counts that say why.
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

/// The smallest calibration count at which NO level's conformal order statistic is clamped.
///
/// Derived from [`QUANTILE_LEVELS`] rather than chosen: the index into the `n` sorted residuals is
/// `floor((n+1)·τ)` below the median and `ceil((n+1)·τ)` above it, 1-indexed, and both must land
/// inside `[1, n]`. Below this count the extreme levels' offsets ARE the minimum and the maximum
/// of the residual sample — a delta made of the two most extreme things that happened — and the
/// nominal coverage they claim is arithmetic that never ran. For the seven levels of §6 it is 19.
///
/// This is the floor of the arithmetic, not a statistical recommendation: 19 heavily-overlapping
/// windows is a little over an hour and a half of one patient's CGM. A caller should ask for far
/// more, and [`fit_quantile_conformal`] takes whichever of the two is larger.
#[uniffi::export]
pub fn conformal_min_cal_windows() -> u32 {
    let median = tau_index(MEDIAN_TAU);
    let mut need = 1u32;
    for (k, &tau) in QUANTILE_LEVELS.iter().enumerate() {
        if Some(k) == median {
            continue;
        }
        // Smallest n for which this level's 1-indexed order-statistic index is in [1, n].
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

/// The 1-indexed split-conformal order-statistic index for level `tau` over `n` residuals, or
/// `None` when it falls outside `[1, n]` (i.e. `n` is too small for this level to be resolved
/// without clamping).
fn order_index(n: usize, tau: f64) -> Option<usize> {
    if n == 0 {
        return None;
    }
    let scaled = (n + 1) as f64 * tau;
    // Below the median a LOWER bound is wanted and above it an UPPER one; the two round opposite
    // ways. See the module note — `ceil` on a lower edge is the anti-conservative hypo bug.
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

/// The empirical `tau`-quantile of the calibration residuals, as the split-conformal order
/// statistic. `residuals` is sorted ascending in place by the caller.
fn conformal_offset(sorted: &[f64], tau: f64) -> f64 {
    match order_index(sorted.len(), tau) {
        Some(i) => sorted[i - 1],
        // Clamped: `n` is below `conformal_min_cal_windows()`. The fit refuses long before this,
        // so reaching here means a caller overrode the floor; return the nearest extreme rather
        // than an out-of-bounds index.
        None if tau < MEDIAN_TAU => sorted[0],
        None => sorted[sorted.len() - 1],
    }
}

/// Fit a per-`(step, τ)` additive band correction from matured forecast windows (§8.4).
///
/// `windows` must be in the order they were made — the split is CHRONOLOGICAL, the older
/// [`CAL_FRACTION`] fitted on and the newer remainder held out and scored. A random split would
/// read better and mean less: the question a patient's delta has to answer is whether a
/// correction fitted on the past holds on the future, and only a forward split asks it.
///
/// `min_cal_windows` is the caller's own threshold on the CALIBRATION split, raised to
/// [`conformal_min_cal_windows`] when it is below the point where the arithmetic degenerates.
/// Under it the result is [`ConformalFit::sufficient`] `= false` with an all-zero `delta` — the
/// fail-closed outcome is the raw fan, not a fan corrected by whatever the handful of residuals
/// happened to be.
///
/// Windows are rejected (and counted in `n_rejected`) on the same terms
/// [`crate::forecast_metrics_suite`] rejects them: a shape that disagrees with the first window, a
/// non-finite value anywhere, or a fan that does not ascend. Total on hostile input; `Err` only on
/// a structurally impossible argument.
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

    // ── The fit: one one-sided order statistic per (step, level), each from its own residuals ──
    let mut delta = vec![0.0f64; steps * nq];
    let mut residuals: Vec<f64> = Vec::with_capacity(cal.len());
    let mut max_abs = 0.0f64;
    for s in 0..steps {
        for (k, &tau) in QUANTILE_LEVELS.iter().enumerate() {
            if k == median_idx {
                continue; // §8.4: the median is held fixed, so its correction is 0 by construction.
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

    // ── The held-out reading: what the correction actually bought, on windows it never saw ──
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

/// Apply a fitted `delta` to a band fan (§8.4): add, restore the median exactly, then clamp
/// outward from it so the fan cannot cross.
///
/// `bands_mgdl` and `delta` are both `steps · 7` step-major in ascending τ. An all-zero `delta`
/// returns the fan unchanged — checked, not merely implied, so the uncalibrated path costs one
/// scan and allocates nothing new.
///
/// Fail-closed: a length mismatch, a non-finite value, or a `delta` whose median column is not
/// exactly zero yields `Err` rather than a fan silently drawn around a moved point forecast.
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
    if !delta.iter().all(|v| v.is_finite()) || !bands_mgdl.iter().all(|v| v.is_finite()) {
        return Err(bad("fan or delta carries a non-finite value".into()));
    }
    let steps = bands_mgdl.len() / nq;
    // The one invariant a caller cannot be trusted with: a non-zero median column would move the
    // point forecast the dose calculator scores off, and every band drawn around it would still
    // look well-formed.
    if (0..steps).any(|s| delta[s * nq + median_idx] != 0.0) {
        return Err(bad("delta moves the median column".into()));
    }
    if delta.iter().all(|&d| d == 0.0) {
        return Ok(bands_mgdl); // §8.4: an all-zero delta is the identity.
    }
    Ok(apply_delta(&bands_mgdl, &delta, steps, nq, median_idx))
}

/// The apply itself, once both arrays are known well-formed. Kept separate so the fit can score
/// its own held-out split without re-validating a fan it has already checked.
fn apply_delta(
    bands: &[f64],
    delta: &[f64],
    steps: usize,
    nq: usize,
    median_idx: usize,
) -> Vec<f64> {
    let mut out = vec![0.0f64; bands.len()];
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
    out
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

    /// A one-step fan centred on `m` with symmetric half-widths, ascending by construction.
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

    /// `n` one-step windows whose truth is `m + offsets[i]`, all on the same fan.
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
        // At the floor every non-median level resolves; one below, at least one does not.
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
        // n = 19, (n+1)·tau: .05 → 1.0, .95 → 19.0, .10 → 2.0, .25 → 5.0, .75 → 15.0.
        assert_eq!(order_index(19, 0.05), Some(1));
        assert_eq!(order_index(19, 0.95), Some(19));
        // n = 20, (n+1)·tau: .05 → 1.05 (floor 1), .95 → 19.95 (ceil 20).
        assert_eq!(order_index(20, 0.05), Some(1));
        assert_eq!(order_index(20, 0.95), Some(20));
        // The asymmetry that matters: a LOWER edge floors. Ceiling it here would sit the hypo
        // edge one order statistic too high.
        assert_eq!(order_index(40, 0.10), Some(4)); // floor(41·.10) = 4, ceil would be 5
        assert_eq!(order_index(40, 0.90), Some(37)); // ceil(41·.90) = 37, floor would be 36
    }

    #[test]
    fn offset_is_the_named_order_statistic_of_the_residuals() {
        // 19 residuals 1..=19; the .05 offset is the 1st and the .95 offset the 19th.
        let sorted: Vec<f64> = (1..=19).map(|i| i as f64).collect();
        assert_eq!(conformal_offset(&sorted, 0.05), 1.0);
        assert_eq!(conformal_offset(&sorted, 0.95), 19.0);
        assert_eq!(conformal_offset(&sorted, 0.25), 5.0); // floor(20·.25) = 5
        assert_eq!(conformal_offset(&sorted, 0.75), 15.0); // ceil(20·.75) = 15
    }

    #[test]
    fn fit_recovers_a_known_shift() {
        // Truth is always the fan's median + 3 mg/dL. Every level's residuals are then the
        // constant `3 − (band_k − median)`, so every order statistic of them is that constant and
        // the fitted delta is exactly the offset that lands each edge 3 above where it was.
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
        // An asymmetric truth distribution: mostly on the median, with a long LOW tail. The lower
        // edges must be pushed DOWN (negative delta) further than the upper edges are pushed up.
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

    // ── apply ──────────────────────────────────────────────────────────────────────────────

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
        // A delta that would swamp the fan if it touched the median at all.
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
        // Deltas chosen to cross every edge past its neighbour and past the median.
        let delta = vec![90.0, 80.0, 70.0, 0.0, -70.0, -80.0, -90.0];
        let out = apply_quantile_conformal(bands, delta).unwrap();
        for k in 1..nq {
            assert!(out[k] >= out[k - 1], "fan crossed at {k}: {out:?}");
        }
        assert_eq!(out[median], 100.0);
        // Everything below the median was pushed up past it, so it clamps AT the median.
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
    fn a_fitted_delta_applied_to_its_own_calibration_set_hits_the_nominal_rate() {
        // The property the fit exists for: with the calibration and evaluation distributions
        // identical, the calibrated τ.05–.95 band covers ~90 % where the raw one is over-confident.
        // A deterministic spread far wider than the fan's ±15, so the raw band under-covers. Its
        // period divides both splits exactly, so the held-out distribution IS the calibration one
        // and the reported coverage is the property under test rather than a sampling accident.
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
