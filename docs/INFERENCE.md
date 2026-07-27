# Inference on device

The model contract — the three spaces, the checkpoint, the architecture, the
attention mask, the risk transform, normalization, the frozen index map, the
decode and its constants — is specified once, for the whole suite, in
**`T1DMCOMMON/SPEC/inference.md`**. It is not restated here.

- Repository: <https://github.com/0xdeadf1sh/T1DMCOMMON>
- Sibling checkout: `../T1DMCOMMON/SPEC/inference.md`
- The two risk spaces it depends on: `../T1DMCOMMON/SPEC/invariants.md` §4

What follows is only what is true of **this app** and of no other consumer.

## Where it is implemented here

The numerics are Rust, in `crates/t1dm-core/src/preproc.rs`: the descriptor
parse, normalize/denormalize, the risk transform pair, the quantile assembly,
the degeneracy check. That crate is the numeric authority; Kotlin orchestrates
and never re-implements a step of it. The backend seam is `:inference`
(`backend/`), and the exported graph is cut at `head_raw` — everything on either
side of that cut is the crate's work, in fp64.

The descriptor is the **sole** source of the pre/post constants. A descriptor
without a `kovatchev` block is rejected rather than defaulted, and the physical
bounds it carries are what the rail-pinned degeneracy check tests against; given
the wrong range that check cannot fire at all.

## The optional BG pre-filter

The reference pipeline applies no smoother. This app offers one, on the BG
channel only, applied before normalization — a denoising choice it makes for a
live CGM feed, not part of the model contract.

- **Strictly causal.** `smooth[t]` is the degree-2 polynomial fit to
  `x[t−(w−1) : t+1]` read at `t`, so it uses only `x[≤ t]`, is computable online,
  and never leaks the future. The left edge is causally replicated with `x[0]`.
- **Window.** Odd, user-selected from `1, 7, 13, 19, 25` samples (× 5 min);
  `w = 1` is the identity, i.e. the raw reference signal. The default is `7`.
- **Taps.** The least-squares quadratic evaluated at `pos = w−1`
  (`scipy.signal.savgol_coeffs(w, 2, pos=w-1, use='dot')`), so the newest sample
  carries the largest weight and the estimate does not lag. For `w = 7` they are
  the exact rationals `[5, −3, −6, −4, 3, 15, 32] / 42`.
- **Only BG.** Carb and insulin are reconstructed from analytic curves and are
  already smooth by construction; they are never filtered.
- **It moves the anchor.** `last_bg` is read off the last context BG cell, so the
  filter moves it too. White-noise variance falls with `Σtap²` as the window
  widens (`1.000, 0.762, 0.516, 0.386, 0.308` for the five windows), while the
  endpoint estimator extrapolates its quadratic to the edge of its own support —
  so a turn takes longer to settle and a spike is overshot further.
- The physical guards sit outside the filter and hold at every window: BG
  clamped to the descriptor's range, carb and insulin floored at `0`.

## Backends

One exported model, two implemented backends: fp32 CPU via XNNPACK as the
reference authority, and fp16 GPU via the Vulkan delegate as a measured shadow.
A non-authoritative backend may render a forecast, but may not feed a dose until
it has cleared the fp32-agreement gate. There is no NPU path — see
`backend/NpuBackends.kt` for what each unavailable route would need.
