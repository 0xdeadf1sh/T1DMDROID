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
//
// ── Two statistics here have NO reference counterpart ──────────────────────────────────
//
// The DTS Error Grid and the Trend Accuracy Matrix below (Klonoff et al. 2024) exist in this crate
// and nowhere else in the suite: `T1DMAI/realdata/metrics.py` does not compute either, so neither
// is pinned to it and neither may be compared against that project's validation table. Everything
// else in this file is; that asymmetry is the point of saying so here. Their gate is instead the
// paper itself — `dts_risk_reproduces_the_published_border_vertices` holds the closed form against
// all 24 coordinates of Table A1, and `trend_category_tables_follow_the_published_rules` holds the
// three transcribed tables against every structural rule the paper states about them.
//
// Both are also, in origin, DEVICE metrics — a monitor read against a reference — and this suite
// applies them to a FORECAST read against the realized trajectory. That is a repurposing, and an
// unsanctioned one: the paper's panel scored sensors, not predictions. It is the same repurposing
// the suite already makes of Clarke and of CG-EGA, and it is defensible for the same reason (the
// question "how wrong is this number, clinically" is the same question) — but a figure produced
// here must never be quoted against a published DTS accuracy figure for a real CGM, because the
// two are measuring different things and the forecast's horizon is not a device error at all.

/// The seven forecast quantile levels, ascending — `SPEC/invariants.md` §6. The one copy
/// in this crate; `preproc::assemble_decode` lays `Forecast::bands_mgdl` out in exactly
/// this order, and [`ForecastWindow::bands_mgdl`] inherits that layout unchanged.
pub(crate) const QUANTILE_LEVELS: [f64; 7] = [0.05, 0.10, 0.25, 0.50, 0.75, 0.90, 0.95];

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
pub(crate) fn tau_index(tau: f64) -> Option<usize> {
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

/// The Clarke Error Grid zone of one scored pair.
///
/// [`clarke_zones`] returns five flags of which exactly one is ever set (see the proof in its
/// doc comment), so this enum is a lossless collapse of that tuple rather than a second
/// reading of the algebra.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum ClarkeZone {
    A,
    B,
    C,
    D,
    E,
}

/// The DTS Error Grid zone of one scored pair — Klonoff et al. 2024 (J Diabetes Sci Technol
/// 18(6):1346), the grid that supersedes the 2014 Surveillance Error Grid.
///
/// Five zones, banded off `|risk|` by [`DTS_ZONE_CEILINGS`]. Unlike Clarke's, they are the level
/// sets of a continuous function rather than a stack of inequalities, so the zone is a *display*
/// of the risk rather than the primitive — see [`dts_risk`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum DtsZone {
    A,
    B,
    C,
    D,
    E,
}

/// One scored `(prediction, truth)` pair in mg/dL carrying every zone verdict passed on it — the
/// per-point series behind the aggregate shares of [`PointBlock`].
///
/// `pred` is the basis's own prediction at the horizon step (band-projected in
/// [`HorizonMetrics::band`], the median line in [`HorizonMetrics::median_line`]) and `truth` the
/// realized BG at that step. The order is the one [`clarke_zones`] and [`dts_risk`] both declare —
/// pred, then truth — and it is load-bearing for each: neither grid is symmetric, and transposing
/// either yields a well-formed picture of a different statistic.
///
/// **One record, two grids, deliberately.** Clarke and the DTS grid classify the very same pair, so
/// two series would double what crosses the foreign-function boundary and — the part that actually
/// matters — would let two scatters drawn on one screen plot different pairs. They cannot: there is
/// one series, and each figure reads its own column of it.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct ScoredPoint {
    pub pred: f64,
    pub truth: f64,
    pub clarke: ClarkeZone,
    pub dts: DtsZone,
    /// The SIGNED DTS risk, positive where the forecast read high of the truth. Carried because it
    /// is what the grid actually is — [`ScoredPoint::dts`] is a banding of this — and because the
    /// sign is the clinical reading: the grid penalises reading high harder than reading low.
    pub dts_risk: f64,
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
///
/// The DTS block is the same window scored on the 2024 grid: `dts_a` is the paper's headline
/// `pZA`, and the five shares are published INDIVIDUALLY and exhaustively rather than as an A∪B
/// the way Clarke's are. That is the paper's own instruction — its panel explicitly declines to
/// report a combined A+B and names `pZA` alone as the metric — so no consumer here may sum the
/// first two and call the result a DTS figure. `dts_mean_abs_risk` is the mean `|risk|` the zones
/// band, which is the grid's continuous reading and does not round a near-miss into a whole zone.
///
/// `points` is the per-point series every share above was counted from — one entry per scored
/// window, in window order. It is not an extra reduction: `point_block` classifies each pair ONCE
/// on both grids and derives the enums and the percentages from that single call, so a scatter
/// drawn from this series cannot disagree with the percentages printed beside it.
///
/// **It is carried on the MEDIAN LINE only, and is empty on the band.** Not an omission. The band
/// projection is `clip(truth, lo, hi)`, a function OF the truth, so every pair whose truth fell
/// inside the band lands exactly on the identity diagonal — unconditionally in Clarke zone A, and
/// at exactly zero DTS risk, which is zone A there too — around half of them at a calibrated
/// `band_cov50`. A scatter of that series would be a picture of band coverage wearing the look of a
/// flawless forecast, so no consumer may draw one; the band's grid performance is read off its
/// SHARES above, where it cannot mislead that way. A series nothing may plot is not worth building,
/// and is certainly not worth marshalling across a foreign-function boundary once per horizon.
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

/// The Trend Accuracy Matrix of one horizon — Klonoff et al. 2024, the same paper as the DTS grid.
///
/// `counts` is the `5 × 5` contingency table over [`TREND_BIN_EDGES`]' rate bins, TRUTH-MAJOR:
/// cell `(t, p)` is truth bin `t` against forecast bin `p`, at index `t * 5 + p`. The layout
/// matches [`clarke_zone_grid`]'s and is load-bearing for the same reason — a transposed table is
/// well-formed and describes the opposite failure.
///
/// `category_n` / `category_pct` are the paper's five risk categories, 1 through 5 at indices 0
/// through 4: no risk, underestimate, overestimate, extreme underestimate, extreme overestimate.
/// Overestimates outrank underestimates at equal bin distance because an overestimated rate is what
/// drives an over-dose. The category of a cell depends on the point's own TRUE BG — see
/// [`trend_category_table`] — so the three stratified tables of the paper are applied per point,
/// never one table to the whole horizon.
///
/// **`category_pct[0]` is also the diagonal share.** Category 1 occupies exactly the five diagonal
/// cells in all three stratified tables, so exact bin agreement and "no risk" are one number rather
/// than two names for it, and this record deliberately carries it once.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TrendMatrix {
    pub counts: Vec<u32>,
    pub category_n: Vec<u32>,
    pub category_pct: Vec<f64>,
    pub n: u32,
}

/// Everything scored at one horizon.
///
/// `band` is the HEADLINE — the band projection of §6.2 — and `median_line` the same block
/// on the median. Because a wider band can only lower the error, the band figures are
/// meaningless without the two numbers that expose a band widened until it swallows
/// everything: `band_cov50` (target `METRIC_BAND_TAU_HI − METRIC_BAND_TAU_LO`) with its
/// mean edge-to-edge `band_width50`, and the outer `band_cov90` / `band_width90`.
/// Persistence carries no band, so the one baseline is shared by both bases.
///
/// `trend` is carried on the MEDIAN LINE alone, and has no band counterpart at all. §6.2's own
/// consequence clause is why: where the truth lies inside the band the projection EQUALS the truth,
/// so a rate differenced from the projected series inherits the truth's own derivative there. A
/// band-basis trend matrix would therefore sit on its diagonal by construction wherever the band
/// covered, which is a picture of coverage rather than of trend agreement. The median line is the
/// one basis whose rate is the model's own.
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
/// zone algebra of `metrics.py::_clarke`.
///
/// **The five flags are a partition: exactly one is ever set.** `c`, `d` and `b` are written
/// `!a && !e && …` and `b` is the complement of `c ∪ d` inside that guard, so at most one of
/// the three can fire and one always does; what remains is that `a` and `e` are themselves
/// exclusive, which the inequalities give: `pb ≤ 70 ∧ tb ≥ 180` forces
/// `rel = (tb − pb)/tb ≥ 1 − 70/180 > 0.20` and denies `pb ≤ 70 ∧ tb ≤ 70`, while
/// `pb ≥ 180 ∧ tb ≤ 70` forces `rel ≥ 110/70 > 0.20` and denies `pb ≤ 70`. Either E clause
/// therefore excludes both A clauses. [`clarke_zone`] collapses the tuple on that basis and
/// [`clarke_zones_are_a_partition`] holds it over a dense lattice.
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

/// The single zone of a `(pred, true)` pair — [`clarke_zones`]'s partition, collapsed.
///
/// Deliberately not a second reading of the inequalities: it calls the one classifier and
/// picks the flag that is set. Nothing outside this file may restate the boundaries.
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

/// Cell ceiling on [`clarke_zone_grid`] — a lattice this large already resolves the grid far
/// past any display, and the bound is what keeps a caller's arithmetic slip from asking for
/// an allocation the device cannot serve.
const CLARKE_GRID_MAX_CELLS: usize = 4_000_000;

/// Classify a caller-chosen lattice of `(truth, pred)` mg/dL coordinates, row-major and
/// TRUTH-MAJOR: cell `(i, j)` is `truth_axis[i]` against `pred_axis[j]`, at index
/// `i * pred_axis.len() + j`.
///
/// This exists so a figure can DRAW the Clarke regions without transcribing a single
/// inequality. The zone boundaries live only inside [`clarke_zones`]; a renderer samples this
/// lattice and paints the cells it gets back, so an outline it draws is the classifier's own
/// output rasterised rather than a second copy of the algebra free to drift from it. The
/// resolution is the caller's to choose, and it is the only thing that separates the painted
/// edge from the true one.
///
/// Total: an empty axis yields an empty lattice. A non-finite coordinate or a lattice past
/// [`CLARKE_GRID_MAX_CELLS`] is an `Err` — a classification of NaN is a well-formed answer to
/// a question nobody asked, and painting it would look exactly like a real region.
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

// ═══════════════════════════════════════════════════════════════════════════════════════
// The DTS Error Grid — Klonoff et al. 2024, J Diabetes Sci Technol 18(6):1346–1361.
// ═══════════════════════════════════════════════════════════════════════════════════════
//
// The grid an 89-expert Diabetes Technology Society panel published to replace the 2014
// Surveillance Error Grid, straightening that grid's serrated expert-averaged borders into a single
// closed form and applying one standard to both blood-glucose meters and non-adjunctive CGM.
//
// **Why this one and not the SEG.** The Surveillance Error Grid has no closed form and no published
// table: it is an empirical surface averaged over 412 expert responses across the whole 20–600 mg/dL
// square, distributed only as a spreadsheet. Naming it in a specification would commit this suite to
// a surface nobody here can reproduce or check. The 2024 grid is three lines of arithmetic and every
// published border vertex falls out of them, so it is the one that can be implemented honestly.

/// Risk coefficient when the forecast reads ABOVE the truth.
const DTS_RISK_COEF_OVER: f64 = 2.75;
/// …and when it reads at or below it.
///
/// **The asymmetry IS the grid.** Reading high is penalised harder than reading low by the same
/// ratio, because a high reading is what drives an over-dose. Folding the two into one coefficient
/// would produce a symmetric grid that is not this one and would under-report exactly the direction
/// the panel weighted.
const DTS_RISK_COEF_UNDER: f64 = 2.25;

/// Both axes are floored here before the ratio is taken (mg/dL).
///
/// The paper prints the clamp as a single clause zeroing the risk when BOTH values fall below 50,
/// which does not reproduce its own border table: Table A1's borders run FLAT at monitor 60 / 86.5 /
/// 124 / 179 for every reference at or under 50, and VERTICAL at reference 62.5 / 97.5 / 153 / 238
/// for every monitor at or under 50. Flooring each axis independently is what produces those two
/// families, and it subsumes the printed clause exactly — floor both and the ratio is 1, so the risk
/// is 0 without a special case. Every one of the 24 published vertices then lands within 0.017 risk
/// units of its own zone threshold, which is the paper's rounding of the coordinates and not a
/// disagreement about the function.
const DTS_CLAMP_MGDL: f64 = 50.0;

/// `|risk|` ceilings of zones A, B, C and D; anything above the last is E. Each bound is CLOSED —
/// a pair landing exactly on 0.5 is zone A.
const DTS_ZONE_CEILINGS: [f64; 4] = [0.5, 1.5, 2.5, 3.5];

/// The DTS risk of one `(pred, truth)` pair — the grid's primitive, of which the zone is a banding.
///
/// ```text
/// risk = 2.75 · ln(M / R)   where M > R          M := max(pred,  50)
///        2.25 · ln(M / R)   where M ≤ R          R := max(truth, 50)
/// ```
///
/// Positive where the forecast read HIGH. Total on anything finite: the floor makes a zero or
/// negative input a ratio of one rather than a logarithm of zero, so no input in mg/dL can produce
/// an infinity here.
///
/// **This is not `SPEC/invariants.md` §4's risk space.** The two are both logarithmic in BG and are
/// otherwise unrelated: §4's is Kovatchev's `1.509·(ln(BG)^1.084 − 5.381)` on a single value, this
/// is a log-RATIO of two. The 2.75 / 2.25 coefficients are this grid's own and are not risk-space
/// constants; nothing may convert between the two scales or reuse one's constants in the other.
///
/// **Nor is zone A "within 20 %".** The A boundary is `e^(0.5/2.75) = +19.940 %` above and
/// `e^(−0.5/2.25) = −19.926 %` below. A forecast at exactly 1.20× the truth scores 0.50138 and is
/// zone **B**. A reader checking this against a ±20 % intuition will find single points disagreeing,
/// and the intuition is what is wrong.
#[inline]
fn dts_risk(pred: f64, truth: f64) -> f64 {
    let m = pred.max(DTS_CLAMP_MGDL);
    let r = truth.max(DTS_CLAMP_MGDL);
    let l = (m / r).ln();
    if m > r { DTS_RISK_COEF_OVER * l } else { DTS_RISK_COEF_UNDER * l }
}

/// Band `|risk|` into a zone. A non-finite argument cannot reach here — every caller classifies a
/// pair that already passed the suite's finiteness gate, and [`dts_zone_grid`] rejects its axes.
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

/// The DTS zone of one `(pred, truth)` pair. The one classifier; nothing outside this file may
/// restate the coefficients or the ceilings.
#[inline]
fn dts_zone(pred: f64, truth: f64) -> DtsZone {
    dts_zone_of_abs_risk(dts_risk(pred, truth).abs())
}

/// Classify a caller-chosen lattice of `(truth, pred)` mg/dL coordinates into DTS zones, row-major
/// and TRUTH-MAJOR — cell `(i, j)` is `truth_axis[i]` against `pred_axis[j]`, at index
/// `i * pred_axis.len() + j`, exactly as [`clarke_zone_grid`] lays its own out.
///
/// It exists for the same reason that one does: so a figure can DRAW the five regions without
/// transcribing a boundary. Here the case is stronger still — the DTS borders are level sets of a
/// logarithm, so a renderer that wanted to outline them would have to reimplement the function
/// rather than merely copy four inequalities. It samples this instead and paints what comes back.
///
/// Total: an empty axis yields an empty lattice; a non-finite coordinate or a lattice past
/// [`CLARKE_GRID_MAX_CELLS`] is an `Err`, because a classification of NaN is a well-formed answer to
/// a question nobody asked and painting it would look exactly like a real region.
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
        // `ln` of the truth is constant down a row; the ratio is not, so this only hoists the
        // clamp. The saving is small and the point is that the row loop stays the classifier's.
        for &p in &pred_axis {
            out.push(dts_zone(p, t));
        }
    }
    Ok(out)
}

// ═══════════════════════════════════════════════════════════════════════════════════════
// The Trend Accuracy Matrix — the same paper, Table 2 and Figures 3–5.
// ═══════════════════════════════════════════════════════════════════════════════════════

/// Number of trend bins.
pub(crate) const TREND_BINS: usize = 5;

/// The four interior edges of the five rate bins, in mg/dL per minute (paper Table 2).
///
/// The bins are `< −2`, `[−2, −1)`, `[−1, +1]`, `(+1, +2]`, `> +2`; exhaustive and disjoint.
/// **Each edge is closed on its OUTER side**: exactly −2 falls in the −1 bin, exactly +2 in the +1
/// bin, and exactly ±1 in the flat bin. That reads asymmetric written out and is what the paper
/// specifies — the flat bin is the closed interval and its neighbours are half-open away from it.
///
/// Stated in mg/dL only. The paper's mmol/L equivalents (0.056, 0.111) are rounded at 18.0 rather
/// than §3's 18.0182, so converting this grid into mmol/L and back would move a bin edge by a part
/// in ten thousand for no gain — the storage unit is mg/dL and the bins stay there.
pub(crate) const TREND_BIN_EDGES: [f64; 4] = [-2.0, -1.0, 1.0, 2.0];

/// Lookback for the trend rate, in five-minute steps of `SPEC/invariants.md` §1 — 15 minutes.
///
/// The paper differences its reference against a value 15 to 45 minutes earlier. This takes the
/// SHORTEST window in that band, for two reasons: it lands on §1's grid exactly (three steps), and
/// it is the only choice available at every horizon the suite reports without reaching back before
/// the forecast begins.
///
/// **It is deliberately NOT §6.3's single-step rate.** CG-EGA differences one five-minute step; this
/// differences three. The bin edges at ±1 and ±2 mg/dL/min happen to coincide with the breakpoints
/// of CG-EGA's `mod` widening, and that coincidence is a trap: the same thresholds over a different
/// differencing window are a different statistic. A five-minute difference is three times noisier
/// and would scatter points out of the flat bin that the paper's window holds inside it.
pub(crate) const TREND_LOOKBACK_STEPS: usize = 3;

/// Number of risk categories.
pub(crate) const TREND_CATEGORIES: usize = 5;

/// The reference-BG cuts that select which category table a point is scored on (mg/dL).
///
/// **These are the paper's own cuts and are NOT §6.3's glycaemic regions.** CG-EGA splits at 70 and
/// 180; this splits at 100 and 180. Reusing either set for the other would be a quiet re-definition
/// of one of the two statistics, so the two live apart on purpose.
const TREND_REGION_CUTS: [f64; 2] = [100.0, 180.0];

/// The five risk-category tables, TRUTH-MAJOR: `table[true_bin][pred_bin]`, holding the category
/// 1–5. Transposed once, here, from the paper's figures — which are printed monitor-major, rows
/// descending — so that every consumer downstream reads one layout and the transpose exists in
/// exactly one place.
///
/// The three tables are selected by the point's own TRUE BG through [`TREND_REGION_CUTS`]:
///
/// * **below 100 mg/dL** — category 5 widens from two cells to six, and there it IS a clean rule:
///   every cell where the forecast's bin sits two or more bins above the truth's. Hypoglycaemia is
///   where an overestimated rate does the most harm, and this is the table that says so.
/// * **100 to 180 mg/dL** — the paper's main table, which is also the one it publishes unstratified
///   over the whole 1–600 mg/dL range.
/// * **above 180 mg/dL** — only four categories; 5 folds into 4, the two cells differing from the
///   main table. Relabelled "extreme underestimate / overestimate" in the paper, which is a naming
///   change and not a fifth category with no members.
///
/// Categories 4 and 5 of the main table are the FDA iCGM special controls (DEN170088) verbatim:
/// category 5 is a forecast rate above +1 mg/dL/min while the truth falls faster than −2, category 4
/// a forecast rate below −1 while the truth rises faster than +2. Both are capped at 1 % of pairs
/// there, a limit this suite reports against but does not enforce.
///
/// **The paper's prose and its figures disagree, and the figures win.** The text describes 4 and 5
/// as spanning "3 or 4 bins", but the main table scores `(pred +2, truth [−2,−1))` — a three-bin
/// overestimate — as category 3. The bin-distance rule holds only for the sub-100 table; the main
/// table's category 5 is the FDA's two-cell rule. Transcribed from the figures accordingly, and the
/// per-category tallies the paper publishes reproduce exactly from them.
/// A `static` rather than a `const` deliberately: a `const` is inlined at every use site, so the
/// 75 bytes would be duplicated per reference and `trend_category_table` could not hand back a
/// reference to *the* table. One instance, one address, one place to audit.
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

/// The interior bin edges in mg/dL per minute, ascending — [`TREND_BIN_EDGES`], exported.
///
/// It crosses the seam for the reason [`clarke_zone_grid`] does: a figure has to LABEL the five bins,
/// and a label is the edge written out. Reading them back from the crate means an axis caption
/// cannot come to disagree with the binning it captions. `TREND_BINS` is always one more than this.
#[uniffi::export]
pub fn trend_bin_edges() -> Vec<f64> {
    TREND_BIN_EDGES.to_vec()
}

/// Bin one rate of change (mg/dL/min) into `0..TREND_BINS`, ascending. See [`TREND_BIN_EDGES`] for
/// why the edges close outward. A non-finite rate has no bin and yields `None` rather than falling
/// through every comparison into the top one, which is what a bare comparison chain would do.
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

/// The category table a point of this true BG is scored on.
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

/// The value of a scored series at step `i`, where `-1` is the persistence anchor (§6.3's
/// convention: the measured BG at the forecast's `made_at` is the step before the first). Anything
/// earlier than the anchor never existed and yields `None`.
#[inline]
fn step_or_anchor(series: &[f64], anchor: f64, i: isize) -> Option<f64> {
    if i == -1 {
        Some(anchor)
    } else {
        usize::try_from(i).ok().and_then(|u| series.get(u).copied())
    }
}

/// Reduce one horizon's windows to the Trend Accuracy Matrix.
///
/// The rate at horizon step `k` is `(y[k] − y[k−3]) / 15` on both trajectories — the truth's and the
/// median line's — with `y[−1]` the persistence anchor. A window whose lookback reaches before the
/// anchor contributes nothing; at the horizons this app reports (`k` = 5, 11, 23) none can, and the
/// guard is there so a shorter horizon cannot silently score a different alignment.
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
        // The category reads the TRUTH's BG, like every other region split in this suite: a point
        // belongs to the glycaemia it was actually in, never to the one the forecast imagined.
        let cat = trend_category_table(s.truth[k])[tb][pb];
        category_n[usize::from(cat) - 1] += 1;
        n += 1;
    }
    let category_pct = if n > 0 {
        category_n.iter().map(|&c| 100.0 * f64::from(c) / f64::from(n)).collect()
    } else {
        // No scored pair is not a matrix of zero percentages — it is no matrix. An empty vector
        // cannot be drawn as a partition of nothing, which a five-zero one invites.
        Vec::new()
    };
    TrendMatrix { counts, category_n, category_pct, n }
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
///
/// `keep_points` decides whether the per-pair series is RETAINED, never whether it is
/// derived: every pair is classified either way and the four shares are counted off those
/// very enums, so the two bases' percentages are produced by identical arithmetic. See
/// [`PointBlock`] for why only the median line keeps its series.
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
        // ONE classification per pair on EACH grid, feeding both the published shares and the
        // series a figure scatters. They cannot disagree because there is nothing to disagree
        // with: every percentage below is counted off these very enums, in this one pass.
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
            // A persistence baseline that was itself perfect leaves the ratio undefined.
            // Reporting 0 (or −inf) would read as a real skill figure; `None` cannot.
            None
        },
        points,
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

    /// The per-point zone series must re-aggregate to the four percentages just checked
    /// against `metrics.py`. That is what pins the SERIES to the reference: the reference
    /// publishes no per-point zones, but it does publish those four shares to 1e-9, and a
    /// series that reproduces all four (over the same `n`, in the same window order) is
    /// pinned through them. It also pins zone C, which nothing publishes at all.
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
        // Five disjoint counts that exhaust `n` — a figure that partitions a bar by these
        // shares is drawing a whole, not four slices and a gap.
        let counted: usize = [ClarkeZone::A, ClarkeZone::B, ClarkeZone::C, ClarkeZone::D, ClarkeZone::E]
            .iter()
            .map(|z| pts.iter().filter(|p| p.clarke == *z).count())
            .sum();
        assert_eq!(counted, n, "{what}: zones do not exhaust the window");
        // Each point must still classify to its own recorded zone — the pair travels with
        // its verdict, and a scatter drawn at (truth, pred) must land in the region the
        // lattice paints there. Both grids, off the ONE series they share.
        for p in pts {
            assert_eq!(clarke_zone(p.pred, p.truth), p.clarke, "{what}: clarke point disagrees with itself");
            assert_eq!(dts_zone(p.pred, p.truth), p.dts, "{what}: dts point disagrees with itself");
            close(dts_risk(p.pred, p.truth), p.dts_risk, &format!("{what}: carried risk"));
        }
    }

    /// The five DTS shares must be a partition of the same window, and the mean `|risk|` must be the
    /// mean of the carried risks. Unlike Clarke's, all five are published, so this needs no
    /// remainder — which is exactly why the paper's refusal to combine A and B is implementable.
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
            // The band basis carries no series, and that is a contract rather than an
            // accident: nothing may scatter it, so a non-empty one here is a regression
            // towards the misleading figure `PointBlock`'s note forbids.
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
                // One point per scored window on the basis that keeps them — a scatter's `n`
                // is the table's — and none at all on the band, which nothing may scatter.
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

                // §6.2: a degenerate fan's projection IS the median line, so the two bases
                // must come back identical — not merely close. Every measured quantity,
                // that is: the series is the one asymmetry, carried by the median line and
                // withheld from the band by contract rather than by arithmetic.
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

    // ── The Clarke zone partition and the lattice a figure paints from it ──────────────

    /// [`clarke_zone`] collapses five flags on the promise that exactly one is ever set.
    /// The proof is in `clarke_zones`' doc comment; this holds it over a dense lattice —
    /// including the clamped corner, where `pb`/`tb` are floored at 1 mg/dL.
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
        // All five reachable inside 0–400 mg/dL: a figure that samples that square paints
        // five regions, not four and an absence.
        assert!(seen.iter().all(|&s| s), "a zone is unreachable on 0–400 mg/dL: {seen:?}");
    }

    /// The lattice a renderer paints must be the classifier's own verdicts, in the declared
    /// TRUTH-MAJOR order. Deliberately unequal axis lengths: a transposed index survives a
    /// square lattice silently, and a transposed Clarke grid is a well-formed picture of a
    /// different statistic (the same defect that corrupted the reference's CG-EGA).
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

        // Total on the edges, loud on what it cannot answer.
        assert!(clarke_zone_grid(vec![], vec![1.0]).unwrap().is_empty());
        assert!(clarke_zone_grid(vec![f64::NAN], vec![100.0]).is_err());
        assert!(clarke_zone_grid(vec![0.0; 2048], vec![0.0; 2048]).is_err());
    }

    /// Pairs sitting ON each boundary of `metrics.py::_clarke`, and their neighbours just
    /// across it. A `<=` flipped to `<`, a constant nudged, or a clause dropped fails here
    /// by name rather than as a percentage that moved.
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


    // ── The DTS Error Grid (Klonoff 2024) ──────────────────────────────────────────────

    /// Table A1's border polylines, held against the closed form of Appendix 2.
    ///
    /// This is THE gate on the grid: the paper publishes the borders as rounded `(reference,
    /// monitor)` coordinates and the function as an equation, and says the equation is what was
    /// fitted. Every vertex must therefore land on its own zone threshold to within the paper's
    /// coordinate rounding — measured in RISK units, which is the scale-free way to say it, because
    /// the same rounding is worth 0.05 mg/dL at the bottom of the grid and 3.7 at the top.
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
        // The largest deviation any published vertex shows, in risk units: 0.0171 at the D|E upper
        // border's 167/600 corner. A coefficient mistyped in the third digit moves this by an order
        // of magnitude, so the bound is tight enough to be a gate rather than a formality.
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

    /// Zone A is `+19.94 % / −19.93 %`, not `±20 %`. The pair that separates the two readings is
    /// pinned here by name, because a reader checking this grid against a 20 % intuition will find
    /// exactly this point and conclude the implementation is off by one.
    #[test]
    fn dts_zone_a_is_not_twenty_percent() {
        assert_eq!(dts_zone(120.0, 100.0), DtsZone::B, "1.20x the truth is B, not A");
        assert_eq!(dts_zone(119.9, 100.0), DtsZone::A);
        // …and the grid is asymmetric, so the low side reaches further before it leaves A.
        assert_eq!(dts_zone(80.1, 100.0), DtsZone::A);
        assert_eq!(dts_zone(80.0, 100.0), DtsZone::B, "0.80x the truth is B on the 2.25 arm");
        // The asymmetry itself: reading high scores worse than reading low by the same ratio.
        assert!(dts_risk(125.0, 100.0).abs() > dts_risk(80.0, 100.0).abs());
    }

    /// Every zone ceiling is CLOSED, and the ladder is monotone in `|risk|`.
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

    /// The 50 mg/dL floor is what makes the grid total at the bottom of the scale — and what
    /// reproduces Table A1's flat and vertical border families. Below it the grid stops
    /// discriminating, which is the paper's intent and not a degeneracy.
    #[test]
    fn dts_clamp_makes_the_bottom_of_the_grid_total() {
        // Both under the floor ⇒ risk 0 exactly, which is the printed clause, arrived at without a
        // special case for it.
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

    /// All five zones are reachable inside the extent a figure draws, and the classifier is total
    /// over it — a figure sampling that square paints five regions, not four and an absence.
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

    /// The lattice a renderer paints must be the classifier's own verdicts in the declared
    /// TRUTH-MAJOR order. Unequal axis lengths deliberately: a transposed index survives a square
    /// lattice silently, and this grid is asymmetric, so a transpose is a well-formed picture that
    /// swaps every overestimate for an underestimate.
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
        // A transpose must be VISIBLE — otherwise this test proves nothing about the order.
        let flipped = dts_zone_grid(pred, truth).unwrap();
        assert_ne!(grid, flipped, "the grid must not be symmetric under transposition");

        assert!(dts_zone_grid(vec![], vec![1.0]).unwrap().is_empty());
        assert!(dts_zone_grid(vec![f64::NAN], vec![100.0]).is_err());
        assert!(dts_zone_grid(vec![0.0; 2048], vec![0.0; 2048]).is_err());
    }

    // ── The Trend Accuracy Matrix ──────────────────────────────────────────────────────

    /// Table 2's bins, with every edge held on the side the paper closes it.
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
        // A rate with no bin is None, never the top one a bare comparison chain would fall to.
        assert_eq!(trend_bin(f64::NAN), None);
        assert_eq!(trend_bin(f64::INFINITY), None);
    }

    /// Every structural rule the paper states about the three tables, checked against the
    /// transcription. These are what stand in for a reference implementation nobody publishes.
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

        // 2. The diagonal is category 1 and NOTHING else is — which is what lets the record carry
        //    "exact bin agreement" and "no risk" as one number.
        for t in &TREND_CATEGORY_TABLES {
            for tb in 0..TREND_BINS {
                for pb in 0..TREND_BINS {
                    assert_eq!(t[tb][pb] == 1, tb == pb, "category 1 off the diagonal at ({tb}, {pb})");
                }
            }
        }

        // 3. The main table's categories 4 and 5 are the FDA iCGM cells verbatim. Category 5: the
        //    forecast rises faster than +1 while the truth falls faster than −2. Category 4: the
        //    forecast falls faster than −1 while the truth rises faster than +2.
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

        // 6. An overestimated rate is never scored more leniently than the underestimate that
        //    mirrors it — the asymmetry the paper's own ordering asserts.
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

    /// The region cuts are the paper's, and must not have drifted onto §6.3's.
    #[test]
    fn trend_region_cuts_are_the_papers_own() {
        assert_eq!(TREND_REGION_CUTS, [100.0, 180.0]);
        assert!(std::ptr::eq(trend_category_table(99.9), &TREND_CATEGORY_TABLES[0]));
        assert!(std::ptr::eq(trend_category_table(100.0), &TREND_CATEGORY_TABLES[1]));
        assert!(std::ptr::eq(trend_category_table(180.0), &TREND_CATEGORY_TABLES[1]));
        assert!(std::ptr::eq(trend_category_table(180.1), &TREND_CATEGORY_TABLES[2]));
        // 70 is CG-EGA's hypo cut and must NOT select the sub-100 table's neighbour by accident.
        assert!(std::ptr::eq(trend_category_table(70.0), &TREND_CATEGORY_TABLES[0]));
    }

    /// The matrix is TRUTH-MAJOR and its lookback anchors on the persistence value (§6.3), so a
    /// horizon whose window reaches exactly one step before its first still scores.
    #[test]
    fn trend_matrix_is_truth_major_and_anchors_on_persistence() {
        // Three steps, horizon 15 min ⇒ k = 2 and the 3-step lookback lands exactly on the anchor.
        // Truth climbs 100 → 145 over those 15 minutes (+3.0 mg/dL/min, the top bin); the median
        // line stays flat (bin 2). One cell, off the diagonal, and it must be the TRUTH's row.
        let w = window(&[100.0, 100.0, 100.0], &[100.0, 100.0, 145.0], 100.0, 4.0);
        let s = forecast_metrics_suite(vec![w], vec![15], cfg(), false).unwrap();
        let m = &s.horizons[0].trend;
        assert_eq!(m.n, 1);
        assert_eq!(m.counts[4 * TREND_BINS + 2], 1, "truth bin 4 against forecast bin 2");
        assert_eq!(m.counts.iter().sum::<u32>(), 1, "exactly one cell filled");
        // truth[k] = 145 ⇒ the main table ⇒ (4, 2) is category 2, an UNDERestimated rate.
        assert_eq!(m.category_n, vec![0, 1, 0, 0, 0]);
        close(m.category_pct[1], 100.0, "category 2 share");
    }

    /// A forecast that is exactly right is zone A everywhere on both grids, on the diagonal of the
    /// trend matrix, and wholly category 1 — the sanity floor for all three at once.
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
            // On the diagonal, whichever bin the pair landed in.
            let diag: u32 = (0..TREND_BINS).map(|i| h.trend.counts[i * TREND_BINS + i]).sum();
            assert_eq!(diag, 1, "@{tag}: a perfect rate must sit on the diagonal");
        }
    }

    /// An empty horizon yields no matrix rather than a partition of nothing — the same distinction
    /// `CgEga`'s `Option` draws, made here with an empty share vector.
    #[test]
    fn trend_matrix_of_no_scored_pair_has_no_shares() {
        // A lookback that reaches before the anchor: horizon 5 min ⇒ k = 0, back = −3.
        let w = window(&[100.0, 105.0, 110.0], &[102.0, 107.0, 112.0], 98.0, 4.0);
        let s = forecast_metrics_suite(vec![w], vec![5], cfg(), false).unwrap();
        let m = &s.horizons[0].trend;
        assert_eq!(m.n, 0);
        assert!(m.category_pct.is_empty(), "no pair scored ⇒ no shares to draw");
        assert_eq!(m.counts.iter().sum::<u32>(), 0);
        // The counts vector is still the right SHAPE — a figure may lay out an empty grid.
        assert_eq!(m.counts.len(), TREND_BINS * TREND_BINS);
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
            // Every measured quantity identical; the per-pair series is the one asymmetry,
            // withheld from the band by contract rather than produced differently.
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
