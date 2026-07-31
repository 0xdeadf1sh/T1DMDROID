//! Continuous Glucose-Error Grid Analysis (CG-EGA), Kovatchev 2004 (Diabetes Care
//! 27(8):1922) as adapted to glucose *prediction*.
//!
//! A transcription of `T1DMAI/cg_ega.py` (itself a vectorized port of the dotXem/CG-EGA
//! reference) into scalar Rust. Nothing here is a new definition: the zone algebra, the
//! AP/BE/EP filter matrices and the region split are `dotXem`'s, and the anchoring and
//! window are `SPEC/invariants.md` §6.3.
//!
//! **This is not Kovatchev's published grid** and must not be described as one. §6.3
//! enumerates where `dotXem` departs from it — a non-directional rate widening, the
//! hyperglycaemic `lD` cell marked benign where the published grid marks it erroneous,
//! and a different upper-`C` slope. The first two under-report danger. They are
//! reproduced here deliberately, because matching `T1DMAI` bit for bit is what makes the
//! phone's panel and that project's validation table one statistic — not because they
//! are right.
//!
//! CG-EGA grades a forecast on two axes at once and reduces the pair to one clinical
//! verdict per point:
//!
//!   * P-EGA (point) — 5 zones `[A, B, C, D, E]` — is the predicted VALUE close enough,
//!     with the acceptance band widened when BG is moving fast (the `mod` term)?
//!   * R-EGA (rate)  — 8 zones `[A, B, uC, lC, uD, lD, uE, lE]` — does the predicted RATE
//!     of change (mg/dL/min) agree with the true rate?
//!
//! The `(R-mark, P-mark)` pair is looked up per glycaemic region and reduced to AP
//! (accurate), BE (benign error) or EP (erroneous prediction).
//!
//! **The 70 / 180 / 240 mg/dL numbers below are the published GRID, not the patient's
//! bands.** They fix where the error grid's zones lie and are the same in every CG-EGA
//! ever published; the configurable hypo/hyper thresholds of `SPEC/invariants.md` §6.1 are
//! a different quantity and are passed in by the caller of the excursion detectors, never
//! substituted here.
//!
//! Everything is scalar, allocation-light and total: no indexing that can go out of
//! bounds, no division that can trap, no panic on a hostile value.

/// Number of P-EGA marks — `[A, B, C, D, E]`.
const P_MARKS: usize = 5;
/// Number of R-EGA marks — `[A, B, uC, lC, uD, lD, uE, lE]`.
const R_MARKS: usize = 8;

/// AP / BE / EP verdict codes, matching the reference's `0 / 1 / 2`.
const LABEL_AP: u8 = 0;
const LABEL_BE: u8 = 1;
const LABEL_EP: u8 = 2;

/// Which P-mark columns (indices into `[A,B,C,D,E]`) each region's filter matrices use —
/// hypo `[A,D,E]`, eu `[A,B,C]`, hyper all five. A `(R,P)` cell whose P-mark is not among
/// its region's columns is EP: it is in neither the AP nor the BE filter.
const HYPO_P_COLS: [usize; 3] = [0, 3, 4];
const EU_P_COLS: [usize; 3] = [0, 1, 2];
const HYPER_P_COLS: [usize; 5] = [0, 1, 2, 3, 4];

// Filter matrices, VERBATIM from the reference: rows are the 8 R-marks in
// `[A, B, uC, lC, uD, lD, uE, lE]` order, columns the region's selected P-marks above.
const FILTER_AP_HYPO: [[bool; 3]; R_MARKS] = [
    [true, false, false],
    [true, false, false],
    [false, false, false],
    [false, false, false],
    [false, false, false],
    [false, false, false],
    [false, false, false],
    [false, false, false],
];
const FILTER_BE_HYPO: [[bool; 3]; R_MARKS] = [
    [false, false, false],
    [false, false, false],
    [true, false, false],
    [true, false, false],
    [false, false, false],
    [true, false, false],
    [false, false, false],
    [true, false, false],
];
const FILTER_AP_EU: [[bool; 3]; R_MARKS] = [
    [true, true, false],
    [true, true, false],
    [false, false, false],
    [false, false, false],
    [false, false, false],
    [false, false, false],
    [false, false, false],
    [false, false, false],
];
const FILTER_BE_EU: [[bool; 3]; R_MARKS] = [
    [false, false, false],
    [false, false, false],
    [true, true, false],
    [true, true, false],
    [true, true, false],
    [true, true, false],
    [false, false, false],
    [false, false, false],
];
const FILTER_AP_HYPER: [[bool; 5]; R_MARKS] = [
    [true, true, false, false, false],
    [true, true, false, false, false],
    [false, false, false, false, false],
    [false, false, false, false, false],
    [false, false, false, false, false],
    [false, false, false, false, false],
    [false, false, false, false, false],
    [false, false, false, false, false],
];
const FILTER_BE_HYPER: [[bool; 5]; R_MARKS] = [
    [false, false, false, false, false],
    [false, false, false, false, false],
    [true, true, false, false, false],
    [true, true, false, false, false],
    [true, true, false, false, false],
    [true, true, false, false, false],
    [false, false, false, false, false],
    [false, false, false, false, false],
];

/// The three glycaemic regions, assigned by the TRUE BG of each point (published grid:
/// hypo `y <= 70`, eu `70 < y <= 180`, hyper `y > 180`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Region {
    Hypo = 0,
    Eu = 1,
    Hyper = 2,
}

/// Region of a point, from its TRUE BG (mg/dL).
#[inline]
fn region_of(y_true: f64) -> Region {
    if y_true <= 70.0 {
        Region::Hypo
    } else if y_true <= 180.0 {
        Region::Eu
    } else {
        Region::Hyper
    }
}

/// The `(8 × 5)` AP/BE/EP verdict table for one region, derived from the filter matrices
/// exactly as the reference's `_region_label_table` does (default EP; a column carried by
/// the AP filter is AP, else by the BE filter is BE).
fn label_table(region: Region) -> [[u8; P_MARKS]; R_MARKS] {
    let mut table = [[LABEL_EP; P_MARKS]; R_MARKS];
    match region {
        Region::Hypo => fill(&mut table, &HYPO_P_COLS, &FILTER_AP_HYPO, &FILTER_BE_HYPO),
        Region::Eu => fill(&mut table, &EU_P_COLS, &FILTER_AP_EU, &FILTER_BE_EU),
        Region::Hyper => fill(&mut table, &HYPER_P_COLS, &FILTER_AP_HYPER, &FILTER_BE_HYPER),
    }
    table
}

/// Write one region's filter columns into its verdict table. Generic over the column count
/// so hypo/eu (3 columns) and hyper (5) share the derivation rather than repeating it.
fn fill<const C: usize>(
    table: &mut [[u8; P_MARKS]; R_MARKS],
    cols: &[usize; C],
    f_ap: &[[bool; C]; R_MARKS],
    f_be: &[[bool; C]; R_MARKS],
) {
    for (j, &p) in cols.iter().enumerate() {
        for r in 0..R_MARKS {
            table[r][p] = if f_ap[r][j] {
                LABEL_AP
            } else if f_be[r][j] {
                LABEL_BE
            } else {
                LABEL_EP
            };
        }
    }
}

/// Per-step rate of change (mg/dL/min) anchored at `last_bg` — `SPEC/invariants.md` §6.3.
///
/// `dy[t] = (y[t] − y[t−1]) / freq_min` with `y[−1] := last_bg`, so every step in the
/// window carries a rate, the first included. The anchor is the measured BG at the
/// forecast's `made_at` — the persistence value — for the truth and the forecast alike.
pub(crate) fn rates(y: &[f64], last_bg: f64, freq_min: f64) -> Vec<f64> {
    let mut out = Vec::with_capacity(y.len());
    let mut prev = last_bg;
    for &v in y {
        out.push((v - prev) / freq_min);
        prev = v;
    }
    out
}

/// P-EGA mark for one point: an index into `[A, B, C, D, E]`.
///
/// `dy_true` drives `mod`, the rate-of-change widening of the acceptance bands. The
/// return is `argmax` over the five booleans — first true wins, and an all-false stack
/// yields `A`, reproducing `np.argmax` on a boolean stack exactly (`B` is defined as the
/// complement of the others, so all-false cannot actually arise).
fn p_ega_mark(y_true: f64, y_pred: f64, dy_true: f64) -> usize {
    let m = if (dy_true > -2.0 && dy_true <= -1.0) || (dy_true >= 1.0 && dy_true < 2.0) {
        10.0
    } else if dy_true <= -2.0 || dy_true >= 2.0 {
        20.0
    } else {
        0.0
    };

    let a = ((y_pred <= 70.0 + m) && (y_true <= 70.0))
        || ((y_pred <= y_true * 6.0 / 5.0 + m) && (y_pred >= y_true * 4.0 / 5.0 - m));
    let e = ((y_true > 180.0) && (y_pred < 70.0 - m))
        || ((y_pred > 180.0 + m) && (y_true <= 70.0));
    let d = ((y_pred > 70.0 + m)
        && (y_pred > y_true * 6.0 / 5.0 + m)
        && (y_true <= 70.0)
        && (y_pred <= 180.0 + m))
        || ((y_true > 240.0) && (y_pred < 180.0 - m) && (y_pred >= 70.0 - m));
    let c = ((y_true > 70.0)
        && (y_pred > y_true * 22.0 / 17.0 + (180.0 - 70.0 * 22.0 / 17.0) + m))
        || ((y_true <= 180.0) && (y_pred < y_true * 7.0 / 5.0 - 182.0 - m));
    let b = !(a || c || d || e);

    first_true(&[a, b, c, d, e])
}

/// R-EGA mark for one point: an index into `[A, B, uC, lC, uD, lD, uE, lE]`.
///
/// `argmax` semantics as above — and here an all-false stack IS reachable, in which case
/// numpy's `argmax` returns 0 (`A`). Reproduced deliberately: diverging would score a
/// handful of points into a different clinical verdict than the reference does.
fn r_ega_mark(dy_true: f64, dy_pred: f64) -> usize {
    let a = ((dy_pred >= dy_true - 1.0) && (dy_pred <= dy_true + 1.0))
        || ((dy_pred <= dy_true / 2.0) && (dy_pred >= dy_true * 2.0))
        || ((dy_pred <= dy_true * 2.0) && (dy_pred >= dy_true / 2.0));
    let b = !a
        && (((dy_pred <= -1.0) && (dy_true <= -1.0))
            || ((dy_pred <= dy_true + 2.0) && (dy_pred >= dy_true - 2.0))
            || ((dy_pred >= 1.0) && (dy_true >= 1.0)));
    let uc = (dy_true < 1.0) && (dy_true >= -1.0) && (dy_pred > dy_true + 2.0);
    let lc = (dy_true <= 1.0) && (dy_true > -1.0) && (dy_pred < dy_true - 2.0);
    let ud = (dy_pred <= 1.0) && (dy_pred >= -1.0) && (dy_pred > dy_true + 2.0);
    let ld = (dy_pred <= 1.0) && (dy_pred >= -1.0) && (dy_pred < dy_true - 2.0);
    let ue = (dy_pred > 1.0) && (dy_true < -1.0);
    let le = (dy_pred < -1.0) && (dy_true > 1.0);

    first_true(&[a, b, uc, lc, ud, ld, ue, le])
}

/// `np.argmax` over a boolean stack: the first `true`, or 0 when none is.
#[inline]
fn first_true(flags: &[bool]) -> usize {
    flags.iter().position(|&f| f).unwrap_or(0)
}

/// The nine AP/BE/EP counts, `[hypo, eu, hyper] × [ap, be, ep]`, indexed
/// `counts[region][verdict]`.
pub(crate) type CgEgaCounts = [[u32; 3]; 3];

/// Accumulate one forecast window's CG-EGA verdicts into `counts`.
///
/// `y_true` and `y_pred` are the realized and (band-projected, §6.2) forecast mg/dL
/// trajectories over the WHOLE window — §6.3 scores every step from first to last, not a
/// single horizon. `last_bg` is the persistence anchor both rates difference against, and
/// `freq_min` the five-minute grid of §1. A length mismatch contributes nothing rather
/// than indexing out of bounds.
pub(crate) fn accumulate(
    counts: &mut CgEgaCounts,
    y_true: &[f64],
    y_pred: &[f64],
    last_bg: f64,
    freq_min: f64,
) {
    if y_true.len() != y_pred.len() {
        return;
    }
    let dy_true = rates(y_true, last_bg, freq_min);
    let dy_pred = rates(y_pred, last_bg, freq_min);
    // The three verdict tables are derived per call; 120 cells is far cheaper than the
    // synchronization a cached copy would need, and keeps the derivation auditable.
    let tables = [
        label_table(Region::Hypo),
        label_table(Region::Eu),
        label_table(Region::Hyper),
    ];
    for t in 0..y_true.len() {
        let region = region_of(y_true[t]);
        let p = p_ega_mark(y_true[t], y_pred[t], dy_true[t]);
        let r = r_ega_mark(dy_true[t], dy_pred[t]);
        let verdict = tables[region as usize][r][p];
        counts[region as usize][verdict as usize] += 1;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rates_anchor_at_the_persistence_value() {
        // §6.3: y[-1] := the anchor, so step 0 carries a rate too.
        let dy = rates(&[105.0, 115.0, 110.0], 100.0, 5.0);
        assert_eq!(dy, vec![1.0, 2.0, -1.0]);
        assert_eq!(rates(&[], 100.0, 5.0), Vec::<f64>::new());
    }

    #[test]
    fn a_perfect_forecast_is_wholly_accurate() {
        // Truth == prediction ⇒ P-mark A and R-mark A everywhere ⇒ every point AP.
        let truth = [65.0, 68.0, 100.0, 140.0, 200.0, 260.0];
        let mut counts: CgEgaCounts = [[0; 3]; 3];
        accumulate(&mut counts, &truth, &truth, 70.0, 5.0);
        let total_ap: u32 = counts.iter().map(|r| r[LABEL_AP as usize]).sum();
        assert_eq!(total_ap, truth.len() as u32);
    }

    #[test]
    fn region_split_follows_the_published_grid() {
        assert_eq!(region_of(70.0), Region::Hypo);
        assert_eq!(region_of(70.001), Region::Eu);
        assert_eq!(region_of(180.0), Region::Eu);
        assert_eq!(region_of(180.001), Region::Hyper);
    }

    #[test]
    fn label_tables_default_to_erroneous_outside_a_regions_columns() {
        // Hypo carries only the [A, D, E] P-columns; B and C are in neither filter ⇒ EP.
        let t = label_table(Region::Hypo);
        assert_eq!(t[0][0], LABEL_AP); // (R=A, P=A)
        assert_eq!(t[0][1], LABEL_EP); // (R=A, P=B) — not a hypo column
        assert_eq!(t[2][0], LABEL_BE); // (R=uC, P=A)
        let e = label_table(Region::Eu);
        assert_eq!(e[1][1], LABEL_AP); // (R=B, P=B)
        assert_eq!(e[4][1], LABEL_BE); // (R=uD, P=B)
        assert_eq!(e[4][2], LABEL_EP); // (R=uD, P=C) — C is in neither eu filter
        assert_eq!(e[6][0], LABEL_EP); // (R=uE, P=A)
    }

    #[test]
    fn mismatched_lengths_contribute_nothing() {
        let mut counts: CgEgaCounts = [[0; 3]; 3];
        accumulate(&mut counts, &[100.0, 110.0], &[100.0], 100.0, 5.0);
        assert_eq!(counts, [[0; 3]; 3]);
    }
}
