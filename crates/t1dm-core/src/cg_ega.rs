//! CG-EGA, transcribed from `T1DMAI/cg_ega.py` (a port of the dotXem reference). Anchoring
//! and window: `SPEC/invariants.md` §6.3.
//!
//! Not Kovatchev's published grid: §6.3 lists dotXem's departures, two of which under-report
//! danger. Reproduced anyway so this and `T1DMAI` publish one statistic.
//!
//! The 70 / 180 / 240 mg/dL below are the published grid's zone boundaries, never the
//! configurable hypo/hyper thresholds of §6.1.

/// `[A, B, C, D, E]`.
const P_MARKS: usize = 5;
/// `[A, B, uC, lC, uD, lD, uE, lE]`.
const R_MARKS: usize = 8;

/// The reference's codes; not free to renumber.
const LABEL_AP: u8 = 0;
const LABEL_BE: u8 = 1;
const LABEL_EP: u8 = 2;

/// P-mark columns (indices into `[A,B,C,D,E]`) each region's filters carry. A cell whose
/// P-mark is not among them is in neither filter, hence EP.
const HYPO_P_COLS: [usize; 3] = [0, 3, 4];
const EU_P_COLS: [usize; 3] = [0, 1, 2];
const HYPER_P_COLS: [usize; 5] = [0, 1, 2, 3, 4];

// Verbatim from the reference. Rows are the 8 R-marks in order, columns the region's
// P-columns above.
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

/// Assigned by the TRUE BG of a point.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Region {
    Hypo = 0,
    Eu = 1,
    Hyper = 2,
}

/// `y_true` in mg/dL.
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

/// The `(8 × 5)` verdict table for one region, as the reference's `_region_label_table`.
fn label_table(region: Region) -> [[u8; P_MARKS]; R_MARKS] {
    let mut table = [[LABEL_EP; P_MARKS]; R_MARKS];
    match region {
        Region::Hypo => fill(&mut table, &HYPO_P_COLS, &FILTER_AP_HYPO, &FILTER_BE_HYPO),
        Region::Eu => fill(&mut table, &EU_P_COLS, &FILTER_AP_EU, &FILTER_BE_EU),
        Region::Hyper => fill(&mut table, &HYPER_P_COLS, &FILTER_AP_HYPER, &FILTER_BE_HYPER),
    }
    table
}

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

/// Per-step rate (mg/dL/min) with `y[−1] := last_bg` — `SPEC/invariants.md` §6.3.
pub(crate) fn rates(y: &[f64], last_bg: f64, freq_min: f64) -> Vec<f64> {
    let mut out = Vec::with_capacity(y.len());
    let mut prev = last_bg;
    for &v in y {
        out.push((v - prev) / freq_min);
        prev = v;
    }
    out
}

/// Index into `[A, B, C, D, E]`. `dy_true` drives `m`, the rate widening of the bands.
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

/// Index into `[A, B, uC, lC, uD, lD, uE, lE]`. An all-false stack is reachable here and
/// yields `A`, as numpy's `argmax` does; diverging would rescore points.
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

/// `np.argmax` semantics: the first `true`, or 0 when none is.
#[inline]
fn first_true(flags: &[bool]) -> usize {
    flags.iter().position(|&f| f).unwrap_or(0)
}

/// `counts[region][verdict]` — `[hypo, eu, hyper] × [ap, be, ep]`.
pub(crate) type CgEgaCounts = [[u32; 3]; 3];

/// `y_pred` is band-projected (§6.2) mg/dL; §6.3 scores every step of the window, not one
/// horizon. `last_bg` anchors both rate series, `freq_min` is §1's grid. A length mismatch
/// contributes nothing.
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
        let dy = rates(&[105.0, 115.0, 110.0], 100.0, 5.0);
        assert_eq!(dy, vec![1.0, 2.0, -1.0]);
        assert_eq!(rates(&[], 100.0, 5.0), Vec::<f64>::new());
    }

    #[test]
    fn a_perfect_forecast_is_wholly_accurate() {
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
        let t = label_table(Region::Hypo);
        assert_eq!(t[0][0], LABEL_AP); // (R=A, P=A)
        assert_eq!(t[0][1], LABEL_EP); // (R=A, P=B)
        assert_eq!(t[2][0], LABEL_BE); // (R=uC, P=A)
        let e = label_table(Region::Eu);
        assert_eq!(e[1][1], LABEL_AP); // (R=B, P=B)
        assert_eq!(e[4][1], LABEL_BE); // (R=uD, P=B)
        assert_eq!(e[4][2], LABEL_EP); // (R=uD, P=C)
        assert_eq!(e[6][0], LABEL_EP); // (R=uE, P=A)
    }

    #[test]
    fn mismatched_lengths_contribute_nothing() {
        let mut counts: CgEgaCounts = [[0; 3]; 3];
        accumulate(&mut counts, &[100.0, 110.0], &[100.0], 100.0, 5.0);
        assert_eq!(counts, [[0; 3]; 3]);
    }
}
