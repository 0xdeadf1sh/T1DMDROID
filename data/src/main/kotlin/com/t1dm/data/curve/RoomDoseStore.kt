package com.t1dm.data.curve

import com.t1dm.core.model.BasalDoseSpec
import com.t1dm.core.model.BasalSchedule
import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.CurveKind
import com.t1dm.data.db.BasalScheduleDao
import com.t1dm.data.db.DoseKind
import com.t1dm.data.db.BasalScheduleEntity
import com.t1dm.data.db.LoggedDoseDao
import com.t1dm.data.db.LoggedDoseEntity
import com.t1dm.data.db.LoggedMealDao
import com.t1dm.data.db.LoggedMealEntity
import com.t1dm.data.db.toDoubleList

/**
 * The Room-backed [DoseStore] (PLAN.private.md §3.3): resolves `logged_dose` / `logged_meal`
 * rows into [CurveEvent]s via the [CurveEngine], and reads the active `basal_schedule`. Each
 * row is self-describing (it carries its own curve params), so reconstruction is stable across
 * preset changes; a meal's optional `customCurve` BLOB overrides the gamma. Window queries use
 * the DAO `tsMs BETWEEN` reads — the [ChannelBuilder] pads the window back far enough to catch
 * any still-acting tail.
 */
class RoomDoseStore(
    private val engine: CurveEngine,
    private val loggedDoses: LoggedDoseDao,
    private val loggedMeals: LoggedMealDao,
    private val basalSchedules: BasalScheduleDao,
) : DoseStore {

    override suspend fun carbEvents(fromMs: Long, toMs: Long): List<CurveEvent> =
        loggedMeals.inRange(fromMs, toMs).map { it.toCurveEvent() }

    override suspend fun insulinEvents(fromMs: Long, toMs: Long): List<CurveEvent> =
        loggedDoses.inRange(fromMs, toMs).map { it.toCurveEvent() }

    override suspend fun activeBasalSchedule(): BasalSchedule? {
        val rows = basalSchedules.activeDoses()
        if (rows.isEmpty()) return null
        return rows.toSchedule()
    }

    private suspend fun LoggedMealEntity.toCurveEvent(): CurveEvent {
        // A user-drawn appearance curve overrides the GI-derived gamma (food-builder path).
        val values = customCurve?.toDoubleList()
            ?: engine.gamma(
                grams,
                k ?: CurveEngine.Presets.carbGammaForGi(gi ?: DEFAULT_GI).first,
                theta ?: CurveEngine.Presets.carbGammaForGi(gi ?: DEFAULT_GI).second,
                durationMin,
            ).asList()
        return CurveEvent(tsMs, CurveEngine.STEP_MS, CurveKind.CARB, grams, values)
    }

    private suspend fun LoggedDoseEntity.toCurveEvent(): CurveEvent {
        // A user-drawn action curve overrides the analytic gamma/Bateman (custom insulin type),
        // mirroring the meal path.
        val values = customCurve?.toDoubleList()
            ?: when (kind) {
                DoseKind.BOLUS -> engine.gamma(
                    units,
                    k ?: CurveEngine.Presets.BOLUS_GAMMA_K,
                    theta ?: CurveEngine.Presets.BOLUS_GAMMA_THETA,
                    durationMin,
                ).asList()
                DoseKind.BASAL -> engine.bateman(
                    units,
                    durationMin,
                    kaPerHour ?: CurveEngine.Presets.BASAL_KA_PER_HOUR,
                    kePerHour ?: CurveEngine.Presets.BASAL_KE_PER_HOUR,
                ).asList()
            }
        return CurveEvent(tsMs, CurveEngine.STEP_MS, CurveKind.INSULIN, units, values)
    }

    private fun List<BasalScheduleEntity>.toSchedule(): BasalSchedule = BasalSchedule(
        tzOffsetMin = first().tzOffsetMin,
        doses = map {
            BasalDoseSpec(
                timeOfDayMin = it.timeOfDayMin,
                doseU = it.doseU,
                durationMin = it.durationMin,
                kaPerHour = it.kaPerHour,
                kePerHour = it.kePerHour,
            )
        },
    )

    private companion object {
        /** Fallback GI when a meal carries neither explicit gamma params nor a GI (medium-GI). */
        const val DEFAULT_GI: Double = 50.0
    }
}
