package com.t1dm.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire DTOs for the T1DMSERVER `/v1` contract (docs/T1DMSERVER_API.md). Field names match the JSON
 * exactly. The phone omits absent optionals on write (`explicitNulls = false`); the server writes
 * explicit `null` for gaps on read. `mood` is the lone integer among the sample floats.
 */

@Serializable
data class HealthDto(val status: String, val ws_clients: Int = 0, val store_epoch: String? = null)

@Serializable
data class SampleDto(
    val ts: Long,
    val tz_offset: Int = 0,
    val bg: Double? = null,
    val bg_source: String? = null,
    val hr: Double? = null,
    val steps: Double? = null,
    val sleep: Double? = null,
    val exercise: Double? = null,
    val mood: Int? = null,
    /** [bg] is a promoted model reconstruction, not a sensor reading. Non-null on a read: `false`
     *  is a fact about the row, not an unknown. */
    val bg_reconstructed: Boolean = false,
    val updated_at: Long = 0,
    /** Tombstoned slot. The row keeps its last-written values, so a reader must exclude by this
     *  flag rather than infer a deletion from all-null scalars. */
    val deleted: Boolean = false,
)

@Serializable
data class SeriesPageDto(val rows: List<SampleDto> = emptyList(), val next_cursor: Long? = null)

/**
 * A meal as a self-describing appearance curve keyed by [client_id]. Parametric meals carry
 * [gi]/[k]/[theta]; mixed/builder meals carry [custom_curve] on the 300 000 ms grid (§8.3). [ts] is
 * grid-snapped, [updated_at] the phone clock stored verbatim.
 */
@Serializable
data class MealEventDto(
    val client_id: String,
    val ts: Long,
    val tz_offset: Int = 0,
    val updated_at: Long,
    val grams: Double,
    val duration_min: Double,
    val gi: Double? = null,
    val k: Double? = null,
    val theta: Double? = null,
    val custom_curve: List<Double>? = null,
    val note: String? = null,
    /** Tombstone. An ordinary upsert carrying the flag, so it inherits the strictly-newer
     *  `updated_at` guard. The phone authors one as [MealTombstoneDto]. */
    val deleted: Boolean = false,
)

/** The minimal body `PUT /v1/meals` accepts when `deleted` is set. Separate from [MealEventDto] so
 *  the curve fields stay required there — nullable, a malformed meal would store as zero grams. */
@Serializable
data class MealTombstoneDto(
    val client_id: String,
    val ts: Long,
    val tz_offset: Int = 0,
    val updated_at: Long,
    val deleted: Boolean = true,
)

@Serializable
data class DoseTombstoneDto(
    val client_id: String,
    val ts: Long,
    val tz_offset: Int = 0,
    val updated_at: Long,
    val deleted: Boolean = true,
)

/**
 * A dose as a self-describing PK action curve keyed by [client_id]. [kind] is `"bolus"` (gamma:
 * [k]/[theta]) or `"basal"` (Bateman: [ka_per_hour]/[ke_per_hour]); [custom_curve] overrides the
 * params when present.
 */
@Serializable
data class DoseEventDto(
    val client_id: String,
    val ts: Long,
    val tz_offset: Int = 0,
    val updated_at: Long,
    val kind: String,
    val units: Double,
    val duration_min: Double,
    val k: Double? = null,
    val theta: Double? = null,
    val ka_per_hour: Double? = null,
    val ke_per_hour: Double? = null,
    val custom_curve: List<Double>? = null,
    val note: String? = null,
    /** See [MealEventDto.deleted]. */
    val deleted: Boolean = false,
)

/** Full-replace: a `PUT` replaces the whole template. */
@Serializable
data class BasalScheduleDto(val schedule_id: String, val active: Boolean, val slots: List<BasalSlotDto>)

@Serializable
data class BasalSlotDto(
    val client_id: String,
    val label: String,
    val time_of_day_min: Int,
    val dose_u: Double,
    val duration_min: Double,
    val ka_per_hour: Double,
    val ke_per_hour: Double,
    val tz_offset: Int = 0,
    val updated_at: Long,
)

/** [ids] are the accepted `client_id`s; the upsert is idempotent by key. */
@Serializable
data class EventBatchAck(val ok: Boolean = false, val ids: List<String> = emptyList())

@Serializable
data class MealsPageDto(val meals: List<MealEventDto> = emptyList())

@Serializable
data class DosesPageDto(val doses: List<DoseEventDto> = emptyList())

/** Null on the wire means no time head, never a zeroed vector. */
@Serializable
data class CircadianDto(
    val probs: List<Double>,
    val predicted_hour: Double,
    val resultant_r: Double,
    val n_bins: Int,
    val bin_hours: Double,
)

/** Keyed idempotent on `(made_at, model_id)`. [made_at] is the phone's cycle ts, [updated_at] the
 *  phone clock; both stored verbatim. */
@Serializable
data class PredictionWriteDto(
    val made_at: Long,
    val model_id: String,
    val updated_at: Long,
    val horizon_steps: Int,
    val line: List<Double>,
    val fan: List<List<Double>>,
    val circadian: CircadianDto? = null,
)

@Serializable
data class IngestDto(
    val ts: Long,
    val tz_offset: Int,
    val bg: Double? = null,
    /** Opaque sensor label, never the serial. */
    val bg_source: String? = null,
    val hr: Double? = null,
    val steps: Double? = null,
    val sleep: Double? = null,
    val exercise: Double? = null,
    val mood: Int? = null,
    /** See [SampleDto.bg_reconstructed]. An absent key means `false` (`explicitNulls = false`). */
    val bg_reconstructed: Boolean? = null,
    val updated_at: Long,
    /**
     * Column names to erase at [ts]. Not an explicit null: a written null leaves the column
     * untouched, and with no version marker on the wire, overloading null would let an older writer
     * destroy stored readings.
     */
    val clear: List<String>? = null,
    /**
     * Whole-row tombstone at [ts]. Tri-state: `true` deletes, `false` revives, an ABSENT key leaves
     * the deletion as it stands. Ingest is a partial-fill path, so a non-null default would have
     * every later steps-only or backfill bundle revive a slot the patient deleted.
     */
    val deleted: Boolean? = null,
)

/** [id] is the opaque label the samples carry; the rest is descriptive and may be absent. */
@Serializable
data class CgmSourceDto(
    val id: String,
    val family: String? = null,
    val model: String? = null,
    val serial: String? = null,
    val updated_at: Long = 0,
)

@Serializable
data class IngestAck(val ok: Boolean = false, val ts: Long = 0)

@Serializable
data class AlertWriteDto(
    val client_id: String,
    val ts: Long,
    val kind: String,
    val payload: JsonElement? = null,
)

@Serializable
data class IdAck(val ok: Boolean = false, val id: String = "")

@Serializable
data class PhotoAck(val ok: Boolean = false, val id: Long = 0, val sha256: String = "")

/**
 * [id] IS the artifact filename (e.g. `t1dmai_best.xnnpack.pte`). [meta] is the exporter's opaque
 * sidecar, kept unparsed and written back verbatim, `null` when the server has none. [sha256] is
 * cross-checked against the download's `X-SHA256` header.
 */
@Serializable
data class ModelDto(
    val id: String,
    val name: String = "",
    val ext: String = "",
    val path: String = "",
    val meta: JsonElement? = null,
    val sha256: String = "",
    val bytes: Long = 0,
    val discovered_at: Long = 0,
)

@Serializable
data class ModelsEnvelope(val models: List<ModelDto> = emptyList())

@Serializable
data class EventStatDto(val count: Int = 0, val duration_ms: Long = 0)

@Serializable
data class StatsDto(
    val window: String,
    val tir: Double = 0.0,
    val time_below: Double = 0.0,
    val time_above: Double = 0.0,
    val mean_bg: Double = 0.0,
    val gmi: Double = 0.0,
    val cv: Double = 0.0,
    val sd: Double = 0.0,
    val hypo_events: EventStatDto = EventStatDto(),
    val hyper_events: EventStatDto = EventStatDto(),
    val mean_daily_carbs: Double = 0.0,
    val tdd: Double = 0.0,
    val bolus_basal_ratio: Double = 0.0,
    val mean_hr: Double = 0.0,
    val bg_hr_corr: Double = 0.0,
    val n_samples: Int = 0,
)

@Serializable
data class StatsEnvelope(val stats: StatsDto)

/** Phone-computed; the server stores and re-serves it verbatim. [window] is `7d`/`30d`/`90d`,
 *  [updated_at] the phone clock. */
@Serializable
data class StatsPushDto(
    val window: String,
    val updated_at: Long,
    val tir: Double = 0.0,
    val time_below: Double = 0.0,
    val time_above: Double = 0.0,
    val mean_bg: Double = 0.0,
    val gmi: Double = 0.0,
    val cv: Double = 0.0,
    val sd: Double = 0.0,
    val hypo_events: EventStatDto = EventStatDto(),
    val hyper_events: EventStatDto = EventStatDto(),
    val mean_daily_carbs: Double = 0.0,
    val tdd: Double = 0.0,
    val bolus_basal_ratio: Double = 0.0,
    val mean_hr: Double = 0.0,
    val bg_hr_corr: Double = 0.0,
    val n_samples: Int = 0,
)

/**
 * Event fields are inlined beside the `"type"` discriminant. Only `sample` and `alert` are surfaced
 * upward; the rest are decoded solely to keep decoding total — the phone never receives its own
 * meal/dose/basal/stats echo, and that history is hydrated over REST.
 */
@Serializable
sealed interface WsEvent {
    @Serializable
    @SerialName("sample")
    data class Sample(
        val ts: Long,
        val tz_offset: Int = 0,
        val bg: Double? = null,
        val bg_source: String? = null,
        /** Without it another session's promoted span arrives as MEASURED, where it can clear an
         *  alarm and enter the dosing series. Defaults false, which is what a 0.4.0 writer means. */
        val bg_reconstructed: Boolean = false,
        /** Nothing on the phone writes one; decoded so this frame is not the path that drops it. */
        val deleted: Boolean = false,
        val hr: Double? = null,
        val steps: Double? = null,
        val sleep: Double? = null,
        val exercise: Double? = null,
        val mood: Int? = null,
        val updated_at: Long = 0,
    ) : WsEvent

    @Serializable
    @SerialName("alert")
    data class Alert(
        val id: Long = 0,
        val ts: Long = 0,
        val kind: String = "",
        val payload: JsonElement? = null,
        val created_at: Long = 0,
    ) : WsEvent

    @Serializable @SerialName("prediction") data class Prediction(val id: Long = 0, val made_at: Long = 0) : WsEvent

    @Serializable @SerialName("photo") data class Photo(val id: Long = 0, val ts: Long = 0) : WsEvent

    @Serializable @SerialName("meal") data class Meal(val client_id: String = "", val ts: Long = 0) : WsEvent

    @Serializable @SerialName("dose") data class Dose(val client_id: String = "", val ts: Long = 0) : WsEvent

    @Serializable @SerialName("basal_schedule") data class BasalSchedule(val schedule_id: String = "") : WsEvent

    @Serializable @SerialName("stats") data class Stats(val window: String = "") : WsEvent
}

/**
 * The one frame the phone sends up the stream; no route stores a forecast. Fields are inlined beside
 * the `"type"` discriminant, never nested under a property — a nested frame does not match the
 * contract and the server drops it in silence. `WsClientFrameTest` pins the exact frame text.
 */
@Serializable
sealed interface WsClientFrame {
    @Serializable
    @SerialName("prediction")
    data class Prediction(
        val made_at: Long,
        val model_id: String,
        val updated_at: Long,
        val horizon_steps: Int,
        val line: List<Double>,
        val fan: List<List<Double>>,
        val circadian: CircadianDto? = null,
    ) : WsClientFrame
}

fun PredictionWriteDto.toStreamFrame(): WsClientFrame.Prediction = WsClientFrame.Prediction(
    made_at = made_at,
    model_id = model_id,
    updated_at = updated_at,
    horizon_steps = horizon_steps,
    line = line,
    fan = fan,
    circadian = circadian,
)

/** Serialized size of the frame, not of the DTO inside it. */
fun PredictionWriteDto.frameBytes(): Int =
    SyncJson.encodeToString<WsClientFrame>(toStreamFrame()).toByteArray(Charsets.UTF_8).size
