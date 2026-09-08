package com.t1dm.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Wire DTOs for T1DMSERVER /v1; phone omits absent optionals, server writes explicit null. */

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
    /** bg is a promoted reconstruction; false on a read is a fact about the row, not an unknown. */
    val bg_reconstructed: Boolean = false,
    val updated_at: Long = 0,
    /** Tombstoned slot; row keeps last values, exclude by this flag, not all-null scalars. */
    val deleted: Boolean = false,
)

@Serializable
data class SeriesPageDto(val rows: List<SampleDto> = emptyList(), val next_cursor: Long? = null)

/** Meal as a self-describing curve by client_id; parametric gi/k/theta, builder custom_curve. */
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
    /** Tombstone: ordinary upsert with the flag, inherits the strictly-newer updated_at guard. */
    val deleted: Boolean = false,
)

/** Minimal PUT /v1/meals body when deleted; keeps MealEventDto's curve fields required. */
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

/** Dose as a self-describing PK curve by client_id; kind bolus (gamma) or basal (Bateman). */
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

/** Keyed idempotent on (made_at, model_id); made_at is cycle ts, updated_at the phone clock. */
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
    /** Column names to erase at ts; a written null leaves it untouched, unlike an older writer. */
    val clear: List<String>? = null,
    /** Tri-state tombstone at ts: true deletes, false revives, ABSENT leaves it; defaults null. */
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

/** id IS the artifact filename; meta is opaque, verbatim; sha256 checked vs X-SHA256. */
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

/** Phone-computed, server re-serves verbatim; window 7d/30d/90d, updated_at is the phone clock. */
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

/** Fields inlined beside type; only sample/alert surface, rest decode to keep decoding total. */
@Serializable
sealed interface WsEvent {
    @Serializable
    @SerialName("sample")
    data class Sample(
        val ts: Long,
        val tz_offset: Int = 0,
        val bg: Double? = null,
        val bg_source: String? = null,
        /** Without it a span arrives MEASURED, can clear an alarm; false = a 0.4.0 writer. */
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

/** The phone's one stream frame; fields inlined beside type, else the server drops it silently. */
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
