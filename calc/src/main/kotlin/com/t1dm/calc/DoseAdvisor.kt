package com.t1dm.calc

import com.t1dm.core.model.CurveEvent
import com.t1dm.inference.InferenceControllerDefaults
import kotlin.math.max

/** null ⇒ no selected model. */
fun interface BackendInfoSource {
    suspend fun current(): BackendInfo?
}

/** Sequences the §3.6 fail-closed bolus pipeline; terminal value is advice, nothing actuates. */
class DoseAdvisor(
    private val bolus: BolusCalculator,
    private val anchorSource: AnchorInfoSource,
    private val iobSource: IobSource,
    private val backendSource: BackendInfoSource,
    /** Read once, pinned onto every roll; a throw degrades to default (disclosed, not a gate). */
    private val smoothingWindowSource: suspend () -> Int = { InferenceControllerDefaults.SAVGOL_WINDOW },
) {

    suspend fun recommendBolus(
        nowMs: Long,
        announced: List<CurveEvent>,
        config: CalcConfig,
        // Off by default; the app engages it only for its user-acknowledged total-silence mode.
        bypassDegeneracyGate: Boolean = false,
    ): AdviceResult {
        val anchor = anchorSource.current(nowMs)
        val iob = iobSource.snapshot(nowMs)
        val backend = backendSource.current()

        // §3.6-E: a missing or disagreeing backend fails closed.
        if (backend == null) {
            return AdviceResult.Refused(listOf("No selected model — refusing to recommend a dose."))
        }
        if (!backend.trustworthy) {
            return AdviceResult.Refused(
                listOf(
                    "Selected backend ${backend.backend} is not the fp32 CPU authority — " +
                        "withholding the dose to fail safe (the forecast may still show).",
                ),
            )
        }

        // Resolved here, not at card time (a mid-search edit would split last_bg across fans).
        val smoothing = InferenceControllerDefaults.nearestSmoothingStop(
            runCatching { smoothingWindowSource() }.getOrNull() ?: InferenceControllerDefaults.SAVGOL_WINDOW,
        )
        val result = bolus.search(rollStartMs = nowMs, announced = announced, config = config, smoothingWindow = smoothing)

        val degen = Rails.baselineDegeneracy(result.baseline)
        if (!bypassDegeneracyGate && degen is RailVerdict.Block) return AdviceResult.Refused(listOf(degen.reason))

        val notes = ArrayList<String>()

        if (config.rails.hypoTreatment && inHypoTerritory(anchor, result.baseline, config)) {
            val grams = rescueCarbs(anchor, iob, config)
            notes.add("Hypo-treatment path: current/near-term BG is low — withholding insulin, recommend ~${grams.toInt()} g fast carbs.")
            val zero = result.ranked.firstOrNull { it.doseU == 0.0 } ?: Candidate(0.0, 0.0, result.baseline)
            return AdviceResult.Recommended(
                best = zero,
                ranked = result.ranked,
                card = buildCard(anchor, iob, backend, zero, nowMs, false, emptyList(), config, smoothing),
                railNotes = notes,
                requiresConfirmation = true, // a hypo recommendation is always acknowledged
                rescueCarbsG = grams,
            )
        }

        val zeroCandidate = result.ranked.firstOrNull { it.doseU == 0.0 } ?: Candidate(0.0, 0.0, result.baseline)
        var chosen: Candidate? = null
        for (c in result.ranked) {
            if (chosen != null) break
            if (c.score == Double.POSITIVE_INFINITY) continue
            val veto = Rails.predictedLowVeto(c.fan, config)
            val ceiling = Rails.iobCeiling(iob, c.doseU, config)
            if (veto is RailVerdict.Block) { if (c.doseU > 0.0) notes.add("Skipped ${fmt(c.doseU)} U — ${veto.reason}"); continue }
            if (ceiling is RailVerdict.Block) { notes.add("Skipped ${fmt(c.doseU)} U — ${ceiling.reason}"); continue }
            chosen = c
            break
        }
        if (chosen == null) {
            notes.add("Every nonzero dose was vetoed by a fail-closed rail — recommending 0 U.")
            chosen = zeroCandidate
        }

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
        // Off the median, like every dose-path read: a band edge nearly always dips into rescue.
        val nearLow = baseline.eligible && baseline.validatedWindow()
            .any { it.medianBg < config.hypoNowThresholdMgdl }
        return nowLow || nearLow
    }

    private fun rescueCarbs(anchor: AnchorInfo?, iob: IobSnapshot?, config: CalcConfig): Double {
        val current = anchor?.currentBgMgdl ?: config.target.lowMgdl
        val liftNeeded = max(config.rescueTargetLiftMgdl, config.target.targetMgdl - current)
        val grams = liftNeeded / config.carbSensitivityMgdlPerG
        // Discount COB so a rescue isn't double-counted.
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
