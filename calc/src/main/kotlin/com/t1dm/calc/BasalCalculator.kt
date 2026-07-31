package com.t1dm.calc

import com.t1dm.core.model.BasalDoseSpec
import com.t1dm.core.model.BasalSchedule
import kotlinx.coroutines.yield

/**
 * Rolls a *candidate* day-long basal [BasalSchedule] (rather than a discrete announced bolus) to a
 * long horizon, folding the auto-extended near-flat Bateman background into the model context. The
 * real implementation drives `ChannelBuilder` with the candidate schedule substituted for the active
 * one; the fail-closed contract matches [ForecastPort] (a non-eligible fan on any bad input).
 */
fun interface BasalForecastPort {
    suspend fun roll(schedule: BasalSchedule, rollStartMs: Long, nSteps: Int, validatedSteps: Int): PredFan
}

/** A ranked basal-schedule candidate: the whole-day [schedule], its total daily units, and the score. */
data class BasalCandidate(
    val schedule: BasalSchedule,
    val totalDailyU: Double,
    val score: Double,
    val fan: PredFan,
)

/**
 * The day-long basal calculator (Phase 4 §6 — "a day-long Bateman-schedule search, run when the
 * user injects basal"). It grid-searches the daily basal total (optionally split across the
 * [template] injections), rolls each candidate over a long horizon, and scores the fasting fan under
 * the configured objective (hypo off the median). Fail-closed: a degenerate/stale/missing roll
 * scores to +∞ and can never be recommended; if no candidate is eligible the caller refuses.
 */
class BasalCalculator(private val port: BasalForecastPort) {

    /**
     * @param template the shape of the daily schedule (injection times, DIA, ka/ke) whose *doses* the
     *   search scales; each candidate keeps the times/kinetics and sets the units.
     * @param totalGrid candidate whole-day totals in units (user-set, unbounded).
     * @param rollHours the fasting horizon to roll (typically a full day between injections).
     */
    suspend fun search(
        template: BasalSchedule,
        totalGrid: List<Double>,
        rollStartMs: Long,
        rollHours: Double,
        config: CalcConfig,
    ): List<BasalCandidate> {
        require(template.doses.isNotEmpty()) { "basal template needs at least one injection slot" }
        val nSteps = (rollHours * HorizonPolicy.STEPS_PER_HOUR).toInt()
        val validated = config.horizon.validatedSteps
        val nSlots = template.doses.size

        val out = ArrayList<BasalCandidate>(totalGrid.size)
        for (total in totalGrid) {
            yield()
            val perSlot = total / nSlots
            val candidate = template.copy(
                doses = template.doses.map { it.scaledTo(perSlot) },
            )
            val fan = port.roll(candidate, rollStartMs, nSteps, validated)
            out.add(BasalCandidate(candidate, total, Scoring.scoreFan(fan, config), fan))
        }
        out.sortWith(compareBy({ it.score }, { it.totalDailyU }))
        return out
    }

    private fun BasalDoseSpec.scaledTo(units: Double): BasalDoseSpec = copy(doseU = units)
}
