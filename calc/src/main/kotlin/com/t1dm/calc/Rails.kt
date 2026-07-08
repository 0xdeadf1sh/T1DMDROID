package com.t1dm.calc

/**
 * The fail-closed guard-rails (PLAN §3.6-C). Every rail is **structurally fail-closed**: on missing,
 * degenerate, stale, or collapsed input an *enabled* rail BLOCKS (or forces confirmation) rather than
 * silently passing the dose — "a rail that reads as protection but passes the dose is worse than no
 * rail". A *disabled* rail is an explicit no-op (the user's unbounded-thresholds lock), and that is the
 * only way a rail lets bad input through.
 *
 * Every verdict carries a plain-language WHY (the general human-readable-messages rule).
 */
sealed interface RailVerdict {
    /** The rail is satisfied (or disabled). */
    data object Pass : RailVerdict

    /** The rail forbids this dose; [reason] states why in plain language. */
    data class Block(val rail: String, val reason: String) : RailVerdict

    /** The dose may proceed only behind an explicit human acknowledgement of [reason]. */
    data class RequireConfirm(val rail: String, val reason: String) : RailVerdict

    val blocking: Boolean get() = this is Block
    val needsConfirm: Boolean get() = this is RequireConfirm
}

/**
 * The individual rails. Global rails ([freshness], [baselineDegeneracy]) gate the *whole*
 * recommendation and refuse it outright when they block; per-candidate rails ([predictedLowVeto],
 * [iobCeiling]) filter the grid; [mandatoryConfirmation] annotates the chosen candidate.
 */
object Rails {

    /**
     * §3.6-D CGM freshness gate. Refuses any recommendation off a stale or over-interpolated anchor.
     * Fail-closed order: no anchor → no MEASURED reading → warm-up → too-old → too-interpolated.
     */
    fun freshness(anchor: AnchorInfo?, nowMs: Long, config: CalcConfig): RailVerdict {
        val name = "freshness"
        if (!config.rails.freshnessGate) return RailVerdict.Pass
        if (anchor == null) {
            return RailVerdict.Block(name, "No CGM signal — refusing to dose without a current reading.")
        }
        val lastMeasured = anchor.lastMeasuredTsMs
            ?: return RailVerdict.Block(name, "No MEASURED reading yet — refusing to dose off an interpolated or warm-up value.")
        if (anchor.warmup) {
            return RailVerdict.Block(name, "Sensor is still warming up — refusing to dose off a warm-up value.")
        }
        val ageMs = nowMs - lastMeasured
        if (ageMs > config.freshnessMaxAgeMs) {
            val mins = ageMs / 60_000L
            val limit = config.freshnessMaxAgeMs / 60_000L
            return RailVerdict.Block(name, "Last real BG is $mins min old (limit $limit min) — refusing to dose off a stale anchor.")
        }
        if (anchor.interpolatedFraction > config.maxInterpolatedFraction) {
            val pct = (anchor.interpolatedFraction * 100).toInt()
            val limit = (config.maxInterpolatedFraction * 100).toInt()
            return RailVerdict.Block(name, "$pct% of the recent context is interpolated/warm-up (limit $limit%) — refusing to dose off a mostly-fabricated anchor.")
        }
        return RailVerdict.Pass
    }

    /**
     * §3.6-B/-C baseline degeneracy gate. The do-nothing (candidate = null) fan is the reference the
     * whole search stands on; if the model cannot produce a trustworthy baseline the recommendation is
     * refused. Never disableable — a degenerate fan is unscoreable.
     */
    fun baselineDegeneracy(baseline: PredFan): RailVerdict {
        val name = "degeneracy"
        return when (baseline.eligibility) {
            ForecastEligibility.ELIGIBLE -> RailVerdict.Pass
            ForecastEligibility.MISSING ->
                RailVerdict.Block(name, "No forecast available (no selected model / context) — refusing to recommend a dose.")
            ForecastEligibility.STALE ->
                RailVerdict.Block(name, "Forecast is stale (anchor too old) — refusing to recommend a dose.")
            ForecastEligibility.DEGENERATE ->
                RailVerdict.Block(name, "Forecast is degenerate (${baseline.worstStatus}) — refusing to recommend a dose.")
        }
    }

    /**
     * §3.6-C predicted-low veto. Blocks a candidate whose lower band dips below the threshold anywhere
     * in the full roll. Fail-closed: an ineligible candidate fan is treated as a veto, not a pass.
     */
    fun predictedLowVeto(fan: PredFan, config: CalcConfig): RailVerdict {
        val name = "predicted-low"
        if (!config.rails.predictedLowVeto) return RailVerdict.Pass
        if (!fan.eligible) {
            return RailVerdict.Block(name, "Cannot verify the low risk of this dose (forecast ${fan.eligibility}) — vetoing to fail safe.")
        }
        val idx = fan.firstLowerBelow(config.predictedLowThresholdMgdl)
        if (idx != null) {
            val mins = (idx.toLong() * fan.stepMs) / 60_000L
            val low = fan.steps[idx].lowerBg.toInt()
            return RailVerdict.Block(name, "Predicted low: the lower band reaches ${low} mg/dL at +$mins min (floor ${config.predictedLowThresholdMgdl.toInt()}).")
        }
        return RailVerdict.Pass
    }

    /**
     * §3.6-C IOB ceiling. Blocks when assumed IOB + candidate dose exceeds the ceiling. Fail-closed:
     * an unknown IOB with a nonzero dose blocks (a forgotten log otherwise under-counts active insulin).
     */
    fun iobCeiling(iob: IobSnapshot?, candidateU: Double, config: CalcConfig): RailVerdict {
        val name = "iob-ceiling"
        if (!config.rails.iobCeiling) return RailVerdict.Pass
        if (candidateU <= 0.0) return RailVerdict.Pass
        val iobU = iob?.iobU
            ?: return RailVerdict.Block(name, "Active insulin (IOB) is unknown — dose log unavailable — blocking a nonzero dose to fail safe.")
        val total = iobU + candidateU
        if (total > config.iobCeilingU) {
            return RailVerdict.Block(
                name,
                "IOB ${fmt(iobU)} U + dose ${fmt(candidateU)} U = ${fmt(total)} U exceeds the ceiling ${fmt(config.iobCeilingU)} U.",
            )
        }
        return RailVerdict.Pass
    }

    /**
     * §3.6-F mandatory confirmation. A nonzero recommendation combined with a long gap since the last
     * logged dose (or no logged dose at all) is a mandatory-confirmation trigger — the IOB is computed
     * from logged doses only, so a stale log silently under-counts active insulin.
     */
    fun mandatoryConfirmation(iob: IobSnapshot?, candidateU: Double, nowMs: Long, config: CalcConfig): RailVerdict {
        val name = "log-gap"
        if (!config.rails.mandatoryConfirmation) return RailVerdict.Pass
        if (candidateU <= 0.0) return RailVerdict.Pass
        val lastTs = iob?.lastLoggedDoseTsMs
        if (lastTs == null) {
            return RailVerdict.RequireConfirm(name, "No dose has been logged — IOB is assumed 0. Confirm you have not taken recent insulin.")
        }
        val gap = nowMs - lastTs
        if (gap > config.longGapSinceLogMs) {
            val mins = gap / 60_000L
            return RailVerdict.RequireConfirm(name, "Last logged dose was $mins min ago — IOB may under-count. Confirm no unlogged insulin is active.")
        }
        return RailVerdict.Pass
    }

    private fun fmt(u: Double): String = ((u * 100).toLong() / 100.0).toString()
}
