package com.t1dm.calc

import com.t1dm.core.model.BasalDoseSpec
import com.t1dm.core.model.BasalSchedule
import kotlinx.coroutines.yield

/** Fail-closed like [ForecastPort]: a non-eligible fan on any bad input, never a throw. */
fun interface BasalForecastPort {
    suspend fun roll(schedule: BasalSchedule, rollStartMs: Long, nSteps: Int, validatedSteps: Int): PredFan
}

data class BasalCandidate(
    val schedule: BasalSchedule,
    val totalDailyU: Double,
    val score: Double,
    val fan: PredFan,
)

/** Fail-closed: an ineligible roll scores +∞ and can never be recommended. */
class BasalCalculator(private val port: BasalForecastPort) {

    /** [template] gives times/kinetics, only doses scale; [totalGrid] is whole-day units. */
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
