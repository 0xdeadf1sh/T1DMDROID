//! Direct multi-step ridge on lagged CGM plus causal on-board, fitted per patient. One weight
//! vector per horizon step; the fan is `SPEC/inference.md` §8.4's split conformal over a
//! degenerate fan, so it and the neural fan score on one basis (`SPEC/invariants.md` §6.2).
//!
//! On-board here is CAUSAL, deliberately unlike [`crate::on_board`]: that sums every matching
//! event's remaining tail, which at a historical step would leak a later-logged dose into the
//! fit. [`on_board_series`] gates on having started (`SPEC/inference.md` §7.1).

use crate::accuracy::{ForecastWindow, QUANTILE_LEVELS};
use crate::conformal::{apply_quantile_conformal, fit_quantile_conformal, ConformalFit};
use crate::curve::{CurveEvent, CurveKind, STEP_MS};
use crate::preproc::{fan_is_ascending, fan_is_collapsed, median_is_rail_pinned, ForecastStatus};
use crate::{CoreError, CLINICAL_BG_CLAMP_MAX, CLINICAL_BG_CLAMP_MIN};

/// Ridge share of the usable rows; the rest becomes the conformal window set, which
/// [`fit_quantile_conformal`] splits again, so no reported coverage is in-sample.
const RIDGE_FIT_FRACTION: f64 = 0.6;

/// 8 weeks of 5-minute steps. The normal-equation accumulation is `O(n·d²)`, on the phone.
const MAX_FIT_STEPS: usize = 16_128;

/// 6 h of lags. Past a few hours the design matrix is collinear and Cholesky conditioning
/// degrades for no accuracy.
const MAX_LAGS: u32 = 72;

/// 6 h, in 5-minute steps.
const MAX_HORIZON: u32 = 72;

/// A floor, not a default: a non-positive penalty leaves a singular system for a patient
/// whose COB is identically zero.
const MIN_LAMBDA: f64 = 1e-6;

/// Forward windows in five-minute steps — 30, 60, 120 min — summing committed carb appearance
/// and insulin action. They count every curve overlapping the window, one starting after the
/// anchor included, so `holdout_rmse_mgdl` is optimistic against a strictly-causal figure.
const FORWARD_BLOCKS: [usize; 3] = [6, 12, 24];

#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct BaselineSpec {
    /// Design-row lags, most-recent first.
    pub n_lags: u32,
    pub horizon_steps: u32,
    /// Penalty on the standardized columns.
    pub ridge_lambda: f64,
    pub use_iob: bool,
    pub use_cob: bool,
    /// The [`FORWARD_BLOCKS`] sums.
    pub use_forward: bool,
}

#[uniffi::export]
pub fn baseline_default_spec() -> BaselineSpec {
    BaselineSpec {
        n_lags: 12,
        horizon_steps: 24,
        ridge_lambda: 1.0,
        use_iob: true,
        use_cob: true,
        use_forward: true,
    }
}

/// `weights` is `horizon_steps × (1 + n_features)`, row-major, intercept first, standardization
/// folded in. `band_delta` is part of the model, not §8.4's display-only correction: a ridge has
/// no interval of its own. All zeros until fitted, which the degeneracy guard withholds.
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

/// `holdout_rmse_mgdl` is per horizon step over the conformal split, a MEDIAN-LINE figure —
/// not `SPEC/invariants.md` §6.2's band projection, and never in one column with it.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct BaselineFit {
    pub model: BaselineModel,
    pub conformal: ConformalFit,
    pub holdout_rmse_mgdl: Vec<f64>,
    pub n_holdout_windows: u32,
    /// Zero-order-hold RMSE per horizon over the same held-out windows.
    pub persistence_rmse_mgdl: Vec<f64>,
}

/// mg/dL only: there is no risk space to name (`SPEC/invariants.md` §4 rule 1).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct BaselineForecast {
    pub median_bg: Vec<f64>,
    pub bands_mgdl: Vec<f64>,
}

/// Per step, the remaining tail area of every matching event THAT HAS ALREADY STARTED —
/// [`crate::on_board`] restricted to started events; see the module note.
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
        // `values` is indexed by GRID steps; an off-cadence event would land at the wrong times.
        if ev.step_ms != STEP_MS {
            continue;
        }
        let offset = (((ev.start_ms - grid_start_ms) as f64) / STEP_MS as f64).round() as i64;
        let len = ev.values.len();
        if offset >= n as i64 || offset + (len as i64) <= 0 {
            continue;
        }
        suffix.clear();
        suffix.resize(len + 1, 0.0);
        for j in (0..len).rev() {
            let v = ev.values[j];
            suffix[j] = suffix[j + 1] + if v.is_finite() { v } else { 0.0 };
        }
        // `offset < 0` — a dose predating the grid — still contributes its overlapping tail.
        let lo = offset.max(0) as usize;
        let hi = ((offset + len as i64) as usize).min(n);
        for (i, slot) in out.iter_mut().enumerate().take(hi).skip(lo) {
            let m = (i as i64 - offset) as usize;
            *slot += suffix[m];
        }
    }
}

/// The causal on-board amount at one instant — the feature [`baseline_predict`] consumes. NOT
/// [`crate::on_board`]: computing this column any other way infers on a definition the fit did
/// not train on, and the bias is invisible in every forecast.
#[uniffi::export]
pub fn baseline_on_board_at(events: Vec<CurveEvent>, at_ms: i64, kind: CurveKind) -> f64 {
    let mut out = [0.0f64; 1];
    let mut suffix = Vec::new();
    on_board_series(&events, kind, at_ms, 1, &mut out, &mut suffix);
    out[0]
}

/// `a` is `d×d` row-major, overwritten with `L`; `b` is overwritten with the solution. False if
/// `A` is not positive definite, which with a positive ridge means a non-finite design.
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

/// Returns `(n_lags, horizon, n_features)`.
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
    if spec.use_forward && h < FORWARD_BLOCKS[FORWARD_BLOCKS.len() - 1] {
        return Err(bad(format!(
            "baseline horizon_steps {h} is shorter than the widest forward block {}",
            FORWARD_BLOCKS[FORWARD_BLOCKS.len() - 1]
        )));
    }
    let fwd = if spec.use_forward { 2 * FORWARD_BLOCKS.len() } else { 0 };
    let d = p + spec.use_iob as usize + spec.use_cob as usize + fwd;
    Ok((p, h, d))
}

/// `carb`/`ins` are per-step rates whose index 0 is the step immediately AFTER the anchor — the
/// same alignment at fit time and at inference.
#[inline]
fn forward_row(carb: &[f64], ins: &[f64], row: &mut [f64], at: usize) -> bool {
    for (b, &block) in FORWARD_BLOCKS.iter().enumerate() {
        if carb.len() < block || ins.len() < block {
            return false;
        }
        let c: f64 = carb[..block].iter().sum();
        let i: f64 = ins[..block].iter().sum();
        if !c.is_finite() || !i.is_finite() {
            return false;
        }
        row[at + b] = c;
        row[at + FORWARD_BLOCKS.len() + b] = i;
    }
    true
}

/// False if any component is non-finite: the row is dropped, never imputed, since gap-filling
/// is a presentation step only (`SPEC/invariants.md` §1).
#[allow(clippy::too_many_arguments)]
#[inline]
fn design_row(
    bg: &[f64],
    iob: &[f64],
    cob: &[f64],
    carb_rate: &[f64],
    ins_rate: &[f64],
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
        k += 1;
    }
    if spec.use_forward {
        let from = t + 1;
        if from > carb_rate.len() || from > ins_rate.len() {
            return false;
        }
        if !forward_row(&carb_rate[from..], &ins_rate[from..], row, k) {
            return false;
        }
    }
    true
}

/// `bg_mgdl` is grid-aligned from `grid_start_ms`, newest last, a non-finite entry marking a
/// gap; `events` must include the tails of anything still acting when the window opens. The
/// split is chronological. Too small a held-out set returns an all-zero, withheld band.
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
    // The same channels `bucketize` lays down for the neural model's context.
    let mut carb_rate = vec![0.0f64; n];
    let mut ins_rate = vec![0.0f64; n];
    if spec.use_forward {
        crate::curve::bucketize_into(&events, grid_start_ms, CurveKind::Carb, &mut carb_rate);
        crate::curve::bucketize_into(&events, grid_start_ms, CurveKind::Insulin, &mut ins_rate);
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

    let mut row = vec![0.0f64; d];
    let mut mean = vec![0.0f64; d];
    let mut m2 = vec![0.0f64; d];
    let mut n_rows = 0usize;
    for t in t_lo..split_t {
        if !design_row(&bg_mgdl, &iob, &cob, &carb_rate, &ins_rate, &spec, p, t, &mut row) {
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
    // A constant column: scale 1.0 keeps the solve well-posed and its standardized values 0.
    let mut scale = vec![1.0f64; d];
    for j in 0..d {
        let var = m2[j] / (n_rows as f64 - 1.0);
        if var.is_finite() && var > 1e-12 {
            scale[j] = var.sqrt();
        }
    }

    // One Gram per horizon: a gap `k` steps ahead invalidates only horizon `k`.
    let dd = d * d;
    let mut gram = vec![0.0f64; dd * h];
    let mut xty = vec![0.0f64; d * h];
    let mut colsum = vec![0.0f64; d * h];
    let mut ybar = vec![0.0f64; h];
    let mut ycount = vec![0.0f64; h];
    let mut z = vec![0.0f64; d];

    for t in t_lo..split_t {
        if !design_row(&bg_mgdl, &iob, &cob, &carb_rate, &ins_rate, &spec, p, t, &mut row) {
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
        // This horizon's rows are a subset of the standardized set, so its column means are
        // not 0. Exact re-centring: `Z_cᵀZ_c = ZᵀZ − n·z̄z̄ᵀ`, `Z_cᵀy_c = Zᵀy − ȳ·Zᵀ1`.
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
        // Standardized intercept `ȳ − wᵀz̄`, then fold the standardization into the weights.
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

    // The roll below reads only the weights, so `band_delta` is still empty here — which is
    // why the roll calls `predict_median` rather than `baseline_predict`.
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

    let nq = QUANTILE_LEVELS.len();
    let mut windows: Vec<ForecastWindow> = Vec::with_capacity(t_hi.saturating_sub(split_t));
    let mut se = vec![0.0f64; h];
    let mut se_persist = vec![0.0f64; h];
    let mut se_n = vec![0.0f64; h];
    for t in split_t..t_hi {
        if !design_row(&bg_mgdl, &iob, &cob, &carb_rate, &ins_rate, &spec, p, t, &mut row) {
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

/// `bg_tail` is the trailing `n_lags` mg/dL values OLDEST→NEWEST; `iob`/`cob` are causal
/// on-board at the anchor. With an unfitted `band_delta` the fan stays degenerate and
/// [`baseline_degeneracy_check`] withholds it: a median with no interval is not shown.
#[uniffi::export]
pub fn baseline_predict(
    model: &BaselineModel,
    bg_tail: Vec<f64>,
    iob: f64,
    cob: f64,
    future_carb: Vec<f64>,
    future_insulin: Vec<f64>,
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
        k += 1;
    }
    if model.spec.use_forward {
        // Index 0 is the step after the anchor, as at fit time. A zero-padded short future
        // would read as "no dose is coming", so withhold instead.
        if future_carb.len() < h || future_insulin.len() < h {
            return Err(bad(format!(
                "baseline future channels are {} / {} steps, need {h}",
                future_carb.len(),
                future_insulin.len()
            )));
        }
        if !forward_row(&future_carb, &future_insulin, &mut row, k) {
            return Err(bad("baseline future channels are not finite".into()));
        }
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
    // The degenerate fan is the identity input to §8.4's apply.
    let mut bands_mgdl = if model.band_delta.is_empty() {
        bands
    } else {
        apply_quantile_conformal(bands, model.band_delta.clone())?
    };
    // The delta lands downstream of the median's own clamp, so an edge can leave the physical
    // domain even though the median cannot. Clamping a monotone sequence keeps it monotone.
    for v in bands_mgdl.iter_mut() {
        *v = v.clamp(CLINICAL_BG_CLAMP_MIN, CLINICAL_BG_CLAMP_MAX);
    }

    Ok(BaselineForecast {
        median_bg: median,
        bands_mgdl,
    })
}

/// The §3.6-B guard for a forecast with no risk space: [`crate::forecast_degeneracy_check`]'s
/// predicates over the mg/dL bands and the clinical rails.
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
            use_forward: false,
        }
    }

    /// Deterministic, autocorrelated: a fit has real structure to find, with no RNG dependency.
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
        let raw = baseline_predict(&unfitted, tail.clone(), 0.0, 0.0, vec![], vec![]).expect("raw");
        assert_eq!(
            baseline_degeneracy_check(&raw),
            ForecastStatus::CollapsedBand,
            "an uncalibrated baseline must be withheld, not shown as a confident line"
        );

        let cal = baseline_predict(&fit.model, tail, 0.0, 0.0, vec![], vec![]).expect("calibrated");
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

        for (i, &v) in out.iter().enumerate().take(10) {
            assert_eq!(v, 0.0, "step {i} sees a meal that has not happened");
        }
        for i in 10..n {
            let want = crate::on_board(vec![ev.clone()], i as i64 * STEP_MS, CurveKind::Carb);
            assert!((out[i] - want).abs() < 1e-9, "step {i}: {} vs {want}", out[i]);
        }
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
        assert!(fit_baseline_ridge(vec![100.0; 10], 0, vec![], spec(12, 24), 0, 19).is_err());
        assert!(fit_baseline_ridge(vec![f64::NAN; 3000], 0, vec![], spec(12, 24), 0, 19).is_err());
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
        assert!(baseline_predict(&fit.model, tail, 0.0, 0.0, vec![], vec![]).is_err());
        assert!(baseline_predict(&fit.model, vec![120.0; 11], 0.0, 0.0, vec![], vec![]).is_err());
    }

    #[test]
    fn band_edges_stay_inside_the_physical_domain() {
        let bg = synthetic_bg(3000);
        let fit = fit_baseline_ridge(bg.clone(), 0, vec![], spec(12, 24), 0, 19).expect("fit");
        let mut hostile = fit.model.clone();
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
        let out = baseline_predict(&hostile, bg[bg.len() - 12..].to_vec(), 0.0, 0.0, vec![], vec![]).expect("predict");
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
    fn a_committed_meal_moves_the_forecast_without_a_new_bg_sample() {
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
            use_forward: true,
            ..spec(12, 24)
        };
        let fit = fit_baseline_ridge(bg.clone(), 0, events, s, 0, 19).expect("fit");
        assert_eq!(fit.model.n_features, 12 + 2 + 2 * FORWARD_BLOCKS.len() as u32);

        let tail: Vec<f64> = bg[bg.len() - 12..].to_vec();
        let none = vec![0.0f64; 24];
        let quiet = baseline_predict(&fit.model, tail.clone(), 0.0, 0.0, none.clone(), none.clone())
            .expect("no committed dose");
        // Identical BG history and on-board at the anchor; only the forward channels differ.
        let meal = crate::curve::gamma(60.0, 3.25, 22.5, 120.0);
        let mut future_carb = vec![0.0f64; 24];
        for (i, slot) in future_carb.iter_mut().enumerate() {
            *slot = meal.get(i).copied().unwrap_or(0.0);
        }
        let fed = baseline_predict(&fit.model, tail, 0.0, 0.0, future_carb, none)
            .expect("committed meal");
        assert_ne!(
            quiet.median_bg, fed.median_bg,
            "a committed meal must move the forecast with no new BG sample"
        );
    }

    #[test]
    fn predict_refuses_a_short_future_window() {
        let bg = synthetic_bg(3000);
        let s = BaselineSpec { use_forward: true, ..spec(12, 24) };
        let fit = fit_baseline_ridge(bg.clone(), 0, vec![], s, 0, 19).expect("fit");
        let tail: Vec<f64> = bg[bg.len() - 12..].to_vec();
        assert!(baseline_predict(&fit.model, tail, 0.0, 0.0, vec![0.0; 12], vec![0.0; 24]).is_err());
    }

    #[test]
    fn a_flat_trace_predicts_itself() {
        let fit = fit_baseline_ridge(vec![120.0; 3000], 0, vec![], spec(12, 24), 0, 19)
            .expect("flat fit");
        let out = baseline_predict(&fit.model, vec![120.0; 12], 0.0, 0.0, vec![], vec![]).expect("predict");
        for v in &out.median_bg {
            assert!((v - 120.0).abs() < 1e-6, "flat forecast drifted to {v}");
        }
    }
}
