package com.t1dm.calc

import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.displayName
import com.t1dm.inference.InferenceControllerDefaults
import kotlin.math.max

/** Fail-closed source of the selected model's backend/precision/agreement provenance; null ⇒ no model. */
fun interface BackendInfoSource {
    suspend fun current(): BackendInfo?
}

/**
 * The top-level advisory orchestrator (SPEC §3.6, Phase 4 §5). It sequences the whole fail-closed
 * pipeline for a bolus recommendation:
 *
 *  1. gather the §3.6-D/-E/-F inputs (anchor, IOB, backend) — any missing input fails its rail closed;
 *  2. the **freshness gate** and **fp16-agreement gate** — a stale anchor or an untrusted precision
 *     refuses the whole recommendation;
 *  3. the **grid search** ([BolusCalculator]) → the ranked fan;
 *  4. the **baseline degeneracy gate** — a degenerate/missing baseline refuses;
 *  5. the **hypo-treatment carb-rescue path** — a current or near-term predicted low withholds insulin
 *     and recommends rescue carbs instead;
 *  6. the **per-candidate rails** (predicted-low veto, IOB ceiling) filtering the grid to the best
 *     dose that clears them, falling back to 0 U when every nonzero dose is vetoed;
 *  7. the **mandatory-confirmation rail** and the **§3.6-F point-of-decision card**, gating Accept.
 *
 * Nothing here actuates: the terminal value is an [AdviceResult] the human reads and acts on manually.
 */
class DoseAdvisor(
    private val bolus: BolusCalculator,
    private val anchorSource: AnchorInfoSource,
    private val iobSource: IobSource,
    private val backendSource: BackendInfoSource,
    /** The BG causal-SavGol window the fan is built at, for the §3.6-F card. Read ONCE per
     *  recommendation and pinned onto every roll of the grid search, so the card's disclosure is true
     *  by construction; a throw degrades to the default rather than refusing (this is provenance the
     *  card DISCLOSES, not a gate it enforces). */
    private val smoothingWindowSource: suspend () -> Int = { InferenceControllerDefaults.SAVGOL_WINDOW },
) {

    suspend fun recommendBolus(
        nowMs: Long,
        announced: List<CurveEvent>,
        config: CalcConfig,
        // Opt-in override (defaults off): emit the objective argmin even off a degenerate/stale/missing
        // baseline instead of refusing. The app engages it only for its user-acknowledged total-silence
        // mode; every safety default and every test leaves it false, so the §3.6-B refusal stands.
        bypassDegeneracyGate: Boolean = false,
    ): AdviceResult {
        val anchor = anchorSource.current(nowMs)
        val iob = iobSource.snapshot(nowMs)
        val backend = backendSource.current()

        // (2a) §3.6-E fp16 agreement gate — a decision that could admit a larger dose must run on a
        // trustworthy precision. The selected model is fp32-authoritative by design (§3.2); a missing
        // or disagreeing backend fails closed.
        if (backend == null) {
            return AdviceResult.Refused(listOf("No selected model — refusing to recommend a dose."))
        }
        if (!backend.trustworthy) {
            val why = if (backend.agreementOk == false) {
                "disagrees with the fp32 CPU reference"
            } else {
                "has not passed the fp32-agreement gate"
            }
            return AdviceResult.Refused(
                listOf(
                    "Selected backend ${backend.backend} ($why) is not the fp32 CPU authority — " +
                        "withholding the dose to fail safe (the forecast may still show).",
                ),
            )
        }

        // (2b) §3.6-D freshness gate.
        val fresh = Rails.freshness(anchor, nowMs, config)
        if (fresh is RailVerdict.Block) return AdviceResult.Refused(listOf(fresh.reason))

        // (3) grid search. The BG input filter is resolved HERE, not at card-construction time: the
        // search is a cancellable foreground job the user can navigate away from, and a Settings edit
        // landing mid-search would otherwise rank fans anchored on two different `last_bg` values and
        // let the §3.6-F card assert a window its fan never saw.
        val smoothing = InferenceControllerDefaults.nearestSmoothingStop(
            runCatching { smoothingWindowSource() }.getOrNull() ?: InferenceControllerDefaults.SAVGOL_WINDOW,
        )
        val result = bolus.search(rollStartMs = nowMs, announced = announced, config = config, smoothingWindow = smoothing)

        // (4) baseline degeneracy gate.
        val degen = Rails.baselineDegeneracy(result.baseline)
        if (!bypassDegeneracyGate && degen is RailVerdict.Block) return AdviceResult.Refused(listOf(degen.reason))

        val notes = ArrayList<String>()

        // Non-blocking informational note (§3.6-E): the dose is ALWAYS computed on the fp32 CPU
        // authority, but the switcher may be rendering the DISPLAYED forecast on a GPU/NPU backend.
        // Tell the user so — never a warning, never a refusal; the recommendation proceeds unchanged.
        val displayed = backend.displayedBackend
        if (displayed != null && displayed != backend.backend) {
            notes.add(
                "Displayed forecast is rendered by ${displayed.displayName()}; this dose was computed on " +
                    "the fp32 CPU authority (${backend.backend.displayName()}).",
            )
        }

        // (5) hypo-treatment carb-rescue path — withhold insulin, recommend carbs.
        if (config.rails.hypoTreatment && inHypoTerritory(anchor, result.baseline, config)) {
            val grams = rescueCarbs(anchor, iob, config)
            notes.add("Hypo-treatment path: current/near-term BG is low — withholding insulin, recommend ~${grams.toInt()} g fast carbs.")
            val zero = result.ranked.firstOrNull { it.doseU == 0.0 } ?: Candidate(0.0, 0.0, result.baseline)
            return AdviceResult.Recommended(
                best = zero,
                ranked = result.ranked,
                card = buildCard(anchor, iob, backend, zero, nowMs, false, emptyList(), config, smoothing),
                railNotes = notes,
                requiresConfirmation = true, // any hypo recommendation must be acknowledged
                rescueCarbsG = grams,
            )
        }

        // (6) per-candidate rails: pick the best-scoring finite candidate clearing the BLOCK rails.
        val zeroCandidate = result.ranked.firstOrNull { it.doseU == 0.0 } ?: Candidate(0.0, 0.0, result.baseline)
        var chosen: Candidate? = null
        for (c in result.ranked) {
            if (c.score == Double.POSITIVE_INFINITY) continue
            val veto = Rails.predictedLowVeto(c.fan, config)
            val ceiling = Rails.iobCeiling(iob, c.doseU, config)
            if (veto is RailVerdict.Block) { if (c.doseU > 0.0) notes.add("Skipped ${fmt(c.doseU)} U — ${veto.reason}"); continue }
            if (ceiling is RailVerdict.Block) { notes.add("Skipped ${fmt(c.doseU)} U — ${ceiling.reason}"); continue }
            chosen = c
            break
        }
        // Fall back to 0 U (never dosing is the safe floor) when every nonzero dose is blocked.
        if (chosen == null) {
            notes.add("Every nonzero dose was vetoed by a fail-closed rail — recommending 0 U.")
            chosen = zeroCandidate
        }

        // (7) mandatory-confirmation rail + card.
        val confirm = Rails.mandatoryConfirmation(iob, chosen.doseU, nowMs, config)
        val confirmReasons = ArrayList<String>()
        if (confirm is RailVerdict.RequireConfirm) { confirmReasons.add(confirm.reason); notes.add(confirm.reason) }
        val requiresConfirmation = confirmReasons.isNotEmpty()

        val card = buildCard(anchor, iob, backend, chosen, nowMs, requiresConfirmation, confirmReasons, config, smoothing)
        return AdviceResult.Recommended(
            best = chosen,
            ranked = result.ranked,
            card = card,
            railNotes = notes,
            requiresConfirmation = requiresConfirmation,
        )
    }

    private fun inHypoTerritory(anchor: AnchorInfo?, baseline: PredFan, config: CalcConfig): Boolean {
        val nowLow = anchor?.currentBgMgdl?.let { it < config.hypoNowThresholdMgdl } ?: false
        // Near-term = the validated window; a low predicted there is treated as present-tense hypo.
        // Off the MEDIAN, like every other dose-path read of the fan: a band edge dipping under the
        // threshold means a low is possible, which diverted almost every session to carb rescue.
        val nearLow = baseline.eligible && baseline.validatedWindow()
            .any { it.medianBg < config.hypoNowThresholdMgdl }
        return nowLow || nearLow
    }

    private fun rescueCarbs(anchor: AnchorInfo?, iob: IobSnapshot?, config: CalcConfig): Double {
        val current = anchor?.currentBgMgdl ?: config.target.lowMgdl
        val liftNeeded = max(config.rescueTargetLiftMgdl, config.target.targetMgdl - current)
        val grams = liftNeeded / config.carbSensitivityMgdlPerG
        // Discount carbs already on board (COB) so a rescue isn't double-counted.
        return max(0.0, grams - (iob?.cobG ?: 0.0))
    }

    private fun buildCard(
        anchor: AnchorInfo?,
        iob: IobSnapshot?,
        backend: BackendInfo,
        chosen: Candidate,
        nowMs: Long,
        requiresConfirmation: Boolean,
        confirmReasons: List<String>,
        config: CalcConfig,
        smoothing: Int,
    ): DecisionCard {
        val bandWidth = chosen.fan.steps.getOrNull(config.horizon.validatedSteps - 1)?.bandWidth
            ?: chosen.fan.steps.lastOrNull()?.bandWidth
        return DecisionCard(
            ageOfLastRealReadingMin = anchor?.ageMs(nowMs)?.let { it / 60_000L },
            interpolatedFraction = anchor?.interpolatedFraction ?: 1.0,
            warmup = anchor?.warmup ?: false,
            backend = backend.backend,
            precision = backend.precision,
            agreementOk = backend.agreementOk,
            assumedIobU = iob?.iobU,
            minSinceLastLoggedDose = iob?.minSinceLastDose(nowMs),
            bandWidthMgdl = bandWidth,
            smoothingWindow = smoothing,
            requiresConfirmation = requiresConfirmation,
            confirmationReasons = confirmReasons,
        )
    }

    private fun fmt(u: Double): String = ((u * 100).toLong() / 100.0).toString()
}
