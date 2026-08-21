//! Model pre/post-processing — the fp64 reference pipeline (Phase 2, INFERENCE.md).
//!
//! The ExecuTorch graph is cut at `head_raw` (B,4,6,7) in risk space; everything on
//! either side of that cut lives here, in fp64 Rust, as the numerical authority
//! (§2.4/§3.2). The descriptor JSON is the SOLE source of the
//! normalization stats and the checkpoint-absent decode constants — the app never
//! parses the `.pt` pickle.
//!
//! Pipeline (INFERENCE.md §§6-8):
//!   raw channels ──savgol (BG)──> ──normalize──> context (z) ──graph──> head_raw
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

// Deliberately does NOT import the crate's clinical `kovatchev_f`/`kovatchev_f_inv`: every
// (b)↔(c) crossing on the model path goes through the descriptor's own `KovatchevParams`,
// and having the clinical pair in scope here is how they get reached for by accident.
use crate::CoreError;

// ── Fixed architecture constants (INFERENCE.md §11; not descriptor-varying) ─────────
/// Steps per patch (6 × 5 min = 30 min).
const PATCH_SIZE: usize = 6;
/// Input feature stack width
/// `[bg_absolute, carb_intake, insulin_combined, exercise_equiv, bg_masked]`.
const N_FEAT: usize = 5;
/// The normalized SIGNAL channels — feats 0..3. Feat 4 carries no statistics.
const N_CHANNELS: usize = 4;
/// Feature index of the per-patch masked-announcement bit. Nothing else writes it, and a
/// builder that forgets it announces every masked patch as an observation with every shape
/// still matching and every fan still monotone.
const BG_MASKED_FEAT: usize = 4;
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
/// The default window — what every build applied unconditionally before the filter became
/// configurable, and the value the goldens and the fp16-agreement probe are pinned to.
const SAVGOL_WINDOW: usize = 7;
/// Largest accepted window. Well past the widest offered detent (25) yet small enough that a
/// hostile value can never provoke a giant tap allocation before the odd/positive guards bite.
const SAVGOL_WINDOW_MAX: i32 = 99;

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

/// The Kovatchev risk parameterization **the exported checkpoint was trained under**, read
/// verbatim from the descriptor's `kovatchev` block (INFERENCE.md §5).
///
/// A checkpoint re-anchored to a different physical BG range ships different constants, so
/// these cannot be baked into the runtime: decoding risk-space output with the wrong
/// `scale`/`offset` yields plausible, finite, wrong mg/dL — silently, with no guard able to
/// see it. The crate's free `kovatchev_f`/`kovatchev_f_inv` are the separate, deliberately
/// fixed CLINICAL scale (LBGI/HBGI, display axis) and must never decode a forecast.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record, Deserialize)]
pub struct KovatchevParams {
    #[serde(rename = "SCALE")]
    pub scale: f64,
    #[serde(rename = "POWER")]
    pub power: f64,
    #[serde(rename = "OFFSET")]
    pub offset: f64,
    /// Lower physical BG bound (mg/dL); `f` clamps to it and `f_inv` cannot decode below it.
    #[serde(rename = "BG_CLAMP_MIN")]
    pub bg_clamp_min: f64,
    /// Upper physical BG bound (mg/dL).
    #[serde(rename = "BG_CLAMP_MAX")]
    pub bg_clamp_max: f64,
}

impl KovatchevParams {
    /// `f(g) = scale·(ln(g)^power − offset)`, mg/dL → risk. Total on hostile input with the
    /// same guard order as the clinical [`crate::kovatchev_f`]: clamp to the physical range
    /// first (so the `ln` base is positive), NaN treated as the low bound.
    pub(crate) fn f(&self, mgdl: f64) -> f64 {
        let g = if mgdl.is_nan() {
            self.bg_clamp_min
        } else {
            mgdl.clamp(self.bg_clamp_min, self.bg_clamp_max)
        };
        self.scale * (g.ln().powf(self.power) - self.offset)
    }

    /// `f_inv(r) = exp((r/scale + offset)^(1/power))`, risk → mg/dL. Non-finite risk is
    /// replaced (NaN/−inf → the low rail, +inf → the high rail), the risk input is clamped to
    /// `[f(min), f(max)]` — keeping the base ≥ 0, so no complex/NaN and no `exp` overflow —
    /// and the output is clamped to the physical range.
    pub(crate) fn f_inv(&self, risk: f64) -> f64 {
        let r_lo = self.f(self.bg_clamp_min);
        let r_hi = self.f(self.bg_clamp_max);
        let r = if risk.is_nan() || risk == f64::NEG_INFINITY {
            r_lo
        } else if risk == f64::INFINITY {
            r_hi
        } else {
            risk
        };
        let r = r.clamp(r_lo, r_hi);
        let base = r / self.scale + self.offset; // ≥ 0 by the clamp above
        let mgdl = base.powf(1.0 / self.power).exp();
        mgdl.clamp(self.bg_clamp_min, self.bg_clamp_max)
    }
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

/// One tensor in the head side file, named and shaped in FILE order.
#[derive(Debug, Clone, PartialEq, uniffi::Record, Deserialize)]
pub struct HeadTensorSpec {
    pub name: String,
    pub shape: Vec<i32>,
}

/// The BG head the export wrote beside the artifact — the seam an adapter attaches to.
///
/// Everything needed to read a flat fp32 dump back into layers, plus a digest over the exact
/// bytes. The digest is checked on load rather than recorded: a head paired with the wrong
/// graph reproduces a plausible, finite, wrong `head_raw`, and nothing downstream can see it.
#[derive(Debug, Clone, PartialEq, uniffi::Record, Deserialize)]
pub struct HeadSpec {
    pub file: String,
    pub dtype: String,
    pub byte_order: String,
    pub activation: String,
    pub sha256: String,
    pub d_model: i32,
    pub hidden: i32,
    pub step_basis_dim: i32,
    pub out_dim: i32,
    pub tensors: Vec<HeadTensorSpec>,
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
    /// Carbohydrate-EQUIVALENT glucose disposal, g/step — a positive magnitude in its own
    /// channel, never a negative carbohydrate value in the carb channel.
    pub exercise: ChannelStat,
    /// RoPE base frequency (checkpoint-absent; descriptor-carried).
    pub rope_base: i32,
    /// Global-median DCT subspace dimension at a span of `PREDICTION_PATCHES`; shorter spans scale
    /// down from it (`global_median_dim`). Descriptor-carried — it is a training-time choice.
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
    /// The exported graph's fixed sequence length `T`. The window is left-padded into it,
    /// so the future patches always sit at the right edge and the absolute RoPE positions
    /// match training.
    pub seq_len: i32,
    /// `M` — the head's slot count, and the cap on the masked set a caller may ask for.
    pub max_masked_patches: i32,
    /// Spans per masked set, and the longest span, that the training sampler ever drew.
    /// Beyond either the model is being asked for something it never saw.
    pub mask_max_spans: i32,
    pub mask_span_max: i32,
    /// Trunk width — the length of one slot's hidden state.
    pub d_model: i32,
    /// `K` — within-patch basis columns the head emits per (slot, channel).
    pub step_basis_dim: i32,
    /// The risk transform THIS checkpoint was trained under — the sole authority for every
    /// (b)↔(c) crossing on the model path (INFERENCE.md §5).
    pub kovatchev: KovatchevParams,
    /// Split-conformal band recalibration flag. **Default false** for real-CGM
    /// deployment (INFERENCE.md §8.4 — a simulator-fit delta must never silently
    /// narrow the safety bands).
    pub conformal_enabled: bool,
    /// The co-trained hour-of-day time-probe descriptor, or `None` when the exported graph
    /// is cut at `head_raw` (BG fan only). Consumed by [`decode_time`] to surface a
    /// circadian-phase belief; its absence is graceful (no predicted-hour rendered).
    pub time: Option<TimeHead>,
    /// The BG head shipped beside the artifact, or `None` when the export wrote none. Its
    /// absence costs no forecast — the graph's own `head_raw` is the fast path — but it is
    /// the only seam an adapter can attach to, so a model without it takes no LoRA.
    pub head: Option<HeadSpec>,
}

impl ModelDescriptor {
    fn stat(&self, feat: usize) -> ChannelStat {
        match feat {
            0 => self.bg,
            1 => self.carb,
            2 => self.insulin,
            3 => self.exercise,
            _ => self.bg, // unreachable: feat ∈ [0, N_CHANNELS)
        }
    }

    /// P = PREDICTION_PATCHES = horizon_hours · (60 / (patch_size · 5)).
    pub(crate) fn prediction_patches(&self) -> Result<usize, CoreError> {
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
    /// REQUIRED. A descriptor without it predates the exercise channel, and running such a
    /// model against a five-feature input builds a context it was never trained on.
    exercise_equiv: ChannelStat,
}

#[derive(Deserialize)]
struct TimeDto {
    output_index: i32,
    n_bins: i32,
    bin_hours: f64,
}

#[derive(Deserialize)]
struct ConformalDto {
    #[serde(default)]
    enabled: bool,
}

/// The exporter's `geometry` block, verbatim. The SCREAMING names are the exporter's, kept
/// so the descriptor has ONE schema across the suite rather than a projected second one:
/// every projection is a place a key can be silently dropped, and a dropped decode constant
/// is invisible until a forecast decodes wrong.
#[derive(Deserialize)]
struct GeometryDto {
    #[serde(rename = "T")]
    t: i32,
    #[serde(rename = "PATCH_SIZE")]
    patch_size: i32,
    #[serde(rename = "N_INPUT_FEATURES")]
    n_input_features: i32,
    #[serde(rename = "MIN_CONTEXT_PATCHES")]
    min_context_patches: i32,
    #[serde(rename = "MAX_CONTEXT_PATCHES")]
    max_context_patches: i32,
    #[serde(rename = "MAX_MASKED_PATCHES")]
    max_masked_patches: i32,
    #[serde(rename = "D_MODEL")]
    d_model: i32,
    #[serde(rename = "MASK_MAX_SPANS", default = "default_mask_max_spans")]
    mask_max_spans: i32,
    #[serde(rename = "MASK_SPAN_LENGTHS", default)]
    mask_span_lengths: Vec<i32>,
}

#[derive(Deserialize)]
struct ConstantsDto {
    #[serde(rename = "ROPE_BASE")]
    rope_base: i32,
    #[serde(rename = "BG_HEAD_MEDIAN_GLOBAL_DIM")]
    median_global_dim: i32,
    #[serde(rename = "BG_HEAD_MEDIAN_MODE")]
    median_mode: String,
    #[serde(rename = "BG_HEAD_STEP_BASIS_TYPE")]
    step_basis_type: String,
    #[serde(rename = "BG_HEAD_STEP_BASIS_DIM")]
    step_basis_dim: i32,
    #[serde(rename = "BG_QUANTILE_SPREAD_MIN")]
    quantile_spread_min: f64,
    neg_fill: f64,
    #[serde(rename = "PREDICTION_HORIZON_HOURS")]
    prediction_horizon_hours: i32,
}

#[derive(Deserialize)]
struct DescriptorDto {
    normalization_stats: NormStatsDto,
    geometry: GeometryDto,
    constants: ConstantsDto,
    /// REQUIRED. Absent ⇒ the descriptor is rejected rather than decoded against a guessed
    /// scale: there is no safe default, and a wrong one is invisible downstream.
    kovatchev: KovatchevParams,
    #[serde(default)]
    conformal: Option<ConformalDto>,
    #[serde(default)]
    time: Option<TimeDto>,
    #[serde(default)]
    head: Option<HeadSpec>,
}

fn default_mask_max_spans() -> i32 {
    3
}

/// Parse a model `descriptor.json` (SPEC §2.4) into a [`ModelDescriptor`]. Returns
/// `Err(CoreError::Decode)` — never panics — on malformed JSON or a missing field.
#[uniffi::export]
pub fn parse_descriptor(json: String) -> Result<ModelDescriptor, CoreError> {
    let d: DescriptorDto = serde_json::from_str(&json).map_err(|e| CoreError::Decode {
        reason: format!("descriptor parse: {e}"),
    })?;
    if d.geometry.n_input_features as usize != N_FEAT {
        return Err(CoreError::Decode {
            reason: format!(
                "n_input_features {} != {N_FEAT}; this build reads the five-feature masked-BG \
                 input and cannot run an earlier architecture",
                d.geometry.n_input_features
            ),
        });
    }
    // Only the 'global' median is implemented here. A checkpoint trained under another mode
    // decodes to a DIFFERENT median through this one — smooth, finite and wrong — so the
    // descriptor's own declaration is checked rather than carried and ignored.
    if d.constants.median_mode != "global" {
        return Err(CoreError::Decode {
            reason: format!(
                "unsupported BG_HEAD_MEDIAN_MODE {:?} (this build assembles the 'global' median)",
                d.constants.median_mode
            ),
        });
    }
    if d.constants.step_basis_type != "dct" {
        return Err(CoreError::Decode {
            reason: format!(
                "unsupported step_basis_type {:?} (want \"dct\")",
                d.constants.step_basis_type
            ),
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
    let g = d.geometry;
    let c = d.constants;
    let desc = ModelDescriptor {
        bg: d.normalization_stats.bg_absolute,
        carb: d.normalization_stats.carb_intake,
        insulin: d.normalization_stats.insulin_combined,
        exercise: d.normalization_stats.exercise_equiv,
        rope_base: c.rope_base,
        median_global_dim: c.median_global_dim,
        step_basis_type: c.step_basis_type,
        quantile_spread_min: c.quantile_spread_min,
        neg_fill: c.neg_fill,
        prediction_horizon_hours: c.prediction_horizon_hours,
        max_context_patches: g.max_context_patches,
        min_context_patches: g.min_context_patches,
        patch_size: g.patch_size,
        n_input_features: g.n_input_features,
        seq_len: g.t,
        max_masked_patches: g.max_masked_patches,
        mask_max_spans: g.mask_max_spans,
        mask_span_max: g.mask_span_lengths.iter().copied().max().unwrap_or(8),
        d_model: g.d_model,
        step_basis_dim: c.step_basis_dim,
        kovatchev: d.kovatchev,
        conformal_enabled: d.conformal.map(|x| x.enabled).unwrap_or(false),
        time,
        head: d.head,
    };

    // ── Fail-closed guards on decode-critical descriptor drift (§3.6-B, INFERENCE.md §11) ──
    // A re-exported model whose decode constants diverge from its baked graph is REJECTED
    // here (Decode → a `null` descriptor on the Kotlin side → the model is refused), never
    // silently trusted: several of these degrade into a *confident-flat* forecast that
    // `forecast_degeneracy_check` cannot catch (e.g. `median_global_dim=0` pins the median at
    // the anchor via an empty DCT basis; a negative value casts to `usize::MAX` and defeats
    // the low-frequency contraction).
    for (name, v) in [
        ("seq_len", desc.seq_len),
        ("max_masked_patches", desc.max_masked_patches),
        ("d_model", desc.d_model),
        ("step_basis_dim", desc.step_basis_dim),
        ("mask_max_spans", desc.mask_max_spans),
        ("mask_span_max", desc.mask_span_max),
    ] {
        // A negative dimension casts to a colossal usize downstream and the allocation aborts the
        // process (the crate is `panic = "abort"`), so it is refused here where a refusal is just a
        // skipped model.
        if v < 1 {
            return Err(CoreError::Decode {
                reason: format!("geometry {name} = {v} must be >= 1"),
            });
        }
    }
    if desc.seq_len < desc.max_context_patches {
        return Err(CoreError::Decode {
            reason: format!(
                "seq_len {} cannot hold max_context_patches {}",
                desc.seq_len, desc.max_context_patches
            ),
        });
    }
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
    // The risk transform decodes every forecast, so a malformed one is rejected outright.
    // `bg_clamp_min > 1` keeps `ln(g) > 0`, without which `ln(g)^power` is NaN for a
    // fractional power; `scale > 0` keeps `f` increasing (so `f(min) < f(max)` bracket the
    // f_inv clamp the right way round); `power > 0` keeps `1/power` finite.
    let k = desc.kovatchev;
    if ![k.scale, k.power, k.offset, k.bg_clamp_min, k.bg_clamp_max]
        .iter()
        .all(|v| v.is_finite())
    {
        return Err(CoreError::Decode {
            reason: format!("kovatchev block has a non-finite constant: {k:?}"),
        });
    }
    if k.scale <= 0.0 || k.power <= 0.0 {
        return Err(CoreError::Decode {
            reason: format!("kovatchev scale {} and power {} must be > 0", k.scale, k.power),
        });
    }
    if !(k.bg_clamp_min > 1.0 && k.bg_clamp_min < k.bg_clamp_max) {
        return Err(CoreError::Decode {
            reason: format!(
                "kovatchev bounds invalid: require 1 < bg_clamp_min ({}) < bg_clamp_max ({})",
                k.bg_clamp_min, k.bg_clamp_max
            ),
        });
    }
    // Defense in depth: prove the round trip is total at both rails before any forecast rides
    // it (an offset that drives the f_inv base negative would otherwise surface as NaN mg/dL).
    let (r_lo, r_hi) = (k.f(k.bg_clamp_min), k.f(k.bg_clamp_max));
    if !(r_lo.is_finite() && r_hi.is_finite() && r_lo < r_hi)
        || !k.f_inv(r_lo).is_finite()
        || !k.f_inv(r_hi).is_finite()
    {
        return Err(CoreError::Decode {
            reason: format!("kovatchev transform is not total on [{}, {}]", k.bg_clamp_min, k.bg_clamp_max),
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

/// Accept a caller-supplied window: odd, in `[1, SAVGOL_WINDOW_MAX]`. Anything else is a
/// programming error or a corrupt persisted setting and fails closed — an even window has no
/// endpoint-centred abscissa and `w < 1` would index a tap set that does not exist.
fn validate_window(window: i32) -> Result<usize, CoreError> {
    if window < 1 || window > SAVGOL_WINDOW_MAX || window % 2 == 0 {
        return Err(CoreError::Internal {
            reason: format!(
                "savgol window {window} must be odd and in [1, {SAVGOL_WINDOW_MAX}]"
            ),
        });
    }
    Ok(window as usize)
}

/// Endpoint taps for `(window, polyorder = 2)` derived at runtime, oldest→newest, as
/// `(taps, denom)` — the caller divides ONCE after accumulating, so the shipped default keeps
/// its exact integer rationals over 42 and stays bit-identical to the pre-configurable filter
/// (the CPU-unchanged proof compares `head_raw` byte-for-byte across the stock and custom AAR,
/// and `savgol_solver_reproduces_reference_taps` is what pins the `w = 7` fast path to those
/// rationals; the §3.6-E agreement probe is a mg/dL-tolerance GPU-vs-CPU check in ONE process
/// and could not see the difference, since both backends consume the same taps).
///
/// The general branch is the least-squares quadratic fit to `x[t-(w-1) ..= t]` evaluated at the
/// newest sample — `scipy.signal.savgol_coeffs(w, 2, pos=w-1, use='dot')` — solved in closed
/// form rather than by a pseudo-inverse. Centring the abscissa at `m = (w-1)/2` (`u = i - m`)
/// kills the odd power sums, leaving `S0 = w`, `S2 = Σu²`, `S4 = Σu⁴` and a 2×2 solve; the
/// endpoint sits at `u = m`. `w = 1` is the identity (`m = 0` ⇒ `S2 = 0`; scipy likewise
/// rejects `polyorder >= window_length`), i.e. an unfiltered pass-through.
fn savgol_endpoint_taps(window: usize) -> (Vec<f64>, f64) {
    match window {
        1 => (vec![1.0], 1.0),
        SAVGOL_WINDOW => (SAVGOL_TAPS.to_vec(), SAVGOL_DENOM),
        w => (savgol_endpoint_taps_general(w), 1.0),
    }
}

/// The closed-form solver behind [`savgol_endpoint_taps`], already normalized (sums to 1).
/// Callers must not hand it `window < 3` — the `S2` division is singular at `m = 0`.
fn savgol_endpoint_taps_general(window: usize) -> Vec<f64> {
    let m = ((window - 1) / 2) as f64;
    let s0 = window as f64;
    let s2 = m * (m + 1.0) * (2.0 * m + 1.0) / 3.0;
    let s4 = m * (m + 1.0) * (2.0 * m + 1.0) * (3.0 * m * m + 3.0 * m - 1.0) / 15.0;
    let det = s0 * s4 - s2 * s2;
    (0..window)
        .map(|i| {
            let u = i as f64 - m;
            (s4 - s2 * u * u) / det + m * (u / s2) + m * m * (s0 * u * u - s2) / det
        })
        .collect()
}

/// Strictly-causal one-sided Savitzky-Golay smooth of a 1-D signal ([`window`] odd,
/// `polyorder=2`, evaluated at the endpoint). `out[t]` is the degree-2 fit to
/// `x[t-(window-1) ..= t]` read at `t`, so it uses only `x[≤t]` and never leaks the future; the
/// left edge is causally replicated with `x[0]`. `window = 1` is the identity — the raw signal,
/// clamped. Optional physical clamps are applied to the output (BG → `[20,500]`;
/// carb/insulin → `min = 0`); they are NOT part of the filter and hold at every window.
///
/// A wider window suppresses more sensor noise (white-noise variance falls as `Σtap²`) but the
/// endpoint estimator EXTRAPOLATES its quadratic to the edge of its own support, so it settles
/// more slowly after a turn and overshoots a spike further — wider is not unconditionally safer.
///
/// An out-of-contract window (even, `< 1`, or above the accepted ceiling) falls back to the
/// default `SAVGOL_WINDOW` instead of panicking (the crate is `panic = "abort"`); the model
/// input path — [`build_context`] — rejects it outright rather than silently substituting.
#[uniffi::export]
pub fn causal_smooth(
    series: Vec<f64>,
    clamp_min: Option<f64>,
    clamp_max: Option<f64>,
    window: i32,
) -> Vec<f64> {
    let w = validate_window(window).unwrap_or(SAVGOL_WINDOW);
    causal_smooth_w(&series, clamp_min, clamp_max, w)
}

/// The validated-window kernel behind [`causal_smooth`].
fn causal_smooth_w(
    series: &[f64],
    clamp_min: Option<f64>,
    clamp_max: Option<f64>,
    window: usize,
) -> Vec<f64> {
    let n = series.len();
    let mut out = vec![0.0f64; n];
    if n == 0 {
        return out;
    }
    let (taps, denom) = savgol_endpoint_taps(window);
    for t in 0..n {
        let mut acc = 0.0f64;
        for (j, &tap) in taps.iter().enumerate() {
            // window sample = x[t - (W-1) + j], replicated with x[0] on the left edge.
            let idx = t as isize - (window as isize - 1) + j as isize;
            let xv = if idx < 0 { series[0] } else { series[idx as usize] };
            acc += tap * xv;
        }
        let mut v = acc / denom;
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
        d.kovatchev.f(x) // clamps to the descriptor's range and scrubs NaN internally
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
        d.kovatchev.f_inv(v) // risk → mg/dL, clamped to the descriptor's range
    } else {
        v.exp_m1().max(0.0)
    }
}

/// Normalize a raw `[bg_mgdl, carb, insulin, exercise]` sample to z-space (INFERENCE.md §6).
#[uniffi::export]
pub fn normalize_sample(
    desc: &ModelDescriptor,
    bg: f64,
    carb: f64,
    insulin: f64,
    exercise: f64,
) -> Vec<f64> {
    vec![
        normalize_feat(desc, 0, bg),
        normalize_feat(desc, 1, carb),
        normalize_feat(desc, 2, insulin),
        normalize_feat(desc, 3, exercise),
    ]
}

/// Denormalize a z-space `[bg, carb, insulin, exercise]` sample back to raw units.
#[uniffi::export]
pub fn denormalize_sample(desc: &ModelDescriptor, z: Vec<f64>) -> Result<Vec<f64>, CoreError> {
    if z.len() != N_CHANNELS {
        return Err(CoreError::Internal {
            reason: format!("denormalize_sample expects {N_CHANNELS} channels, got {}", z.len()),
        });
    }
    Ok((0..N_CHANNELS).map(|f| denormalize_feat(desc, f, z[f])).collect())
}

// ── Context construction (INFERENCE.md §7) ──────────────────────────────────────────

/// One masked span over the window, in CONTEXT-relative patch coordinates: patch 0 is the
/// oldest real context patch the caller supplied, whatever left-padding lands in front of it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct MaskSpan {
    pub start_patch: i32,
    pub length: i32,
}

/// The complete fixed-shape graph input, built here so the mask rule, the left-pad and the
/// masked-patch fill exist once. Kotlin copies the three float buffers into direct NIO
/// buffers and hands them to the backend unchanged; nothing on that side reasons about
/// geometry.
///
/// `patches` is `T·PATCH_SIZE·N_FEAT` step-major (`flat = (patch·PATCH_SIZE + step)·N_FEAT +
/// feat`), `attn_mask` is `T·T` additive (`0` attend / `neg_fill` block), `slot_sel` is
/// `M·T` one-hot rows. `anchors` and `slot_patch` describe all `M` slots; only the first
/// `n_masked` are real, and `assemble_decode` decodes exactly those.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct GraphInput {
    pub n_ctx: i32,
    pub t: i32,
    pub patch_dim: i32,
    pub m_slots: i32,
    pub n_masked: i32,
    pub patches: Vec<f32>,
    pub attn_mask: Vec<f32>,
    pub slot_sel: Vec<f32>,
    pub anchors: Vec<f64>,
    pub slot_patch: Vec<i32>,
    /// Absolute patch index of the first future patch, or `-1` when the window carries no
    /// future zone (a pure infill or backcast, which forecasts nothing).
    pub first_forecast_patch: i32,
}

/// Validate the masked set over a window of `n_real` real patches, `n_ctx` of them observed.
///
/// Four rules, each a correctness requirement:
///
/// * spans never abut — one visible patch must separate neighbours, and that separator is
///   what makes the anchor, the per-span median basis and the span grouping well defined.
///   Two spans with nothing between them ARE one longer span, and the sampler this mirrors
///   never emitted that pair.
/// * `sum(length) <= M` — the head has that many slots.
/// * every future patch is masked. There is no observed BG there at all, so a future patch
///   left visible announces a fabricated `z = 0` as an observation.
/// * at least one patch stays visible, since every span anchors on a visible neighbour.
fn resolve_mask_spans(
    desc: &ModelDescriptor,
    spans: &[MaskSpan],
    n_ctx: usize,
    n_real: usize,
) -> Result<Vec<(usize, usize)>, CoreError> {
    let mut out: Vec<(usize, usize)> = Vec::with_capacity(spans.len() + 1);
    for sp in spans {
        if sp.length < 1 {
            return Err(CoreError::Internal {
                reason: format!("mask span length {} must be >= 1", sp.length),
            });
        }
        if sp.start_patch < 0 || (sp.start_patch as usize) + (sp.length as usize) > n_ctx {
            return Err(CoreError::Internal {
                reason: format!(
                    "mask span ({}, {}) leaves the {n_ctx} observed context patches",
                    sp.start_patch, sp.length
                ),
            });
        }
        out.push((sp.start_patch as usize, sp.length as usize));
    }
    out.sort_by_key(|(s, _)| *s);
    if n_real > n_ctx {
        out.push((n_ctx, n_real - n_ctx)); // the future zone, masked by construction
    }
    if out.is_empty() {
        return Err(CoreError::Internal {
            reason: "masked set is empty — the head must be given at least one masked patch"
                .into(),
        });
    }
    let mut prev_end: i64 = -2;
    for (start, length) in &out {
        if (*start as i64) <= prev_end + 1 {
            return Err(CoreError::Internal {
                reason: format!(
                    "masked spans abut or overlap at patch {start}; one visible patch must \
                     separate neighbours"
                ),
            });
        }
        prev_end = (*start + *length - 1) as i64;
    }
    let total: usize = out.iter().map(|(_, l)| *l).sum();
    if total > desc.max_masked_patches as usize {
        return Err(CoreError::Internal {
            reason: format!(
                "masked set of {total} patches exceeds the head's {} slots",
                desc.max_masked_patches
            ),
        });
    }
    if total >= n_real {
        return Err(CoreError::Internal {
            reason: "every patch of the window is masked; nothing is left to anchor on".into(),
        });
    }
    Ok(out)
}

/// Build the whole fixed-shape graph input from raw per-step history (INFERENCE.md §§7.2-7.4).
///
/// `bg`/`carb`/`insulin`/`exercise` are equal-length trailing series of `n_ctx·PATCH_SIZE`
/// steps. `exercise` is a carbohydrate-EQUIVALENT disposal in g/step — a positive magnitude
/// in its own channel, on the scale the model was trained at, never an intensity and never a
/// negative carbohydrate.
///
/// `with_forecast` appends the `P` future patches at the right edge, masked, with the dose
/// channels at the announced plan or the `normalize(0)` no-event baseline. Without it the
/// window is pure history and forecasts nothing — which is what a gap repair wants, since the
/// evidence on BOTH sides of the gap is then real.
///
/// `mask_spans` names the withheld context patches, empty for a plain forecast.
///
/// `smoothing_window` selects the causal Savitzky-Golay window applied to the **BG channel
/// only** (odd, `1` = unfiltered); it fails closed on anything else rather than substituting a
/// default, because this is the model-input path. Carb, insulin and exercise are passed
/// unfiltered: they are analytic reconstructions (gamma / Bateman / exponential action
/// curves), already smooth by construction, and filtering them only blunts the onset of a
/// meal, a dose or a bout. Both keep their physical guards — BG clamped to the descriptor's
/// range, the other three floored at 0 by `normalize_feat`'s `log1p` — at every window.
///
/// The window MOVES every anchor, since each is read off a smoothed BG cell, so it is
/// decision-relevant and not a display preference.
#[allow(clippy::too_many_arguments)]
#[uniffi::export]
pub fn build_graph_input(
    desc: &ModelDescriptor,
    bg: Vec<f64>,
    carb: Vec<f64>,
    insulin: Vec<f64>,
    exercise: Vec<f64>,
    announced_carb: Option<Vec<f64>>,
    announced_insulin: Option<Vec<f64>>,
    announced_exercise: Option<Vec<f64>>,
    mask_spans: Vec<MaskSpan>,
    with_forecast: bool,
    smoothing_window: i32,
) -> Result<GraphInput, CoreError> {
    let bg_window = validate_window(smoothing_window)?;
    let n = bg.len();
    if n == 0 || carb.len() != n || insulin.len() != n || exercise.len() != n {
        return Err(CoreError::Internal {
            reason: format!(
                "channel lengths must match and be > 0: bg={} carb={} insulin={} exercise={}",
                n,
                carb.len(),
                insulin.len(),
                exercise.len()
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
    let t = desc.seq_len as usize;
    let p = desc.prediction_patches()?;
    let n_real = if with_forecast { n_ctx + p } else { n_ctx };
    if n_real > t {
        return Err(CoreError::Internal {
            reason: format!("window of {n_real} patches does not fit the graph's T={t}"),
        });
    }
    let m = desc.max_masked_patches as usize;
    let spans = resolve_mask_spans(desc, &mask_spans, n_ctx, n_real)?;
    let pred_steps = p * PATCH_SIZE;
    for (name, a) in [
        ("announced_carb", &announced_carb),
        ("announced_insulin", &announced_insulin),
        ("announced_exercise", &announced_exercise),
    ] {
        if let Some(v) = a {
            if !with_forecast {
                return Err(CoreError::Internal {
                    reason: format!("{name} supplied for a window with no future zone"),
                });
            }
            if v.len() != pred_steps {
                return Err(CoreError::Internal {
                    reason: format!("{name} length {} != P·S {pred_steps}", v.len()),
                });
            }
        }
    }

    // Pre-filter BG at the requested window — PER VISIBLE RUN, not across the whole series.
    //
    // The filter is causal, so a step's value is a weighted sum of the six before it. Run it over
    // the raw series and the six steps after a masked span carry that span's own withheld BG into
    // the model's input, and the anchor of a span with a right-side neighbour is read from a cell
    // built out of the very values being withheld. Both are leaks from the answer into the
    // question: an infill scored that way is scored against evidence it was supposed not to have.
    // Restarting at each boundary is also what a causal filter would do if the data were simply
    // absent, which is what a masked patch means.
    let mut sm_bg = vec![0.0f64; n];
    {
        let mut masked_step = vec![false; n];
        for (start, length) in &spans {
            for q in *start..(*start + *length) {
                if q < n_ctx {
                    for step in 0..PATCH_SIZE {
                        masked_step[q * PATCH_SIZE + step] = true;
                    }
                }
            }
        }
        let mut i = 0usize;
        while i < n {
            if masked_step[i] {
                sm_bg[i] = bg[i];
                i += 1;
                continue;
            }
            let start = i;
            while i < n && !masked_step[i] {
                i += 1;
            }
            let run = causal_smooth_w(
                &bg[start..i],
                Some(desc.kovatchev.bg_clamp_min),
                Some(desc.kovatchev.bg_clamp_max),
                bg_window,
            );
            sm_bg[start..i].copy_from_slice(&run);
        }
    }

    // Normalize the observed context, step-major into the padded patch buffer.
    let pad0 = t - n_real;
    let mut patches = vec![0.0f32; t * PATCH_SIZE * N_FEAT];
    let mut ctx_bg_z = vec![0.0f64; n]; // kept in f64 for the anchor read-back
    for gs in 0..n {
        let z = [
            normalize_feat(desc, 0, sm_bg[gs]),
            normalize_feat(desc, 1, carb[gs]),
            normalize_feat(desc, 2, insulin[gs]),
            normalize_feat(desc, 3, exercise[gs]),
        ];
        ctx_bg_z[gs] = z[0];
        let base = ((pad0 + gs / PATCH_SIZE) * PATCH_SIZE + gs % PATCH_SIZE) * N_FEAT;
        for (f, v) in z.iter().enumerate() {
            patches[base + f] = *v as f32;
        }
    }

    // The future zone: BG withheld, dose channels at the announced plan or the no-event
    // baseline. A literal z = 0 there routes through the sparse log1p inverse and announces a
    // phantom dose, which is why the baseline is normalize(0) and not zero.
    if with_forecast {
        let zbase: [f64; 3] = [
            normalize_feat(desc, 1, 0.0),
            normalize_feat(desc, 2, 0.0),
            normalize_feat(desc, 3, 0.0),
        ];
        for j in 0..pred_steps {
            let patch = pad0 + n_ctx + j / PATCH_SIZE;
            let base = (patch * PATCH_SIZE + j % PATCH_SIZE) * N_FEAT;
            patches[base] = 0.0; // BG: what the model predicts
            for (k, announced) in [&announced_carb, &announced_insulin, &announced_exercise]
                .iter()
                .enumerate()
            {
                patches[base + 1 + k] = match announced {
                    Some(a) => normalize_feat(desc, 1 + k, a[j]) as f32,
                    None => zbase[k] as f32,
                };
            }
        }
    }

    // Announce the masked set: feat 0 withheld, feat 4 set, on every masked patch.
    let mut visible = vec![true; t];
    for i in 0..pad0 {
        visible[i] = false; // a pad row is neither visible nor masked; is_pad dominates
    }
    let mut slot_patch_real: Vec<i32> = Vec::with_capacity(m);
    for (start, length) in &spans {
        for q in *start..(*start + *length) {
            let patch = pad0 + q;
            visible[patch] = false;
            slot_patch_real.push(patch as i32);
            for step in 0..PATCH_SIZE {
                let base = (patch * PATCH_SIZE + step) * N_FEAT;
                patches[base] = 0.0;
                patches[base + BG_MASKED_FEAT] = 1.0;
            }
        }
    }
    let n_masked = slot_patch_real.len();

    // The attention rule (SPEC/inference.md §4), in the four load-bearing lines.
    let mut attn = vec![desc.neg_fill as f32; t * t];
    for row in 0..t {
        let row_is_pad = row < pad0;
        let row_is_masked = !row_is_pad && !visible[row];
        for col in 0..t {
            let col_is_pad = col < pad0;
            let allow = if row_is_pad || col_is_pad {
                row == col // a pad row reads nothing but itself; no all-False row
            } else {
                visible[col] || row_is_masked
            };
            if allow {
                attn[row * t + col] = 0.0;
            }
        }
    }

    // Slot selection. Surplus slots repeat patch 0 and are discarded downstream.
    let mut slot_sel = vec![0.0f32; m * t];
    let mut slot_patch = vec![0i32; m];
    for j in 0..m {
        let patch = if j < n_masked { slot_patch_real[j] as usize } else { 0 };
        slot_sel[j * t + patch] = 1.0;
        slot_patch[j] = if j < n_masked { slot_patch_real[j] } else { -1 };
    }

    // Per-slot anchors: one-sided and left-preferring — the last step of the span's left
    // neighbour, or the first step of the right neighbour when the left one is padding or
    // does not exist. Only a VISIBLE cell may be named: feat 0 of a masked patch is a
    // legal-looking z that decodes to an ordinary mg/dL, so a wrong index yields a plausible
    // anchor rather than an error.
    let mut anchors = vec![0.0f64; m];
    for (start, length) in &spans {
        let left = *start as i64 - 1;
        let right = *start + *length;
        let cell = if left >= 0 && visible[pad0 + left as usize] {
            Some((left as usize, PATCH_SIZE - 1))
        } else if right < n_ctx && visible[pad0 + right] {
            Some((right, 0))
        } else {
            None
        };
        let (cp, cs) = cell.ok_or_else(|| CoreError::Internal {
            reason: format!(
                "masked span ({start}, {length}) has no visible neighbour to anchor on"
            ),
        })?;
        let z = ctx_bg_z[cp * PATCH_SIZE + cs];
        let a = denormalize_feat(desc, 0, z);
        for j in 0..*length {
            let slot = slot_patch_real
                .iter()
                .position(|&x| x as usize == pad0 + *start + j)
                .expect("span slots were just pushed");
            anchors[slot] = a;
        }
    }
    // Padded slots still need a legal mg/dL anchor: the forward asserts every slot is above
    // the physical floor, and a z-scored value routed in by mistake trips it.
    let fill = anchors[0];
    for a in anchors.iter_mut().skip(n_masked) {
        *a = fill;
    }

    Ok(GraphInput {
        n_ctx: n_ctx as i32,
        t: t as i32,
        patch_dim: (PATCH_SIZE * N_FEAT) as i32,
        m_slots: m as i32,
        n_masked: n_masked as i32,
        patches,
        attn_mask: attn,
        slot_sel,
        anchors,
        slot_patch,
        first_forecast_patch: if with_forecast { (pad0 + n_ctx) as i32 } else { -1 },
    })
}

// ── Global-median DCT basis (INFERENCE.md §8.2) ─────────────────────────────────────

/// DCT-II cosine modes over `n` steps, `g` columns, L2-orthonormalized. Replicates
/// `utils.get_global_median_basis`, INCLUDING its unconditional fp32 round-trip
/// (`.to(float32).to(dtype)`): the columns are normalized in fp64, then each entry is
/// quantized through fp32 — load-bearing for bit-close agreement. Returned row-major
/// `(n, g)`.
pub(crate) fn global_median_basis(n: usize, g: usize) -> Vec<f64> {
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

/// `G_L` — the smooth-basis dimension for a masked span of `L` patches, clamped to the
/// span's own step count. A FIXED `G` is a defect rather than an approximation: at `L = 1`
/// the projection would have a column per step, i.e. the identity, so the anti-drift
/// contraction is ABSENT rather than weakened, and every fan assert still passes.
pub(crate) fn global_median_dim(desc: &ModelDescriptor, span_patches: usize, p: usize) -> usize {
    let num = desc.median_global_dim as usize * span_patches;
    let g = num.div_ceil(p.max(1)).max(1);
    g.min(span_patches * PATCH_SIZE)
}

/// Numerically-stable softplus matching PyTorch `F.softplus` (beta=1, threshold=20).
fn softplus(x: f64) -> f64 {
    if x > SOFTPLUS_THRESHOLD {
        x
    } else {
        x.exp().ln_1p()
    }
}

/// Group `n_masked` slots into contiguous spans. Slot `j` continues slot `j-1`'s span iff
/// their patch indices are adjacent — the masked set never lets two spans abut, so adjacency
/// identifies a span exactly. Returns one `(start_slot, length)` per span.
fn span_layout(slot_patch: &[i32], n_masked: usize) -> Vec<(usize, usize)> {
    let mut spans: Vec<(usize, usize)> = Vec::new();
    for j in 0..n_masked {
        let continues = j > 0 && slot_patch[j] == slot_patch[j - 1] + 1;
        if continues {
            if let Some(last) = spans.last_mut() {
                last.1 += 1;
            }
        } else {
            spans.push((j, 1));
        }
    }
    spans
}

// ── Quantile assembly + decode (INFERENCE.md §8.1-8.3) ──────────────────────────────

/// The decoded forecast. All arrays are step-major over the decoded slots
/// (`i = slot·PATCH_SIZE + step`). `median_risk` / `q_tau_risk` are risk space (the model's
/// native output space); `median_bg` / `bands_mgdl` are the `f_inv` mg/dL projections
/// consumed by rails, alerts and the GUI. `slot_patch` names the absolute patch each decoded
/// slot came from, which is what locates a span on a chart — and, for a masked set holding
/// more than the forecast, what tells an infill row from a forecast row.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Forecast {
    pub median_risk: Vec<f64>,
    pub q_tau_risk: Vec<f64>,
    pub median_bg: Vec<f64>,
    pub bands_mgdl: Vec<f64>,
    pub slot_patch: Vec<i32>,
}

/// Assemble `head_raw` (`M·PATCH_SIZE·7`, risk space) into an ascending quantile fan and
/// decode to mg/dL (INFERENCE.md §8.1, `BG_HEAD_MEDIAN_MODE='global'`).
///
/// The `M` axis is a SET of masked patches, not a trailing horizon: `slot_patch` groups it
/// into contiguous spans and the median runs per span, so nothing accumulates or low-passes
/// across the visible patches between two spans. Each slot anchors on its own span's visible
/// neighbour. Slots past `n_masked` are padding and are dropped.
///
/// The fan returned is the RAW one: §8.4's conformal recalibration is not applied here and is
/// applied to nothing this function feeds — it is a display correction, fitted on device and
/// applied by [`crate::apply_quantile_conformal`] at the last point before pixels. `head_raw`
/// column 0 is the median delta; columns 1..=3 the τ>.5 spreads (nearest→far); 4..=6 the τ<.5
/// spreads. The median is `anchor + proj_DCT(delta)` (a low-frequency L2 contraction that
/// cannot drift); the fan is `m ± carry_spread ± cumsum(softplus+floor)`.
///
/// `carry_spread` is the rolling widening of INFERENCE.md §9, and it is PER LEVEL: empty for
/// none, one value for every level alike, or `2·N_SPREADS` in `head_raw[1..]`'s own layout
/// `[.75 .9 .95 | .25 .1 .05]`. One value shared across the levels re-seeds each of them from
/// the outermost one's accumulation, so the next roll's .75 edge lands outside this roll's .95
/// edge and the fan flattens into a slab — which is why the shape is checked here rather than
/// left to a caller.
#[uniffi::export]
pub fn assemble_decode(
    desc: &ModelDescriptor,
    head_raw: Vec<f64>,
    anchors: Vec<f64>,
    slot_patch: Vec<i32>,
    n_masked: i32,
    carry_spread: Vec<f64>,
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
    let m = head_raw.len() / stride;
    let n_masked = n_masked as usize;
    if n_masked == 0 || n_masked > m {
        return Err(CoreError::Internal {
            reason: format!("n_masked {n_masked} outside 1..={m} head slots"),
        });
    }
    if anchors.len() < n_masked || slot_patch.len() < n_masked {
        return Err(CoreError::Internal {
            reason: format!(
                "need {n_masked} anchors and slot indices, got {} and {}",
                anchors.len(),
                slot_patch.len()
            ),
        });
    }
    let carry: [f64; 2 * N_SPREADS] = match carry_spread.len() {
        0 => [0.0; 2 * N_SPREADS],
        1 => [carry_spread[0]; 2 * N_SPREADS],
        n if n == 2 * N_SPREADS => {
            let mut c = [0.0; 2 * N_SPREADS];
            c.copy_from_slice(&carry_spread);
            c
        }
        n => {
            return Err(CoreError::Internal {
                reason: format!(
                    "carry_spread must hold 0, 1 or {} values (per level), got {n}",
                    2 * N_SPREADS
                ),
            })
        }
    };
    if carry.iter().any(|c| !c.is_finite() || *c < 0.0) {
        return Err(CoreError::Internal {
            reason: "carry_spread must be finite and non-negative on every level".into(),
        });
    }
    let kov = desc.kovatchev;
    let p = desc.prediction_patches()?;
    let floor = desc.quantile_spread_min;
    let n_steps = n_masked * PATCH_SIZE;

    // Median, per span: project that span's per-step delta onto its own low-frequency
    // DCT-II subspace over the span's own L·S steps, patch-major (flat = patch·S + step).
    let mut median = vec![0.0f64; n_steps];
    for (start_slot, length) in span_layout(&slot_patch, n_masked) {
        let n = length * PATCH_SIZE;
        let g = global_median_dim(desc, length, p);
        let basis = global_median_basis(n, g); // (n, g) row-major, fp32-quantized
        let delta: Vec<f64> = (0..n)
            .map(|i| head_raw[(start_slot * PATCH_SIZE + i) * N_QUANTILES])
            .collect();
        let mut zc = vec![0.0f64; g];
        for (j, zj) in zc.iter_mut().enumerate() {
            let mut acc = 0.0f64;
            for (i, d) in delta.iter().enumerate() {
                acc += d * basis[i * g + j];
            }
            *zj = acc;
        }
        for i in 0..n {
            let mut acc = 0.0f64;
            for (j, zj) in zc.iter().enumerate() {
                acc += zj * basis[i * g + j];
            }
            // The anchor is flat across the slot's own steps, and every slot of one span
            // carries the same value.
            let slot = start_slot + i / PATCH_SIZE;
            let anchor = kov.f(anchors[slot].clamp(kov.bg_clamp_min, kov.bg_clamp_max));
            median[start_slot * PATCH_SIZE + i] = anchor + acc;
        }
    }

    // Spreads: softplus + floor, cumsum fan around the median (§8.1).
    let mut median_risk = vec![0.0f64; n_steps];
    let mut q_tau_risk = vec![0.0f64; n_steps * N_QUANTILES];
    let mut median_bg = vec![0.0f64; n_steps];
    let mut bands_mgdl = vec![0.0f64; n_steps * N_QUANTILES];
    for i in 0..n_steps {
        let mi = median[i];
        let mut up = [0.0f64; N_SPREADS];
        let mut dn = [0.0f64; N_SPREADS];
        let mut cs_up = 0.0f64;
        let mut cs_dn = 0.0f64;
        for k in 0..N_SPREADS {
            cs_up += softplus(head_raw[i * N_QUANTILES + 1 + k]) + floor;
            cs_dn += softplus(head_raw[i * N_QUANTILES + 1 + N_SPREADS + k]) + floor;
            up[k] = mi + carry[k] + cs_up;
            dn[k] = mi - carry[N_SPREADS + k] - cs_dn;
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
        median_bg[i] = kov.f_inv(mi);
        for k in 0..N_QUANTILES {
            bands_mgdl[row + k] = kov.f_inv(q_tau_risk[row + k]);
        }
    }

    Ok(Forecast {
        median_risk,
        q_tau_risk,
        median_bg,
        bands_mgdl,
        slot_patch: slot_patch[..n_masked].to_vec(),
    })
}

/// The rows of `f` whose slot sits in `[from_patch, to_patch)`, as a Forecast of its own.
///
/// A masked set may hold an infill span and a forecast at once, and almost nothing
/// downstream wants both: the alarm engine, the rails and the accuracy suite read the
/// forecast, the chart draws each infill where it sits. Slicing by patch keeps that split in
/// one place rather than in every caller's index arithmetic.
#[uniffi::export]
pub fn forecast_slice(f: &Forecast, from_patch: i32, to_patch: i32) -> Result<Forecast, CoreError> {
    let keep: Vec<usize> = f
        .slot_patch
        .iter()
        .enumerate()
        .filter(|(_, &p)| p >= from_patch && p < to_patch)
        .map(|(i, _)| i)
        .collect();
    if keep.is_empty() {
        return Err(CoreError::Internal {
            reason: format!("no decoded slot lies in patches [{from_patch}, {to_patch})"),
        });
    }
    let mut out = Forecast {
        median_risk: Vec::with_capacity(keep.len() * PATCH_SIZE),
        q_tau_risk: Vec::with_capacity(keep.len() * PATCH_SIZE * N_QUANTILES),
        median_bg: Vec::with_capacity(keep.len() * PATCH_SIZE),
        bands_mgdl: Vec::with_capacity(keep.len() * PATCH_SIZE * N_QUANTILES),
        slot_patch: keep.iter().map(|&i| f.slot_patch[i]).collect(),
    };
    for &slot in &keep {
        for step in 0..PATCH_SIZE {
            let i = slot * PATCH_SIZE + step;
            out.median_risk.push(f.median_risk[i]);
            out.median_bg.push(f.median_bg[i]);
            for k in 0..N_QUANTILES {
                out.q_tau_risk.push(f.q_tau_risk[i * N_QUANTILES + k]);
                out.bands_mgdl.push(f.bands_mgdl[i * N_QUANTILES + k]);
            }
        }
    }
    Ok(out)
}

/// The fan's own line at an arbitrary quantile level, in mg/dL.
///
/// The model emits seven levels; reading the fan at a τ between two of them is a linear
/// interpolation in RISK space followed by `f_inv` — the space the fan was assembled in, and
/// the only one where the interpolation is between neighbouring band edges rather than across
/// a warp. τ at a published level returns that level's own edge, and `τ = 0.5` returns the
/// median untouched, so the default line is the same line as ever.
///
/// This reads a fan the model already emitted. It moves no median, is stored nowhere, and
/// nothing that classifies a category may consume it.
#[uniffi::export]
pub fn band_line(desc: &ModelDescriptor, f: &Forecast, tau: f64) -> Result<Vec<f64>, CoreError> {
    // The fan against the run it came from, which [`band_line_at`] cannot check on its own: a fan
    // holding a whole number of steps can still hold the wrong number of them.
    let n_steps = f.median_risk.len();
    if f.q_tau_risk.len() != n_steps * N_QUANTILES {
        return Err(CoreError::Internal {
            reason: format!(
                "fan of {} values does not match {n_steps} steps × {N_QUANTILES} levels",
                f.q_tau_risk.len()
            ),
        });
    }
    band_line_at(desc, f.q_tau_risk.clone(), tau)
}

/// The same line, read from a fan held on its own rather than inside a [`Forecast`].
///
/// The panel stores a reconstructed span's risk-space fan and nothing else of the run that made
/// it, so sweeping τ after the fact has no `Forecast` to hand. One interpolation body serves both
/// entry points deliberately: a second copy of the bracket-and-lerp would be free to drift from
/// this one, and the two would then disagree about where the same τ sits on the same fan.
///
/// `q_tau_risk` is `n_steps · N_QUANTILES` risk-space values, ascending τ within each step.
#[uniffi::export]
pub fn band_line_at(
    desc: &ModelDescriptor,
    q_tau_risk: Vec<f64>,
    tau: f64,
) -> Result<Vec<f64>, CoreError> {
    if !tau.is_finite() {
        return Err(CoreError::Internal {
            reason: format!("tau {tau} must be finite"),
        });
    }
    if q_tau_risk.len() % N_QUANTILES != 0 {
        return Err(CoreError::Internal {
            reason: format!(
                "fan of {} values is not a whole number of {N_QUANTILES}-level steps",
                q_tau_risk.len()
            ),
        });
    }
    let levels = QUANTILE_LEVELS;
    let tau = tau.clamp(levels[0], levels[N_QUANTILES - 1]);
    let n_steps = q_tau_risk.len() / N_QUANTILES;
    // The bracketing pair, and the weight of the upper one.
    let mut hi = 1usize;
    while hi < N_QUANTILES - 1 && levels[hi] < tau {
        hi += 1;
    }
    let lo = hi - 1;
    let span = levels[hi] - levels[lo];
    let w = if span > 0.0 { (tau - levels[lo]) / span } else { 0.0 };
    Ok((0..n_steps)
        .map(|i| {
            let a = q_tau_risk[i * N_QUANTILES + lo];
            let b = q_tau_risk[i * N_QUANTILES + hi];
            desc.kovatchev.f_inv(a + (b - a) * w)
        })
        .collect())
}

/// The seven levels the head emits, ascending — `invariants.md` §6.
pub(crate) const QUANTILE_LEVELS: [f64; N_QUANTILES] = [0.05, 0.10, 0.25, 0.50, 0.75, 0.90, 0.95];

// ── Forecast degeneracy guard (§3.6-B) ──────────────────────────────────────────────

/// Why a forecast is unfit to drive a rail, alert, or calculator score. Because `f_inv`
/// clamps to the descriptor's physical range, a collapsed or runaway model reads as a
/// *confident flat rail*, not an obvious NaN — hence the explicit rail / collapse /
/// mis-order checks.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ForecastStatus {
    /// Passed every check; eligible to drive rails/alerts.
    Ok,
    /// A NaN or infinity in the median or the fan.
    NonFinite,
    /// The whole median is pinned flat at the descriptor's low or high physical rail.
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
///
/// Takes the `desc` the forecast was decoded with because the rails ARE descriptor-defined:
/// checked against the wrong physical range the rail test cannot fire at all (a median
/// pinned flat at 40 mg/dL clears a `<= 20` test), and the guard silently passes exactly the
/// forecast it exists to reject.
#[uniffi::export]
pub fn forecast_degeneracy_check(desc: &ModelDescriptor, f: &Forecast) -> ForecastStatus {
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
    if !fan_is_ascending(&f.q_tau_risk, n, N_QUANTILES) {
        return ForecastStatus::MisorderedQuantiles;
    }

    // (3) Rail-pinned flat median, against THIS model's physical rails.
    let (lo, hi) = (desc.kovatchev.bg_clamp_min, desc.kovatchev.bg_clamp_max);
    if median_is_rail_pinned(&f.median_bg, lo, hi) {
        return ForecastStatus::RailPinned;
    }

    // (4) Collapsed band: the widest step is still near-zero width.
    if fan_is_collapsed(&f.bands_mgdl, n, N_QUANTILES) {
        return ForecastStatus::CollapsedBand;
    }

    ForecastStatus::Ok
}

// ── The degeneracy predicates, shared with the classical baseline ───────────────────
//
// `crate::baseline` runs the same three tests on a forecast that has no risk space and no
// descriptor: it judges fan order on the mg/dL bands and rails on the clinical physical domain.
// Only the inputs differ, so only the inputs are passed — the epsilons and the comparisons
// themselves stay here, in one copy. Two guards that agreed on the day they were written and
// drifted afterwards is precisely the failure the suite's no-second-copy rule exists to prevent.

/// True when every step's fan ascends across `nq` levels, within [`MONOTONE_TOL`].
pub(crate) fn fan_is_ascending(fan: &[f64], n: usize, nq: usize) -> bool {
    for i in 0..n {
        let row = i * nq;
        for k in 1..nq {
            if fan[row + k] < fan[row + k - 1] - MONOTONE_TOL {
                return false;
            }
        }
    }
    true
}

/// True when the whole median sits on one physical rail — the shape a collapsed or runaway
/// model takes after an output clamp, which reads as a confident flat line rather than a NaN.
pub(crate) fn median_is_rail_pinned(median_bg: &[f64], lo: f64, hi: f64) -> bool {
    median_bg.iter().all(|&v| v <= lo + RAIL_EPS_MGDL)
        || median_bg.iter().all(|&v| v >= hi - RAIL_EPS_MGDL)
}

/// True when even the widest step's outer band is narrower than [`COLLAPSE_EPS_MGDL`].
pub(crate) fn fan_is_collapsed(bands_mgdl: &[f64], n: usize, nq: usize) -> bool {
    let max_width = (0..n)
        .map(|i| {
            let row = i * nq;
            bands_mgdl[row + nq - 1] - bands_mgdl[row]
        })
        .fold(0.0f64, f64::max);
    max_width < COLLAPSE_EPS_MGDL
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

    /// The cross-implementation fixture: T1DMAI's own reference for the same inputs,
    /// regenerated by `T1DMAI/exporters/rust_golden.py` whenever the contract moves.
    const PIPELINE: &str = include_str!("testdata/pipeline_golden.json");

    fn pipeline() -> Value {
        serde_json::from_str(PIPELINE).unwrap()
    }

    /// Every case the fixture carries. Named here so a case ADDED to the generator and not read
    /// here fails the count check below rather than sitting unexercised.
    const CASES: [&str; 5] = [
        "forecast",
        "infill",
        "infill_no_forecast",
        "span_ladder",
        "span_ladder_long",
    ];

    #[test]
    fn every_golden_case_is_exercised() {
        let n = pipeline()["cases"].as_array().unwrap().len();
        assert_eq!(n, CASES.len(), "the fixture carries {n} cases and the tests read {}", CASES.len());
    }

    fn case(name: &str) -> Value {
        pipeline()["cases"]
            .as_array()
            .unwrap()
            .iter()
            .find(|c| c["name"] == name)
            .unwrap_or_else(|| panic!("golden case {name} is missing"))
            .clone()
    }

    /// Build the graph input for a golden case, at the reference's own (unfiltered) window —
    /// T1DMAI applies no smoother, so any other window compares two different pipelines.
    fn built(c: &Value, d: &ModelDescriptor) -> GraphInput {
        let spans: Vec<MaskSpan> = c["mask_spans"]
            .as_array()
            .unwrap()
            .iter()
            .map(|s| MaskSpan {
                start_patch: s[0].as_i64().unwrap() as i32,
                length: s[1].as_i64().unwrap() as i32,
            })
            .collect();
        build_graph_input(
            d,
            f64s(&c["raw_bg"]),
            f64s(&c["raw_carb"]),
            f64s(&c["raw_insulin"]),
            f64s(&c["raw_exercise"]),
            None,
            None,
            None,
            spans,
            c["with_forecast"].as_bool().unwrap(),
            1,
        )
        .expect("golden case must build")
    }

    /// The reference's own decode of a case: its head output, its anchors, its slot layout.
    /// Feeding OUR anchors here would let two errors cancel.
    fn decoded(c: &Value, d: &ModelDescriptor) -> Forecast {
        let n_masked = c["n_masked"].as_i64().unwrap() as i32;
        assemble_decode(
            d,
            f64s(&c["head_raw"]),
            f64s(&c["anchors"]),
            c["slot_patch"].as_array().unwrap().iter().map(|v| v.as_i64().unwrap() as i32).collect(),
            n_masked,
            vec![],
        )
        .expect("golden decode")
    }

    fn f64s(v: &Value) -> Vec<f64> {
        v.as_array().unwrap().iter().map(|x| x.as_f64().unwrap()).collect()
    }

    /// A DIFFERENT parameterization from the shipped one — a different scale and a different
    /// physical range — used to prove the pipeline follows the descriptor rather than a baked
    /// constant. It is not any released model's transform, and nothing decodes against it
    /// outside these tests.
    const OTHER_KOVATCHEV: KovatchevParams = KovatchevParams {
        scale: 1.509,
        power: 1.084,
        offset: 5.381,
        bg_clamp_min: 20.0,
        bg_clamp_max: 500.0,
    };

    /// The shipped parameterization, restated so a test can name it beside the other one.
    const SHIPPED_KOVATCHEV: KovatchevParams = KovatchevParams {
        scale: 2.2211457449985317,
        power: 1.084,
        offset: 5.540076976170212,
        bg_clamp_min: 10.0,
        bg_clamp_max: 400.0,
    };

    const REFERENCE_DESCRIPTOR: &str = include_str!("../../../models/descriptor.json");

    /// The shipped descriptor, parsed. Tests that need a variant clone it with `..`, so a
    /// fixture descriptor can never drift from the one the app actually reads.
    fn test_descriptor() -> ModelDescriptor {
        parse_descriptor(REFERENCE_DESCRIPTOR.to_string()).expect("reference descriptor")
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
        let d = test_descriptor();
        assert_eq!(d.rope_base, 1000);
        assert_eq!(d.step_basis_type, "dct");
        assert_eq!(d.quantile_spread_min, 1e-3);
        assert_eq!(d.neg_fill, -30000.0);
        assert_eq!(d.prediction_horizon_hours, 2);
        assert_eq!(d.prediction_patches().unwrap(), 4);
        assert!(!d.conformal_enabled);
        // The five-feature input and the seven-day window the newer models read.
        assert_eq!(d.n_input_features, N_FEAT as i32);
        assert_eq!(d.seq_len, d.max_context_patches + 4);
        assert!(d.max_context_patches >= d.min_context_patches);
        assert!(d.max_masked_patches >= 4, "the head must hold at least a forecast");
        assert!(d.d_model > 0 && d.step_basis_dim > 0);
        // The exercise channel has statistics of its own; without them the fourth input
        // feature would be normalized against another channel's scale.
        assert!(d.exercise.std > 0.0);
        // The head side file is what an adapter attaches to.
        let head = d.head.as_ref().expect("the reference export ships a head file");
        assert_eq!(head.out_dim, head.step_basis_dim * N_QUANTILES as i32);
        assert_eq!(head.sha256.len(), 64);
    }

    #[test]
    fn parse_descriptor_refuses_the_retired_three_feature_input() {
        // The exercise channel and the masked-announcement bit are not optional: a descriptor
        // from before them describes a model this build cannot construct an input for, and
        // running one anyway would feed carbohydrate statistics to an insulin column.
        let mut v: Value = serde_json::from_str(REFERENCE_DESCRIPTOR).unwrap();
        v["geometry"]["N_INPUT_FEATURES"] = serde_json::json!(3);
        assert!(matches!(parse_descriptor(v.to_string()), Err(CoreError::Decode { .. })));

        let mut v: Value = serde_json::from_str(REFERENCE_DESCRIPTOR).unwrap();
        v["normalization_stats"]
            .as_object_mut()
            .unwrap()
            .remove("exercise_equiv");
        assert!(matches!(parse_descriptor(v.to_string()), Err(CoreError::Decode { .. })));
    }

    /// The reference descriptor declares the risk space the model was TRAINED in, not the
    /// clinical one. Pinned numerically because a stale descriptor beside a newer `.pte`
    /// decodes finite, plausible, wrong mg/dL, and nothing downstream can see it.
    #[test]
    fn reference_descriptor_is_anchored_on_the_trained_range() {
        let k = test_descriptor().kovatchev;
        assert_eq!(k, SHIPPED_KOVATCHEV, "reference descriptor must carry the shipped constants");
        // The clamp is the physical range; the ANCHORS the constants were solved for sit
        // inside it, and the two are not the same pair.
        assert_eq!(k.bg_clamp_min, 10.0);
        assert_eq!(k.bg_clamp_max, 400.0);
        let root_ten = 10.0f64.sqrt();
        assert!((k.f(40.0) + root_ten).abs() < 1e-12, "f(40) = -sqrt(10), got {}", k.f(40.0));
        assert!((k.f(400.0) - root_ten).abs() < 1e-12, "f(400) = +sqrt(10), got {}", k.f(400.0));
    }

    #[test]
    fn parse_descriptor_rejects_garbage() {
        assert!(matches!(parse_descriptor("{".into()), Err(CoreError::Decode { .. })));
        assert!(matches!(parse_descriptor("{}".into()), Err(CoreError::Decode { .. })));
    }

    // ── #16: parse_descriptor fails closed on decode-critical drift ──────────────────
    #[test]
    fn parse_descriptor_rejects_decode_critical_drift() {
        // The unmodified reference descriptor passes every guard.
        assert!(
            parse_descriptor(REFERENCE_DESCRIPTOR.to_string()).is_ok(),
            "reference descriptor must still parse"
        );

        // Splice one drifted field into the reference and assert a fail-closed Decode.
        let bad = |block: &str, key: &str, val: Value| -> Result<ModelDescriptor, CoreError> {
            let mut v: Value = serde_json::from_str(REFERENCE_DESCRIPTOR).unwrap();
            v[block][key] = val;
            parse_descriptor(v.to_string())
        };
        let is_decode =
            |r: Result<ModelDescriptor, CoreError>| matches!(r, Err(CoreError::Decode { .. }));

        assert!(is_decode(bad("constants", "BG_HEAD_MEDIAN_GLOBAL_DIM", serde_json::json!(0))));
        assert!(is_decode(bad("constants", "BG_HEAD_MEDIAN_GLOBAL_DIM", serde_json::json!(-1))));
        assert!(is_decode(bad("geometry", "PATCH_SIZE", serde_json::json!(4))));
        assert!(is_decode(bad("constants", "neg_fill", serde_json::json!(0.0))));
        assert!(is_decode(bad("constants", "neg_fill", serde_json::json!(30000.0))));
        assert!(is_decode(bad("constants", "BG_QUANTILE_SPREAD_MIN", serde_json::json!(-1e-3))));
        // NaN is not representable in JSON → serialized as null → Decode at the parse step.
        assert!(is_decode(bad("constants", "BG_QUANTILE_SPREAD_MIN", serde_json::json!(f64::NAN))));
        assert!(is_decode(bad("geometry", "MIN_CONTEXT_PATCHES", serde_json::json!(0))));
        assert!(is_decode(bad("geometry", "MAX_CONTEXT_PATCHES", serde_json::json!(8))));
        assert!(is_decode(bad("constants", "PREDICTION_HORIZON_HOURS", serde_json::json!(0))));
        assert!(is_decode(bad("constants", "ROPE_BASE", serde_json::json!(0))));
        // A median this build does not assemble decodes to a different — smooth, finite, wrong —
        // line through the one it does.
        assert!(is_decode(bad("constants", "BG_HEAD_MEDIAN_MODE", serde_json::json!("cumulative"))));
    }

    // ── the risk transform is the DESCRIPTOR's, never a baked-in one ─────────────────

    #[test]
    fn parse_descriptor_requires_the_kovatchev_block() {
        // A descriptor that does not declare its risk transform is REJECTED. Defaulting to
        // any particular scale is what silently mis-decoded a re-anchored checkpoint: the
        // output stays finite and plausible, so nothing downstream can notice.
        let mut v: Value = serde_json::from_str(REFERENCE_DESCRIPTOR).unwrap();
        v.as_object_mut().unwrap().remove("kovatchev");
        assert!(matches!(parse_descriptor(v.to_string()), Err(CoreError::Decode { .. })));
    }

    #[test]
    fn parse_descriptor_rejects_a_malformed_kovatchev_block() {
        let bad = |k: &str, val: Value| {
            let mut v: Value = serde_json::from_str(REFERENCE_DESCRIPTOR).unwrap();
            v["kovatchev"][k] = val;
            parse_descriptor(v.to_string())
        };
        let is_decode = |r: Result<ModelDescriptor, CoreError>| {
            matches!(r, Err(CoreError::Decode { .. }))
        };

        assert!(is_decode(bad("SCALE", serde_json::json!(0.0))), "scale 0 ⇒ f_inv divides by 0");
        assert!(is_decode(bad("SCALE", serde_json::json!(-1.509))), "negative scale inverts f");
        assert!(is_decode(bad("POWER", serde_json::json!(0.0))));
        assert!(is_decode(bad("SCALE", serde_json::json!(f64::NAN))), "NaN → JSON null");
        // ln(g) <= 0 makes ln(g)^power NaN for a fractional power.
        assert!(is_decode(bad("BG_CLAMP_MIN", serde_json::json!(1.0))));
        assert!(is_decode(bad("BG_CLAMP_MIN", serde_json::json!(0.0))));
        // Inverted / empty physical range.
        assert!(is_decode(bad("BG_CLAMP_MAX", serde_json::json!(5.0))));
    }

    #[test]
    fn decode_follows_the_descriptor_not_a_baked_constant() {
        // THE regression. Risk-space output means nothing without the scale that produced
        // it: decoding a risk-v3 forecast against risk-v2 constants yields finite, plausible,
        // WRONG mg/dL (true 120 reads ~102, true 300 reads ~394). Same risk value in, the two
        // parameterizations must disagree, and each must invert its own f exactly.
        for mgdl in [55.0, 70.0, 120.0, 180.0, 300.0] {
            let r_v3 = SHIPPED_KOVATCHEV.f(mgdl);
            let own = SHIPPED_KOVATCHEV.f_inv(r_v3);
            let wrong = OTHER_KOVATCHEV.f_inv(r_v3);
            assert!((own - mgdl).abs() < 1e-9, "f_inv(f({mgdl})) = {own} under its own params");
            assert!(
                (wrong - mgdl).abs() > 5.0,
                "decoding {mgdl} mg/dL under the wrong scale gave {wrong} — if this ever \
                 stops differing, the two parameterizations have been unified by accident"
            );
        }

        // And the full assemble_decode path rides the descriptor: a flat (all-zero) head_raw
        // anchors the median at last_bg, recovered exactly through each descriptor's own f/f_inv.
        let flat = vec![0.0f64; 4 * PATCH_SIZE * N_QUANTILES];
        for kov in [OTHER_KOVATCHEV, SHIPPED_KOVATCHEV] {
            let d = ModelDescriptor { kovatchev: kov, ..test_descriptor() };
            let f =
                assemble_decode(&d, flat.clone(), vec![120.0; 4], vec![0, 1, 2, 3], 4, vec![])
                    .unwrap();
            for v in &f.median_bg {
                assert!((v - 120.0).abs() < 1e-6, "anchor round trip under {kov:?} gave {v}");
            }
            // f_inv can never leave the descriptor's own physical range.
            for v in &f.bands_mgdl {
                assert!(
                    *v >= kov.bg_clamp_min - 1e-9 && *v <= kov.bg_clamp_max + 1e-9,
                    "band {v} escaped [{}, {}]",
                    kov.bg_clamp_min,
                    kov.bg_clamp_max
                );
            }
        }
    }

    #[test]
    fn rail_pinned_is_detected_at_the_descriptors_own_rails() {
        // A median flat at 40 mg/dL is rail-pinned for risk-v3 but an ordinary low forecast
        // for risk-v2 — the check is meaningless without the descriptor. Before the guard took
        // one, a risk-v3 rail-pin sailed through: nothing can reach the old 20 mg/dL rail.
        let n = 4 * PATCH_SIZE;
        let pinned = |bg: f64| Forecast {
            median_risk: vec![0.0; n],
            q_tau_risk: (0..n * N_QUANTILES).map(|k| (k % N_QUANTILES) as f64).collect(),
            median_bg: vec![bg; n],
            bands_mgdl: (0..n * N_QUANTILES).map(|k| bg + (k % N_QUANTILES) as f64).collect(),
            slot_patch: (0..(n / PATCH_SIZE) as i32).collect(),
        };
        let shipped = ModelDescriptor { kovatchev: SHIPPED_KOVATCHEV, ..test_descriptor() };
        let other = ModelDescriptor { kovatchev: OTHER_KOVATCHEV, ..test_descriptor() };

        // 20 mg/dL is the OTHER parameterization's floor and an ordinary low forecast under
        // the shipped one; 10 is the shipped floor. Each descriptor must call its own.
        assert_eq!(forecast_degeneracy_check(&shipped, &pinned(10.0)), ForecastStatus::RailPinned);
        assert_eq!(forecast_degeneracy_check(&shipped, &pinned(400.0)), ForecastStatus::RailPinned);
        assert_eq!(forecast_degeneracy_check(&shipped, &pinned(20.0)), ForecastStatus::Ok);
        assert_eq!(forecast_degeneracy_check(&other, &pinned(20.0)), ForecastStatus::RailPinned);
        assert_eq!(forecast_degeneracy_check(&other, &pinned(500.0)), ForecastStatus::RailPinned);
        assert_eq!(forecast_degeneracy_check(&other, &pinned(40.0)), ForecastStatus::Ok);
    }

    #[test]
    fn descriptor_kovatchev_guards_are_total() {
        // The same hostile-input totality the clinical pair guarantees (INFERENCE.md §5).
        for kov in [OTHER_KOVATCHEV, SHIPPED_KOVATCHEV] {
            for r in [f64::NAN, f64::INFINITY, f64::NEG_INFINITY, -1e9, 1e9] {
                let g = kov.f_inv(r);
                assert!(
                    g.is_finite() && (kov.bg_clamp_min..=kov.bg_clamp_max).contains(&g),
                    "f_inv({r}) = {g} under {kov:?}"
                );
            }
            for g in [f64::NAN, f64::INFINITY, f64::NEG_INFINITY, -50.0, 1e9] {
                assert!(kov.f(g).is_finite(), "f({g}) not finite under {kov:?}");
            }
        }
    }


    /// The five detents the app offers (Off / Standard / Moderate / Strong / Heavy).
    const STOPS: [i32; 5] = [1, 7, 13, 19, 25];

    // ── the runtime solver reproduces the pinned reference taps ──────────────────────
    #[test]
    fn savgol_solver_reproduces_reference_taps() {
        // The general closed form must land on the exact rationals over 42 that the golden
        // fixture (and every pre-configurable build) pins, to fp64 round-off.
        let want: Vec<f64> = SAVGOL_TAPS.iter().map(|t| t / SAVGOL_DENOM).collect();
        assert_close(&savgol_endpoint_taps_general(7), &want, 1e-15, "solver taps w=7");
        // ...and the w=7 fast path is the exact rationals, so the default smooth is bit-identical.
        let (taps, denom) = savgol_endpoint_taps(7);
        assert_eq!(taps, SAVGOL_TAPS.to_vec());
        assert_eq!(denom, SAVGOL_DENOM);
    }

    #[test]
    fn savgol_taps_preserve_dc_at_every_stop() {
        for w in STOPS {
            let (taps, denom) = savgol_endpoint_taps(w as usize);
            assert_eq!(taps.len(), w as usize, "w={w}: tap count");
            let sum: f64 = taps.iter().sum::<f64>() / denom;
            assert!((sum - 1.0).abs() < 1e-12, "w={w}: taps sum to {sum}, not 1");
            // use='dot' ordering: the NEWEST sample carries the largest weight, so the
            // endpoint estimate does not lag.
            let newest = taps[w as usize - 1] / denom;
            assert!(
                taps.iter().all(|t| t / denom <= newest + 1e-12),
                "w={w}: newest tap {newest} is not the largest"
            );
        }
    }

    #[test]
    fn savgol_window_one_is_the_identity() {
        let x = vec![120.0, 4.0, 900.0, -30.0, 77.5];
        assert_eq!(causal_smooth(x.clone(), None, None, 1), x);
        // The physical clamps are not part of the filter — they hold at w=1 too.
        assert_eq!(
            causal_smooth(x, Some(OTHER_KOVATCHEV.bg_clamp_min), Some(OTHER_KOVATCHEV.bg_clamp_max), 1),
            vec![120.0, 20.0, 500.0, 20.0, 77.5]
        );
    }

    #[test]
    fn causal_smooth_never_leaks_the_future() {
        let base: Vec<f64> = (0..64).map(|i| 100.0 + (i as f64 * 0.7).sin() * 30.0).collect();
        for w in STOPS {
            let a = causal_smooth(base.clone(), None, None, w);
            let mut perturbed = base.clone();
            perturbed[40] += 250.0;
            let b = causal_smooth(perturbed, None, None, w);
            for t in 0..40 {
                assert!((a[t] - b[t]).abs() < 1e-12, "w={w}: sample 40 leaked backwards into t={t}");
            }
        }
    }

    #[test]
    fn causal_smooth_falls_back_on_an_out_of_contract_window() {
        // Display-path totality: an even / non-positive / oversized window must never panic
        // (the crate is `panic = "abort"`); it degrades to the default instead.
        let x: Vec<f64> = (0..32).map(|i| 90.0 + i as f64).collect();
        let want = causal_smooth(x.clone(), None, None, SAVGOL_WINDOW as i32);
        for bad in [0, -7, 8, SAVGOL_WINDOW_MAX + 2, i32::MIN] {
            assert_eq!(causal_smooth(x.clone(), None, None, bad), want, "window {bad}");
        }
    }



    #[test]
    fn normalize_denormalize_round_trip() {
        let d = test_descriptor();
        for (bg, carb, ins, ex) in
            [(75.0, 0.0, 0.02, 0.0), (180.0, 8.0, 0.5, 1.2), (40.0, 3.0, 0.1, 0.4)]
        {
            let z = normalize_sample(&d, bg, carb, ins, ex);
            let back = denormalize_sample(&d, z).unwrap();
            assert!((back[0] - bg).abs() < 1e-6, "bg round-trip {bg} -> {}", back[0]);
            assert!((back[1] - carb).abs() < 1e-6, "carb round-trip");
            assert!((back[2] - ins).abs() < 1e-6, "insulin round-trip");
            assert!((back[3] - ex).abs() < 1e-6, "exercise round-trip");
        }
    }










    /// The rolling carry is PER LEVEL (INFERENCE.md §8.1, §9): each edge moves by its own
    /// offset and by no other, the median does not move, and a single value widens every level
    /// alike. A carry shared across the levels is what makes the next roll's .75 edge land
    /// outside this roll's .95 edge.
    #[test]
    fn carry_spread_is_per_level() {
        let d = test_descriptor();
        let mut head = vec![0.0f64; 4 * PATCH_SIZE * N_QUANTILES];
        for (i, h) in head.iter_mut().enumerate() {
            *h = ((i % 5) as f64 - 2.0) * 0.3; // something asymmetric on both sides
        }
        let anchors = vec![120.0; 4];
        let slots = vec![0, 1, 2, 3];
        let bare = assemble_decode(&d, head.clone(), anchors.clone(), slots.clone(), 4, vec![])
            .unwrap();
        // [up .75 .9 .95 | dn .25 .1 .05]
        let carry = vec![0.10, 0.20, 0.30, 0.40, 0.50, 0.60];
        let per = assemble_decode(&d, head.clone(), anchors.clone(), slots.clone(), 4, carry.clone())
            .unwrap();

        for i in 0..bare.median_risk.len() {
            let row = i * N_QUANTILES;
            assert!((per.q_tau_risk[row + N_SPREADS] - bare.q_tau_risk[row + N_SPREADS]).abs() < 1e-12,
                "the carry moved the median");
            for k in 0..N_SPREADS {
                let up = per.q_tau_risk[row + N_SPREADS + 1 + k] - bare.q_tau_risk[row + N_SPREADS + 1 + k];
                let dn = bare.q_tau_risk[row + N_SPREADS - 1 - k] - per.q_tau_risk[row + N_SPREADS - 1 - k];
                assert!((up - carry[k]).abs() < 1e-12, "up level {k} moved by {up}, want {}", carry[k]);
                assert!((dn - carry[N_SPREADS + k]).abs() < 1e-12,
                    "dn level {k} moved by {dn}, want {}", carry[N_SPREADS + k]);
            }
        }

        // One value is that value in every slot; none is the identity.
        let one = assemble_decode(&d, head.clone(), anchors.clone(), slots.clone(), 4, vec![0.37])
            .unwrap();
        let six = assemble_decode(&d, head.clone(), anchors.clone(), slots.clone(), 4, vec![0.37; 6])
            .unwrap();
        assert_eq!(one.q_tau_risk, six.q_tau_risk);
        assert_eq!(bare.q_tau_risk, assemble_decode(&d, head.clone(), anchors.clone(), slots.clone(), 4, vec![0.0]).unwrap().q_tau_risk);

        // A shape that is neither is refused, never broadcast; so is a negative or non-finite one.
        for bad in [vec![0.1, 0.2], vec![0.0; 7], vec![-0.1; 6], vec![f64::NAN; 6]] {
            assert!(
                assemble_decode(&d, head.clone(), anchors.clone(), slots.clone(), 4, bad.clone())
                    .is_err(),
                "carry {bad:?} must be refused"
            );
        }
    }

    // ── forecast_degeneracy_check (§3.6-B) ───────────────────────────────────────────
    #[test]
    fn degeneracy_non_finite() {
        let d = test_descriptor();
        let c = case("forecast");
        let mut f = decoded(&c, &d);
        f.median_bg[3] = f64::NAN;
        assert_eq!(forecast_degeneracy_check(&test_descriptor(), &f), ForecastStatus::NonFinite);
        let mut f2 = decoded(&c, &d);
        f2.q_tau_risk[0] = f64::INFINITY;
        assert_eq!(forecast_degeneracy_check(&test_descriptor(), &f2), ForecastStatus::NonFinite);
    }

    #[test]
    fn degeneracy_rail_pinned() {
        // A runaway model: enormous median delta ⇒ every median clamps to 500.
        let d = test_descriptor();
        let mut head = vec![0.0f64; 4 * 6 * 7];
        for i in 0..24 {
            head[i * 7] = 100.0; // huge risk delta
        }
        let f = assemble_decode(&d, head, vec![120.0; 4], vec![0, 1, 2, 3], 4, vec![]).unwrap();
        let hi = d.kovatchev.bg_clamp_max;
        assert!(f.median_bg.iter().all(|&v| (v - hi).abs() < 1e-6));
        assert_eq!(forecast_degeneracy_check(&test_descriptor(), &f), ForecastStatus::RailPinned);
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
        let f = Forecast {
            median_risk,
            q_tau_risk: q,
            median_bg,
            bands_mgdl: b,
            slot_patch: (0..(n / PATCH_SIZE) as i32).collect(),
        };
        assert_eq!(forecast_degeneracy_check(&test_descriptor(), &f), ForecastStatus::CollapsedBand);
    }

    #[test]
    fn degeneracy_misordered() {
        let d = test_descriptor();
        let mut f = decoded(&case("forecast"), &d);
        // Swap two fan entries at step 0 to break monotonicity.
        f.q_tau_risk.swap(0, 6);
        assert_eq!(forecast_degeneracy_check(&test_descriptor(), &f), ForecastStatus::MisorderedQuantiles);
    }

    #[test]
    fn degeneracy_ok_on_healthy_fan() {
        let d = test_descriptor();
        let f = decoded(&case("forecast"), &d);
        assert_eq!(forecast_degeneracy_check(&test_descriptor(), &f), ForecastStatus::Ok);
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
    /// from the four real model per-patch logit rows must decode to `model_slot0`'s belief.
    #[test]
    fn decode_time_origin_patch_reduction() {
        let g = time_golden();
        let bin_hours = g["bin_hours"].as_f64().unwrap();
        let n_bins = g["n_bins"].as_i64().unwrap() as i32;
        let rows = g["rows"].as_array().unwrap();
        let patch_rows: Vec<&Value> = rows
            .iter()
            .filter(|r| r["name"].as_str().unwrap().starts_with("model_slot"))
            .collect();
        assert_eq!(patch_rows.len(), 4, "expected 4 model patch rows");

        // Concatenate all four patches row-major into one (4, n_bins) flat buffer.
        let mut flat: Vec<f64> = Vec::new();
        for r in &patch_rows {
            flat.extend(f64s(&r["logits"]));
        }
        let pt = decode_time(flat, n_bins, bin_hours).expect("decode_time must succeed");

        let want = &patch_rows[0]; // origin = model_slot0
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
        // Present in the reference export, which emits the co-trained probe.
        let t = test_descriptor().time.expect("time section must parse");
        assert_eq!(t.n_bins, 12);
        assert_eq!(t.bin_hours, 2.0);

        // Absent ⇒ None, and no predicted hour is surfaced.
        let mut v: Value = serde_json::from_str(REFERENCE_DESCRIPTOR).unwrap();
        v.as_object_mut().unwrap().remove("time");
        assert!(parse_descriptor(v.to_string()).unwrap().time.is_none());

        // A degenerate time section (n_bins ≤ 0) fails closed.
        v["time"] = serde_json::json!({ "output_index": 1, "n_bins": 0, "bin_hours": 2.0 });
        assert!(parse_descriptor(v.to_string()).is_err());
    }

    // ── the built graph input matches T1DMAI's own, case by case ─────────────────────

    #[test]
    fn graph_input_matches_the_reference_pipeline() {
        use sha2::{Digest, Sha256};
        let d = test_descriptor();
        let tol = pipeline()["tolerances"]["patches"].as_f64().unwrap();
        for name in CASES {
            let c = case(name);
            let gi = built(&c, &d);
            assert_eq!(gi.t, c["t"].as_i64().unwrap() as i32, "{name}: T");
            assert_eq!(gi.n_masked, c["n_masked"].as_i64().unwrap() as i32, "{name}: n_masked");

            // The patch tensor, including the withheld BG and the announcement bit.
            let want = f64s(&c["patches_f32"]);
            let got: Vec<f64> = gi.patches.iter().map(|v| *v as f64).collect();
            assert_close(&got, &want, tol, &format!("{name}: patches"));

            // The attention pattern is boolean, so it is compared exactly. A digest catches a
            // rule that is subtly wrong everywhere as readily as one wrong cell.
            let bytes: Vec<u8> = gi.attn_mask.iter().map(|v| u8::from(*v == 0.0)).collect();
            assert_eq!(
                format!("{:x}", Sha256::digest(&bytes)),
                c["attn_sha256"].as_str().unwrap(),
                "{name}: attention pattern"
            );

            // The masked set and its anchors.
            let want_slots: Vec<i32> = c["slot_patch"]
                .as_array()
                .unwrap()
                .iter()
                .map(|v| v.as_i64().unwrap() as i32)
                .collect();
            assert_eq!(&gi.slot_patch[..want_slots.len()], &want_slots[..], "{name}: slot_patch");
            let n = want_slots.len();
            assert_close(
                &gi.anchors[..n],
                &f64s(&c["anchors"]),
                1e-3,
                &format!("{name}: anchors"),
            );

            // Every slot_sel row is one-hot on the patch its slot names.
            let t = gi.t as usize;
            for (j, patch) in gi.slot_patch.iter().enumerate() {
                let row = &gi.slot_sel[j * t..(j + 1) * t];
                assert_eq!(row.iter().filter(|v| **v != 0.0).count(), 1, "{name}: slot {j}");
                let want = if *patch < 0 { 0 } else { *patch as usize };
                assert_eq!(row[want], 1.0, "{name}: slot {j} selects patch {want}");
            }
        }
    }

    #[test]
    fn decode_matches_the_reference_pipeline() {
        let d = test_descriptor();
        for name in CASES {
            let c = case(name);
            let f = decoded(&c, &d);
            assert_close(
                &f.median_risk,
                &f64s(&c["median_risk"]),
                1e-8,
                &format!("{name}: median_risk"),
            );
            assert_close(
                &f.q_tau_risk,
                &f64s(&c["q_tau_risk"]),
                1e-8,
                &format!("{name}: q_tau_risk"),
            );
        }
    }

    /// The span-scaled basis dimension is the whole reason a one-patch infill does not simply
    /// reproduce its own noise. `span_ladder` holds spans of 1, 2, 3 and 4 patches at once,
    /// which no single forecast reaches.
    #[test]
    fn median_projection_contracts_at_every_span_length() {
        let d = test_descriptor();
        let c = case("span_ladder");
        let f = decoded(&c, &d);
        let head = f64s(&c["head_raw"]);
        let anchors = f64s(&c["anchors"]);
        let slots: Vec<i32> = c["slot_patch"]
            .as_array()
            .unwrap()
            .iter()
            .map(|v| v.as_i64().unwrap() as i32)
            .collect();
        for (start, length) in span_layout(&slots, slots.len()) {
            let n = length * PATCH_SIZE;
            // A projection can only shrink: ||m − anchor|| <= ||delta|| over the span.
            let mut proj = 0.0;
            let mut raw = 0.0;
            for i in 0..n {
                let idx = start * PATCH_SIZE + i;
                let anchor = d.kovatchev.f(anchors[start]);
                proj += (f.median_risk[idx] - anchor).powi(2);
                raw += head[idx * N_QUANTILES].powi(2);
            }
            assert!(
                proj <= raw + 1e-9,
                "span at slot {start} of {length} patches: projection grew ({proj} > {raw})"
            );
            assert!(
                global_median_dim(&d, length, 4) <= n,
                "span of {length} patches would take more basis columns than it has steps"
            );
        }
    }

    // ── masked-set rules ─────────────────────────────────────────────────────────────

    #[test]
    fn masked_patches_withhold_bg_and_announce_themselves() {
        let d = test_descriptor();
        let c = case("infill");
        let gi = built(&c, &d);
        let stride = PATCH_SIZE * N_FEAT;
        let masked: std::collections::HashSet<i32> =
            gi.slot_patch.iter().copied().filter(|p| *p >= 0).collect();
        for patch in 0..gi.t {
            let bit = gi.patches[patch as usize * stride + BG_MASKED_FEAT];
            if masked.contains(&patch) {
                assert_eq!(bit, 1.0, "masked patch {patch} does not announce itself");
                for step in 0..PATCH_SIZE {
                    let base = (patch as usize * PATCH_SIZE + step) * N_FEAT;
                    assert_eq!(gi.patches[base], 0.0, "masked patch {patch} still carries BG");
                    assert_eq!(gi.patches[base + BG_MASKED_FEAT], 1.0);
                }
            } else {
                assert_eq!(bit, 0.0, "visible patch {patch} claims to be masked");
            }
        }
    }

    #[test]
    fn attention_never_lets_evidence_read_a_prediction() {
        let d = test_descriptor();
        let c = case("infill");
        let gi = built(&c, &d);
        let t = gi.t as usize;
        let masked: std::collections::HashSet<i32> =
            gi.slot_patch.iter().copied().filter(|p| *p >= 0).collect();
        let pad0 = t - (gi.n_ctx as usize) - if gi.first_forecast_patch >= 0 { 4 } else { 0 };
        let mut checked = 0;
        for row in pad0..t {
            if masked.contains(&(row as i32)) {
                continue; // a prediction may read everything real
            }
            for col in pad0..t {
                if masked.contains(&(col as i32)) {
                    assert_eq!(
                        gi.attn_mask[row * t + col], d.neg_fill as f32,
                        "visible row {row} can read masked column {col}"
                    );
                    checked += 1;
                }
            }
            // Nothing may read a pad column.
            for col in 0..pad0 {
                assert_eq!(gi.attn_mask[row * t + col], d.neg_fill as f32);
            }
        }
        assert!(checked > 0, "the case has no visible→masked pair to check");
        // No row may be entirely blocked, or its softmax is NaN.
        for row in 0..t {
            assert!(
                (0..t).any(|col| gi.attn_mask[row * t + col] == 0.0),
                "row {row} attends to nothing"
            );
        }
    }

    #[test]
    fn parse_descriptor_rejects_a_geometry_that_would_abort_the_process() {
        // A negative dimension casts to a colossal usize in the builder and the allocation tears
        // the process down; the crate is `panic = "abort"`, so there is nothing to catch.
        let bad = |block: &str, key: &str, val: Value| {
            let mut v: Value = serde_json::from_str(REFERENCE_DESCRIPTOR).unwrap();
            v[block][key] = val;
            parse_descriptor(v.to_string())
        };
        for key in ["T", "MAX_MASKED_PATCHES", "D_MODEL"] {
            assert!(bad("geometry", key, serde_json::json!(-1)).is_err(), "{key} = -1");
            assert!(bad("geometry", key, serde_json::json!(0)).is_err(), "{key} = 0");
        }
        assert!(bad("constants", "BG_HEAD_STEP_BASIS_DIM", serde_json::json!(0)).is_err());
        // A window the graph cannot hold is refused rather than truncated.
        assert!(bad("geometry", "T", serde_json::json!(4)).is_err());
    }

    /// The masked set is the question; the withheld BG is the answer. The causal filter is a
    /// weighted sum of the six preceding steps, so run over the whole series it would carry a
    /// masked span's own glucose into the visible patch after it AND into the anchor of any span
    /// that anchors on its right neighbour — an infill scored against evidence it was supposed not
    /// to have.
    #[test]
    fn withheld_bg_never_reaches_a_visible_patch_through_the_filter() {
        let d = test_descriptor();
        let n_ctx = d.min_context_patches as usize;
        let n = n_ctx * PATCH_SIZE;
        let span = MaskSpan { start_patch: 40, length: 2 };
        let masked_from = span.start_patch as usize * PATCH_SIZE;
        let masked_to = masked_from + span.length as usize * PATCH_SIZE;

        let build = |bg: Vec<f64>| {
            build_graph_input(
                &d, bg, vec![0.0; n], vec![0.0; n], vec![0.0; n], None, None, None,
                vec![span], false, 7,
            )
            .expect("window builds")
        };
        let base: Vec<f64> = (0..n).map(|i| 120.0 + (i % 17) as f64).collect();
        let mut perturbed = base.clone();
        // Move ONLY the withheld steps, by a lot.
        for v in perturbed[masked_from..masked_to].iter_mut() {
            *v = 300.0;
        }
        let a = build(base);
        let b = build(perturbed);

        assert_eq!(a.anchors, b.anchors, "an anchor moved with the BG that was withheld");
        for i in 0..a.patches.len() {
            let patch = i / (PATCH_SIZE * N_FEAT);
            let is_masked = a.slot_patch.iter().any(|p| *p == patch as i32);
            if !is_masked {
                assert_eq!(
                    a.patches[i], b.patches[i],
                    "visible cell {i} moved with the BG that was withheld",
                );
            }
        }
    }

    #[test]
    fn build_graph_input_rejects_an_impossible_masked_set() {
        let d = test_descriptor();
        let n = d.min_context_patches as usize * PATCH_SIZE;
        let bg = vec![120.0; n];
        let z = vec![0.0; n];
        let call = |spans: Vec<MaskSpan>, with_forecast: bool| {
            build_graph_input(
                &d, bg.clone(), z.clone(), z.clone(), z.clone(), None, None, None, spans,
                with_forecast, 1,
            )
        };
        let sp = |s: i32, l: i32| MaskSpan { start_patch: s, length: l };
        // Abutting spans are one longer span, which the sampler never drew and the anchor
        // cannot describe.
        assert!(call(vec![sp(10, 2), sp(12, 2)], false).is_err());
        // A span abutting the future zone leaves the forecast with no visible neighbour.
        assert!(call(vec![sp(d.min_context_patches - 2, 2)], true).is_err());
        // More masked patches than the head has slots.
        let too_many: Vec<MaskSpan> =
            (0..d.max_masked_patches + 1).map(|i| sp(i * 2, 1)).collect();
        assert!(call(too_many, false).is_err());
        // Off the end of the observed context.
        assert!(call(vec![sp(d.min_context_patches - 1, 4)], false).is_err());
        // A window with nothing masked at all has nothing to predict.
        assert!(call(vec![], false).is_err());
        // …and the ordinary forecast is accepted.
        assert!(call(vec![], true).is_ok());
        assert!(call(vec![sp(20, 3)], true).is_ok());
    }

    #[test]
    fn build_graph_input_rejects_a_context_outside_the_descriptors_bounds() {
        let d = test_descriptor();
        let short = (d.min_context_patches as usize - 1) * PATCH_SIZE;
        let bg = vec![120.0; short];
        let z = vec![0.0; short];
        assert!(build_graph_input(
            &d, bg, z.clone(), z.clone(), z, None, None, None, vec![], true, 1
        )
        .is_err());
        // Ragged channels, and a history that does not tile the patch.
        let n = d.min_context_patches as usize * PATCH_SIZE;
        assert!(build_graph_input(
            &d, vec![120.0; n], vec![0.0; n - 1], vec![0.0; n], vec![0.0; n],
            None, None, None, vec![], true, 1
        )
        .is_err());
        assert!(build_graph_input(
            &d, vec![120.0; n + 1], vec![0.0; n + 1], vec![0.0; n + 1], vec![0.0; n + 1],
            None, None, None, vec![], true, 1
        )
        .is_err());
    }

    /// An announced future dose reaches the prediction zone, and an unannounced one takes the
    /// `normalize(0)` no-event baseline rather than a literal `z = 0` — which would announce a
    /// phantom fraction of a gram through the sparse log1p inverse.
    #[test]
    fn announced_doses_reach_the_future_zone() {
        let d = test_descriptor();
        let n = d.min_context_patches as usize * PATCH_SIZE;
        let pred_steps = 4 * PATCH_SIZE;
        let carb: Vec<f64> = (0..pred_steps).map(|i| i as f64 * 0.5).collect();
        let gi = build_graph_input(
            &d, vec![120.0; n], vec![0.0; n], vec![0.0; n], vec![0.0; n],
            Some(carb.clone()), None, None, vec![], true, 1,
        )
        .unwrap();
        let t = gi.t as usize;
        let first = gi.first_forecast_patch as usize;
        assert_eq!(first, t - 4);
        let zbase = normalize_sample(&d, 0.0, 0.0, 0.0, 0.0);
        for j in 0..pred_steps {
            let base = ((first + j / PATCH_SIZE) * PATCH_SIZE + j % PATCH_SIZE) * N_FEAT;
            let want_carb = normalize_sample(&d, 0.0, carb[j], 0.0, 0.0)[1];
            assert!((gi.patches[base + 1] as f64 - want_carb).abs() < 1e-6, "carb step {j}");
            assert!((gi.patches[base + 2] as f64 - zbase[2]).abs() < 1e-6, "insulin step {j}");
            assert!((gi.patches[base + 3] as f64 - zbase[3]).abs() < 1e-6, "exercise step {j}");
            assert_eq!(gi.patches[base], 0.0, "future BG must stay withheld");
        }
        // An announced channel on a window with no future zone is a caller error, not a
        // silently ignored argument.
        assert!(build_graph_input(
            &d, vec![120.0; n], vec![0.0; n], vec![0.0; n], vec![0.0; n],
            Some(carb), None, None, vec![MaskSpan { start_patch: 10, length: 2 }], false, 1,
        )
        .is_err());
    }

    // ── reading the fan ──────────────────────────────────────────────────────────────

    #[test]
    fn band_line_at_the_median_is_the_median() {
        let d = test_descriptor();
        let f = decoded(&case("forecast"), &d);
        let line = band_line(&d, &f, 0.5).unwrap();
        assert_close(&line, &f.median_bg, 1e-12, "band_line(0.5)");
        // Each published level returns its own edge.
        for (k, tau) in QUANTILE_LEVELS.iter().enumerate() {
            let line = band_line(&d, &f, *tau).unwrap();
            let want: Vec<f64> = (0..f.median_bg.len())
                .map(|i| f.bands_mgdl[i * N_QUANTILES + k])
                .collect();
            assert_close(&line, &want, 1e-12, &format!("band_line({tau})"));
        }
    }

    #[test]
    fn band_line_is_monotone_in_tau_and_clamped_to_the_fan() {
        let d = test_descriptor();
        let f = decoded(&case("forecast"), &d);
        let mut prev = band_line(&d, &f, 0.05).unwrap();
        for tau in [0.07, 0.2, 0.4, 0.5, 0.6, 0.8, 0.93, 0.95] {
            let line = band_line(&d, &f, tau).unwrap();
            for (i, (a, b)) in prev.iter().zip(&line).enumerate() {
                assert!(*a <= *b + 1e-9, "tau {tau} step {i}: {a} > {b}");
            }
            prev = line;
        }
        // Outside the published levels the line is clamped to the outermost edge rather than
        // extrapolated: the model said nothing about a level it never emitted.
        let lo = band_line(&d, &f, 0.0).unwrap();
        let hi = band_line(&d, &f, 1.0).unwrap();
        assert_close(&lo, &band_line(&d, &f, 0.05).unwrap(), 1e-12, "tau below the fan");
        assert_close(&hi, &band_line(&d, &f, 0.95).unwrap(), 1e-12, "tau above the fan");
        assert!(band_line(&d, &f, f64::NAN).is_err());
    }

    /// The fan read on its own is the same line as the fan read inside its run.
    ///
    /// Both entry points share one interpolation body; this pins that they are wired to it, since
    /// a stored span's τ sweep and a live forecast's must not disagree about the same fan.
    #[test]
    fn band_line_at_reads_a_bare_fan_the_same_way() {
        let d = test_descriptor();
        let f = decoded(&case("forecast"), &d);
        for tau in [0.05, 0.13, 0.5, 0.5001, 0.77, 0.95] {
            let inside = band_line(&d, &f, tau).unwrap();
            let bare = band_line_at(&d, f.q_tau_risk.clone(), tau).unwrap();
            assert_close(&bare, &inside, 1e-12, &format!("band_line_at({tau})"));
        }
        assert!(band_line_at(&d, f.q_tau_risk.clone(), f64::NAN).is_err());
    }

    /// A fan that is not a whole number of seven-level steps is a blob from another build, and it
    /// is refused rather than read as a shorter run — half a fan drawn as nested bands would be a
    /// shape nothing emitted.
    #[test]
    fn band_line_at_refuses_a_ragged_fan() {
        let d = test_descriptor();
        assert!(band_line_at(&d, vec![0.1; N_QUANTILES * 3 + 1], 0.5).is_err());
        assert!(band_line_at(&d, vec![0.1; N_QUANTILES - 1], 0.5).is_err());
        // Empty is a whole number of steps — zero of them — and reads as an empty line.
        assert_eq!(band_line_at(&d, vec![], 0.5).unwrap().len(), 0);
    }

    #[test]
    fn forecast_slice_separates_an_infill_from_the_forecast() {
        let d = test_descriptor();
        let c = case("infill");
        let gi = built(&c, &d);
        let f = decoded(&c, &d);
        let first = gi.first_forecast_patch;
        let fc = forecast_slice(&f, first, gi.t).unwrap();
        assert_eq!(fc.slot_patch, (first..gi.t).collect::<Vec<i32>>());
        assert_eq!(fc.median_bg.len(), 4 * PATCH_SIZE);
        // The infill rows are the rest, and none of them sits in the forecast.
        let infill = forecast_slice(&f, 0, first).unwrap();
        assert!(infill.slot_patch.iter().all(|p| *p < first));
        assert_eq!(infill.median_bg.len() + fc.median_bg.len(), f.median_bg.len());
        // A range holding no slot is an error, not an empty forecast nothing checks.
        assert!(forecast_slice(&f, first, first).is_err());
    }
}
