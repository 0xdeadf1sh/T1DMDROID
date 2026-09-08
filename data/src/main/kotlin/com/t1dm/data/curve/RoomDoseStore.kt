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

/** Each row carries own curve params (stable across preset changes); window reads key on tsMs. */
class RoomDoseStore(
    private val engine: CurveEngine,
    private val loggedDoses: LoggedDoseDao,
    private val loggedMeals: LoggedMealDao,
    private val basalSchedules: BasalScheduleDao,
) : DoseStore {

    override suspend fun carbEvents(fromMs: Long, toMs: Long): List<CurveEvent> =
        loggedMeals.inRange(fromMs, toMs).map { it.toCurveEvent() }

    override suspend fun insulinEvents(fromMs: Long, toMs: Long): List<CurveEvent> =
        loggedDoses.inRange(fromMs, toMs).filter { it.kind == DoseKind.BOLUS }.map { it.toCurveEvent() }

    override suspend fun basalInjectionEvents(fromMs: Long, toMs: Long): List<CurveEvent> =
        loggedDoses.inRange(fromMs, toMs).filter { it.kind == DoseKind.BASAL }.map { it.toCurveEvent() }

    /** One window read for both halves; filter keeps order, so each half matches its call. */
    override suspend fun insulinAndBasalInjectionEvents(
        fromMs: Long,
        toMs: Long,
    ): Pair<List<CurveEvent>, List<CurveEvent>> {
        val rows = loggedDoses.inRange(fromMs, toMs)
        return rows.filter { it.kind == DoseKind.BOLUS }.map { it.toCurveEvent() } to
            rows.filter { it.kind == DoseKind.BASAL }.map { it.toCurveEvent() }
    }

    override suspend fun activeBasalSchedule(): BasalSchedule? {
        val rows = basalSchedules.activeDoses()
        if (rows.isEmpty()) return null
        return rows.toSchedule()
    }

    private suspend fun LoggedMealEntity.toCurveEvent(): CurveEvent {
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
        val values = customCurve?.toDoubleList()
            ?: when (kind) {
                DoseKind.BOLUS -> if (k != null && theta != null) {
                    engine.gamma(units, k, theta, durationMin).asList()
                } else {
                    // Fallback: clinical bolus rides in customCurve (NovoRapid-shaped).
                    engine.expAction(units, minOf(75.0, durationMin * 0.4), durationMin).asList()
                }
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
        /** Medium GI. */
        const val DEFAULT_GI: Double = 50.0
    }
}
