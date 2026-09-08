package com.t1dm.calc

/** §3.6-C: enabled rail BLOCKS bad input; disabled is the only way bad input passes. */
sealed interface RailVerdict {
    data object Pass : RailVerdict

    data class Block(val rail: String, val reason: String) : RailVerdict

    data class RequireConfirm(val rail: String, val reason: String) : RailVerdict

    val blocking: Boolean get() = this is Block
    val needsConfirm: Boolean get() = this is RequireConfirm
}

object Rails {

    /** §3.6-B/-C: gates the recommendation, never disableable — a degenerate fan is unscoreable. */
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

    /** §3.6-C: MEDIAN/VALIDATED window; τ=.05 monotone forced 0U at trip; hypo+gate still apply. */
    fun predictedLowVeto(fan: PredFan, config: CalcConfig): RailVerdict {
        val name = "predicted-low"
        if (!config.rails.predictedLowVeto) return RailVerdict.Pass
        if (!fan.eligible) {
            return RailVerdict.Block(name, "Cannot verify the low risk of this dose (forecast ${fan.eligibility}) — vetoing to fail safe.")
        }
        val window = fan.validatedWindow()
        if (window.isEmpty()) {
            return RailVerdict.Block(name, "Cannot verify the low risk of this dose (no validated forecast window) — vetoing to fail safe.")
        }
        val idx = fan.firstMedianBelow(config.predictedLowThresholdMgdl)
        if (idx != null) {
            val mins = (idx.toLong() * fan.stepMs) / 60_000L
            val low = window[idx].medianBg.toInt()
            return RailVerdict.Block(name, "Predicted low: the median reaches ${low} mg/dL at +$mins min (floor ${config.predictedLowThresholdMgdl.toInt()}).")
        }
        return RailVerdict.Pass
    }

    /** §3.6-C: fail-closed; unknown IOB with nonzero dose blocks — a forgotten log under-counts. */
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

    /** §3.6-F. IOB comes from logged doses only, so a stale log silently under-counts. */
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
