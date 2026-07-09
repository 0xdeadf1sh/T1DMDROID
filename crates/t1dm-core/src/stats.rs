//! Advanced glycemic statistics (Phase 6, PLAN.private.md §"Phase 6 — Stats").
//!
//! A pure, golden-gated engine that reduces a time-ordered sample series to the
//! `AdvancedStats` block the phone renders against the user's *configurable* target
//! range `[target_low, target_high]` mg/dL. The server (`T1DMSERVER`) already ships the
//! range-independent shared block (GMI/CV/SD/mean_bg/TDD/…); this reproduces those
//! exactly (same formulas) so a local recompute parity-checks the server cache, and adds
//! the range-dependent + richer metrics the server does not carry: time-weighted
//! TIR/TBR/TAR, the clinical sub-bands, LBGI/HBGI, MAGE, and the AGP percentile ribbon.
//!
//! Everything here is total: an empty or degenerate series yields a well-defined empty
//! `AdvancedStats` (`n_samples == 0`), never a panic or a NaN. Every `#[uniffi::export]`
//! returns `Result`; release builds are `panic = "abort"`.
//!
//! Formula provenance:
//!   - LBGI/HBGI: Kovatchev et al., "Symmetrization of the Blood Glucose Measurement
//!     Scale and Its Applications", Diabetes Care 20(11):1655-1658, 1997; and Kovatchev
//!     et al., "Risk analysis of blood glucose data", J Diabetes Sci Technol, 2009.
//!     r(bg) = 10·f(bg)²; rl = r when f(bg)<0 else 0; rh = r when f(bg)>0 else 0;
//!     LBGI = mean(rl), HBGI = mean(rh). `f` is the crate's `kovatchev_f` (INFERENCE.md §5).
//!   - GMI(%) = 3.31 + 0.02392·mean_bg(mg/dL): Bergenstal et al., "Glucose Management
//!     Indicator (GMI)", Diabetes Care 41(11):2275-2280, 2018. (Matches the server.)
//!   - MAGE: Service et al., "Mean amplitude of glycemic excursions, a measure of
//!     diabetic instability", Diabetes 19(9):644-655, 1970 — excursions exceeding 1 SD,
//!     averaged. Exact variant pinned below + by stats_golden.json.

use crate::{kovatchev_f, CoreError};

/// Gaps beyond this (sensor dropout) are clamped so a stale reading cannot inflate a
/// band's time-weight. Six missed 5-min CGM buckets.
const MAX_GAP_MS: i64 = 30 * 60_000;
/// Minutes in a day — the AGP bin grid must divide it evenly.
const DAY_MIN: u32 = 1440;
const DAY_MS: f64 = 86_400_000.0;
/// Clinical AGP fixed sub-band edges (mg/dL); the target edges are user-configurable.
const VERY_LOW: f64 = 54.0;
const VERY_HIGH: f64 = 250.0;
/// mg/dL → mmol/L (the SI molar-mass conversion for glucose, 18.0182 mg/dL per mmol/L).
const MMOL_PER_MGDL: f64 = 1.0 / 18.0182;
/// Schlichtkrull M-value ideal reference glucose (mg/dL).
const M_REF: f64 = 120.0;
/// GRADE region thresholds (mmol/L), Hill 2007: hypo < 3.9, hyper > 7.8.
const GRADE_HYPO_MMOL: f64 = 3.9;
const GRADE_HYPER_MMOL: f64 = 7.8;
/// Glucose distribution histogram: 20 mg/dL bins over [40, 400) → 18 bins (tails clamp in).
const HIST_LO: f64 = 40.0;
const HIST_HI: f64 = 400.0;
const HIST_BIN: f64 = 20.0;
/// An excursion episode needs at least this many consecutive in-band samples.
const EPISODE_MIN_SAMPLES: usize = 2;

/// One input sample on the shared 5-min grid. `bg_mgdl` is required; the treatment /
/// activity channels are `None` where the grid cell carries no such event.
/// uniffi record → Kotlin `StatSample`.
#[derive(Debug, Clone, uniffi::Record)]
pub struct StatSample {
    pub ts_ms: i64,
    pub bg_mgdl: f64,
    pub carbs_g: Option<f64>,
    pub bolus_u: Option<f64>,
    pub basal_u: Option<f64>,
    pub steps: Option<i64>,
    pub mood: Option<i32>,
}

/// Time-weighted fraction of the record spent in each clinical band (sums to 1 over a
/// non-empty series). `in_range` uses the *configurable* target edges; `very_low`/
/// `very_high` are the fixed 54/250 clinical cuts.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SubBands {
    pub very_low: f64,
    pub low: f64,
    pub in_range: f64,
    pub high: f64,
    pub very_high: f64,
}

/// Per-time-of-day-bin BG percentiles for the AGP ribbon. `minute_of_day` is the bin's
/// start (0..1440). Only populated bins are emitted; the chart interpolates gaps.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct AgpBin {
    pub minute_of_day: u32,
    pub p5: f64,
    pub p25: f64,
    pub p50: f64,
    pub p75: f64,
    pub p95: f64,
}

/// Optional mood summary over samples that carry a mood score.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct MoodSummary {
    pub mean: f64,
    pub n: u32,
    pub min: i32,
    pub max: i32,
}

/// Sample-count TIR/TBR/TAR within a fixed 6-hour time-of-day bucket (start 0/360/720/1080
/// minutes). A count-based fraction (not time-weighted) over the bucket's valid-BG samples —
/// the diurnal breakdown the single overall time-weighted TIR cannot express. All four
/// buckets are always emitted (an empty bucket carries `n == 0` and zero fractions).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TodBucket {
    pub start_min: u32,
    pub n: u32,
    pub tir: f64,
    pub tbr: f64,
    pub tar: f64,
}

/// One fixed-width bin of the glucose distribution histogram: 20 mg/dL bins spanning
/// `[40, 400)` (18 bins); readings below/above clamp into the end bins. `frac = count / n`.
/// Every bin is emitted (including empties) so the chart draws a full distribution.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct HistBin {
    pub lo: f64,
    pub hi: f64,
    pub count: u32,
    pub frac: f64,
}

/// Aggregate of the hypo (resp. hyper) excursion EPISODES: a maximal run of ≥2 consecutive
/// valid samples strictly below `target_low` (resp. above `target_high`), split by a sensor
/// dropout gap (> `MAX_GAP_MS`). `mean_extreme` is the mean per-episode nadir (hypo) / peak
/// (hyper); `worst_extreme` the single most extreme reading across all episodes.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct EpisodeSummary {
    pub count: u32,
    pub total_duration_ms: i64,
    pub mean_duration_ms: f64,
    pub mean_extreme: f64,
    pub worst_extreme: f64,
}

impl EpisodeSummary {
    fn empty() -> Self {
        EpisodeSummary {
            count: 0,
            total_duration_ms: 0,
            mean_duration_ms: 0.0,
            mean_extreme: 0.0,
            worst_extreme: 0.0,
        }
    }
}

/// GRADE (Hill et al. 2007) mean risk score plus its hypo/eu/hyper attribution — the
/// fraction of the summed score contributed by readings in each region (thresholds 3.9 /
/// 7.8 mmol/L). The three fractions sum to 1 whenever the score is positive.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct GradeSplit {
    pub grade: f64,
    pub hypo: f64,
    pub eu: f64,
    pub hyper: f64,
}

/// The full advanced-stats block. Range-dependent + richer metrics on top of the
/// server's shared block; the shared fields (`mean_bg`/`sd`/`cv`/`gmi` and the raw
/// treatment totals) reproduce the server bit-for-bit for the parity check. Daily rates
/// use the observed span (`span_ms`); callers wanting the server's fixed-window
/// denominator can re-derive them from the exposed `total_*`.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct AdvancedStats {
    /// Count of valid-BG samples (matches the server's `n`).
    pub n_samples: u32,
    /// Observed span first→last sample (ms); the daily-rate denominator.
    pub span_ms: i64,
    /// Time-weighted fractions vs the target range (0..1).
    pub tir: f64,
    pub tbr: f64,
    pub tar: f64,
    /// Finer clinical breakdown (also time-weighted).
    pub sub_bands: SubBands,
    /// Kovatchev low/high blood-glucose indices (mean risk).
    pub lbgi: f64,
    pub hbgi: f64,
    /// Mean amplitude of glycemic excursions (> 1 SD).
    pub mage: f64,
    /// Shared block — reproduces the server exactly.
    pub mean_bg: f64,
    pub sd: f64,
    pub cv: f64,
    pub gmi: f64,
    /// Raw treatment totals over the window (so callers can re-normalize).
    pub total_carbs: f64,
    pub total_bolus: f64,
    pub total_basal: f64,
    /// Per-day rates over `span_ms`.
    pub mean_daily_carbs: f64,
    pub tdd: f64,
    pub bolus_basal_ratio: f64,
    /// Mean of present step counts, or `None` if no sample carried steps.
    pub mean_steps: Option<f64>,
    /// Mood summary, or `None` if no sample carried a mood.
    pub mood: Option<MoodSummary>,
    /// AGP percentile ribbon, one entry per populated time-of-day bin, ascending.
    pub agp: Vec<AgpBin>,
    // ── Variability & risk extensions (Phase 7D) ──────────────────────────────
    /// Mean Of Daily Differences — |ΔBG| at matched minute-of-day across consecutive days.
    pub modd: f64,
    /// CONGA at 1 / 2 / 4 h lag (SD of the n-hour net glycemic action).
    pub conga1: f64,
    pub conga2: f64,
    pub conga4: f64,
    /// J-index = 0.001·(mean + SD)² (mg/dL space).
    pub j_index: f64,
    /// Schlichtkrull M-value (ideal reference 120 mg/dL).
    pub m_value: f64,
    /// Average Daily Risk Range (Kovatchev).
    pub adrr: f64,
    /// Between-day SD of the per-day mean glucose (day-to-day variability).
    pub dtd_sd: f64,
    /// GRADE score + hypo/eu/hyper attribution.
    pub grade: GradeSplit,
    /// Diurnal TIR — four fixed 6-hour time-of-day buckets (always length 4).
    pub tod: Vec<TodBucket>,
    /// Glucose distribution histogram (20 mg/dL bins over [40,400); always length 18).
    pub histogram: Vec<HistBin>,
    /// Hypo (< target_low) excursion-episode aggregate.
    pub hypo_episodes: EpisodeSummary,
    /// Hyper (> target_high) excursion-episode aggregate.
    pub hyper_episodes: EpisodeSummary,
}

impl AdvancedStats {
    /// The well-defined empty block (degenerate/empty input). All fractions/metrics 0,
    /// channels absent — never NaN.
    fn empty() -> Self {
        AdvancedStats {
            n_samples: 0,
            span_ms: 0,
            tir: 0.0,
            tbr: 0.0,
            tar: 0.0,
            sub_bands: SubBands { very_low: 0.0, low: 0.0, in_range: 0.0, high: 0.0, very_high: 0.0 },
            lbgi: 0.0,
            hbgi: 0.0,
            mage: 0.0,
            mean_bg: 0.0,
            sd: 0.0,
            cv: 0.0,
            gmi: 0.0,
            total_carbs: 0.0,
            total_bolus: 0.0,
            total_basal: 0.0,
            mean_daily_carbs: 0.0,
            tdd: 0.0,
            bolus_basal_ratio: 0.0,
            mean_steps: None,
            mood: None,
            agp: Vec::new(),
            modd: 0.0,
            conga1: 0.0,
            conga2: 0.0,
            conga4: 0.0,
            j_index: 0.0,
            m_value: 0.0,
            adrr: 0.0,
            dtd_sd: 0.0,
            grade: GradeSplit { grade: 0.0, hypo: 0.0, eu: 0.0, hyper: 0.0 },
            tod: Vec::new(),
            histogram: Vec::new(),
            hypo_episodes: EpisodeSummary::empty(),
            hyper_episodes: EpisodeSummary::empty(),
        }
    }
}

/// numpy-compatible (type-7, linear) percentile of an already-sorted slice. `p` in
/// `[0,100]`. `sorted` must be non-empty (caller guarantees).
fn percentile_sorted(sorted: &[f64], p: f64) -> f64 {
    let n = sorted.len();
    if n == 1 {
        return sorted[0];
    }
    let rank = p / 100.0 * (n - 1) as f64;
    let lo = rank.floor() as usize;
    let frac = rank - lo as f64;
    if lo + 1 < n {
        sorted[lo] + frac * (sorted[lo + 1] - sorted[lo])
    } else {
        sorted[n - 1]
    }
}

/// MAGE via turning-point extrema, retaining excursions whose amplitude exceeds `sd`,
/// then averaging. Deterministic definition (pinned by stats_golden.json + a sawtooth
/// unit test): reduce the BG series to alternating extrema (endpoints included, flats
/// skipped); a swing between consecutive extrema counts iff its amplitude > `sd`.
fn mage(bg: &[f64], sd: f64) -> f64 {
    if bg.len() < 2 {
        return 0.0;
    }
    let mut ext: Vec<f64> = vec![bg[0]];
    let mut direction: i8 = 0;
    for k in 1..bg.len() {
        let d = bg[k] - bg[k - 1];
        if d == 0.0 {
            continue;
        }
        let s: i8 = if d > 0.0 { 1 } else { -1 };
        if direction != 0 && s != direction {
            ext.push(bg[k - 1]);
        }
        direction = s;
    }
    ext.push(bg[bg.len() - 1]);
    let mut sum = 0.0;
    let mut count = 0u32;
    for w in ext.windows(2) {
        let amp = (w[1] - w[0]).abs();
        if amp > sd {
            sum += amp;
            count += 1;
        }
    }
    if count == 0 {
        0.0
    } else {
        sum / count as f64
    }
}

/// The UTC day index a timestamp falls in (days since the epoch). Used for the day-partitioned
/// variability metrics (MODD / ADRR / day-to-day). A fixed offset, so day boundaries are
/// consistent within the record even if not local-midnight aligned.
#[inline]
fn day_of(ts_ms: i64) -> i64 {
    ts_ms.div_euclid(DAY_MS as i64)
}

/// The minute-of-day (0..1440) a timestamp falls in.
#[inline]
fn minute_of_day(ts_ms: i64) -> i64 {
    ts_ms.rem_euclid(DAY_MS as i64) / 60_000
}

/// MODD — Mean Of Daily Differences (Molnar et al. 1972): the mean |ΔBG| between readings
/// exactly 24 h apart, matched on identical minute-of-day across consecutive days. 0 when no
/// matched pair exists. A `BTreeMap` keeps the summation order deterministic.
fn modd(valid: &[&StatSample]) -> f64 {
    use std::collections::BTreeMap;
    let mut by_key: BTreeMap<(i64, i64), f64> = BTreeMap::new();
    for s in valid {
        by_key.insert((day_of(s.ts_ms), minute_of_day(s.ts_ms)), s.bg_mgdl);
    }
    let mut sum = 0.0;
    let mut count = 0u32;
    for (&(day, m), &bg) in &by_key {
        if let Some(&prev) = by_key.get(&(day - 1, m)) {
            sum += (bg - prev).abs();
            count += 1;
        }
    }
    if count == 0 {
        0.0
    } else {
        sum / count as f64
    }
}

/// CONGA-n — Continuous Overlapping Net Glycemic Action (McDonnell et al. 2005): the
/// population SD of the differences `Dt = bg(t) − bg(t − n·60 min)` over every t whose
/// n-hour-earlier reading is present. 0 with fewer than two such differences.
fn conga(valid: &[&StatSample], hours: i64) -> f64 {
    use std::collections::BTreeMap;
    let mut by_ts: BTreeMap<i64, f64> = BTreeMap::new();
    for s in valid {
        by_ts.insert(s.ts_ms, s.bg_mgdl);
    }
    let lag = hours * 3_600_000;
    let mut diffs: Vec<f64> = Vec::new();
    for (&ts, &bg) in &by_ts {
        if let Some(&prev) = by_ts.get(&(ts - lag)) {
            diffs.push(bg - prev);
        }
    }
    if diffs.len() < 2 {
        return 0.0;
    }
    let m = diffs.iter().sum::<f64>() / diffs.len() as f64;
    let var = diffs.iter().map(|d| (d - m).powi(2)).sum::<f64>() / diffs.len() as f64;
    var.sqrt()
}

/// The per-reading GRADE contribution (Hill et al. 2007): `425·[log10(log10(bg mmol/L)) +
/// 0.16]²`, capped at 50. The mmol value is floored just above 1 so the nested log stays
/// real for any positive BG (physiological BG ≫ 18 mg/dL never hits the floor).
#[inline]
fn grade_contrib(bg_mgdl: f64) -> f64 {
    let mmol = (bg_mgdl * MMOL_PER_MGDL).max(1.000_001);
    (425.0 * (mmol.log10().log10() + 0.16).powi(2)).min(50.0)
}

/// ADRR — Average Daily Risk Range (Kovatchev et al. 2006): the mean over days of
/// `max(low-risk) + max(high-risk)`, where risk `r(bg) = 10·f(bg)²`. 0 with no readings.
fn adrr(valid: &[&StatSample]) -> f64 {
    use std::collections::BTreeMap;
    let mut per_day: BTreeMap<i64, (f64, f64)> = BTreeMap::new();
    for s in valid {
        let f = kovatchev_f(s.bg_mgdl);
        let r = 10.0 * f * f;
        let (lr, hr) = if f < 0.0 { (r, 0.0) } else if f > 0.0 { (0.0, r) } else { (0.0, 0.0) };
        let e = per_day.entry(day_of(s.ts_ms)).or_insert((0.0, 0.0));
        e.0 = e.0.max(lr);
        e.1 = e.1.max(hr);
    }
    if per_day.is_empty() {
        return 0.0;
    }
    let sum: f64 = per_day.values().map(|&(lr, hr)| lr + hr).sum();
    sum / per_day.len() as f64
}

/// Between-day SD of the per-day mean glucose (day-to-day variability). 0 with < 2 days.
fn dtd_sd(valid: &[&StatSample]) -> f64 {
    use std::collections::BTreeMap;
    let mut per_day: BTreeMap<i64, (f64, u32)> = BTreeMap::new();
    for s in valid {
        let e = per_day.entry(day_of(s.ts_ms)).or_insert((0.0, 0));
        e.0 += s.bg_mgdl;
        e.1 += 1;
    }
    let means: Vec<f64> = per_day.values().map(|&(sum, c)| sum / c as f64).collect();
    if means.len() < 2 {
        return 0.0;
    }
    let m = means.iter().sum::<f64>() / means.len() as f64;
    let var = means.iter().map(|v| (v - m).powi(2)).sum::<f64>() / means.len() as f64;
    var.sqrt()
}

/// Four fixed 6-hour diurnal buckets (00-06/06-12/12-18/18-24), each carrying its
/// sample-count TBR/TIR/TAR vs the target edges. Always four entries.
fn tod_buckets(valid: &[&StatSample], tlo: f64, thi: f64) -> Vec<TodBucket> {
    let mut cnt = [[0u32; 3]; 4]; // [bucket][below, in, above]
    for s in valid {
        let b = (minute_of_day(s.ts_ms) / 360) as usize;
        let idx = if s.bg_mgdl < tlo { 0 } else if s.bg_mgdl <= thi { 1 } else { 2 };
        cnt[b][idx] += 1;
    }
    (0..4)
        .map(|b| {
            let n = cnt[b][0] + cnt[b][1] + cnt[b][2];
            let inv = if n > 0 { 1.0 / n as f64 } else { 0.0 };
            TodBucket {
                start_min: b as u32 * 360,
                n,
                tbr: cnt[b][0] as f64 * inv,
                tir: cnt[b][1] as f64 * inv,
                tar: cnt[b][2] as f64 * inv,
            }
        })
        .collect()
}

/// The 18-bin glucose distribution histogram (20 mg/dL bins, [40,400), tails clamped).
fn histogram(bgs: &[f64]) -> Vec<HistBin> {
    let nbins = ((HIST_HI - HIST_LO) / HIST_BIN) as usize;
    let mut counts = vec![0u32; nbins];
    for &bg in bgs {
        let idx = (((bg - HIST_LO) / HIST_BIN).floor() as isize).clamp(0, nbins as isize - 1) as usize;
        counts[idx] += 1;
    }
    let n = bgs.len() as f64;
    (0..nbins)
        .map(|i| HistBin {
            lo: HIST_LO + i as f64 * HIST_BIN,
            hi: HIST_LO + (i + 1) as f64 * HIST_BIN,
            count: counts[i],
            frac: if n > 0.0 { counts[i] as f64 / n } else { 0.0 },
        })
        .collect()
}

/// Detect the hypo (`below == true`, edge `target_low`) / hyper (`below == false`, edge
/// `target_high`) excursion episodes and aggregate them. An episode is a maximal run of
/// ≥ `EPISODE_MIN_SAMPLES` consecutive in-band valid samples; a dropout gap (> `MAX_GAP_MS`)
/// splits a run. Duration is last−first ts; the extreme is the run's nadir/peak.
fn episodes(valid: &[&StatSample], edge: f64, below: bool) -> EpisodeSummary {
    let mut runs: Vec<Vec<(i64, f64)>> = Vec::new();
    let mut cur: Vec<(i64, f64)> = Vec::new();
    let mut prev_ts: Option<i64> = None;
    let close_run = |cur: &mut Vec<(i64, f64)>, runs: &mut Vec<Vec<(i64, f64)>>| {
        if cur.len() >= EPISODE_MIN_SAMPLES {
            runs.push(std::mem::take(cur));
        } else {
            cur.clear();
        }
    };
    for s in valid {
        let in_band = if below { s.bg_mgdl < edge } else { s.bg_mgdl > edge };
        let gap_break = prev_ts.map_or(false, |p| s.ts_ms - p > MAX_GAP_MS);
        if in_band && !gap_break {
            cur.push((s.ts_ms, s.bg_mgdl));
        } else {
            close_run(&mut cur, &mut runs);
            if in_band {
                cur.push((s.ts_ms, s.bg_mgdl));
            }
        }
        prev_ts = Some(s.ts_ms);
    }
    close_run(&mut cur, &mut runs);

    if runs.is_empty() {
        return EpisodeSummary::empty();
    }
    let count = runs.len() as u32;
    let mut total_dur = 0i64;
    let mut ext_sum = 0.0;
    let mut worst = if below { f64::INFINITY } else { f64::NEG_INFINITY };
    for r in &runs {
        total_dur += r.last().unwrap().0 - r.first().unwrap().0;
        let ext = if below {
            r.iter().map(|&(_, b)| b).fold(f64::INFINITY, f64::min)
        } else {
            r.iter().map(|&(_, b)| b).fold(f64::NEG_INFINITY, f64::max)
        };
        ext_sum += ext;
        worst = if below { worst.min(ext) } else { worst.max(ext) };
    }
    EpisodeSummary {
        count,
        total_duration_ms: total_dur,
        mean_duration_ms: total_dur as f64 / count as f64,
        mean_extreme: ext_sum / count as f64,
        worst_extreme: worst,
    }
}

/// Compute the `AdvancedStats` for `samples` against the target range and AGP bin grid.
///
/// * `target_low`/`target_high` — mg/dL, `low < high` required.
/// * `agp_bins` — time-of-day bins; must evenly divide 1440 (e.g. 24 hourly, 48 half-hourly).
///
/// Time-weighting: each valid-BG sample holds the gap to the next (clamped to
/// `MAX_GAP_MS`); the final sample holds the median clamped gap. Band fractions are
/// weight sums, not sample counts. The shared block (`mean_bg`/`sd`/`cv`/`gmi`) and the
/// treatment totals are plain sample-count reductions, matching the server.
///
/// Fail-closed: an empty series, or one with no finite positive BG, returns
/// `AdvancedStats::empty()`. Bad range / bin count returns `Err`.
#[uniffi::export]
pub fn advanced_stats(
    samples: Vec<StatSample>,
    target_low: u16,
    target_high: u16,
    agp_bins: u32,
) -> Result<AdvancedStats, CoreError> {
    if target_low >= target_high {
        return Err(CoreError::Decode {
            reason: format!("target range: low {target_low} must be < high {target_high}"),
        });
    }
    if agp_bins == 0 || agp_bins > DAY_MIN || DAY_MIN % agp_bins != 0 {
        return Err(CoreError::Decode {
            reason: format!("agp_bins {agp_bins} must divide {DAY_MIN} evenly (e.g. 24, 48)"),
        });
    }
    let tlo = target_low as f64;
    let thi = target_high as f64;

    // Sort by timestamp (defensive — time-weighting needs order); cheap clone.
    let mut all = samples;
    all.sort_by_key(|s| s.ts_ms);

    // Valid-BG subsequence for every BG-derived metric.
    let valid: Vec<&StatSample> =
        all.iter().filter(|s| s.bg_mgdl.is_finite() && s.bg_mgdl > 0.0).collect();
    if valid.is_empty() {
        return Ok(AdvancedStats::empty());
    }
    let n = valid.len();
    let bgs: Vec<f64> = valid.iter().map(|s| s.bg_mgdl).collect();

    // ── time weights over the valid subsequence ──────────────────────────────
    let mut weights = vec![0.0f64; n];
    let mut gaps: Vec<i64> = Vec::with_capacity(n.saturating_sub(1));
    for i in 0..n - 1 {
        let g = (valid[i + 1].ts_ms - valid[i].ts_ms).clamp(0, MAX_GAP_MS);
        gaps.push(g);
        weights[i] = g as f64;
    }
    // Last sample: median clamped gap (or the grid nominal when only one sample).
    let last = if gaps.is_empty() {
        MAX_GAP_MS as f64 // single sample: nominal, cancels in the ratio anyway
    } else {
        let mut g = gaps.clone();
        g.sort_unstable();
        let m = g.len();
        let med = if m % 2 == 1 {
            g[m / 2] as f64
        } else {
            (g[m / 2 - 1] + g[m / 2]) as f64 / 2.0
        };
        med
    };
    weights[n - 1] = last;
    let wsum: f64 = weights.iter().sum();

    // ── band fractions (time-weighted) ───────────────────────────────────────
    let (mut w_vlow, mut w_low, mut w_in, mut w_high, mut w_vhigh) = (0.0, 0.0, 0.0, 0.0, 0.0);
    for (i, &bg) in bgs.iter().enumerate() {
        let w = weights[i];
        if bg < VERY_LOW {
            w_vlow += w;
        } else if bg < tlo {
            w_low += w;
        }
        if bg >= tlo && bg <= thi {
            w_in += w;
        }
        if bg > thi && bg <= VERY_HIGH {
            w_high += w;
        } else if bg > VERY_HIGH {
            w_vhigh += w;
        }
    }
    let inv = if wsum > 0.0 { 1.0 / wsum } else { 0.0 };
    let tbr = (w_vlow + w_low) * inv;
    let tir = w_in * inv;
    let tar = (w_high + w_vhigh) * inv;
    let sub_bands = SubBands {
        very_low: w_vlow * inv,
        low: w_low * inv,
        in_range: w_in * inv,
        high: w_high * inv,
        very_high: w_vhigh * inv,
    };

    // ── shared block: mean / population SD / CV / GMI (matches the server) ────
    let mean_bg = bgs.iter().sum::<f64>() / n as f64;
    let var = bgs.iter().map(|v| (v - mean_bg).powi(2)).sum::<f64>() / n as f64;
    let sd = var.sqrt();
    let cv = if mean_bg != 0.0 { sd / mean_bg * 100.0 } else { 0.0 };
    let gmi = 3.31 + 0.02392 * mean_bg;

    // ── LBGI / HBGI (Kovatchev risk; reuse the crate's f) ────────────────────
    let (mut lsum, mut hsum) = (0.0, 0.0);
    for &bg in &bgs {
        let f = kovatchev_f(bg);
        let r = 10.0 * f * f;
        if f < 0.0 {
            lsum += r;
        } else if f > 0.0 {
            hsum += r;
        }
    }
    let lbgi = lsum / n as f64;
    let hbgi = hsum / n as f64;

    // ── MAGE ─────────────────────────────────────────────────────────────────
    let mage_v = mage(&bgs, sd);

    // ── channels (over all samples) ──────────────────────────────────────────
    let span_ms = all.last().unwrap().ts_ms - all.first().unwrap().ts_ms;
    let days = span_ms as f64 / DAY_MS;
    let total_carbs: f64 = all.iter().filter_map(|s| s.carbs_g).sum();
    let total_bolus: f64 = all.iter().filter_map(|s| s.bolus_u).sum();
    let total_basal: f64 = all.iter().filter_map(|s| s.basal_u).sum();
    let (mean_daily_carbs, tdd) = if days > 0.0 {
        (total_carbs / days, (total_bolus + total_basal) / days)
    } else {
        (0.0, 0.0)
    };
    let bolus_basal_ratio = if total_basal != 0.0 { total_bolus / total_basal } else { 0.0 };

    let step_vals: Vec<f64> = all.iter().filter_map(|s| s.steps).map(|s| s as f64).collect();
    let mean_steps =
        if step_vals.is_empty() { None } else { Some(step_vals.iter().sum::<f64>() / step_vals.len() as f64) };

    let mood_vals: Vec<i32> = all.iter().filter_map(|s| s.mood).collect();
    let mood = if mood_vals.is_empty() {
        None
    } else {
        let n_m = mood_vals.len();
        Some(MoodSummary {
            mean: mood_vals.iter().map(|&m| m as f64).sum::<f64>() / n_m as f64,
            n: n_m as u32,
            min: *mood_vals.iter().min().unwrap(),
            max: *mood_vals.iter().max().unwrap(),
        })
    };

    // ── AGP percentile bands ─────────────────────────────────────────────────
    let bin_width = DAY_MIN / agp_bins; // minutes per bin (exact — divides 1440)
    let mut buckets: Vec<Vec<f64>> = vec![Vec::new(); agp_bins as usize];
    for s in &valid {
        let minute_of_day = ((s.ts_ms.rem_euclid(DAY_MS as i64)) / 60_000) as u32;
        let b = (minute_of_day / bin_width) as usize;
        buckets[b].push(s.bg_mgdl);
    }
    let mut agp = Vec::new();
    for (b, vals) in buckets.iter_mut().enumerate() {
        if vals.is_empty() {
            continue;
        }
        vals.sort_by(|a, c| a.partial_cmp(c).unwrap());
        agp.push(AgpBin {
            minute_of_day: b as u32 * bin_width,
            p5: percentile_sorted(vals, 5.0),
            p25: percentile_sorted(vals, 25.0),
            p50: percentile_sorted(vals, 50.0),
            p75: percentile_sorted(vals, 75.0),
            p95: percentile_sorted(vals, 95.0),
        });
    }

    // ── variability & risk extensions (Phase 7D) ─────────────────────────────
    let modd_v = modd(&valid);
    let conga1 = conga(&valid, 1);
    let conga2 = conga(&valid, 2);
    let conga4 = conga(&valid, 4);
    let j_index = 0.001 * (mean_bg + sd).powi(2);
    let m_value = bgs.iter().map(|&bg| (10.0 * (bg / M_REF).log10()).abs().powi(3)).sum::<f64>() / n as f64;
    let adrr_v = adrr(&valid);
    let dtd = dtd_sd(&valid);

    // GRADE score + region attribution.
    let (mut g_sum, mut g_hypo, mut g_eu, mut g_hyper) = (0.0, 0.0, 0.0, 0.0);
    for &bg in &bgs {
        let g = grade_contrib(bg);
        g_sum += g;
        let mmol = bg * MMOL_PER_MGDL;
        if mmol < GRADE_HYPO_MMOL {
            g_hypo += g;
        } else if mmol > GRADE_HYPER_MMOL {
            g_hyper += g;
        } else {
            g_eu += g;
        }
    }
    let inv_g = if g_sum > 0.0 { 1.0 / g_sum } else { 0.0 };
    let grade = GradeSplit {
        grade: g_sum / n as f64,
        hypo: g_hypo * inv_g,
        eu: g_eu * inv_g,
        hyper: g_hyper * inv_g,
    };

    let tod = tod_buckets(&valid, tlo, thi);
    let histogram = histogram(&bgs);
    let hypo_episodes = episodes(&valid, tlo, true);
    let hyper_episodes = episodes(&valid, thi, false);

    Ok(AdvancedStats {
        n_samples: n as u32,
        span_ms,
        tir,
        tbr,
        tar,
        sub_bands,
        lbgi,
        hbgi,
        mage: mage_v,
        mean_bg,
        sd,
        cv,
        gmi,
        total_carbs,
        total_bolus,
        total_basal,
        mean_daily_carbs,
        tdd,
        bolus_basal_ratio,
        mean_steps,
        mood,
        agp,
        modd: modd_v,
        conga1,
        conga2,
        conga4,
        j_index,
        m_value,
        adrr: adrr_v,
        dtd_sd: dtd,
        grade,
        tod,
        histogram,
        hypo_episodes,
        hyper_episodes,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    fn golden() -> Value {
        serde_json::from_str(include_str!("testdata/stats_golden.json")).unwrap()
    }

    fn opt_f(v: &Value) -> Option<f64> {
        v.as_f64()
    }

    fn samples_from(g: &Value) -> Vec<StatSample> {
        g["samples"]
            .as_array()
            .unwrap()
            .iter()
            .map(|s| StatSample {
                ts_ms: s["ts_ms"].as_i64().unwrap(),
                bg_mgdl: s["bg_mgdl"].as_f64().unwrap(),
                carbs_g: opt_f(&s["carbs_g"]),
                bolus_u: opt_f(&s["bolus_u"]),
                basal_u: opt_f(&s["basal_u"]),
                steps: s["steps"].as_i64(),
                mood: s["mood"].as_i64().map(|m| m as i32),
            })
            .collect()
    }

    fn close(a: f64, b: f64, tol: f64, what: &str) {
        assert!((a - b).abs() <= tol, "{what}: got {a}, want {b} (tol {tol})");
    }

    #[test]
    fn advanced_stats_golden() {
        let g = golden();
        let p = &g["params"];
        let e = &g["expected"];
        let out = advanced_stats(
            samples_from(&g),
            p["target_low"].as_u64().unwrap() as u16,
            p["target_high"].as_u64().unwrap() as u16,
            p["agp_bins"].as_u64().unwrap() as u32,
        )
        .expect("golden series must compute");

        assert_eq!(out.n_samples, e["n_samples"].as_u64().unwrap() as u32);
        assert_eq!(out.span_ms, e["span_ms"].as_i64().unwrap());

        // Range-dependent (time-weighted).
        close(out.tir, e["tir"].as_f64().unwrap(), 1e-9, "tir");
        close(out.tbr, e["tbr"].as_f64().unwrap(), 1e-9, "tbr");
        close(out.tar, e["tar"].as_f64().unwrap(), 1e-9, "tar");
        let sb = &e["sub_bands"];
        close(out.sub_bands.very_low, sb["very_low"].as_f64().unwrap(), 1e-9, "very_low");
        close(out.sub_bands.low, sb["low"].as_f64().unwrap(), 1e-9, "low");
        close(out.sub_bands.in_range, sb["in_range"].as_f64().unwrap(), 1e-9, "in_range");
        close(out.sub_bands.high, sb["high"].as_f64().unwrap(), 1e-9, "high");
        close(out.sub_bands.very_high, sb["very_high"].as_f64().unwrap(), 1e-9, "very_high");
        // Bands partition the record.
        close(out.sub_bands.very_low + out.sub_bands.low + out.sub_bands.in_range
                + out.sub_bands.high + out.sub_bands.very_high, 1.0, 1e-9, "sub_bands sum");

        // Risk-space + variability.
        close(out.lbgi, e["lbgi"].as_f64().unwrap(), 1e-6, "lbgi");
        close(out.hbgi, e["hbgi"].as_f64().unwrap(), 1e-6, "hbgi");
        close(out.mage, e["mage"].as_f64().unwrap(), 1e-6, "mage");

        // Shared block (must match the server bit-for-bit → tight tol).
        close(out.mean_bg, e["mean_bg"].as_f64().unwrap(), 1e-9, "mean_bg");
        close(out.sd, e["sd"].as_f64().unwrap(), 1e-9, "sd");
        close(out.cv, e["cv"].as_f64().unwrap(), 1e-9, "cv");
        close(out.gmi, e["gmi"].as_f64().unwrap(), 1e-9, "gmi");

        // Channels.
        close(out.total_carbs, e["total_carbs"].as_f64().unwrap(), 1e-9, "total_carbs");
        close(out.total_bolus, e["total_bolus"].as_f64().unwrap(), 1e-9, "total_bolus");
        close(out.total_basal, e["total_basal"].as_f64().unwrap(), 1e-9, "total_basal");
        close(out.mean_daily_carbs, e["mean_daily_carbs"].as_f64().unwrap(), 1e-6, "mean_daily_carbs");
        close(out.tdd, e["tdd"].as_f64().unwrap(), 1e-6, "tdd");
        close(out.bolus_basal_ratio, e["bolus_basal_ratio"].as_f64().unwrap(), 1e-9, "bolus_basal_ratio");
        close(out.mean_steps.unwrap(), e["mean_steps"].as_f64().unwrap(), 1e-9, "mean_steps");
        let m = out.mood.unwrap();
        let em = &e["mood"];
        close(m.mean, em["mean"].as_f64().unwrap(), 1e-9, "mood.mean");
        assert_eq!(m.n, em["n"].as_u64().unwrap() as u32);
        assert_eq!(m.min, em["min"].as_i64().unwrap() as i32);
        assert_eq!(m.max, em["max"].as_i64().unwrap() as i32);

        // AGP ribbon.
        let ea = e["agp"].as_array().unwrap();
        assert_eq!(out.agp.len(), ea.len(), "agp bin count");
        for (got, want) in out.agp.iter().zip(ea) {
            assert_eq!(got.minute_of_day, want["minute_of_day"].as_u64().unwrap() as u32);
            close(got.p5, want["p5"].as_f64().unwrap(), 1e-9, "agp.p5");
            close(got.p25, want["p25"].as_f64().unwrap(), 1e-9, "agp.p25");
            close(got.p50, want["p50"].as_f64().unwrap(), 1e-9, "agp.p50");
            close(got.p75, want["p75"].as_f64().unwrap(), 1e-9, "agp.p75");
            close(got.p95, want["p95"].as_f64().unwrap(), 1e-9, "agp.p95");
        }

        // ── Phase-7D extensions (independent numpy reference in the golden) ──
        close(out.modd, e["modd"].as_f64().unwrap(), 1e-6, "modd");
        close(out.conga1, e["conga1"].as_f64().unwrap(), 1e-6, "conga1");
        close(out.conga2, e["conga2"].as_f64().unwrap(), 1e-6, "conga2");
        close(out.conga4, e["conga4"].as_f64().unwrap(), 1e-6, "conga4");
        close(out.j_index, e["j_index"].as_f64().unwrap(), 1e-6, "j_index");
        close(out.m_value, e["m_value"].as_f64().unwrap(), 1e-6, "m_value");
        close(out.adrr, e["adrr"].as_f64().unwrap(), 1e-6, "adrr");
        close(out.dtd_sd, e["dtd_sd"].as_f64().unwrap(), 1e-6, "dtd_sd");
        let eg = &e["grade"];
        close(out.grade.grade, eg["grade"].as_f64().unwrap(), 1e-6, "grade");
        close(out.grade.hypo, eg["hypo"].as_f64().unwrap(), 1e-9, "grade.hypo");
        close(out.grade.eu, eg["eu"].as_f64().unwrap(), 1e-9, "grade.eu");
        close(out.grade.hyper, eg["hyper"].as_f64().unwrap(), 1e-9, "grade.hyper");
        // GRADE region fractions partition the score.
        close(out.grade.hypo + out.grade.eu + out.grade.hyper, 1.0, 1e-9, "grade split sum");

        let et = e["tod"].as_array().unwrap();
        assert_eq!(out.tod.len(), 4, "four diurnal buckets");
        assert_eq!(out.tod.len(), et.len(), "tod count");
        for (got, want) in out.tod.iter().zip(et) {
            assert_eq!(got.start_min, want["start_min"].as_u64().unwrap() as u32);
            assert_eq!(got.n, want["n"].as_u64().unwrap() as u32, "tod.n");
            close(got.tir, want["tir"].as_f64().unwrap(), 1e-9, "tod.tir");
            close(got.tbr, want["tbr"].as_f64().unwrap(), 1e-9, "tod.tbr");
            close(got.tar, want["tar"].as_f64().unwrap(), 1e-9, "tod.tar");
        }

        let eh = e["histogram"].as_array().unwrap();
        assert_eq!(out.histogram.len(), 18, "18 histogram bins");
        assert_eq!(out.histogram.len(), eh.len(), "histogram count");
        let mut hist_total = 0u32;
        for (got, want) in out.histogram.iter().zip(eh) {
            close(got.lo, want["lo"].as_f64().unwrap(), 1e-9, "hist.lo");
            close(got.hi, want["hi"].as_f64().unwrap(), 1e-9, "hist.hi");
            assert_eq!(got.count, want["count"].as_u64().unwrap() as u32, "hist.count");
            close(got.frac, want["frac"].as_f64().unwrap(), 1e-9, "hist.frac");
            hist_total += got.count;
        }
        assert_eq!(hist_total, out.n_samples, "histogram counts every valid sample");

        for (got, key) in [(&out.hypo_episodes, "hypo_episodes"), (&out.hyper_episodes, "hyper_episodes")] {
            let w = &e[key];
            assert_eq!(got.count, w["count"].as_u64().unwrap() as u32, "{key}.count");
            assert_eq!(got.total_duration_ms, w["total_duration_ms"].as_i64().unwrap(), "{key}.dur");
            close(got.mean_duration_ms, w["mean_duration_ms"].as_f64().unwrap(), 1e-6, "ep.mean_dur");
            close(got.mean_extreme, w["mean_extreme"].as_f64().unwrap(), 1e-9, "ep.mean_extreme");
            close(got.worst_extreme, w["worst_extreme"].as_f64().unwrap(), 1e-9, "ep.worst");
        }
    }

    // ── hand-computed anchors for the extensions the periodic golden leaves degenerate ──

    fn s(ts_ms: i64, bg: f64) -> StatSample {
        StatSample { ts_ms, bg_mgdl: bg, carbs_g: None, bolus_u: None, basal_u: None, steps: None, mood: None }
    }

    #[test]
    fn modd_and_dtd_hand_computed() {
        // Two days, one matched minute-of-day (00:00) per day: day0 = 100, day1 = 140.
        // MODD = |140−100| = 40. Daily means differ (100 vs 140) → dtd_sd = SD([100,140]) = 20.
        let day = 86_400_000i64;
        let out = advanced_stats(vec![s(0, 100.0), s(day, 140.0)], 70, 180, 24).unwrap();
        close(out.modd, 40.0, 1e-9, "modd two-day");
        close(out.dtd_sd, 20.0, 1e-9, "dtd two-day");
        // A single day → no matched pair, no between-day SD: both 0, no NaN.
        let one = advanced_stats(vec![s(0, 100.0), s(300_000, 120.0)], 70, 180, 24).unwrap();
        close(one.modd, 0.0, 1e-12, "modd single day");
        close(one.dtd_sd, 0.0, 1e-12, "dtd single day");
    }

    #[test]
    fn conga_hand_computed() {
        // 5-min grid, values [100,110,130]; CONGA-5min-lag differences = [10,20];
        // reuse the 1-hour path shape by lagging 5 min is awkward, so test the 1h lag:
        // samples one hour apart [100, 130, 160] → Dt = [30,30], SD = 0.
        let hr = 3_600_000i64;
        let out = advanced_stats(vec![s(0, 100.0), s(hr, 130.0), s(2 * hr, 160.0)], 70, 180, 24).unwrap();
        close(out.conga1, 0.0, 1e-9, "conga1 constant slope → 0 SD");
        // Uneven: [100, 130, 150] → Dt = [30, 20]; population SD = 5.
        let out2 = advanced_stats(vec![s(0, 100.0), s(hr, 130.0), s(2 * hr, 150.0)], 70, 180, 24).unwrap();
        close(out2.conga1, 5.0, 1e-9, "conga1 uneven");
    }

    #[test]
    fn j_and_m_value_hand_computed() {
        // Constant 120 mg/dL: SD = 0 → J = 0.001·120² = 14.4; M = |10·log10(120/120)|³ = 0.
        let flat: Vec<StatSample> = (0..6).map(|i| s(i * 300_000, 120.0)).collect();
        let out = advanced_stats(flat, 70, 180, 24).unwrap();
        close(out.j_index, 14.4, 1e-9, "j-index @120 flat");
        close(out.m_value, 0.0, 1e-12, "m-value @120 flat");
    }

    #[test]
    fn episodes_hand_computed() {
        // 30-min grid (== MAX_GAP_MS, no gap break). One hypo run (2 samples: 60,50) and one
        // hyper run (3 samples: 200,240,210), separated by an in-range sample.
        let g = 1_800_000i64;
        let series = vec![
            s(0, 120.0),          // in range
            s(g, 60.0),           // hypo start
            s(2 * g, 50.0),       // hypo (nadir 50)
            s(3 * g, 120.0),      // recover
            s(4 * g, 200.0),      // hyper start
            s(5 * g, 240.0),      // hyper (peak 240)
            s(6 * g, 210.0),      // hyper
            s(7 * g, 120.0),      // recover
        ];
        let out = advanced_stats(series, 70, 180, 24).unwrap();
        assert_eq!(out.hypo_episodes.count, 1, "one hypo episode");
        assert_eq!(out.hypo_episodes.total_duration_ms, g, "hypo dur = one 30-min step");
        close(out.hypo_episodes.mean_extreme, 50.0, 1e-9, "hypo nadir");
        close(out.hypo_episodes.worst_extreme, 50.0, 1e-9, "hypo worst nadir");
        assert_eq!(out.hyper_episodes.count, 1, "one hyper episode");
        assert_eq!(out.hyper_episodes.total_duration_ms, 2 * g, "hyper dur = two steps");
        close(out.hyper_episodes.mean_extreme, 240.0, 1e-9, "hyper peak");
        // A lone below-target sample (< min-samples) is NOT an episode.
        let lone = advanced_stats(vec![s(0, 120.0), s(g, 55.0), s(2 * g, 120.0)], 70, 180, 24).unwrap();
        assert_eq!(lone.hypo_episodes.count, 0, "single-sample dip is not an episode");
    }

    #[test]
    fn grade_split_hand_computed() {
        // All-euglycemic constant 120 mg/dL (6.66 mmol, between 3.9 and 7.8) → the whole score
        // is attributed to `eu`; hypo and hyper fractions are 0.
        let flat: Vec<StatSample> = (0..6).map(|i| s(i * 300_000, 120.0)).collect();
        let out = advanced_stats(flat, 70, 180, 24).unwrap();
        close(out.grade.eu, 1.0, 1e-12, "grade all-eu");
        close(out.grade.hypo, 0.0, 1e-12, "grade hypo 0");
        close(out.grade.hyper, 0.0, 1e-12, "grade hyper 0");
        assert!(out.grade.grade > 0.0, "even eu glucose carries a small positive GRADE");
    }

    // ── independent anchors (not routed through the golden generator) ────────

    #[test]
    fn lbgi_hbgi_pure_low_and_high() {
        // A series pinned at 70 mg/dL: f(70) ≈ -0.8806 < 0 → all risk is LBGI, HBGI = 0.
        let lows: Vec<StatSample> = (0..12)
            .map(|i| StatSample {
                ts_ms: i * 300_000,
                bg_mgdl: 70.0,
                carbs_g: None,
                bolus_u: None,
                basal_u: None,
                steps: None,
                mood: None,
            })
            .collect();
        let s = advanced_stats(lows, 70, 180, 24).unwrap();
        let want_l = 10.0 * kovatchev_f(70.0).powi(2);
        close(s.lbgi, want_l, 1e-9, "lbgi @70");
        close(s.hbgi, 0.0, 1e-12, "hbgi @70");

        // Pinned at 250 mg/dL: f(250) > 0 → all risk is HBGI, LBGI = 0.
        let highs: Vec<StatSample> = (0..12)
            .map(|i| StatSample {
                ts_ms: i * 300_000,
                bg_mgdl: 250.0,
                carbs_g: None,
                bolus_u: None,
                basal_u: None,
                steps: None,
                mood: None,
            })
            .collect();
        let s = advanced_stats(highs, 70, 180, 24).unwrap();
        close(s.lbgi, 0.0, 1e-12, "lbgi @250");
        close(s.hbgi, 10.0 * kovatchev_f(250.0).powi(2), 1e-9, "hbgi @250");
    }

    #[test]
    fn mage_sawtooth_hand_computed() {
        // [100,100,200,100,200,100]: SD ≈ 47.14; every 100-swing exceeds 1 SD → MAGE = 100.
        let vals = [100.0, 100.0, 200.0, 100.0, 200.0, 100.0];
        let mean = vals.iter().sum::<f64>() / 6.0;
        let sd = (vals.iter().map(|v| (v - mean).powi(2)).sum::<f64>() / 6.0).sqrt();
        assert!((sd - 47.140).abs() < 1e-2, "sd {sd}");
        assert_eq!(mage(&vals, sd), 100.0);
        // Sub-SD swings are filtered: a big 100→300 step (200) dwarfs SD (≈74) and counts;
        // the ±1 wobbles do not → MAGE = 200 (the sole qualifying excursion).
        let mix = [100.0, 300.0, 299.0, 300.0, 299.0, 300.0];
        let mm = mix.iter().sum::<f64>() / 6.0;
        let sdm = (mix.iter().map(|v| (v - mm).powi(2)).sum::<f64>() / 6.0).sqrt();
        assert!((1.0..200.0).contains(&sdm), "sd {sdm} must sit between the wobble and the step");
        assert_eq!(mage(&mix, sdm), 200.0);
        // A constant series has no excursion → MAGE = 0 (no NaN from an empty average).
        assert_eq!(mage(&[100.0, 100.0, 100.0, 100.0], 0.0), 0.0);
    }

    #[test]
    fn agp_percentiles_hand_computed() {
        // Two bins (720-min each). Bin 0 (minute-of-day < 720) gets 40,100,200,300,400 across
        // days; type-7 percentiles of that are exactly [52,100,200,300,380].
        let day = 86_400_000i64;
        let vals = [40.0f64, 100.0, 200.0, 300.0, 400.0];
        let samples: Vec<StatSample> = vals
            .iter()
            .enumerate()
            .map(|(d, &bg)| StatSample {
                ts_ms: d as i64 * day + 60 * 60_000, // 01:00 → bin 0
                bg_mgdl: bg,
                carbs_g: None,
                bolus_u: None,
                basal_u: None,
                steps: None,
                mood: None,
            })
            .collect();
        let s = advanced_stats(samples, 70, 180, 2).unwrap();
        assert_eq!(s.agp.len(), 1, "only one populated bin");
        let b = &s.agp[0];
        assert_eq!(b.minute_of_day, 0);
        close(b.p5, 52.0, 1e-9, "p5");
        close(b.p25, 100.0, 1e-9, "p25");
        close(b.p50, 200.0, 1e-9, "p50");
        close(b.p75, 300.0, 1e-9, "p75");
        close(b.p95, 380.0, 1e-9, "p95");
    }

    #[test]
    fn time_weighting_clamps_dropout_gap() {
        // Three readings: 100 (in), 100 (in) five minutes on, then a 6-hour-later 300
        // (very-high). The 6h gap is a sensor dropout: without the clamp the second
        // in-range reading would persist for 6h and swamp everything. The clamp caps that
        // forward gap at MAX_GAP_MS (30 min).
        let s = advanced_stats(
            vec![
                StatSample { ts_ms: 0, bg_mgdl: 100.0, carbs_g: None, bolus_u: None, basal_u: None, steps: None, mood: None },
                StatSample { ts_ms: 300_000, bg_mgdl: 100.0, carbs_g: None, bolus_u: None, basal_u: None, steps: None, mood: None },
                StatSample { ts_ms: 300_000 + 6 * 3_600_000, bg_mgdl: 300.0, carbs_g: None, bolus_u: None, basal_u: None, steps: None, mood: None },
            ],
            70,
            180,
            24,
        )
        .unwrap();
        // gaps = [300k, clamp(6h)=1_800k]; last weight = median([300k,1_800k]) = 1_050k.
        // in-range weight = 300k(bg100) + 1_800k(bg100); very-high = 1_050k(bg300).
        let total = 300_000.0 + 1_800_000.0 + 1_050_000.0; // 3_150k
        close(s.tir, (300_000.0 + 1_800_000.0) / total, 1e-9, "clamped tir");
        close(s.tar, 1_050_000.0 / total, 1e-9, "clamped tar");
        // The clamp did fire: without it the middle reading would hold the full 6h
        // (21_600k), pinning tir far higher than this.
        assert!(s.tir < 0.9, "unclamped tir would exceed 0.98; the clamp pulls it to ~0.667");
        close(s.tir + s.tar, 1.0, 1e-9, "two bands partition the record");
    }

    #[test]
    fn empty_and_degenerate_are_total() {
        // Empty series.
        let s = advanced_stats(vec![], 70, 180, 24).unwrap();
        assert_eq!(s.n_samples, 0);
        assert!(s.agp.is_empty() && s.mean_steps.is_none() && s.mood.is_none());
        // All-invalid BG (NaN / non-positive) → still empty, no NaN leak.
        let bad = vec![
            StatSample { ts_ms: 0, bg_mgdl: f64::NAN, carbs_g: Some(1.0), bolus_u: None, basal_u: None, steps: None, mood: None },
            StatSample { ts_ms: 1, bg_mgdl: -5.0, carbs_g: None, bolus_u: None, basal_u: None, steps: None, mood: None },
        ];
        let s = advanced_stats(bad, 70, 180, 24).unwrap();
        assert_eq!(s.n_samples, 0);
        for v in [s.tir, s.tbr, s.tar, s.lbgi, s.hbgi, s.mage, s.mean_bg, s.sd, s.cv, s.gmi] {
            assert!(v.is_finite(), "no NaN/inf in empty stats");
        }
        // Single valid sample: no panic, finite, one AGP bin.
        let one = vec![StatSample {
            ts_ms: 500_000,
            bg_mgdl: 120.0,
            carbs_g: None,
            bolus_u: None,
            basal_u: None,
            steps: Some(10),
            mood: Some(3),
        }];
        let s = advanced_stats(one, 70, 180, 24).unwrap();
        assert_eq!(s.n_samples, 1);
        close(s.tir, 1.0, 1e-12, "single in-range");
        assert_eq!(s.agp.len(), 1);
        assert!(s.mean_daily_carbs == 0.0 && s.tdd == 0.0, "zero-span daily rates are 0");
    }

    #[test]
    fn rejects_bad_range_and_bins() {
        let one = vec![StatSample {
            ts_ms: 0,
            bg_mgdl: 120.0,
            carbs_g: None,
            bolus_u: None,
            basal_u: None,
            steps: None,
            mood: None,
        }];
        assert!(advanced_stats(one.clone(), 180, 70, 24).is_err(), "low>high");
        assert!(advanced_stats(one.clone(), 100, 100, 24).is_err(), "low==high");
        assert!(advanced_stats(one.clone(), 70, 180, 0).is_err(), "0 bins");
        assert!(advanced_stats(one.clone(), 70, 180, 7).is_err(), "7 ∤ 1440");
        assert!(advanced_stats(one.clone(), 70, 180, 5000).is_err(), "too many bins");
        assert!(advanced_stats(one, 70, 180, 48).is_ok(), "48 | 1440");
    }
}
