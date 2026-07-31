package com.t1dm.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire DTOs for the T1DMSERVER `/v1` contract (docs/T1DMSERVER_API.md). Field names match the JSON
 * exactly. The phone is the sole read-write author: physiologic meals/doses travel as self-describing
 * curve events keyed by a phone-minted `client_id`, and the phone's `updated_at` is carried verbatim
 * (the server never re-stamps). Null-asymmetry: the phone omits absent optionals on write
 * (`explicitNulls = false`), the server writes explicit `null` for gaps on read; `mood` is the lone
 * integer among the sample floats.
 */

@Serializable
data class HealthDto(val status: String, val ws_clients: Int = 0, val store_epoch: String? = null)

// ── Sample row (read: GET /v1/series, WS `sample`) — demoted to six scalars ───────────────────

@Serializable
data class SampleDto(
    val ts: Long,
    val tz_offset: Int = 0,
    val bg: Double? = null,
    val hr: Double? = null,
    val steps: Double? = null,
    val sleep: Double? = null,
    val exercise: Double? = null,
    val mood: Int? = null,
    val updated_at: Long = 0,
)

@Serializable
data class SeriesPageDto(val rows: List<SampleDto> = emptyList(), val next_cursor: Long? = null)

// ── Meal / Dose curve events (write: PUT /v1/meals, /v1/doses; read: GET /v1/meals, /v1/doses) ─

/**
 * A meal as a self-describing appearance curve, keyed by the phone's [client_id]. A parametric meal
 * carries its gamma params ([gi]/[k]/[theta]); a mixed/builder meal instead carries the resolved
 * [custom_curve] on the fixed 300 000 ms grid (no per-food components — §8.3). [ts] is grid-snapped;
 * [updated_at] is the phone clock, stored verbatim.
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
)

/**
 * A dose as a self-describing PK action curve, keyed by the phone's [client_id]. [kind] is
 * `"bolus"` (gamma: [k]/[theta]) or `"basal"` (Bateman: [ka_per_hour]/[ke_per_hour]); a resolved
 * [custom_curve] overrides the params when present.
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
)

/** The full active basal template (write: PUT /v1/basal-schedule full-replace; read: GET). */
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

/** Batch-upsert ack for PUT /v1/meals & /v1/doses — the accepted `client_id`s (idempotent by key). */
@Serializable
data class EventBatchAck(val ok: Boolean = false, val ids: List<String> = emptyList())

@Serializable
data class MealsPageDto(val meals: List<MealEventDto> = emptyList())

@Serializable
data class DosesPageDto(val doses: List<DoseEventDto> = emptyList())

// ── Prediction (write: PUT /v1/predictions) ───────────────────────────────────────────────────

/** The live circadian (time-of-day) head belief; `null` on the wire means "no head", never zeroed. */
@Serializable
data class CircadianDto(
    val probs: List<Double>,
    val predicted_hour: Double,
    val resultant_r: Double,
    val n_bins: Int,
    val bin_hours: Double,
)

/**
 * The prediction write shape, keyed idempotent on `(made_at, model_id)`. [made_at] is the phone's
 * cycle ts and [updated_at] the phone clock — both stored verbatim. [circadian] replaces the former
 * zeroed `tod`/`tod_conf` pair.
 */
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
data class PutPredictionsAck(val ok: Boolean = false, val ids: List<Long> = emptyList())

// ── Ingest (write: POST /v1/ingest) — six scalars, required updated_at ─────────────────────────

@Serializable
data class IngestDto(
    val ts: Long,
    val tz_offset: Int,
    val bg: Double? = null,
    val hr: Double? = null,
    val steps: Double? = null,
    val sleep: Double? = null,
    val exercise: Double? = null,
    val mood: Int? = null,
    val updated_at: Long,
)

@Serializable
data class IngestAck(val ok: Boolean = false, val ts: Long = 0)

// ── Alerts (write) — client_id keyed ──────────────────────────────────────────────────────────

@Serializable
data class AlertWriteDto(
    val client_id: String,
    val ts: Long,
    val kind: String,
    val payload: JsonElement? = null,
)

@Serializable
data class IdAck(val ok: Boolean = false, val id: String = "")

// ── Photo (write: POST /v1/photos, multipart/form-data) ───────────────────────────────────────

@Serializable
data class PhotoAck(val ok: Boolean = false, val id: Long = 0, val sha256: String = "")

// ── Model registry (read: GET /v1/models) ─────────────────────────────────────────────────────

/**
 * A served model-registry row (`GET /v1/models`). [id] IS the artifact filename (e.g.
 * `t1dmai_best.xnnpack.pte`); [meta] is the opaque sidecar JSON — the exporter's descriptor —
 * retained as an unparsed [JsonElement] (`null` when the server has no sidecar), which the
 * coordinator inspects with `jsonObject`/`jsonPrimitive` and writes back verbatim (bar the
 * normalized `id`/`artifact` fields). [sha256] is the registry-declared content hash, cross-checked
 * against the download's `X-SHA256` response header.
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

// ── Stats (read: GET /v1/stats; write: PUT /v1/stats) ─────────────────────────────────────────

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

/**
 * The phone-computed stats block the server stores verbatim and re-serves (the server never
 * computes). A flat superset of the `core::Stats` fields the TUI renders; [window] is `7d`/`30d`/`90d`
 * and [updated_at] the phone clock.
 */
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

// ── WebSocket events (read: GET /v1/stream) ───────────────────────────────────────────────────

/**
 * The nine push events carry their inlined event fields beside a `"type"` discriminant. Only
 * `sample` and `alert` are surfaced upward; the rest — `prediction`, `note`, `photo`, and the
 * `meal`/`dose`/`basal_schedule`/`stats` stubs — are decoded but ignored so an unknown or extra
 * field never breaks the stream (`ignoreUnknownKeys` tolerates the inlined fields we don't model).
 * The phone is the sole read-write author and every write fans out `broadcast_except(origin)`, so it
 * never receives its own meal/dose/basal/stats echo; those variants exist only to keep decoding total
 * (they are not wired to catch-up) and gap-hydration is REST-only.
 */
@Serializable
sealed interface WsEvent {
    @Serializable
    @SerialName("sample")
    data class Sample(
        val ts: Long,
        val tz_offset: Int = 0,
        val bg: Double? = null,
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
