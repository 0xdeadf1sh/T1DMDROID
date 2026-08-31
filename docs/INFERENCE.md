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
- **A rolled band takes the correction off the whole panel.** The on-demand roll
  extends past the 2 h masked set the delta is fitted against, and §8.4 forbids
  broadcasting a delta to another protocol, so the roll's tail can never wear one.
  For as long as a rolled band is drawn, the BG panel's forecast overlay and its
  hindsight sweep drop the correction too, on the same rule as above: one basis per
  panel. The trigger is the band, not the roll — a roll no longer than the validated
  horizon, or a degenerate one, draws only a median line and takes nothing away.
- **The wire carries the raw fan.** `SPEC/http-api.md`'s Prediction has no
  calibrated/raw discriminator, and a calibrated fan would satisfy its "row index
  3 equals `line`" and travel indistinguishably. Nothing calibrated is written to
  the `prediction` table or pushed.
- The descriptor's `conformal.enabled` flag is unrelated to this and is read by
  nothing.

## The adapter (LoRA)

The base weights are baked into the `.pte` and are never written. What the export
gives instead is a seam: the graph emits `hidden` — the trunk's final-normed state
for every patch — and the head's own weights ship beside the artifact as a flat
fp32 file. `preproc::step_states` gathers each span's nodes out of `hidden` and
splines them into the head's per-step input; `crates/t1dm-core/src/head.rs` re-runs
the head over those states and puts a low-rank adapter in front of it.

- **Where it attaches.** A rank-`r` bottleneck on the step state, and a rank-`r`
  delta on each of the head's three `Linear`s. The spline is a fixed linear mix of
  nodes and the site is linear, so this is the same function as the node-state
  adapter `SPEC/inference.md` §3.1 describes. `B` starts at zero, so a fresh
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
  re-run head must reproduce the graph's `head_raw` from the graph's own `hidden`;
  that is checked once per model at first run. A mismatch disables
  the adapter path for that model rather than forecasting differently from
  everything already stored.
- **Attaching changes what the model is.** The adapted fan is the one the panel
  draws, the one that is stored and pushed, the one the alarm engine classifies and
  the one the dose calculator rolls — there is one forecaster, not a display variant
  and a real one. The band correction and the stored predictions the realised-accuracy
  suite reads were both fitted against the frozen forecaster, so both are dropped when
  an adapter is attached or detached.
- **It fails closed, not open.** A model with an adapter attached whose adapter
  cannot be applied — an unusable head, a graph with no `hidden` — produces NO
  forecast that cycle rather than a frozen one. Falling back silently would store and
  alarm on a forecaster the user is not looking at, and mix two of them in one model's
  history with nothing recording which produced which row.
- Adapters are stored in the `lora` table and ride the archive; a restore brings
  them back DETACHED, because which adapter a model runs is a property of this
  phone and not of a backup file.

## The Lab

One screen that generates a synthetic patient over the selected model's context
window. Nothing it produces is stored, pushed, alarmed on, or read by the dose
calculator, which is what lets it invent a week of history.

**Synthetic mode** fills only the steps the real history lacks — every real sample
survives — from the seeded generator in `crates/t1dm-core/src/synth.rs`. It exists
because the newer models read up to seven days of context and a phone that has
been running two days cannot give them one. It is a shaped plausibility, not a
simulator and not the patient. The trace marks which steps it invented.

The model is picked for its window LENGTH. No inference runs here.

## Editing the curve

The BG panel is the editing surface. `Edit` swaps the chip row for a toolbar; one
finger drags out a stretch of time and stops there, and every act on that stretch
is a separate press. A drag shorter than the touch slop selects nothing, and each
end of a selection is a handle that resizes it.

- **Cut** erases every stored BG on the grid in the selection, locally and on the
  server. It is the only operation in the app that destroys measured physiologic
  data on purpose.
- **Fill** reconstructs the selection. Forecast, backcast and infill are the same
  artifact under a different `slot_sel`, so this needs no second inference path —
  it passes a masked set the cycle never asks for. The geometry is derived from
  where the stretch sits and named on the button; it is never chosen.
- **τ** reads the fan the model already emitted at an arbitrary level, in the
  crate, in risk space, between the two published levels that bracket τ. It moves
  the drawn line through the fan and records which level it landed on, so a
  promotion of it cannot later be read as the median.
- **Undo** takes back the last cut or fill. It holds in memory for the session: a
  cut is pushed to the server as it is made, and undoing one restores the rows
  with their provenance and re-pushes each slot.
- **Fills** hides the reconstruction overlay without discarding anything.

A gap in the sensor signal is a hole, and a seven-day context with a hole in it is
a context the model never saw. A fill masks the gap's patches INSIDE a window that
has real evidence on both sides of it — the whole advantage of an infill over a
forecast — and stores the reconstruction in its own `bg_infill` table.

**A fill is not a reading.** It conditions the DISPLAYED forecast and nothing else:
not the alarm engine, not the statistics, not the accuracy suite, not the dose
calculator. `fitBgSeries`, the series a model is FITTED on, never sees one, and
neither does `dosingBgSeries` — a dose scored partly on a model's own
reconstruction would close a loop between an output and the advice derived from
it. The BG panel draws a fill as its quantile fan with a dashed line over it, in
the surface's own muted ink and never in a glucose colour. A row that predates the
fan columns has two band edges and nothing between them, and draws as the single
band it is. A span nobody wants is **discarded**; the fan and the row go with it.

**A fill can be PROMOTED into the record** from the panel's edit bar, and it then
crosses the wire as a sample flagged reconstructed. The flag is for life: the
value may never clear an alarm, feed a dose, count as measured context for a cold
start, or enter a statistic. A backcast may not be promoted at all — nothing
brackets it on the left, so storing one extends the history backwards on a single
anchor.

**A promoted sample is neither a fit TARGET nor a fit WINDOW'S CONTEXT.**
`fitBgSeries` excludes it from the target, and the replay drops any window whose
context holds a reconstruction at all — promoted into `cgm_reading`, or spliced in
from `bg_infill`. The provider names those slots (`reconstructedSlots`) rather than
the replay inferring them, because the fit series is `NaN` at an ordinary sensor
gap too: testing it for `NaN` across the window cannot tell the rule's subject from
a dropout, and refusing both leaves a record with one sensor change contributing no
usable window at all. A carried-forward slot stays admissible — a model is
CONDITIONED on a dense context by design.

**And the window's ANCHOR must be measured.** `build_graph_input` anchors a masked
run on one step — the last step of the patch to its left, or the first step of the
patch to its right where there is no left one — and the pinball target, the frozen
baseline and both of the guard's terminal reads are all measured from it. A carried
value sitting there is a flat stretch that never happened being used as the
reference every residual is taken against.

## The counterfactual guard

An adapter is a few thousand parameters fitted to one patient's own weeks, and the
loss it is fitted on says nothing about whether the model still responds to
insulin. It can null that response with a rank-1 map, score better on pinball
loss, and hand the dose calculator a forecaster that does not move when insulin is
added — which is exactly the marginal response `Rails.predictedLowVeto` reads,
because that rail tests the MEDIAN line and not a band edge.

Two things stand against it, and they are independent:

- **A distillation term during the fit.** Every forecast window is replayed twice —
  once as it happened and once with one unit of RAPID INSULIN added to the horizon,
  as the action curve the preset resolves and a logged bolus stores, not as a lump
  in one bucket — and the term pins the ADAPTED difference between the two to the
  FROZEN model's. It is read on the assembled fan and in risk space, for the same
  two reasons the pinball term is, and on the median column alone: pinning the
  spreads would fight the pinball term's freedom to widen this patient's bands.
  Windows at infill and backcast geometry carry no counterfactual: the response is
  read at a horizon's terminal step, and an infill has none.
- **A verdict at attach.** After the fit, the same response is measured on held-out
  forecast windows. The RATIO is taken in risk space — the space the distillation
  term is formed in, so the guard reads the quantity the fit optimises, and a pure
  level shift through a convex `f_inv` cannot move it. The mg/dL-per-unit pair is
  measured and reported beside it, and is what the "nothing to preserve" floor
  gates on. Together they decide Pass, Blocked or Inconclusive. The verdict and every input to it
  are stored on the adapter's row, and attach is refused STRUCTURALLY — in
  `LabController.attach`, not merely in the panel — on Blocked, on Inconclusive,
  and on an adapter nobody has measured at all. An adapter arriving by import or by
  an archive restore is in that last state, and silence is not a pass.

The refusal is overridable by a deliberate second action, which sticks to that
adapter's row: a re-fit makes a fresh row with no override.

The guard measures PRESERVATION, not correctness. A model whose marginal response
is already wrong-signed passes as long as the adapter keeps that sign; exposing
that is the sensitivity probe's job and stays there.

## Backends

One exported model, two implemented backends: fp32 CPU via XNNPACK as the
reference authority, and fp16 GPU via the Vulkan delegate as a measured shadow.
Both take `(patches, attn_mask, slot_sel)` and return `(head_raw, time_logits,
hidden)`; the masked set crosses as a one-hot selection matrix, so no int64
tensor crosses the runtime boundary.
A non-authoritative backend may render a forecast, but may not feed a dose until
it has cleared the fp32-agreement gate. There is no NPU path — see
`backend/NpuBackends.kt` for what each unavailable route would need.
