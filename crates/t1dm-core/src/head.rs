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

    let n_params = w.params.len();
    let mut m = vec![0.0f64; n_params];
    let mut v = vec![0.0f64; n_params];
    let mut rng = Rng::new(opts.seed as u64 ^ 0xA5A5_5A5A_1234_9876);
    let mut order: Vec<usize> = (0..n_train).collect();
    let mut history = Vec::with_capacity(opts.epochs as usize);
    let mut holdout_history = Vec::with_capacity(opts.epochs as usize);
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
        for &idx in &order {
            let lora = Lora::from_weights(&w, d, head.hidden, out_dim)?;
            let mut grad = vec![0.0f64; n_params];
            let loss =
                sample_loss_and_grad(head, desc, &train[idx], Some(&lora), Some(&mut grad))?;
            epoch_loss += loss;
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
        acc += sample_loss_and_grad(head, desc, s, lora.as_ref(), None)?;
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
fn sample_loss_and_grad(
    head: &HeadModel,
    desc: &ModelDescriptor,
    sample: &LoraSample,
    lora: Option<&Lora>,
    mut grad: Option<&mut [f64]>,
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
    let loss = pinball(desc, &fan.q_tau_risk, &sample.target_bg, n_steps);
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
    for i in 0..n_steps {
        let row = i * N_QUANTILES;
        let mut dm = 0.0;
        for k in 0..N_QUANTILES {
            dm += dq[row + k];
        }
        d_median[i] = dm;
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

    let mut d_out = vec![0.0f64; out_dim];
    for slot in 0..n {
        let a = &acts[slot];
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
            let h = &sample.hidden[slot * head.d_model..(slot + 1) * head.d_model];
            let mut sink = vec![0.0f64; head.d_model];
            let (da, db) =
                grad[off_hidden..off_hidden + site.n_params()].split_at_mut(site.r * site.n_in);
            site.backward(h, &a.u_hidden, &d_h, lora.scale, da, db, &mut sink);
        }
    }
    Ok(loss)
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
        sample_loss_and_grad(&head, &d, &s, Some(&lora), Some(&mut analytic)).unwrap();

        let h = 1e-6;
        let mut checked = 0;
        for p in (0..w.params.len()).step_by(7) {
            let mut plus = w.clone();
            plus.params[p] += h;
            let mut minus = w.clone();
            minus.params[p] -= h;
            let lp = Lora::from_weights(&plus, 8, 6, 21).unwrap();
            let lm = Lora::from_weights(&minus, 8, 6, 21).unwrap();
            let f_plus = sample_loss_and_grad(&head, &d, &s, Some(&lp), None).unwrap();
            let f_minus = sample_loss_and_grad(&head, &d, &s, Some(&lm), None).unwrap();
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
        let base = LoraTrainOpts { epochs: 4, lr: 2e-2, holdout_frac: 0.25, weight_decay: 0.0, seed: 9 };
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
        let opts = LoraTrainOpts { epochs: 3, lr: 1e-3, holdout_frac: 0.0, weight_decay: 0.0, seed: 2 };
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
        let opts = LoraTrainOpts { epochs: 5, lr: 1e-3, holdout_frac: 0.25, weight_decay: 0.0, seed: 3 };
        let rec = std::sync::Arc::new(Rec(StdMutex::new(Vec::new())));
        lora_train(&head, &d, samples, cfg(), opts, Some(rec.clone())).unwrap();
        assert_eq!(*rec.0.lock().unwrap(), vec![(1, 5), (2, 5), (3, 5), (4, 5), (5, 5)]);
    }

    #[test]
    fn training_refuses_a_history_too_short_to_fit() {
        let d = desc();
        let (head, _) = synthetic_head(8, 6, 3);
        let samples: Vec<LoraSample> = (0..6).map(|i| sample(8, 4, i as u64)).collect();
        let opts = LoraTrainOpts { epochs: 4, lr: 1e-3, holdout_frac: 0.2, weight_decay: 0.0, seed: 1 };
        assert!(lora_train(&head, &d, samples, cfg(), opts, None).is_err());
    }

    #[test]
    fn training_rejects_hostile_options_and_malformed_samples() {
        let d = desc();
        let (head, _) = synthetic_head(8, 6, 3);
        let good: Vec<LoraSample> = (0..12).map(|i| sample(8, 4, i as u64)).collect();
        let base = LoraTrainOpts { epochs: 2, lr: 1e-3, holdout_frac: 0.2, weight_decay: 0.0, seed: 1 };
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
