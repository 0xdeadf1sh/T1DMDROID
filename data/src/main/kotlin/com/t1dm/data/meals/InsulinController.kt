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

/** A logged dose carries its resolved PK curve in `logged_dose.customCurve`, so it reconstructs
 *  exactly whatever the presets become later. */
class InsulinController(
    private val repository: T1dmRepository,
    private val engine: CurveEngine,
    private val dispatchers: T1dmDispatchers,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun seedBuiltinsIfEmpty() = withContext(dispatchers.io) {
        if (repository.insulinTypeBuiltinCount() == 0) {
            val ts = now()
            repository.seedInsulinTypes(BUILTINS.map { it.toEntity(ts) })
        }
    }

    val types: Flow<List<InsulinType>> =
        repository.observeInsulinTypes().map { list -> list.map { it.toModel() } }

    /** The PK-action curve, units per 5-min. */
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

    suspend fun logDose(type: InsulinType, units: Double, tsMs: Long = now()): LoggedDoseEntity =
        withContext(dispatchers.io) {
            // Round-to-nearest, not floor, so this lands in the SAME slot as the CGM/single-dose
            // writers (`repository.snapToGrid`, `GridStamper.snap`); a floor snap misaligns the
            // insulin-action channel against BG by up to one step.
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

    /**
     * A row stores its resolved PK curve, not the type it came from, so a retype must rewrite every
     * PK field and the note that names the insulin. [type] null leaves all of them, and with them a
     * hand-drawn curve, as stored. [tsMs] is snapped by the repository.
     */
    suspend fun editDose(
        row: LoggedDoseEntity,
        type: InsulinType?,
        units: Double,
        tsMs: Long,
        nowMs: Long = now(),
    ): LoggedDoseEntity? = withContext(dispatchers.io) {
        val retimed = row.copy(tsMs = tsMs, units = units)
        val next = if (type == null) retimed else {
            val curve = pkCurve(type, units)
            retimed.copy(
                kind = if (type.kind == InsulinKind.BOLUS) DoseKind.BOLUS else DoseKind.BASAL,
                durationMin = type.durationMin,
                k = type.k,
                theta = type.theta,
                kaPerHour = type.kaPerHour,
                kePerHour = type.kePerHour,
                customCurve = if (curve.isEmpty()) null else curve.toBlob(),
                note = type.name,
            )
        }
        repository.editLoggedDose(next, nowMs)
    }

    companion object {
        val BUILTINS: List<InsulinType> = listOf(
            InsulinType(
                id = 0, name = "Novorapid", kind = InsulinKind.BOLUS,
                durationMin = 360.0, // NovoRapid DIA 6 h (Loop rapidActingAdult); no gamma params ⇒ exp-action
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
