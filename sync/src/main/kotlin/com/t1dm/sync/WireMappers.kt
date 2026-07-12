package com.t1dm.sync

import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.SamplePatch
import com.t1dm.data.db.SampleEntity

/** Wire ⇄ local mappings for the `/v1` contract; the fan transpose is the only subtle step. */

private const val N_QUANTILES = 7
private const val TOD_BINS = 12

/**
 * [ModelPrediction] → the `PUT /v1/predictions` write shape. `line` is the median; `fan` is the
 * `nQuantiles × H` matrix in ascending-τ order (row 3 == line). The model carries the fan as
 * [ModelPrediction.bandsMgdl] step-major/τ-minor (`i = s·nQ + q`), so it transposes to quantile-
 * major rows here. The model has no circadian head this phase, so `tod` is a zeroed 12-bin vector
 * with zero confidence (the server stores it verbatim; a real head fills it in Phase 4+).
 */
fun ModelPrediction.toWrite(): PredictionWriteDto {
    val h = medianBg.size
    val nq = nQuantiles
    val fan = List(nq) { q -> List(h) { s -> bandsMgdl[s * nq + q] } }
    return PredictionWriteDto(
        model_id = modelId,
        horizon_steps = h,
        line = medianBg,
        fan = fan,
        tod = List(TOD_BINS) { 0.0 },
        tod_conf = 0.0,
    )
}

/**
 * A wide local row → `POST /v1/ingest` bundle (integer series widened to the wire's floats).
 * carbs/bolus/basal are DELIBERATELY omitted: the local `sample` holds a dose as a single-bucket
 * IMPULSE (the log slot), but the server must hold the spread appearance/action CURVE, which is pushed
 * separately via `PUT /v1/series/{carbs,bolus,basal}` (the `curveSeries` push). Sending them here too
 * would overwrite that spread with the impulse (ingest's present-fields-overwrite, absent-untouched
 * contract — so leaving them null leaves the series-pushed curve intact).
 */
fun SampleEntity.toIngest(): IngestDto = IngestDto(
    ts = ts,
    tz_offset = tzOffsetMin,
    bg = bgMgdl?.toDouble(),
    carbs = null,
    bolus = null,
    basal = null,
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
    carbsG = carbs,
    bolusU = bolus,
    basalU = basal,
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
    carbsG = carbs,
    bolusU = bolus,
    basalU = basal,
    steps = steps?.let { Math.round(it).toInt() },
    mood = mood,
    hr = hr?.let { Math.round(it).toInt() },
    sleep = sleep?.let { Math.round(it).toInt() },
    exercise = exercise?.let { Math.round(it).toInt() },
)
