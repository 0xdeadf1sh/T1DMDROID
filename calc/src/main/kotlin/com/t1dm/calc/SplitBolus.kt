package com.t1dm.calc

import com.t1dm.core.model.CurveEvent
import kotlinx.coroutines.yield

/** Every part sums back to the total: a split changes the timing, never how much insulin. */
class SplitBolusSearch(
    private val port: ForecastPort,
    private val resolver: BolusResolver,
) {
    /** Best-first. Empty when splitting is disabled or [totalU] is 0. */
    suspend fun search(
        rollStartMs: Long,
        totalU: Double,
        announced: List<CurveEvent>,
        config: CalcConfig,
    ): List<Candidate> {
        val spec = config.split
        if (!spec.enabled || totalU <= 0.0 || spec.maxParts < 2) return emptyList()
        val validated = config.horizon.validatedSteps
        val full = config.horizon.fullRollSteps

        val out = ArrayList<Candidate>()
        // Only 2-part splits are modelled, whatever [SplitSpec.maxParts] allows.
        for (frac in spec.firstFractionGrid) {
            for (gap in spec.gapGridMin) {
                yield()
                val firstU = totalU * frac
                val secondU = totalU - firstU
                if (firstU <= 0.0 || secondU <= 0.0) continue
                val gapMs = gap.toLong() * 60_000L
                val events = resolver.resolve(firstU, rollStartMs) + resolver.resolve(secondU, rollStartMs + gapMs)
                val fan = port.roll(
                    ForecastRequest(rollStartMs, full, validated, announced, candidate = events, candidateU = totalU),
                )
                out.add(
                    Candidate(
                        doseU = totalU,
                        score = Scoring.scoreFan(fan, config),
                        fan = fan,
                        splits = listOf(SplitPart(firstU, 0), SplitPart(secondU, gap)),
                    ),
                )
            }
        }
        out.sortWith(compareBy({ it.score }, { it.splits?.size ?: 0 }))
        return out
    }
}
