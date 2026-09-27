package com.t1dm.data.meals

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.InsulinFamily
import com.t1dm.core.model.InsulinKind
import com.t1dm.core.model.InsulinType
import com.t1dm.data.T1dmRepository
import com.t1dm.data.curve.ChannelBuilder
import com.t1dm.data.curve.CurveEngine
import com.t1dm.data.db.DoseKind
import com.t1dm.data.db.InsulinTypeEntity
import com.t1dm.data.db.LoggedDoseEntity
import com.t1dm.data.db.toBlob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max

/** Logged dose carries its resolved PK curve in customCurve, reconstructs later preset changes. */
class InsulinController(
    private val repository: T1dmRepository,
    private val engine: CurveEngine,
    private val dispatchers: T1dmDispatchers,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Idempotent: inserts missing builtins and rewrites existing ones to the current catalogue. */
    suspend fun syncBuiltins() = withContext(dispatchers.io) {
        val catalog = engine.presetCatalog()
        val existing = repository.builtinInsulinTypes().associateBy { it.name }
        val ts = now()
        for ((name, label) in BUILTIN_PRESETS) {
            val spec = catalog.first { it.label == label }
            val type = InsulinType(
                id = existing[name]?.id ?: 0,
                name = name,
                kind = if (spec.family == InsulinFamily.RapidGamma) InsulinKind.BOLUS else InsulinKind.BASAL,
                durationMin = if (spec.family == InsulinFamily.RapidGamma) spec.diaBaseHours * 60.0 else spec.actionMin,
                kaPerHour = spec.kaPerHour.takeIf { spec.family == InsulinFamily.BasalBateman },
                kePerHour = spec.kePerHour.takeIf { spec.family == InsulinFamily.BasalBateman },
                builtin = true,
            )
            repository.upsertInsulinType(type.toEntity(ts))
        }
    }

    val types: Flow<List<InsulinType>> =
        repository.observeInsulinTypes().map { list -> list.map { it.toModel() } }

    /** The PK-action curve, units per 5-min; empty when it does not encode [units]. */
    suspend fun resolvePreview(type: InsulinType, units: Double): List<Double> =
        pkCurveOf(engine, type, units).takeIf { encodesDose(it, units) }.orEmpty()

    /** A dose-scaled bolus acts for its own curve's length, not the type's 5 U reference. */
    private fun actingMin(curve: List<Double>, type: InsulinType): Double =
        if (curve.isEmpty()) type.durationMin else curve.size * (CurveEngine.STEP_MS / 60_000.0)

    /** Drops a type whose 1 U curve encodes no dose. */
    suspend fun saveCustomType(type: InsulinType) {
        if (!encodesDose(pkCurveOf(engine, type, 1.0), 1.0)) return
        repository.upsertInsulinType(type.copy(builtin = false).toEntity(now()))
    }

    suspend fun deleteCustomType(id: Long) = repository.deleteCustomInsulinType(id)

    suspend fun logDose(type: InsulinType, units: Double, tsMs: Long = now()): LoggedDoseEntity =
        withContext(dispatchers.io) {
            // Round-to-nearest, not floor, lands in the SAME slot as CGM/snapToGrid writers.
            val gridTs = Math.floorDiv(tsMs + CurveEngine.STEP_MS / 2, CurveEngine.STEP_MS) * CurveEngine.STEP_MS
            val curve = pkCurveOf(engine, type, units)
            require(encodesDose(curve, units)) { "${type.name} encodes no $units U curve." }
            val tz = TimeZone.getDefault().getOffset(gridTs) / 60_000
            repository.logLoggedDose(
                LoggedDoseEntity(
                    clientId = "",
                    tsMs = gridTs,
                    kind = if (type.kind == InsulinKind.BOLUS) DoseKind.BOLUS else DoseKind.BASAL,
                    units = units,
                    durationMin = actingMin(curve, type),
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

    /** Row stores resolved PK curve, not the type; type null leaves fields, hand-drawn too. */
    suspend fun editDose(
        row: LoggedDoseEntity,
        type: InsulinType?,
        units: Double,
        tsMs: Long,
        nowMs: Long = now(),
    ): LoggedDoseEntity? = withContext(dispatchers.io) {
        val retimed = row.copy(tsMs = tsMs, units = units)
        val next = if (type == null) retimed else {
            val curve = pkCurveOf(engine, type, units)
            if (!encodesDose(curve, units)) return@withContext null
            retimed.copy(
                kind = if (type.kind == InsulinKind.BOLUS) DoseKind.BOLUS else DoseKind.BASAL,
                durationMin = actingMin(curve, type),
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
        /** Builtin type name → the catalogue label its PK is read from. */
        val BUILTIN_PRESETS: List<Pair<String, String>> = listOf(
            "Novorapid" to "Aspart · NovoRapid/Novolog",
            "Lantus" to "Glargine U100 · Lantus",
            "Tresiba" to "Degludec · Tresiba",
        )

        /** θ and duration grow with the dose, SPEC/invariants.md §5; others scale linearly. */
        fun isDoseScaled(type: InsulinType): Boolean = type.kind == InsulinKind.BOLUS &&
            type.customCurve.isNullOrEmpty() && (type.k == null || type.theta == null)
    }
}

internal suspend fun pkCurveOf(engine: CurveEngine, type: InsulinType, units: Double): List<Double> {
    val shape = type.customCurve
    val k = type.k
    val theta = type.theta
    val ka = type.kaPerHour
    val ke = type.kePerHour
    return when {
        shape != null && shape.isNotEmpty() -> {
            val tot = shape.sum()
            val scale = if (tot > 0.0) units / tot else 0.0
            shape.map { it * scale }
        }
        type.kind == InsulinKind.BOLUS && k != null && theta != null ->
            engine.gamma(units, k, theta, type.durationMin).toList()
        InsulinController.isDoseScaled(type) ->
            engine.presetCurve(units, engine.defaultPreset(InsulinFamily.RapidGamma)).toList()
        ka != null && ke != null -> engine.bateman(units, type.durationMin, ka, ke).toList()
        else -> engine.presetCurve(units, engine.defaultPreset(InsulinFamily.BasalBateman)).toList()
    }
}

/** Sums to the dose, SPEC/invariants.md §5, and fits the [ChannelBuilder.PAD_MS] lookback. */
internal fun encodesDose(curve: List<Double>, units: Double): Boolean =
    curve.isNotEmpty() && curve.size * CurveEngine.STEP_MS <= ChannelBuilder.PAD_MS &&
        curve.all { it.isFinite() && it >= 0.0 } && abs(curve.sum() - units) <= 1e-9 * max(1.0, units)
