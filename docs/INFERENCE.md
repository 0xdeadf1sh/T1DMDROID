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

## Band recalibration, fitted on device

`SPEC/inference.md` §8.4 describes an optional conformal correction the checkpoint
may carry, fit on the simulator distribution, and states that for real-world CGM
it must be re-fit per cohort or omitted. The exporter ships none, so the fan the
model produces here is the raw fan.

This app fits its own, from the patient's own matured forecasts. It is the same
object §8.4 defines — per `(step, τ)`, additive, in mg/dL, downstream of `f_inv`
— fitted and applied in `crates/t1dm-core/src/conformal.rs` and stored one row
per model in the `conformal_delta` table.

- **Split-conformal.** The trailing 14 days of matured windows are ordered by the
  time they were made and split chronologically: the older 70 % is the
  calibration set, the newer 30 % is held out and scored. The stored coverage
  and mean band width therefore describe windows the correction never saw.
- **Fitted at the model's own horizon**, read from its descriptor's
  `PREDICTION_HORIZON_HOURS`, not at the accuracy suite's longest horizon. The
  correction's step count is therefore the length of the fan it will be applied
  to; a model whose horizon cannot be established is not fitted at all.
- **It lapses.** A correction applies for one fitting window past the moment it
  was made, and the raw fan is drawn from then on. Validity rests on
  exchangeability between the calibration set and the forecasts the delta later
  reaches; a change in the patient's own behaviour breaks that and is not
  detectable here, so the correction is trusted for no longer than the history it
  was fitted on. The drill-down keeps the figures and marks them expired.
- **It belongs to the artifact.** Applying a staged model update renames the
  `.pte` and descriptor in place under an unchanged id, so the correction and the
  stored forecasts it was fitted on are dropped along with the artifact they
  describe.
- **Fail-closed.** Below 144 calibration windows the correction is zero, not an
  extrapolated one, and the panel names both counts. The crate additionally
  raises any threshold below the point at which the extreme levels' order
  statistics would clamp — 19 for the seven levels of `invariants.md` §6.
- **The median does not move.** §8.4 pins it and the crate rejects a delta that
  does not, so the forecast line, and every dose scored off it, is identical
  before and after a fit.
- **Classify raw, calibrate for display.** The alarm engine, the calculator
  rails, the excursion detectors and the realized-accuracy suite all read the
  stored fan, which is the raw one. The correction reaches the BG panel's
  forecast overlay and nothing else.
- **The wire carries the raw fan.** `SPEC/http-api.md`'s Prediction has no
  calibrated/raw discriminator, and a calibrated fan would satisfy its "row index
  3 equals `line`" and travel indistinguishably. Nothing calibrated is written to
  the `prediction` table or pushed.
- The descriptor's `conformal.enabled` flag is unrelated to this and is read by
  nothing.

## Backends

One exported model, two implemented backends: fp32 CPU via XNNPACK as the
reference authority, and fp16 GPU via the Vulkan delegate as a measured shadow.
A non-authoritative backend may render a forecast, but may not feed a dose until
it has cleared the fp32-agreement gate. There is no NPU path — see
`backend/NpuBackends.kt` for what each unavailable route would need.
