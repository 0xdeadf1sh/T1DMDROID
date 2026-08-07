//! The classical forecast baseline — direct multi-step ridge regression on lagged CGM plus
//! causal insulin- and carbohydrate-on-board.
//!
//! It exists to answer one question the neural model cannot answer about itself: whether the
//! transformer earns its footprint. The literature's answer is that it barely does — on
//! OhioT1DM the whole published field spans under 2 mg/dL RMSE at a 30-minute horizon, and
//! beats zero-order hold by about five — so the comparison is only worth making if the
//! baseline is a fair opponent rather than a straw one. Three choices follow from that:
//!
//! * **Direct multi-step, not recursive.** One weight vector per horizon step. Recursive
//!   roll-forward accumulates its own error and buys extra lag for it.
//! * **Fitted on the patient**, not shipped pre-trained. Individualization is the single
//!   largest effect in the published comparisons — larger than linear-vs-nonlinear.
//! * **The same band machinery as the neural fan.** The fan here is not a second interval
//!   method; it is `SPEC/inference.md` §8.4's split conformal applied to a degenerate fan,
//!   so both models' quantiles mean the same thing and `SPEC/invariants.md` §6.2's band
//!   projection scores them on one basis.
//!
//! **On-board is computed causally here, and that deliberately differs from [`crate::on_board`].**
//! That function sums the remaining tail of *every* matching event, which is right at "now"
//! and wrong at a historical step: an event logged after `t` would contribute to the feature
//! at `t` and leak the future into a fit. [`on_board_series`] gates each event on having
//! started, which is the strictly-causal requirement `SPEC/inference.md` §7.1 puts on any
//! consumer-side transform of the model input.
//!
//! The rails are the **clinical physical BG domain**, not a model risk space. The baseline has
//! no risk space to name — it regresses mg/dL onto mg/dL — and `SPEC/invariants.md` §4 rule 1
//! makes an unnamed risk value a defect, so there is deliberately no risk-space field on
//! [`BaselineForecast`].

use crate::accuracy::{ForecastWindow, QUANTILE_LEVELS};
use crate::conformal::{apply_quantile_conformal, fit_quantile_conformal, ConformalFit};
use crate::curve::{CurveEvent, CurveKind, STEP_MS};
use crate::preproc::{fan_is_ascending, fan_is_collapsed, median_is_rail_pinned, ForecastStatus};
use crate::{CoreError, CLINICAL_BG_CLAMP_MAX, CLINICAL_BG_CLAMP_MIN};

/// Fraction of the usable rows the ridge itself is fitted on; the remainder becomes the
/// conformal window set.
///
/// The two splits are nested on purpose. The ridge never sees a step the conformal fit
/// calibrates on, and [`fit_quantile_conformal`] splits its own input again, so the coverage
/// it reports is measured on windows that neither the weights nor the delta were fitted to.
/// A single split would let the band correction quietly repair the weights' in-sample optimism.
const RIDGE_FIT_FRACTION: f64 = 0.6;

/// Hard cap on the fit window, in 5-minute steps — 8 weeks. The normal-equation accumulation is
/// `O(n·d²)` and would happily chew a decade of history on the phone's main-thread budget if a
/// caller asked it to.
const MAX_FIT_STEPS: usize = 16_128;

/// Ceiling on the lag order. Twelve lags is one hour of context; past a few hours the design
/// matrix is mostly collinear and the Cholesky conditioning degrades for no accuracy.
const MAX_LAGS: u32 = 72;

/// Ceiling on the forecast horizon, in steps. The neural model's default is 24 (2 h).
const MAX_HORIZON: u32 = 72;

/// Ridge shrinkage floor. The normal equations are formed on standardized columns, so `lambda`
/// is comparable across features; a non-positive value would leave a singular system for a
/// patient whose COB is identically zero.
const MIN_LAMBDA: f64 = 1e-6;

/// The baseline's shape and shrinkage. Defaults come from [`baseline_default_spec`].
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct BaselineSpec {
    /// Lagged BG values in the design row, most-recent first. 12 ≙ one hour.
    pub n_lags: u32,
    /// Forecast steps produced, `1..=horizon_steps` ahead. 24 ≙ the neural model's 2 h.
    pub horizon_steps: u32,
    /// Ridge penalty on the standardized columns.
    pub ridge_lambda: f64,
    /// Include causal insulin-on-board as a feature.
    pub use_iob: bool,
    /// Include causal carbohydrate-on-board as a feature.
    pub use_cob: bool,
}

/// The shipped defaults: 12 lags (1 h), 24 steps (2 h), λ = 1.0, both on-board channels on.
#[uniffi::export]
pub fn baseline_default_spec() -> BaselineSpec {
    BaselineSpec {
        n_lags: 12,
        horizon_steps: 24,
        ridge_lambda: 1.0,
        use_iob: true,
        use_cob: true,
    }
}

/// A fitted baseline: the folded weights, the band estimator, and the provenance for both.
///
/// `weights` is `horizon_steps × (1 + n_features)`, row-major per horizon, intercept first. The
/// column standardization used during the solve is **folded into these weights**, so a prediction
/// is a raw dot product over the untransformed feature row and no per-call rescaling is needed.
///
/// **`band_delta` is part of the model, not a correction applied to it.** The distinction matters
/// because `SPEC/inference.md` §8.4's delta is a post-hoc, display-only recalibration of a fan the
/// neural model already produced, and the phone is forbidden from storing or transmitting a fan
/// that has one applied. Here the arithmetic is the same but its role is not: a ridge fit has no
/// interval of its own, so the residual quantiles ARE its interval estimator, fitted from the same
/// history in the same call and carried in the same record. There is never a "raw" and a
/// "calibrated" baseline fan to choose between — [`baseline_predict`] produces exactly one, which
/// is why the delta lives in here rather than being passed alongside.
///
/// All zeros until a fit has enough held-out history for the seven levels to resolve; that is the
/// fail-closed state, and it renders as a collapsed band the degeneracy guard withholds.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct BaselineModel {
    pub spec: BaselineSpec,
    /// Feature count excluding the intercept.
    pub n_features: u32,
    pub weights: Vec<f64>,
    /// `horizon_steps × 7`, step-major in ascending τ — the layout of `bands_mgdl`.
    pub band_delta: Vec<f64>,
    pub n_train_rows: u32,
    pub fitted_at_ms: i64,
    pub train_from_ms: i64,
    pub train_to_ms: i64,
}

/// One manual fit: the model, its band calibration, and the held-out evidence for both.
///
/// `holdout_rmse_mgdl` is per horizon step, measured on the conformal split — rows the ridge
/// never saw. It is a **median-line** figure, not the band projection of `SPEC/invariants.md`
/// §6.2, and the two must not be reported in one column.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct BaselineFit {
    pub model: BaselineModel,
    pub conformal: ConformalFit,
    pub holdout_rmse_mgdl: Vec<f64>,
    pub n_holdout_windows: u32,
    /// Zero-order-hold RMSE per horizon over the same held-out windows — the number that decides
    /// whether anything here, neural or classical, earned its footprint.
    pub persistence_rmse_mgdl: Vec<f64>,
}

/// A baseline forecast. mg/dL only: there is no risk space to name (§4 rule 1).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct BaselineForecast {
    pub median_bg: Vec<f64>,
    pub bands_mgdl: Vec<f64>,
}

/// Causal on-board series over a grid: for each step, the remaining tail area of every matching
/// event **that has already started**.
///
/// `out[i] += suffix_e[i - offset_e]` for `i ∈ [offset_e, offset_e + len_e)`, which is
/// [`crate::on_board`]'s definition restricted to started events — see the module note on why the
/// restriction is not optional. Cost is `O(Σ len_e)`, one scatter per event, rather than the
/// `O(n · Σ len_e)` a per-step call would pay.
fn on_board_series(
    events: &[CurveEvent],
    kind: CurveKind,
    grid_start_ms: i64,
    n: usize,
    out: &mut [f64],
    suffix: &mut Vec<f64>,
) {
    out[..n].fill(0.0);
    for ev in events {
        if ev.kind != kind || ev.values.is_empty() {
            continue;
        }
        // The scatter below indexes `values` by GRID steps, so an event sampled at any other
        // cadence would be laid down at the wrong times. Every producer in the crate emits
        // `step_ms == STEP_MS`; one that did not is skipped rather than silently misplaced, which
        // is also where `crate::on_board` and this function would otherwise disagree.
        if ev.step_ms != STEP_MS {
            continue;
        }
        let offset = (((ev.start_ms - grid_start_ms) as f64) / STEP_MS as f64).round() as i64;
        let len = ev.values.len();
        // Nothing to scatter if the event's action ends before the grid or starts after it.
        if offset >= n as i64 || offset + (len as i64) <= 0 {
            continue;
        }
        suffix.clear();
        suffix.resize(len + 1, 0.0);
        for j in (0..len).rev() {
            let v = ev.values[j];
            suffix[j] = suffix[j + 1] + if v.is_finite() { v } else { 0.0 };
        }
        // Causality: an event contributes only from the step it starts at. `offset < 0` (a dose
        // predating the grid) still contributes its overlapping tail, which is a started event.
        let lo = offset.max(0) as usize;
        let hi = ((offset + len as i64) as usize).min(n);
        for (i, slot) in out.iter_mut().enumerate().take(hi).skip(lo) {
            let m = (i as i64 - offset) as usize;
            *slot += suffix[m];
        }
    }
}

/// The causal on-board amount at one instant — the feature [`baseline_predict`] consumes.
///
/// This exists rather than the caller reaching for [`crate::on_board`] because the two differ on
/// announced future doses: `on_board` counts every matching event's remaining tail, which is right
/// for "insulin still to act" at now, while a design feature must count only what had already been
/// taken. The fit computes its column with [`on_board_series`], so a live cycle computing the same
/// column any other way would train on one definition and infer on another — a bias that is
/// invisible in every forecast it produces.
#[uniffi::export]
pub fn baseline_on_board_at(events: Vec<CurveEvent>, at_ms: i64, kind: CurveKind) -> f64 {
    let mut out = [0.0f64; 1];
    let mut suffix = Vec::new();
    on_board_series(&events, kind, at_ms, 1, &mut out, &mut suffix);
    out[0]
}

/// In-place Cholesky `A = L·Lᵀ` followed by forward/back substitution, solving `A·x = b`.
///
/// `a` is `d×d` row-major and is overwritten with `L`; `b` is overwritten with the solution.
/// Returns false if `A` is not positive definite, which with a positive ridge on standardized
/// columns means the caller handed in a non-finite design.
fn cholesky_solve_in_place(a: &mut [f64], b: &mut [f64], d: usize) -> bool {
    for i in 0..d {
        for j in 0..=i {
            let mut sum = a[i * d + j];
            for k in 0..j {
                sum -= a[i * d + k] * a[j * d + k];
            }
            if i == j {
                if !(sum > 0.0) || !sum.is_finite() {
                    return false;
                }
                a[i * d + i] = sum.sqrt();
            } else {
                a[i * d + j] = sum / a[j * d + j];
            }
        }
    }
    // L·y = b
    for i in 0..d {
        let mut sum = b[i];
        for k in 0..i {
            sum -= a[i * d + k] * b[k];
        }
        b[i] = sum / a[i * d + i];
    }
    // Lᵀ·x = y
    for i in (0..d).rev() {
        let mut sum = b[i];
        for k in (i + 1)..d {
            sum -= a[k * d + i] * b[k];
        }
        b[i] = sum / a[i * d + i];
    }
    b.iter().all(|v| v.is_finite())
}

/// Validate a spec and return `(n_lags, horizon, n_features)`.
fn checked_shape(spec: &BaselineSpec) -> Result<(usize, usize, usize), CoreError> {
    let bad = |reason: String| CoreError::Internal { reason };
    if spec.n_lags == 0 || spec.n_lags > MAX_LAGS {
        return Err(bad(format!(
            "baseline n_lags {} outside 1..={MAX_LAGS}",
            spec.n_lags
        )));
    }
    if spec.horizon_steps == 0 || spec.horizon_steps > MAX_HORIZON {
        return Err(bad(format!(
            "baseline horizon_steps {} outside 1..={MAX_HORIZON}",
            spec.horizon_steps
        )));
    }
    if !spec.ridge_lambda.is_finite() || spec.ridge_lambda < MIN_LAMBDA {
        return Err(bad(format!(
            "baseline ridge_lambda {} must be finite and >= {MIN_LAMBDA}",
            spec.ridge_lambda
        )));
    }
    let p = spec.n_lags as usize;
    let h = spec.horizon_steps as usize;
    let d = p + spec.use_iob as usize + spec.use_cob as usize;
    Ok((p, h, d))
}

/// Fill one design row for the step at grid index `t`. Returns false if any component is
/// non-finite (a CGM gap in the lag span), which drops the row rather than imputing it —
/// `SPEC/invariants.md` §1 makes gap-filling a presentation step, never a stored or fitted value.
#[inline]
fn design_row(
    bg: &[f64],
    iob: &[f64],
    cob: &[f64],
    spec: &BaselineSpec,
    p: usize,
    t: usize,
    row: &mut [f64],
) -> bool {
    for j in 0..p {
        let v = bg[t - j];
        if !v.is_finite() {
            return false;
        }
        row[j] = v;
    }
    let mut k = p;
    if spec.use_iob {
        let v = iob[t];
        if !v.is_finite() {
            return false;
        }
        row[k] = v;
        k += 1;
    }
    if spec.use_cob {
        let v = cob[t];
        if !v.is_finite() {
            return false;
        }
        row[k] = v;
    }
    true
}

/// Fit the baseline and calibrate its band fan, in one pass over the patient's own history.
///
/// `bg_mgdl` is a grid-aligned trailing series starting at `grid_start_ms`, newest last, with a
/// non-finite entry marking a gap. `events` are the resolved carb and insulin curves covering the
/// same span (and, for on-board to be right at the start of the window, the tails of anything
/// still acting when it opens).
///
/// The window is split chronologically: [`RIDGE_FIT_FRACTION`] of the usable rows fit the
/// weights, the remainder become [`ForecastWindow`]s scored against the truth and handed to
/// [`fit_quantile_conformal`]. A fit whose held-out set is too small for the seven levels to
/// resolve returns a `sufficient = false` calibration and an all-zero delta — the fail-closed
/// outcome is a model with no band, which the degeneracy guard then withholds.
#[uniffi::export]
pub fn fit_baseline_ridge(
    bg_mgdl: Vec<f64>,
    grid_start_ms: i64,
    events: Vec<CurveEvent>,
    spec: BaselineSpec,
    now_ms: i64,
    min_cal_windows: u32,
) -> Result<BaselineFit, CoreError> {
    let bad = |reason: String| CoreError::Internal { reason };
    let (p, h, d) = checked_shape(&spec)?;
    let n = bg_mgdl.len();
    if n > MAX_FIT_STEPS {
        return Err(bad(format!(
            "baseline fit window {n} steps exceeds cap {MAX_FIT_STEPS}"
        )));
    }
    // A row needs `p` lags behind it and `h` realized steps ahead of it.
    if n < p + h + 1 {
        return Err(bad(format!(
            "baseline fit needs at least {} steps, got {n}",
            p + h + 1
        )));
    }

    let mut iob = vec![0.0f64; n];
    let mut cob = vec![0.0f64; n];
    let mut suffix: Vec<f64> = Vec::new();
    if spec.use_iob {
        on_board_series(&events, CurveKind::Insulin, grid_start_ms, n, &mut iob, &mut suffix);
    }
    if spec.use_cob {
        on_board_series(&events, CurveKind::Carb, grid_start_ms, n, &mut cob, &mut suffix);
    }

    // Usable anchor steps: `t` has a full lag span behind and a full horizon ahead.
    let t_lo = p - 1;
    let t_hi = n - h; // exclusive
    let usable = t_hi - t_lo;
    let n_ridge = ((usable as f64) * RIDGE_FIT_FRACTION).floor() as usize;
    if n_ridge < d + 2 {
        return Err(bad(format!(
            "baseline fit split leaves {n_ridge} ridge rows for {d} features; need at least {}",
            d + 2
        )));
    }
    let split_t = t_lo + n_ridge; // first held-out anchor

    // ── Pass 1: column means and scales over the ridge rows ────────────────────────────────
    let mut row = vec![0.0f64; d];
    let mut mean = vec![0.0f64; d];
    let mut m2 = vec![0.0f64; d];
    let mut n_rows = 0usize;
    for t in t_lo..split_t {
        if !design_row(&bg_mgdl, &iob, &cob, &spec, p, t, &mut row) {
            continue;
        }
        n_rows += 1;
        let inv = 1.0 / n_rows as f64;
        for j in 0..d {
            let delta = row[j] - mean[j];
            mean[j] += delta * inv;
            m2[j] += delta * (row[j] - mean[j]);
        }
    }
    if n_rows < d + 2 {
        return Err(bad(format!(
            "baseline fit has {n_rows} complete rows for {d} features (gaps dropped)"
        )));
    }
    // A constant column carries no information; scale 1.0 keeps the solve well-posed and its
    // standardized values become 0, so the fold-back contributes nothing but the intercept.
    let mut scale = vec![1.0f64; d];
    for j in 0..d {
        let var = m2[j] / (n_rows as f64 - 1.0);
        if var.is_finite() && var > 1e-12 {
            scale[j] = var.sqrt();
        }
    }

    // ── Pass 2: per-horizon normal equations on the standardized, target-centred system ────
    // One `XᵀX` and one `Xᵀy` per horizon: the usable row set differs by horizon (a gap `k`
    // steps ahead invalidates only horizon `k`), so a shared Gram matrix would be wrong.
    let dd = d * d;
    let mut gram = vec![0.0f64; dd * h];
    let mut xty = vec![0.0f64; d * h];
    let mut colsum = vec![0.0f64; d * h];
    let mut ybar = vec![0.0f64; h];
    let mut ycount = vec![0.0f64; h];
    let mut z = vec![0.0f64; d];

    for t in t_lo..split_t {
        if !design_row(&bg_mgdl, &iob, &cob, &spec, p, t, &mut row) {
            continue;
        }
        for j in 0..d {
            z[j] = (row[j] - mean[j]) / scale[j];
        }
        for k in 0..h {
            let y = bg_mgdl[t + 1 + k];
            if !y.is_finite() {
                continue;
            }
            ycount[k] += 1.0;
            ybar[k] += (y - ybar[k]) / ycount[k];
            let g = &mut gram[k * dd..(k + 1) * dd];
            let xy = &mut xty[k * d..(k + 1) * d];
            let cs = &mut colsum[k * d..(k + 1) * d];
            for i in 0..d {
                let zi = z[i];
                xy[i] += zi * y;
                cs[i] += zi;
                let gr = &mut g[i * d..i * d + d];
                for (j, slot) in gr.iter_mut().enumerate().take(i + 1) {
                    *slot += zi * z[j];
                }
            }
        }
    }

    // ── Solve, then fold the standardization back into raw-feature weights ─────────────────
    let mut weights = vec![0.0f64; h * (1 + d)];
    let mut a = vec![0.0f64; dd];
    let mut b = vec![0.0f64; d];
    for k in 0..h {
        let cnt = ycount[k];
        if cnt < (d + 2) as f64 {
            return Err(bad(format!(
                "baseline horizon {} has {cnt} usable targets for {d} features",
                k + 1
            )));
        }
        // The columns are standardized on the RIDGE rows, but this horizon's usable rows are a
        // subset of those (a gap `k` steps ahead invalidates only horizon `k`), so the column
        // means are not zero here and an intercept-free solve would be biased. Both sides carry
        // the exact correction: `Z_cᵀZ_c = ZᵀZ − n·z̄z̄ᵀ` and `Z_cᵀy_c = Zᵀy − ȳ·Zᵀ1`.
        let g = &gram[k * dd..(k + 1) * dd];
        let xy = &xty[k * d..(k + 1) * d];
        let cs = &colsum[k * d..(k + 1) * d];
        let inv_n = 1.0 / cnt;
        for i in 0..d {
            for j in 0..=i {
                let v = g[i * d + j] - cs[i] * cs[j] * inv_n;
                a[i * d + j] = v;
                a[j * d + i] = v;
            }
            a[i * d + i] += spec.ridge_lambda;
            b[i] = xy[i] - ybar[k] * cs[i];
        }
        if !cholesky_solve_in_place(&mut a, &mut b, d) {
            return Err(bad(format!(
                "baseline horizon {} normal equations are not positive definite",
                k + 1
            )));
        }
        // Standardized intercept `a = ȳ − wᵀz̄`, then fold the standardization into raw-feature
        // weights so a prediction is one dot product over untransformed inputs.
        let w = &mut weights[k * (1 + d)..(k + 1) * (1 + d)];
        let mut intercept = ybar[k];
        for i in 0..d {
            intercept -= b[i] * cs[i] * inv_n;
        }
        for i in 0..d {
            let raw = b[i] / scale[i];
            w[1 + i] = raw;
            intercept -= raw * mean[i];
        }
        w[0] = intercept;
    }

    // The band estimator is not known until the held-out roll below has been scored, so the roll
    // runs against a model whose `band_delta` is still empty. That is sound because the roll reads
    // only the weights — and it is also why `predict_median` rather than `baseline_predict` is
    // what the roll calls.
    let model = BaselineModel {
        spec,
        n_features: d as u32,
        weights,
        band_delta: Vec::new(),
        n_train_rows: n_rows as u32,
        fitted_at_ms: now_ms,
        train_from_ms: grid_start_ms + (t_lo as i64) * STEP_MS,
        train_to_ms: grid_start_ms + (split_t as i64) * STEP_MS,
    };

    // ── The held-out roll: synthetic windows for the conformal fit and the honest RMSE ─────
    let nq = QUANTILE_LEVELS.len();
    let mut windows: Vec<ForecastWindow> = Vec::with_capacity(t_hi.saturating_sub(split_t));
    let mut se = vec![0.0f64; h];
    let mut se_persist = vec![0.0f64; h];
    let mut se_n = vec![0.0f64; h];
    for t in split_t..t_hi {
        if !design_row(&bg_mgdl, &iob, &cob, &spec, p, t, &mut row) {
            continue;
        }
        let mut realized = Vec::with_capacity(h);
        let mut ok = true;
        for k in 0..h {
            let y = bg_mgdl[t + 1 + k];
            if !y.is_finite() {
                ok = false;
                break;
            }
            realized.push(y);
        }
        if !ok {
            continue;
        }
        let last_bg = row[0];
        let median = predict_median(&model, &row, d, h);
        let mut bands = vec![0.0f64; h * nq];
        for (k, &m) in median.iter().enumerate() {
            let base = k * nq;
            for slot in bands[base..base + nq].iter_mut() {
                *slot = m;
            }
            let err = m - realized[k];
            se[k] += err * err;
            let perr = last_bg - realized[k];
            se_persist[k] += perr * perr;
            se_n[k] += 1.0;
        }
        windows.push(ForecastWindow {
            bands_mgdl: bands,
            median_bg: median,
            realized_bg: realized,
            last_bg,
        });
    }

    let n_holdout = windows.len() as u32;
    let conformal = fit_quantile_conformal(windows, min_cal_windows)?;
    let rmse = |sum: &[f64], cnt: &[f64]| -> Vec<f64> {
        (0..h)
            .map(|k| {
                if cnt[k] > 0.0 {
                    (sum[k] / cnt[k]).sqrt()
                } else {
                    f64::NAN
                }
            })
            .collect()
    };

    Ok(BaselineFit {
        model: BaselineModel {
            band_delta: conformal.delta.clone(),
            ..model
        },
        conformal,
        holdout_rmse_mgdl: rmse(&se, &se_n),
        n_holdout_windows: n_holdout,
        persistence_rmse_mgdl: rmse(&se_persist, &se_n),
    })
}

/// The raw dot product, per horizon, clamped to the clinical physical domain.
fn predict_median(model: &BaselineModel, row: &[f64], d: usize, h: usize) -> Vec<f64> {
    let mut out = Vec::with_capacity(h);
    for k in 0..h {
        let w = &model.weights[k * (1 + d)..(k + 1) * (1 + d)];
        let mut acc = w[0];
        for (j, &x) in row.iter().enumerate().take(d) {
            acc += w[1 + j] * x;
        }
        out.push(acc.clamp(CLINICAL_BG_CLAMP_MIN, CLINICAL_BG_CLAMP_MAX));
    }
    out
}

/// Run a fitted baseline for one cycle.
///
/// `bg_tail` is the trailing `n_lags` mg/dL values **oldest→newest**; `iob`/`cob` are the causal
/// on-board amounts at the anchor. The band estimator comes from the model itself
/// ([`BaselineModel::band_delta`]); an unfitted one is all zeros, the fan stays degenerate, and
/// [`baseline_degeneracy_check`] withholds it as a collapsed band. That is the intended
/// uncalibrated behaviour: a median with no honest interval is not shown.
#[uniffi::export]
pub fn baseline_predict(
    model: &BaselineModel,
    bg_tail: Vec<f64>,
    iob: f64,
    cob: f64,
) -> Result<BaselineForecast, CoreError> {
    let bad = |reason: String| CoreError::Internal { reason };
    let (p, h, d) = checked_shape(&model.spec)?;
    if model.n_features as usize != d {
        return Err(bad(format!(
            "baseline model declares {} features but its spec implies {d}",
            model.n_features
        )));
    }
    if model.weights.len() != h * (1 + d) {
        return Err(bad(format!(
            "baseline weights length {} != horizon {h} × (1 + {d})",
            model.weights.len()
        )));
    }
    if bg_tail.len() != p {
        return Err(bad(format!(
            "baseline bg_tail length {} != n_lags {p}",
            bg_tail.len()
        )));
    }
    if !bg_tail.iter().all(|v| v.is_finite()) {
        return Err(bad("baseline bg_tail has a gap; a forecast is withheld".into()));
    }
    if model.spec.use_iob && !iob.is_finite() {
        return Err(bad("baseline iob is not finite".into()));
    }
    if model.spec.use_cob && !cob.is_finite() {
        return Err(bad("baseline cob is not finite".into()));
    }

    // `bg_tail` arrives oldest→newest; the design row is most-recent-first.
    let mut row = vec![0.0f64; d];
    for j in 0..p {
        row[j] = bg_tail[p - 1 - j];
    }
    let mut k = p;
    if model.spec.use_iob {
        row[k] = iob;
        k += 1;
    }
    if model.spec.use_cob {
        row[k] = cob;
    }

    let median = predict_median(model, &row, d, h);
    let nq = QUANTILE_LEVELS.len();
    let mut bands = vec![0.0f64; h * nq];
    for (i, &m) in median.iter().enumerate() {
        let base = i * nq;
        for slot in bands[base..base + nq].iter_mut() {
            *slot = m;
        }
    }
    // The degenerate fan is the identity input to §8.4's apply: with an all-zero delta it comes
    // back unchanged, and with a fitted one it opens into the residual quantiles around a median
    // the correction is forbidden to move.
    let mut bands_mgdl = if model.band_delta.is_empty() {
        bands
    } else {
        apply_quantile_conformal(bands, model.band_delta.clone())?
    };
    // The delta is added downstream of the median's own clamp, so an edge can land outside the
    // physical domain even though the median cannot. The neural path never has this problem — its
    // `f_inv` clamps every band edge on the way out — so clamp here to keep both models' fans
    // inside the same rails. Order is preserved: clamping a monotone sequence to an interval
    // leaves it monotone.
    for v in bands_mgdl.iter_mut() {
        *v = v.clamp(CLINICAL_BG_CLAMP_MIN, CLINICAL_BG_CLAMP_MAX);
    }

    Ok(BaselineForecast {
        median_bg: median,
        bands_mgdl,
    })
}

/// The §3.6-B guard, for a forecast with no risk space.
///
/// Shares [`crate::preproc`]'s predicates and epsilons with
/// [`crate::forecast_degeneracy_check`] rather than restating them; what differs is only the
/// inputs — fan order is judged on the mg/dL bands (there is no rawer signal to judge it on) and
/// the rails are the clinical physical domain rather than a descriptor's.
#[uniffi::export]
pub fn baseline_degeneracy_check(f: &BaselineForecast) -> ForecastStatus {
    let nq = QUANTILE_LEVELS.len();
    let n = f.median_bg.len();
    if n == 0 || f.bands_mgdl.len() != n * nq {
        return ForecastStatus::NonFinite;
    }
    if !f.median_bg.iter().all(|v| v.is_finite()) || !f.bands_mgdl.iter().all(|v| v.is_finite()) {
        return ForecastStatus::NonFinite;
    }
    if !fan_is_ascending(&f.bands_mgdl, n, nq) {
        return ForecastStatus::MisorderedQuantiles;
    }
    if median_is_rail_pinned(&f.median_bg, CLINICAL_BG_CLAMP_MIN, CLINICAL_BG_CLAMP_MAX) {
        return ForecastStatus::RailPinned;
    }
    if fan_is_collapsed(&f.bands_mgdl, n, nq) {
        return ForecastStatus::CollapsedBand;
    }
    ForecastStatus::Ok
}

#[cfg(test)]
mod tests {
    use super::*;

    fn spec(p: u32, h: u32) -> BaselineSpec {
        BaselineSpec {
            n_lags: p,
            horizon_steps: h,
            ridge_lambda: 1.0,
            use_iob: false,
            use_cob: false,
        }
    }

    /// A deterministic, mildly autocorrelated BG trace — a slow sinusoid plus a reproducible
    /// pseudo-random jitter, so a fit has real structure to find without a RNG dependency.
    fn synthetic_bg(n: usize) -> Vec<f64> {
        let mut state: u64 = 0x2545_F491_4F6C_DD1D;
        (0..n)
            .map(|i| {
                state ^= state << 13;
                state ^= state >> 7;
                state ^= state << 17;
                let jitter = ((state >> 40) as f64 / 16_777_216.0 - 0.5) * 6.0;
                140.0 + 45.0 * ((i as f64) * std::f64::consts::TAU / 288.0).sin() + jitter
            })
            .collect()
    }

    #[test]
    fn fit_beats_persistence_on_held_out_data() {
        let bg = synthetic_bg(3000);
        let fit = fit_baseline_ridge(bg, 0, vec![], spec(12, 24), 0, 19).expect("fit");
        assert!(fit.n_holdout_windows > 500, "windows {}", fit.n_holdout_windows);
        // The trace is genuinely predictable, so the ridge must beat zero-order hold at every
        // horizon past the first few steps. This is the guard against a fit that silently
        // degenerates to the intercept.
        for k in 5..24 {
            assert!(
                fit.holdout_rmse_mgdl[k] < fit.persistence_rmse_mgdl[k],
                "horizon {}: ridge {} vs persistence {}",
                k + 1,
                fit.holdout_rmse_mgdl[k],
                fit.persistence_rmse_mgdl[k]
            );
        }
    }

    #[test]
    fn conformal_opens_the_fan_and_reports_coverage() {
        let bg = synthetic_bg(3000);
        let fit = fit_baseline_ridge(bg, 0, vec![], spec(12, 24), 0, 19).expect("fit");
        assert!(fit.conformal.sufficient, "expected a sufficient calibration");
        let cov = fit.conformal.cov90_cal.expect("held-out coverage");
        assert!((0.75..=1.0).contains(&cov), "cov90_cal {cov}");
        assert!(fit.conformal.mean_width90_cal.unwrap_or(0.0) > 0.0);
    }

    #[test]
    fn predict_is_ok_once_calibrated_and_collapsed_before() {
        let bg = synthetic_bg(3000);
        let fit = fit_baseline_ridge(bg.clone(), 0, vec![], spec(12, 24), 0, 19).expect("fit");
        let tail: Vec<f64> = bg[bg.len() - 12..].to_vec();

        let unfitted = BaselineModel {
            band_delta: Vec::new(),
            ..fit.model.clone()
        };
        let raw = baseline_predict(&unfitted, tail.clone(), 0.0, 0.0).expect("raw");
        assert_eq!(
            baseline_degeneracy_check(&raw),
            ForecastStatus::CollapsedBand,
            "an uncalibrated baseline must be withheld, not shown as a confident line"
        );

        let cal = baseline_predict(&fit.model, tail, 0.0, 0.0).expect("calibrated");
        assert_eq!(baseline_degeneracy_check(&cal), ForecastStatus::Ok);
        assert_eq!(cal.median_bg, raw.median_bg, "the delta may not move the median");
    }

    #[test]
    fn on_board_series_is_causal_and_matches_on_board_for_started_events() {
        let ev = CurveEvent {
            start_ms: 10 * STEP_MS,
            step_ms: STEP_MS,
            kind: CurveKind::Carb,
            total: 60.0,
            values: crate::curve::gamma(60.0, 3.25, 22.5, 120.0),
        };
        let n = 40;
        let mut out = vec![0.0; n];
        let mut scratch = Vec::new();
        on_board_series(&[ev.clone()], CurveKind::Carb, 0, n, &mut out, &mut scratch);

        // Before the meal is logged the feature is zero — the future may not leak backwards.
        for (i, &v) in out.iter().enumerate().take(10) {
            assert_eq!(v, 0.0, "step {i} sees a meal that has not happened");
        }
        // From the start onward it equals the shared `on_board` definition.
        for i in 10..n {
            let want = crate::on_board(vec![ev.clone()], i as i64 * STEP_MS, CurveKind::Carb);
            assert!((out[i] - want).abs() < 1e-9, "step {i}: {} vs {want}", out[i]);
        }
        // COB decays to nothing once the curve is spent.
        assert!(out[n - 1] < 1e-9);
    }

    #[test]
    fn iob_cob_features_are_accepted_and_change_the_fit() {
        let bg = synthetic_bg(3000);
        let events: Vec<CurveEvent> = (0..20)
            .map(|i| CurveEvent {
                start_ms: (i as i64 * 144 + 30) * STEP_MS,
                step_ms: STEP_MS,
                kind: CurveKind::Carb,
                total: 45.0,
                values: crate::curve::gamma(45.0, 3.25, 22.5, 120.0),
            })
            .collect();
        let s = BaselineSpec {
            use_iob: true,
            use_cob: true,
            ..spec(12, 24)
        };
        let fit = fit_baseline_ridge(bg, 0, events, s, 0, 19).expect("fit with on-board");
        assert_eq!(fit.model.n_features, 14);
        assert_eq!(fit.model.weights.len(), 24 * 15);
        assert!(fit.model.weights.iter().all(|w| w.is_finite()));
    }

    #[test]
    fn gaps_drop_rows_rather_than_being_filled() {
        let mut bg = synthetic_bg(3000);
        for v in bg.iter_mut().skip(500).take(60) {
            *v = f64::NAN;
        }
        let fit = fit_baseline_ridge(bg, 0, vec![], spec(12, 24), 0, 19).expect("fit with gaps");
        assert!(fit.model.weights.iter().all(|w| w.is_finite()));
        assert!(fit.model.n_train_rows > 1000);
    }

    #[test]
    fn hostile_input_is_err_not_panic() {
        // Too short.
        assert!(fit_baseline_ridge(vec![100.0; 10], 0, vec![], spec(12, 24), 0, 19).is_err());
        // All-NaN.
        assert!(fit_baseline_ridge(vec![f64::NAN; 3000], 0, vec![], spec(12, 24), 0, 19).is_err());
        // Degenerate specs.
        assert!(fit_baseline_ridge(vec![100.0; 3000], 0, vec![], spec(0, 24), 0, 19).is_err());
        assert!(fit_baseline_ridge(vec![100.0; 3000], 0, vec![], spec(12, 0), 0, 19).is_err());
        let mut s = spec(12, 24);
        s.ridge_lambda = f64::NAN;
        assert!(fit_baseline_ridge(vec![100.0; 3000], 0, vec![], s, 0, 19).is_err());
    }

    #[test]
    fn predict_rejects_a_gap_in_the_tail() {
        let bg = synthetic_bg(3000);
        let fit = fit_baseline_ridge(bg, 0, vec![], spec(12, 24), 0, 19).expect("fit");
        let mut tail = vec![120.0; 12];
        tail[3] = f64::NAN;
        assert!(baseline_predict(&fit.model, tail, 0.0, 0.0).is_err());
        // Wrong tail length is a caller bug, not a silent pad.
        assert!(baseline_predict(&fit.model, vec![120.0; 11], 0.0, 0.0).is_err());
    }

    #[test]
    fn band_edges_stay_inside_the_physical_domain() {
        // The conformal delta is added downstream of the median's clamp, so a wide correction near
        // a rail could otherwise push an edge outside the domain the rest of the app renders on.
        let bg = synthetic_bg(3000);
        let fit = fit_baseline_ridge(bg.clone(), 0, vec![], spec(12, 24), 0, 19).expect("fit");
        let mut hostile = fit.model.clone();
        // A delta far larger than anything a real fit produces, still median-fixed and monotone.
        let nq = QUANTILE_LEVELS.len();
        let median_idx = 3;
        hostile.band_delta = (0..24 * nq)
            .map(|i| {
                let k = i % nq;
                if k == median_idx {
                    0.0
                } else if k < median_idx {
                    -5000.0
                } else {
                    5000.0
                }
            })
            .collect();
        let out = baseline_predict(&hostile, bg[bg.len() - 12..].to_vec(), 0.0, 0.0).expect("predict");
        for (i, &v) in out.bands_mgdl.iter().enumerate() {
            assert!(
                (CLINICAL_BG_CLAMP_MIN..=CLINICAL_BG_CLAMP_MAX).contains(&v),
                "band edge {i} = {v} escaped the physical domain"
            );
        }
        assert_eq!(baseline_degeneracy_check(&out), ForecastStatus::Ok);
    }

    #[test]
    fn an_event_off_the_grid_cadence_is_skipped_not_misplaced() {
        let ev = CurveEvent {
            start_ms: 0,
            step_ms: STEP_MS / 2, // not the five-minute grid
            kind: CurveKind::Carb,
            total: 60.0,
            values: crate::curve::gamma(60.0, 3.25, 22.5, 120.0),
        };
        let mut out = vec![0.0; 40];
        let mut scratch = Vec::new();
        on_board_series(&[ev], CurveKind::Carb, 0, 40, &mut out, &mut scratch);
        assert!(out.iter().all(|&v| v == 0.0), "off-cadence event was laid onto the grid");
    }

    #[test]
    fn a_flat_trace_predicts_itself() {
        // A constant series has zero variance in every lag column; the standardization guard must
        // keep the solve well-posed and the forecast must sit on the constant.
        let fit = fit_baseline_ridge(vec![120.0; 3000], 0, vec![], spec(12, 24), 0, 19)
            .expect("flat fit");
        let out = baseline_predict(&fit.model, vec![120.0; 12], 0.0, 0.0).expect("predict");
        for v in &out.median_bg {
            assert!((v - 120.0).abs() < 1e-6, "flat forecast drifted to {v}");
        }
    }
}
