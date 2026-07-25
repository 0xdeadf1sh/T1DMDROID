# T1DMAI Inference Guide

A hardware- and framework-agnostic guide to loading a trained T1DMAI checkpoint
and producing a blood-glucose (BG) forecast. It is written for anyone shipping
these models in their own application — CPU, GPU, NPU, mobile, or a non-PyTorch
runtime. Everything below is verified against the source in this repository
(`config.py`, `model.py`, `utils.py`, `normalization.py`, `inference.py`).

> [!CAUTION]
> **Research and educational use only.** T1DMAI is trained on synthetic
> simulator data and is **not a medical device**. Its output is a forecast of
> artificial or research signals, **not** clinical guidance. It must not be used
> to make medical, diagnostic, or treatment decisions, to calculate or adjust
> insulin doses, or to manage diabetes in any way. No regulatory clearance.

The model is plain **fp32 PyTorch** — no autocast, no custom CUDA. The parts a
non-PyTorch runtime must reimplement are the pre/post-processing: per-channel
normalization, the Kovatchev risk transform, and the quantile assembly. All of
them are pure numeric, and every constant they need is tabulated in
[§11](#11-reference-constants). There is **no input smoother** in the reference
pipeline — the model consumes the raw signal directly (BG only clamped to
`[BG_CLAMP_MIN, BG_CLAMP_MAX]`, carb/insulin floored at 0). T1DMDROID applies an
optional pre-filter of its own on the BG channel; see [§7.1](#71-the-optional-bg-pre-filter-before-normalization).

## Table of contents

- [1. Mental model: three spaces](#1-mental-model-three-spaces)
- [2. Getting a checkpoint](#2-getting-a-checkpoint)
- [3. Architecture](#3-architecture)
- [4. Attention mask](#4-attention-mask)
- [5. The Kovatchev risk transform (physical ↔ risk)](#5-the-kovatchev-risk-transform-physical--risk)
- [6. Normalization (raw ↔ z-score)](#6-normalization-raw--z-score)
- [7. Input construction (the frozen index map)](#7-input-construction-the-frozen-index-map)
- [8. Forward pass and output decode](#8-forward-pass-and-output-decode)
- [9. End-to-end recipe](#9-end-to-end-recipe)
- [10. Minimal PyTorch example](#10-minimal-pytorch-example)
- [11. Reference constants](#11-reference-constants)
- [12. Porting to another runtime](#12-porting-to-another-runtime)

---

## 1. Mental model: three spaces

Every tensor lives in exactly one of three spaces, crossed by exactly two
bridge pairs. Tracking which space a value is in is the single most important
thing when reimplementing inference.

| space | representation |
|---|---|
| **(a) normalized z-space** | model **inputs**: per-channel z-scores. The BG input is `z(f(bg))` — Kovatchev risk **then** z-score. |
| **(b) mg/dL physical** | the `last_bg` anchor, the true forecast in mg/dL, all clinical thresholds, the GUI. |
| **(c) Kovatchev risk** | the BG input *before* its z-score (`f(bg)`), and **all model outputs** (`q_tau`, `median`). |

The two bridges:

- **(a) ↔ (b): `normalize` / `denormalize`** — [§6](#6-normalization-raw--z-score).
- **(b) ↔ (c): `kovatchev_f` / `kovatchev_f_inv`** — [§5](#5-the-kovatchev-risk-transform-physical--risk).

The model **never sees raw mg/dL** for BG, and it **never emits mg/dL**. Inputs
are risk-then-z; outputs are risk. Your code owns both bridges: build the input
by `f` then z; decode the output by `f_inv`.

---

## 2. Getting a checkpoint

A checkpoint is a `torch.save` pickle; load it with:

```python
import torch
ckpt = torch.load("t1dmai.pt", map_location="cpu", weights_only=False)
```

### 2.1 Checkpoint keys

A training-produced checkpoint contains:

| key | contents | needed for inference? |
|---|---|---|
| `arch_version` | e.g. `'risk-v2'` | provenance |
| `loss_schema` | e.g. `'kendall-pinball-dilate-v3'` | provenance |
| `step` | training step | provenance |
| `model_state_dict` | live weights | base weights |
| `model_ema_state_dict` | EMA shadow weights (same keys) | **use these** |
| `weighting_state_dict` | `{log_sigma_Q, log_sigma_D}` | no (loss only) |
| `muon_optimizer_state_dict`, `adam_optimizer_state_dict` | optimizer state | no (bloat) |
| `training_config` | dict of shape/hparam scalars | rebuild the graph |
| `normalization_stats` | `{channel: {mean, std}}` | **yes** |
| `conformal_delta`, `conformal_meta` | optional band recalibration (§8.4) | optional |
| `master_seed`, `loss_history`, `val_history`, `loss_ema`, `best_val_*` | telemetry | no |

Some checkpoints (e.g. a fine-tuned one) carry a leaner set and may **omit
`training_config`**; in that case recover the architecture dimensions from the
state-dict tensor shapes ([§3.1](#31-recovering-dimensions)).

### 2.2 Which weights to run

Validation and every reported metric were produced under the **EMA** weights, so
run those. Merge the EMA shadow over the live weights, then load:

```python
sd     = ckpt["model_state_dict"]
ema    = ckpt.get("model_ema_state_dict")
merged = {k: ema.get(k, v) for k, v in sd.items()} if ema else dict(sd)
model.load_state_dict(merged, strict=False)   # strict=False tolerates the aux time_head
model.eval()
```

### 2.3 Slimming a shipped checkpoint

For distribution you can drop `muon_optimizer_state_dict`,
`adam_optimizer_state_dict`, `weighting_state_dict`, and all telemetry. Keep the
EMA weights (or a pre-merged state dict), `normalization_stats`, and enough of
`training_config` (or the shapes) to rebuild the graph. Dropping the optimizer
states shrinks the file to roughly the model size. The `time_head.*` weights are
a diagnostic hour-of-day probe that never touches the BG forecast — you may drop
them entirely.

---

## 3. Architecture

`T1DMAI` takes **no constructor arguments**; it reads every dimension from
`config.py` module globals at construction. To rebuild the graph you set those
globals (from the checkpoint) and instantiate.

### 3.1 Recovering dimensions

| config global | meaning | from `training_config` | from state-dict shape |
|---|---|---|---|
| `D_MODEL` | hidden width | `d_model` | `patch_embed.weight` rows |
| `N_LAYERS` | transformer blocks | `n_layers` | count of `blocks.N.*` |
| `N_HEADS` | attention heads | `n_heads` | `blocks.0.attn.alibi_slopes` length |
| `HEAD_DIM` | `= D_MODEL // N_HEADS` | derive | `blocks.0.attn.q_norm.weight` length |
| `FFN_DIM` | SwiGLU inner width | `ffn_dim` | `blocks.0.ffn.w1.weight` rows |
| `PATCH_SIZE` | steps per patch = 6 | `patch_size` | `step_basis` rows |
| `N_INPUT_FEATURES` | 3 (fixed) | — | `PATCH_DIM / PATCH_SIZE` |
| `PATCH_DIM` | `PATCH_SIZE·N_INPUT_FEATURES` = 18 | derive | `patch_embed.weight` cols |
| `PREDICTION_PATCHES` | horizon patches | `prediction_patches` | — |
| `MIN/MAX_CONTEXT_PATCHES` | 16 / 48 | `min/max_context_patches` | — |
| `BG_HEAD_HIDDEN` | head MLP width | — | `bg_head.0.weight` rows |
| `BG_HEAD_STEP_BASIS_DIM` (K) | within-patch coeffs = 3 | — | `step_basis` cols |
| `N_SPREADS` | 3 | — | `bg_head.4.weight` rows `= K·(1+2·N_SPREADS)` |

A few decode-critical constants are **not** stored anywhere and are fixed
released defaults — you must reproduce them exactly: `ROPE_BASE = 1000`, RMSNorm
`eps = 1e-6`, `QUANTILE_LEVELS = (.05, .1, .25, .5, .75, .9, .95)`,
`BG_QUANTILE_SPREAD_MIN = 1e-3`, `BG_HEAD_MEDIAN_MODE = 'global'`,
`BG_HEAD_MEDIAN_GLOBAL_DIM = 6`, `BG_HEAD_STEP_BASIS_TYPE = 'dct'`, and the
Kovatchev constants (§5). The per-patch `step_basis` buffer **is** saved in the
state dict; the global-median DCT basis is **not** — recompute it (§8.2).

### 3.2 Block structure (pre-norm, 2 residual writes per block)

```
x = patch_embed(patches)                      # Linear(PATCH_DIM -> D_MODEL), has bias
for block in blocks:
    x = x + attn(norm1(x))                    # RMSNorm -> TemporalSelfAttention
    x = x + ffn (norm2(x))                    # RMSNorm -> SwiGLU
x = final_norm(x)                             # RMSNorm
pred     = x[:, -PREDICTION_PATCHES:, :]      # slice the horizon patches
coeff    = bg_head(pred).view(B, P, K, 1 + 2*N_SPREADS)
head_raw = einsum('sk,bpkc->bpsc', step_basis, coeff)   # (B, P, PATCH_SIZE, 7)
q_tau, median = assemble_quantiles(head_raw, last_bg)   # §8
```

- **RMSNorm** (no mean subtraction, no bias): `x / sqrt(mean(x², dim=-1) + eps) *
  weight`, `eps = 1e-6`, learned per-channel `weight` (init 1).

### 3.3 Temporal self-attention (per block)

1. `q, k, v = w_q/w_k/w_v(x)` (no bias), reshaped to `(B, N_HEADS, T, HEAD_DIM)`.
2. **QK-norm**: per-head `RMSNorm(HEAD_DIM)` (`q_norm` / `k_norm`, eps 1e-6) on
   `q` and `k`, **before** RoPE.
3. **RoPE** on `q` and `k` (base 1000; §3.4).
4. **ALiBi bias**: `alibi_bias[h,i,j] = -|i-j| · |slope_h|`, where
   `slope_h = alibi_slopes[h].abs()`. **The stored slopes are trained and may be
   negative — you MUST take the absolute value.** (A naive port that keeps the raw
   sign flips a recency bias into an anti-recency bias.) Init was the geometric
   series `slope_h = 2^(-8(h+1)/N_HEADS)`.
5. **Additive mask**: `logits_mask = alibi_bias + struct`, where `struct` is `0`
   where attention is allowed and `-inf` where blocked (§4).
6. `attn = softmax(QKᵀ / sqrt(HEAD_DIM) + logits_mask) @ V`. In PyTorch this is
   `F.scaled_dot_product_attention(q, k, v, attn_mask=logits_mask)`; the
   `1/sqrt(HEAD_DIM)` scaling and the additive float mask are the only things a
   reimplementation must reproduce.
7. `out = w_o(concat_heads)`.

### 3.4 RoPE cache (`build_rope_cache(T, HEAD_DIM, base=1000)`)

```
half     = HEAD_DIM // 2
inv_freq = 1 / (base ** (arange(0, half) / half))     # (half,)
freqs    = outer(arange(T), inv_freq)                 # (T, half)
emb      = concat([freqs, freqs], dim=-1)             # (T, HEAD_DIM)
cos, sin = emb.cos(), emb.sin()
```

Applied per head with the rotate-half convention:

```
x1, x2 = x[..., :half], x[..., half:]
x_rot  = concat([-x2, x1], dim=-1)
out    = x * cos + x_rot * sin
```

Tables depend only on `T` and `HEAD_DIM`, so they are built once per forward and
shared across layers.

### 3.5 SwiGLU FFN

`out = w2( SiLU(w1(x)) ⊙ w3(x) )`, all `bias=False`, where `SiLU(z) = z·σ(z)`.

---

## 4. Attention mask

Let `C = n_ctx` context patches, `P = PREDICTION_PATCHES` prediction patches,
`T = C + P`. The boolean mask (`True = attend`) is **hybrid, not standard
causal**:

```
Context    → Context      bidirectional   (full C×C block True)
Prediction → Context      full            (every pred sees all ctx)
Prediction → Prediction   bidirectional   (full P×P block True — NOT triangular)
Context    → Prediction   BLOCKED         (False — no future leak)
```

Only the top-right `C×P` quadrant (context rows, prediction columns) is `False`;
everything else is `True`. Convert to the additive float mask by placing `-inf`
where `False` and `0` where `True`, then add the ALiBi bias.

The prediction horizon is decoded **jointly** in one forward pass; there is no
within-horizon causal triangulation, and future leak is prevented solely by the
blocked context→prediction quadrant. The prediction patches are always the
**last `P`** rows of the sequence. The model accepts any `n_ctx` in
`[MIN_CONTEXT_PATCHES, MAX_CONTEXT_PATCHES]` = `[16, 48]` patches (8–24 h); build
the mask with the actual `n_ctx` (`create_attention_mask(n_ctx, P)`).

---

## 5. The Kovatchev risk transform (physical ↔ risk)

The symmetrizing transform whose risk-distance equates the clinical danger of a
low and a high excursion. It is the (b) ↔ (c) bridge.

```
f(g)     = 1.509 · ( ln(g)^1.084 − 5.381 )                # mg/dL -> risk
f_inv(r) = exp( ( r/1.509 + 5.381 )^(1/1.084) )           # risk  -> mg/dL
```

Constants: `SCALE = 1.509`, `POWER = 1.084`, `OFFSET = 5.381`. Reference values:
`f(20) = −3.1629`, `f(70) = −0.8806`, `f(100) = −0.2196`, `f(180) = +0.8792`,
`f(400) = +2.3884`, `f(500) = +2.8133`.

**Clamp guards** (reproduce these to match the model at extremes):

- `f_inv`: first replace non-finite risk inputs (NaN/−inf → `f(20)`, +inf →
  `f(500)`), then **clamp the risk input** to `[f(20), f(500)] ≈ [−3.1629,
  +2.8133]` (this keeps the base `r/1.509 + 5.381 ≥ 0` — no complex/NaN — and
  prevents fp32 `exp` overflow), compute `f_inv`, then **clamp the output** to
  `[20, 500]` mg/dL.
- `f` on BG is applied inside `normalize` on physically-clamped mg/dL, so it is
  always well-defined.

`BG_CLAMP_MIN = 20.0` and `BG_CLAMP_MAX = 500.0` mg/dL are the physical BG
bounds (these are the only two numbers borrowed from the simulator; they are
plain constants — you do not need the simulator to run inference).

---

## 6. Normalization (raw ↔ z-score)

Three channels, fixed order — the index **is** the model input-feature index:

```
CHANNEL_NAMES = ['bg_absolute', 'carb_intake', 'insulin_combined']
                #  feat 0        feat 1         feat 2
```

Membership sets: `RISK_SPACE_CHANNELS = {'bg_absolute'}`,
`SPARSE_LOG1P_CHANNELS = {'carb_intake', 'insulin_combined'}`.

**Units.** BG in mg/dL; carb in **grams per 5-min step**; insulin in **units per
5-min step**, with basal and bolus already **summed** into the single channel.
One timestep = 5 min; one patch = 6 steps = 30 min.

**normalize (raw → z):**

```
bg  (risk) :  z = ( f(clamp(x, 20, 500)) − mean_bg ) / (std_bg + 1e-8)
carb/ins   :  z = ( log1p(max(x, 0))     − mean_c  ) / (std_c  + 1e-8)
```

**denormalize (z → raw):**

```
bg  (risk) :  x = f_inv( z·(std_bg + 1e-8) + mean_bg )
carb/ins   :  x = max( expm1( z·(std_c + 1e-8) + mean_c ), 0 )
```

`log1p(x) = ln(1+x)`, `expm1(x) = eˣ − 1`. The `std + 1e-8` floor and the
`max(·, 0)` on the sparse inverse are load-bearing.

**Stats structure** (`ckpt['normalization_stats']`, mirrored on disk as
`normalization_stats.json`):

```json
{ "bg_absolute":      {"mean": <risk-space>,  "std": <risk-space>},
  "carb_intake":      {"mean": <log1p-space>, "std": <log1p-space>},
  "insulin_combined": {"mean": <log1p-space>, "std": <log1p-space>} }
```

The BG mean/std live in **risk space** (fit on `f(bg)`); carb/insulin in log1p
space. Prefer the checkpoint's embedded stats — they are exactly what the model
was trained with.

---

## 7. Input construction (the frozen index map)

Per timestep the features are `[bg_absolute, carbs, insulin]`
(`N_INPUT_FEATURES = 3`). The output-channel → input-feature map is
`CHANNEL_TO_FEAT = {0: 1, 1: 2}` (carb-channel 0 → feat 1, insulin-channel 1 →
feat 2). BG (feat 0) is never overrideable.

**Patch flatten order is step-major:** `(PATCH_SIZE, N_INPUT_FEATURES) →
PATCH_DIM` via a C-contiguous reshape, i.e.

```
flat_index = t · N_INPUT_FEATURES + feat        # t in [0, 6), feat in [0, 3)
PATCH_DIM  = PATCH_SIZE · N_INPUT_FEATURES = 6 · 3 = 18
```

### 7.1 The optional BG pre-filter (before normalization)

The reference pipeline applies **no smoother**. Inputs, forecast target, loss and
metrics all live in one raw post-noise space: the same raw BG is the model input,
the forecast target and the `last_bg` anchor. BG is clamped to `[20, 500]`; carb
and insulin are floored at `0` (the `log1p` transform does this in `normalize`).

T1DMDROID additionally offers a **strictly-causal one-sided Savitzky-Golay**
pre-filter on the **BG channel only**, applied before normalization. It is a
denoising choice made by the application, not part of the model contract.

- `smooth[t]` is the degree-2 polynomial fit to `x[t−(w−1) : t+1]` read at `t` —
  it uses **only** `x[≤ t]`, so it is online-computable (the live CGM stream is
  filtered identically) and never leaks the future. The left edge is causally
  replicated with `x[0]`.
- The window `w` is odd and user-selected from `1, 7, 13, 19, 25` samples (× 5
  min per step); `w = 1` is the identity, i.e. the raw reference signal. The
  default is `7`.
- The endpoint coefficients are the least-squares quadratic fit evaluated at
  `pos = w−1` — `scipy.signal.savgol_coeffs(w, 2, pos=w-1, use='dot')` — so the
  newest sample carries the largest weight and the estimate does not lag. For
  `w = 7` they are the exact rationals `[5, −3, −6, −4, 3, 15, 32] / 42`.
- The carb and insulin channels are never filtered: they are reconstructed from
  analytic curves (gamma / Bateman / exponential action), already smooth by
  construction.
- The physical guards are not part of the filter and hold at every window: BG is
  clamped to `[20, 500]`, carb and insulin floored at `0`.
- The filter moves the `last_bg` anchor of [§7.4](#74-the-last_bg-anchor), since
  that anchor is read off the last context BG cell. White-noise variance falls
  with `Σtap²` as `w` widens (`1.000, 0.762, 0.516, 0.386, 0.308` for the five
  windows), while the endpoint estimator extrapolates its quadratic to the edge
  of its own support, so a turn takes longer to settle and a spike is overshot
  further.

### 7.2 Building the context tensor

`context` has shape `(n_ctx, PATCH_SIZE, N_INPUT_FEATURES)`, already normalized.
From a raw history:

1. Take the trailing raw per-step series for BG (mg/dL), carb (g/step), and
   insulin (U/step, basal + bolus summed), length `n_ctx · PATCH_SIZE`, with
   `n_ctx ∈ [16, 48]`.
2. Clamp BG to `[20, 500]`; floor carb/insulin at 0. Optionally pre-filter BG
   (§7.1); the reference applies no filter.
3. `normalize` each channel (BG via risk-z, carb/insulin via log1p-z).
4. Reshape to `(n_ctx, 6, 3)`.

### 7.3 Prediction-zone patches

The `P = PREDICTION_PATCHES` prediction patches are appended after the context:

- **feat 0 (BG): always 0** — it is what the model predicts.
- **feat 1 / feat 2 (carb/insulin): the no-dose baseline `normalize(0)`** for
  that channel — **not** a literal `z = 0`. Because the sparse inverse routes
  through `log1p`, a literal `z = 0` decodes to a phantom ≈0.39 g / ≈0.14 U per
  step. Overwrite these slots with **announced** future doses (normalized) to
  condition the forecast (a what-if, or a deployment where the pump schedule is
  known).

### 7.4 The `last_bg` anchor

`model.forward` requires a `(B,)` mg/dL anchor. It is simply the last context BG
cell denormalized back to mg/dL:

```
last_bg = f_inv( context[-1, -1, 0] · (std_bg + 1e-8) + mean_bg )    # clamp [20, 500]
```

The forward asserts `last_bg ≥ 20 − 1e-3` (a units tripwire that catches a
z-scored value routed in by mistake) and forms the risk anchor `f(last_bg)`
internally. No further filtering is applied to the context here.

---

## 8. Forward pass and output decode

**Signature** (frozen):
`forward(patches, attn_mask, last_bg, return_time=False) -> (q_tau, median)`.

- `patches`: `(B, T, PATCH_DIM)`; `attn_mask`: `(T, T)` or `(B, T, T)` bool
  (`True = attend`); `last_bg`: `(B,)` mg/dL.
- `q_tau`: `(B, PREDICTION_PATCHES, PATCH_SIZE, 7)` in **risk space**, ascending τ.
- `median`: `(B, PREDICTION_PATCHES, PATCH_SIZE)` in risk space (`== q_tau[..., 3]`).
- `return_time=True` additionally returns the diagnostic hour-of-day probe
  logits; they never affect `q_tau`/`median`, so ignore them for BG inference.

`QUANTILE_LEVELS = (0.05, 0.10, 0.25, 0.50, 0.75, 0.90, 0.95)`, median column
index `3`.

### 8.1 `assemble_quantiles(head_raw, last_bg, carry_spread=0.0)`

`head_raw` is `(B, P, S, 7)`: column 0 = median delta; columns 1..3 = the τ>.5
spreads (nearest→far, .75/.9/.95); columns 4..6 = the τ<.5 spreads
(nearest→far, .25/.1/.05).

```
anchor = f( clamp(last_bg, 20, 500) )                    # (B,), risk; broadcast over (P, S)
delta  = head_raw[..., 0]                                # (B, P, S)

# --- median (mode = 'global', the released default) ---
delta_flat = delta.reshape(B, P*S)          # C-contiguous, PATCH-MAJOR: flat = p*S + s
Bg         = global_median_basis(n=P*S, G=6, kind='dct') # (P*S, 6) orthonormal columns
m          = anchor + (delta_flat @ Bg @ Bgᵀ).reshape(B, P, S)   # low-freq DCT projection

# --- spreads (identical under every median mode) ---
spread = softplus(head_raw[..., 1:]) + 1e-3              # strict positive floor
d_up   = spread[..., :3]                                 # .75/.9/.95
d_dn   = spread[..., 3:]                                 # .25/.1/.05
up = m[..., None] + carry_spread + cumsum(d_up, dim=-1)  # ascending
dn = m[..., None] − carry_spread − cumsum(d_dn, dim=-1)  # descending in value
q_tau = concat([ flip(dn, -1), m[..., None], up ], dim=-1)   # (B, P, S, 7) ascending τ
```

The median is a projection of the raw per-patch deltas onto a 6-dimensional
low-frequency DCT subspace over the full horizon, so it is smooth and cannot
drift or oscillate; at initialization (`delta ≈ 0`) it is `≈ anchor` — a flat
persistence forecast. The `cumsum` of strictly-positive spreads guarantees a
monotone ascending fan around the median. `carry_spread` defaults to `0` (only
rolling inflation uses a non-zero value, §9).

### 8.2 The global-median DCT basis

`global_median_basis(n, G, 'dct')` builds DCT-II cosine modes over the full
horizon and L2-orthonormalizes the columns:

```
B[s, j] = cos( π (s + 0.5) j / n )     for s in [0, n), j in [0, G)
then normalize each column to unit L2 norm.
```

This basis is **not** saved in the checkpoint — recompute it. (The per-patch
`step_basis`, of shape `(PATCH_SIZE, K) = (6, 3)`, **is** saved; read it from the
state dict or recompute the same way over `n = 6`.)

### 8.3 Decoding to mg/dL

Inference owns the risk → mg/dL inverse:

```
median_bg = f_inv(median).flatten()      # (P·S,) mg/dL — the headline forecast
bands     = f_inv(q_tau)                  # (P, S, 7) mg/dL band edges
```

At the 2 h default, `P·S = 4 · 6 = 24` steps = 2 h at 5-min cadence. Both are
clamped into `[20, 500]` by `f_inv`.

### 8.4 Optional conformal recalibration

If `ckpt['conformal_delta']` is present (per-`(step, τ)`, shape `(P·S, 7)`, in
mg/dL, applied downstream of `f_inv`), you can recalibrate the band fan:
`apply_quantile_conformal(bands, delta, median_idx=3)` adds `delta` and
re-enforces three invariants — the **median is held fixed** (`delta[..., 3] = 0`),
the **fan stays monotone** (no crossing), and an **all-zero delta is the
identity**. The point forecast is untouched. A stored delta is fit on the
**simulator** distribution; for real-world CGM it must be re-fit per cohort or
omitted. Skipping it is bit-identical to the raw bands.

---

## 9. End-to-end recipe

**Single window** (≤ `PREDICTION_HORIZON_HOURS`, default 2 h):

1. Gather the trailing raw history: BG (mg/dL), carb (g/step), insulin (U/step,
   basal + bolus summed), length `n_ctx · 6`, `n_ctx ∈ [16, 48]`.
2. Clamp BG to `[20, 500]`; floor carb/insulin at 0 (no filtering — `normalize`
   floors the sparse channels through `log1p`).
3. `normalize` each channel → `context (n_ctx, 6, 3)`.
4. Build `patches (T, 18)`: context reshaped step-major, then `P` prediction
   patches with BG = 0 and carb/insulin = `normalize(0)` **or** announced doses.
5. `attn_mask = create_attention_mask(n_ctx, P)` (§4).
6. `last_bg = f_inv(denormalize(context[-1, -1, 0]))`, clamp `[20, 500]`.
7. `q_tau, median = model(patches[None], attn_mask, last_bg[None])`.
8. `median_bg = f_inv(median)` (mg/dL); `bands = f_inv(q_tau)`; optionally
   conformal-recalibrate `bands`.

**Autoregressive rolling** (horizons beyond one window): repeat the window; each
roll —

1. Run steps 4–8 → a risk-space `median`.
2. Re-feed: `median → f_inv → mg/dL → normalize → BG feat-0 slot` of the new
   context patches. Carb/insulin come from the caller's announced schedule for
   that roll, else the `normalize(0)` no-dose baseline.
3. Slide the context forward, dropping the oldest patches once it exceeds
   `MAX_CONTEXT_PATCHES`. BG anchors at the last forecast BG carried across rolls.
4. To keep the band fan from resetting at each roll seam, carry the accumulated
   risk-space half-width forward via `assemble_quantiles`'s `carry_spread`
   (seeded each roll by the terminal-step half-width the model emitted). The
   median is untouched; only the bands widen as uncertainty accumulates.

The shipped `inference.predict` and `inference.predict_rolling` implement both
recipes; `predict_what_if` is `predict` with `overrides`.

---

## 10. Minimal PyTorch example

Using the shipped helpers (the simplest path — no reimplementation):

```python
import numpy as np, torch
from model import T1DMAI
from inference import predict
from normalization import normalize
from config import MIN_CONTEXT_PATCHES, PATCH_SIZE, N_INPUT_FEATURES

# 1. Load a checkpoint and its embedded stats.
ckpt   = torch.load("t1dmai.pt", map_location="cpu", weights_only=False)
stats  = ckpt["normalization_stats"]
model  = T1DMAI()                                   # dims read from config.py
sd, ema = ckpt["model_state_dict"], ckpt.get("model_ema_state_dict")
merged = {k: ema.get(k, v) for k, v in sd.items()} if ema else dict(sd)
model.load_state_dict(merged, strict=False)
model.eval()

# 2. Build a normalized context from a raw history.
#    Here: n_ctx patches of BG (mg/dL), carb (g/step), insulin (U/step).
n_ctx = MIN_CONTEXT_PATCHES
raw   = np.zeros((n_ctx * PATCH_SIZE, N_INPUT_FEATURES), dtype=np.float32)
raw[:, 0] = 120.0        # BG mg/dL   (raw; clamp a real stream to [20, 500])
raw[:, 1] = 0.0          # carb g/step
raw[:, 2] = 0.02         # insulin U/step (basal)
ctx_norm = normalize(raw, stats)                    # (n_ctx*6, 3) normalized
context  = torch.from_numpy(ctx_norm.reshape(n_ctx, PATCH_SIZE, N_INPUT_FEATURES))

# 3. Forecast. predict() handles the mask, the last_bg anchor, and f_inv.
with torch.no_grad():
    out = predict(model, context, normalization_stats=stats)

median_bg = out["median_bg"]    # (PREDICTION_PATCHES*PATCH_SIZE,) mg/dL headline
bands     = out["bands"]        # (PREDICTION_PATCHES, PATCH_SIZE, 7) mg/dL fan
```

To announce future doses, pass
`overrides={0: carb_norm, 1: insulin_norm}` (each `(PREDICTION_PATCHES,
PATCH_SIZE)` **normalized**) to `predict`. For horizons past 2 h use
`inference.predict_rolling(...)`.

---

## 11. Reference constants

Everything a from-scratch reimplementation needs (none require the simulator):

| constant | value |
|---|---|
| Kovatchev `SCALE / POWER / OFFSET` | `1.509 / 1.084 / 5.381` |
| `BG_CLAMP_MIN / MAX` | `20.0 / 500.0` mg/dL |
| risk clamp `[f(20), f(500)]` | `[−3.1629, +2.8133]` |
| `PATCH_SIZE` | `6` (5-min steps; one patch = 30 min) |
| `N_INPUT_FEATURES` / `PATCH_DIM` | `3` / `18` |
| feature order | `[bg_absolute, carb, insulin]` |
| `CHANNEL_TO_FEAT` | `{0: 1, 1: 2}` |
| patch flatten | step-major: `flat = t·3 + feat` |
| `PREDICTION_PATCHES` / output steps | `4` / `24` (2 h) at the default horizon |
| `MIN / MAX_CONTEXT_PATCHES` | `16 / 48` (8–24 h) |
| `QUANTILE_LEVELS` | `(.05, .1, .25, .5, .75, .9, .95)`; median idx `3` |
| `N_SPREADS` / `N_QUANTILES` | `3` / `7` |
| `BG_QUANTILE_SPREAD_MIN` | `1e-3` |
| `BG_HEAD_STEP_BASIS_DIM` (K) / type | `3` / `'dct'` |
| `BG_HEAD_MEDIAN_MODE` / `GLOBAL_DIM` | `'global'` / `6` |
| `ROPE_BASE` | `1000` |
| RMSNorm `eps` | `1e-6` |
| normalize `std` floor | `1e-8` |
| input filter | none in the reference (raw signal; BG clamped to `[20, 500]`, carb/insulin floored at 0) |
| ALiBi slope | `−|i−j| · |slope_h|`, `slope_h = |stored|` (init `2^(−8(h+1)/N_HEADS)`) |
| SDPA scaling | `1/sqrt(HEAD_DIM)` |

The per-channel `mean` / `std` come from `ckpt['normalization_stats']` (BG in
risk space, carb/insulin in log1p space).

---

## 12. Porting to another runtime

- **The graph is plain fp32 PyTorch** — `model.eval()` + `torch.no_grad()`, no
  autocast anywhere. Once the dimensions are fixed the graph is static and
  **traceable / ONNX-exportable**.
- The only non-elementwise/matmul op is `F.scaled_dot_product_attention` with an
  additive float mask. Export with a math-fallback SDPA, or hand-roll
  `softmax(QKᵀ/sqrt(HEAD_DIM) + mask) @ V`. RoPE, RMSNorm, SwiGLU, and ALiBi are
  all elementwise or matmul.
- **What a non-PyTorch runtime reimplements outside the exported graph** (all
  pure numeric): per-channel `normalize` / `denormalize`; `kovatchev_f` /
  `kovatchev_f_inv` with their clamp guards; the `last_bg` anchor; the
  step-major patch flatten; the prediction-zone `normalize(0)` fill;
  `assemble_quantiles` (softplus, cumsum, DCT projection); and the optional
  conformal apply.
- **Watch the ALiBi sign.** The stored slopes are trained and can be negative;
  the forward takes `.abs()`. Using the raw sign silently inverts the temporal
  bias — this is the single easiest porting mistake.
- **Keep the decode constants exact.** `ROPE_BASE`, the median mode/basis, and
  the quantile floor are not stored in the checkpoint; a released model is locked
  to the values in §11.
