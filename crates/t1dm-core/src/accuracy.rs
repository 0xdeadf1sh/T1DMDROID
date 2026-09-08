//! Reproduces `T1DMAI/realdata/metrics.py::compute_suite`.

use crate::cg_ega::{self, CgEgaCounts};
use crate::curve::DT_MINUTES;
use crate::CoreError;
use std::collections::BTreeMap;

/// `band_lo`/`band_hi` are the τ.05 / τ.95 mg/dL fan edges, read only when `has_band`.
#[derive(Debug, Clone, uniffi::Record)]
pub struct AccuracyPair {
    pub horizon_min: u32,
    pub predicted: f64,
    pub realized: f64,
    pub band_lo: f64,
    pub band_hi: f64,
    pub has_band: bool,
}

/// `coverage90` is `None` when no pair at this horizon carried a band.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct HorizonAccuracy {
    pub horizon_min: u32,
    pub n: u32,
    pub rmse: f64,
    pub mae: f64,
    pub mard: f64,
    pub coverage90: Option<f64>,
    pub sufficient: bool,
}

/// Horizons ascending.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct AccuracyReport {
    pub horizons: Vec<HorizonAccuracy>,
    pub n_pairs: u32,
    pub min_samples: u32,
}

#[uniffi::export]
pub fn accuracy_at_horizons(
    pairs: Vec<AccuracyPair>,
    min_samples: u32,
) -> Result<AccuracyReport, CoreError> {
    let mut buckets: BTreeMap<u32, Vec<AccuracyPair>> = BTreeMap::new();
    let mut n_pairs: u32 = 0;
    for p in pairs {
        if !p.predicted.is_finite() || !p.realized.is_finite() {
            continue;
        }
        n_pairs += 1;
        buckets.entry(p.horizon_min).or_default().push(p);
    }

    let mut horizons = Vec::with_capacity(buckets.len());
    for (horizon_min, rows) in buckets {
        let n = rows.len();
        let mut sq_sum = 0.0f64;
        let mut abs_sum = 0.0f64;
        let mut ard_sum = 0.0f64;
        let mut cov_hits = 0.0f64;
        let mut cov_n = 0u32;
        for r in &rows {
            let e = r.predicted - r.realized;
            sq_sum += e * e;
            abs_sum += e.abs();
            // MARD denominator clamps the realized value at 1 mg/dL (numpy `clip(true, 1, None)`).
            ard_sum += e.abs() / r.realized.max(1.0);
            if r.has_band {
                cov_n += 1;
                if r.realized >= r.band_lo && r.realized <= r.band_hi {
                    cov_hits += 1.0;
                }
            }
        }
        let nf = n as f64;
        horizons.push(HorizonAccuracy {
            horizon_min,
            n: n as u32,
            rmse: (sq_sum / nf).sqrt(),
            mae: abs_sum / nf,
            mard: 100.0 * ard_sum / nf,
            coverage90: if cov_n > 0 { Some(cov_hits / cov_n as f64) } else { None },
            sufficient: n as u32 >= min_samples,
        });
    }

    Ok(AccuracyReport { horizons, n_pairs, min_samples })
}

// No rmse_macro (one patient: macro=micro); DTS grid/Trend Matrix are device metrics, unpublished

/// The seven forecast quantile levels, ascending — `SPEC/invariants.md` §6.
pub(crate) const QUANTILE_LEVELS: [f64; 7] = [0.05, 0.10, 0.25, 0.50, 0.75, 0.90, 0.95];

// Four metric levels (§6.1); the two pairs are numerically equal today, named separately.
const METRIC_BAND_TAU_LO: f64 = 0.25;
const METRIC_BAND_TAU_HI: f64 = 0.75;
const HYPO_ALARM_QUANTILE_TAU: f64 = 0.25;
const HYPER_ALARM_QUANTILE_TAU: f64 = 0.75;

/// Not §6.1 levels — the extremes of [`QUANTILE_LEVELS`], read for central-90 coverage.
const OUTER_TAU_LO: f64 = 0.05;
const OUTER_TAU_HI: f64 = 0.95;

/// mg/dL slack on ascending-fan check (risk-space equal levels differ via f_inv rounding).
const FAN_ORDER_TOL_MGDL: f64 = 1e-6;

/// Persistence RMSE below which the skill score is `None` (`metrics.py`'s `> 1e-9` guard).
const PERSIST_RMSE_EPS: f64 = 1e-9;

pub(crate) fn tau_index(tau: f64) -> Option<usize> {
    QUANTILE_LEVELS.iter().position(|&t| t == tau)
}

/// bands_mgdl: steps×7 row-major ascending τ, mg/dL; last_bg is the made_at persistence anchor.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ForecastWindow {
    pub bands_mgdl: Vec<f64>,
    pub median_bg: Vec<f64>,
    pub realized_bg: Vec<f64>,
    pub last_bg: f64,
}

/// `excursion_precision_tolerance_mgdl` forgives a near-boundary false alarm; recall is strict.
#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct MetricsConfig {
    pub hypo_threshold_mgdl: f64,
    pub hyper_threshold_mgdl: f64,
    pub excursion_precision_tolerance_mgdl: f64,
    pub min_samples: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum ClarkeZone {
    A,
    B,
    C,
    D,
    E,
}

/// Zone banding of [`dts_risk`] — Klonoff et al. 2024, J Diabetes Sci Technol 18(6):1346.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum DtsZone {
    A,
    B,
    C,
    D,
    E,
}

/// One scored `(pred, truth)` pair, mg/dL. The order is load-bearing: neither grid is symmetric.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct ScoredPoint {
    pub pred: f64,
    pub truth: f64,
    pub clarke: ClarkeZone,
    pub dts: DtsZone,
    /// Signed: positive where the forecast read high of the truth.
    pub dts_risk: f64,
}

/// *_point=horizon step, *_winmean pools 0..=k; DTS shares never sum to A+B; points empty on band.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct PointBlock {
    pub rmse_point: f64,
    pub mae_point: f64,
    pub rmse_winmean: f64,
    pub mae_winmean: f64,
    pub mard: f64,
    pub clarke_a: f64,
    pub clarke_ab: f64,
    pub clarke_d: f64,
    pub clarke_e: f64,
    pub dts_a: f64,
    pub dts_b: f64,
    pub dts_c: f64,
    pub dts_d: f64,
    pub dts_e: f64,
    pub dts_mean_abs_risk: f64,
    pub skill_point: Option<f64>,
    pub points: Vec<ScoredPoint>,
}

/// `None` where undefined: `recall` if the truth never crossed, `precision` if none was called.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct ExcursionAccuracy {
    pub recall: Option<f64>,
    pub precision: Option<f64>,
    pub n_true: u32,
    pub n_pred: u32,
}

/// Truth-major 5×5: cell (t,p) at t*TREND_BINS+p; category_pct empty (not zeroed) if unscored.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TrendMatrix {
    pub counts: Vec<u32>,
    pub category_n: Vec<u32>,
    pub category_pct: Vec<f64>,
    pub n: u32,
}

/// band=headline (§6.2 projection), median_line=same on median; read beside band_cov50/width50.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct HorizonMetrics {
    pub horizon_min: u32,
    pub n: u32,
    pub sufficient: bool,
    pub band: PointBlock,
    pub median_line: PointBlock,
    pub rmse_persist_point: f64,
    pub rmse_persist_winmean: f64,
    pub band_cov50: f64,
    pub band_width50: f64,
    pub band_cov90: f64,
    pub band_width90: f64,
    pub hypo: ExcursionAccuracy,
    pub hyper: ExcursionAccuracy,
    pub trend: TrendMatrix,
}

/// Accurate / benign / erroneous shares, `None` where the region held no points.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct CgEgaRegion {
    pub ap_pct: Option<f64>,
    pub be_pct: Option<f64>,
    pub ep_pct: Option<f64>,
    pub n_ap: u32,
    pub n_be: u32,
    pub n_ep: u32,
}

/// CG-EGA over the whole forecast window (§6.3); per horizon it is a different statistic.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct CgEga {
    pub hypo: CgEgaRegion,
    pub eu: CgEgaRegion,
    pub hyper: CgEgaRegion,
}

/// `cgega` is `None` when `include_cgega` was false: never computed, not an all-zero triple.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct MetricsSuite {
    pub horizons: Vec<HorizonMetrics>,
    pub cgega: Option<CgEga>,
    pub n_windows: u32,
    pub n_rejected: u32,
    pub n_steps: u32,
}

/// The band point nearest the truth — `SPEC/invariants.md` §6.2.
#[inline]
fn band_project(truth: f64, lo: f64, hi: f64) -> f64 {
    // `clamp` panics on lo > hi; min/max stays total.
    truth.max(lo).min(hi)
}

/// Clarke zones as (A,B,C,D,E) flags (metrics.py::_clarke); a partition, exactly one set.
fn clarke_zones(pred: f64, truth: f64) -> (bool, bool, bool, bool, bool) {
    let pb = pred.max(1.0);
    let tb = truth.max(1.0);
    let rel = (pb - tb).abs() / tb;
    let a = rel <= 0.20 || (pb <= 70.0 && tb <= 70.0);
    let e = (pb <= 70.0 && tb >= 180.0) || (pb >= 180.0 && tb <= 70.0);
    let c_up = tb >= 70.0 && tb <= 290.0 && pb >= tb + 110.0;
    let c_lo = tb >= 130.0 && tb <= 180.0 && pb <= (7.0 / 5.0) * tb - 182.0;
    let c = !a && !e && (c_up || c_lo);
    let d = !a && !e && !c && (tb <= 70.0 || tb >= 240.0) && pb >= 70.0 && pb <= 180.0;
    let b = !a && !e && !c && !d;
    (a, b, c, d, e)
}

fn clarke_zone(pred: f64, truth: f64) -> ClarkeZone {
    let (a, b, c, d, e) = clarke_zones(pred, truth);
    debug_assert_eq!(
        u8::from(a) + u8::from(b) + u8::from(c) + u8::from(d) + u8::from(e),
        1,
        "clarke_zones({pred}, {truth}) is not a partition"
    );
    match (a, b, c, d, e) {
        (true, ..) => ClarkeZone::A,
        (_, true, ..) => ClarkeZone::B,
        (_, _, true, ..) => ClarkeZone::C,
        (_, _, _, true, _) => ClarkeZone::D,
        _ => ClarkeZone::E,
    }
}

/// Allocation ceiling; a lattice this large already resolves the grid past any display.
const CLARKE_GRID_MAX_CELLS: usize = 4_000_000;

/// Classify (truth,pred) lattice, TRUTH-MAJOR: (i,j) at i*pred_len+j; Err if non-finite/oversized.
#[uniffi::export]
pub fn clarke_zone_grid(
    truth_axis: Vec<f64>,
    pred_axis: Vec<f64>,
) -> Result<Vec<ClarkeZone>, CoreError> {
    let bad = |reason: String| CoreError::Internal { reason };
    if truth_axis.iter().chain(pred_axis.iter()).any(|v| !v.is_finite()) {
        return Err(bad("clarke lattice axis carries a non-finite coordinate".into()));
    }
    let cells = truth_axis.len().saturating_mul(pred_axis.len());
    if cells > CLARKE_GRID_MAX_CELLS {
        return Err(bad(format!(
            "clarke lattice of {cells} cells exceeds the {CLARKE_GRID_MAX_CELLS}-cell ceiling"
        )));
    }
    let mut out = Vec::with_capacity(cells);
    for &t in &truth_axis {
        for &p in &pred_axis {
            out.push(clarke_zone(p, t));
        }
    }
    Ok(out)
}

/// Risk coefficient when the forecast reads ABOVE the truth.
const DTS_RISK_COEF_OVER: f64 = 2.75;
/// …and at or below. Reading high is penalised harder on purpose: it drives an over-dose.
const DTS_RISK_COEF_UNDER: f64 = 2.25;

/// Both axes floored (mg/dL) before ratio; per-axis flooring reproduces Table A1 borders exactly.
const DTS_CLAMP_MGDL: f64 = 50.0;

/// `|risk|` ceilings of zones A–D; above the last is E. Each bound is CLOSED.
const DTS_ZONE_CEILINGS: [f64; 4] = [0.5, 1.5, 2.5, 3.5];

/// DTS risk, positive where forecast read HIGH; NOT §4's risk space (log-ratio of two values).
#[inline]
fn dts_risk(pred: f64, truth: f64) -> f64 {
    let m = pred.max(DTS_CLAMP_MGDL);
    let r = truth.max(DTS_CLAMP_MGDL);
    let l = (m / r).ln();
    if m > r { DTS_RISK_COEF_OVER * l } else { DTS_RISK_COEF_UNDER * l }
}

#[inline]
fn dts_zone_of_abs_risk(abs_risk: f64) -> DtsZone {
    if abs_risk <= DTS_ZONE_CEILINGS[0] {
        DtsZone::A
    } else if abs_risk <= DTS_ZONE_CEILINGS[1] {
        DtsZone::B
    } else if abs_risk <= DTS_ZONE_CEILINGS[2] {
        DtsZone::C
    } else if abs_risk <= DTS_ZONE_CEILINGS[3] {
        DtsZone::D
    } else {
        DtsZone::E
    }
}

#[inline]
fn dts_zone(pred: f64, truth: f64) -> DtsZone {
    dts_zone_of_abs_risk(dts_risk(pred, truth).abs())
}

/// Classify DTS zones of a (truth,pred) lattice, TRUTH-MAJOR: (i,j) at i*pred_len+j.
#[uniffi::export]
pub fn dts_zone_grid(truth_axis: Vec<f64>, pred_axis: Vec<f64>) -> Result<Vec<DtsZone>, CoreError> {
    let bad = |reason: String| CoreError::Internal { reason };
    if truth_axis.iter().chain(pred_axis.iter()).any(|v| !v.is_finite()) {
        return Err(bad("dts lattice axis carries a non-finite coordinate".into()));
    }
    let cells = truth_axis.len().saturating_mul(pred_axis.len());
    if cells > CLARKE_GRID_MAX_CELLS {
        return Err(bad(format!(
            "dts lattice of {cells} cells exceeds the {CLARKE_GRID_MAX_CELLS}-cell ceiling"
        )));
    }
    let mut out = Vec::with_capacity(cells);
    for &t in &truth_axis {
        for &p in &pred_axis {
            out.push(dts_zone(p, t));
        }
    }
    Ok(out)
}

pub(crate) const TREND_BINS: usize = 5;

/// Four edges of 5 rate bins, mg/dL/min (Table 2); closed OUTER: ±2→±1 bin, ±1→flat bin.
pub(crate) const TREND_BIN_EDGES: [f64; 4] = [-2.0, -1.0, 1.0, 2.0];

/// Trend-rate lookback, §1 steps: 15min (paper's 15-45min window). NOT §6.3's single-step rate.
pub(crate) const TREND_LOOKBACK_STEPS: usize = 3;

pub(crate) const TREND_CATEGORIES: usize = 5;

/// Reference-BG cuts selecting the category table (mg/dL); NOT §6.3's regions (70/180 split).
const TREND_REGION_CUTS: [f64; 2] = [100.0, 180.0];

/// Five risk tables, TRUTH-MAJOR table[true_bin][pred_bin]; static not const, one shared address.
static TREND_CATEGORY_TABLES: [[[u8; TREND_BINS]; TREND_BINS]; 3] = [
    // Truth below 100 mg/dL.
    [
        [1, 3, 5, 5, 5],
        [2, 1, 3, 5, 5],
        [2, 2, 1, 3, 5],
        [2, 2, 2, 1, 3],
        [4, 4, 2, 2, 1],
    ],
    // Truth 100 to 180 mg/dL — the paper's main table.
    [
        [1, 3, 3, 5, 5],
        [2, 1, 3, 3, 3],
        [2, 2, 1, 3, 3],
        [2, 2, 2, 1, 3],
        [4, 4, 2, 2, 1],
    ],
    // Truth above 180 mg/dL — category 5 folded into 4.
    [
        [1, 3, 3, 4, 4],
        [2, 1, 3, 3, 3],
        [2, 2, 1, 3, 3],
        [2, 2, 2, 1, 3],
        [4, 4, 2, 2, 1],
    ],
];

#[uniffi::export]
pub fn trend_bin_edges() -> Vec<f64> {
    TREND_BIN_EDGES.to_vec()
}

/// Bin a rate (mg/dL/min) into `0..TREND_BINS`, ascending. `None` for a non-finite rate.
#[inline]
fn trend_bin(rate: f64) -> Option<usize> {
    if !rate.is_finite() {
        return None;
    }
    Some(if rate < TREND_BIN_EDGES[0] {
        0
    } else if rate < TREND_BIN_EDGES[1] {
        1
    } else if rate <= TREND_BIN_EDGES[2] {
        2
    } else if rate <= TREND_BIN_EDGES[3] {
        3
    } else {
        4
    })
}

#[inline]
fn trend_category_table(truth: f64) -> &'static [[u8; TREND_BINS]; TREND_BINS] {
    let i = if truth < TREND_REGION_CUTS[0] {
        0
    } else if truth <= TREND_REGION_CUTS[1] {
        1
    } else {
        2
    };
    &TREND_CATEGORY_TABLES[i]
}

/// The value at step `i`; `i == -1` is the persistence anchor, the step before the first (§6.3).
#[inline]
fn step_or_anchor(series: &[f64], anchor: f64, i: isize) -> Option<f64> {
    if i == -1 {
        Some(anchor)
    } else {
        usize::try_from(i).ok().and_then(|u| series.get(u).copied())
    }
}

fn trend_matrix(scored: &[Scored], k: usize) -> TrendMatrix {
    let mut counts = vec![0u32; TREND_BINS * TREND_BINS];
    let mut category_n = vec![0u32; TREND_CATEGORIES];
    let mut n = 0u32;
    let back = isize::try_from(k).unwrap_or(isize::MAX) - TREND_LOOKBACK_STEPS as isize;
    let dt = TREND_LOOKBACK_STEPS as f64 * DT_MINUTES;
    for s in scored {
        let (Some(t_prev), Some(m_prev)) = (
            step_or_anchor(&s.truth, s.last_bg, back),
            step_or_anchor(&s.median, s.last_bg, back),
        ) else {
            continue;
        };
        let (Some(tb), Some(pb)) = (
            trend_bin((s.truth[k] - t_prev) / dt),
            trend_bin((s.median[k] - m_prev) / dt),
        ) else {
            continue;
        };
        counts[tb * TREND_BINS + pb] += 1;
        let cat = trend_category_table(s.truth[k])[tb][pb];
        category_n[usize::from(cat) - 1] += 1;
        n += 1;
    }
    let category_pct = if n > 0 {
        category_n.iter().map(|&c| 100.0 * f64::from(c) / f64::from(n)).collect()
    } else {
        Vec::new()
    };
    TrendMatrix { counts, category_n, category_pct, n }
}

/// Root mean square of a slice; `NaN` on an empty one.
fn rmse(errs: &[f64]) -> f64 {
    let n = errs.len() as f64;
    (errs.iter().map(|e| e * e).sum::<f64>() / n).sqrt()
}

fn mean(xs: &[f64]) -> f64 {
    xs.iter().sum::<f64>() / xs.len() as f64
}

struct Scored {
    truth: Vec<f64>,
    median: Vec<f64>,
    pred_eff: Vec<f64>,
    band_lo: Vec<f64>,
    band_hi: Vec<f64>,
    outer_lo: Vec<f64>,
    outer_hi: Vec<f64>,
    hypo_edge: Vec<f64>,
    hyper_edge: Vec<f64>,
    last_bg: f64,
}

/// keep_points decides RETENTION only, never derivation; both bases classify the same way.
fn point_block(
    pred_k: &[f64],
    true_k: &[f64],
    err_winmean: &[f64],
    rmse_persist: f64,
    keep_points: bool,
) -> PointBlock {
    let n = pred_k.len() as f64;
    let mut errs = Vec::with_capacity(pred_k.len());
    let mut points = Vec::with_capacity(if keep_points { pred_k.len() } else { 0 });
    let mut abs_sum = 0.0f64;
    let mut ard_sum = 0.0f64;
    let (mut n_a, mut n_ab, mut n_d, mut n_e) = (0u32, 0u32, 0u32, 0u32);
    let mut n_dts = [0u32; 5];
    let mut abs_risk_sum = 0.0f64;
    for (&p, &t) in pred_k.iter().zip(true_k.iter()) {
        let e = p - t;
        errs.push(e);
        abs_sum += e.abs();
        // MARD's denominator clamps the truth at 1 mg/dL (numpy `clip(true, 1, None)`).
        ard_sum += e.abs() / t.max(1.0);
        let zone = clarke_zone(p, t);
        let risk = dts_risk(p, t);
        let dts = dts_zone_of_abs_risk(risk.abs());
        if keep_points {
            points.push(ScoredPoint { pred: p, truth: t, clarke: zone, dts, dts_risk: risk });
        }
        n_a += u32::from(zone == ClarkeZone::A);
        n_ab += u32::from(matches!(zone, ClarkeZone::A | ClarkeZone::B));
        n_d += u32::from(zone == ClarkeZone::D);
        n_e += u32::from(zone == ClarkeZone::E);
        n_dts[dts as usize] += 1;
        abs_risk_sum += risk.abs();
    }
    let rmse_point = rmse(&errs);
    let dts_pct = |i: usize| 100.0 * (f64::from(n_dts[i]) / n);
    PointBlock {
        rmse_point,
        mae_point: abs_sum / n,
        rmse_winmean: rmse(err_winmean),
        mae_winmean: err_winmean.iter().map(|e| e.abs()).sum::<f64>() / err_winmean.len() as f64,
        mard: 100.0 * (ard_sum / n),
        clarke_a: 100.0 * (f64::from(n_a) / n),
        clarke_ab: 100.0 * (f64::from(n_ab) / n),
        clarke_d: 100.0 * (f64::from(n_d) / n),
        clarke_e: 100.0 * (f64::from(n_e) / n),
        dts_a: dts_pct(0),
        dts_b: dts_pct(1),
        dts_c: dts_pct(2),
        dts_d: dts_pct(3),
        dts_e: dts_pct(4),
        dts_mean_abs_risk: abs_risk_sum / n,
        skill_point: if rmse_persist > PERSIST_RMSE_EPS {
            Some((rmse_persist - rmse_point) / rmse_persist)
        } else {
            // A perfect persistence baseline leaves the ratio undefined; `None`, not a score.
            None
        },
        points,
    }
}

/// edge: τ-lower for hypo, τ-upper for hyper (§6.1); precision forgives within tol, recall strict
fn excursion(edge: &[f64], truth: &[f64], threshold: f64, tol: f64, is_hypo: bool) -> ExcursionAccuracy {
    let (mut n_true, mut n_pred, mut tp, mut prec_hits) = (0u32, 0u32, 0u32, 0u32);
    for (&p, &t) in edge.iter().zip(truth.iter()) {
        let (te, pe) = if is_hypo {
            (t < threshold, p < threshold)
        } else {
            (t > threshold, p > threshold)
        };
        let close = (p - t).abs() <= tol;
        n_true += te as u32;
        n_pred += pe as u32;
        tp += (te && pe) as u32;
        prec_hits += (pe && (te || close)) as u32;
    }
    ExcursionAccuracy {
        recall: (n_true > 0).then(|| f64::from(tp) / f64::from(n_true)),
        precision: (n_pred > 0).then(|| f64::from(prec_hits) / f64::from(n_pred)),
        n_true,
        n_pred,
    }
}

fn cgega_from_counts(counts: &CgEgaCounts) -> CgEga {
    let region = |i: usize| {
        let [ap, be, ep] = counts[i];
        let total = f64::from(ap) + f64::from(be) + f64::from(ep);
        let pct = |v: u32| (total > 0.0).then(|| 100.0 * (f64::from(v) / total));
        CgEgaRegion {
            ap_pct: pct(ap),
            be_pct: pct(be),
            ep_pct: pct(ep),
            n_ap: ap,
            n_be: be,
            n_ep: ep,
        }
    };
    CgEga { hypo: region(0), eu: region(1), hyper: region(2) }
}

/// Scores windows per horizon (§6.2 band) + CG-EGA (§6.3); include_cgega gates the latter.
#[uniffi::export]
pub fn forecast_metrics_suite(
    windows: Vec<ForecastWindow>,
    horizons_min: Vec<u32>,
    config: MetricsConfig,
    include_cgega: bool,
) -> Result<MetricsSuite, CoreError> {
    let nq = QUANTILE_LEVELS.len();
    let bad = |reason: String| CoreError::Internal { reason };

    // §6.1: resolve each level by lookup, never a literal index.
    let idx_lo = tau_index(METRIC_BAND_TAU_LO)
        .ok_or_else(|| bad(format!("METRIC_BAND_TAU_LO {METRIC_BAND_TAU_LO} is not a fan level")))?;
    let idx_hi = tau_index(METRIC_BAND_TAU_HI)
        .ok_or_else(|| bad(format!("METRIC_BAND_TAU_HI {METRIC_BAND_TAU_HI} is not a fan level")))?;
    let idx_hypo = tau_index(HYPO_ALARM_QUANTILE_TAU).ok_or_else(|| {
        bad(format!("HYPO_ALARM_QUANTILE_TAU {HYPO_ALARM_QUANTILE_TAU} is not a fan level"))
    })?;
    let idx_hyper = tau_index(HYPER_ALARM_QUANTILE_TAU).ok_or_else(|| {
        bad(format!("HYPER_ALARM_QUANTILE_TAU {HYPER_ALARM_QUANTILE_TAU} is not a fan level"))
    })?;
    let idx_out_lo = tau_index(OUTER_TAU_LO)
        .ok_or_else(|| bad(format!("OUTER_TAU_LO {OUTER_TAU_LO} is not a fan level")))?;
    let idx_out_hi = tau_index(OUTER_TAU_HI)
        .ok_or_else(|| bad(format!("OUTER_TAU_HI {OUTER_TAU_HI} is not a fan level")))?;
    // A level on the wrong side of the median would mirror the band rather than widen it.
    if !(METRIC_BAND_TAU_LO < 0.5
        && METRIC_BAND_TAU_HI > 0.5
        && HYPO_ALARM_QUANTILE_TAU < 0.5
        && HYPER_ALARM_QUANTILE_TAU > 0.5)
    {
        return Err(bad("a metric level sits on the wrong side of the median".into()));
    }

    if !config.hypo_threshold_mgdl.is_finite()
        || !config.hyper_threshold_mgdl.is_finite()
        || !config.excursion_precision_tolerance_mgdl.is_finite()
        || config.excursion_precision_tolerance_mgdl < 0.0
    {
        return Err(bad("metrics config carries a non-finite or negative value".into()));
    }

    let empty = MetricsSuite {
        horizons: Vec::new(),
        cgega: include_cgega.then(|| cgega_from_counts(&[[0; 3]; 3])),
        n_windows: 0,
        n_rejected: 0,
        n_steps: 0,
    };
    if windows.is_empty() {
        return Ok(empty);
    }

    let n_steps = windows[0].realized_bg.len();
    if n_steps == 0 {
        return Ok(empty);
    }

    let mut scored: Vec<Scored> = Vec::with_capacity(windows.len());
    let mut n_rejected = 0u32;
    for w in &windows {
        if w.realized_bg.len() != n_steps
            || w.median_bg.len() != n_steps
            || w.bands_mgdl.len() != n_steps * nq
        {
            return Err(bad(format!(
                "window shape mismatch: realized {}, median {}, bands {} (expected {n_steps}, \
                 {n_steps}, {})",
                w.realized_bg.len(),
                w.median_bg.len(),
                w.bands_mgdl.len(),
                n_steps * nq
            )));
        }
        // Fail closed: an fp16 mis-order mirrors an interval silently.
        let finite = w.last_bg.is_finite()
            && w.realized_bg.iter().all(|v| v.is_finite())
            && w.median_bg.iter().all(|v| v.is_finite())
            && w.bands_mgdl.iter().all(|v| v.is_finite());
        let ascending = finite
            && (0..n_steps).all(|i| {
                let row = i * nq;
                (1..nq).all(|k| w.bands_mgdl[row + k] >= w.bands_mgdl[row + k - 1] - FAN_ORDER_TOL_MGDL)
            });
        if !finite || !ascending {
            n_rejected += 1;
            continue;
        }

        let col = |k: usize| -> Vec<f64> { (0..n_steps).map(|i| w.bands_mgdl[i * nq + k]).collect() };
        let band_lo = col(idx_lo);
        let band_hi = col(idx_hi);
        let pred_eff = (0..n_steps)
            .map(|i| band_project(w.realized_bg[i], band_lo[i], band_hi[i]))
            .collect();
        scored.push(Scored {
            truth: w.realized_bg.clone(),
            median: w.median_bg.clone(),
            pred_eff,
            band_lo,
            band_hi,
            outer_lo: col(idx_out_lo),
            outer_hi: col(idx_out_hi),
            hypo_edge: col(idx_hypo),
            hyper_edge: col(idx_hyper),
            last_bg: w.last_bg,
        });
    }

    if scored.is_empty() {
        return Ok(MetricsSuite { n_rejected, n_steps: n_steps as u32, ..empty });
    }

    let grid_min = DT_MINUTES as u32; // §1's five-minute grid.
    let mut wanted: Vec<u32> = horizons_min;
    wanted.sort_unstable();
    wanted.dedup();
    let mut horizons = Vec::with_capacity(wanted.len());
    for h in wanted {
        if h == 0 || h % grid_min != 0 {
            return Err(bad(format!("horizon {h} min is not a positive multiple of {grid_min}")));
        }
        let k = (h / grid_min - 1) as usize;
        if k >= n_steps {
            return Err(bad(format!(
                "horizon {h} min is step {k}, past the {n_steps}-step window"
            )));
        }

        let n = scored.len();
        let (mut pred_eff_k, mut median_k, mut true_k) =
            (Vec::with_capacity(n), Vec::with_capacity(n), Vec::with_capacity(n));
        let (mut hypo_k, mut hyper_k) = (Vec::with_capacity(n), Vec::with_capacity(n));
        let span = k + 1;
        let (mut err_band, mut err_med, mut err_persist) = (
            Vec::with_capacity(n * span),
            Vec::with_capacity(n * span),
            Vec::with_capacity(n * span),
        );
        let (mut cov50, mut cov90, mut width50, mut width90) =
            (0.0f64, 0.0f64, Vec::with_capacity(n), Vec::with_capacity(n));
        for s in &scored {
            pred_eff_k.push(s.pred_eff[k]);
            median_k.push(s.median[k]);
            true_k.push(s.truth[k]);
            hypo_k.push(s.hypo_edge[k]);
            hyper_k.push(s.hyper_edge[k]);
            for i in 0..span {
                err_band.push(s.pred_eff[i] - s.truth[i]);
                err_med.push(s.median[i] - s.truth[i]);
                err_persist.push(s.last_bg - s.truth[i]);
            }
            let t = s.truth[k];
            cov50 += ((t >= s.band_lo[k]) && (t <= s.band_hi[k])) as u32 as f64;
            cov90 += ((t >= s.outer_lo[k]) && (t <= s.outer_hi[k])) as u32 as f64;
            width50.push(s.band_hi[k] - s.band_lo[k]);
            width90.push(s.outer_hi[k] - s.outer_lo[k]);
        }

        // Persistence has no band; one baseline shared by both bases.
        let persist_err_k: Vec<f64> =
            scored.iter().map(|s| s.last_bg - s.truth[k]).collect();
        let rmse_persist_point = rmse(&persist_err_k);

        horizons.push(HorizonMetrics {
            horizon_min: h,
            n: n as u32,
            sufficient: n as u32 >= config.min_samples,
            band: point_block(&pred_eff_k, &true_k, &err_band, rmse_persist_point, false),
            median_line: point_block(&median_k, &true_k, &err_med, rmse_persist_point, true),
            rmse_persist_point,
            rmse_persist_winmean: rmse(&err_persist),
            band_cov50: cov50 / n as f64,
            band_width50: mean(&width50),
            band_cov90: cov90 / n as f64,
            band_width90: mean(&width90),
            hypo: excursion(
                &hypo_k,
                &true_k,
                config.hypo_threshold_mgdl,
                config.excursion_precision_tolerance_mgdl,
                true,
            ),
            hyper: excursion(
                &hyper_k,
                &true_k,
                config.hyper_threshold_mgdl,
                config.excursion_precision_tolerance_mgdl,
                false,
            ),
            trend: trend_matrix(&scored, k),
        });
    }

        // Truth FIRST (cg_ega.py's y_true,y_pred order); a transpose yields a different table.
    let cgega = include_cgega.then(|| {
        let mut counts: CgEgaCounts = [[0; 3]; 3];
        for s in &scored {
            cg_ega::accumulate(&mut counts, &s.truth, &s.pred_eff, s.last_bg, DT_MINUTES);
        }
        cgega_from_counts(&counts)
    });

    Ok(MetricsSuite {
        horizons,
        cgega,
        n_windows: scored.len() as u32,
        n_rejected,
        n_steps: n_steps as u32,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    fn golden() -> Value {
        serde_json::from_str(include_str!("testdata/accuracy_golden.json")).unwrap()
    }

    fn pairs_from(v: &Value) -> Vec<AccuracyPair> {
        v["pairs"]
            .as_array()
            .unwrap()
            .iter()
            .map(|p| AccuracyPair {
                horizon_min: p["horizon_min"].as_u64().unwrap() as u32,
                predicted: p["predicted"].as_f64().unwrap(),
                realized: p["realized"].as_f64().unwrap(),
                band_lo: p["band_lo"].as_f64().unwrap(),
                band_hi: p["band_hi"].as_f64().unwrap(),
                has_band: p["has_band"].as_bool().unwrap(),
            })
            .collect()
    }

    #[test]
    fn accuracy_golden() {
        let g = golden();
        let min_samples = g["min_samples"].as_u64().unwrap() as u32;
        let report = accuracy_at_horizons(pairs_from(&g), min_samples).unwrap();
        let exp = &g["expected"];
        assert_eq!(report.min_samples, min_samples);
        for h in &report.horizons {
            let e = &exp[h.horizon_min.to_string()];
            assert_eq!(h.n, e["n"].as_u64().unwrap() as u32, "n@{}", h.horizon_min);
            let tol = 1e-9;
            assert!((h.rmse - e["rmse"].as_f64().unwrap()).abs() < tol, "rmse@{} {}", h.horizon_min, h.rmse);
            assert!((h.mae - e["mae"].as_f64().unwrap()).abs() < tol, "mae@{} {}", h.horizon_min, h.mae);
            assert!((h.mard - e["mard"].as_f64().unwrap()).abs() < tol, "mard@{} {}", h.horizon_min, h.mard);
            assert!(
                (h.coverage90.unwrap() - e["coverage90"].as_f64().unwrap()).abs() < tol,
                "cov@{} {:?}", h.horizon_min, h.coverage90,
            );
        }
        let hs: Vec<u32> = report.horizons.iter().map(|h| h.horizon_min).collect();
        assert_eq!(hs, vec![30, 60, 120]);
        assert_eq!(report.n_pairs, 10);
        let h120 = report.horizons.iter().find(|h| h.horizon_min == 120).unwrap();
        assert!(!h120.sufficient);
        assert!(report.horizons.iter().find(|h| h.horizon_min == 30).unwrap().sufficient);
    }

    #[test]
    fn empty_and_nonfinite_are_total() {
        let empty = accuracy_at_horizons(vec![], 6).unwrap();
        assert!(empty.horizons.is_empty());
        assert_eq!(empty.n_pairs, 0);

        let pairs = vec![
            AccuracyPair { horizon_min: 30, predicted: f64::NAN, realized: 100.0, band_lo: 0.0, band_hi: 0.0, has_band: false },
            AccuracyPair { horizon_min: 30, predicted: 100.0, realized: 110.0, band_lo: 0.0, band_hi: 0.0, has_band: false },
        ];
        let r = accuracy_at_horizons(pairs, 6).unwrap();
        assert_eq!(r.n_pairs, 1);
        let h = &r.horizons[0];
        assert_eq!(h.n, 1);
        assert!(h.coverage90.is_none());
        assert_eq!(h.mae, 10.0);
        assert!(!h.sufficient);
    }

    /// Both sides run fp64 over the same formulae, differing only in summation order.
    const SUITE_TOL: f64 = 1e-9;

    fn suite_golden() -> Value {
        serde_json::from_str(include_str!("testdata/metrics_golden.json")).unwrap()
    }

    fn f64s(v: &Value) -> Vec<f64> {
        v.as_array().unwrap().iter().map(|x| x.as_f64().unwrap()).collect()
    }

    fn close(got: f64, want: f64, what: &str) {
        assert!(
            (got - want).abs() < SUITE_TOL,
            "{what}: got {got}, want {want} (Δ {})",
            (got - want).abs()
        );
    }

    fn close_opt(got: Option<f64>, want: &Value, what: &str) {
        match (got, want.as_f64()) {
            (Some(g), Some(w)) => close(g, w, what),
            (None, None) => {}
            (g, w) => panic!("{what}: got {g:?}, want {w:?}"),
        }
    }

    /// Re-aggregating the series must reproduce the four published shares (pins it to reference).
    fn assert_clarke_points(got: &PointBlock, what: &str) {
        let pts = &got.points;
        let n = pts.len();
        assert!(n > 0, "{what}: no points behind published shares");
        let pct = |f: fn(ClarkeZone) -> bool| {
            100.0 * pts.iter().filter(|p| f(p.clarke)).count() as f64 / n as f64
        };
        close(pct(|z| z == ClarkeZone::A), got.clarke_a, &format!("{what}.points→A"));
        close(
            pct(|z| matches!(z, ClarkeZone::A | ClarkeZone::B)),
            got.clarke_ab,
            &format!("{what}.points→AB"),
        );
        close(pct(|z| z == ClarkeZone::D), got.clarke_d, &format!("{what}.points→D"));
        close(pct(|z| z == ClarkeZone::E), got.clarke_e, &format!("{what}.points→E"));
        let counted: usize = [ClarkeZone::A, ClarkeZone::B, ClarkeZone::C, ClarkeZone::D, ClarkeZone::E]
            .iter()
            .map(|z| pts.iter().filter(|p| p.clarke == *z).count())
            .sum();
        assert_eq!(counted, n, "{what}: zones do not exhaust the window");
        for p in pts {
            assert_eq!(clarke_zone(p.pred, p.truth), p.clarke, "{what}: clarke point disagrees with itself");
            assert_eq!(dts_zone(p.pred, p.truth), p.dts, "{what}: dts point disagrees with itself");
            close(dts_risk(p.pred, p.truth), p.dts_risk, &format!("{what}: carried risk"));
        }
    }

    fn assert_dts_points(got: &PointBlock, what: &str) {
        let pts = &got.points;
        let n = pts.len() as f64;
        let pct = |z: DtsZone| 100.0 * pts.iter().filter(|p| p.dts == z).count() as f64 / n;
        close(pct(DtsZone::A), got.dts_a, &format!("{what}.points→dtsA"));
        close(pct(DtsZone::B), got.dts_b, &format!("{what}.points→dtsB"));
        close(pct(DtsZone::C), got.dts_c, &format!("{what}.points→dtsC"));
        close(pct(DtsZone::D), got.dts_d, &format!("{what}.points→dtsD"));
        close(pct(DtsZone::E), got.dts_e, &format!("{what}.points→dtsE"));
        close(
            got.dts_a + got.dts_b + got.dts_c + got.dts_d + got.dts_e,
            100.0,
            &format!("{what}: dts shares are a partition"),
        );
        close(
            pts.iter().map(|p| p.dts_risk.abs()).sum::<f64>() / n,
            got.dts_mean_abs_risk,
            &format!("{what}.dts_mean_abs_risk"),
        );
    }

    fn assert_point_block(got: &PointBlock, want: &Value, what: &str, keeps_points: bool) {
        close(got.rmse_point, want["rmse_point"].as_f64().unwrap(), &format!("{what}.rmse_point"));
        close(got.mae_point, want["mae_point"].as_f64().unwrap(), &format!("{what}.mae_point"));
        close(got.rmse_winmean, want["rmse_winmean"].as_f64().unwrap(), &format!("{what}.rmse_winmean"));
        close(got.mae_winmean, want["mae_winmean"].as_f64().unwrap(), &format!("{what}.mae_winmean"));
        close(got.mard, want["mard"].as_f64().unwrap(), &format!("{what}.mard"));
        close(got.clarke_a, want["clarke_A"].as_f64().unwrap(), &format!("{what}.clarke_A"));
        close(got.clarke_ab, want["clarke_AB"].as_f64().unwrap(), &format!("{what}.clarke_AB"));
        close(got.clarke_d, want["clarke_D"].as_f64().unwrap(), &format!("{what}.clarke_D"));
        close(got.clarke_e, want["clarke_E"].as_f64().unwrap(), &format!("{what}.clarke_E"));
        close_opt(got.skill_point, &want["skill_point"], &format!("{what}.skill_point"));
        if keeps_points {
            assert_clarke_points(got, what);
            assert_dts_points(got, what);
        } else {
            assert!(got.points.is_empty(), "{what}: band must carry no points");
        }
    }

    fn assert_excursion(got: &ExcursionAccuracy, want: &Value, what: &str) {
        close_opt(got.recall, &want["recall"], &format!("{what}.recall"));
        close_opt(got.precision, &want["precision"], &format!("{what}.precision"));
        assert_eq!(got.n_true, want["n_true"].as_u64().unwrap() as u32, "{what}.n_true");
        assert_eq!(got.n_pred, want["n_pred"].as_u64().unwrap() as u32, "{what}.n_pred");
    }

    fn assert_region(got: &CgEgaRegion, cg: &Value, want: &Value, reg: &str) {
        close_opt(got.ap_pct, &cg[format!("ap_{reg}")], &format!("cgega.ap_{reg}"));
        close_opt(got.be_pct, &cg[format!("be_{reg}")], &format!("cgega.be_{reg}"));
        close_opt(got.ep_pct, &cg[format!("ep_{reg}")], &format!("cgega.ep_{reg}"));
        assert_eq!(got.n_ap, want[format!("ap_{reg}")].as_u64().unwrap() as u32, "counts.ap_{reg}");
        assert_eq!(got.n_be, want[format!("be_{reg}")].as_u64().unwrap() as u32, "counts.be_{reg}");
        assert_eq!(got.n_ep, want[format!("ep_{reg}")].as_u64().unwrap() as u32, "counts.ep_{reg}");
    }

    /// The gate: reproduced from `T1DMAI/realdata/metrics.py::compute_suite` + `cg_ega.py`.
    #[test]
    fn metrics_suite_golden() {
        let g = suite_golden();
        let cfg_v = &g["config"];
        let config = MetricsConfig {
            hypo_threshold_mgdl: cfg_v["hypo_threshold_mgdl"].as_f64().unwrap(),
            hyper_threshold_mgdl: cfg_v["hyper_threshold_mgdl"].as_f64().unwrap(),
            excursion_precision_tolerance_mgdl: cfg_v["excursion_precision_tolerance_mgdl"]
                .as_f64()
                .unwrap(),
            min_samples: cfg_v["min_samples"].as_u64().unwrap() as u32,
        };
        assert_eq!(f64s(&g["quantile_levels"]), QUANTILE_LEVELS.to_vec());

        for case in g["cases"].as_array().unwrap() {
            let name = case["name"].as_str().unwrap();
            let windows: Vec<ForecastWindow> = case["windows"]
                .as_array()
                .unwrap()
                .iter()
                .map(|w| ForecastWindow {
                    bands_mgdl: f64s(&w["bands_mgdl"]),
                    median_bg: f64s(&w["median_bg"]),
                    realized_bg: f64s(&w["realized_bg"]),
                    last_bg: w["last_bg"].as_f64().unwrap(),
                })
                .collect();
            let horizons: Vec<u32> = case["horizons_min"]
                .as_array()
                .unwrap()
                .iter()
                .map(|h| h.as_u64().unwrap() as u32)
                .collect();
            let n_windows = windows.len() as u32;
            let suite =
                forecast_metrics_suite(windows.clone(), horizons.clone(), config, true).unwrap();

            let cheap =
                forecast_metrics_suite(windows, horizons.clone(), config, false).unwrap();
            assert_eq!(cheap.cgega, None, "[{name}] cgega without include_cgega");
            assert_eq!(cheap.horizons, suite.horizons, "[{name}] horizons differ by cgega flag");

            assert_eq!(suite.n_windows, n_windows, "[{name}] n_windows");
            assert_eq!(suite.n_rejected, 0, "[{name}] n_rejected");
            assert_eq!(suite.n_steps, case["n_steps"].as_u64().unwrap() as u32, "[{name}] n_steps");
            assert_eq!(
                suite.horizons.iter().map(|h| h.horizon_min).collect::<Vec<_>>(),
                horizons,
                "[{name}] horizons"
            );

            for h in &suite.horizons {
                let e = &case["expected"][h.horizon_min.to_string()];
                let tag = format!("[{name}]@{}", h.horizon_min);
                assert_eq!(h.n, e["n"].as_u64().unwrap() as u32, "{tag}.n");
                assert!(h.band.points.is_empty(), "{tag}.band points");
                assert_eq!(h.median_line.points.len() as u32, h.n, "{tag}.median points");
                assert_point_block(&h.band, &e["band"], &format!("{tag}.band"), false);
                assert_point_block(&h.median_line, &e["median_line"], &format!("{tag}.median_line"), true);
                close(h.rmse_persist_point, e["rmse_persist_point"].as_f64().unwrap(), &format!("{tag}.rmse_persist_point"));
                close(h.rmse_persist_winmean, e["rmse_persist_winmean"].as_f64().unwrap(), &format!("{tag}.rmse_persist_winmean"));
                close(h.band_cov50, e["band_cov50"].as_f64().unwrap(), &format!("{tag}.band_cov50"));
                close(h.band_width50, e["band_width50"].as_f64().unwrap(), &format!("{tag}.band_width50"));
                close(h.band_cov90, e["band_cov90"].as_f64().unwrap(), &format!("{tag}.band_cov90"));
                close(h.band_width90, e["band_width90"].as_f64().unwrap(), &format!("{tag}.band_width90"));
                assert_excursion(&h.hypo, &e["hypo"], &format!("{tag}.hypo"));
                assert_excursion(&h.hyper, &e["hyper"], &format!("{tag}.hyper"));

                // §6.2: a degenerate fan's projection is the median line.
                if case["collapsed_band"].as_bool().unwrap() {
                    assert_eq!(
                        PointBlock { points: h.median_line.points.clone(), ..h.band.clone() },
                        h.median_line,
                        "{tag}: collapsed band must reduce to the median line",
                    );
                }
            }

            let cg = &case["cgega"];
            let counts = &cg["counts"];
            let got = suite.cgega.expect("include_cgega = true must yield a CG-EGA");
            assert_region(&got.hypo, cg, counts, "hypo");
            assert_region(&got.eu, cg, counts, "eu");
            assert_region(&got.hyper, cg, counts, "hyper");
        }
    }

    #[test]
    fn clarke_zones_are_a_partition() {
        let mut seen = [false; 5];
        for ti in 0..=400u32 {
            for pi in 0..=400u32 {
                let (t, p) = (f64::from(ti), f64::from(pi));
                let (a, b, c, d, e) = clarke_zones(p, t);
                let set = u8::from(a) + u8::from(b) + u8::from(c) + u8::from(d) + u8::from(e);
                assert_eq!(set, 1, "({p}, {t}) set {set} flags");
                seen[match clarke_zone(p, t) {
                    ClarkeZone::A => 0,
                    ClarkeZone::B => 1,
                    ClarkeZone::C => 2,
                    ClarkeZone::D => 3,
                    ClarkeZone::E => 4,
                }] = true;
            }
        }
        assert!(seen.iter().all(|&s| s), "a zone is unreachable on 0–400 mg/dL: {seen:?}");
    }

    /// Axis lengths deliberately unequal: a transposed index survives a square lattice silently.
    #[test]
    fn clarke_zone_grid_is_truth_major_and_is_the_classifier() {
        let truth: Vec<f64> = (0..37).map(|i| f64::from(i) * 11.0).collect();
        let pred: Vec<f64> = (0..29).map(|j| f64::from(j) * 14.0).collect();
        assert_ne!(truth.len(), pred.len());
        let grid = clarke_zone_grid(truth.clone(), pred.clone()).unwrap();
        assert_eq!(grid.len(), truth.len() * pred.len());
        for (i, &t) in truth.iter().enumerate() {
            for (j, &p) in pred.iter().enumerate() {
                assert_eq!(grid[i * pred.len() + j], clarke_zone(p, t), "cell ({i}, {j})");
            }
        }

        assert!(clarke_zone_grid(vec![], vec![1.0]).unwrap().is_empty());
        assert!(clarke_zone_grid(vec![f64::NAN], vec![100.0]).is_err());
        assert!(clarke_zone_grid(vec![0.0; 2048], vec![0.0; 2048]).is_err());
    }

    /// Pairs on each boundary of `metrics.py::_clarke`, and their neighbours just across it.
    #[test]
    fn clarke_zone_boundaries_are_where_the_reference_puts_them() {
        for (pred, truth, want) in [
            (120.0, 100.0, ClarkeZone::A),  // rel exactly 0.20, above
            (121.0, 100.0, ClarkeZone::B),
            (80.0, 100.0, ClarkeZone::A),   // rel exactly 0.20, below
            (79.0, 100.0, ClarkeZone::B),
            (70.0, 40.0, ClarkeZone::A),    // both edges of the hypo square
            (70.1, 40.0, ClarkeZone::D),
            (20.0, 70.0, ClarkeZone::A),
            (20.0, 70.1, ClarkeZone::B),
            (180.0, 70.0, ClarkeZone::E),   // the two E corners
            (179.9, 70.0, ClarkeZone::D),
            (70.0, 180.0, ClarkeZone::E),
            (70.0, 179.9, ClarkeZone::B),
            (210.0, 100.0, ClarkeZone::C),  // pb = tb + 110, the upper C edge
            (209.0, 100.0, ClarkeZone::B),
            (400.0, 290.0, ClarkeZone::C),  // its truth ceiling
            (401.0, 291.0, ClarkeZone::B),
            (55.0, 170.0, ClarkeZone::C),   // under pb = 7/5·tb − 182, the lower C edge
            (57.0, 170.0, ClarkeZone::B),
            (180.0, 240.0, ClarkeZone::D),  // the zone-D truth floor and pred ceiling
            (180.0, 239.0, ClarkeZone::B),
            (179.0, 60.0, ClarkeZone::D),
            (0.0, 0.0, ClarkeZone::A),      // the max(·, 1 mg/dL) clamp
        ] {
            assert_eq!(clarke_zone(pred, truth), want, "pred {pred}, truth {truth}");
        }
    }


    /// Table A1 border polylines vs closed form; tolerance in RISK units (0.05 low, 3.7 high).
    #[test]
    fn dts_risk_reproduces_the_published_border_vertices() {
        // (threshold, [(reference, monitor)]) — the four borders, lower then upper.
        let borders: [(f64, [(f64, f64); 3]); 8] = [
            (0.5, [(62.5, 0.0), (62.5, 50.0), (600.0, 480.0)]),
            (0.5, [(0.0, 60.0), (50.0, 60.0), (500.0, 600.0)]),
            (1.5, [(97.5, 0.0), (97.5, 50.0), (600.0, 307.0)]),
            (1.5, [(0.0, 86.5), (50.0, 86.5), (347.0, 600.0)]),
            (2.5, [(153.0, 0.0), (153.0, 50.0), (600.0, 197.0)]),
            (2.5, [(0.0, 124.0), (50.0, 124.0), (241.0, 600.0)]),
            (3.5, [(238.0, 0.0), (238.0, 50.0), (600.0, 126.0)]),
            (3.5, [(0.0, 179.0), (50.0, 179.0), (167.0, 600.0)]),
        ];
        // The largest deviation any published vertex shows, in risk units, is 0.0171.
        const VERTEX_TOL: f64 = 0.02;
        for (threshold, verts) in borders {
            for (reference, monitor) in verts {
                let got = dts_risk(monitor, reference).abs();
                assert!(
                    (got - threshold).abs() < VERTEX_TOL,
                    "vertex ref {reference} / mon {monitor}: |risk| {got}, want {threshold}",
                );
            }
        }
    }

    #[test]
    fn dts_zone_a_is_not_twenty_percent() {
        assert_eq!(dts_zone(120.0, 100.0), DtsZone::B, "1.20x the truth is B, not A");
        assert_eq!(dts_zone(119.9, 100.0), DtsZone::A);
        assert_eq!(dts_zone(80.1, 100.0), DtsZone::A);
        assert_eq!(dts_zone(80.0, 100.0), DtsZone::B, "0.80x the truth is B on the 2.25 arm");
        assert!(dts_risk(125.0, 100.0).abs() > dts_risk(80.0, 100.0).abs());
    }

    #[test]
    fn dts_zone_ceilings_are_closed_and_ordered() {
        for (i, &c) in DTS_ZONE_CEILINGS.iter().enumerate() {
            let below = [DtsZone::A, DtsZone::B, DtsZone::C, DtsZone::D][i];
            let above = [DtsZone::B, DtsZone::C, DtsZone::D, DtsZone::E][i];
            assert_eq!(dts_zone_of_abs_risk(c), below, "ceiling {c} must close downward");
            assert_eq!(dts_zone_of_abs_risk(c + 1e-9), above, "just past {c}");
        }
        assert!(DTS_ZONE_CEILINGS.windows(2).all(|w| w[0] < w[1]));
    }

    #[test]
    fn dts_clamp_makes_the_bottom_of_the_grid_total() {
        // Both under the floor ⇒ risk 0, the paper's printed clause without a special case.
        assert_eq!(dts_risk(30.0, 40.0), 0.0);
        assert_eq!(dts_risk(0.0, 0.0), 0.0);
        assert_eq!(dts_zone(10.0, 49.0), DtsZone::A);
        // A zero or negative reading is a ratio of one, never a logarithm of zero.
        assert!(dts_risk(0.0, 200.0).is_finite());
        assert!(dts_risk(-50.0, 200.0).is_finite());
        assert_eq!(dts_risk(-50.0, 200.0), dts_risk(50.0, 200.0));
        // One axis under the floor still discriminates on the other.
        assert_eq!(dts_zone(40.0, 300.0), DtsZone::E);
    }

    #[test]
    fn dts_zones_are_all_reachable_on_the_drawn_extent() {
        let mut seen = [false; 5];
        for ti in 0..=400u32 {
            for pi in 0..=400u32 {
                let z = dts_zone(f64::from(pi), f64::from(ti));
                seen[z as usize] = true;
            }
        }
        assert!(seen.iter().all(|&s| s), "a DTS zone is unreachable on 0–400 mg/dL: {seen:?}");
    }

    /// Axis lengths deliberately unequal; this grid is asymmetric, so a transpose is visible.
    #[test]
    fn dts_zone_grid_is_truth_major_and_is_the_classifier() {
        let truth: Vec<f64> = (0..37).map(|i| f64::from(i) * 11.0).collect();
        let pred: Vec<f64> = (0..29).map(|j| f64::from(j) * 14.0).collect();
        assert_ne!(truth.len(), pred.len());
        let grid = dts_zone_grid(truth.clone(), pred.clone()).unwrap();
        assert_eq!(grid.len(), truth.len() * pred.len());
        for (i, &t) in truth.iter().enumerate() {
            for (j, &p) in pred.iter().enumerate() {
                assert_eq!(grid[i * pred.len() + j], dts_zone(p, t), "cell ({i}, {j})");
            }
        }
        let flipped = dts_zone_grid(pred, truth).unwrap();
        assert_ne!(grid, flipped, "the grid must not be symmetric under transposition");

        assert!(dts_zone_grid(vec![], vec![1.0]).unwrap().is_empty());
        assert!(dts_zone_grid(vec![f64::NAN], vec![100.0]).is_err());
        assert!(dts_zone_grid(vec![0.0; 2048], vec![0.0; 2048]).is_err());
    }

    #[test]
    fn trend_bins_close_outward_from_the_flat_bin() {
        for (rate, want) in [
            (-9.0, 0), (-2.001, 0),
            (-2.0, 1), (-1.5, 1), (-1.001, 1),
            (-1.0, 2), (0.0, 2), (1.0, 2),
            (1.001, 3), (1.5, 3), (2.0, 3),
            (2.001, 4), (9.0, 4),
        ] {
            assert_eq!(trend_bin(rate), Some(want), "rate {rate}");
        }
        // No bin, not the top one a bare comparison chain would fall to.
        assert_eq!(trend_bin(f64::NAN), None);
        assert_eq!(trend_bin(f64::INFINITY), None);
    }

    /// The paper's structural rules, standing in for a reference implementation nobody publishes.
    #[test]
    fn trend_category_tables_follow_the_published_rules() {
        let low = &TREND_CATEGORY_TABLES[0];
        let main = &TREND_CATEGORY_TABLES[1];
        let high = &TREND_CATEGORY_TABLES[2];

        // 1. Every cell is a real category.
        for t in &TREND_CATEGORY_TABLES {
            for row in t {
                for &c in row {
                    assert!((1..=5).contains(&c), "category {c} is not 1–5");
                }
            }
        }

        // 2. The diagonal is category 1 and NOTHING else is.
        for t in &TREND_CATEGORY_TABLES {
            for tb in 0..TREND_BINS {
                for pb in 0..TREND_BINS {
                    assert_eq!(t[tb][pb] == 1, tb == pb, "category 1 off the diagonal at ({tb}, {pb})");
                }
            }
        }

        // 3. Main table 4/5 = FDA iCGM cells: fcast>+1 & truth<−2, or fcast<−1 & truth>+2.
        assert_eq!(main[0][3], 5);
        assert_eq!(main[0][4], 5);
        assert_eq!(main[4][0], 4);
        assert_eq!(main[4][1], 4);
        let fives = |t: &[[u8; TREND_BINS]; TREND_BINS]| {
            t.iter().flatten().filter(|&&c| c == 5).count()
        };
        assert_eq!(fives(main), 2, "the main table's category 5 is the two FDA cells");

        // 4. Below 100 mg/dL category 5 widens to six cells, and there it IS a bin-distance rule.
        assert_eq!(fives(low), 6);
        for tb in 0..TREND_BINS {
            for pb in 0..TREND_BINS {
                assert_eq!(
                    low[tb][pb] == 5,
                    pb as isize - tb as isize >= 2,
                    "sub-100 category 5 at ({tb}, {pb}) is not the distance rule",
                );
            }
        }

        // 5. Above 180 mg/dL, 5 folds into 4 and the table is otherwise the main one.
        assert_eq!(fives(high), 0, "the hyperglycaemic table has no category 5");
        for tb in 0..TREND_BINS {
            for pb in 0..TREND_BINS {
                let want = if main[tb][pb] == 5 { 4 } else { main[tb][pb] };
                assert_eq!(high[tb][pb], want, "hyper table at ({tb}, {pb})");
            }
        }

        // 6. An overestimated rate never scored more leniently than its mirrored underestimate.
        for t in &TREND_CATEGORY_TABLES {
            for tb in 0..TREND_BINS {
                for pb in (tb + 1)..TREND_BINS {
                    let over = t[tb][pb];
                    let under = t[TREND_BINS - 1 - tb][TREND_BINS - 1 - pb];
                    assert!(over >= under, "mirrored pair ({tb},{pb}) scores {over} vs {under}");
                }
            }
        }
    }

    #[test]
    fn trend_region_cuts_are_the_papers_own() {
        assert_eq!(TREND_REGION_CUTS, [100.0, 180.0]);
        assert!(std::ptr::eq(trend_category_table(99.9), &TREND_CATEGORY_TABLES[0]));
        assert!(std::ptr::eq(trend_category_table(100.0), &TREND_CATEGORY_TABLES[1]));
        assert!(std::ptr::eq(trend_category_table(180.0), &TREND_CATEGORY_TABLES[1]));
        assert!(std::ptr::eq(trend_category_table(180.1), &TREND_CATEGORY_TABLES[2]));
        // 70 is CG-EGA's hypo cut, not one of these.
        assert!(std::ptr::eq(trend_category_table(70.0), &TREND_CATEGORY_TABLES[0]));
    }

    #[test]
    fn trend_matrix_is_truth_major_and_anchors_on_persistence() {
        // Horizon 15min ⇒ k=2, 3-step lookback lands on anchor; truth +3.0 mg/dL/min (top bin).
        let w = window(&[100.0, 100.0, 100.0], &[100.0, 100.0, 145.0], 100.0, 4.0);
        let s = forecast_metrics_suite(vec![w], vec![15], cfg(), false).unwrap();
        let m = &s.horizons[0].trend;
        assert_eq!(m.n, 1);
        assert_eq!(m.counts[4 * TREND_BINS + 2], 1, "truth bin 4 against forecast bin 2");
        assert_eq!(m.counts.iter().sum::<u32>(), 1, "exactly one cell filled");
        // truth[k] = 145 ⇒ the main table ⇒ (4, 2) is category 2.
        assert_eq!(m.category_n, vec![0, 1, 0, 0, 0]);
        close(m.category_pct[1], 100.0, "category 2 share");
    }

    #[test]
    fn a_perfect_forecast_is_zone_a_and_category_one() {
        let truth = [60.0, 95.0, 130.0, 190.0, 260.0, 300.0];
        let w = window(&truth, &truth, 100.0, 6.0);
        let s = forecast_metrics_suite(vec![w], vec![20, 25, 30], cfg(), false).unwrap();
        for h in &s.horizons {
            let tag = h.horizon_min;
            close(h.median_line.dts_a, 100.0, &format!("@{tag} dts_a"));
            close(h.median_line.dts_mean_abs_risk, 0.0, &format!("@{tag} mean |risk|"));
            close(h.median_line.clarke_a, 100.0, &format!("@{tag} clarke_a"));
            assert_eq!(h.trend.n, 1);
            close(h.trend.category_pct[0], 100.0, &format!("@{tag} category 1"));
            let diag: u32 = (0..TREND_BINS).map(|i| h.trend.counts[i * TREND_BINS + i]).sum();
            assert_eq!(diag, 1, "@{tag}: a perfect rate must sit on the diagonal");
        }
    }

    #[test]
    fn trend_matrix_of_no_scored_pair_has_no_shares() {
        // A lookback that reaches before the anchor: horizon 5 min ⇒ k = 0, back = −3.
        let w = window(&[100.0, 105.0, 110.0], &[102.0, 107.0, 112.0], 98.0, 4.0);
        let s = forecast_metrics_suite(vec![w], vec![5], cfg(), false).unwrap();
        let m = &s.horizons[0].trend;
        assert_eq!(m.n, 0);
        assert!(m.category_pct.is_empty(), "no pair scored ⇒ no shares to draw");
        assert_eq!(m.counts.iter().sum::<u32>(), 0);
        assert_eq!(m.counts.len(), TREND_BINS * TREND_BINS);
    }

    fn cfg() -> MetricsConfig {
        MetricsConfig {
            hypo_threshold_mgdl: 70.0,
            hyper_threshold_mgdl: 180.0,
            excursion_precision_tolerance_mgdl: 10.0,
            min_samples: 1,
        }
    }

    /// A window whose fan is the median ± `spread`·(1,2,3) — ascending by construction.
    fn window(median: &[f64], truth: &[f64], last_bg: f64, spread: f64) -> ForecastWindow {
        let mut bands = Vec::with_capacity(median.len() * QUANTILE_LEVELS.len());
        for &m in median {
            for k in 0..QUANTILE_LEVELS.len() {
                bands.push(m + spread * (k as f64 - 3.0));
            }
        }
        ForecastWindow {
            bands_mgdl: bands,
            median_bg: median.to_vec(),
            realized_bg: truth.to_vec(),
            last_bg,
        }
    }

    #[test]
    fn suite_empty_input_is_an_empty_suite() {
        let s = forecast_metrics_suite(vec![], vec![30], cfg(), true).unwrap();
        assert!(s.horizons.is_empty());
        assert_eq!(s.n_windows, 0);
        assert_eq!(s.n_steps, 0);
        // Asked for and empty, distinct from never computed.
        assert_eq!(s.cgega.unwrap().eu.ap_pct, None);
        assert_eq!(forecast_metrics_suite(vec![], vec![30], cfg(), false).unwrap().cgega, None);

        // A zero-step window is the same nothing, not a divide-by-zero.
        let s = forecast_metrics_suite(vec![window(&[], &[], 100.0, 5.0)], vec![30], cfg(), true)
            .unwrap();
        assert_eq!(s.n_windows, 0);
        assert!(s.horizons.is_empty());
    }

    #[test]
    fn suite_rejects_nonfinite_and_misordered_windows() {
        let good = window(&[100.0, 105.0], &[102.0, 107.0], 98.0, 4.0);

        let mut nan = good.clone();
        nan.bands_mgdl[3] = f64::NAN;
        let mut inf = good.clone();
        inf.last_bg = f64::INFINITY;
        // A mirrored interval: τ.75 below τ.25 at step 0, far past the fp16 rounding slack.
        let mut misordered = good.clone();
        misordered.bands_mgdl.swap(2, 4);

        let s = forecast_metrics_suite(
            vec![good.clone(), nan, inf, misordered],
            vec![5, 10],
            cfg(),
            true,
        )
        .unwrap();
        assert_eq!(s.n_windows, 1);
        assert_eq!(s.n_rejected, 3);

        let mut all_bad = good;
        all_bad.median_bg[0] = f64::NAN;
        let s = forecast_metrics_suite(vec![all_bad], vec![5], cfg(), true).unwrap();
        assert_eq!(s.n_windows, 0);
        assert_eq!(s.n_rejected, 1);
        assert!(s.horizons.is_empty());
    }

    #[test]
    fn suite_degenerate_band_reduces_to_a_point_forecast() {
        // §6.2: lo == hi ⇒ the projection returns that common value exactly.
        assert_eq!(band_project(80.0, 100.0, 100.0), 100.0);
        assert_eq!(band_project(120.0, 100.0, 100.0), 100.0);
        assert_eq!(band_project(110.0, 100.0, 120.0), 110.0);
        assert_eq!(band_project(90.0, 100.0, 120.0), 100.0);
        assert_eq!(band_project(130.0, 100.0, 120.0), 120.0);

        let w = window(&[100.0, 105.0, 110.0], &[104.0, 99.0, 130.0], 98.0, 0.0);
        let s = forecast_metrics_suite(vec![w], vec![5, 10, 15], cfg(), true).unwrap();
        for h in &s.horizons {
            // The series is the one asymmetry, withheld by contract.
            assert_eq!(
                PointBlock { points: h.median_line.points.clone(), ..h.band.clone() },
                h.median_line,
                "collapsed fan @{}",
                h.horizon_min,
            );
            assert!(h.band.points.is_empty(), "collapsed fan @{}", h.horizon_min);
            assert_eq!(h.band_width50, 0.0);
            assert_eq!(h.band_cov50, 0.0);
        }
    }

    #[test]
    fn suite_zero_persistence_rmse_leaves_skill_undefined() {
        // Persistence was perfect ⇒ the skill ratio has no denominator.
        let w = window(&[110.0], &[100.0], 100.0, 5.0);
        let s = forecast_metrics_suite(vec![w], vec![5], cfg(), true).unwrap();
        let h = &s.horizons[0];
        assert_eq!(h.rmse_persist_point, 0.0);
        assert_eq!(h.band.skill_point, None);
        assert_eq!(h.median_line.skill_point, None);
        assert!(h.band.rmse_point.is_finite());
    }

    #[test]
    fn suite_rejects_impossible_shapes_and_horizons() {
        let w = window(&[100.0, 105.0], &[102.0, 107.0], 98.0, 4.0);
        let is_err = |r: Result<MetricsSuite, CoreError>| matches!(r, Err(CoreError::Internal { .. }));

        // A horizon off the five-minute grid, or one past the end of the window.
        assert!(is_err(forecast_metrics_suite(vec![w.clone()], vec![7], cfg(), true)));
        assert!(is_err(forecast_metrics_suite(vec![w.clone()], vec![0], cfg(), true)));
        assert!(is_err(forecast_metrics_suite(vec![w.clone()], vec![120], cfg(), true)));

        // Windows that disagree on their step count, and a fan missing a level.
        let short = window(&[100.0], &[102.0], 98.0, 4.0);
        assert!(is_err(forecast_metrics_suite(vec![w.clone(), short], vec![5], cfg(), true)));
        let mut truncated = w.clone();
        truncated.bands_mgdl.pop();
        assert!(is_err(forecast_metrics_suite(vec![truncated], vec![5], cfg(), true)));

        // A config that cannot score anything.
        let mut bad_cfg = cfg();
        bad_cfg.excursion_precision_tolerance_mgdl = -1.0;
        assert!(is_err(forecast_metrics_suite(vec![w.clone()], vec![5], bad_cfg, true)));
        let mut nan_cfg = cfg();
        nan_cfg.hypo_threshold_mgdl = f64::NAN;
        assert!(is_err(forecast_metrics_suite(vec![w], vec![5], nan_cfg, true)));
    }

    /// The membership + side check `SPEC/invariants.md` §6.1 says every consumer owes.
    #[test]
    fn metric_levels_are_members_of_the_fan_on_the_right_side() {
        for (name, tau, lower) in [
            ("METRIC_BAND_TAU_LO", METRIC_BAND_TAU_LO, true),
            ("METRIC_BAND_TAU_HI", METRIC_BAND_TAU_HI, false),
            ("HYPO_ALARM_QUANTILE_TAU", HYPO_ALARM_QUANTILE_TAU, true),
            ("HYPER_ALARM_QUANTILE_TAU", HYPER_ALARM_QUANTILE_TAU, false),
            ("OUTER_TAU_LO", OUTER_TAU_LO, true),
            ("OUTER_TAU_HI", OUTER_TAU_HI, false),
        ] {
            let i = tau_index(tau).unwrap_or_else(|| panic!("{name} = {tau} is not a fan level"));
            assert_eq!(QUANTILE_LEVELS[i], tau, "{name} resolved to the wrong position");
            assert_eq!(tau < 0.5, lower, "{name} sits on the wrong side of the median");
        }
        assert_eq!(tau_index(0.5), Some(3), "the median is index 3 (SPEC §6)");
        assert_eq!(tau_index(0.3), None, "a level outside the fan has no position");
        assert!(QUANTILE_LEVELS.windows(2).all(|w| w[0] < w[1]));
    }

    #[test]
    fn suite_never_panics_on_hostile_windows() {
        // Deterministic xorshift over wild magnitudes, sign flips and non-finite values.
        let mut state: u64 = 0xDEAD_BEEF_1234_5678;
        let mut next = || {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            state
        };
        let wild = |r: u64| -> f64 {
            match r % 7 {
                0 => f64::NAN,
                1 => f64::INFINITY,
                2 => f64::NEG_INFINITY,
                3 => -(r as f64),
                4 => (r % 1_000_000) as f64 * 1e30,
                _ => (r % 60_000) as f64 / 100.0,
            }
        };
        for _ in 0..2_000 {
            let steps = (next() % 5) as usize;
            let mk = |n: usize, f: &mut dyn FnMut() -> u64| -> Vec<f64> {
                (0..n).map(|_| wild(f())).collect()
            };
            let w = ForecastWindow {
                bands_mgdl: mk(steps * QUANTILE_LEVELS.len(), &mut next),
                median_bg: mk(steps, &mut next),
                realized_bg: mk(steps, &mut next),
                last_bg: wild(next()),
            };
            let horizons = vec![(next() % 200) as u32];
            // Ok or Err; the invariant is no panic and no silently-NaN accepted horizon.
            if let Ok(s) = forecast_metrics_suite(vec![w], horizons, cfg(), true) {
                for h in &s.horizons {
                    assert!(h.band.rmse_point.is_finite(), "accepted horizon carried a NaN rmse");
                    assert!(h.band_width50.is_finite());
                }
            }
        }
    }
}
