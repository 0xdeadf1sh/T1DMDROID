//! Model pre/post fp64 (INFERENCE.md §§6-8); every exported fn is total on hostile input.

use serde::Deserialize;

// Clinical kovatchev_f/f_inv unused: model path crosses (b)<->(c) via descriptor's own params.
use crate::CoreError;

/// 6 × 5 min steps = 30 min.
const PATCH_SIZE: usize = 6;
/// `[bg_absolute, carb_intake, insulin_combined, exercise_equiv, bg_masked]`.
const N_FEAT: usize = 5;
/// Feats 0..3; feat 4 carries no statistics.
const N_CHANNELS: usize = 4;
/// Masked-announcement bit; unset, a masked patch reads as an observation, shapes still match.
const BG_MASKED_FEAT: usize = 4;
/// Per side of the median.
const N_SPREADS: usize = 3;
/// The only architecture this build decodes.
const ARCH_VERSION: &str = "risk-v5";
/// The only `head.decoder` this build implements — the spline of INFERENCE.md §8.2.
const HEAD_DECODER: &str = "bspline-centre-nodes";
const N_QUANTILES: usize = 1 + 2 * N_SPREADS;
/// z-score denominator (INFERENCE.md §6).
const STD_FLOOR: f64 = 1e-8;
/// PyTorch `F.softplus`: `x > 20 ⇒ softplus(x) == x`.
const SOFTPLUS_THRESHOLD: f64 = 20.0;

/// savgol_coeffs(7,2,pos=6,use='dot') as rationals/42, oldest->newest; newest sample weighs most.
const SAVGOL_TAPS: [f64; 7] = [5.0, -3.0, -6.0, -4.0, 3.0, 15.0, 32.0];
const SAVGOL_DENOM: f64 = 42.0;
/// The goldens and the fp16-agreement probe are pinned to this window.
const SAVGOL_WINDOW: usize = 7;
/// Past the widest offered detent (25), small enough that a hostile value allocates nothing huge.
const SAVGOL_WINDOW_MAX: i32 = 99;

const RAIL_EPS_MGDL: f64 = 1e-3;
/// The 1e-3 risk spread floor alone yields ≳0.06 mg/dL, so a healthy fan clears this hugely.
const COLLAPSE_EPS_MGDL: f64 = 1e-4;
/// Quantile fan ascent, risk space.
const MONOTONE_TOL: f64 = 1e-9;

/// bg_absolute in Kovatchev risk space; carb/insulin in log1p space (INFERENCE.md §6).
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record, Deserialize)]
pub struct ChannelStat {
    pub mean: f64,
    pub std: f64,
}

/// Risk param the checkpoint trained under (§5); fixed kovatchev_f/f_inv must never decode it.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record, Deserialize)]
pub struct KovatchevParams {
    #[serde(rename = "SCALE")]
    pub scale: f64,
    #[serde(rename = "POWER")]
    pub power: f64,
    #[serde(rename = "OFFSET")]
    pub offset: f64,
    /// mg/dL.
    #[serde(rename = "BG_CLAMP_MIN")]
    pub bg_clamp_min: f64,
    /// mg/dL.
    #[serde(rename = "BG_CLAMP_MAX")]
    pub bg_clamp_max: f64,
}

impl KovatchevParams {
    /// f(g)=scale*(ln(g)^power-offset); clamped first so ln's base is positive, NaN=low.
    pub(crate) fn f(&self, mgdl: f64) -> f64 {
        let g = if mgdl.is_nan() {
            self.bg_clamp_min
        } else {
            mgdl.clamp(self.bg_clamp_min, self.bg_clamp_max)
        };
        self.scale * (g.ln().powf(self.power) - self.offset)
    }

    /// f_inv(r)=exp((r/scale+offset)^(1/power)); NaN/-inf=low, +inf=high, clamped [f(min),f(max)].
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

/// Co-trained hour-of-day probe; absent past head_raw is fail-open, no hour surfaced.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct TimeHead {
    pub output_index: i32,
    pub n_bins: i32,
    pub bin_hours: f64,
}

/// Named and shaped in FILE order.
#[derive(Debug, Clone, PartialEq, uniffi::Record, Deserialize)]
pub struct HeadTensorSpec {
    pub name: String,
    pub shape: Vec<i32>,
}

/// BG head beside the artifact, adapter seam; digest checked, mismatch gives silent wrong head_raw.
#[derive(Debug, Clone, PartialEq, uniffi::Record, Deserialize)]
pub struct HeadSpec {
    pub file: String,
    pub dtype: String,
    pub byte_order: String,
    pub activation: String,
    pub sha256: String,
    pub d_model: i32,
    pub hidden: i32,
    pub out_dim: i32,
    /// Rule taking hidden to head's per-step input (§8.2); rejected if build can't implement it.
    pub decoder: String,
    pub tensors: Vec<HeadTensorSpec>,
}

/// The pre/post contract parsed from `descriptor.json` (INFERENCE.md §3.1, SPEC §2.4).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ModelDescriptor {
    pub bg: ChannelStat,
    pub carb: ChannelStat,
    pub insulin: ChannelStat,
    /// Carb-equivalent glucose disposal, g/step; a positive magnitude, never a negative carb.
    pub exercise: ChannelStat,
    pub rope_base: i32,
    /// Additive floor on each softplus spread.
    pub quantile_spread_min: f64,
    /// Blocked-position fill, fp16-safe.
    pub neg_fill: f64,
    pub prediction_horizon_hours: i32,
    pub max_context_patches: i32,
    pub min_context_patches: i32,
    pub patch_size: i32,
    pub n_input_features: i32,
    /// T; window left-padded so future patches sit right, RoPE positions match training.
    pub seq_len: i32,
    /// `M` — the head's slot count, and the cap on the masked set a caller may ask for.
    pub max_masked_patches: i32,
    /// The most spans, and the longest span, the training sampler ever drew.
    pub mask_max_spans: i32,
    pub mask_span_max: i32,
    pub d_model: i32,
    /// The architecture the checkpoint was trained under; only `risk-v5` decodes through here.
    pub arch_version: String,
    /// INFERENCE.md §5.
    pub kovatchev: KovatchevParams,
    /// Default false for real-CGM: a sim-fit delta must never silently narrow bands (§8.4).
    pub conformal_enabled: bool,
    pub time: Option<TimeHead>,
    /// `None` costs no forecast, but a model without a head takes no adapter.
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

#[derive(Deserialize)]
struct NormStatsDto {
    bg_absolute: ChannelStat,
    carb_intake: ChannelStat,
    insulin_combined: ChannelStat,
    /// No default: without it the exercise column would be normalized on another channel's scale.
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

/// Exporter's geometry block verbatim: a projected schema could silently drop a constant.
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
    #[serde(rename = "BG_QUANTILE_SPREAD_MIN")]
    quantile_spread_min: f64,
    neg_fill: f64,
    #[serde(rename = "PREDICTION_HORIZON_HOURS")]
    prediction_horizon_hours: i32,
}

#[derive(Deserialize)]
struct DescriptorDto {
    arch_version: String,
    normalization_stats: NormStatsDto,
    geometry: GeometryDto,
    constants: ConstantsDto,
    /// No safe default: absent ⇒ rejected, never decoded against a guessed scale.
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

/// SPEC §2.4. Never panics: malformed JSON or a missing field returns `Err(CoreError::Decode)`.
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
    // risk-v4 decodes here smooth, finite, wrong: it projected the median onto a DCT subspace.
    if d.arch_version != ARCH_VERSION {
        return Err(CoreError::Decode {
            reason: format!(
                "arch_version {:?} is not {ARCH_VERSION}; this build decodes the B-spline \
                 step-state head and cannot run another architecture",
                d.arch_version
            ),
        });
    }
    if let Some(h) = &d.head {
        if h.decoder != HEAD_DECODER {
            return Err(CoreError::Decode {
                reason: format!(
                    "head decoder {:?} is not {HEAD_DECODER}",
                    h.decoder
                ),
            });
        }
    }
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
        arch_version: d.arch_version,
        kovatchev: d.kovatchev,
        conformal_enabled: d.conformal.map(|x| x.enabled).unwrap_or(false),
        time,
        head: d.head,
    };

    // Decode-critical drift is refused, not trusted.
    for (name, v) in [
        ("seq_len", desc.seq_len),
        ("max_masked_patches", desc.max_masked_patches),
        ("d_model", desc.d_model),
        ("mask_max_spans", desc.mask_max_spans),
        ("mask_span_max", desc.mask_span_max),
    ] {
        // A negative dimension casts to a colossal usize and aborts the process (panic=abort).
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
    // bg_clamp_min>1 keeps ln(g)>0 (else NaN^power); scale>0 keeps f increasing; power>0 finite.
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
    // Prove the round trip is total at both rails before any forecast rides it.
    let (r_lo, r_hi) = (k.f(k.bg_clamp_min), k.f(k.bg_clamp_max));
    if !(r_lo.is_finite() && r_hi.is_finite() && r_lo < r_hi)
        || !k.f_inv(r_lo).is_finite()
        || !k.f_inv(r_hi).is_finite()
    {
        return Err(CoreError::Decode {
            reason: format!("kovatchev transform is not total on [{}, {}]", k.bg_clamp_min, k.bg_clamp_max),
        });
    }
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

/// Odd, in [1, SAVGOL_WINDOW_MAX]; fails closed, an even window has no endpoint-centred abscissa.
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

/// Endpoint taps (window, order=2) as (taps, denom); caller divides ONCE, w=7 stays exact/42.
fn savgol_endpoint_taps(window: usize) -> (Vec<f64>, f64) {
    match window {
        1 => (vec![1.0], 1.0),
        SAVGOL_WINDOW => (SAVGOL_TAPS.to_vec(), SAVGOL_DENOM),
        w => (savgol_endpoint_taps_general(w), 1.0),
    }
}

/// Normalized (sums to 1). `window < 3` is singular: the `S2` division at `m = 0`.
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

/// Causal Savitzky-Golay (§7.1): out[t] is degree-2 fit to x[t-(w-1)..=t]; bad window falls back.
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

/// feat 0 is bg risk-z, 1/2/3 log1p-z (INFERENCE.md §6).
fn normalize_feat(d: &ModelDescriptor, feat: usize, x: f64) -> f64 {
    let s = d.stat(feat);
    let pre = if feat == 0 {
        d.kovatchev.f(x)
    } else {
        x.max(0.0).ln_1p()
    };
    (pre - s.mean) / (s.std + STD_FLOOR)
}

fn denormalize_feat(d: &ModelDescriptor, feat: usize, z: f64) -> f64 {
    let s = d.stat(feat);
    let v = z * (s.std + STD_FLOOR) + s.mean;
    if feat == 0 {
        d.kovatchev.f_inv(v)
    } else {
        v.exp_m1().max(0.0)
    }
}

/// Raw `[bg_mgdl, carb, insulin, exercise]` → z.
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

/// z `[bg, carb, insulin, exercise]` → raw units.
#[uniffi::export]
pub fn denormalize_sample(desc: &ModelDescriptor, z: Vec<f64>) -> Result<Vec<f64>, CoreError> {
    if z.len() != N_CHANNELS {
        return Err(CoreError::Internal {
            reason: format!("denormalize_sample expects {N_CHANNELS} channels, got {}", z.len()),
        });
    }
    Ok((0..N_CHANNELS).map(|f| denormalize_feat(desc, f, z[f])).collect())
}

/// CONTEXT-relative: patch 0 is the oldest real context patch, whatever left-padding precedes it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct MaskSpan {
    pub start_patch: i32,
    pub length: i32,
}

/// patches: T*PATCH_SIZE*N_FEAT step-major; attn_mask T*T additive; slot_sel M*T one-hot rows.
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
    /// Absolute patch index, or `-1` when the window carries no future zone.
    pub first_forecast_patch: i32,
}

/// Spans never abut: a visible patch separates anchor/basis/grouping; a visible future fakes z=0.
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
        out.push((n_ctx, n_real - n_ctx)); // the future zone
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

/// §§7.2-7.4: channels are equal-length series of n_ctx*PATCH_SIZE steps; smoothing moves anchors.
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

    // Filter BG PER VISIBLE RUN: must not leak a masked span's BG into a later anchor.
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

    let pad0 = t - n_real;
    let mut patches = vec![0.0f32; t * PATCH_SIZE * N_FEAT];
    let mut ctx_bg_z = vec![0.0f64; n]; // f64 for the anchor read-back
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

    // No-event baseline is normalize(0), not literal z=0, which would fake a dose via log1p.
    if with_forecast {
        let zbase: [f64; 3] = [
            normalize_feat(desc, 1, 0.0),
            normalize_feat(desc, 2, 0.0),
            normalize_feat(desc, 3, 0.0),
        ];
        for j in 0..pred_steps {
            let patch = pad0 + n_ctx + j / PATCH_SIZE;
            let base = (patch * PATCH_SIZE + j % PATCH_SIZE) * N_FEAT;
            patches[base] = 0.0; // BG withheld
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

    let mut visible = vec![true; t];
    for i in 0..pad0 {
        visible[i] = false; // a pad row is neither visible nor masked
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

    // SPEC/inference.md §4.
    let mut attn = vec![desc.neg_fill as f32; t * t];
    for row in 0..t {
        let row_is_pad = row < pad0;
        let row_is_masked = !row_is_pad && !visible[row];
        for col in 0..t {
            let col_is_pad = col < pad0;
            let allow = if row_is_pad || col_is_pad {
                row == col // a pad row reads only itself; no all-blocked row
            } else {
                visible[col] || row_is_masked
            };
            if allow {
                attn[row * t + col] = 0.0;
            }
        }
    }

    // Surplus slots repeat patch 0 and are discarded downstream.
    let mut slot_sel = vec![0.0f32; m * t];
    let mut slot_patch = vec![0i32; m];
    for j in 0..m {
        let patch = if j < n_masked { slot_patch_real[j] as usize } else { 0 };
        slot_sel[j * t + patch] = 1.0;
        slot_patch[j] = if j < n_masked { slot_patch_real[j] } else { -1 };
    }

    // Left-preferring: only a VISIBLE cell may anchor -- a masked one decodes to a wrong mg/dL.
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
    // Padded slots still need a legal mg/dL anchor; forward asserts every slot clears the floor.
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

/// Cubic B-spline step weights for a span (§8.2); depends only on (l, has_left, has_right).
pub(crate) fn bspline_step_weights(l: usize, has_left: bool, has_right: bool) -> Vec<f64> {
    let s = PATCH_SIZE;
    let lo: i64 = if has_left { 0 } else { 1 };
    let hi: i64 = if has_right { l as i64 + 1 } else { l as i64 };
    let n_nodes = (hi - lo + 1) as usize;
    let mut w = vec![0.0f64; l * s * n_nodes];
    for i in 1..=l as i64 {
        for j in 0..s {
            let dc = (j as f64 - (s as f64 - 1.0) / 2.0) / s as f64;
            let (k, u) = if dc < 0.0 { (i - 1, dc + 1.0) } else { (i, dc) };
            let basis = [
                (1.0 - u).powi(3) / 6.0,
                (3.0 * u.powi(3) - 6.0 * u * u + 4.0) / 6.0,
                (-3.0 * u.powi(3) + 3.0 * u * u + 3.0 * u + 1.0) / 6.0,
                u.powi(3) / 6.0,
            ];
            let row = ((i - 1) as usize * s + j) * n_nodes;
            for (b, o) in basis.iter().zip([-1i64, 0, 1, 2]) {
                let col = ((k + o).clamp(lo, hi) - lo) as usize;
                w[row + col] += b;
            }
        }
    }
    w
}

/// One span of the masked set: head slot start, length, whether each-side neighbour is a node.
pub(crate) struct SpanGeom {
    pub(crate) first_slot: usize,
    pub(crate) length: usize,
    pub(crate) has_left: bool,
    pub(crate) has_right: bool,
}

/// Spans of a masked set with node brackets; padded slots (slot_patch=-1) point at patch 0.
pub(crate) fn span_geometry(slot_patch: &[i32], attn_mask: &[f32], t: usize) -> Vec<SpanGeom> {
    let patch = |j: usize| -> usize { slot_patch[j].max(0) as usize };
    let attends = |row: usize, col: usize| -> bool {
        attn_mask
            .get(row * t + col)
            .is_some_and(|v| *v == 0.0)
    };
    let mut spans: Vec<SpanGeom> = Vec::new();
    for j in 0..slot_patch.len() {
        // A padded slot sits at patch 0, can't continue a span: needs a predecessor at patch -1.
        let continues = j > 0
            && slot_patch[j] >= 0
            && slot_patch[j - 1] >= 0
            && slot_patch[j] == slot_patch[j - 1] + 1;
        if continues {
            spans.last_mut().expect("a span precedes any continuation").length += 1;
        } else {
            spans.push(SpanGeom { first_slot: j, length: 1, has_left: false, has_right: false });
        }
    }
    for sp in spans.iter_mut() {
        let first = patch(sp.first_slot);
        let last = patch(sp.first_slot + sp.length - 1);
        sp.has_left = first > 0 && attends(first, first - 1);
        sp.has_right = last + 1 < t && attends(last, last + 1);
    }
    spans
}

/// Head's per-step input (m_slots*PATCH_SIZE*d_model) fp64 from hidden (t*d_model) (§8.2).
#[uniffi::export]
pub fn step_states(
    desc: &ModelDescriptor,
    hidden: Vec<f32>,
    slot_patch: Vec<i32>,
    attn_mask: Vec<f32>,
) -> Result<Vec<f64>, CoreError> {
    let t = desc.seq_len as usize;
    let d = desc.d_model as usize;
    if hidden.len() != t * d {
        return Err(CoreError::Internal {
            reason: format!("hidden has {} values, want t {t} × d_model {d}", hidden.len()),
        });
    }
    if attn_mask.len() != t * t {
        return Err(CoreError::Internal {
            reason: format!("attn_mask has {} values, want t² {}", attn_mask.len(), t * t),
        });
    }
    if slot_patch.is_empty() || slot_patch.len() > desc.max_masked_patches as usize {
        return Err(CoreError::Internal {
            reason: format!(
                "slot_patch holds {} slots, want 1..={}",
                slot_patch.len(),
                desc.max_masked_patches
            ),
        });
    }
    if slot_patch.iter().any(|&p| p >= t as i32) {
        return Err(CoreError::Internal {
            reason: format!("a slot names a patch outside the graph's T={t}"),
        });
    }
    let node = |patch: usize| -> &[f32] { &hidden[patch * d..(patch + 1) * d] };
    let mut out = vec![0.0f64; slot_patch.len() * PATCH_SIZE * d];
    for sp in span_geometry(&slot_patch, &attn_mask, t) {
        let w = bspline_step_weights(sp.length, sp.has_left, sp.has_right);
        let first = slot_patch[sp.first_slot].max(0) as usize;
        let n_nodes = sp.length + usize::from(sp.has_left) + usize::from(sp.has_right);
        // Nodes lo..=hi ascending: left neighbour if any, then span's own patches, then right.
        let mut nodes: Vec<&[f32]> = Vec::with_capacity(n_nodes);
        if sp.has_left {
            nodes.push(node(first - 1));
        }
        for i in 0..sp.length {
            nodes.push(node(slot_patch[sp.first_slot + i].max(0) as usize));
        }
        if sp.has_right {
            nodes.push(node(first + sp.length));
        }
        for i in 0..sp.length {
            for j in 0..PATCH_SIZE {
                let row = &w[(i * PATCH_SIZE + j) * n_nodes..(i * PATCH_SIZE + j + 1) * n_nodes];
                let dst = ((sp.first_slot + i) * PATCH_SIZE + j) * d;
                for (n, &weight) in row.iter().enumerate() {
                    if weight == 0.0 {
                        continue;
                    }
                    for c in 0..d {
                        out[dst + c] += weight * nodes[n][c] as f64;
                    }
                }
            }
        }
    }
    Ok(out)
}

/// Matches PyTorch `F.softplus` (beta=1, threshold=20).
fn softplus(x: f64) -> f64 {
    if x > SOFTPLUS_THRESHOLD {
        x
    } else {
        x.exp().ln_1p()
    }
}

/// Step-major over decoded slots; median_risk/q_tau_risk risk space, median_bg/bands_mgdl mg/dL.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Forecast {
    pub median_risk: Vec<f64>,
    pub q_tau_risk: Vec<f64>,
    pub median_bg: Vec<f64>,
    pub bands_mgdl: Vec<f64>,
    pub slot_patch: Vec<i32>,
}

/// head_raw is M*PATCH_SIZE*7 risk-space (§8.1), RAW fan; carry_spread (§9) composes in QUADRATURE.
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
    let floor = desc.quantile_spread_min;
    let n_steps = n_masked * PATCH_SIZE;

    // Pointwise: slot's anchor plus head's per-step delta; grouping happened in the step spline.
    let mut median = vec![0.0f64; n_steps];
    for (i, mi) in median.iter_mut().enumerate() {
        let anchor = kov.f(anchors[i / PATCH_SIZE].clamp(kov.bg_clamp_min, kov.bg_clamp_max));
        *mi = anchor + head_raw[i * N_QUANTILES];
    }

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
            // Independent increments add variances; skipped at zero to keep decode bit-identical.
            let (c_up, c_dn) = (carry[k], carry[N_SPREADS + k]);
            up[k] = mi + if c_up == 0.0 { cs_up } else { c_up.hypot(cs_up) };
            dn[k] = mi - if c_dn == 0.0 { cs_dn } else { c_dn.hypot(cs_dn) };
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

/// Rows whose slot sits in [from_patch, to_patch); masked set may hold infill and forecast at once.
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

/// Fan at arbitrary tau, mg/dL: linear interp in RISK space, f_inv; no classifier may use it.
#[uniffi::export]
pub fn band_line(desc: &ModelDescriptor, f: &Forecast, tau: f64) -> Result<Vec<f64>, CoreError> {
    // [`band_line_at`] cannot check this: a whole number of steps can still be the wrong number.
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

/// Same line from a bare fan (n_steps*N_QUANTILES risk values); one body serves both entry points.
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

/// Ascending — `invariants.md` §6.
pub(crate) const QUANTILE_LEVELS: [f64; N_QUANTILES] = [0.05, 0.10, 0.25, 0.50, 0.75, 0.90, 0.95];

/// f_inv clamps to the physical range; a collapsed/runaway model reads as a confident rail.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ForecastStatus {
    Ok,
    NonFinite,
    RailPinned,
    CollapsedBand,
    MisorderedQuantiles,
}

/// §3.6-B: a failed forecast blocks the rails; desc must match what it decoded with.
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

    let finite = f.median_risk.iter().all(|v| v.is_finite())
        && f.median_bg.iter().all(|v| v.is_finite())
        && f.q_tau_risk.iter().all(|v| v.is_finite())
        && f.bands_mgdl.iter().all(|v| v.is_finite());
    if !finite {
        return ForecastStatus::NonFinite;
    }

    if !fan_is_ascending(&f.q_tau_risk, n, N_QUANTILES) {
        return ForecastStatus::MisorderedQuantiles;
    }

    let (lo, hi) = (desc.kovatchev.bg_clamp_min, desc.kovatchev.bg_clamp_max);
    if median_is_rail_pinned(&f.median_bg, lo, hi) {
        return ForecastStatus::RailPinned;
    }

    if fan_is_collapsed(&f.bands_mgdl, n, N_QUANTILES) {
        return ForecastStatus::CollapsedBand;
    }

    ForecastStatus::Ok
}

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

pub(crate) fn median_is_rail_pinned(median_bg: &[f64], lo: f64, hi: f64) -> bool {
    median_bg.iter().all(|&v| v <= lo + RAIL_EPS_MGDL)
        || median_bg.iter().all(|&v| v >= hi - RAIL_EPS_MGDL)
}

pub(crate) fn fan_is_collapsed(bands_mgdl: &[f64], n: usize, nq: usize) -> bool {
    let max_width = (0..n)
        .map(|i| {
            let row = i * nq;
            bands_mgdl[row + nq - 1] - bands_mgdl[row]
        })
        .fold(0.0f64, f64::max);
    max_width < COLLAPSE_EPS_MGDL
}

/// probs: softmax of ORIGIN patch; predicted_hour [0,24); resultant_r [0,1]; belief not timestamp.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct PredictedTime {
    pub probs: Vec<f64>,
    pub predicted_hour: f64,
    pub resultant_r: f64,
    pub n_bins: i32,
    pub bin_hours: f64,
}

/// A zero or underflowing partition falls back to uniform, so no NaN escapes.
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

/// Port of time_of_day_resultant; bin k centers at bin_hours*(k+0.5); hour is FP noise at R->0.
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

/// time_logits flat (P,n_bins); reduces to ORIGIN patch, as T1DMAI's estimate_current_hour does.
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

    /// T1DMAI's reference for the same inputs; regenerated by `T1DMAI/exporters/rust_golden.py`.
    const PIPELINE: &str = include_str!("testdata/pipeline_golden.json");

    fn pipeline() -> Value {
        serde_json::from_str(PIPELINE).unwrap()
    }

    /// A case added to the generator and not named here fails the count check below.
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

    /// At the reference's own unfiltered window: T1DMAI applies no smoother.
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

    /// The reference's own head output and anchors; feeding ours would let two errors cancel.
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

    /// Not any released transform: proves the pipeline follows the descriptor, not a constant.
    const OTHER_KOVATCHEV: KovatchevParams = KovatchevParams {
        scale: 1.509,
        power: 1.084,
        offset: 5.381,
        bg_clamp_min: 20.0,
        bg_clamp_max: 500.0,
    };

    const SHIPPED_KOVATCHEV: KovatchevParams = KovatchevParams {
        scale: 2.2211457449985317,
        power: 1.084,
        offset: 5.540076976170212,
        bg_clamp_min: 10.0,
        bg_clamp_max: 400.0,
    };

    const REFERENCE_DESCRIPTOR: &str = include_str!("testdata/reference_descriptor.json");

    /// A real exported descriptor; tests clone it with `..`, not an impossible geometry.
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

    #[test]
    fn parse_descriptor_reference() {
        let d = test_descriptor();
        assert_eq!(d.rope_base, 1000);
        assert_eq!(d.arch_version, ARCH_VERSION);
        assert_eq!(d.quantile_spread_min, 1e-3);
        assert_eq!(d.neg_fill, -30000.0);
        assert_eq!(d.prediction_horizon_hours, 2);
        assert_eq!(d.prediction_patches().unwrap(), 4);
        assert!(!d.conformal_enabled);
        assert_eq!(d.n_input_features, N_FEAT as i32);
        assert_eq!(d.seq_len, d.max_context_patches + 4);
        assert!(d.max_context_patches >= d.min_context_patches);
        assert!(d.max_masked_patches >= 4, "the head must hold at least a forecast");
        assert!(d.d_model > 0);
        assert!(d.exercise.std > 0.0);
        let head = d.head.as_ref().expect("the reference export ships a head file");
        assert_eq!(head.out_dim, N_QUANTILES as i32, "the head emits one row per step");
        assert_eq!(head.decoder, HEAD_DECODER);
        assert_eq!(head.sha256.len(), 64);
    }

    #[test]
    fn parse_descriptor_refuses_the_retired_three_feature_input() {
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

    /// Pinned numerically: a stale descriptor beside a newer .pte decodes plausible, wrong mg/dL.
    #[test]
    fn reference_descriptor_is_anchored_on_the_trained_range() {
        let k = test_descriptor().kovatchev;
        assert_eq!(k, SHIPPED_KOVATCHEV, "reference descriptor must carry the shipped constants");
        // The clamp is the physical range; the anchors the constants were solved for sit inside it.
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

    #[test]
    fn parse_descriptor_rejects_decode_critical_drift() {
        assert!(
            parse_descriptor(REFERENCE_DESCRIPTOR.to_string()).is_ok(),
            "reference descriptor must still parse"
        );

        let bad = |block: &str, key: &str, val: Value| -> Result<ModelDescriptor, CoreError> {
            let mut v: Value = serde_json::from_str(REFERENCE_DESCRIPTOR).unwrap();
            v[block][key] = val;
            parse_descriptor(v.to_string())
        };
        let is_decode =
            |r: Result<ModelDescriptor, CoreError>| matches!(r, Err(CoreError::Decode { .. }));

        assert!(is_decode(bad("geometry", "PATCH_SIZE", serde_json::json!(4))));
        assert!(is_decode(bad("constants", "neg_fill", serde_json::json!(0.0))));
        assert!(is_decode(bad("constants", "neg_fill", serde_json::json!(30000.0))));
        assert!(is_decode(bad("constants", "BG_QUANTILE_SPREAD_MIN", serde_json::json!(-1e-3))));
        // NaN is not representable in JSON: it serializes to null.
        assert!(is_decode(bad("constants", "BG_QUANTILE_SPREAD_MIN", serde_json::json!(f64::NAN))));
        assert!(is_decode(bad("geometry", "MIN_CONTEXT_PATCHES", serde_json::json!(0))));
        assert!(is_decode(bad("geometry", "MAX_CONTEXT_PATCHES", serde_json::json!(8))));
        assert!(is_decode(bad("constants", "PREDICTION_HORIZON_HOURS", serde_json::json!(0))));
        assert!(is_decode(bad("constants", "ROPE_BASE", serde_json::json!(0))));
    }

    /// risk-v4 decodes here finite, plausible, wrong: its median was a per-span DCT projection.
    #[test]
    fn parse_descriptor_refuses_an_earlier_architecture() {
        let mut v: Value = serde_json::from_str(REFERENCE_DESCRIPTOR).unwrap();
        v["arch_version"] = serde_json::json!("risk-v4");
        assert!(matches!(parse_descriptor(v.to_string()), Err(CoreError::Decode { .. })));

        let mut v: Value = serde_json::from_str(REFERENCE_DESCRIPTOR).unwrap();
        v.as_object_mut().unwrap().remove("arch_version");
        assert!(matches!(parse_descriptor(v.to_string()), Err(CoreError::Decode { .. })));

        // A decoder this build does not implement is refused, never assumed to be this one.
        let mut v: Value = serde_json::from_str(REFERENCE_DESCRIPTOR).unwrap();
        v["head"]["decoder"] = serde_json::json!("step-basis-dct");
        assert!(matches!(parse_descriptor(v.to_string()), Err(CoreError::Decode { .. })));

        // A sidecar synced without the head block decodes `head_raw` alone and needs no name.
        let mut v: Value = serde_json::from_str(REFERENCE_DESCRIPTOR).unwrap();
        v.as_object_mut().unwrap().remove("head");
        let d = parse_descriptor(v.to_string()).expect("a head-less sidecar still forecasts");
        assert!(d.head.is_none());
    }

    #[test]
    fn parse_descriptor_requires_the_kovatchev_block() {
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
        assert!(is_decode(bad("BG_CLAMP_MAX", serde_json::json!(5.0))));
    }

    #[test]
    fn decode_follows_the_descriptor_not_a_baked_constant() {
        // Decoding under wrong constants yields plausible WRONG mg/dL: 120 reads ~102, 300~394.
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

        // A flat head_raw anchors the median, recovered through each descriptor's own f/f_inv.
        let flat = vec![0.0f64; 4 * PATCH_SIZE * N_QUANTILES];
        for kov in [OTHER_KOVATCHEV, SHIPPED_KOVATCHEV] {
            let d = ModelDescriptor { kovatchev: kov, ..test_descriptor() };
            let f =
                assemble_decode(&d, flat.clone(), vec![120.0; 4], vec![0, 1, 2, 3], 4, vec![])
                    .unwrap();
            for v in &f.median_bg {
                assert!((v - 120.0).abs() < 1e-6, "anchor round trip under {kov:?} gave {v}");
            }
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
        // Meaningless without the descriptor: 40 mg/dL is a rail under one param, not the other.
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

        assert_eq!(forecast_degeneracy_check(&shipped, &pinned(10.0)), ForecastStatus::RailPinned);
        assert_eq!(forecast_degeneracy_check(&shipped, &pinned(400.0)), ForecastStatus::RailPinned);
        assert_eq!(forecast_degeneracy_check(&shipped, &pinned(20.0)), ForecastStatus::Ok);
        assert_eq!(forecast_degeneracy_check(&other, &pinned(20.0)), ForecastStatus::RailPinned);
        assert_eq!(forecast_degeneracy_check(&other, &pinned(500.0)), ForecastStatus::RailPinned);
        assert_eq!(forecast_degeneracy_check(&other, &pinned(40.0)), ForecastStatus::Ok);
    }

    #[test]
    fn descriptor_kovatchev_guards_are_total() {
        // The same totality the clinical pair guarantees (INFERENCE.md §5).
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


    /// The five detents the app offers: Off / Standard / Moderate / Strong / Heavy.
    const STOPS: [i32; 5] = [1, 7, 13, 19, 25];

    #[test]
    fn savgol_solver_reproduces_reference_taps() {
        let want: Vec<f64> = SAVGOL_TAPS.iter().map(|t| t / SAVGOL_DENOM).collect();
        assert_close(&savgol_endpoint_taps_general(7), &want, 1e-15, "solver taps w=7");
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
        // Must never panic: the crate is `panic = "abort"`.
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










    /// Each edge takes hypot(own carry, own native offset); the median does not move (§8.1, §9).
    #[test]
    fn carry_spread_is_per_level() {
        let d = test_descriptor();
        let mut head = vec![0.0f64; 4 * PATCH_SIZE * N_QUANTILES];
        for (i, h) in head.iter_mut().enumerate() {
            *h = ((i % 5) as f64 - 2.0) * 0.3; // asymmetric on both sides
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
            let med = bare.q_tau_risk[row + N_SPREADS];
            assert!((per.q_tau_risk[row + N_SPREADS] - med).abs() < 1e-12,
                "the carry moved the median");
            for k in 0..N_SPREADS {
                let (up_i, dn_i) = (row + N_SPREADS + 1 + k, row + N_SPREADS - 1 - k);
                let (nat_up, nat_dn) = (bare.q_tau_risk[up_i] - med, med - bare.q_tau_risk[dn_i]);
                let (got_up, got_dn) = (per.q_tau_risk[up_i] - med, med - per.q_tau_risk[dn_i]);
                assert!((got_up - carry[k].hypot(nat_up)).abs() < 1e-12,
                    "up level {k} offset {got_up}, want hypot({}, {nat_up})", carry[k]);
                assert!((got_dn - carry[N_SPREADS + k].hypot(nat_dn)).abs() < 1e-12,
                    "dn level {k} offset {got_dn}, want hypot({}, {nat_dn})", carry[N_SPREADS + k]);
                assert!(got_up < carry[k] + nat_up && got_dn < carry[N_SPREADS + k] + nat_dn,
                    "quadrature must sit inside the perfectly-correlated additive bound");
            }
        }

        let one = assemble_decode(&d, head.clone(), anchors.clone(), slots.clone(), 4, vec![0.37])
            .unwrap();
        let six = assemble_decode(&d, head.clone(), anchors.clone(), slots.clone(), 4, vec![0.37; 6])
            .unwrap();
        assert_eq!(one.q_tau_risk, six.q_tau_risk);
        assert_eq!(bare.q_tau_risk, assemble_decode(&d, head.clone(), anchors.clone(), slots.clone(), 4, vec![0.0]).unwrap().q_tau_risk);

        for bad in [vec![0.1, 0.2], vec![0.0; 7], vec![-0.1; 6], vec![f64::NAN; 6]] {
            assert!(
                assemble_decode(&d, head.clone(), anchors.clone(), slots.clone(), 4, bad.clone())
                    .is_err(),
                "carry {bad:?} must be refused"
            );
        }
    }

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
        let d = test_descriptor();
        let mut head = vec![0.0f64; 4 * 6 * 7];
        for i in 0..24 {
            head[i * 7] = 100.0;
        }
        let f = assemble_decode(&d, head, vec![120.0; 4], vec![0, 1, 2, 3], 4, vec![]).unwrap();
        let hi = d.kovatchev.bg_clamp_max;
        assert!(f.median_bg.iter().all(|&v| (v - hi).abs() < 1e-6));
        assert_eq!(forecast_degeneracy_check(&test_descriptor(), &f), ForecastStatus::RailPinned);
    }

    #[test]
    fn degeneracy_collapsed_band() {
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
        f.q_tau_risk.swap(0, 6);
        assert_eq!(forecast_degeneracy_check(&test_descriptor(), &f), ForecastStatus::MisorderedQuantiles);
    }

    #[test]
    fn degeneracy_ok_on_healthy_fan() {
        let d = test_descriptor();
        let f = decoded(&case("forecast"), &d);
        assert_eq!(forecast_degeneracy_check(&test_descriptor(), &f), ForecastStatus::Ok);
    }

    const TIME_GOLDEN: &str = include_str!("testdata/time_head_golden.json");

    fn time_golden() -> Value {
        serde_json::from_str(TIME_GOLDEN).unwrap()
    }

    fn circ_dhour(a: f64, b: f64) -> f64 {
        let mut d = (a - b).rem_euclid(24.0);
        if d > 12.0 {
            d = 24.0 - d;
        }
        d.abs()
    }

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
            assert_eq!(r >= r_deg, hour_defined, "{name}: hour_defined vs R>=eps");
            if hour_defined {
                let dh = circ_dhour(hour, want_hour);
                assert!(dh <= hour_tol, "{name}: hour got {hour}, want {want_hour} (circΔ={dh:.3e} > {hour_tol:.1e})");
            }
        }
    }

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

        let mut flat: Vec<f64> = Vec::new();
        for r in &patch_rows {
            flat.extend(f64s(&r["logits"]));
        }
        let pt = decode_time(flat, n_bins, bin_hours).expect("decode_time must succeed");

        let want = &patch_rows[0];
        assert_eq!(pt.n_bins, n_bins);
        assert_eq!(pt.bin_hours, bin_hours);
        assert_close(&pt.probs, &f64s(&want["probs"]), 1e-4, "decode_time probs");
        let dh = circ_dhour(pt.predicted_hour, want["hour"].as_f64().unwrap());
        assert!(dh <= g["hour_tol"].as_f64().unwrap(), "decode_time hour circΔ={dh:.3e}");
        assert!((pt.resultant_r - want["R"].as_f64().unwrap()).abs() <= g["R_tol"].as_f64().unwrap());
    }

    #[test]
    fn decode_time_rejects_bad_input() {
        assert!(decode_time(vec![], 12, 2.0).is_err());
        assert!(decode_time(vec![0.0; 5], 12, 2.0).is_err());
        assert!(decode_time(vec![0.0; 12], 0, 2.0).is_err());
        assert!(decode_time(vec![0.0; 12], 12, 0.0).is_err());
        let mut nan = vec![0.0; 12];
        nan[3] = f64::NAN;
        assert!(decode_time(nan, 12, 2.0).is_err());
    }

    #[test]
    fn parse_descriptor_time_section() {
        let t = test_descriptor().time.expect("time section must parse");
        assert_eq!(t.n_bins, 12);
        assert_eq!(t.bin_hours, 2.0);

        let mut v: Value = serde_json::from_str(REFERENCE_DESCRIPTOR).unwrap();
        v.as_object_mut().unwrap().remove("time");
        assert!(parse_descriptor(v.to_string()).unwrap().time.is_none());

        v["time"] = serde_json::json!({ "output_index": 1, "n_bins": 0, "bin_hours": 2.0 });
        assert!(parse_descriptor(v.to_string()).is_err());
    }

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

            let want = f64s(&c["patches_f32"]);
            let got: Vec<f64> = gi.patches.iter().map(|v| *v as f64).collect();
            assert_close(&got, &want, tol, &format!("{name}: patches"));

            // A digest catches a rule wrong everywhere as readily as one wrong cell.
            let bytes: Vec<u8> = gi.attn_mask.iter().map(|v| u8::from(*v == 0.0)).collect();
            assert_eq!(
                format!("{:x}", Sha256::digest(&bytes)),
                c["attn_sha256"].as_str().unwrap(),
                "{name}: attention pattern"
            );

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

    /// span_ladder holds spans of 1, 2, 3 and 4 patches at once, which no single forecast reaches.
    #[test]
    fn the_median_is_the_anchor_plus_the_heads_own_delta() {
        let d = test_descriptor();
        for name in ["span_ladder", "span_ladder_long", "infill"] {
            let c = case(name);
            let f = decoded(&c, &d);
            let head = f64s(&c["head_raw"]);
            let anchors = f64s(&c["anchors"]);
            for i in 0..f.median_risk.len() {
                let want = d.kovatchev.f(anchors[i / PATCH_SIZE]) + head[i * N_QUANTILES];
                assert!(
                    (f.median_risk[i] - want).abs() < 1e-12,
                    "{name}[{i}]: median {} is not anchor + delta {want}",
                    f.median_risk[i]
                );
            }
        }
    }

    /// Whole consumer path in one assert: hidden, node gather, spline, head must rebuild head_raw.
    #[test]
    fn the_head_file_rebuilds_head_raw_from_hidden() {
        use sha2::{Digest, Sha256};

        let d = test_descriptor();
        let g = pipeline();
        let tol = g["tolerances"]["head_raw_from_hidden"].as_f64().unwrap();

        let mut bytes: Vec<u8> = Vec::new();
        let mut tensors: Vec<HeadTensorSpec> = Vec::new();
        for t in g["head"].as_array().unwrap() {
            for v in t["values"].as_array().unwrap() {
                bytes.extend_from_slice(&(v.as_f64().unwrap() as f32).to_le_bytes());
            }
            tensors.push(HeadTensorSpec {
                name: t["name"].as_str().unwrap().to_string(),
                shape: t["shape"].as_array().unwrap().iter().map(|s| s.as_i64().unwrap() as i32).collect(),
            });
        }
        // Read off l0.weight (hidden, d_model): a wrong-width head pins against wrong geometry.
        let l0 = &tensors.iter().find(|t| t.name == "l0.weight").expect("golden ships l0.weight").shape;
        let spec = HeadSpec {
            file: "golden.head.bin".into(),
            dtype: "fp32".into(),
            byte_order: "little".into(),
            activation: "silu".into(),
            sha256: format!("{:x}", Sha256::digest(&bytes)),
            d_model: l0[1],
            hidden: l0[0],
            out_dim: N_QUANTILES as i32,
            decoder: HEAD_DECODER.into(),
            tensors,
        };
        let head = crate::head::HeadModel::parse(bytes, spec).expect("golden head parses");

        let mut ran = 0;
        for name in CASES {
            let c = case(name);
            if c["hidden_f32"].is_null() {
                continue; // a synthetic-head ladder never ran the trunk
            }
            let gi = built(&c, &d);
            let hidden: Vec<f32> =
                c["hidden_f32"].as_array().unwrap().iter().map(|v| v.as_f64().unwrap() as f32).collect();
            let states =
                step_states(&d, hidden, gi.slot_patch.clone(), gi.attn_mask.clone()).expect("step states");
            let got = head.forward(states, gi.m_slots).expect("head re-run");
            // Only the real slots: a padded slot's row is discarded before it reaches a decode.
            let n = gi.n_masked as usize * PATCH_SIZE * N_QUANTILES;
            assert_close(&got[..n], &f64s(&c["head_raw"])[..n], tol, &format!("{name}: head_raw"));
            ran += 1;
        }
        assert!(ran >= 2, "only {ran} cases carried a hidden state to rebuild from");
    }

    #[test]
    fn every_spline_row_sums_to_one() {
        for l in 1..=8usize {
            for has_left in [false, true] {
                for has_right in [false, true] {
                    let n_nodes = l + usize::from(has_left) + usize::from(has_right);
                    let w = bspline_step_weights(l, has_left, has_right);
                    assert_eq!(w.len(), l * PATCH_SIZE * n_nodes);
                    for r in 0..l * PATCH_SIZE {
                        let s: f64 = w[r * n_nodes..(r + 1) * n_nodes].iter().sum();
                        assert!(
                            (s - 1.0).abs() < 1e-12,
                            "L={l} left={has_left} right={has_right} row {r} sums to {s}"
                        );
                        assert!(
                            w[r * n_nodes..(r + 1) * n_nodes].iter().all(|v| *v >= 0.0),
                            "a cubic B-spline weight went negative"
                        );
                    }
                }
            }
        }
    }

    /// Node bracket is T1DMAI's per case: a pad row never becomes a node's edge-read neighbour.
    #[test]
    fn span_geometry_matches_the_reference() {
        let d = test_descriptor();
        for name in CASES {
            let c = case(name);
            let gi = built(&c, &d);
            let got = span_geometry(&gi.slot_patch[..gi.n_masked as usize], &gi.attn_mask, gi.t as usize);
            let want = c["spans"].as_array().unwrap();
            assert_eq!(got.len(), want.len(), "{name}: span count");
            for (g, w) in got.iter().zip(want) {
                assert_eq!(g.first_slot as i64, w["first_slot"].as_i64().unwrap(), "{name}: first_slot");
                assert_eq!(g.length as i64, w["length"].as_i64().unwrap(), "{name}: length");
                assert_eq!(g.has_left, w["has_left"].as_bool().unwrap(), "{name}: has_left");
                assert_eq!(g.has_right, w["has_right"].as_bool().unwrap(), "{name}: has_right");
            }
        }
    }

    /// A padded slot reads patch 0 and is its own span, as the graph's `slot_sel` makes it.
    #[test]
    fn a_padded_slot_is_a_singleton_span_at_patch_zero() {
        let t = 8usize;
        let attn = vec![0.0f32; t * t];
        let spans = span_geometry(&[3, 4, -1, -1], &attn, t);
        assert_eq!(spans.len(), 3);
        assert_eq!((spans[0].first_slot, spans[0].length), (0, 2));
        assert_eq!((spans[1].first_slot, spans[1].length), (2, 1));
        assert_eq!((spans[2].first_slot, spans[2].length), (3, 1));
        assert!(!spans[1].has_left, "patch 0 has no left neighbour");
    }

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
        let bad = |block: &str, key: &str, val: Value| {
            let mut v: Value = serde_json::from_str(REFERENCE_DESCRIPTOR).unwrap();
            v[block][key] = val;
            parse_descriptor(v.to_string())
        };
        for key in ["T", "MAX_MASKED_PATCHES", "D_MODEL"] {
            assert!(bad("geometry", key, serde_json::json!(-1)).is_err(), "{key} = -1");
            assert!(bad("geometry", key, serde_json::json!(0)).is_err(), "{key} = 0");
        }
        assert!(bad("geometry", "T", serde_json::json!(4)).is_err());
    }

    /// Over the whole series, the filter would leak a masked span's glucose into a later anchor.
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
        // abutting spans
        assert!(call(vec![sp(10, 2), sp(12, 2)], false).is_err());
        // abuts the future zone
        assert!(call(vec![sp(d.min_context_patches - 2, 2)], true).is_err());
        // more masked patches than slots
        let too_many: Vec<MaskSpan> =
            (0..d.max_masked_patches + 1).map(|i| sp(i * 2, 1)).collect();
        assert!(call(too_many, false).is_err());
        // off the end of the observed context
        assert!(call(vec![sp(d.min_context_patches - 1, 4)], false).is_err());
        // nothing masked at all
        assert!(call(vec![], false).is_err());
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
        // ragged channels; a history that does not tile the patch
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

    /// An unannounced dose takes the `normalize(0)` baseline, not a literal `z = 0`.
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
        // A caller error, not a silently ignored argument.
        assert!(build_graph_input(
            &d, vec![120.0; n], vec![0.0; n], vec![0.0; n], vec![0.0; n],
            Some(carb), None, None, vec![MaskSpan { start_patch: 10, length: 2 }], false, 1,
        )
        .is_err());
    }

    #[test]
    fn band_line_at_the_median_is_the_median() {
        let d = test_descriptor();
        let f = decoded(&case("forecast"), &d);
        let line = band_line(&d, &f, 0.5).unwrap();
        assert_close(&line, &f.median_bg, 1e-12, "band_line(0.5)");
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
        // Clamped to the outermost edge, not extrapolated.
        let lo = band_line(&d, &f, 0.0).unwrap();
        let hi = band_line(&d, &f, 1.0).unwrap();
        assert_close(&lo, &band_line(&d, &f, 0.05).unwrap(), 1e-12, "tau below the fan");
        assert_close(&hi, &band_line(&d, &f, 0.95).unwrap(), 1e-12, "tau above the fan");
        assert!(band_line(&d, &f, f64::NAN).is_err());
    }

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

    #[test]
    fn band_line_at_refuses_a_ragged_fan() {
        let d = test_descriptor();
        assert!(band_line_at(&d, vec![0.1; N_QUANTILES * 3 + 1], 0.5).is_err());
        assert!(band_line_at(&d, vec![0.1; N_QUANTILES - 1], 0.5).is_err());
        // Empty is a whole number of steps — zero of them.
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
        let infill = forecast_slice(&f, 0, first).unwrap();
        assert!(infill.slot_patch.iter().all(|p| *p < first));
        assert_eq!(infill.median_bg.len() + fc.median_bg.len(), f.median_bg.len());
        // An empty range is an error, not an empty forecast nothing checks.
        assert!(forecast_slice(&f, first, first).is_err());
    }
}
