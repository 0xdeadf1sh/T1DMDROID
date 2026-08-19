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
parse, normalize/denormalize, the risk transform pair, the whole graph input
(the left-pad, the masked-patch fill, the attention mask, the slot selection and
the per-slot anchors), the per-span quantile assembly, and the degeneracy check.
That crate is the numeric authority; Kotlin orchestrates and never re-implements
a step of it — `:inference` copies the crate's three float buffers into direct
NIO buffers and reasons about no geometry at all. The exported graph is cut at
`head_raw`; everything on either side of that cut is the crate's work, in fp64.

The descriptor is parsed exactly as the exporter writes it. There is no
projection onto a second schema on this side: a projection is a place a key is
silently dropped, and a dropped decode constant is invisible until a forecast
decodes wrong.

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
  stored fan, which is the raw one. The correction reaches three **display** fans
  and nothing else: the BG panel's forecast overlay, the hindsight sweep beside
  it, and the exercise review's swept fan. All three, because a fan drawn raw
  beside a calibrated one — on the same axes or a screen away — states a second
  and narrower uncertainty with nothing saying why. The two swept fans apply the
  correction in-sample, to rows it was fitted on, which §8.4's exchangeability
  argument does not cover; accepted because nothing either sweep draws is read by
  anything.
- **The wire carries the raw fan.** `SPEC/http-api.md`'s Prediction has no
  calibrated/raw discriminator, and a calibrated fan would satisfy its "row index
  3 equals `line`" and travel indistinguishably. Nothing calibrated is written to
  the `prediction` table or pushed.
- The descriptor's `conformal.enabled` flag is unrelated to this and is read by
  nothing.

## The adapter (LoRA)

The base weights are baked into the `.pte` and are never written. What the export
gives instead is a seam: the graph emits `slot_hidden` — the trunk's final-normed
hidden state per masked slot — and the head's own weights ship beside the artifact
as a flat fp32 file. `crates/t1dm-core/src/head.rs` re-runs the head from those two
and puts a low-rank adapter in front of it.

- **Where it attaches.** A rank-`r` bottleneck on the hidden state, and a rank-`r`
  delta on each of the head's three `Linear`s. `B` starts at zero, so a fresh
  adapter is exactly the identity and attaching one changes no forecast until it
  has been trained.
- **What it cannot do.** The trunk's attention and FFN blocks are frozen inside
  the graph and no gradient reaches them. This adapts the representation the head
  reads and the head itself; it does not retrain the model.
- **How it is fitted.** Historical windows are replayed through the graph to
  recover their hidden states — not stored, since they are a function of the model
  and would go stale the moment the artifact was replaced — and paired with the BG
  that actually followed. A window whose horizon carries a gap is dropped. The loss
  is the pinball loss over the seven levels, in risk space, read on the ASSEMBLED
  fan; the gradient is analytic through the assembly and is gated by a
  finite-difference check in the crate's tests.
- **The bar for attaching.** The fit holds out the newest quarter of the windows
  and reports the frozen head's loss beside the adapter's on them. A fit that does
  not beat the frozen head learnt the patient's past, not their physiology, and the
  panel says so rather than hiding it.
- **A head that is not the graph's own is refused.** With no adapter attached the
  re-run head must reproduce the graph's `head_raw` from the graph's own
  `slot_hidden`; that is checked once per model at first run. A mismatch disables
  the adapter path for that model rather than forecasting differently from
  everything already stored.
- **Attaching changes what the model is.** The adapted fan is the one the panel
  draws, the one that is stored and pushed, the one the alarm engine classifies and
  the one the dose calculator rolls — there is one forecaster, not a display variant
  and a real one. The band correction and the stored predictions the realised-accuracy
  suite reads were both fitted against the frozen forecaster, so both are dropped when
  an adapter is attached or detached.
- **It fails closed, not open.** A model with an adapter attached whose adapter
  cannot be applied — an unusable head, a graph with no `slot_hidden` — produces NO
  forecast that cycle rather than a frozen one. Falling back silently would store and
  alarm on a forecaster the user is not looking at, and mix two of them in one model's
  history with nothing recording which produced which row.
- Adapters are stored in the `lora` table and ride the archive; a restore brings
  them back DETACHED, because which adapter a model runs is a property of this
  phone and not of a backup file.

## The Lab

One screen that runs any loaded model over any masked set. Forecast, backcast and
infill are the same artifact under a different `slot_sel`, so the Lab needs no
second inference path — it passes a masked set the cycle never asks for.

Nothing the Lab produces is stored, pushed, alarmed on, or read by the dose
calculator. That is what lets it mask real glucose, invent a week of history, and
run an untried adapter.

- **Synthetic mode** fills only the steps the real history lacks — every real
  sample survives — from the seeded generator in `crates/t1dm-core/src/synth.rs`.
  It exists because the newer models read up to seven days of context and a phone
  that has been running two days cannot give them one. It is a shaped
  plausibility, not a simulator and not the patient.
- **The median slider** reads the fan the model already emitted at an arbitrary
  level. The line is computed in the crate, in risk space, between the two
  published levels that bracket τ; the screen picks from a precomputed ladder
  rather than interpolating itself.

## Gap repair

A sensor swap or a dropped signal leaves a hole, and a seven-day context with a
hole in it is a context the model never saw. The repair masks the gap's patches
INSIDE a window that has real evidence on both sides of it — which is the whole
advantage of an infill over a forecast — and stores the reconstruction in its own
`bg_infill` table.

**A fill is not a reading.** It conditions the DISPLAYED forecast and nothing else:
not the alarm engine, not the statistics, not the accuracy suite, not the wire.
`fitBgSeries`, the series a model is FITTED on, never sees one, and neither does
`dosingBgSeries` — a dose scored partly on a model's own reconstruction would close
a loop between an output and the advice derived from it. The BG panel does not yet
draw fills.

## Backends

One exported model, two implemented backends: fp32 CPU via XNNPACK as the
reference authority, and fp16 GPU via the Vulkan delegate as a measured shadow.
Both take `(patches, attn_mask, slot_sel)` and return `(head_raw, time_logits,
slot_hidden)`; the masked set crosses as a one-hot selection matrix, so no int64
tensor crosses the runtime boundary.
A non-authoritative backend may render a forecast, but may not feed a dose until
it has cleared the fp32-agreement gate. There is no NPU path — see
`backend/NpuBackends.kt` for what each unavailable route would need.
