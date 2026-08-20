//! The BG head, re-run on device, and the low-rank adapter that personalises it.
//!
//! The exported `.pte` bakes its weights in and offers no seam. What it does offer is
//! `slot_hidden` — the trunk's final-normed hidden state at each masked slot — and the export
//! writes `bg_head`'s own weights beside the artifact. Together those let this crate
//! reproduce `head_raw` outside the graph, and therefore adapt it: the trunk stays frozen in
//! the `.pte`, and everything trainable lives in a few thousand low-rank numbers here.
//!
//! Two adapter sites, both optional and both zero at initialisation, so an attached-but-
//! untrained adapter is the identity and the fan does not move:
//!
//! * a rank-`r` bottleneck on the hidden state itself, `h + s·B_h(A_h h)` — it re-weights the
//!   representation the head reads, which is where a patient differs from the simulator pool;
//! * a rank-`r` delta on each of the head's three `Linear`s, `y = Wx + b + s·B(Ax)` — the
//!   ordinary LoRA placement.
//!
//! **What this can and cannot do.** The trunk's attention and FFN blocks are frozen inside the
//! graph and no gradient reaches them. This adapts the representation the head consumes and
//! the head itself; it does not retrain the model. That is the whole reason it is cheap enough
//! to fit on a phone from one patient's own history.
//!
//! Everything is fp64, like the rest of the pre/post path. Training is exact analytic
//! gradients through the same assembly `preproc::assemble_decode` performs — the pinball loss
//! is read on the ASSEMBLED fan, not on `head_raw`, because the fan is what the patient sees
//! and the per-span projection between them is not an identity.

use std::sync::Mutex;

use sha2::{Digest, Sha256};

use crate::preproc::{
    assemble_decode, global_median_basis, global_median_dim, HeadSpec, ModelDescriptor,
    QUANTILE_LEVELS,
};
use crate::CoreError;

/// Steps per patch — the head expands each slot to this many timesteps.
const PATCH_SIZE: usize = 6;
/// Quantile spreads per side of the median.
const N_SPREADS: usize = 3;
/// `1 + 2·N_SPREADS`.
const N_QUANTILES: usize = 7;
/// Floor of the cosine learning-rate schedule, as a fraction of `LoraTrainOpts::lr`.
const LR_MIN_RATIO: f64 = 0.1;

/// What the counterfactual guard is run with at the end of a fit.
///
/// **These numbers are reasoned, not measured.** The retention band is deliberately wide: it is
/// meant to catch a COLLAPSE and a runaway AMPLIFICATION, not to police an adapter that shifts
/// the response by a third. Expect to retune after three real fits, and change them here rather
/// than at a call site so every surface reports the same bar.
/// `min_frozen_response`: below 2 mg/dL per unit at the horizon the frozen model has no response
/// worth preserving, and a ratio against it would be noise divided by noise — hence Inconclusive
/// rather than a pass.
const GUARD_OPTS_FIT: LoraGuardOpts = LoraGuardOpts {
    max_windows: 64,
    min_windows: 8,
    probe_dose_u: 1.0,
    min_frozen_response: 2.0,
    min_retention: 0.25,
    max_retention: 4.0,
    min_sign_agreement: 0.75,
};
/// The bar the fit's own guard pass measures against, exported so a probe of a stored adapter
/// measures against the SAME one. Two copies of a threshold are two thresholds the day one moves.
#[uniffi::export]
pub fn lora_guard_opts_fit() -> LoraGuardOpts {
    GUARD_OPTS_FIT
}

/// Serialized-adapter magic and version.
const LORA_MAGIC: &[u8; 8] = b"T1DMLORA";
const LORA_VERSION: u16 = 2;

fn sigmoid(x: f64) -> f64 {
    if x >= 0.0 {
        1.0 / (1.0 + (-x).exp())
    } else {
        let e = x.exp();
        e / (1.0 + e)
    }
}

fn silu(x: f64) -> f64 {
    x * sigmoid(x)
}

/// `d/dx [x·σ(x)] = σ(x)·(1 + x·(1 − σ(x)))`.
fn silu_grad(x: f64) -> f64 {
    let s = sigmoid(x);
    s * (1.0 + x * (1.0 - s))
}

// ── The frozen head ─────────────────────────────────────────────────────────────────

/// One dense layer of the head, row-major `(out, in)` as torch stores it.
struct Linear {
    w: Vec<f64>,
    b: Vec<f64>,
    n_in: usize,
    n_out: usize,
}

impl Linear {
    fn forward(&self, x: &[f64], out: &mut [f64]) {
        for o in 0..self.n_out {
            let row = &self.w[o * self.n_in..(o + 1) * self.n_in];
            let mut acc = self.b[o];
            for (i, xi) in x.iter().enumerate() {
                acc += row[i] * xi;
            }
            out[o] = acc;
        }
    }

    /// `dx += Wᵀ dy`.
    fn backward_input(&self, dy: &[f64], dx: &mut [f64]) {
        for o in 0..self.n_out {
            let g = dy[o];
            if g == 0.0 {
                continue;
            }
            let row = &self.w[o * self.n_in..(o + 1) * self.n_in];
            for (i, dxi) in dx.iter_mut().enumerate() {
                *dxi += row[i] * g;
            }
        }
    }
}

/// The head as the export wrote it: the within-patch basis plus three `Linear`s. Frozen —
/// nothing here is ever a training parameter.
#[derive(uniffi::Object)]
pub struct HeadModel {
    /// The digest of the file these weights came from — the identity an adapter is bound to.
    sha256: String,
    step_basis: Vec<f64>, // (PATCH_SIZE, K) row-major
    l0: Linear,
    l1: Linear,
    l2: Linear,
    d_model: usize,
    hidden: usize,
    k: usize,
    /// A parsed adapter, or none. Held here so a forward is one FFI call rather than one per
    /// slot with the weights crossing each time.
    lora: Mutex<Option<Lora>>,
}

fn read_f32_le(buf: &[u8], off: usize, n: usize) -> Result<Vec<f64>, CoreError> {
    let end = off + n * 4;
    if end > buf.len() {
        return Err(CoreError::Decode {
            reason: format!("head file is {} bytes, needs at least {end}", buf.len()),
        });
    }
    Ok((0..n)
        .map(|i| {
            let j = off + i * 4;
            f32::from_le_bytes([buf[j], buf[j + 1], buf[j + 2], buf[j + 3]]) as f64
        })
        .collect())
}

#[uniffi::export]
impl HeadModel {
    /// Parse the flat fp32 head file against the descriptor's `head` block.
    ///
    /// The digest is checked, not merely recorded: a head paired with the wrong graph
    /// reproduces a plausible, finite, wrong `head_raw`, and no downstream guard can see it.
    /// The tensor order comes from the block, so a format change is a descriptor change.
    #[uniffi::constructor]
    pub fn parse(bytes: Vec<u8>, spec: HeadSpec) -> Result<std::sync::Arc<Self>, CoreError> {
        if spec.activation != "silu" {
            return Err(CoreError::Decode {
                reason: format!("unsupported head activation {:?} (want \"silu\")", spec.activation),
            });
        }
        if spec.byte_order != "little" || spec.dtype != "fp32" {
            return Err(CoreError::Decode {
                reason: format!(
                    "unsupported head encoding {} / {} (want fp32 little)",
                    spec.dtype, spec.byte_order
                ),
            });
        }
        let digest = format!("{:x}", Sha256::digest(&bytes));
        if digest != spec.sha256 {
            return Err(CoreError::Decode {
                reason: "head file digest does not match the descriptor; the head and the \
                         graph are not from the same export"
                    .into(),
            });
        }
        let mut off = 0usize;
        let mut ten: Vec<(String, Vec<f64>, Vec<i32>)> = Vec::with_capacity(spec.tensors.len());
        for t in &spec.tensors {
            let n: usize = t.shape.iter().map(|&d| d.max(0) as usize).product();
            if n == 0 {
                return Err(CoreError::Decode {
                    reason: format!("head tensor {} has an empty shape", t.name),
                });
            }
            ten.push((t.name.clone(), read_f32_le(&bytes, off, n)?, t.shape.clone()));
            off += n * 4;
        }
        if off != bytes.len() {
            return Err(CoreError::Decode {
                reason: format!("head file has {} bytes, tensors account for {off}", bytes.len()),
            });
        }
        let take = |name: &str| -> Result<Vec<f64>, CoreError> {
            ten.iter()
                .find(|(n, _, _)| n == name)
                .map(|(_, v, _)| v.clone())
                .ok_or_else(|| CoreError::Decode {
                    reason: format!("head file is missing tensor {name}"),
                })
        };
        let d = spec.d_model.max(0) as usize;
        let h = spec.hidden.max(0) as usize;
        let k = spec.step_basis_dim.max(0) as usize;
        let out = spec.out_dim.max(0) as usize;
        if d == 0 || h == 0 || k == 0 || out != k * N_QUANTILES {
            return Err(CoreError::Decode {
                reason: format!(
                    "head geometry invalid: d_model={d} hidden={h} k={k} out_dim={out} \
                     (out_dim must be k·{N_QUANTILES})"
                ),
            });
        }
        let sb = take("step_basis")?;
        if sb.len() != PATCH_SIZE * k {
            return Err(CoreError::Decode {
                reason: format!("step_basis has {} values, want {}", sb.len(), PATCH_SIZE * k),
            });
        }
        let mk = |wn: &str, bn: &str, n_in: usize, n_out: usize| -> Result<Linear, CoreError> {
            let w = take(wn)?;
            let b = take(bn)?;
            if w.len() != n_in * n_out || b.len() != n_out {
                return Err(CoreError::Decode {
                    reason: format!(
                        "{wn}/{bn} shaped {}/{} , want {}/{n_out}",
                        w.len(),
                        b.len(),
                        n_in * n_out
                    ),
                });
            }
            Ok(Linear { w, b, n_in, n_out })
        };
        Ok(std::sync::Arc::new(HeadModel {
            sha256: digest,
            step_basis: sb,
            l0: mk("l0.weight", "l0.bias", d, h)?,
            l1: mk("l1.weight", "l1.bias", h, h)?,
            l2: mk("l2.weight", "l2.bias", h, out)?,
            d_model: d,
            hidden: h,
            k,
            lora: Mutex::new(None),
        }))
    }

    /// Attach an adapter, or detach with `None`. Attaching is instant and reversible: the base
    /// weights are untouched, so detaching restores the graph's own fan exactly.
    pub fn set_lora(&self, lora: Option<LoraWeights>) -> Result<(), CoreError> {
        let parsed = match lora {
            None => None,
            Some(w) => {
                // Geometry alone does not identify a head: two checkpoints of the same width have
                // the same one, and an adapter fitted on either would load into the other and
                // decode plausibly, finitely, wrong.
                if w.head_sha256 != self.sha256 {
                    return Err(CoreError::Internal {
                        reason: "adapter was fitted on a different head than this model's".into(),
                    });
                }
                Some(Lora::from_weights(&w, self.d_model, self.hidden, self.k * N_QUANTILES)?)
            }
        };
        *self.lora.lock().expect("lora mutex") = parsed;
        Ok(())
    }

    /// The digest of the head file this model was parsed from — what an adapter records so it can
    /// only ever be attached back to the head it was fitted on.
    pub fn sha256(&self) -> String {
        self.sha256.clone()
    }

    pub fn has_lora(&self) -> bool {
        self.lora.lock().expect("lora mutex").is_some()
    }

    pub fn d_model(&self) -> i32 {
        self.d_model as i32
    }

    /// `head_raw` for `n_slots` hidden states, flat `(n_slots·PATCH_SIZE·N_QUANTILES)` in the
    /// same layout `assemble_decode` consumes.
    ///
    /// With no adapter attached this reproduces the graph's own `head_raw`, which is worth
    /// checking once at load: a mismatch means the head file and the `.pte` are not the same
    /// head, and the adapter path must then be refused rather than silently disagreeing with
    /// every forecast the app already stored.
    pub fn forward(&self, hidden: Vec<f64>, n_slots: i32) -> Result<Vec<f64>, CoreError> {
        let n = n_slots.max(0) as usize;
        if n == 0 || hidden.len() != n * self.d_model {
            return Err(CoreError::Internal {
                reason: format!(
                    "hidden has {} values, want n_slots {n} × d_model {}",
                    hidden.len(),
                    self.d_model
                ),
            });
        }
        let guard = self.lora.lock().expect("lora mutex");
        let lora = guard.as_ref();
        let mut out = vec![0.0f64; n * PATCH_SIZE * N_QUANTILES];
        let mut act = Activations::new(self.hidden, self.k * N_QUANTILES, self.d_model);
        for s in 0..n {
            let h = &hidden[s * self.d_model..(s + 1) * self.d_model];
            self.forward_slot(h, lora, &mut act);
            self.expand_basis(&act.out, &mut out[s * PATCH_SIZE * N_QUANTILES..]);
        }
        Ok(out)
    }
}

/// Scratch buffers for one slot's forward, reused across slots so a 300-window training pass
/// does not allocate per slot.
struct Activations {
    h_in: Vec<f64>,
    z1: Vec<f64>,
    a1: Vec<f64>,
    z2: Vec<f64>,
    a2: Vec<f64>,
    out: Vec<f64>,
    u_hidden: Vec<f64>,
    u0: Vec<f64>,
    u1: Vec<f64>,
    u2: Vec<f64>,
}

impl Activations {
    fn new(hidden: usize, out: usize, d_model: usize) -> Self {
        Activations {
            h_in: vec![0.0; d_model],
            z1: vec![0.0; hidden],
            a1: vec![0.0; hidden],
            z2: vec![0.0; hidden],
            a2: vec![0.0; hidden],
            out: vec![0.0; out],
            u_hidden: Vec::new(),
            u0: Vec::new(),
            u1: Vec::new(),
            u2: Vec::new(),
        }
    }
}

impl HeadModel {
    fn forward_slot(&self, h: &[f64], lora: Option<&Lora>, act: &mut Activations) {
        act.h_in.copy_from_slice(h);
        if let Some(l) = lora {
            if let Some(a) = &l.hidden_site {
                act.u_hidden = a.apply(h, &mut act.h_in, l.scale);
            }
        }
        self.l0.forward(&act.h_in, &mut act.z1);
        if let Some(l) = lora {
            if let Some(a) = &l.l0_site {
                act.u0 = a.add(&act.h_in, &mut act.z1, l.scale);
            }
        }
        for i in 0..self.hidden {
            act.a1[i] = silu(act.z1[i]);
        }
        self.l1.forward(&act.a1, &mut act.z2);
        if let Some(l) = lora {
            if let Some(a) = &l.l1_site {
                act.u1 = a.add(&act.a1, &mut act.z2, l.scale);
            }
        }
        for i in 0..self.hidden {
            act.a2[i] = silu(act.z2[i]);
        }
        self.l2.forward(&act.a2, &mut act.out);
        if let Some(l) = lora {
            if let Some(a) = &l.l2_site {
                act.u2 = a.add(&act.a2, &mut act.out, l.scale);
            }
        }
    }

    /// `head_raw[s, c] = Σ_k step_basis[s, k] · coeff[k, c]`, coeff being `out` viewed
    /// row-major as `(K, N_QUANTILES)`.
    fn expand_basis(&self, out: &[f64], dst: &mut [f64]) {
        for s in 0..PATCH_SIZE {
            for c in 0..N_QUANTILES {
                let mut acc = 0.0;
                for k in 0..self.k {
                    acc += self.step_basis[s * self.k + k] * out[k * N_QUANTILES + c];
                }
                dst[s * N_QUANTILES + c] = acc;
            }
        }
    }

    /// The transpose of [`expand_basis`]: `d_out[k, c] = Σ_s step_basis[s, k] · d_head[s, c]`.
    fn expand_basis_backward(&self, d_head: &[f64], d_out: &mut [f64]) {
        for v in d_out.iter_mut() {
            *v = 0.0;
        }
        for s in 0..PATCH_SIZE {
            for c in 0..N_QUANTILES {
                let g = d_head[s * N_QUANTILES + c];
                if g == 0.0 {
                    continue;
                }
                for k in 0..self.k {
                    d_out[k * N_QUANTILES + c] += self.step_basis[s * self.k + k] * g;
                }
            }
        }
    }
}

// ── The adapter ─────────────────────────────────────────────────────────────────────

/// Which sites an adapter attaches to, its rank, and its scaling. `alpha/rank` is the usual
/// LoRA scale, so raising the rank does not silently raise the step size with it.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct LoraConfig {
    pub rank: i32,
    pub alpha: f64,
    /// The bottleneck on the hidden state the head reads.
    pub target_hidden: bool,
    pub target_l0: bool,
    pub target_l1: bool,
    pub target_l2: bool,
}

impl LoraConfig {
    fn validate(&self) -> Result<(), CoreError> {
        if self.rank < 1 || self.rank > 64 {
            return Err(CoreError::Internal {
                reason: format!("lora rank {} outside 1..=64", self.rank),
            });
        }
        if !(self.alpha.is_finite() && self.alpha > 0.0) {
            return Err(CoreError::Internal {
                reason: format!("lora alpha {} must be finite and > 0", self.alpha),
            });
        }
        if !(self.target_hidden || self.target_l0 || self.target_l1 || self.target_l2) {
            return Err(CoreError::Internal {
                reason: "an adapter with no target site would train nothing".into(),
            });
        }
        Ok(())
    }
}

/// A trained (or freshly initialised) adapter, as it travels across the FFI and into storage.
/// `params` is the flat concatenation of every site's `A` then `B`, in site order
/// hidden → l0 → l1 → l2, skipping the sites the config leaves off.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct LoraWeights {
    pub config: LoraConfig,
    /// The digest of the head file this adapter was fitted on. An adapter belongs to ONE head.
    pub head_sha256: String,
    pub d_model: i32,
    pub hidden: i32,
    pub out_dim: i32,
    pub params: Vec<f64>,
}

/// One site's `A` `(r, n_in)` and `B` `(n_out, r)`.
struct Site {
    a: Vec<f64>,
    b: Vec<f64>,
    r: usize,
    n_in: usize,
    n_out: usize,
}

impl Site {
    fn n_params(&self) -> usize {
        self.r * self.n_in + self.n_out * self.r
    }

    /// `u = A x`, then `y += scale · B u`. Returns `u`, which the backward pass needs.
    fn add(&self, x: &[f64], y: &mut [f64], scale: f64) -> Vec<f64> {
        let mut u = vec![0.0f64; self.r];
        for j in 0..self.r {
            let row = &self.a[j * self.n_in..(j + 1) * self.n_in];
            let mut acc = 0.0;
            for (i, xi) in x.iter().enumerate() {
                acc += row[i] * xi;
            }
            u[j] = acc;
        }
        for o in 0..self.n_out {
            let mut acc = 0.0;
            for j in 0..self.r {
                acc += self.b[o * self.r + j] * u[j];
            }
            y[o] += scale * acc;
        }
        u
    }

    /// The hidden-state bottleneck: `h' = h + scale · B(A h)`, written into `dst`.
    fn apply(&self, h: &[f64], dst: &mut [f64], scale: f64) -> Vec<f64> {
        dst.copy_from_slice(h);
        self.add(h, dst, scale)
    }

    /// Accumulate `dA`, `dB` and the input gradient for `y = ... + scale·B(Ax)`.
    fn backward(
        &self,
        x: &[f64],
        u: &[f64],
        dy: &[f64],
        scale: f64,
        da: &mut [f64],
        db: &mut [f64],
        dx: &mut [f64],
    ) {
        // dB[o, j] = scale · dy[o] · u[j]; du[j] = scale · Σ_o dy[o] · B[o, j]
        let mut du = vec![0.0f64; self.r];
        for o in 0..self.n_out {
            let g = dy[o];
            if g == 0.0 {
                continue;
            }
            for j in 0..self.r {
                db[o * self.r + j] += scale * g * u[j];
                du[j] += scale * g * self.b[o * self.r + j];
            }
        }
        // dA[j, i] = du[j] · x[i]; dx[i] += Σ_j du[j] · A[j, i]
        for j in 0..self.r {
            let g = du[j];
            if g == 0.0 {
                continue;
            }
            let row = &self.a[j * self.n_in..(j + 1) * self.n_in];
            for i in 0..self.n_in {
                da[j * self.n_in + i] += g * x[i];
                dx[i] += g * row[i];
            }
        }
    }
}

struct Lora {
    scale: f64,
    hidden_site: Option<Site>,
    l0_site: Option<Site>,
    l1_site: Option<Site>,
    l2_site: Option<Site>,
}

/// The site geometry a config implies, in the fixed order the flat parameter vector uses.
fn site_shapes(
    cfg: &LoraConfig,
    d_model: usize,
    hidden: usize,
    out_dim: usize,
) -> Vec<(usize, usize)> {
    let mut v = Vec::new();
    if cfg.target_hidden {
        v.push((d_model, d_model));
    }
    if cfg.target_l0 {
        v.push((d_model, hidden));
    }
    if cfg.target_l1 {
        v.push((hidden, hidden));
    }
    if cfg.target_l2 {
        v.push((hidden, out_dim));
    }
    v
}

fn n_params_for(cfg: &LoraConfig, d_model: usize, hidden: usize, out_dim: usize) -> usize {
    let r = cfg.rank.max(0) as usize;
    site_shapes(cfg, d_model, hidden, out_dim)
        .iter()
        .map(|(i, o)| r * i + o * r)
        .sum()
}

impl Lora {
    fn from_weights(
        w: &LoraWeights,
        d_model: usize,
        hidden: usize,
        out_dim: usize,
    ) -> Result<Self, CoreError> {
        w.config.validate()?;
        if w.d_model as usize != d_model || w.hidden as usize != hidden || w.out_dim as usize != out_dim
        {
            return Err(CoreError::Internal {
                reason: format!(
                    "adapter was fitted for ({}, {}, {}) but this head is ({d_model}, {hidden}, \
                     {out_dim}); an adapter belongs to the model it was trained on",
                    w.d_model, w.hidden, w.out_dim
                ),
            });
        }
        let want = n_params_for(&w.config, d_model, hidden, out_dim);
        if w.params.len() != want {
            return Err(CoreError::Internal {
                reason: format!("adapter has {} parameters, want {want}", w.params.len()),
            });
        }
        if w.params.iter().any(|v| !v.is_finite()) {
            return Err(CoreError::Internal {
                reason: "adapter carries a non-finite parameter".into(),
            });
        }
        let r = w.config.rank as usize;
        let mut off = 0usize;
        let mut take = |n_in: usize, n_out: usize| -> Site {
            let a = w.params[off..off + r * n_in].to_vec();
            off += r * n_in;
            let b = w.params[off..off + n_out * r].to_vec();
            off += n_out * r;
            Site { a, b, r, n_in, n_out }
        };
        let hidden_site = w.config.target_hidden.then(|| take(d_model, d_model));
        let l0_site = w.config.target_l0.then(|| take(d_model, hidden));
        let l1_site = w.config.target_l1.then(|| take(hidden, hidden));
        let l2_site = w.config.target_l2.then(|| take(hidden, out_dim));
        Ok(Lora {
            scale: w.config.alpha / r as f64,
            hidden_site,
            l0_site,
            l1_site,
            l2_site,
        })
    }
}

/// A deterministic parameter initialiser. `A` gets small Gaussian noise and `B` is **zero**,
/// so a fresh adapter is exactly the identity: attaching one changes no forecast until it has
/// been trained, which is what makes attach/detach safe to offer as a toggle.
#[uniffi::export]
pub fn lora_new(
    config: LoraConfig,
    head_sha256: String,
    d_model: i32,
    hidden: i32,
    out_dim: i32,
    seed: i64,
) -> Result<LoraWeights, CoreError> {
    config.validate()?;
    let (d, h, o) = (d_model.max(0) as usize, hidden.max(0) as usize, out_dim.max(0) as usize);
    if d == 0 || h == 0 || o == 0 {
        return Err(CoreError::Internal {
            reason: format!("head geometry invalid: d_model={d} hidden={h} out_dim={o}"),
        });
    }
    let r = config.rank as usize;
    let mut rng = Rng::new(seed as u64 ^ 0x9E37_79B9_7F4A_7C15);
    let mut params = Vec::with_capacity(n_params_for(&config, d, h, o));
    for (n_in, n_out) in site_shapes(&config, d, h, o) {
        let sd = 1.0 / (n_in as f64).sqrt();
        for _ in 0..(r * n_in) {
            params.push(rng.normal() * sd);
        }
        params.extend(std::iter::repeat(0.0).take(n_out * r));
    }
    if head_sha256.len() != 64 {
        return Err(CoreError::Internal {
            reason: format!("head digest {head_sha256:?} is not a sha256"),
        });
    }
    Ok(LoraWeights {
        config,
        head_sha256,
        d_model: d as i32,
        hidden: h as i32,
        out_dim: o as i32,
        params,
    })
}

/// A small deterministic PRNG (xorshift64*), so a seed reproduces an adapter exactly and a
/// training run can be replayed. Nothing here is cryptographic.
pub(crate) struct Rng(u64);

impl Rng {
    pub(crate) fn new(seed: u64) -> Self {
        Rng(if seed == 0 { 0x1234_5678_9ABC_DEF0 } else { seed })
    }

    pub(crate) fn next_u64(&mut self) -> u64 {
        let mut x = self.0;
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        self.0 = x;
        x.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }

    /// Uniform in `[0, 1)`.
    pub(crate) fn uniform(&mut self) -> f64 {
        (self.next_u64() >> 11) as f64 / (1u64 << 53) as f64
    }

    /// Standard normal, Box-Muller. One of the pair is used; the other is discarded, which
    /// costs a little and keeps the state machine trivial.
    pub(crate) fn normal(&mut self) -> f64 {
        let u1 = self.uniform().max(1e-12);
        let u2 = self.uniform();
        (-2.0 * u1.ln()).sqrt() * (2.0 * std::f64::consts::PI * u2).cos()
    }
}

// ── Serialization ───────────────────────────────────────────────────────────────────

fn hex_to_bytes(hex: &str) -> [u8; 32] {
    let mut out = [0u8; 32];
    let b = hex.as_bytes();
    for i in 0..32 {
        let hi = b.get(i * 2).and_then(|c| (*c as char).to_digit(16)).unwrap_or(0);
        let lo = b.get(i * 2 + 1).and_then(|c| (*c as char).to_digit(16)).unwrap_or(0);
        out[i] = ((hi << 4) | lo) as u8;
    }
    out
}

fn bytes_to_hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

/// Serialize an adapter for storage or backup: a fixed header, the parameters as
/// little-endian fp64, then a sha256 over everything before it.
///
/// The digest is what makes a restored backup trustworthy — a truncated or edited adapter
/// otherwise loads as a plausible one and quietly moves every forecast it touches.
#[uniffi::export]
pub fn lora_serialize(w: &LoraWeights) -> Vec<u8> {
    let mut out = Vec::with_capacity(64 + w.params.len() * 8);
    out.extend_from_slice(LORA_MAGIC);
    out.extend_from_slice(&LORA_VERSION.to_le_bytes());
    out.extend_from_slice(&(w.config.rank as u16).to_le_bytes());
    out.extend_from_slice(&w.config.alpha.to_le_bytes());
    let flags: u8 = (w.config.target_hidden as u8)
        | ((w.config.target_l0 as u8) << 1)
        | ((w.config.target_l1 as u8) << 2)
        | ((w.config.target_l2 as u8) << 3);
    out.push(flags);
    let digest = hex_to_bytes(&w.head_sha256);
    out.extend_from_slice(&digest);
    out.extend_from_slice(&(w.d_model as u32).to_le_bytes());
    out.extend_from_slice(&(w.hidden as u32).to_le_bytes());
    out.extend_from_slice(&(w.out_dim as u32).to_le_bytes());
    out.extend_from_slice(&(w.params.len() as u32).to_le_bytes());
    for v in &w.params {
        out.extend_from_slice(&v.to_le_bytes());
    }
    let digest = Sha256::digest(&out);
    out.extend_from_slice(&digest);
    out
}

/// Inverse of [`lora_serialize`]. Total on hostile input: a short, mistyped or corrupted blob
/// is an error, never a partially-populated adapter.
#[uniffi::export]
pub fn lora_deserialize(bytes: Vec<u8>) -> Result<LoraWeights, CoreError> {
    const HEADER: usize = 8 + 2 + 2 + 8 + 1 + 32 + 4 + 4 + 4 + 4;
    if bytes.len() < HEADER + 32 {
        return Err(CoreError::Decode {
            reason: format!("adapter blob is {} bytes, too short to hold a header", bytes.len()),
        });
    }
    if &bytes[..8] != LORA_MAGIC {
        return Err(CoreError::Decode {
            reason: "adapter blob does not carry the adapter magic".into(),
        });
    }
    let body = bytes.len() - 32;
    let want = Sha256::digest(&bytes[..body]);
    if want.as_slice() != &bytes[body..] {
        return Err(CoreError::Decode {
            reason: "adapter blob digest does not match its contents".into(),
        });
    }
    let u16at = |o: usize| u16::from_le_bytes([bytes[o], bytes[o + 1]]);
    let u32at = |o: usize| u32::from_le_bytes([bytes[o], bytes[o + 1], bytes[o + 2], bytes[o + 3]]);
    let version = u16at(8);
    if version != LORA_VERSION {
        return Err(CoreError::Decode {
            reason: format!("adapter blob version {version} is not readable (this build writes {LORA_VERSION})"),
        });
    }
    let rank = u16at(10) as i32;
    let alpha = f64::from_le_bytes(bytes[12..20].try_into().expect("8 bytes"));
    let flags = bytes[20];
    let config = LoraConfig {
        rank,
        alpha,
        target_hidden: flags & 1 != 0,
        target_l0: flags & 2 != 0,
        target_l1: flags & 4 != 0,
        target_l2: flags & 8 != 0,
    };
    config.validate()?;
    let head_sha256 = bytes_to_hex(&bytes[21..53]);
    let d_model = u32at(53) as i32;
    let hidden = u32at(57) as i32;
    let out_dim = u32at(61) as i32;
    let n = u32at(65) as usize;
    if body != HEADER + n * 8 {
        return Err(CoreError::Decode {
            reason: format!("adapter blob claims {n} parameters but carries {} bytes", body - HEADER),
        });
    }
    let want_n = n_params_for(&config, d_model.max(0) as usize, hidden.max(0) as usize, out_dim.max(0) as usize);
    if n != want_n {
        return Err(CoreError::Decode {
            reason: format!("adapter blob has {n} parameters, its own config implies {want_n}"),
        });
    }
    let params = (0..n)
        .map(|i| {
            let o = HEADER + i * 8;
            f64::from_le_bytes(bytes[o..o + 8].try_into().expect("8 bytes"))
        })
        .collect::<Vec<f64>>();
    if params.iter().any(|v| !v.is_finite()) {
        return Err(CoreError::Decode {
            reason: "adapter blob carries a non-finite parameter".into(),
        });
    }
    Ok(LoraWeights {
        config,
        head_sha256,
        d_model,
        hidden,
        out_dim,
        params,
    })
}

// ── Training ────────────────────────────────────────────────────────────────────────

/// One training window: the trunk hidden states of a span's slots, that span's anchors, and
/// the BG that actually happened.
///
/// `hidden` is `n_slots · d_model`, `anchors` is `n_slots` mg/dL, `target_bg` is
/// `n_slots · PATCH_SIZE` mg/dL. The slots must be ONE contiguous span — the median projection
/// runs per span, so a sample spanning two of them would be trained through a projection no
/// forecast ever uses.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct LoraSample {
    pub hidden: Vec<f64>,
    pub anchors: Vec<f64>,
    pub target_bg: Vec<f64>,
    pub n_slots: i32,
    /// The SAME window's trunk hidden state with a probe dose injected into the masked span's
    /// dose channel — the counterfactual branch. Empty when the window was not paired.
    ///
    /// Its whole job is to make "what does one more unit of insulin do to this forecast" a
    /// quantity the fit can see. Without it an adapter can null the model's marginal dose
    /// response with a rank-1 map, score better on pinball loss, and hand the calculator a
    /// forecaster that does not respond to insulin at all.
    pub hidden_pert: Vec<f64>,
    /// True for the trailing-forecast geometry — the only one the guard measures on, because it
    /// is the only one whose terminal step is the horizon a dose recommendation is read at.
    pub is_forecast: bool,
}

/// Optimiser settings. The defaults the app offers are deliberately timid: this fits a few
/// thousand parameters to one patient's own weeks, and the failure mode of trying harder is a
/// forecast that is confidently wrong about the patient it was fitted to.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct LoraTrainOpts {
    pub epochs: i32,
    pub lr: f64,
    /// Fraction of the samples, taken from the END of the supplied order, held out and never
    /// trained on. The caller supplies samples in chronological order, so the split is
    /// chronological too — an adapter validated on windows it had already seen tells you
    /// nothing about the next one.
    pub holdout_frac: f64,
    pub weight_decay: f64,
    pub seed: i64,
    /// How hard to pin the ADAPTED marginal dose response to the frozen model's. `0.0` disables
    /// the term entirely.
    ///
    /// A MULTIPLE of the frozen head's own mean training pinball loss rather than a raw
    /// coefficient, so `1.0` means "a total collapse of the counterfactual costs as much as the
    /// frozen model's entire loss" and the number means the same thing across patients and
    /// checkpoints. See `distill_scale` on the report for what it resolved to.
    pub distill_weight: f64,
}

/// What a fit did, in the terms the panel has to show before anyone attaches it.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct LoraTrainReport {
    pub n_train: i32,
    pub n_holdout: i32,
    pub epochs_run: i32,
    pub train_loss_first: f64,
    pub train_loss_last: f64,
    /// Held-out pinball loss of the FROZEN head, before any adaptation.
    pub holdout_loss_before: f64,
    /// Held-out pinball loss of the adapter this fit produced.
    pub holdout_loss_after: f64,
    /// True only when the adapter beat the frozen head on windows it never trained on.
    /// A fit that does not clear this bar has learnt the patient's past, not their physiology.
    pub improved: bool,
    pub loss_history: Vec<f64>,
    /// Held-out loss measured at the END of each epoch, `epochs_run` long. `loss_history`'s
    /// held-out twin: the training curve alone cannot show where a fit began to overfit.
    pub holdout_history: Vec<f64>,
    /// The epoch whose weights this fit RETURNED — the argmin of `holdout_history`, or `0`
    /// when no epoch beat the frozen head and the identity adapter was kept. With no holdout
    /// there is nothing to select on and this is `epochs_run`.
    pub best_epoch: i32,
    /// Training samples that carried a usable counterfactual branch. Zero means the
    /// distillation term did nothing, whatever `distill_weight` was set to.
    pub n_paired: i32,
    /// The coefficient actually applied — `distill_weight · F / S`, where `F` is the frozen
    /// head's mean training pinball and `S` the mean squared frozen response. `0.0` when the
    /// term was off or nothing was paired.
    pub distill_scale: f64,
    /// The distillation term alone, per epoch. `loss_history` carries the sum, so without this
    /// a fit whose pinball improved while its dose response collapsed looks like a good one.
    pub distill_history: Vec<f64>,
    /// What the counterfactual guard made of the returned adapter on held-out forecast windows.
    /// `None` when there were none to measure on — which is itself a reason not to attach.
    pub guard: Option<LoraGuardReport>,
}

/// What the counterfactual guard is allowed to conclude, and on what evidence.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct LoraGuardOpts {
    pub max_windows: i32,
    pub min_windows: i32,
    pub probe_dose_u: f64,
    /// Below this, the frozen model has no response worth preserving and the guard declines to
    /// judge rather than passing an adapter it cannot measure.
    pub min_frozen_response: f64,
    pub min_retention: f64,
    pub max_retention: f64,
    pub min_sign_agreement: f64,
}

/// Whether an adapter preserved the model's marginal response to insulin.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum LoraGuardVerdict {
    Pass,
    Blocked,
    Inconclusive,
}

/// The guard's finding, with every input to it, so a refusal can be read rather than trusted.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct LoraGuardReport {
    pub verdict: LoraGuardVerdict,
    pub n_windows: i32,
    /// mg/dL at the span's terminal step per unit of insulin, frozen model.
    pub frozen_response_mgdl: f64,
    /// The same, with the adapter applied.
    pub adapted_response_mgdl: f64,
    /// `adapted / frozen`. 1.0 is perfect preservation; near 0 is a collapse.
    pub retention: f64,
    /// The fraction of windows where both responses have the same sign.
    pub sign_agreement: f64,
    /// Which clause fired, and its two numbers. Empty on a pass.
    pub why: String,
}

/// A caller that wants to watch a fit run. Called once per epoch, never per sample: a fit is
/// hundreds of samples an epoch and a callback on each would cost more than the gradient.
#[uniffi::export(with_foreign)]
pub trait LoraProgress: Send + Sync {
    fn on_epoch(&self, epoch: i32, epochs: i32, train_loss: f64, holdout_loss: f64);
}

/// Fit an adapter on the patient's own matured windows.
///
/// The loss is the pinball loss over the seven levels, in RISK space, read on the assembled
/// fan — the same assembly a forecast goes through, per-span median projection included.
/// Optimising `head_raw` directly would optimise a quantity the projection then discards.
///
/// Returns the adapter and its report. The caller decides whether to attach it; nothing here
/// attaches anything, and a fit that fails to beat the frozen head on held-out windows is
/// returned honestly rather than suppressed.
#[uniffi::export]
pub fn lora_train(
    head: &HeadModel,
    desc: &ModelDescriptor,
    samples: Vec<LoraSample>,
    config: LoraConfig,
    opts: LoraTrainOpts,
    progress: Option<std::sync::Arc<dyn LoraProgress>>,
) -> Result<LoraTrainResult, CoreError> {
    config.validate()?;
    if opts.epochs < 1 || opts.epochs > 500 {
        return Err(CoreError::Internal {
            reason: format!("epochs {} outside 1..=500", opts.epochs),
        });
    }
    if !(opts.lr.is_finite() && opts.lr > 0.0) {
        return Err(CoreError::Internal {
            reason: format!("lr {} must be finite and > 0", opts.lr),
        });
    }
    if !(0.0..0.9).contains(&opts.holdout_frac) {
        return Err(CoreError::Internal {
            reason: format!("holdout_frac {} outside [0, 0.9)", opts.holdout_frac),
        });
    }
    let d = head.d_model;
    let out_dim = head.k * N_QUANTILES;
    for (i, s) in samples.iter().enumerate() {
        let n = s.n_slots.max(0) as usize;
        if n == 0
            || s.hidden.len() != n * d
            || s.anchors.len() != n
            || s.target_bg.len() != n * PATCH_SIZE
        {
            return Err(CoreError::Internal {
                reason: format!("sample {i} is malformed for {n} slots of a {d}-wide head"),
            });
        }
        // A truncated pairing must not silently train on a shorter branch: the counterfactual
        // is either the whole window or it is absent.
        if !s.hidden_pert.is_empty() && s.hidden_pert.len() != n * d {
            return Err(CoreError::Internal {
                reason: format!(
                    "sample {i}'s counterfactual is {} long, needs {} for {n} slots",
                    s.hidden_pert.len(),
                    n * d,
                ),
            });
        }
    }
    if !opts.distill_weight.is_finite() || opts.distill_weight < 0.0 {
        return Err(CoreError::Internal {
            reason: format!("distill_weight {} is not a finite non-negative number", opts.distill_weight),
        });
    }
    let n_total = samples.len();
    let n_holdout = ((n_total as f64) * opts.holdout_frac).floor() as usize;
    let n_train = n_total - n_holdout;
    if n_train < 8 {
        return Err(CoreError::Internal {
            reason: format!("{n_train} training windows is too few to fit an adapter"),
        });
    }
    let (train, holdout) = samples.split_at(n_train);

    let mut w = lora_new(config, head.sha256.clone(), d as i32, head.hidden as i32, out_dim as i32, opts.seed)?;
    let holdout_before = mean_loss(head, desc, holdout, None)?;

    // ── the distillation term's normaliser and its frozen targets ──
    //
    // `d0` is what the FROZEN model does to its own median when one unit of insulin is added:
    // the quantity the adapter must not null. Computed once, with no adapter, at two head
    // forwards per paired sample and no trunk forward at all.
    //
    // `scale = distill_weight · F / S` makes the weight a MULTIPLE of the frozen head's own
    // mean training loss, so it means the same thing across patients and checkpoints. `S` is a
    // GLOBAL mean rather than a per-sample divisor, so a window where the frozen model barely
    // responds contributes proportionally little instead of being amplified into dominance.
    let distill_on = opts.distill_weight > 0.0;
    let mut d0_train: Vec<Option<Vec<f64>>> = vec![None; train.len()];
    // Counted whatever the weight is. `n_paired` reports how many training windows CARRIED a
    // counterfactual branch, which is a property of the replay and not of the optimiser: folding it
    // into the `distill_on` branch made a comparison run at `distill_weight = 0` indistinguishable
    // from a fit whose replay never paired anything at all.
    let mut n_paired = train.iter().filter(|s| !s.hidden_pert.is_empty()).count() as i32;
    let mut distill_scale = 0.0f64;
    if distill_on {
        n_paired = 0;
        let mut sq_acc = 0.0f64;
        let mut sq_n = 0usize;
        for (i, s) in train.iter().enumerate() {
            if s.hidden_pert.is_empty() {
                continue;
            }
            let m = branch_median_risk(head, desc, s, None, false)?;
            let mp = branch_median_risk(head, desc, s, None, true)?;
            let d: Vec<f64> = m.iter().zip(&mp).map(|(a, b)| a - b).collect();
            sq_acc += d.iter().map(|x| x * x).sum::<f64>() / d.len().max(1) as f64;
            sq_n += 1;
            d0_train[i] = Some(d);
            n_paired += 1;
        }
        if sq_n > 0 {
            let s_mean = sq_acc / sq_n as f64;
            let f_mean = mean_loss(head, desc, train, None)?;
            if s_mean > 0.0 && f_mean.is_finite() {
                distill_scale = opts.distill_weight * f_mean / s_mean;
            }
        }
    }

    let n_params = w.params.len();
    let mut m = vec![0.0f64; n_params];
    let mut v = vec![0.0f64; n_params];
    let mut rng = Rng::new(opts.seed as u64 ^ 0xA5A5_5A5A_1234_9876);
    let mut order: Vec<usize> = (0..n_train).collect();
    let mut history = Vec::with_capacity(opts.epochs as usize);
    let mut holdout_history = Vec::with_capacity(opts.epochs as usize);
    let mut distill_history = Vec::with_capacity(opts.epochs as usize);
    let (b1, b2, eps) = (0.9f64, 0.999f64, 1e-8f64);
    let mut t = 0.0f64;
    let mut first_loss = f64::NAN;
    // Epoch 0 is the UNTRAINED adapter, which is exactly the identity — so the frozen head is
    // itself a candidate, and a fit whose every epoch overfits returns something that changes
    // no forecast rather than one that makes them worse.
    let mut best = (0i32, holdout_before);
    let mut best_params = w.params.clone();

    for epoch in 0..opts.epochs {
        // Fisher-Yates on the deterministic RNG, so a seed replays the run exactly.
        for i in (1..order.len()).rev() {
            let j = (rng.next_u64() % (i as u64 + 1)) as usize;
            order.swap(i, j);
        }
        // Cosine decay across the run, floored at LR_MIN_RATIO. Adam's step size is the same
        // at the last sample as at the first, so a long fit random-walks at full amplitude
        // around whatever it found; the schedule is deterministic and keeps a seed replayable.
        let sched = if opts.epochs > 1 {
            let phase = std::f64::consts::PI * epoch as f64 / (opts.epochs - 1) as f64;
            LR_MIN_RATIO + (1.0 - LR_MIN_RATIO) * 0.5 * (1.0 + phase.cos())
        } else {
            1.0
        };
        let mut epoch_loss = 0.0;
        let mut epoch_distill = 0.0;
        for &idx in &order {
            let lora = Lora::from_weights(&w, d, head.hidden, out_dim)?;
            let mut grad = vec![0.0f64; n_params];
            let ctx = d0_train[idx].as_ref().and_then(|d0| {
                if distill_scale > 0.0 { Some(DistillCtx { d0, scale: distill_scale }) } else { None }
            });
            let mut distill_term = 0.0;
            let loss = sample_loss_and_grad(
                head,
                desc,
                &train[idx],
                Some(&lora),
                Some(&mut grad),
                ctx.as_ref(),
                Some(&mut distill_term),
            )?;
            epoch_loss += loss;
            epoch_distill += distill_term;
            t += 1.0;
            let lr_t = opts.lr * sched * (1.0 - b2.powf(t)).sqrt() / (1.0 - b1.powf(t));
            for p in 0..n_params {
                let g = grad[p] + opts.weight_decay * w.params[p];
                m[p] = b1 * m[p] + (1.0 - b1) * g;
                v[p] = b2 * v[p] + (1.0 - b2) * g * g;
                w.params[p] -= lr_t * m[p] / (v[p].sqrt() + eps);
            }
        }
        let mean = epoch_loss / n_train as f64;
        if epoch == 0 {
            first_loss = mean;
        }
        history.push(mean);
        distill_history.push(epoch_distill / n_train as f64);
        if !mean.is_finite() {
            return Err(CoreError::Internal {
                reason: format!("training diverged at epoch {epoch}"),
            });
        }
        // The held-out number, every epoch. It is what selects the returned weights, and
        // measuring it once at the end is what let a fit hand back the epoch it had already
        // overfitted on. One forward pass over the holdout — no gradient.
        let h = mean_loss(head, desc, holdout, Some(&w))?;
        holdout_history.push(h);
        if n_holdout > 0 && h < best.1 {
            best = (epoch + 1, h);
            best_params.copy_from_slice(&w.params);
        }
        if let Some(p) = progress.as_ref() {
            p.on_epoch(epoch + 1, opts.epochs, mean, h);
        }
    }

    // With no holdout there is nothing to select on, so the last epoch stands and the report
    // says so rather than implying a choice was made.
    let (best_epoch, holdout_after) = if n_holdout > 0 {
        w.params.copy_from_slice(&best_params);
        best
    } else {
        (opts.epochs, f64::NAN)
    };
    let improved = n_holdout > 0 && holdout_after < holdout_before;
    // The guard runs on the weights the fit is actually RETURNING, and on held-out windows
    // only. The fit is never refused on it — the block is on ATTACH, where a person is present
    // to read the reason and decide.
    let guard = if holdout.iter().any(|s| s.is_forecast && !s.hidden_pert.is_empty()) {
        Some(lora_guard(head, desc, holdout.to_vec(), &w, GUARD_OPTS_FIT)?)
    } else {
        None
    };
    let report = LoraTrainReport {
        n_train: n_train as i32,
        n_holdout: n_holdout as i32,
        epochs_run: opts.epochs,
        train_loss_first: first_loss,
        train_loss_last: *history.last().unwrap_or(&f64::NAN),
        holdout_loss_before: holdout_before,
        holdout_loss_after: holdout_after,
        improved,
        loss_history: history,
        holdout_history,
        best_epoch,
        n_paired,
        distill_scale,
        distill_history,
        guard,
    };
    Ok(LoraTrainResult { weights: w, report })
}

/// An adapter and the account of how it was fitted, which travel together — a set of weights
/// with no held-out numbers beside them is not something anyone can decide to attach.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct LoraTrainResult {
    pub weights: LoraWeights,
    pub report: LoraTrainReport,
}

/// The per-span median projection of [`assemble_decode`] and nothing else — `m = anchor + B Bᵀ
/// delta`, in RISK space.
///
/// For the branch that needs only the median line. It reuses `global_median_dim` /
/// `global_median_basis` rather than re-deriving the projection, because a second implementation
/// of it is exactly the drift this crate exists to avoid.
fn span_median_risk(
    desc: &ModelDescriptor,
    head_raw: &[f64],
    anchors: &[f64],
    n: usize,
) -> Result<Vec<f64>, CoreError> {
    let n_steps = n * PATCH_SIZE;
    let p = desc.prediction_patches()?;
    let g = global_median_dim(desc, n, p);
    let basis = global_median_basis(n_steps, g);
    // The anchor is one value for the whole span, exactly as the production assembly reads it.
    let anchor = desc.kovatchev.f(*anchors.first().unwrap_or(&0.0));
    let mut z = vec![0.0f64; g];
    for (j, zj) in z.iter_mut().enumerate() {
        let mut acc = 0.0;
        for i in 0..n_steps {
            acc += head_raw[i * N_QUANTILES] * basis[i * g + j];
        }
        *zj = acc;
    }
    let mut m = vec![0.0f64; n_steps];
    for (i, mi) in m.iter_mut().enumerate() {
        let mut acc = 0.0;
        for (j, zj) in z.iter().enumerate() {
            acc += zj * basis[i * g + j];
        }
        *mi = anchor + acc;
    }
    Ok(m)
}

/// The guard's statistic, as a pure function of two response arrays.
///
/// Separated from the forwards that produce them so it can be tested with literal numbers — the
/// clause ordering below IS the safety property, and it is the part most likely to be got wrong
/// by a later edit.
///
/// **It measures PRESERVATION, not correctness.** A frozen model whose marginal insulin response
/// is already wrong-signed passes here as long as the adapter keeps that sign. Exposing a
/// wrong-signed model is `SensitivityProbe`'s job and stays there, deliberately: a guard that
/// tried to do both would refuse adapters for a defect the adapter did not introduce.
/// [r0]/[r1] are the frozen and adapted responses in RISK space — the ratio's space — and
/// [m0]/[m1] the same responses in mg/dL per unit, which are reported and gated on but never
/// divided by one another. See [lora_guard] for why the two are not one.
fn guard_verdict(
    r0: &[f64],
    r1: &[f64],
    m0: &[f64],
    m1: &[f64],
    opts: &LoraGuardOpts,
) -> LoraGuardReport {
    fn median(v: &[f64]) -> f64 {
        let mut s: Vec<f64> = v.iter().copied().filter(|x| x.is_finite()).collect();
        if s.is_empty() {
            return f64::NAN;
        }
        s.sort_by(|a, b| a.partial_cmp(b).unwrap());
        let mid = s.len() / 2;
        if s.len() % 2 == 0 { (s[mid - 1] + s[mid]) / 2.0 } else { s[mid] }
    }

    let n = r0.len().min(r1.len()).min(m0.len()).min(m1.len());
    let frozen_risk = median(&r0[..n]);
    let adapted_risk = median(&r1[..n]);
    let frozen = median(&m0[..n]);
    let adapted = median(&m1[..n]);
    let agree = if n == 0 {
        0.0
    } else {
        r0[..n].iter().zip(&r1[..n]).filter(|(a, b)| *a * *b > 0.0).count() as f64 / n as f64
    };
    let retention = adapted_risk / frozen_risk;

    let mut report = LoraGuardReport {
        verdict: LoraGuardVerdict::Pass,
        n_windows: n as i32,
        frozen_response_mgdl: frozen,
        adapted_response_mgdl: adapted,
        retention,
        sign_agreement: agree,
        why: String::new(),
    };

    // First clause that fires wins, and the two inconclusive ones come first: an adapter the
    // guard could not measure must never read as one it measured and passed.
    if (n as i32) < opts.min_windows {
        report.verdict = LoraGuardVerdict::Inconclusive;
        report.why = format!("only {n} held-out forecast windows, needs {}", opts.min_windows);
    } else if !frozen.is_finite() || frozen.abs() < opts.min_frozen_response {
        report.verdict = LoraGuardVerdict::Inconclusive;
        report.why = format!(
            "the frozen model's own response is {frozen:.2} mg/dL/U, below {:.2} — nothing to preserve",
            opts.min_frozen_response,
        );
    } else if !frozen_risk.is_finite() || frozen_risk == 0.0 {
        report.verdict = LoraGuardVerdict::Inconclusive;
        report.why = "the frozen model's risk-space response is zero — nothing to ratio".to_string();
    } else if !retention.is_finite() {
        report.verdict = LoraGuardVerdict::Inconclusive;
        report.why = "retention is not finite".to_string();
    } else if retention < opts.min_retention {
        report.verdict = LoraGuardVerdict::Blocked;
        report.why = format!(
            "keeps {:.0}% of the model's dose response ({adapted:.2} vs {frozen:.2} mg/dL/U), floor {:.0}%",
            retention * 100.0,
            opts.min_retention * 100.0,
        );
    } else if retention > opts.max_retention {
        // An amplification is as untrustworthy as a collapse: it feeds the calculator an
        // inflated ISF, and the dose that follows is too large rather than too small.
        report.verdict = LoraGuardVerdict::Blocked;
        report.why = format!(
            "amplifies the dose response {:.1}× ({adapted:.2} vs {frozen:.2} mg/dL/U), ceiling {:.1}×",
            retention,
            opts.max_retention,
        );
    } else if agree < opts.min_sign_agreement {
        report.verdict = LoraGuardVerdict::Blocked;
        report.why = format!(
            "agrees on the direction in only {:.0}% of windows, floor {:.0}%",
            agree * 100.0,
            opts.min_sign_agreement * 100.0,
        );
    }
    report
}

/// Measure what an adapter did to the model's marginal response to one unit of insulin.
///
/// Head-only arithmetic: four head forwards and four median assemblies per window, and no trunk
/// forward at all — the counterfactual hidden states were computed once when the samples were
/// built. The response is read in mg/dL at the span's TERMINAL step, which is the same quantity
/// `SensitivityProbe` differences and, at forecast geometry, the horizon a dose is read at.
///
/// The anchor is identical in both branches — the probe touches the dose channels only, and the
/// anchor is read off the BG channel — so it cancels exactly, and the guard cannot be satisfied
/// by an adapter that merely moves the anchor.
#[uniffi::export]
pub fn lora_guard(
    head: &HeadModel,
    desc: &ModelDescriptor,
    samples: Vec<LoraSample>,
    weights: &LoraWeights,
    opts: LoraGuardOpts,
) -> Result<LoraGuardReport, CoreError> {
    let out_dim = head.k * N_QUANTILES;
    let lora = Lora::from_weights(weights, head.d_model, head.hidden, out_dim)?;
    // Ragged samples are refused, not indexed. This is an exported entry point — the Probe path
    // hands it whatever a caller built — and `branch_median_risk` slices `hidden[slot * d_model..]`
    // without checking, which panics ACROSS the FFI boundary rather than returning an error.
    let want = |s: &LoraSample| (s.n_slots.max(0) as usize) * head.d_model;
    for s in &samples {
        if s.hidden.len() < want(s) || (!s.hidden_pert.is_empty() && s.hidden_pert.len() < want(s)) {
            return Err(CoreError::Internal {
                reason: format!(
                    "guard sample has {} hidden and {} perturbed values for {} slots of d_model {}",
                    s.hidden.len(),
                    s.hidden_pert.len(),
                    s.n_slots,
                    head.d_model,
                ),
            });
        }
    }
    let usable: Vec<&LoraSample> = samples
        .iter()
        .filter(|s| s.is_forecast && !s.hidden_pert.is_empty())
        .collect();
    let take = (opts.max_windows.max(0) as usize).min(usable.len());
    let windows = &usable[usable.len() - take..];

    // Two spaces, and the split is load-bearing.
    //
    // RETENTION is a ratio, and it is taken in RISK space — the space the pinball loss and the
    // distillation term are both formed in, so the guard reads the quantity the fit optimises.
    // `f_inv` is strongly convex, so the same risk-space response differences to a different
    // number of mg/dL at 80 than at 250: a ratio of mg/dL responses carries a TERMINAL-LEVEL ratio
    // alongside the response ratio, and an adapter that merely shifted the forecast's level would
    // move it. That is a wrong-quantity guard.
    //
    // The mg/dL pair is still measured, still reported and still what `min_frozen_response` gates
    // on, because "does the model respond to insulin at all" is a clinical question and 2 mg/dL/U
    // is a clinical floor. It is read, never ratioed.
    let mut r0 = Vec::with_capacity(windows.len());
    let mut r1 = Vec::with_capacity(windows.len());
    let mut m0 = Vec::with_capacity(windows.len());
    let mut m1 = Vec::with_capacity(windows.len());
    for s in windows {
        let n = s.n_slots.max(0) as usize;
        if n == 0 {
            continue;
        }
        let last = n * PATCH_SIZE - 1;
        let dose = if opts.probe_dose_u.abs() > 0.0 { opts.probe_dose_u } else { 1.0 };
        let f_base = branch_median_risk(head, desc, s, None, false)?[last];
        let f_pert = branch_median_risk(head, desc, s, None, true)?[last];
        let a_base = branch_median_risk(head, desc, s, Some(&lora), false)?[last];
        let a_pert = branch_median_risk(head, desc, s, Some(&lora), true)?[last];
        r0.push((f_base - f_pert) / dose);
        r1.push((a_base - a_pert) / dose);
        m0.push((desc.kovatchev.f_inv(f_base) - desc.kovatchev.f_inv(f_pert)) / dose);
        m1.push((desc.kovatchev.f_inv(a_base) - desc.kovatchev.f_inv(a_pert)) / dose);
    }
    Ok(guard_verdict(&r0, &r1, &m0, &m1, &opts))
}

/// One branch's median line, in risk space: forward the head over the chosen hidden state and
/// project it through the same per-span DCT the production assembly uses.
fn branch_median_risk(
    head: &HeadModel,
    desc: &ModelDescriptor,
    sample: &LoraSample,
    lora: Option<&Lora>,
    perturbed: bool,
) -> Result<Vec<f64>, CoreError> {
    let n = sample.n_slots.max(0) as usize;
    let out_dim = head.k * N_QUANTILES;
    let hidden = if perturbed { &sample.hidden_pert } else { &sample.hidden };
    let mut head_raw = vec![0.0f64; n * PATCH_SIZE * N_QUANTILES];
    let mut a = Activations::new(head.hidden, out_dim, head.d_model);
    for slot in 0..n {
        let h = &hidden[slot * head.d_model..(slot + 1) * head.d_model];
        head.forward_slot(h, lora, &mut a);
        head.expand_basis(&a.out, &mut head_raw[slot * PATCH_SIZE * N_QUANTILES..]);
    }
    span_median_risk(desc, &head_raw, &sample.anchors, n)
}

fn mean_loss(
    head: &HeadModel,
    desc: &ModelDescriptor,
    samples: &[LoraSample],
    w: Option<&LoraWeights>,
) -> Result<f64, CoreError> {
    if samples.is_empty() {
        return Ok(f64::NAN);
    }
    let out_dim = head.k * N_QUANTILES;
    let lora = match w {
        None => None,
        Some(w) => Some(Lora::from_weights(w, head.d_model, head.hidden, out_dim)?),
    };
    let mut acc = 0.0;
    for s in samples {
        acc += sample_loss_and_grad(head, desc, s, lora.as_ref(), None, None, None)?;
    }
    Ok(acc / samples.len() as f64)
}

/// Risk-space pinball loss of an assembled fan against what actually happened, averaged over
/// steps and levels. Risk space is where the fan lives and where a hypo error costs what it
/// clinically costs; averaging mg/dL residuals instead would train the adapter to spend its
/// capacity on the hyperglycaemic half of the range.
fn pinball(desc: &ModelDescriptor, q_tau_risk: &[f64], target_bg: &[f64], n_steps: usize) -> f64 {
    let kov = desc.kovatchev;
    let mut acc = 0.0;
    for i in 0..n_steps {
        let y = kov.f(target_bg[i]);
        for (k, tau) in QUANTILE_LEVELS.iter().enumerate() {
            let e = y - q_tau_risk[i * N_QUANTILES + k];
            acc += if e > 0.0 { tau * e } else { (tau - 1.0) * e };
        }
    }
    acc / (n_steps * N_QUANTILES) as f64
}

/// One sample's loss, and — when `grad` is given — the gradient of that loss with respect to
/// every adapter parameter.
///
/// The chain runs backwards through the same stages the forward went through: the pinball
/// derivative on each of the seven levels, the cumsum-of-softplus fan, the per-span DCT
/// projection of the median, the within-patch basis, and finally the head's three layers into
/// the adapter sites. The base weights take no gradient — they are frozen inside the `.pte`
/// and this crate could not write them back if it wanted to.
/// The frozen model's marginal response for one sample, and the coefficient to pin it with.
struct DistillCtx<'a> {
    /// `m_0(hidden)[i] − m_0(hidden_pert)[i]`, in risk space, `n_steps` long.
    d0: &'a [f64],
    scale: f64,
}

fn sample_loss_and_grad(
    head: &HeadModel,
    desc: &ModelDescriptor,
    sample: &LoraSample,
    lora: Option<&Lora>,
    mut grad: Option<&mut [f64]>,
    distill: Option<&DistillCtx<'_>>,
    mut distill_out: Option<&mut f64>,
) -> Result<f64, CoreError> {
    let n = sample.n_slots.max(0) as usize;
    let n_steps = n * PATCH_SIZE;
    let out_dim = head.k * N_QUANTILES;

    // ── forward, keeping every activation the backward pass needs ──
    let mut acts: Vec<Activations> = Vec::with_capacity(n);
    let mut head_raw = vec![0.0f64; n_steps * N_QUANTILES];
    for slot in 0..n {
        let mut a = Activations::new(head.hidden, out_dim, head.d_model);
        let h = &sample.hidden[slot * head.d_model..(slot + 1) * head.d_model];
        head.forward_slot(h, lora, &mut a);
        head.expand_basis(&a.out, &mut head_raw[slot * PATCH_SIZE * N_QUANTILES..]);
        acts.push(a);
    }

    // The slots of one sample are one contiguous span, so the assembly is the production one
    // with a contiguous slot_patch — not a second implementation of it.
    let slot_patch: Vec<i32> = (0..n as i32).collect();
    let fan = assemble_decode(
        desc,
        head_raw.clone(),
        sample.anchors.clone(),
        slot_patch,
        n as i32,
        0.0,
    )?;
    let mut loss = pinball(desc, &fan.q_tau_risk, &sample.target_bg, n_steps);

    // ── the counterfactual branch ──
    //
    // A SECOND forward of the same adapted head over the perturbed hidden state, giving the
    // adapted model's own marginal response `m − m'`. The term is the squared distance between
    // that and the frozen model's `d0`.
    //
    // Read on the ASSEMBLED median and in RISK space, for the same two reasons the pinball term
    // is: the per-span DCT projection is not an identity — on the reference descriptor half the
    // raw-delta space is null — so a term read on `head_raw` would take gradient from
    // coefficients the decode discards; and mg/dL residuals would spend the adapter's capacity on
    // the hyperglycaemic half while `f_inv`'s clamp zeroed the gradient at exactly the rails a
    // hypo response lives near.
    //
    // MEDIAN COLUMN ONLY. The median line is what the sensitivity probe differences and what
    // collapses; pinning the spreads' marginal response too would fight the pinball term's
    // freedom to widen this patient's bands.
    let paired = distill.filter(|c| c.scale > 0.0 && !sample.hidden_pert.is_empty());
    let mut pert: Option<(Vec<Activations>, Vec<f64>, Vec<f64>)> = None;
    let mut err = vec![0.0f64; n_steps];
    if let Some(ctx) = paired {
        let mut acts_p: Vec<Activations> = Vec::with_capacity(n);
        let mut raw_p = vec![0.0f64; n_steps * N_QUANTILES];
        for slot in 0..n {
            let mut a = Activations::new(head.hidden, out_dim, head.d_model);
            let h = &sample.hidden_pert[slot * head.d_model..(slot + 1) * head.d_model];
            head.forward_slot(h, lora, &mut a);
            head.expand_basis(&a.out, &mut raw_p[slot * PATCH_SIZE * N_QUANTILES..]);
            acts_p.push(a);
        }
        let m_pert = span_median_risk(desc, &raw_p, &sample.anchors, n)?;
        let mut acc = 0.0;
        for i in 0..n_steps {
            let m = fan.q_tau_risk[i * N_QUANTILES + N_SPREADS];
            err[i] = (m - m_pert[i]) - ctx.d0[i];
            acc += err[i] * err[i];
        }
        let term = ctx.scale * acc / n_steps as f64;
        loss += term;
        if let Some(out) = distill_out.take() {
            *out = term;
        }
        pert = Some((acts_p, raw_p, m_pert));
    }

    let grad = match grad.take() {
        None => return Ok(loss),
        Some(g) => g,
    };
    for v in grad.iter_mut() {
        *v = 0.0;
    }
    let lora = match lora {
        None => return Ok(loss),
        Some(l) => l,
    };

    // ── dL/dq for each of the seven levels ──
    let kov = desc.kovatchev;
    let scale_n = 1.0 / (n_steps * N_QUANTILES) as f64;
    let mut dq = vec![0.0f64; n_steps * N_QUANTILES];
    for i in 0..n_steps {
        let y = kov.f(sample.target_bg[i]);
        for (k, tau) in QUANTILE_LEVELS.iter().enumerate() {
            let e = y - fan.q_tau_risk[i * N_QUANTILES + k];
            // d/dq of max(tau·e, (tau−1)·e) with e = y − q.
            dq[i * N_QUANTILES + k] = scale_n * if e > 0.0 { -tau } else { 1.0 - tau };
        }
    }

    // ── through the fan: the median moves all seven levels; each spread moves the levels
    //    at or beyond it on its own side ──
    let mut d_median = vec![0.0f64; n_steps];
    let mut d_head_raw = vec![0.0f64; n_steps * N_QUANTILES];
    // `c = 2·scale / n_steps`: the derivative of the mean squared error. The baseline branch
    // takes `+c·e[i]` on its median and the perturbed branch `−c·e[i]`, because `e` is their
    // difference minus a constant.
    let c_distill = paired.map_or(0.0, |ctx| 2.0 * ctx.scale / n_steps as f64);
    for i in 0..n_steps {
        let row = i * N_QUANTILES;
        let mut dm = 0.0;
        for k in 0..N_QUANTILES {
            dm += dq[row + k];
        }
        d_median[i] = dm + c_distill * err[i];
        // up_j (levels 4..6) = m + Σ_{t<=j} d_up_t ; dn_j (levels 2−j) = m − Σ_{t<=j} d_dn_t
        for t in 0..N_SPREADS {
            let mut d_up = 0.0;
            let mut d_dn = 0.0;
            for j in t..N_SPREADS {
                d_up += dq[row + 4 + j];
                d_dn -= dq[row + 2 - j];
            }
            let raw_up = head_raw[row + 1 + t];
            let raw_dn = head_raw[row + 1 + N_SPREADS + t];
            d_head_raw[row + 1 + t] = d_up * sigmoid(raw_up);
            d_head_raw[row + 1 + N_SPREADS + t] = d_dn * sigmoid(raw_dn);
        }
    }

    // ── through the per-span median projection: m = anchor + B Bᵀ delta, and B Bᵀ is
    //    symmetric, so the pullback is the same projection applied to dL/dm ──
    let p = desc.prediction_patches()?;
    let g = global_median_dim(desc, n, p);
    let basis = global_median_basis(n_steps, g);
    let mut z = vec![0.0f64; g];
    for (j, zj) in z.iter_mut().enumerate() {
        let mut acc = 0.0;
        for (i, dm) in d_median.iter().enumerate() {
            acc += dm * basis[i * g + j];
        }
        *zj = acc;
    }
    for i in 0..n_steps {
        let mut acc = 0.0;
        for (j, zj) in z.iter().enumerate() {
            acc += zj * basis[i * g + j];
        }
        d_head_raw[i * N_QUANTILES] = acc;
    }

    // ── through the head, slot by slot, into the adapter sites ──
    let mut off_hidden = 0usize;
    let mut off_l0 = 0usize;
    let mut off_l1 = 0usize;
    let mut off_l2 = 0usize;
    {
        let mut off = 0usize;
        if let Some(s) = &lora.hidden_site {
            off_hidden = off;
            off += s.n_params();
        }
        if let Some(s) = &lora.l0_site {
            off_l0 = off;
            off += s.n_params();
        }
        if let Some(s) = &lora.l1_site {
            off_l1 = off;
            off += s.n_params();
        }
        if let Some(s) = &lora.l2_site {
            off_l2 = off;
            off += s.n_params();
        }
        if off != grad.len() {
            return Err(CoreError::Internal {
                reason: format!("gradient buffer is {} long, adapter has {off} parameters", grad.len()),
            });
        }
    }

    // ONE implementation, run once per branch, accumulating into the SAME gradient buffer. The
    // perturbed branch uses its own activations and its own hidden input throughout — a shared
    // buffer with the baseline branch's activations would silently compute the wrong chain.
    backward_head_into_sites(
        head, lora, &acts, &sample.hidden, &d_head_raw,
        (off_hidden, off_l0, off_l1, off_l2), grad,
    );

    if let (Some(ctx), Some((acts_p, _, _))) = (paired, pert.as_ref()) {
        // The perturbed branch's whole median gradient is `−c·e`, and its SPREADS take none: the
        // distillation term never reads one, so the softplus/cumsum fan contributes nothing here.
        let mut d_median_p = vec![0.0f64; n_steps];
        for i in 0..n_steps {
            d_median_p[i] = -2.0 * ctx.scale / n_steps as f64 * err[i];
        }
        let mut d_raw_p = vec![0.0f64; n_steps * N_QUANTILES];
        // The same projection pullback as above — `B Bᵀ` is symmetric — reusing the one basis.
        let mut zp = vec![0.0f64; g];
        for (j, zj) in zp.iter_mut().enumerate() {
            let mut acc = 0.0;
            for (i, dm) in d_median_p.iter().enumerate() {
                acc += dm * basis[i * g + j];
            }
            *zj = acc;
        }
        for i in 0..n_steps {
            let mut acc = 0.0;
            for (j, zj) in zp.iter().enumerate() {
                acc += zj * basis[i * g + j];
            }
            d_raw_p[i * N_QUANTILES] = acc;
        }
        backward_head_into_sites(
            head, lora, acts_p, &sample.hidden_pert, &d_raw_p,
            (off_hidden, off_l0, off_l1, off_l2), grad,
        );
    }
    Ok(loss)
}

/// The head's backward pass for one branch, from `d_head_raw` into the adapter sites.
///
/// Lifted out so the baseline and counterfactual branches run the SAME chain over their own
/// activations and their own hidden input, accumulating into one gradient buffer. With no
/// counterfactual it is called once and is bit-identical to what it replaced.
#[allow(clippy::too_many_arguments)]
fn backward_head_into_sites(
    head: &HeadModel,
    lora: &Lora,
    acts: &[Activations],
    hidden: &[f64],
    d_head_raw: &[f64],
    offs: (usize, usize, usize, usize),
    grad: &mut [f64],
) {
    let (off_hidden, off_l0, off_l1, off_l2) = offs;
    let out_dim = head.k * N_QUANTILES;
    let mut d_out = vec![0.0f64; out_dim];
    for (slot, a) in acts.iter().enumerate() {
        head.expand_basis_backward(
            &d_head_raw[slot * PATCH_SIZE * N_QUANTILES..(slot + 1) * PATCH_SIZE * N_QUANTILES],
            &mut d_out,
        );

        // l2: out = W2·a2 + b2 + scale·B2(A2·a2)
        let mut d_a2 = vec![0.0f64; head.hidden];
        head.l2.backward_input(&d_out, &mut d_a2);
        if let Some(site) = &lora.l2_site {
            let (da, db) = grad[off_l2..off_l2 + site.n_params()].split_at_mut(site.r * site.n_in);
            site.backward(&a.a2, &a.u2, &d_out, lora.scale, da, db, &mut d_a2);
        }

        // silu at z2
        let mut d_z2 = vec![0.0f64; head.hidden];
        for i in 0..head.hidden {
            d_z2[i] = d_a2[i] * silu_grad(a.z2[i]);
        }

        // l1
        let mut d_a1 = vec![0.0f64; head.hidden];
        head.l1.backward_input(&d_z2, &mut d_a1);
        if let Some(site) = &lora.l1_site {
            let (da, db) = grad[off_l1..off_l1 + site.n_params()].split_at_mut(site.r * site.n_in);
            site.backward(&a.a1, &a.u1, &d_z2, lora.scale, da, db, &mut d_a1);
        }

        // silu at z1
        let mut d_z1 = vec![0.0f64; head.hidden];
        for i in 0..head.hidden {
            d_z1[i] = d_a1[i] * silu_grad(a.z1[i]);
        }

        // l0
        let mut d_h = vec![0.0f64; head.d_model];
        head.l0.backward_input(&d_z1, &mut d_h);
        if let Some(site) = &lora.l0_site {
            let (da, db) = grad[off_l0..off_l0 + site.n_params()].split_at_mut(site.r * site.n_in);
            site.backward(&a.h_in, &a.u0, &d_z1, lora.scale, da, db, &mut d_h);
        }

        // the hidden bottleneck: h_in = h + scale·B_h(A_h·h), so d_h flows to both terms
        if let Some(site) = &lora.hidden_site {
            let h = &hidden[slot * head.d_model..(slot + 1) * head.d_model];
            let mut sink = vec![0.0f64; head.d_model];
            let (da, db) =
                grad[off_hidden..off_hidden + site.n_params()].split_at_mut(site.r * site.n_in);
            site.backward(h, &a.u_hidden, &d_h, lora.scale, da, db, &mut sink);
        }
    }
}


#[cfg(test)]
mod tests {
    use super::*;
    use crate::preproc::{parse_descriptor, HeadTensorSpec};

    const REFERENCE_DESCRIPTOR: &str = include_str!("../../../models/descriptor.json");

    fn desc() -> ModelDescriptor {
        parse_descriptor(REFERENCE_DESCRIPTOR.to_string()).expect("reference descriptor")
    }

    /// A small head in the shipped file format, weights and digest included. Small on purpose:
    /// a finite-difference gradient check over the real 128-wide head would take minutes and
    /// prove nothing extra.
    fn synthetic_head(d_model: usize, hidden: usize, k: usize) -> (std::sync::Arc<HeadModel>, HeadSpec) {
        let out_dim = k * N_QUANTILES;
        let mut rng = Rng::new(0xC0FFEE);
        let shapes: Vec<(&str, Vec<i32>)> = vec![
            ("step_basis", vec![PATCH_SIZE as i32, k as i32]),
            ("l0.weight", vec![hidden as i32, d_model as i32]),
            ("l0.bias", vec![hidden as i32]),
            ("l1.weight", vec![hidden as i32, hidden as i32]),
            ("l1.bias", vec![hidden as i32]),
            ("l2.weight", vec![out_dim as i32, hidden as i32]),
            ("l2.bias", vec![out_dim as i32]),
        ];
        let mut bytes = Vec::new();
        for (_, shape) in &shapes {
            let n: usize = shape.iter().map(|d| *d as usize).product();
            for _ in 0..n {
                bytes.extend_from_slice(&((rng.normal() * 0.3) as f32).to_le_bytes());
            }
        }
        let spec = HeadSpec {
            file: "test.head.bin".into(),
            dtype: "fp32".into(),
            byte_order: "little".into(),
            activation: "silu".into(),
            sha256: format!("{:x}", Sha256::digest(&bytes)),
            d_model: d_model as i32,
            hidden: hidden as i32,
            step_basis_dim: k as i32,
            out_dim: out_dim as i32,
            tensors: shapes
                .iter()
                .map(|(n, s)| HeadTensorSpec { name: (*n).into(), shape: s.clone() })
                .collect(),
        };
        (HeadModel::parse(bytes, spec.clone()).expect("head parses"), spec)
    }

    fn sample(d_model: usize, n_slots: usize, seed: u64) -> LoraSample {
        let mut rng = Rng::new(seed);
        LoraSample {
            hidden: (0..n_slots * d_model).map(|_| rng.normal()).collect(),
            anchors: vec![120.0; n_slots],
            // Well away from the anchor, so no residual sits on the pinball kink and a
            // finite-difference check measures the gradient rather than a corner.
            target_bg: (0..n_slots * PATCH_SIZE).map(|i| 190.0 + i as f64).collect(),
            n_slots: n_slots as i32,
            hidden_pert: Vec::new(),
            is_forecast: true,
        }
    }

    /// The same window with a COUNTERFACTUAL branch, drawn from its own seed so the frozen
    /// model's response `d0` is genuinely non-zero — a paired sample whose two branches agreed
    /// would make the distillation term vanish and its gradient check prove nothing.
    fn sample_paired(d_model: usize, n_slots: usize, seed: u64, pert_seed: u64) -> LoraSample {
        let mut rng = Rng::new(pert_seed);
        LoraSample {
            hidden_pert: (0..n_slots * d_model).map(|_| rng.normal()).collect(),
            ..sample(d_model, n_slots, seed)
        }
    }

    fn cfg() -> LoraConfig {
        LoraConfig {
            rank: 2,
            alpha: 4.0,
            target_hidden: true,
            target_l0: true,
            target_l1: true,
            target_l2: true,
        }
    }

    #[test]
    fn head_file_must_match_its_descriptor() {
        let (_, spec) = synthetic_head(8, 6, 3);
        let mut bad = spec.clone();
        bad.sha256 = "0".repeat(64);
        // Any bytes at all, with a digest that does not describe them.
        let bytes = vec![0u8; 4 * (PATCH_SIZE * 3 + 6 * 8 + 6 + 6 * 6 + 6 + 21 * 6 + 21)];
        assert!(HeadModel::parse(bytes.clone(), bad).is_err(), "digest must be checked");
        let mut short = spec.clone();
        short.tensors.pop();
        assert!(HeadModel::parse(bytes, short).is_err(), "a truncated tensor list must not parse");
    }

    #[test]
    fn a_fresh_adapter_is_exactly_the_identity() {
        // Attaching an untrained adapter must not move a single forecast: B is zero, so the
        // low-rank term is zero, and attach/detach is safe to offer as a toggle.
        let (head, spec) = synthetic_head(8, 6, 3);
        let s = sample(8, 4, 7);
        let base = head.forward(s.hidden.clone(), 4).unwrap();
        let fresh = lora_new(cfg(), spec.sha256.clone(), spec.d_model, spec.hidden, spec.out_dim, 42).unwrap();
        head.set_lora(Some(fresh)).unwrap();
        assert!(head.has_lora());
        let with = head.forward(s.hidden.clone(), 4).unwrap();
        assert_eq!(base, with, "a fresh adapter changed the head output");
        head.set_lora(None).unwrap();
        assert!(!head.has_lora());
        assert_eq!(head.forward(s.hidden, 4).unwrap(), base, "detach did not restore the head");
    }

    #[test]
    fn a_trained_adapter_moves_the_head() {
        let (head, spec) = synthetic_head(8, 6, 3);
        let mut w = lora_new(cfg(), spec.sha256.clone(), spec.d_model, spec.hidden, spec.out_dim, 1).unwrap();
        let mut rng = Rng::new(99);
        for p in w.params.iter_mut() {
            *p += rng.normal() * 0.05;
        }
        let s = sample(8, 4, 7);
        let base = head.forward(s.hidden.clone(), 4).unwrap();
        head.set_lora(Some(w)).unwrap();
        let with = head.forward(s.hidden, 4).unwrap();
        assert!(
            base.iter().zip(&with).any(|(a, b)| (a - b).abs() > 1e-9),
            "a non-zero adapter left the head output unchanged"
        );
    }

    #[test]
    fn adapter_round_trips_through_its_serialized_form() {
        let (_, spec) = synthetic_head(8, 6, 3);
        let mut w = lora_new(cfg(), spec.sha256.clone(), spec.d_model, spec.hidden, spec.out_dim, 5).unwrap();
        let mut rng = Rng::new(11);
        for p in w.params.iter_mut() {
            *p = rng.normal();
        }
        let blob = lora_serialize(&w);
        let back = lora_deserialize(blob.clone()).expect("round trip");
        assert_eq!(back, w);

        // A single flipped byte anywhere must be refused rather than loaded as a plausible
        // adapter that quietly moves every forecast it touches.
        for i in [0usize, 12, blob.len() / 2, blob.len() - 33] {
            let mut bad = blob.clone();
            bad[i] ^= 0xFF;
            assert!(lora_deserialize(bad).is_err(), "corruption at byte {i} was accepted");
        }
        assert!(lora_deserialize(blob[..blob.len() - 1].to_vec()).is_err(), "truncation");
        assert!(lora_deserialize(vec![]).is_err());
    }

    #[test]
    fn an_adapter_belongs_to_the_head_it_was_fitted_on() {
        let (head, spec) = synthetic_head(8, 6, 3);
        // Same config, different trunk width: attaching it would read the wrong weights with
        // every shape check downstream still passing.
        let foreign = lora_new(cfg(), spec.sha256.clone(), 16, 6, 21, 3).unwrap();
        assert!(head.set_lora(Some(foreign)).is_err());

        // And the case geometry cannot catch: the SAME shape, a DIFFERENT head. Two checkpoints of
        // one capacity have identical head dimensions, so without the digest an adapter fitted on
        // either would load into the other and decode plausibly, finitely, wrong.
        let mut other = lora_new(cfg(), spec.sha256.clone(), 8, 6, 21, 9).unwrap();
        other.head_sha256 = "f".repeat(64);
        assert!(head.set_lora(Some(other)).is_err(), "an adapter from another head was accepted");

        // Its own is accepted, and the digest survives the round trip.
        let mine = lora_new(cfg(), spec.sha256.clone(), 8, 6, 21, 9).unwrap();
        assert!(head.set_lora(Some(mine.clone())).is_ok());
        assert_eq!(lora_deserialize(lora_serialize(&mine)).unwrap().head_sha256, spec.sha256);
        // A digest that is not one is refused at creation.
        assert!(lora_new(cfg(), "short".into(), 8, 6, 21, 1).is_err());
    }

    #[test]
    fn config_guards_reject_a_pointless_or_hostile_adapter() {
        let (_, spec) = synthetic_head(8, 6, 3);
        let bad = |c: LoraConfig| lora_new(c, spec.sha256.clone(), 8, 6, 21, 1).is_err();
        assert!(bad(LoraConfig { rank: 0, ..cfg() }));
        assert!(bad(LoraConfig { rank: 1000, ..cfg() }));
        assert!(bad(LoraConfig { alpha: 0.0, ..cfg() }));
        assert!(bad(LoraConfig { alpha: f64::NAN, ..cfg() }));
        assert!(bad(LoraConfig {
            target_hidden: false,
            target_l0: false,
            target_l1: false,
            target_l2: false,
            ..cfg()
        }));
    }

    /// The analytic gradient against central finite differences, through the WHOLE chain: the
    /// pinball loss, the cumsum fan, the per-span median projection, the within-patch basis and
    /// the head's three layers. This is the only thing standing between a plausible-looking
    /// training curve and an adapter that optimises the wrong quantity.
    #[test]
    fn gradient_matches_finite_differences() {
        let d = desc();
        let (head, spec) = synthetic_head(8, 6, 3);
        let s = sample(8, 4, 3);
        let mut w = lora_new(cfg(), spec.sha256.clone(), spec.d_model, spec.hidden, spec.out_dim, 17).unwrap();
        let mut rng = Rng::new(23);
        for p in w.params.iter_mut() {
            *p = rng.normal() * 0.2; // away from the B = 0 initialisation
        }
        let lora = Lora::from_weights(&w, 8, 6, 21).unwrap();
        let mut analytic = vec![0.0f64; w.params.len()];
        sample_loss_and_grad(&head, &d, &s, Some(&lora), Some(&mut analytic), None, None).unwrap();

        let h = 1e-6;
        let mut checked = 0;
        for p in (0..w.params.len()).step_by(7) {
            let mut plus = w.clone();
            plus.params[p] += h;
            let mut minus = w.clone();
            minus.params[p] -= h;
            let lp = Lora::from_weights(&plus, 8, 6, 21).unwrap();
            let lm = Lora::from_weights(&minus, 8, 6, 21).unwrap();
            let f_plus = sample_loss_and_grad(&head, &d, &s, Some(&lp), None, None, None).unwrap();
            let f_minus = sample_loss_and_grad(&head, &d, &s, Some(&lm), None, None, None).unwrap();
            let fd = (f_plus - f_minus) / (2.0 * h);
            let got = analytic[p];
            let scale = fd.abs().max(got.abs()).max(1e-6);
            assert!(
                (fd - got).abs() / scale < 1e-4,
                "param {p}: analytic {got:.9e} vs finite difference {fd:.9e}"
            );
            checked += 1;
        }
        assert!(checked > 10, "only {checked} parameters were checked");
        // The gradient must actually be non-trivial, or the check above passes on zeros.
        assert!(analytic.iter().any(|g| g.abs() > 1e-8), "the gradient is entirely zero");
    }

    /// The DISTILLATION term's own gradient, checked the same way and for the same reason.
    ///
    /// This is the only thing standing between a plausible training curve and an adapter
    /// optimising the wrong quantity: the term runs a second forward through the whole head and
    /// accumulates into the same buffer, so a sign error or a missed branch would show up as a
    /// fit that trains smoothly while doing nothing about the counterfactual.
    #[test]
    fn the_distillation_gradient_matches_finite_differences() {
        let d = desc();
        let (head, spec) = synthetic_head(8, 6, 3);
        let s = sample_paired(8, 4, 3, 77);
        let mut w = lora_new(cfg(), spec.sha256.clone(), spec.d_model, spec.hidden, spec.out_dim, 17).unwrap();
        let mut rng = Rng::new(23);
        for p in w.params.iter_mut() {
            *p = rng.normal() * 0.2;
        }
        // A non-zero frozen response, so the term is not trivially satisfied at `e = 0`.
        let m = branch_median_risk(&head, &d, &s, None, false).unwrap();
        let mp = branch_median_risk(&head, &d, &s, None, true).unwrap();
        let d0: Vec<f64> = m.iter().zip(&mp).map(|(a, b)| a - b).collect();
        assert!(d0.iter().any(|x| x.abs() > 1e-9), "the frozen response is zero; nothing to pin");
        let ctx = DistillCtx { d0: &d0, scale: 3.0 };

        let lora = Lora::from_weights(&w, 8, 6, 21).unwrap();
        let mut analytic = vec![0.0f64; w.params.len()];
        let with_term =
            sample_loss_and_grad(&head, &d, &s, Some(&lora), Some(&mut analytic), Some(&ctx), None)
                .unwrap();

        // FIRST: the term does something at all.
        //
        // The finite-difference check below differences the SAME function it takes the analytic
        // gradient from, so an implementation that ignored `ctx` outright would satisfy it
        // perfectly — zero against zero is consistent. What that check proves is that the two
        // agree; what this one proves is that there is anything for them to agree about.
        let mut without = vec![0.0f64; w.params.len()];
        let no_term =
            sample_loss_and_grad(&head, &d, &s, Some(&lora), Some(&mut without), None, None)
                .unwrap();
        assert!(
            (with_term - no_term).abs() > 1e-9,
            "the distillation term did not move the loss: {with_term:.9e} vs {no_term:.9e}",
        );
        let moved = analytic
            .iter()
            .zip(&without)
            .filter(|(a, b)| (*a - *b).abs() > 1e-9)
            .count();
        assert!(
            moved > analytic.len() / 4,
            "the term moved only {moved} of {} gradient components",
            analytic.len(),
        );

        let h = 1e-6;
        let mut checked = 0;
        for p in (0..w.params.len()).step_by(7) {
            let mut plus = w.clone();
            plus.params[p] += h;
            let mut minus = w.clone();
            minus.params[p] -= h;
            let lp = Lora::from_weights(&plus, 8, 6, 21).unwrap();
            let lm = Lora::from_weights(&minus, 8, 6, 21).unwrap();
            let f_plus = sample_loss_and_grad(&head, &d, &s, Some(&lp), None, Some(&ctx), None).unwrap();
            let f_minus = sample_loss_and_grad(&head, &d, &s, Some(&lm), None, Some(&ctx), None).unwrap();
            let fd = (f_plus - f_minus) / (2.0 * h);
            let got = analytic[p];
            let scale = fd.abs().max(got.abs()).max(1e-6);
            assert!(
                (fd - got).abs() / scale < 1e-4,
                "param {p}: analytic {got:.9e} vs finite difference {fd:.9e}"
            );
            checked += 1;
        }
        assert!(checked > 10, "only {checked} parameters were checked");
    }

    /// With the term off, or the sample unpaired, the whole path must be bit-identical to what
    /// it replaced — the refactor into two branches must not have moved the baseline.
    #[test]
    fn an_unpaired_sample_is_unchanged_by_the_distillation_path() {
        let d = desc();
        let (head, spec) = synthetic_head(8, 6, 3);
        let unpaired = sample(8, 4, 3);
        let mut w = lora_new(cfg(), spec.sha256.clone(), spec.d_model, spec.hidden, spec.out_dim, 17).unwrap();
        let mut rng = Rng::new(23);
        for p in w.params.iter_mut() {
            *p = rng.normal() * 0.2;
        }
        let lora = Lora::from_weights(&w, 8, 6, 21).unwrap();

        let mut g_off = vec![0.0f64; w.params.len()];
        let l_off = sample_loss_and_grad(&head, &d, &unpaired, Some(&lora), Some(&mut g_off), None, None).unwrap();

        // An unpaired sample offered a context still takes nothing from it: there is no second
        // branch to compare against.
        let d0 = vec![1.0f64; unpaired.n_slots as usize * PATCH_SIZE];
        let ctx = DistillCtx { d0: &d0, scale: 5.0 };
        let mut g_on = vec![0.0f64; w.params.len()];
        let l_on = sample_loss_and_grad(&head, &d, &unpaired, Some(&lora), Some(&mut g_on), Some(&ctx), None).unwrap();

        assert_eq!(l_off, l_on);
        assert_eq!(g_off, g_on);

        // …and a PAIRED sample with `scale = 0` is likewise untouched.
        let paired = sample_paired(8, 4, 3, 77);
        let zero = DistillCtx { d0: &d0, scale: 0.0 };
        let mut g_zero = vec![0.0f64; w.params.len()];
        let mut g_none = vec![0.0f64; w.params.len()];
        let l_zero = sample_loss_and_grad(&head, &d, &paired, Some(&lora), Some(&mut g_zero), Some(&zero), None).unwrap();
        let l_none = sample_loss_and_grad(&head, &d, &paired, Some(&lora), Some(&mut g_none), None, None).unwrap();
        assert_eq!(l_zero, l_none);
        assert_eq!(g_zero, g_none);
    }

    /// The guard's clause ordering, on literal numbers. The ordering IS the safety property:
    /// an adapter the guard could not measure must never read as one it measured and passed.
    #[test]
    fn the_guard_orders_its_clauses_so_an_unmeasurable_adapter_is_never_a_pass() {
        let opts = LoraGuardOpts {
            max_windows: 64,
            min_windows: 4,
            probe_dose_u: 1.0,
            min_frozen_response: 2.0,
            min_retention: 0.25,
            max_retention: 4.0,
            min_sign_agreement: 0.75,
        };

        // These cases exercise the VERDICT, not the two-space split, so each set stands for both
        // the risk-space response the ratio is taken on and the mg/dL response that is reported.
        let v = |a: &[f64], b: &[f64]| guard_verdict(a, b, a, b, &opts);

        // Preserved: same sign everywhere, retention 1.
        let r0 = vec![-10.0, -12.0, -11.0, -9.0];
        assert_eq!(v(&r0, &r0).verdict, LoraGuardVerdict::Pass);

        // Collapsed to a twentieth — the failure the whole phase exists for.
        let dead = vec![-0.5, -0.6, -0.55, -0.45];
        let got = v(&r0, &dead);
        assert_eq!(got.verdict, LoraGuardVerdict::Blocked);
        assert!(got.why.contains("dose response"), "{}", got.why);

        // Amplified fourfold and more: an inflated ISF is as untrustworthy as a collapsed one,
        // and the dose that follows is too LARGE.
        let loud = vec![-60.0, -62.0, -61.0, -59.0];
        assert_eq!(v(&r0, &loud).verdict, LoraGuardVerdict::Blocked);

        // Retention is fine — the medians agree — but the direction disagrees on three windows
        // in eight. Eight rather than four because a mixed-sign median over four values sits near
        // zero, which would trip the retention clause first and test nothing about this one.
        let wide = vec![-10.0; 8];
        let mut flipped = vec![-10.0; 8];
        for v in flipped.iter_mut().take(3) {
            *v = 10.0;
        }
        let got = v(&wide, &flipped);
        assert_eq!(got.retention, 1.0, "the medians agree; only the signs differ");
        assert_eq!(got.verdict, LoraGuardVerdict::Blocked);
        assert!(got.why.contains("direction"), "{}", got.why);

        // Too few windows, and a frozen model with no response to preserve: INCONCLUSIVE, never
        // a pass. These are checked before every Blocked clause for exactly that reason.
        assert_eq!(v(&r0[..2], &r0[..2]).verdict, LoraGuardVerdict::Inconclusive);
        let flat = vec![0.1, -0.1, 0.05, -0.05];
        assert_eq!(v(&flat, &flat).verdict, LoraGuardVerdict::Inconclusive);

        // The two-space split itself. The mg/dL pair is REPORTED and gated on; it is never the
        // ratio. Here the adapter preserved the risk-space response exactly while the mg/dL
        // response doubled — a pure level shift through a convex `f_inv` — and retention must not
        // move, because nothing about the model's response to insulin changed.
        let risk = vec![-10.0, -12.0, -11.0, -9.0];
        let mgdl_frozen = vec![-20.0, -24.0, -22.0, -18.0];
        let mgdl_adapted: Vec<f64> = mgdl_frozen.iter().map(|x| x * 2.0).collect();
        let shifted = guard_verdict(&risk, &risk, &mgdl_frozen, &mgdl_adapted, &opts);
        assert_eq!(shifted.retention, 1.0, "retention is the risk-space ratio");
        assert_eq!(shifted.verdict, LoraGuardVerdict::Pass);
        assert_eq!(shifted.frozen_response_mgdl, -21.0);
        assert_eq!(shifted.adapted_response_mgdl, -42.0);

        // ...and the mg/dL floor still bites on the pair, not on the ratio's space: a frozen model
        // with a healthy risk-space response but under 2 mg/dL/U at the horizon has nothing worth
        // preserving in the units a dose is read in.
        let tiny = vec![-0.5, -0.6, -0.55, -0.45];
        let quiet = guard_verdict(&risk, &risk, &tiny, &tiny, &opts);
        assert_eq!(quiet.verdict, LoraGuardVerdict::Inconclusive);
        assert!(quiet.why.contains("mg/dL/U"), "{}", quiet.why);
    }

    /// A whole fit with the term on: it runs, reports what it did, and reaches a verdict.
    #[test]
    fn a_paired_fit_reports_its_distillation_and_a_guard_verdict() {
        let d = desc();
        let (head, _) = synthetic_head(8, 6, 3);
        let samples: Vec<LoraSample> =
            (0..40).map(|i| sample_paired(8, 4, 100 + i as u64, 900 + i as u64)).collect();
        let opts = LoraTrainOpts {
            epochs: 6,
            lr: 5e-3,
            holdout_frac: 0.3,
            weight_decay: 0.0,
            seed: 4,
            distill_weight: 1.0,
        };
        let out = lora_train(&head, &d, samples, cfg(), opts, None).expect("training runs");

        assert_eq!(out.report.n_paired, out.report.n_train);
        assert!(out.report.distill_scale > 0.0, "the term was on but scaled to zero");
        assert_eq!(out.report.distill_history.len(), out.report.epochs_run as usize);
        assert!(out.report.guard.is_some(), "held-out forecast windows existed");
        // The held-out number stays PURE pinball, so every stored adapter's pair remains
        // comparable and `improved` stays well posed.
        assert!(out.report.holdout_loss_before.is_finite());
    }

    /// A truncated counterfactual is refused by name rather than silently trained on.
    #[test]
    fn a_truncated_counterfactual_is_rejected() {
        let d = desc();
        let (head, _) = synthetic_head(8, 6, 3);
        let mut samples: Vec<LoraSample> = (0..16).map(|i| sample(8, 4, 100 + i as u64)).collect();
        samples[3].hidden_pert = vec![0.0; 5]; // neither empty nor n·d
        let opts = LoraTrainOpts {
            epochs: 2,
            lr: 1e-3,
            holdout_frac: 0.25,
            weight_decay: 0.0,
            seed: 4,
            distill_weight: 1.0,
        };
        let err = lora_train(&head, &d, samples, cfg(), opts, None).unwrap_err();
        assert!(format!("{err:?}").contains("counterfactual"), "{err:?}");
    }

    #[test]
    fn training_reduces_the_loss_and_reports_a_held_out_number() {
        let d = desc();
        let (head, _) = synthetic_head(8, 6, 3);
        let samples: Vec<LoraSample> = (0..24).map(|i| sample(8, 4, 100 + i as u64)).collect();
        let opts = LoraTrainOpts {
            epochs: 12,
            lr: 5e-3,
            holdout_frac: 0.25,
            weight_decay: 0.0,
            seed: 4,
            distill_weight: 0.0,
        };
        let out = lora_train(&head, &d, samples, cfg(), opts, None).expect("training runs");
        assert_eq!(out.report.n_holdout, 6);
        assert_eq!(out.report.n_train, 18);
        assert!(
            out.report.train_loss_last < out.report.train_loss_first,
            "training loss did not fall: {} -> {}",
            out.report.train_loss_first,
            out.report.train_loss_last
        );
        assert!(out.report.holdout_loss_before.is_finite());
        assert!(out.report.holdout_loss_after.is_finite());
        assert_eq!(out.report.loss_history.len(), 12);
        assert_eq!(out.report.holdout_history.len(), 12);
        // The returned weights are the argmin of the held-out trace, not the last epoch's.
        assert!(out.report.best_epoch >= 0 && out.report.best_epoch <= 12);
        assert!(
            out.report.holdout_loss_after <= out.report.holdout_loss_before,
            "the fit returned an adapter worse than the frozen head: {} -> {}",
            out.report.holdout_loss_before,
            out.report.holdout_loss_after
        );
        if out.report.best_epoch > 0 {
            let at_best = out.report.holdout_history[out.report.best_epoch as usize - 1];
            assert!((at_best - out.report.holdout_loss_after).abs() < 1e-12);
        }
        // The fit is reproducible from its seed.
        let samples2: Vec<LoraSample> = (0..24).map(|i| sample(8, 4, 100 + i as u64)).collect();
        let again = lora_train(&head, &d, samples2, cfg(), opts, None).unwrap();
        assert_eq!(again.weights.params, out.weights.params);
    }

    #[test]
    fn a_longer_fit_never_returns_a_worse_adapter_than_a_shorter_one() {
        // The failure this pins: a fit that overfits used to hand back the epoch it had
        // overfitted on, so raising the epoch count made the forecast worse.
        let d = desc();
        let (head, _) = synthetic_head(8, 6, 3);
        let mk = || -> Vec<LoraSample> { (0..24).map(|i| sample(8, 4, 100 + i as u64)).collect() };
        let base = LoraTrainOpts { epochs: 4, lr: 2e-2, holdout_frac: 0.25, weight_decay: 0.0, seed: 9, distill_weight: 0.0 };
        let short = lora_train(&head, &d, mk(), cfg(), base, None).unwrap().report;
        let long = lora_train(&head, &d, mk(), cfg(), LoraTrainOpts { epochs: 200, ..base }, None)
            .unwrap()
            .report;
        assert!(
            long.holdout_loss_after <= short.holdout_loss_after + 1e-12,
            "200 epochs returned {} against 4 epochs' {}",
            long.holdout_loss_after,
            short.holdout_loss_after
        );
        // ...and never worse than attaching nothing at all.
        assert!(long.holdout_loss_after <= long.holdout_loss_before + 1e-12);
        assert!(long.holdout_history.iter().all(|v| v.is_finite()));
    }

    #[test]
    fn a_fit_with_no_holdout_keeps_the_last_epoch_and_says_so() {
        // Nothing to select on: the report must not imply a choice was made.
        let d = desc();
        let (head, _) = synthetic_head(8, 6, 3);
        let samples: Vec<LoraSample> = (0..12).map(|i| sample(8, 4, i as u64)).collect();
        let opts = LoraTrainOpts { epochs: 3, lr: 1e-3, holdout_frac: 0.0, weight_decay: 0.0, seed: 2, distill_weight: 0.0 };
        let out = lora_train(&head, &d, samples, cfg(), opts, None).unwrap();
        assert_eq!(out.report.n_holdout, 0);
        assert_eq!(out.report.best_epoch, 3);
        assert!(out.report.holdout_loss_after.is_nan());
        assert!(!out.report.improved);
    }

    #[test]
    fn progress_reports_once_per_epoch_in_order() {
        use std::sync::Mutex as StdMutex;
        struct Rec(StdMutex<Vec<(i32, i32)>>);
        impl LoraProgress for Rec {
            fn on_epoch(&self, epoch: i32, epochs: i32, train_loss: f64, holdout_loss: f64) {
                assert!(train_loss.is_finite() && holdout_loss.is_finite());
                self.0.lock().unwrap().push((epoch, epochs));
            }
        }
        let d = desc();
        let (head, _) = synthetic_head(8, 6, 3);
        let samples: Vec<LoraSample> = (0..24).map(|i| sample(8, 4, i as u64)).collect();
        let opts = LoraTrainOpts { epochs: 5, lr: 1e-3, holdout_frac: 0.25, weight_decay: 0.0, seed: 3, distill_weight: 0.0 };
        let rec = std::sync::Arc::new(Rec(StdMutex::new(Vec::new())));
        lora_train(&head, &d, samples, cfg(), opts, Some(rec.clone())).unwrap();
        assert_eq!(*rec.0.lock().unwrap(), vec![(1, 5), (2, 5), (3, 5), (4, 5), (5, 5)]);
    }

    #[test]
    fn training_refuses_a_history_too_short_to_fit() {
        let d = desc();
        let (head, _) = synthetic_head(8, 6, 3);
        let samples: Vec<LoraSample> = (0..6).map(|i| sample(8, 4, i as u64)).collect();
        let opts = LoraTrainOpts { epochs: 4, lr: 1e-3, holdout_frac: 0.2, weight_decay: 0.0, seed: 1, distill_weight: 0.0 };
        assert!(lora_train(&head, &d, samples, cfg(), opts, None).is_err());
    }

    #[test]
    fn training_rejects_hostile_options_and_malformed_samples() {
        let d = desc();
        let (head, _) = synthetic_head(8, 6, 3);
        let good: Vec<LoraSample> = (0..12).map(|i| sample(8, 4, i as u64)).collect();
        let base = LoraTrainOpts { epochs: 2, lr: 1e-3, holdout_frac: 0.2, weight_decay: 0.0, seed: 1, distill_weight: 0.0 };
        for opts in [
            LoraTrainOpts { epochs: 0, ..base },
            LoraTrainOpts { epochs: 100_000, ..base },
            LoraTrainOpts { lr: 0.0, ..base },
            LoraTrainOpts { lr: f64::NAN, ..base },
            LoraTrainOpts { holdout_frac: 0.95, ..base },
            LoraTrainOpts { holdout_frac: -0.1, ..base },
        ] {
            assert!(lora_train(&head, &d, good.clone(), cfg(), opts, None).is_err(), "{opts:?}");
        }
        let mut ragged = good.clone();
        ragged[3].target_bg.pop();
        assert!(lora_train(&head, &d, ragged, cfg(), base, None).is_err());
    }
}
