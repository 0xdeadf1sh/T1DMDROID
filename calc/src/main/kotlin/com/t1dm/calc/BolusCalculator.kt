package com.t1dm.calc

import com.t1dm.core.model.CurveEvent
import kotlinx.coroutines.yield

/**
 * Resolves a candidate insulin dose into its announced-future [CurveEvent]s (the dose-scaled gamma
 * PK), so the calculator stays agnostic of the curve engine. In production this is backed by
 * the selected preset's `CurveEngine.rapidEvent`; in tests a fake returns a marker the fake [ForecastPort] reads.
 */
fun interface BolusResolver {
    suspend fun resolve(doseU: Double, atMs: Long): List<CurveEvent>
}

/**
 * The bounded grid-search bolus engine (Phase 4 §5). For each candidate dose it feeds the dose
 * as announced future insulin into the SELECTED fp32-authoritative model via [ForecastPort], rolls to
 * the full window, scores the fan under the configured objective, and returns the candidates ranked
 * best-first. It is pure with respect to the rails — the [DoseAdvisor] layers the fail-closed rails and
 * the freshness gate on top. Cooperatively cancellable (a `yield()` per candidate) so a fresh
 * `GridTick` or a screen exit aborts a long search promptly.
 */
class BolusCalculator(
    private val port: ForecastPort,
    private val resolver: BolusResolver,
) {
    /**
     * Roll and score every grid dose (plus the 0 U baseline). Returns the ranked candidates and the
     * separately-identified [baseline] fan (candidate = null) the [DoseAdvisor] uses for the global
     * degeneracy gate. Ineligible candidate fans are retained but score to +∞ (they can never win).
     */
    suspend fun search(
        rollStartMs: Long,
        announced: List<CurveEvent>,
        config: CalcConfig,
    ): SearchResult {
        val validated = config.horizon.validatedSteps
        val full = config.horizon.fullRollSteps

        val baseline = port.roll(
            ForecastRequest(rollStartMs, full, validated, announced, candidate = null, candidateU = 0.0),
        )

        val ranked = ArrayList<Candidate>()
        for (doseU in config.grid.doses()) {
            yield() // cancellable
            val fan = if (doseU == 0.0) {
                baseline
            } else {
                val events = resolver.resolve(doseU, rollStartMs)
                port.roll(ForecastRequest(rollStartMs, full, validated, announced, candidate = events, candidateU = doseU))
            }
            ranked.add(Candidate(doseU = doseU, score = Scoring.scoreFan(fan, config), fan = fan))
        }
        ranked.sortWith(compareBy({ it.score }, { it.doseU }))
        return SearchResult(baseline = baseline, ranked = ranked)
    }

    data class SearchResult(val baseline: PredFan, val ranked: List<Candidate>)
}
