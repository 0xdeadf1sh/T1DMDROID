package com.t1dm.sync

import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.SamplePatch
import com.t1dm.data.db.DoseKind
import com.t1dm.data.db.LoggedDoseEntity
import com.t1dm.data.db.LoggedMealEntity
import com.t1dm.data.db.SampleEntity
import com.t1dm.data.db.toBlob
import com.t1dm.data.db.toDoubleList

/** Wire ⇄ local mappings for the `/v1` contract; the fan transpose and the curve-BLOB codec are the subtle steps. */

/**
 * [ModelPrediction] → the `PUT /v1/predictions` write shape. `line` is the median; `fan` is the
 * `nQuantiles × H` matrix in ascending-τ order (row 3 == line). The model carries the fan as
 * [ModelPrediction.bandsMgdl] step-major/τ-minor (`i = s·nQ + q`), so it transposes to quantile-
 * major rows here. `made_at` is the cycle grid ts and `updated_at` the phone wall clock, both stored
 * verbatim server-side. The circadian belief rides as a nested [CircadianDto] carrying the time
 * head's `probs`/`predicted_hour`/`resultant_r`/`n_bins`/`bin_hours` losslessly, or `null` when the
 * model produced no time head (never a zeroed vector).
 */
fun ModelPrediction.toWrite(cycleTsMs: Long, nowMs: Long): PredictionWriteDto {
    val h = medianBg.size
    val nq = nQuantiles
    val fan = List(nq) { q -> List(h) { s -> bandsMgdl[s * nq + q] } }
    return PredictionWriteDto(
        made_at = cycleTsMs,
        model_id = modelId,
        updated_at = nowMs,
        horizon_steps = h,
        line = medianBg,
        fan = fan,
        circadian = predictedTime?.let {
            CircadianDto(it.probs, it.predictedHour, it.resultantR, it.nBins, it.binHours)
        },
    )
}

/**
 * A wide local row → `POST /v1/ingest` bundle (integer series widened to the wire's floats). Only
 * the six demoted scalars travel here (`bg,hr,steps,sleep,exercise,mood`); carbs/bolus/basal are no
 * longer sample columns — a meal/dose is a self-describing curve event pushed separately via
 * `PUT /v1/meals` / `PUT /v1/doses`. `updated_at` is the phone clock, carried verbatim so the
 * server's COALESCE upsert applies a genuine edit and no-ops a byte-identical redelivery.
 */
fun SampleEntity.toIngest(): IngestDto = IngestDto(
    ts = ts,
    tz_offset = tzOffsetMin,
    updated_at = updatedAt,
    bg = bgMgdl?.toDouble(),
    hr = hr?.toDouble(),
    steps = steps?.toDouble(),
    sleep = sleep?.toDouble(),
    exercise = exercise?.toDouble(),
    mood = mood,
)

/**
 * A server row → catch-up [SamplePatch]. The server schema carries no BG provenance/flag, so a
 * present `bg` is reconstructed as MEASURED/NORMAL (documented on [SamplePatch]); floats snap back
 * to the local integer series.
 */
fun SampleDto.toPatch(): SamplePatch = SamplePatch(
    ts = ts,
    tzOffsetMin = tz_offset,
    updatedAt = updated_at,
    bgMgdl = bg?.let { Math.round(it).toInt() },
    bgProvenance = bg?.let { ReadingProvenance.MEASURED },
    bgFlag = bg?.let { ReadingFlag.NORMAL },
    steps = steps?.let { Math.round(it).toInt() },
    mood = mood,
    hr = hr?.let { Math.round(it).toInt() },
    sleep = sleep?.let { Math.round(it).toInt() },
    exercise = exercise?.let { Math.round(it).toInt() },
)

fun WsEvent.Sample.toPatch(): SamplePatch = SamplePatch(
    ts = ts,
    tzOffsetMin = tz_offset,
    updatedAt = updated_at,
    bgMgdl = bg?.let { Math.round(it).toInt() },
    bgProvenance = bg?.let { ReadingProvenance.MEASURED },
    bgFlag = bg?.let { ReadingFlag.NORMAL },
    steps = steps?.let { Math.round(it).toInt() },
    mood = mood,
    hr = hr?.let { Math.round(it).toInt() },
    sleep = sleep?.let { Math.round(it).toInt() },
    exercise = exercise?.let { Math.round(it).toInt() },
)

/**
 * [LoggedMealEntity] → the `PUT /v1/meals` element. The stored appearance-curve BLOB (little-endian
 * `f64`) decodes to the wire's `List<Double>` on the fixed 300 000 ms grid; a parametric meal has a
 * null curve and is reconstructed server-side from `gi`/`k`/`theta`. `client_id` is the phone-minted
 * idempotency key and `updated_at` is carried verbatim.
 */
fun LoggedMealEntity.toMealEventDto(): MealEventDto = MealEventDto(
    client_id = clientId,
    ts = tsMs,
    tz_offset = tzOffsetMin,
    updated_at = updatedAt,
    grams = grams,
    duration_min = durationMin,
    gi = gi,
    k = k,
    theta = theta,
    custom_curve = customCurve?.toDoubleList(),
    note = note,
)

/**
 * [LoggedDoseEntity] → the `PUT /v1/doses` element. `kind` widens the local [DoseKind] to the wire's
 * lowercase `"bolus"`/`"basal"`; a BOLUS carries gamma `k`/`theta`, a BASAL carries Bateman
 * `ka_per_hour`/`ke_per_hour`. A user-drawn action curve rides as the resolved `custom_curve`.
 */
fun LoggedDoseEntity.toDoseEventDto(): DoseEventDto = DoseEventDto(
    client_id = clientId,
    ts = tsMs,
    tz_offset = tzOffsetMin,
    updated_at = updatedAt,
    kind = kind.name.lowercase(),
    units = units,
    duration_min = durationMin,
    k = k,
    theta = theta,
    ka_per_hour = kaPerHour,
    ke_per_hour = kePerHour,
    custom_curve = customCurve?.toDoubleList(),
    note = note,
)

/**
 * `GET /v1/meals` element → [LoggedMealEntity] for id-keyed catch-up hydration (`insertIgnore` on
 * `clientId`). The wire `custom_curve` is re-BLOBbed locally; `id` is left to Room's autogenerate.
 */
fun MealEventDto.toLoggedMealEntity(): LoggedMealEntity = LoggedMealEntity(
    clientId = client_id,
    tsMs = ts,
    grams = grams,
    gi = gi,
    k = k,
    theta = theta,
    durationMin = duration_min,
    customCurve = custom_curve?.toBlob(),
    tzOffsetMin = tz_offset,
    note = note,
    updatedAt = updated_at,
)

/**
 * `GET /v1/doses` element → [LoggedDoseEntity] for id-keyed catch-up hydration. `kind` narrows the
 * wire's lowercase string back to [DoseKind]; `custom_curve` is re-BLOBbed locally.
 */
fun DoseEventDto.toLoggedDoseEntity(): LoggedDoseEntity = LoggedDoseEntity(
    clientId = client_id,
    tsMs = ts,
    kind = DoseKind.valueOf(kind.uppercase()),
    units = units,
    durationMin = duration_min,
    k = k,
    theta = theta,
    kaPerHour = ka_per_hour,
    kePerHour = ke_per_hour,
    customCurve = custom_curve?.toBlob(),
    tzOffsetMin = tz_offset,
    note = note,
    updatedAt = updated_at,
)
