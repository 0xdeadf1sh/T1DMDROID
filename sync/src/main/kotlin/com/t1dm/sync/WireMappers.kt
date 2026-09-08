package com.t1dm.sync

import com.t1dm.core.model.EventTombstone
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

/** fan is nQuantiles×H, ascending-τ; model is step-major/τ-minor, transposed here; ts verbatim. */
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

/** Integer series widened to wire floats, except exercise (grams/bucket, §3): stays unrounded. */
fun SampleEntity.toIngest(): IngestDto = IngestDto(
    ts = ts,
    tz_offset = tzOffsetMin,
    updated_at = updatedAt,
    bg = bgMgdl?.toDouble(),
    bg_source = bgSource,
    // Only ever set when there IS a bg: the flag travels with the reading and only with it.
    bg_reconstructed = bgMgdl?.let { bgProvenance == ReadingProvenance.RECONSTRUCTED },
    hr = hr?.toDouble(),
    steps = steps?.toDouble(),
    sleep = sleep?.toDouble(),
    exercise = exercise,
    mood = mood,
    // A deleted BG must be named: an omitted field leaves the server's copy, catch-up re-merges.
    clear = if (bgMgdl == null) listOf("bg") else null,
)

/** Floats snap back to the local integer series; exercise excepted — grams, stays a float. */
fun SampleDto.toPatch(): SamplePatch = SamplePatch(
    ts = ts,
    bgSource = bg_source,
    tzOffsetMin = tz_offset,
    updatedAt = updated_at,
    bgMgdl = bg?.let { Math.round(it).toInt() },
    // RECONSTRUCTED is NOT a measurement; every safety gate keys on provenance to gate alarms.
    bgProvenance = bg?.let {
        if (bg_reconstructed) ReadingProvenance.RECONSTRUCTED else ReadingProvenance.MEASURED
    },
    bgFlag = bg?.let { ReadingFlag.NORMAL },
    steps = steps?.let { Math.round(it).toInt() },
    mood = mood,
    hr = hr?.let { Math.round(it).toInt() },
    sleep = sleep?.let { Math.round(it).toInt() },
    exercise = exercise,
)

/** Same provenance rule as REST: a MEASURED-flagged reconstruction could clear an alarm. */
fun WsEvent.Sample.toPatch(): SamplePatch = SamplePatch(
    ts = ts,
    bgSource = bg_source,
    tzOffsetMin = tz_offset,
    updatedAt = updated_at,
    bgMgdl = bg?.let { Math.round(it).toInt() },
    bgProvenance = bg?.let {
        if (bg_reconstructed) ReadingProvenance.RECONSTRUCTED else ReadingProvenance.MEASURED
    },
    bgFlag = bg?.let { ReadingFlag.NORMAL },
    steps = steps?.let { Math.round(it).toInt() },
    mood = mood,
    hr = hr?.let { Math.round(it).toInt() },
    sleep = sleep?.let { Math.round(it).toInt() },
    exercise = exercise,
)

/** Stored curve BLOB (LE f64) decodes to List<Double> on the 300000ms grid; parametric=null. */
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

/** kind widens [DoseKind] to wire lowercase bolus/basal; BOLUS is gamma k/theta, BASAL ka/ke. */
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

/** `custom_curve` is re-BLOBbed locally; `id` is left to Room's autogenerate. */
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
    // Authored elsewhere: its authoring stamp is the only honest "when"; no local edit exists.
    loggedAtMs = updated_at,
    mutatedAtMs = null,
)

/** `kind` narrows the wire's lowercase string back to [DoseKind]; `custom_curve` is re-BLOBbed. */
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
    /** See [MealEventDto.toLoggedMealEntity]. */
    loggedAtMs = updated_at,
    mutatedAtMs = null,
    mutatedActingUntilMs = null,
)

fun EventTombstone.toMealTombstoneDto(): MealTombstoneDto = MealTombstoneDto(
    client_id = clientId,
    ts = tsMs,
    tz_offset = tzOffsetMin,
    updated_at = updatedAt,
)

fun EventTombstone.toDoseTombstoneDto(): DoseTombstoneDto = DoseTombstoneDto(
    client_id = clientId,
    ts = tsMs,
    tz_offset = tzOffsetMin,
    updated_at = updatedAt,
)
