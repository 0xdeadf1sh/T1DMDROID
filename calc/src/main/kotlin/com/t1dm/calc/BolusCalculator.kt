package com.t1dm.calc

import com.t1dm.core.model.CurveEvent
import kotlinx.coroutines.yield

fun interface BolusResolver {
    suspend fun resolve(doseU: Double, atMs: Long): List<CurveEvent>
}

/** No rails here; [DoseAdvisor] layers those on top. */
class BolusCalculator(
    private val port: ForecastPort,
    private val resolver: BolusResolver,
) {
    /** Ineligible candidate fans are kept but score to +∞, so they can never win. */
    suspend fun search(
        rollStartMs: Long,
        announced: List<CurveEvent>,
        config: CalcConfig,
        /** Pins one BG input filter across the whole grid; null lets the port resolve its own. */
        smoothingWindow: Int? = null,
    ): SearchResult {
        val validated = config.horizon.validatedSteps
        val full = config.horizon.fullRollSteps

        val baseline = port.roll(
            ForecastRequest(rollStartMs, full, validated, announced, candidate = null, candidateU = 0.0, smoothingWindow = smoothingWindow),
        )

        val ranked = ArrayList<Candidate>()
        for (doseU in config.grid.doses()) {
            yield()
            val fan = if (doseU == 0.0) {
                baseline
            } else {
                val events = resolver.resolve(doseU, rollStartMs)
                port.roll(
                    ForecastRequest(rollStartMs, full, validated, announced, candidate = events, candidateU = doseU, smoothingWindow = smoothingWindow),
                )
            }
            ranked.add(Candidate(doseU = doseU, score = Scoring.scoreFan(fan, config), fan = fan))
        }
        ranked.sortWith(compareBy({ it.score }, { it.doseU }))
        return SearchResult(baseline = baseline, ranked = ranked)
    }

    data class SearchResult(val baseline: PredFan, val ranked: List<Candidate>)
}
