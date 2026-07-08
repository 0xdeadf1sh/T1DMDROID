package com.t1dm.data.meals

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.InsulinKind
import com.t1dm.core.model.InsulinType
import com.t1dm.data.T1dmRepository
import com.t1dm.data.curve.CurveEngine
import com.t1dm.data.db.DoseKind
import com.t1dm.data.db.InsulinTypeEntity
import com.t1dm.data.db.LoggedDoseEntity
import com.t1dm.data.db.toBlob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.TimeZone

/**
 * Orchestrates the insulin builder (PLAN.private.md Phase 4, `:feature:insulin`): the seeded quick
 * presets (Novorapid gamma; Lantus/Tresiba Bateman), user-defined custom types (incl. a drawn
 * action curve), the live PK-action preview, and logging a dose. A logged dose is stored
 * self-describingly on `logged_dose`: the resolved PK curve rides in `customCurve` so it
 * reconstructs exactly — for the dose-scaled Novorapid preset as well as any custom shape.
 */
class InsulinController(
    private val repository: T1dmRepository,
    private val engine: CurveEngine,
    private val dispatchers: T1dmDispatchers,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Seed the three quick presets once (idempotent: only when no builtin exists). */
    suspend fun seedBuiltinsIfEmpty() = withContext(dispatchers.io) {
        if (repository.insulinTypeBuiltinCount() == 0) {
            val ts = now()
            repository.seedInsulinTypes(BUILTINS.map { it.toEntity(ts) })
        }
    }

    val types: Flow<List<InsulinType>> =
        repository.observeInsulinTypes().map { list -> list.map { it.toModel() } }

    /**
     * The PK-action curve (units per 5-min) for [units] of [type]. A custom drawn shape is scaled to
     * the dose; the Novorapid preset uses the dose-scaled [CurveEngine.bolusPk]; other analytic
     * types use their stored gamma/Bateman params.
     */
    suspend fun resolvePreview(type: InsulinType, units: Double): List<Double> = pkCurve(type, units)

    private suspend fun pkCurve(type: InsulinType, units: Double): List<Double> {
        val shape = type.customCurve
        return when {
            shape != null && shape.isNotEmpty() -> {
                val tot = shape.sum()
                val scale = if (tot > 0.0) units / tot else 0.0
                shape.map { it * scale }
            }
            type.kind == InsulinKind.BOLUS && type.builtin -> engine.bolusPk(units).values
            type.kind == InsulinKind.BOLUS -> engine.gamma(
                units,
                type.k ?: CurveEngine.Presets.BOLUS_GAMMA_K,
                type.theta ?: CurveEngine.Presets.BOLUS_GAMMA_THETA,
                type.durationMin,
            ).toList()
            else -> engine.bateman(
                units,
                type.durationMin,
                type.kaPerHour ?: CurveEngine.Presets.BASAL_KA_PER_HOUR,
                type.kePerHour ?: CurveEngine.Presets.BASAL_KE_PER_HOUR,
            ).toList()
        }
    }

    suspend fun saveCustomType(type: InsulinType) =
        repository.upsertInsulinType(type.copy(builtin = false).toEntity(now()))

    suspend fun deleteCustomType(id: Long) = repository.deleteCustomInsulinType(id)

    /**
     * Log [units] of [type] at [tsMs]. The resolved PK curve is persisted as the `logged_dose`
     * `customCurve` (exact reconstruction), with the nominal params kept as provenance metadata.
     */
    suspend fun logDose(type: InsulinType, units: Double, tsMs: Long = now()): Long =
        withContext(dispatchers.io) {
            val gridTs = Math.floorDiv(tsMs, CurveEngine.STEP_MS) * CurveEngine.STEP_MS
            val curve = pkCurve(type, units)
            val tz = TimeZone.getDefault().getOffset(gridTs) / 60_000
            repository.logLoggedDose(
                LoggedDoseEntity(
                    tsMs = gridTs,
                    kind = if (type.kind == InsulinKind.BOLUS) DoseKind.BOLUS else DoseKind.BASAL,
                    units = units,
                    durationMin = type.durationMin,
                    k = type.k,
                    theta = type.theta,
                    kaPerHour = type.kaPerHour,
                    kePerHour = type.kePerHour,
                    customCurve = if (curve.isEmpty()) null else curve.toBlob(),
                    tzOffsetMin = tz,
                    note = type.name,
                    updatedAt = now(),
                ),
            )
        }

    companion object {
        /** The three quick presets (PLAN.private.md Phase 4; params from `CurveEngine.Presets`). */
        val BUILTINS: List<InsulinType> = listOf(
            InsulinType(
                id = 0, name = "Novorapid", kind = InsulinKind.BOLUS,
                durationMin = CurveEngine.Presets.BOLUS_DIA_BASE_HOURS * 60.0,
                k = CurveEngine.Presets.BOLUS_GAMMA_K, theta = CurveEngine.Presets.BOLUS_GAMMA_THETA,
                builtin = true,
            ),
            InsulinType(
                id = 0, name = "Lantus", kind = InsulinKind.BASAL,
                durationMin = CurveEngine.Presets.LANTUS_DIA_MIN,
                kaPerHour = CurveEngine.Presets.BASAL_KA_PER_HOUR,
                kePerHour = CurveEngine.Presets.BASAL_KE_PER_HOUR,
                builtin = true,
            ),
            InsulinType(
                id = 0, name = "Tresiba", kind = InsulinKind.BASAL,
                durationMin = CurveEngine.Presets.TRESIBA_DIA_MIN,
                kaPerHour = CurveEngine.Presets.BASAL_KA_PER_HOUR,
                kePerHour = CurveEngine.Presets.BASAL_KE_PER_HOUR,
                builtin = true,
            ),
        )
    }
}
