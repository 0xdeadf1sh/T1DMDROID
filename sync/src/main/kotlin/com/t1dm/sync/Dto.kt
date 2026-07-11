package com.t1dm.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire DTOs for the T1DMSERVER `/v1` contract (docs/T1DMSERVER_API.md). Field names match the JSON
 * exactly. Every physiologic series is nullable — the server writes `null` for gaps, never omits —
 * and `mood` is the lone integer among the floats.
 */

@Serializable
data class HealthDto(val status: String, val ws_clients: Int = 0)

// ── Sample row (read: GET /v1/series, WS `sample`) ────────────────────────────────────────────

@Serializable
data class SampleDto(
    val ts: Long,
    val tz_offset: Int = 0,
    val bg: Double? = null,
    val carbs: Double? = null,
    val bolus: Double? = null,
    val basal: Double? = null,
    val hr: Double? = null,
    val steps: Double? = null,
    val sleep: Double? = null,
    val exercise: Double? = null,
    val mood: Int? = null,
    val updated_at: Long = 0,
)

@Serializable
data class SeriesPageDto(val rows: List<SampleDto> = emptyList(), val next_cursor: Long? = null)

// ── Prediction (write: PUT /v1/predictions; embedded in ingest) ───────────────────────────────

/** The write shape — the [Prediction] schema minus the server-assigned id/made_at/created_at. */
@Serializable
data class PredictionWriteDto(
    val model_id: String,
    val horizon_steps: Int,
    val line: List<Double>,
    val fan: List<List<Double>>,
    val tod: List<Double>,
    val tod_conf: Double,
)

@Serializable
data class PutPredictionsAck(val ok: Boolean = false, val ids: List<Long> = emptyList())

// ── Ingest (write: POST /v1/ingest) ───────────────────────────────────────────────────────────

@Serializable
data class IngestDto(
    val ts: Long,
    val tz_offset: Int,
    val bg: Double? = null,
    val carbs: Double? = null,
    val bolus: Double? = null,
    val basal: Double? = null,
    val hr: Double? = null,
    val steps: Double? = null,
    val sleep: Double? = null,
    val exercise: Double? = null,
    val mood: Int? = null,
    val prediction: PredictionWriteDto? = null,
    val notes: List<String>? = null,
)

@Serializable
data class IngestAck(val ok: Boolean = false, val ts: Long = 0)

// ── Series batch upsert (write: PUT /v1/series/{name}) ────────────────────────────────────────

@Serializable
data class SeriesPointDto(val ts: Long, val value: Double)

@Serializable
data class SeriesPutDto(val samples: List<SeriesPointDto>)

@Serializable
data class WrittenAck(val ok: Boolean = false, val written: Int = 0)

// ── Notes / Alerts (write) ────────────────────────────────────────────────────────────────────

@Serializable
data class NoteWriteDto(val ts: Long, val tz_offset: Int = 0, val text: String)

@Serializable
data class AlertWriteDto(val ts: Long, val kind: String, val payload: JsonElement? = null)

@Serializable
data class IdAck(val ok: Boolean = false, val id: Long = 0)

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

// ── Stats (read: GET /v1/stats) ───────────────────────────────────────────────────────────────

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

// ── WebSocket events (read: GET /v1/stream) ───────────────────────────────────────────────────

/**
 * The five push events carry the [SampleDto]/[Prediction]/[Note]/[Photo]/[Alert] fields inlined
 * beside a `"type"` discriminant. Only `sample` and `alert` are surfaced upward this phase
 * (deliverable 4); the rest are decoded but ignored so an unknown/extra field never breaks the
 * stream. `ignoreUnknownKeys` on the decoder tolerates the inlined fields we don't model.
 */
@Serializable
sealed interface WsEvent {
    @Serializable
    @SerialName("sample")
    data class Sample(
        val ts: Long,
        val tz_offset: Int = 0,
        val bg: Double? = null,
        val carbs: Double? = null,
        val bolus: Double? = null,
        val basal: Double? = null,
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

    @Serializable @SerialName("note") data class Note(val id: Long = 0, val ts: Long = 0) : WsEvent

    @Serializable @SerialName("photo") data class Photo(val id: Long = 0, val ts: Long = 0) : WsEvent
}
