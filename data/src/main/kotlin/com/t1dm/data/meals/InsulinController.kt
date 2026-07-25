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
 * Orchestrates the insulin builder (Phase 4, `:feature:insulin`): the seeded quick
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
     * the dose; a bolus with no stored gamma params uses the exponential action model; other analytic
     * types use their stored gamma/Bateman params.
     */
    suspend fun resolvePreview(type: InsulinType, units: Double): List<Double> = pkCurve(type, units)

    private suspend fun pkCurve(type: InsulinType, units: Double): List<Double> {
        val shape = type.customCurve
        val k = type.k
        val theta = type.theta
        return when {
            shape != null && shape.isNotEmpty() -> {
                val tot = shape.sum()
                val scale = if (tot > 0.0) units / tot else 0.0
                shape.map { it * scale }
            }
            type.kind == InsulinKind.BOLUS && k != null && theta != null ->
                engine.gamma(units, k, theta, type.durationMin).toList()
            type.kind == InsulinKind.BOLUS ->
                engine.expAction(units, minOf(75.0, type.durationMin * 0.4), type.durationMin).toList()
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
    suspend fun logDose(type: InsulinType, units: Double, tsMs: Long = now()): LoggedDoseEntity =
        withContext(dispatchers.io) {
            // Round-to-nearest (not floor) so this dose lands in the SAME grid slot the round-to-nearest
            // CGM/single-dose writers use (repository.snapToGrid, GridStamper.snap) — a floor snap
            // misaligned the insulin-action channel vs BG by up to one 5-min step (§4-#1 grid invariant).
            val gridTs = Math.floorDiv(tsMs + CurveEngine.STEP_MS / 2, CurveEngine.STEP_MS) * CurveEngine.STEP_MS
            val curve = pkCurve(type, units)
            val tz = TimeZone.getDefault().getOffset(gridTs) / 60_000
            repository.logLoggedDose(
                LoggedDoseEntity(
                    clientId = "",
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
        /** The three quick presets (Phase 4; params from `CurveEngine.Presets`). */
        val BUILTINS: List<InsulinType> = listOf(
            InsulinType(
                id = 0, name = "Novorapid", kind = InsulinKind.BOLUS,
                durationMin = 360.0, // NovoRapid DIA 6 h (Loop rapidActingAdult); exp-action, no gamma params
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
