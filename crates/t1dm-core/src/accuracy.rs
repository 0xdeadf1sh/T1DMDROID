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
//! This is an accuracy statement about a *forecast*, never a dosing claim (safety-posture.md):
//! it is displayed advisory-only. Everything is total — an empty horizon or a bad
//! `horizon_min` never panics; a horizon with fewer than `min_samples` matured pairs is
//! still emitted (with its true `n`) and flagged `sufficient = false` so the UI can say
//! "insufficient history" plainly rather than print a noisy statistic.

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
}
