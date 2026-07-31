//! On-device forecast-accuracy aggregator (Phase 7C, Phase 7 "Models
//! drill-down — performance").
//!
//! A pure, golden-gated reducer: given already-matured `(predicted median BG, realized
//! BG)` pairs — each tagged by its forecast horizon in minutes — it computes, per
//! horizon, the point RMSE / MAE / MARD and (when band edges are supplied) the empirical
//! central-90 coverage. The Kotlin side owns the *pairing* (walking each stored
//! `prediction` row forward to the realized `cgm_reading` at `made_at + h`); this owns
//! only the arithmetic, so the golden fixture is deterministic and provider-free.
//!
//! Formula provenance — reproduces `T1DMAI/realdata/metrics.py::compute_suite` exactly:
//!   - RMSE(h) = sqrt(mean((pred - real)^2)) over the horizon's pairs.
//!   - MAE(h)  = mean(|pred - real|).
//!   - MARD(h) = 100 · mean(|pred - real| / max(real, 1))   (`np.clip(true, 1, None)`).
//!   - coverage90(h) = mean(band_lo <= real <= band_hi)  over pairs carrying a band
//!     (the τ.05 / τ.95 fan edges — matching train.py's `coverage90@h`).
//!
//! This is an accuracy statement about a *forecast*, never a dosing claim:
//! it is displayed advisory-only. Everything is total — an empty horizon or a bad
//! `horizon_min` never panics; a horizon with fewer than `min_samples` matured pairs is
//! still emitted (with its true `n`) and flagged `sufficient = false` so the UI can say
//! "insufficient history" plainly rather than print a noisy statistic.

use crate::cg_ega::{self, CgEgaCounts};
use crate::curve::DT_MINUTES;
use crate::CoreError;
use std::collections::BTreeMap;

/// One matured forecast↔realization pair at a single horizon. `band_lo`/`band_hi` are the
/// τ.05 / τ.95 mg/dL fan edges at this horizon step; ignored for coverage unless
/// `has_band` is set (a degenerate / band-less cohort passes `has_band = false`).
#[derive(Debug, Clone, uniffi::Record)]
pub struct AccuracyPair {
    pub horizon_min: u32,
    pub predicted: f64,
    pub realized: f64,
    pub band_lo: f64,
    pub band_hi: f64,
    pub has_band: bool,
}

/// The reduced accuracy at one horizon. `coverage90` is `None` when no pair at this
/// horizon carried a band. `sufficient` is `n >= min_samples` — the UI shows the metrics
/// only when true, else a plain "insufficient history (n/min)" line.
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

/// The full per-horizon accuracy report, horizons ascending. `n_pairs` is the total
/// matured pair count fed in (finite, matched); `min_samples` echoes the caller's gate.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct AccuracyReport {
    pub horizons: Vec<HorizonAccuracy>,
    pub n_pairs: u32,
    pub min_samples: u32,
}

/// Reduce matured `pairs` to per-horizon RMSE/MAE/MARD (+ central-90 coverage) accuracy.
///
/// Groups by `horizon_min` (ascending in the output), drops any pair whose `predicted` or
/// `realized` is non-finite (a degenerate forecast never reached maturity, but guard
/// anyway — fail-closed), and marks a horizon `sufficient` iff its surviving count is at
/// least `min_samples`. Never panics; an empty input yields an empty report.
#[uniffi::export]
pub fn accuracy_at_horizons(
    pairs: Vec<AccuracyPair>,
    min_samples: u32,
) -> Result<AccuracyReport, CoreError> {
    // Bucket finite pairs by horizon (BTreeMap ⇒ ascending horizon order for free).
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
            // MARD denominator clamps the realized value at 1 mg/dL (numpy clip(true,1,None)).
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

// ═══════════════════════════════════════════════════════════════════════════════════════
// The full metric suite — `T1DMAI/realdata/metrics.py::compute_suite` on device.
// ═══════════════════════════════════════════════════════════════════════════════════════
//
// `accuracy_at_horizons` above scores a forecast the way the Models drill-down needs it:
// two fan edges and a median, one matured point per horizon. The suite below scores it the
// way `T1DMAI`'s validation table does — on the BAND PROJECTION of `SPEC/invariants.md`
// §6.2, with the median line kept nested beneath as the peer-comparable basis — and needs
// the whole seven-level column per step, the whole realized trajectory, and the
// persistence anchor to do it. Hence the wider input record.
//
// DELIBERATELY OMITTED: `rmse_macro`. The reference's is a per-patient macro average over
// the validation cohort — it exists to stop one long patient record dominating the pooled
// figure. This device has exactly one patient, so the macro average collapses to the micro
// one and reports nothing; emitting it would invite a comparison against `T1DMAI`'s
// cohort number that means nothing. It is left out, not forgotten.

/// The seven forecast quantile levels, ascending — `SPEC/invariants.md` §6. The one copy
/// in this crate; `preproc::assemble_decode` lays `Forecast::bands_mgdl` out in exactly
/// this order, and [`ForecastWindow::bands_mgdl`] inherits that layout unchanged.
const QUANTILE_LEVELS: [f64; 7] = [0.05, 0.10, 0.25, 0.50, 0.75, 0.90, 0.95];

// ── The four metric levels (`SPEC/invariants.md` §6.1) ─────────────────────────────────
// Each is a LEVEL, resolved to a fan position by `tau_index` lookup — never a literal
// index. A fan re-levelled without its consumers being re-pointed still parses and still
// validates; only the lookup notices. The two pairs are numerically equal today and named
// separately on purpose: the band a metric scores against and the envelope an alarm reads
// are independent choices, and either may move without the other. None of the four is
// descriptor-carried, so this file holds the phone's copy and §6.1 fixes its values.

/// Lower edge of the band the level metrics score against (§6.2).
const METRIC_BAND_TAU_LO: f64 = 0.25;
/// Upper edge of the same band.
const METRIC_BAND_TAU_HI: f64 = 0.75;
/// The lower band edge whose dip below the hypo threshold raises the scored hypo alarm.
const HYPO_ALARM_QUANTILE_TAU: f64 = 0.25;
/// The upper band edge whose rise above the hyper threshold raises the scored hyper alarm.
const HYPER_ALARM_QUANTILE_TAU: f64 = 0.75;

/// The outer envelope the central-90 coverage is read on — the extreme levels of the fan.
/// Not a §6.1 metric level: it is simply the first and last entries of [`QUANTILE_LEVELS`],
/// resolved the same way so a re-levelled fan cannot silently re-point it either.
const OUTER_TAU_LO: f64 = 0.05;
const OUTER_TAU_HI: f64 = 0.95;

/// mg/dL slack on the ascending-fan check. The fan reaches mg/dL through `f_inv`, so two
/// adjacent levels that coincide in risk space can differ by a float rounding step
/// (`metrics.py::_FAN_ORDER_TOL_MGDL`).
const FAN_ORDER_TOL_MGDL: f64 = 1e-6;

/// Persistence RMSE below which the skill score is undefined rather than explosive
/// (`metrics.py`'s `> 1e-9` guard, which yields NaN; here it yields `None`).
const PERSIST_RMSE_EPS: f64 = 1e-9;

/// Position of a quantile level in the fan, or `None` if that level is not one of the
/// seven. §6.1: a level absent from the tuple has no position and cannot announce its own
/// absence, so the lookup — not an assumed index — is what makes the absence loud.
fn tau_index(tau: f64) -> Option<usize> {
    QUANTILE_LEVELS.iter().position(|&t| t == tau)
}

/// One matured forecast window, scored whole.
///
/// The step grid is `SPEC/invariants.md` §1's five minutes, so step `i` matures at
/// `made_at + 5·(i+1)` minutes and a horizon of `h` minutes is step `h/5 − 1`.
///
/// * `bands_mgdl` — the full quantile fan, `steps × 7` row-major in ascending τ, mg/dL:
///   exactly `preproc::Forecast::bands_mgdl`. Every metric that reads a band edge resolves
///   its column by level lookup, so this must carry all seven, not a pre-selected pair.
/// * `median_bg` — the median line, one per step. The nested peer-comparable basis.
/// * `realized_bg` — the trajectory that actually happened, one per step.
/// * `last_bg` — the persistence anchor: the measured BG at the forecast's `made_at`. It
///   is both the baseline the skill score competes against (held flat) and the step CG-EGA
///   differences its first rate against (§6.3). An anchor lifted from another cycle
///   corrupts `dy` at `t = 0`, the step at which a fast fall is most decisive.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ForecastWindow {
    pub bands_mgdl: Vec<f64>,
    pub median_bg: Vec<f64>,
    pub realized_bg: Vec<f64>,
    pub last_bg: f64,
}

/// What the excursion detectors are scored against. §6.1 fixes which band EDGE they read
/// but deliberately not what it is compared to: that is the consumer's own threshold — a
/// fixed clinical pair in `T1DMAI`'s validation table, the patient's configurable bands
/// here. `excursion_precision_tolerance_mgdl` forgives a near-boundary false alarm whose
/// edge lies within it of the true value (CGM noise at a threshold should not deflate
/// precision); recall is strict regardless.
#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct MetricsConfig {
    pub hypo_threshold_mgdl: f64,
    pub hyper_threshold_mgdl: f64,
    pub excursion_precision_tolerance_mgdl: f64,
    pub min_samples: u32,
}

/// The per-horizon point-error block for ONE forecast basis.
///
/// The basis is part of every figure's identity (§6.2): the same struct filled from the
/// band projection and from the median line holds two different quantities measured on one
/// forecast. Never report them in a single column, and never against an outside number
/// without naming which basis yours is.
///
/// `*_point` is the strict value at the horizon step; `*_winmean` pools every step from 0
/// to that horizon (the basis published transformer baselines use). `clarke_ab` is the
/// A∪B share, not B alone. `skill_point` is `(rmse_persist − rmse) / rmse_persist` against
/// the persistence baseline, `None` where persistence itself was perfect.
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
    pub skill_point: Option<f64>,
}

/// Band-edge recall / precision for one threshold crossing. `recall` is `None` when the
/// truth never crossed, `precision` when the forecast never called one — an undefined
/// ratio, not a zero.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct ExcursionAccuracy {
    pub recall: Option<f64>,
    pub precision: Option<f64>,
    pub n_true: u32,
    pub n_pred: u32,
}

/// Everything scored at one horizon.
///
/// `band` is the HEADLINE — the band projection of §6.2 — and `median_line` the same block
/// on the median. Because a wider band can only lower the error, the band figures are
/// meaningless without the two numbers that expose a band widened until it swallows
/// everything: `band_cov50` (target `METRIC_BAND_TAU_HI − METRIC_BAND_TAU_LO`) with its
/// mean edge-to-edge `band_width50`, and the outer `band_cov90` / `band_width90`.
/// Persistence carries no band, so the one baseline is shared by both bases.
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
}

/// CG-EGA for one glycaemic region: the accurate / benign / erroneous share as percentages,
/// `None` where the region held no points, beside the raw counts they came from.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct CgEgaRegion {
    pub ap_pct: Option<f64>,
    pub be_pct: Option<f64>,
    pub ep_pct: Option<f64>,
    pub n_ap: u32,
    pub n_be: u32,
    pub n_ep: u32,
}

/// CG-EGA over the WHOLE forecast window (§6.3), one triple per region. Not per horizon:
/// a CG-EGA computed at a single horizon is a different statistic and must not be
/// published under this name.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct CgEga {
    pub hypo: CgEgaRegion,
    pub eu: CgEgaRegion,
    pub hyper: CgEgaRegion,
}

/// The full suite. `n_windows` counts the windows actually scored and `n_rejected` those
/// dropped for a non-finite value or a mis-ordered fan — a rejection is never silent,
/// because a suite computed over half its input looks exactly like a good one.
///
/// `cgega` is `None` when the caller passed `include_cgega = false`. It is an `Option`
/// rather than a zeroed triple on purpose: a region that held no points and a statistic
/// that was never computed are different facts, and an all-zero `CgEga` reads as the first.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct MetricsSuite {
    pub horizons: Vec<HorizonMetrics>,
    pub cgega: Option<CgEga>,
    pub n_windows: u32,
    pub n_rejected: u32,
    pub n_steps: u32,
}

/// The effective point forecast: the band point nearest the truth (`SPEC/invariants.md`
/// §6.2).
///
/// Zero error wherever the truth lies inside the band, the distance to the nearer edge
/// wherever it lies outside, and — for a degenerate band where `lo == hi` — that common
/// value, so the projection reduces to a point forecast exactly and a collapsed fan
/// reproduces the median-line numbers unchanged.
#[inline]
fn band_project(truth: f64, lo: f64, hi: f64) -> f64 {
    // `clamp` would panic on lo > hi; the fan is checked ascending before we get here, but
    // an explicit min/max keeps this total whatever reaches it.
    truth.max(lo).min(hi)
}

/// Clarke Error Grid zone of one `(pred, true)` pair, as `(A, B, C, D, E)` flags — the
/// zone algebra of `metrics.py::_clarke`, whose sole caller wants the A, A∪B, D and E
/// shares.
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

/// Root mean square of a slice; `NaN` on an empty one, which the callers never pass.
fn rmse(errs: &[f64]) -> f64 {
    let n = errs.len() as f64;
    (errs.iter().map(|e| e * e).sum::<f64>() / n).sqrt()
}

/// Mean of a slice.
fn mean(xs: &[f64]) -> f64 {
    xs.iter().sum::<f64>() / xs.len() as f64
}

/// A window that survived validation, with everything the horizon loop reads precomputed.
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

/// Build the per-horizon point block for one basis, given the horizon-step predictions,
/// the truths, the window-mean errors over steps `0..=k`, and the shared persistence RMSE.
///
/// Persistence has no band, so `rmse_persist` is computed once per horizon by the caller
/// and fed to both bases; `skill_point` then differs only through the model side.
fn point_block(
    pred_k: &[f64],
    true_k: &[f64],
    err_winmean: &[f64],
    rmse_persist: f64,
) -> PointBlock {
    let n = pred_k.len() as f64;
    let mut errs = Vec::with_capacity(pred_k.len());
    let mut abs_sum = 0.0f64;
    let mut ard_sum = 0.0f64;
    let (mut n_a, mut n_ab, mut n_d, mut n_e) = (0u32, 0u32, 0u32, 0u32);
    for (&p, &t) in pred_k.iter().zip(true_k.iter()) {
        let e = p - t;
        errs.push(e);
        abs_sum += e.abs();
        // MARD's denominator clamps the truth at 1 mg/dL (numpy `clip(true, 1, None)`).
        ard_sum += e.abs() / t.max(1.0);
        let (a, b, _c, d, ee) = clarke_zones(p, t);
        n_a += a as u32;
        n_ab += (a || b) as u32;
        n_d += d as u32;
        n_e += ee as u32;
    }
    let rmse_point = rmse(&errs);
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
        skill_point: if rmse_persist > PERSIST_RMSE_EPS {
            Some((rmse_persist - rmse_point) / rmse_persist)
        } else {
            // A persistence baseline that was itself perfect leaves the ratio undefined.
            // Reporting 0 (or −inf) would read as a real skill figure; `None` cannot.
            None
        },
    }
}

/// Band-edge recall and precision for one threshold crossing.
///
/// `edge` is the τ-lower band edge for a hypo test, the τ-upper for a hyper one (§6.1: the
/// alarm is scored on the excursion's possibility, not its expectation). Recall is strict;
/// precision forgives a false alarm whose edge is within `tol` of the true value.
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

/// Reduce the nine CG-EGA counts to per-region percentages.
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

/// Score matured forecast windows the way `T1DMAI/realdata/metrics.py::compute_suite`
/// does — per horizon on the band projection of `SPEC/invariants.md` §6.2, with the median
/// line nested beneath, plus CG-EGA (§6.3) over the whole window.
///
/// `horizons_min` are the reported horizons in minutes; each must be a positive multiple of
/// the five-minute grid and must land inside the window (`h/5 − 1 < steps`), else the call
/// fails rather than silently scoring one step fewer on a different alignment. Every window
/// must carry the same step count.
///
/// `include_cgega` selects whether the whole-window CG-EGA is computed at all. It walks
/// every step of every window through the P-EGA × R-EGA zone algebra, which is far and away
/// the costliest part of the suite and answers a question the per-horizon table does not
/// ask; a caller that only wants the level metrics passes `false` and gets `cgega: None`.
///
/// Total on hostile input, never a panic: an empty input yields an empty suite; a window
/// carrying a non-finite value or a mis-ordered fan is rejected and counted in
/// `n_rejected`; a structurally impossible argument yields `Err`.
#[uniffi::export]
pub fn forecast_metrics_suite(
    windows: Vec<ForecastWindow>,
    horizons_min: Vec<u32>,
    config: MetricsConfig,
    include_cgega: bool,
) -> Result<MetricsSuite, CoreError> {
    let nq = QUANTILE_LEVELS.len();
    let bad = |reason: String| CoreError::Internal { reason };

    // Resolve every level to a fan position by lookup — §6.1, never a literal index.
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
    // §6.1 owes the same side check `T1DMAI` asserts at import: a level on the wrong side
    // of the median would mirror the band rather than widen it.
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

    // Every window shares one step grid — the reference's `(n_windows, PRED_STEPS)` array.
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
        // Fail closed on a window we cannot score: a non-finite value anywhere, or a fan
        // that does not ascend (an fp16 mis-order mirrors an interval silently).
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

    // ── Per-horizon blocks ─────────────────────────────────────────────────────────────
    let grid_min = DT_MINUTES as u32; // §1's five-minute grid; one forecast step.
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

        // Persistence: the last measured BG held flat. It carries no band, so this one
        // baseline is shared by both bases.
        let persist_err_k: Vec<f64> =
            scored.iter().map(|s| s.last_bg - s.truth[k]).collect();
        let rmse_persist_point = rmse(&persist_err_k);

        horizons.push(HorizonMetrics {
            horizon_min: h,
            n: n as u32,
            sufficient: n as u32 >= config.min_samples,
            band: point_block(&pred_eff_k, &true_k, &err_band, rmse_persist_point),
            median_line: point_block(&median_k, &true_k, &err_med, rmse_persist_point),
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
        });
    }

    // ── CG-EGA over the whole window (§6.3), on the band-projected forecast ────────────
    //
    // The truth goes FIRST, matching `cg_ega.py`'s declared `(y_true, y_pred, last_bg, ...)`
    // and `T1DMAI/realdata/metrics.py`'s call. The order is load-bearing rather than
    // conventional: the first trajectory is the reference on every axis — it assigns the
    // glycaemic region, centres P-EGA's acceptance band, gates the zone-D excursions and
    // supplies the rate that widens `mod` — so transposing the two re-buckets points between
    // regions and moves every denominator, yielding a well-formed table of a different
    // statistic that no assertion here would catch.
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
        // Horizons ascending, all present.
        let hs: Vec<u32> = report.horizons.iter().map(|h| h.horizon_min).collect();
        assert_eq!(hs, vec![30, 60, 120]);
        assert_eq!(report.n_pairs, 10);
        // min_samples = 3 ⇒ the 2-pair 120-min horizon is flagged insufficient.
        let h120 = report.horizons.iter().find(|h| h.horizon_min == 120).unwrap();
        assert!(!h120.sufficient);
        assert!(report.horizons.iter().find(|h| h.horizon_min == 30).unwrap().sufficient);
    }

    #[test]
    fn empty_and_nonfinite_are_total() {
        // Empty input ⇒ empty report, never a panic.
        let empty = accuracy_at_horizons(vec![], 6).unwrap();
        assert!(empty.horizons.is_empty());
        assert_eq!(empty.n_pairs, 0);

        // A non-finite pair is dropped (fail-closed); a band-less horizon ⇒ coverage None.
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

    // ══ The band-projected metric suite ════════════════════════════════════════════════

    /// Absolute tolerance against the reference. Both sides run fp64 over the same
    /// formulae, differing only in summation order (numpy reduces pairwise, we reduce
    /// sequentially), which on these magnitudes costs ~1e-12 at worst.
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

    fn assert_point_block(got: &PointBlock, want: &Value, what: &str) {
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

    /// The whole suite, reproduced from `T1DMAI/realdata/metrics.py::compute_suite` +
    /// `cg_ega.py` over synthetic windows. This is the gate: bit-faithfulness to the
    /// reference is what makes an on-device figure comparable to the validation table.
    ///
    /// The `cgega` block is taken from `cg_ega.py` called in its declared argument order,
    /// which is the order `compute_suite` calls it in — see the note above the CG-EGA block
    /// for why that order is load-bearing.
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
        // The fixture's fan levels must be the levels this crate scores on, or the whole
        // comparison is between two different statistics.
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

            // The level metrics must not depend on whether CG-EGA was asked for; only the
            // `cgega` field may differ between the two calls.
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
                assert_point_block(&h.band, &e["band"], &format!("{tag}.band"));
                assert_point_block(&h.median_line, &e["median_line"], &format!("{tag}.median_line"));
                close(h.rmse_persist_point, e["rmse_persist_point"].as_f64().unwrap(), &format!("{tag}.rmse_persist_point"));
                close(h.rmse_persist_winmean, e["rmse_persist_winmean"].as_f64().unwrap(), &format!("{tag}.rmse_persist_winmean"));
                close(h.band_cov50, e["band_cov50"].as_f64().unwrap(), &format!("{tag}.band_cov50"));
                close(h.band_width50, e["band_width50"].as_f64().unwrap(), &format!("{tag}.band_width50"));
                close(h.band_cov90, e["band_cov90"].as_f64().unwrap(), &format!("{tag}.band_cov90"));
                close(h.band_width90, e["band_width90"].as_f64().unwrap(), &format!("{tag}.band_width90"));
                assert_excursion(&h.hypo, &e["hypo"], &format!("{tag}.hypo"));
                assert_excursion(&h.hyper, &e["hyper"], &format!("{tag}.hyper"));

                // §6.2: a degenerate fan's projection IS the median line, so the two bases
                // must come back identical — not merely close.
                if case["collapsed_band"].as_bool().unwrap() {
                    assert_eq!(h.band, h.median_line, "{tag}: collapsed band must reduce to the median line");
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

    // ── Degenerate inputs: total, never a panic ────────────────────────────────────────

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
        // Asked for and empty — an empty region, distinct from never having been computed.
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

        // Every window rejected ⇒ no horizons at all rather than a NaN-filled report.
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
        // Inside the band ⇒ the truth itself (zero error); outside ⇒ the nearer edge.
        assert_eq!(band_project(110.0, 100.0, 120.0), 110.0);
        assert_eq!(band_project(90.0, 100.0, 120.0), 100.0);
        assert_eq!(band_project(130.0, 100.0, 120.0), 120.0);

        let w = window(&[100.0, 105.0, 110.0], &[104.0, 99.0, 130.0], 98.0, 0.0);
        let s = forecast_metrics_suite(vec![w], vec![5, 10, 15], cfg(), true).unwrap();
        for h in &s.horizons {
            assert_eq!(h.band, h.median_line, "collapsed fan @{}", h.horizon_min);
            assert_eq!(h.band_width50, 0.0);
            assert_eq!(h.band_cov50, 0.0);
        }
    }

    #[test]
    fn suite_zero_persistence_rmse_leaves_skill_undefined() {
        // The anchor equals the truth at the horizon ⇒ persistence was perfect and the
        // skill ratio has no denominator. `None`, never an infinity dressed as a score.
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

    /// The membership + side check `SPEC/invariants.md` §6.1 says every consumer owes:
    /// a level absent from the tuple has no position and cannot announce its own absence.
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
        // Ascending, as the contract's ORDER clause requires.
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
            // Ok or Err — the invariant is that neither panics and no metric is silently
            // NaN in an accepted horizon.
            if let Ok(s) = forecast_metrics_suite(vec![w], horizons, cfg(), true) {
                for h in &s.horizons {
                    assert!(h.band.rmse_point.is_finite(), "accepted horizon carried a NaN rmse");
                    assert!(h.band_width50.is_finite());
                }
            }
        }
    }
}
