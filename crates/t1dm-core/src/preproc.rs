//! Model pre/post-processing — the fp64 reference pipeline (Phase 2, INFERENCE.md).
//!
//! The ExecuTorch graph is cut at `head_raw` (B,4,6,7) in risk space; everything on
//! either side of that cut lives here, in fp64 Rust, as the numerical authority
//! (SPEC.private.md §2.4/§3.2). The descriptor JSON is the SOLE source of the
//! normalization stats and the checkpoint-absent decode constants — the app never
//! parses the `.pt` pickle.
//!
//! Pipeline (INFERENCE.md §§6-8):
//!   raw channels ──savgol──> ──normalize──> context (z) ──graph──> head_raw
//!                                                │                     │
//!                                          last_bg anchor        assemble_decode
//!                                                                (softplus+floor,
//!                                                                 cumsum fan, global
//!                                                                 DCT median, f_inv)
//!
//! Every `#[uniffi::export]` fn is total on hostile input (the crate is
//! `panic = "abort"`): malformed shapes return `Err(CoreError)`, degenerate forecasts
//! are flagged by [`forecast_degeneracy_check`] rather than trusted.

use serde::Deserialize;

use crate::{kovatchev_f, kovatchev_f_inv, CoreError, BG_CLAMP_MAX, BG_CLAMP_MIN};

// ── Fixed architecture constants (INFERENCE.md §11; not descriptor-varying) ─────────
/// Steps per patch (6 × 5 min = 30 min).
const PATCH_SIZE: usize = 6;
/// Input feature stack width `[bg_absolute, carb_intake, insulin_combined]`.
const N_FEAT: usize = 3;
/// Quantile spreads per side of the median.
const N_SPREADS: usize = 3;
/// Head raw width `= 1 + 2·N_SPREADS`, == number of quantiles.
const N_QUANTILES: usize = 1 + 2 * N_SPREADS;
/// `std` floor in the z-score denominator (INFERENCE.md §6).
const STD_FLOOR: f64 = 1e-8;
/// PyTorch `F.softplus` linear-regime threshold: `x > 20 ⇒ softplus(x) == x`.
const SOFTPLUS_THRESHOLD: f64 = 20.0;

/// One-sided Savitzky-Golay endpoint taps for `(window=7, polyorder=2)`, oldest→newest,
/// as exact rationals over 42: `[5,-3,-6,-4,3,15,32]/42` (`scipy.signal.savgol_coeffs(7,
/// 2, pos=6, use='dot')`). `use='dot'` order is load-bearing — the newest sample carries
/// the largest weight (32/42), so the endpoint estimate does not lag (utils.py §causal).
const SAVGOL_TAPS: [f64; 7] = [5.0, -3.0, -6.0, -4.0, 3.0, 15.0, 32.0];
const SAVGOL_DENOM: f64 = 42.0;
const SAVGOL_WINDOW: usize = 7;

// ── Degeneracy thresholds (§3.6-B) ──────────────────────────────────────────────────
/// mg/dL tolerance for "pinned to a rail" (flat 20 or flat 500).
const RAIL_EPS_MGDL: f64 = 1e-3;
/// mg/dL band width below which the fan is treated as collapsed (a healthy fan clears
/// this by orders of magnitude — the 1e-3 risk spread floor alone yields ≳0.06 mg/dL).
const COLLAPSE_EPS_MGDL: f64 = 1e-4;
/// Non-monotonicity tolerance for the ascending quantile fan (risk space).
const MONOTONE_TOL: f64 = 1e-9;

// ── Model descriptor (INFERENCE.md §2, SPEC §2.4) ───────────────────────────────────

/// Per-channel normalization statistics. `bg_absolute` lives in Kovatchev **risk**
/// space (mean/std fit on `f(bg)`); `carb_intake` / `insulin_combined` in **log1p**
/// space (INFERENCE.md §6).
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record, Deserialize)]
pub struct ChannelStat {
    pub mean: f64,
    pub std: f64,
}

/// The co-trained hour-of-day TIME PROBE section of a descriptor (the second `.pte`
/// output). Present iff the exported graph emits `time_logits`; a graph cut at `head_raw`
/// leaves this `None` and the app surfaces no predicted-hour belief (fail-open, never
/// hard-depended-on). `output_index` is the positional `.pte` slot (1 in the current
/// export); `n_bins`/`bin_hours` describe the hour-of-day circle the logits softmax over.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct TimeHead {
    pub output_index: i32,
    pub n_bins: i32,
    pub bin_hours: f64,
}

/// The full pre/post contract parsed from `descriptor.json` — the app's sole source of
/// the normalization stats plus the decode-critical constants absent from the
/// checkpoint (INFERENCE.md §3.1, SPEC §2.4). Downstream Rust reads every constant from
/// here so a re-exported model can never silently diverge from its baked graph.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ModelDescriptor {
    pub bg: ChannelStat,
    pub carb: ChannelStat,
    pub insulin: ChannelStat,
    /// RoPE base frequency (checkpoint-absent; released default 1000).
    pub rope_base: i32,
    /// Global-median DCT subspace dimension `G` (released default 6).
    pub median_global_dim: i32,
    /// Global-median basis kind (`"dct"`).
    pub step_basis_type: String,
    /// Additive floor on each softplus spread (`BG_QUANTILE_SPREAD_MIN`, 1e-3).
    pub quantile_spread_min: f64,
    /// Struct-mask blocked-position fill for the fp16-safe softmax (`-30000.0`).
    pub neg_fill: f64,
    /// Prediction horizon in hours (default 2 ⇒ P = 4 patches).
    pub prediction_horizon_hours: i32,
    pub max_context_patches: i32,
    pub min_context_patches: i32,
    pub patch_size: i32,
    pub n_input_features: i32,
    /// Split-conformal band recalibration flag. **Default false** for real-CGM
    /// deployment (INFERENCE.md §8.4 — a simulator-fit delta must never silently
    /// narrow the safety bands).
    pub conformal_enabled: bool,
    /// The co-trained hour-of-day time-probe descriptor, or `None` when the exported graph
    /// is cut at `head_raw` (BG fan only). Consumed by [`decode_time`] to surface a
    /// circadian-phase belief; its absence is graceful (no predicted-hour rendered).
    pub time: Option<TimeHead>,
}

impl ModelDescriptor {
    fn stat(&self, feat: usize) -> ChannelStat {
        match feat {
            0 => self.bg,
            1 => self.carb,
            2 => self.insulin,
            _ => self.bg, // unreachable: feat ∈ [0,3)
        }
    }

    /// P = PREDICTION_PATCHES = horizon_hours · (60 / (patch_size · 5)).
    fn prediction_patches(&self) -> Result<usize, CoreError> {
        let step_min = self.patch_size as i64 * 5;
        if step_min <= 0 || 60 % step_min != 0 {
            return Err(CoreError::Internal {
                reason: format!("patch_size {} does not tile the hour", self.patch_size),
            });
        }
        let per_hour = 60 / step_min;
        Ok((self.prediction_horizon_hours as i64 * per_hour) as usize)
    }
}

// ── descriptor.json parsing ─────────────────────────────────────────────────────────

#[derive(Deserialize)]
struct NormStatsDto {
    bg_absolute: ChannelStat,
    carb_intake: ChannelStat,
    insulin_combined: ChannelStat,
}

#[derive(Deserialize)]
struct TimeDto {
    output_index: i32,
    n_bins: i32,
    bin_hours: f64,
}

#[derive(Deserialize)]
struct DescriptorDto {
    normalization_stats: NormStatsDto,
    rope_base: i32,
    median_global_dim: i32,
    step_basis_type: String,
    quantile_spread_min: f64,
    neg_fill: f64,
    prediction_horizon_hours: i32,
    max_context_patches: i32,
    min_context_patches: i32,
    patch_size: i32,
    n_input_features: i32,
    conformal_enabled: bool,
    #[serde(default)]
    time: Option<TimeDto>,
}

/// Parse a model `descriptor.json` (SPEC §2.4) into a [`ModelDescriptor`]. Returns
/// `Err(CoreError::Decode)` — never panics — on malformed JSON or a missing field.
#[uniffi::export]
pub fn parse_descriptor(json: String) -> Result<ModelDescriptor, CoreError> {
    let d: DescriptorDto = serde_json::from_str(&json).map_err(|e| CoreError::Decode {
        reason: format!("descriptor parse: {e}"),
    })?;
    if d.n_input_features as usize != N_FEAT {
        return Err(CoreError::Decode {
            reason: format!("n_input_features {} != {N_FEAT}", d.n_input_features),
        });
    }
    if d.step_basis_type != "dct" {
        return Err(CoreError::Decode {
            reason: format!("unsupported step_basis_type {:?} (want \"dct\")", d.step_basis_type),
        });
    }
    // The time-probe section is OPTIONAL (a `head_raw`-only export omits it). When present it
    // must describe a non-degenerate hour circle, else the predicted-hour decode is unusable.
    let time = match d.time {
        None => None,
        Some(t) => {
            if t.n_bins <= 0 {
                return Err(CoreError::Decode {
                    reason: format!("time.n_bins {} must be > 0", t.n_bins),
                });
            }
            if !(t.bin_hours.is_finite() && t.bin_hours > 0.0) {
                return Err(CoreError::Decode {
                    reason: format!("time.bin_hours {} must be finite and > 0", t.bin_hours),
                });
            }
            Some(TimeHead {
                output_index: t.output_index,
                n_bins: t.n_bins,
                bin_hours: t.bin_hours,
            })
        }
    };
    let desc = ModelDescriptor {
        bg: d.normalization_stats.bg_absolute,
        carb: d.normalization_stats.carb_intake,
        insulin: d.normalization_stats.insulin_combined,
        rope_base: d.rope_base,
        median_global_dim: d.median_global_dim,
        step_basis_type: d.step_basis_type,
        quantile_spread_min: d.quantile_spread_min,
        neg_fill: d.neg_fill,
        prediction_horizon_hours: d.prediction_horizon_hours,
        max_context_patches: d.max_context_patches,
        min_context_patches: d.min_context_patches,
        patch_size: d.patch_size,
        n_input_features: d.n_input_features,
        conformal_enabled: d.conformal_enabled,
        time,
    };

    // ── Fail-closed guards on decode-critical descriptor drift (§3.6-B, INFERENCE.md §11) ──
    // A re-exported model whose decode constants diverge from its baked graph is REJECTED
    // here (Decode → a `null` descriptor on the Kotlin side → the model is refused), never
    // silently trusted: several of these degrade into a *confident-flat* forecast that
    // `forecast_degeneracy_check` cannot catch (e.g. `median_global_dim=0` pins the median at
    // the anchor via an empty DCT basis; a negative value casts to `usize::MAX` and defeats
    // the low-frequency contraction).
    if desc.median_global_dim < 1 {
        return Err(CoreError::Decode {
            reason: format!("median_global_dim {} must be >= 1", desc.median_global_dim),
        });
    }
    if desc.patch_size != PATCH_SIZE as i32 {
        return Err(CoreError::Decode {
            reason: format!("patch_size {} != fixed architecture {PATCH_SIZE}", desc.patch_size),
        });
    }
    if !(desc.neg_fill.is_finite() && desc.neg_fill < 0.0) {
        return Err(CoreError::Decode {
            reason: format!("neg_fill {} must be finite and < 0", desc.neg_fill),
        });
    }
    if !(desc.quantile_spread_min.is_finite() && desc.quantile_spread_min >= 0.0) {
        return Err(CoreError::Decode {
            reason: format!(
                "quantile_spread_min {} must be finite and >= 0",
                desc.quantile_spread_min
            ),
        });
    }
    if !(desc.min_context_patches > 0 && desc.min_context_patches <= desc.max_context_patches) {
        return Err(CoreError::Decode {
            reason: format!(
                "context patch bounds invalid: require 0 < min_context_patches ({}) <= max_context_patches ({})",
                desc.min_context_patches, desc.max_context_patches
            ),
        });
    }
    if desc.prediction_horizon_hours <= 0 {
        return Err(CoreError::Decode {
            reason: format!(
                "prediction_horizon_hours {} must be > 0",
                desc.prediction_horizon_hours
            ),
        });
    }
    if desc.rope_base <= 0 {
        return Err(CoreError::Decode {
            reason: format!("rope_base {} must be > 0", desc.rope_base),
        });
    }
    // Reject a patch_size/horizon that does not tile the prediction hour (defense in depth
    // over the patch_size guard; also forces a representable prediction-patch count).
    if desc.prediction_patches().is_err() {
        return Err(CoreError::Decode {
            reason: format!(
                "patch_size {} / prediction_horizon_hours {} does not tile the prediction hour",
                desc.patch_size, desc.prediction_horizon_hours
            ),
        });
    }

    Ok(desc)
}

// ── Causal Savitzky-Golay smoother (INFERENCE.md §7.1) ──────────────────────────────

/// Strictly-causal one-sided Savitzky-Golay smooth of a 1-D signal (`window=7`,
/// `polyorder=2`, evaluated at the endpoint). `out[t]` is the degree-2 fit to
/// `x[t-6 ..= t]` read at `t`, so it uses only `x[≤t]` and never leaks the future; the
/// left edge is causally replicated with `x[0]`. Optional physical clamps are applied
/// to the output (BG → `[20,500]`; carb/insulin → `min = 0`). Mirrors
/// `utils.causal_smooth` (fp64 matmul; T1DMAI casts to fp32 at the end, which we skip —
/// this is the authority).
#[uniffi::export]
pub fn causal_smooth(series: Vec<f64>, clamp_min: Option<f64>, clamp_max: Option<f64>) -> Vec<f64> {
    let n = series.len();
    let mut out = vec![0.0f64; n];
    if n == 0 {
        return out;
    }
    for t in 0..n {
        let mut acc = 0.0f64;
        for (j, &tap) in SAVGOL_TAPS.iter().enumerate() {
            // window sample = x[t - (W-1) + j], replicated with x[0] on the left edge.
            let idx = t as isize - (SAVGOL_WINDOW as isize - 1) + j as isize;
            let xv = if idx < 0 { series[0] } else { series[idx as usize] };
            acc += tap * xv;
        }
        let mut v = acc / SAVGOL_DENOM;
        if let Some(lo) = clamp_min {
            if v < lo {
                v = lo;
            }
        }
        if let Some(hi) = clamp_max {
            if v > hi {
                v = hi;
            }
        }
        out[t] = v;
    }
    out
}

// ── Per-channel normalize / denormalize (INFERENCE.md §6) ───────────────────────────

/// z-score one raw value for input feature `feat` (0=bg risk-z, 1/2=carb/insulin
/// log1p-z), stats from the descriptor.
fn normalize_feat(d: &ModelDescriptor, feat: usize, x: f64) -> f64 {
    let s = d.stat(feat);
    let pre = if feat == 0 {
        kovatchev_f(x) // clamps to [20,500] and scrubs NaN internally
    } else {
        x.max(0.0).ln_1p()
    };
    (pre - s.mean) / (s.std + STD_FLOOR)
}

/// Inverse of [`normalize_feat`] back to raw units.
fn denormalize_feat(d: &ModelDescriptor, feat: usize, z: f64) -> f64 {
    let s = d.stat(feat);
    let v = z * (s.std + STD_FLOOR) + s.mean;
    if feat == 0 {
        kovatchev_f_inv(v) // risk → mg/dL, clamped to [20,500]
    } else {
        v.exp_m1().max(0.0)
    }
}

/// Normalize a raw `[bg_mgdl, carb, insulin]` sample to z-space (INFERENCE.md §6).
#[uniffi::export]
pub fn normalize_sample(desc: &ModelDescriptor, bg: f64, carb: f64, insulin: f64) -> Vec<f64> {
    vec![
        normalize_feat(desc, 0, bg),
        normalize_feat(desc, 1, carb),
        normalize_feat(desc, 2, insulin),
    ]
}

/// Denormalize a z-space `[bg, carb, insulin]` sample back to raw units.
#[uniffi::export]
pub fn denormalize_sample(desc: &ModelDescriptor, z: Vec<f64>) -> Result<Vec<f64>, CoreError> {
    if z.len() != N_FEAT {
        return Err(CoreError::Internal {
            reason: format!("denormalize_sample expects {N_FEAT} channels, got {}", z.len()),
        });
    }
    Ok(vec![
        denormalize_feat(desc, 0, z[0]),
        denormalize_feat(desc, 1, z[1]),
        denormalize_feat(desc, 2, z[2]),
    ])
}

// ── Context construction (INFERENCE.md §7) ──────────────────────────────────────────

/// The normalized model input built from a raw history, plus the mg/dL anchor.
/// `context` is the `n_ctx·PATCH_SIZE·N_FEAT` step-major-flattened normalized context
/// (`flat = global_step·3 + feat`); `pred` is the `P·PATCH_SIZE·N_FEAT` prediction zone
/// (BG feat 0 = literal 0; dose feats = `normalize(0)` baseline or announced doses);
/// `last_bg` is the mg/dL persistence anchor. The backend left-pads `context` to the
/// fixed T=52 artifact and builds the struct mask (SPEC §2.4).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct BuiltContext {
    pub n_ctx: i32,
    pub prediction_patches: i32,
    pub context: Vec<f64>,
    pub pred: Vec<f64>,
    pub last_bg: f64,
}

/// Build the normalized context + prediction-zone patches + `last_bg` anchor from raw
/// per-step history (INFERENCE.md §7.2-7.4). `bg`/`carb`/`insulin` are equal-length
/// trailing series of `n_ctx·PATCH_SIZE` steps, `n_ctx ∈ [min,max]_context_patches`.
/// Each channel is causal-smoothed (BG clamp `[20,500]`; carb/insulin floor 0) then
/// normalized. `announced_carb`/`announced_insulin`, if present, are raw future doses
/// (length `P·PATCH_SIZE`) written into the prediction zone; absent slots take the
/// `normalize(0)` no-dose baseline (NOT z=0 — §7.3).
#[uniffi::export]
pub fn build_context(
    desc: &ModelDescriptor,
    bg: Vec<f64>,
    carb: Vec<f64>,
    insulin: Vec<f64>,
    announced_carb: Option<Vec<f64>>,
    announced_insulin: Option<Vec<f64>>,
) -> Result<BuiltContext, CoreError> {
    let n = bg.len();
    if n == 0 || carb.len() != n || insulin.len() != n {
        return Err(CoreError::Internal {
            reason: format!(
                "channel lengths must match and be > 0: bg={} carb={} insulin={}",
                n,
                carb.len(),
                insulin.len()
            ),
        });
    }
    if n % PATCH_SIZE != 0 {
        return Err(CoreError::Internal {
            reason: format!("history length {n} is not a multiple of PATCH_SIZE {PATCH_SIZE}"),
        });
    }
    let n_ctx = n / PATCH_SIZE;
    if n_ctx < desc.min_context_patches as usize || n_ctx > desc.max_context_patches as usize {
        return Err(CoreError::Internal {
            reason: format!(
                "n_ctx {n_ctx} outside [{}, {}]",
                desc.min_context_patches, desc.max_context_patches
            ),
        });
    }
    let p = desc.prediction_patches()?;

    // Causal-smooth each channel in the same space the model was trained on.
    let sm_bg = causal_smooth(bg, Some(BG_CLAMP_MIN), Some(BG_CLAMP_MAX));
    let sm_carb = causal_smooth(carb, Some(0.0), None);
    let sm_ins = causal_smooth(insulin, Some(0.0), None);

    // Normalize + step-major interleave: context[gs*3 + feat].
    let mut context = vec![0.0f64; n * N_FEAT];
    for gs in 0..n {
        context[gs * N_FEAT] = normalize_feat(desc, 0, sm_bg[gs]);
        context[gs * N_FEAT + 1] = normalize_feat(desc, 1, sm_carb[gs]);
        context[gs * N_FEAT + 2] = normalize_feat(desc, 2, sm_ins[gs]);
    }

    // last_bg anchor: last context BG cell, un-z-scored then f_inv to mg/dL (§7.4).
    let last_z = context[(n - 1) * N_FEAT];
    let last_bg = denormalize_feat(desc, 0, last_z);

    // Prediction zone: BG feat 0 = literal 0; dose feats = normalize(0) or announced.
    let pred_steps = p * PATCH_SIZE;
    if let Some(a) = announced_carb.as_ref() {
        if a.len() != pred_steps {
            return Err(CoreError::Internal {
                reason: format!("announced_carb length {} != P·S {pred_steps}", a.len()),
            });
        }
    }
    if let Some(a) = announced_insulin.as_ref() {
        if a.len() != pred_steps {
            return Err(CoreError::Internal {
                reason: format!("announced_insulin length {} != P·S {pred_steps}", a.len()),
            });
        }
    }
    let zbase_carb = normalize_feat(desc, 1, 0.0);
    let zbase_ins = normalize_feat(desc, 2, 0.0);
    let mut pred = vec![0.0f64; pred_steps * N_FEAT];
    for j in 0..pred_steps {
        pred[j * N_FEAT] = 0.0; // BG: what the model predicts
        pred[j * N_FEAT + 1] = match announced_carb.as_ref() {
            Some(a) => normalize_feat(desc, 1, a[j]),
            None => zbase_carb,
        };
        pred[j * N_FEAT + 2] = match announced_insulin.as_ref() {
            Some(a) => normalize_feat(desc, 2, a[j]),
            None => zbase_ins,
        };
    }

    Ok(BuiltContext {
        n_ctx: n_ctx as i32,
        prediction_patches: p as i32,
        context,
        pred,
        last_bg,
    })
}

// ── Global-median DCT basis (INFERENCE.md §8.2) ─────────────────────────────────────

/// DCT-II cosine modes over `n` steps, `g` columns, L2-orthonormalized. Replicates
/// `utils.get_global_median_basis`, INCLUDING its unconditional fp32 round-trip
/// (`.to(float32).to(dtype)`): the columns are normalized in fp64, then each entry is
/// quantized through fp32 — load-bearing for bit-close agreement. Returned row-major
/// `(n, g)`.
fn global_median_basis(n: usize, g: usize) -> Vec<f64> {
    let mut b = vec![0.0f64; n * g];
    // B[s,j] = cos(pi*(s+0.5)*j/n).
    for s in 0..n {
        for j in 0..g {
            b[s * g + j] =
                (std::f64::consts::PI * (s as f64 + 0.5) * j as f64 / n as f64).cos();
        }
    }
    // L2-normalize each column, then quantize through fp32 (matches PyTorch .to(f32)).
    for j in 0..g {
        let mut nrm = 0.0f64;
        for s in 0..n {
            let v = b[s * g + j];
            nrm += v * v;
        }
        let nrm = nrm.sqrt();
        for s in 0..n {
            let normalized = b[s * g + j] / nrm;
            b[s * g + j] = normalized as f32 as f64;
        }
    }
    b
}

/// Numerically-stable softplus matching PyTorch `F.softplus` (beta=1, threshold=20).
fn softplus(x: f64) -> f64 {
    if x > SOFTPLUS_THRESHOLD {
        x
    } else {
        x.exp().ln_1p()
    }
}

// ── Quantile assembly + decode (INFERENCE.md §8.1-8.3) ──────────────────────────────

/// The decoded forecast. All arrays are step-major over the P·S horizon
/// (`i = p·S + s`). `median_risk` / `q_tau_risk` are risk space (the model's native
/// output space); `median_bg` / `bands_mgdl` are the `f_inv` mg/dL projections consumed
/// by rails, alerts and the GUI.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Forecast {
    pub median_risk: Vec<f64>,
    pub q_tau_risk: Vec<f64>,
    pub median_bg: Vec<f64>,
    pub bands_mgdl: Vec<f64>,
}

/// Assemble `head_raw` (P·S·7, risk space) into an ascending quantile fan and decode to
/// mg/dL (INFERENCE.md §8.1, `BG_HEAD_MEDIAN_MODE='global'`, conformal OFF). `head_raw`
/// column 0 is the median delta; columns 1..=3 the τ>.5 spreads (nearest→far); 4..=6
/// the τ<.5 spreads. The median is `anchor + proj_DCT(delta)` (a low-frequency L2
/// contraction that cannot drift); the fan is `m ± carry_spread ± cumsum(softplus+floor)`.
/// `head_raw` should already be upcast to fp64 by the backend (fp16 → fp64).
#[uniffi::export]
pub fn assemble_decode(
    desc: &ModelDescriptor,
    head_raw: Vec<f64>,
    last_bg: f64,
    carry_spread: f64,
) -> Result<Forecast, CoreError> {
    let stride = PATCH_SIZE * N_QUANTILES;
    if head_raw.is_empty() || head_raw.len() % stride != 0 {
        return Err(CoreError::Internal {
            reason: format!(
                "head_raw length {} is not a multiple of PATCH_SIZE·N_QUANTILES {stride}",
                head_raw.len()
            ),
        });
    }
    let p = head_raw.len() / stride;
    let n = p * PATCH_SIZE; // horizon steps

    // Anchor f(last_bg), broadcast flat across the horizon (§8.1).
    let anchor = kovatchev_f(last_bg.clamp(BG_CLAMP_MIN, BG_CLAMP_MAX));

    // Median: global low-frequency DCT projection of the per-step delta (patch-major).
    let delta: Vec<f64> = (0..n).map(|i| head_raw[i * N_QUANTILES]).collect();
    let g = (desc.median_global_dim as usize).min(n);
    let basis = global_median_basis(n, g); // (n, g) row-major, fp32-quantized
    // z = deltaᵀ · B  (g,) ; then delta_global = B · z  (n,).
    let mut zc = vec![0.0f64; g];
    for j in 0..g {
        let mut acc = 0.0f64;
        for i in 0..n {
            acc += delta[i] * basis[i * g + j];
        }
        zc[j] = acc;
    }
    let mut m = vec![0.0f64; n];
    for i in 0..n {
        let mut acc = 0.0f64;
        for j in 0..g {
            acc += zc[j] * basis[i * g + j];
        }
        m[i] = anchor + acc;
    }

    // Spreads: softplus + floor, cumsum fan around the median (§8.1).
    let floor = desc.quantile_spread_min;
    let mut median_risk = vec![0.0f64; n];
    let mut q_tau_risk = vec![0.0f64; n * N_QUANTILES];
    let mut median_bg = vec![0.0f64; n];
    let mut bands_mgdl = vec![0.0f64; n * N_QUANTILES];
    for i in 0..n {
        let mi = m[i];
        // d_up = cols 1..=3, d_dn = cols 4..=6.
        let mut up = [0.0f64; N_SPREADS];
        let mut dn = [0.0f64; N_SPREADS];
        let mut cs_up = 0.0f64;
        let mut cs_dn = 0.0f64;
        for k in 0..N_SPREADS {
            cs_up += softplus(head_raw[i * N_QUANTILES + 1 + k]) + floor;
            cs_dn += softplus(head_raw[i * N_QUANTILES + 1 + N_SPREADS + k]) + floor;
            up[k] = mi + carry_spread + cs_up;
            dn[k] = mi - carry_spread - cs_dn;
        }
        // Ascending τ: [dn.flip | m | up] = [.05 .1 .25 | .5 | .75 .9 .95].
        let row = i * N_QUANTILES;
        q_tau_risk[row] = dn[2];
        q_tau_risk[row + 1] = dn[1];
        q_tau_risk[row + 2] = dn[0];
        q_tau_risk[row + 3] = mi;
        q_tau_risk[row + 4] = up[0];
        q_tau_risk[row + 5] = up[1];
        q_tau_risk[row + 6] = up[2];
        median_risk[i] = mi;
        median_bg[i] = kovatchev_f_inv(mi);
        for k in 0..N_QUANTILES {
            bands_mgdl[row + k] = kovatchev_f_inv(q_tau_risk[row + k]);
        }
    }

    Ok(Forecast {
        median_risk,
        q_tau_risk,
        median_bg,
        bands_mgdl,
    })
}

// ── Forecast degeneracy guard (§3.6-B) ──────────────────────────────────────────────

/// Why a forecast is unfit to drive a rail, alert, or calculator score. Because `f_inv`
/// clamps to `[20,500]`, a collapsed or runaway model reads as a *confident flat rail*,
/// not an obvious NaN — hence the explicit rail / collapse / mis-order checks.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ForecastStatus {
    /// Passed every check; eligible to drive rails/alerts.
    Ok,
    /// A NaN or infinity in the median or the fan.
    NonFinite,
    /// The whole median is pinned flat at 20 or 500 mg/dL.
    RailPinned,
    /// The band has (near-)zero width at every step.
    CollapsedBand,
    /// The quantile fan is not monotone ascending (an fp16 mis-order).
    MisorderedQuantiles,
}

/// The safety guard every rail and alert gates on (§3.6-B). Rejects: non-finite values,
/// a rail-pinned flat median, a collapsed (zero-width) band, and a mis-ordered quantile
/// fan. A forecast that fails is `DEGENERATE` — ineligible for a predictive alert and it
/// forces the fail-closed rails to block.
#[uniffi::export]
pub fn forecast_degeneracy_check(f: &Forecast) -> ForecastStatus {
    let n = f.median_bg.len();
    if n == 0
        || f.median_risk.len() != n
        || f.median_bg.len() != n
        || f.q_tau_risk.len() != n * N_QUANTILES
        || f.bands_mgdl.len() != n * N_QUANTILES
    {
        return ForecastStatus::NonFinite; // structurally broken ⇒ fail closed
    }

    // (1) Non-finite anywhere.
    let finite = f.median_risk.iter().all(|v| v.is_finite())
        && f.median_bg.iter().all(|v| v.is_finite())
        && f.q_tau_risk.iter().all(|v| v.is_finite())
        && f.bands_mgdl.iter().all(|v| v.is_finite());
    if !finite {
        return ForecastStatus::NonFinite;
    }

    // (2) Mis-ordered fan (risk space, the rawer signal before the f_inv clamp).
    for i in 0..n {
        let row = i * N_QUANTILES;
        for k in 1..N_QUANTILES {
            if f.q_tau_risk[row + k] < f.q_tau_risk[row + k - 1] - MONOTONE_TOL {
                return ForecastStatus::MisorderedQuantiles;
            }
        }
    }

    // (3) Rail-pinned flat median.
    let all_low = f.median_bg.iter().all(|&v| v <= BG_CLAMP_MIN + RAIL_EPS_MGDL);
    let all_high = f.median_bg.iter().all(|&v| v >= BG_CLAMP_MAX - RAIL_EPS_MGDL);
    if all_low || all_high {
        return ForecastStatus::RailPinned;
    }

    // (4) Collapsed band: the widest step is still near-zero width.
    let max_width = (0..n)
        .map(|i| {
            let row = i * N_QUANTILES;
            f.bands_mgdl[row + N_QUANTILES - 1] - f.bands_mgdl[row]
        })
        .fold(0.0f64, f64::max);
    if max_width < COLLAPSE_EPS_MGDL {
        return ForecastStatus::CollapsedBand;
    }

    ForecastStatus::Ok
}

// ── Time-probe decode (circadian-phase belief) ──────────────────────────────────────

/// The decoded hour-of-day belief of the co-trained TIME PROBE (the second `.pte`
/// output). [`probs`] is the `n_bins`-long softmax of the ORIGIN prediction patch's
/// logits; [`predicted_hour`] is the mean-resultant hour in `[0,24)`; [`resultant_r`] is
/// the resultant length in `[0,1]` = the circular concentration (confidence). This is the
/// model's belief about **what hour-of-day it currently is** (a circadian phase), NOT a
/// per-forecast-step timestamp — a predicted-time axis is this hour plus the step offset.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct PredictedTime {
    pub probs: Vec<f64>,
    pub predicted_hour: f64,
    pub resultant_r: f64,
    pub n_bins: i32,
    pub bin_hours: f64,
}

/// Numerically-stable softmax over `logits` (shift by the max). An empty slice yields an
/// empty vector; a zero/underflowing partition falls back to uniform so no NaN escapes.
fn softmax(logits: &[f64]) -> Vec<f64> {
    let n = logits.len();
    if n == 0 {
        return Vec::new();
    }
    let max = logits.iter().copied().fold(f64::NEG_INFINITY, f64::max);
    let mut exps: Vec<f64> = logits.iter().map(|&l| (l - max).exp()).collect();
    let sum: f64 = exps.iter().sum();
    if sum > 0.0 && sum.is_finite() {
        for e in &mut exps {
            *e /= sum;
        }
    } else {
        let u = 1.0 / n as f64;
        for e in &mut exps {
            *e = u;
        }
    }
    exps
}

/// Port of `utils.time_of_day_resultant`: the probability-weighted mean resultant vector
/// over the hour-of-day circle. Bin `k` sits at center hour `bin_hours·(k + 0.5)` mapped
/// to angle `θ_k = 2π·center/24`; the resultant is `(Σ pₖcosθₖ, Σ pₖsinθₖ)`. Returns
/// `(hour, R)` with `hour = (atan2(sin,cos) mod 2π)·24/2π ∈ [0,24)` and
/// `R = hypot(cos,sin) ∈ [0,1]`. At `R → 0` the resultant vanishes and `hour` is FP-noise
/// (the caller treats it as undefined via [`R_DEGENERATE_EPS`]).
fn time_of_day_resultant(probs: &[f64], bin_hours: f64) -> (f64, f64) {
    let mut c = 0.0f64;
    let mut s = 0.0f64;
    for (k, &p) in probs.iter().enumerate() {
        let center = bin_hours * (k as f64 + 0.5);
        let theta = std::f64::consts::TAU * center / 24.0;
        c += p * theta.cos();
        s += p * theta.sin();
    }
    let r = c.hypot(s);
    let mut ang = s.atan2(c); // (-π, π]
    if ang < 0.0 {
        ang += std::f64::consts::TAU;
    }
    let hour = ang * 24.0 / std::f64::consts::TAU;
    (hour, r)
}

/// Decode the time-probe's per-prediction-patch logits (`time_logits`, flat row-major
/// `(P, n_bins)` = the `.pte` slot-1 tensor) into a single hour-of-day belief per the
/// declared `origin_patch` reduction: softmax the ORIGIN patch (index 0) and take its mean
/// resultant. Total on hostile input — a non-multiple length, a zero `n_bins`, or a
/// non-finite logit yields `Err` (the caller maps it to a null predicted-time, fail-open).
/// Faithful to T1DMAI's `inference.estimate_current_hour` (`time_pred[:,0,:]`).
#[uniffi::export]
pub fn decode_time(time_logits: Vec<f64>, n_bins: i32, bin_hours: f64) -> Result<PredictedTime, CoreError> {
    if n_bins <= 0 {
        return Err(CoreError::Decode {
            reason: format!("n_bins {n_bins} must be > 0"),
        });
    }
    let nb = n_bins as usize;
    if time_logits.is_empty() || time_logits.len() % nb != 0 {
        return Err(CoreError::Decode {
            reason: format!(
                "time_logits length {} is not a positive multiple of n_bins {nb}",
                time_logits.len()
            ),
        });
    }
    if !(bin_hours.is_finite() && bin_hours > 0.0) {
        return Err(CoreError::Decode {
            reason: format!("bin_hours {bin_hours} must be finite and > 0"),
        });
    }
    // origin_patch reduction: the first n_bins entries are prediction patch 0.
    let patch0 = &time_logits[0..nb];
    if patch0.iter().any(|v| !v.is_finite()) {
        return Err(CoreError::Decode {
            reason: "time_logits[origin_patch] contains a non-finite value".to_string(),
        });
    }
    let probs = softmax(patch0);
    let (hour, r) = time_of_day_resultant(&probs, bin_hours);
    Ok(PredictedTime {
        probs,
        predicted_hour: hour,
        resultant_r: r,
        n_bins,
        bin_hours,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    const GOLDEN: &str = include_str!("testdata/golden.json");

    fn golden() -> Value {
        serde_json::from_str(GOLDEN).unwrap()
    }

    fn f64s(v: &Value) -> Vec<f64> {
        v.as_array().unwrap().iter().map(|x| x.as_f64().unwrap()).collect()
    }

    fn test_descriptor() -> ModelDescriptor {
        let g = golden();
        let s = &g["stats"];
        let cs = |k: &str| ChannelStat {
            mean: s[k]["mean"].as_f64().unwrap(),
            std: s[k]["std"].as_f64().unwrap(),
        };
        ModelDescriptor {
            bg: cs("bg_absolute"),
            carb: cs("carb_intake"),
            insulin: cs("insulin_combined"),
            rope_base: 1000,
            median_global_dim: 6,
            step_basis_type: "dct".into(),
            quantile_spread_min: 1e-3,
            neg_fill: -30000.0,
            prediction_horizon_hours: 2,
            max_context_patches: 48,
            min_context_patches: 16,
            patch_size: 6,
            n_input_features: 3,
            conformal_enabled: false,
            time: None,
        }
    }

    fn assert_close(got: &[f64], want: &[f64], tol: f64, what: &str) {
        assert_eq!(got.len(), want.len(), "{what}: length mismatch");
        for (i, (g, w)) in got.iter().zip(want).enumerate() {
            assert!(
                (g - w).abs() <= tol,
                "{what}[{i}]: got {g}, want {w} (|Δ|={:.3e} > {tol:.1e})",
                (g - w).abs()
            );
        }
    }

    // ── descriptor.json parses to the pinned constants ──────────────────────────────
    #[test]
    fn parse_descriptor_reference() {
        let json = include_str!("../../../models/descriptor.json");
        let d = parse_descriptor(json.to_string()).expect("reference descriptor must parse");
        assert_eq!(d.rope_base, 1000);
        assert_eq!(d.median_global_dim, 6);
        assert_eq!(d.step_basis_type, "dct");
        assert_eq!(d.quantile_spread_min, 1e-3);
        assert_eq!(d.neg_fill, -30000.0);
        assert_eq!(d.prediction_horizon_hours, 2);
        assert_eq!(d.max_context_patches, 48);
        assert!(!d.conformal_enabled);
        assert!((d.bg.mean - 0.44047466508264904).abs() < 1e-15);
        assert!((d.insulin.std - 0.09111449226803763).abs() < 1e-15);
        assert_eq!(d.prediction_patches().unwrap(), 4);
    }

    #[test]
    fn parse_descriptor_rejects_garbage() {
        assert!(matches!(parse_descriptor("{".into()), Err(CoreError::Decode { .. })));
        assert!(matches!(parse_descriptor("{}".into()), Err(CoreError::Decode { .. })));
    }

    // ── #16: parse_descriptor fails closed on decode-critical drift ──────────────────
    #[test]
    fn parse_descriptor_rejects_decode_critical_drift() {
        let base = include_str!("../../../models/descriptor.json");
        // The unmodified reference descriptor passes every guard.
        assert!(
            parse_descriptor(base.to_string()).is_ok(),
            "reference descriptor must still parse"
        );

        // Splice one drifted field into the reference and assert a fail-closed Decode.
        let bad = |key: &str, val: Value| -> Result<ModelDescriptor, CoreError> {
            let mut v: Value = serde_json::from_str(base).unwrap();
            v[key] = val;
            parse_descriptor(v.to_string())
        };
        let is_decode =
            |r: Result<ModelDescriptor, CoreError>| matches!(r, Err(CoreError::Decode { .. }));

        assert!(is_decode(bad("median_global_dim", serde_json::json!(0))));
        assert!(is_decode(bad("median_global_dim", serde_json::json!(-1))));
        assert!(is_decode(bad("patch_size", serde_json::json!(4))));
        assert!(is_decode(bad("neg_fill", serde_json::json!(0.0))));
        assert!(is_decode(bad("neg_fill", serde_json::json!(30000.0))));
        assert!(is_decode(bad("quantile_spread_min", serde_json::json!(-1e-3))));
        // NaN is not representable in JSON → serialized as null → Decode at the parse step.
        assert!(is_decode(bad("quantile_spread_min", serde_json::json!(f64::NAN))));
        assert!(is_decode(bad("min_context_patches", serde_json::json!(0))));
        // max < min (reference min_context_patches == 16).
        assert!(is_decode(bad("max_context_patches", serde_json::json!(8))));
        assert!(is_decode(bad("prediction_horizon_hours", serde_json::json!(0))));
        assert!(is_decode(bad("rope_base", serde_json::json!(0))));
    }

    // ── SAVGOL taps == scipy.savgol_coeffs(7,2,pos=6,use='dot') ──────────────────────
    #[test]
    fn savgol_coeffs_match_scipy() {
        let want = f64s(&golden()["savgol_coeffs"]);
        let got: Vec<f64> = SAVGOL_TAPS.iter().map(|t| t / SAVGOL_DENOM).collect();
        // Our taps are the exact rationals over 42; scipy's lstsq carries ~1e-15 noise.
        assert_close(&got, &want, 1e-12, "savgol_coeffs");
    }

    // ── causal_smooth matches utils.causal_smooth (f32-cast production output) ────────
    #[test]
    fn causal_smooth_matches_production() {
        let g = golden();
        let bg = causal_smooth(f64s(&g["raw_bg"]), Some(20.0), Some(500.0));
        let carb = causal_smooth(f64s(&g["raw_carb"]), Some(0.0), None);
        let ins = causal_smooth(f64s(&g["raw_insulin"]), Some(0.0), None);
        // Production casts to fp32 at the end; we stay fp64 → agree to ~fp32 eps.
        assert_close(&bg, &f64s(&g["smoothed_bg"]), 1e-3, "smoothed_bg");
        assert_close(&carb, &f64s(&g["smoothed_carb"]), 1e-5, "smoothed_carb");
        assert_close(&ins, &f64s(&g["smoothed_insulin"]), 1e-5, "smoothed_insulin");
    }

    // ── normalize matches normalization.normalize (per channel) ──────────────────────
    #[test]
    fn normalize_zero_baseline() {
        let d = test_descriptor();
        let want = f64s(&golden()["normalize_zero"]);
        let got = normalize_sample(&d, 0.0, 0.0, 0.0);
        assert_close(&got, &want, 1e-4, "normalize(0)");
    }

    #[test]
    fn normalize_denormalize_round_trip() {
        let d = test_descriptor();
        for (bg, carb, ins) in [(75.0, 0.0, 0.02), (180.0, 8.0, 0.5), (40.0, 3.0, 0.1)] {
            let z = normalize_sample(&d, bg, carb, ins);
            let back = denormalize_sample(&d, z).unwrap();
            assert!((back[0] - bg).abs() < 1e-6, "bg round-trip {bg} -> {}", back[0]);
            assert!((back[1] - carb).abs() < 1e-6, "carb round-trip");
            assert!((back[2] - ins).abs() < 1e-6, "insulin round-trip");
        }
    }

    // ── build_context: normalized context + last_bg anchor ───────────────────────────
    #[test]
    fn build_context_matches_production() {
        let d = test_descriptor();
        let g = golden();
        let bc = build_context(
            &d,
            f64s(&g["raw_bg"]),
            f64s(&g["raw_carb"]),
            f64s(&g["raw_insulin"]),
            None,
            None,
        )
        .expect("build_context");
        assert_eq!(bc.n_ctx, 16);
        assert_eq!(bc.prediction_patches, 4);
        // context_norm golden is (N,3) row-major == our step-major flatten.
        let want: Vec<f64> = golden()["context_norm"]
            .as_array()
            .unwrap()
            .iter()
            .flat_map(|row| f64s(row))
            .collect();
        assert_close(&bc.context, &want, 1e-4, "context_norm");
        // last_bg anchor (mg/dL).
        let want_last = g["last_bg"].as_f64().unwrap();
        assert!(
            (bc.last_bg - want_last).abs() < 1e-3,
            "last_bg: got {}, want {want_last}",
            bc.last_bg
        );
        // pred zone: BG feat 0 literal 0, dose feats == normalize(0) baseline.
        let z0 = normalize_sample(&d, 0.0, 0.0, 0.0);
        assert_eq!(bc.pred.len(), 4 * 6 * 3);
        for j in 0..(4 * 6) {
            assert_eq!(bc.pred[j * 3], 0.0, "pred BG must be literal 0");
            assert!((bc.pred[j * 3 + 1] - z0[1]).abs() < 1e-12);
            assert!((bc.pred[j * 3 + 2] - z0[2]).abs() < 1e-12);
        }
    }

    #[test]
    fn build_context_announced_doses_override() {
        let d = test_descriptor();
        let g = golden();
        let ann_carb: Vec<f64> = (0..24).map(|i| i as f64 * 0.5).collect();
        let ann_ins: Vec<f64> = (0..24).map(|_| 0.3).collect();
        let bc = build_context(
            &d,
            f64s(&g["raw_bg"]),
            f64s(&g["raw_carb"]),
            f64s(&g["raw_insulin"]),
            Some(ann_carb.clone()),
            Some(ann_ins.clone()),
        )
        .unwrap();
        for j in 0..24 {
            assert!((bc.pred[j * 3 + 1] - normalize_feat(&d, 1, ann_carb[j])).abs() < 1e-12);
            assert!((bc.pred[j * 3 + 2] - normalize_feat(&d, 2, ann_ins[j])).abs() < 1e-12);
        }
    }

    #[test]
    fn build_context_rejects_bad_shapes() {
        let d = test_descriptor();
        // too few patches (n_ctx = 4 < 16)
        assert!(build_context(&d, vec![100.0; 24], vec![0.0; 24], vec![0.0; 24], None, None).is_err());
        // non-multiple of PATCH_SIZE
        assert!(build_context(&d, vec![100.0; 97], vec![0.0; 97], vec![0.0; 97], None, None).is_err());
        // mismatched channels
        assert!(build_context(&d, vec![100.0; 96], vec![0.0; 90], vec![0.0; 96], None, None).is_err());
        // wrong announced length
        assert!(build_context(
            &d,
            vec![100.0; 96],
            vec![0.0; 96],
            vec![0.0; 96],
            Some(vec![0.0; 10]),
            None
        )
        .is_err());
    }

    // ── global-median DCT basis == utils.get_global_median_basis ─────────────────────
    #[test]
    fn dct_basis_matches_production() {
        let want = f64s(&golden()["dct_basis"]); // (24,6) row-major
        let got = global_median_basis(24, 6);
        assert_close(&got, &want, 1e-12, "dct_basis");
    }

    // ── assemble_decode == utils.assemble_quantiles + f_inv (the key golden) ─────────
    #[test]
    fn assemble_decode_matches_production() {
        let d = test_descriptor();
        let g = golden();
        let head_raw = f64s(&g["head_raw"]);
        let last_bg = g["last_bg"].as_f64().unwrap();
        let f = assemble_decode(&d, head_raw, last_bg, 0.0).unwrap();
        assert_close(&f.median_risk, &f64s(&g["median_risk"]), 1e-9, "median_risk");
        assert_close(&f.q_tau_risk, &f64s(&g["q_tau_risk"]), 1e-9, "q_tau_risk");
        assert_close(&f.median_bg, &f64s(&g["median_bg"]), 1e-8, "median_bg");
        assert_close(&f.bands_mgdl, &f64s(&g["bands_mgdl"]), 1e-8, "bands_mgdl");
        // fan is ascending and passes the guard.
        assert_eq!(forecast_degeneracy_check(&f), ForecastStatus::Ok);
    }

    #[test]
    fn assemble_decode_carry_spread() {
        let d = test_descriptor();
        let g = golden();
        let head_raw = f64s(&g["head_raw"]);
        let last_bg = g["last_bg"].as_f64().unwrap();
        let f = assemble_decode(&d, head_raw, last_bg, 0.1).unwrap();
        assert_close(&f.q_tau_risk, &f64s(&g["q_tau_risk_carry0p1"]), 1e-9, "q_tau_carry");
    }

    #[test]
    fn assemble_decode_rejects_bad_shape() {
        let d = test_descriptor();
        assert!(assemble_decode(&d, vec![], 100.0, 0.0).is_err());
        assert!(assemble_decode(&d, vec![0.0; 41], 100.0, 0.0).is_err()); // not a multiple of 42
    }

    // ── forecast_degeneracy_check (§3.6-B) ───────────────────────────────────────────
    #[test]
    fn degeneracy_non_finite() {
        let d = test_descriptor();
        let g = golden();
        let mut f = assemble_decode(&d, f64s(&g["head_raw"]), g["last_bg"].as_f64().unwrap(), 0.0).unwrap();
        f.median_bg[3] = f64::NAN;
        assert_eq!(forecast_degeneracy_check(&f), ForecastStatus::NonFinite);
        let mut f2 = assemble_decode(&d, f64s(&g["head_raw"]), g["last_bg"].as_f64().unwrap(), 0.0).unwrap();
        f2.q_tau_risk[0] = f64::INFINITY;
        assert_eq!(forecast_degeneracy_check(&f2), ForecastStatus::NonFinite);
    }

    #[test]
    fn degeneracy_rail_pinned() {
        // A runaway model: enormous median delta ⇒ every median clamps to 500.
        let d = test_descriptor();
        let mut head = vec![0.0f64; 4 * 6 * 7];
        for i in 0..24 {
            head[i * 7] = 100.0; // huge risk delta
        }
        let f = assemble_decode(&d, head, 120.0, 0.0).unwrap();
        assert!(f.median_bg.iter().all(|&v| (v - 500.0).abs() < 1e-6));
        assert_eq!(forecast_degeneracy_check(&f), ForecastStatus::RailPinned);
    }

    #[test]
    fn degeneracy_collapsed_band() {
        // Construct a zero-width fan directly (all quantiles == median).
        let n = 24;
        let median_risk = vec![-0.2; n];
        let median_bg = vec![100.0; n];
        let mut q = vec![0.0; n * 7];
        let mut b = vec![0.0; n * 7];
        for i in 0..n {
            for k in 0..7 {
                q[i * 7 + k] = -0.2;
                b[i * 7 + k] = 100.0;
            }
        }
        let f = Forecast { median_risk, q_tau_risk: q, median_bg, bands_mgdl: b };
        assert_eq!(forecast_degeneracy_check(&f), ForecastStatus::CollapsedBand);
    }

    #[test]
    fn degeneracy_misordered() {
        let d = test_descriptor();
        let g = golden();
        let mut f = assemble_decode(&d, f64s(&g["head_raw"]), g["last_bg"].as_f64().unwrap(), 0.0).unwrap();
        // Swap two fan entries at step 0 to break monotonicity.
        f.q_tau_risk.swap(0, 6);
        assert_eq!(forecast_degeneracy_check(&f), ForecastStatus::MisorderedQuantiles);
    }

    #[test]
    fn degeneracy_ok_on_healthy_fan() {
        let d = test_descriptor();
        let g = golden();
        let f = assemble_decode(&d, f64s(&g["head_raw"]), g["last_bg"].as_f64().unwrap(), 0.0).unwrap();
        assert_eq!(forecast_degeneracy_check(&f), ForecastStatus::Ok);
    }

    // ── Time-probe decode goldens (testdata/time_head_golden.json) ───────────────────

    const TIME_GOLDEN: &str = include_str!("testdata/time_head_golden.json");

    fn time_golden() -> Value {
        serde_json::from_str(TIME_GOLDEN).unwrap()
    }

    /// Circular |Δhour| on the 24 h clock (wrap-aware).
    fn circ_dhour(a: f64, b: f64) -> f64 {
        let mut d = (a - b).rem_euclid(24.0);
        if d > 12.0 {
            d = 24.0 - d;
        }
        d.abs()
    }

    /// Reproduce softmax + `time_of_day_resultant`/`decode_bins` for every golden row.
    #[test]
    fn time_head_golden_rows() {
        let g = time_golden();
        let bin_hours = g["bin_hours"].as_f64().unwrap();
        let n_bins = g["n_bins"].as_i64().unwrap() as usize;
        let hour_tol = g["hour_tol"].as_f64().unwrap();
        let r_tol = g["R_tol"].as_f64().unwrap();
        let r_deg = g["R_degenerate_eps"].as_f64().unwrap();

        for row in g["rows"].as_array().unwrap() {
            let name = row["name"].as_str().unwrap();
            let logits = f64s(&row["logits"]);
            assert_eq!(logits.len(), n_bins, "{name}: logit count");
            let want_probs = f64s(&row["probs"]);
            let want_hour = row["hour"].as_f64().unwrap();
            let want_r = row["R"].as_f64().unwrap();
            let hour_defined = row["hour_defined"].as_bool().unwrap();

            let probs = softmax(&logits);
            assert_close(&probs, &want_probs, 1e-4, &format!("{name} probs"));

            let (hour, r) = time_of_day_resultant(&probs, bin_hours);
            assert!(
                (r - want_r).abs() <= r_tol,
                "{name}: R got {r}, want {want_r} (|Δ|={:.3e} > {r_tol:.1e})",
                (r - want_r).abs()
            );
            // Sanity: hour_defined agrees with the resultant magnitude.
            assert_eq!(r >= r_deg, hour_defined, "{name}: hour_defined vs R>=eps");
            if hour_defined {
                let dh = circ_dhour(hour, want_hour);
                assert!(dh <= hour_tol, "{name}: hour got {hour}, want {want_hour} (circΔ={dh:.3e} > {hour_tol:.1e})");
            }
        }
    }

    /// `decode_time` reduces to the ORIGIN patch (index 0): a flat (P, n_bins) buffer built
    /// from the four real model per-patch logit rows must decode to `model_patch0`'s belief.
    #[test]
    fn decode_time_origin_patch_reduction() {
        let g = time_golden();
        let bin_hours = g["bin_hours"].as_f64().unwrap();
        let n_bins = g["n_bins"].as_i64().unwrap() as i32;
        let rows = g["rows"].as_array().unwrap();
        let patch_rows: Vec<&Value> = rows
            .iter()
            .filter(|r| r["name"].as_str().unwrap().starts_with("model_patch"))
            .collect();
        assert_eq!(patch_rows.len(), 4, "expected 4 model patch rows");

        // Concatenate all four patches row-major into one (4, n_bins) flat buffer.
        let mut flat: Vec<f64> = Vec::new();
        for r in &patch_rows {
            flat.extend(f64s(&r["logits"]));
        }
        let pt = decode_time(flat, n_bins, bin_hours).expect("decode_time must succeed");

        let want = &patch_rows[0]; // origin = model_patch0
        assert_eq!(pt.n_bins, n_bins);
        assert_eq!(pt.bin_hours, bin_hours);
        assert_close(&pt.probs, &f64s(&want["probs"]), 1e-4, "decode_time probs");
        let dh = circ_dhour(pt.predicted_hour, want["hour"].as_f64().unwrap());
        assert!(dh <= g["hour_tol"].as_f64().unwrap(), "decode_time hour circΔ={dh:.3e}");
        assert!((pt.resultant_r - want["R"].as_f64().unwrap()).abs() <= g["R_tol"].as_f64().unwrap());
    }

    /// `decode_time` is total on hostile input — never panics, always `Err` on bad shape.
    #[test]
    fn decode_time_rejects_bad_input() {
        assert!(decode_time(vec![], 12, 2.0).is_err()); // empty
        assert!(decode_time(vec![0.0; 5], 12, 2.0).is_err()); // not a multiple of n_bins
        assert!(decode_time(vec![0.0; 12], 0, 2.0).is_err()); // zero n_bins
        assert!(decode_time(vec![0.0; 12], 12, 0.0).is_err()); // zero bin_hours
        let mut nan = vec![0.0; 12];
        nan[3] = f64::NAN;
        assert!(decode_time(nan, 12, 2.0).is_err()); // non-finite origin logit
    }

    /// The descriptor's optional time section parses when present and is `None` when absent.
    #[test]
    fn parse_descriptor_time_section() {
        // Absent ⇒ None (the reference flat descriptor carries no time probe).
        let base = include_str!("../../../models/descriptor.json");
        assert!(parse_descriptor(base.to_string()).unwrap().time.is_none());

        // Present ⇒ parsed. Splice a flat "time" object into the reference descriptor.
        let mut v: Value = serde_json::from_str(base).unwrap();
        v["time"] = serde_json::json!({ "output_index": 1, "n_bins": 12, "bin_hours": 2.0 });
        let d = parse_descriptor(v.to_string()).unwrap();
        let t = d.time.expect("time section must parse");
        assert_eq!(t.output_index, 1);
        assert_eq!(t.n_bins, 12);
        assert_eq!(t.bin_hours, 2.0);

        // A degenerate time section (n_bins ≤ 0) fails closed.
        v["time"] = serde_json::json!({ "output_index": 1, "n_bins": 0, "bin_hours": 2.0 });
        assert!(parse_descriptor(v.to_string()).is_err());
    }
}
