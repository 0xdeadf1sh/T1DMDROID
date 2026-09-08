package com.t1dm.sync

import com.t1dm.core.common.T1dmDispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** [body] is UTF-8 JSON, or null for a GET. */
data class SyncRequest(val method: String, val path: String, val body: ByteArray?) {
    override fun equals(other: Any?) = other is SyncRequest &&
        method == other.method && path == other.path && (body?.contentEquals(other.body ?: ByteArray(0)) ?: (other.body == null))

    override fun hashCode() = (method.hashCode() * 31 + path.hashCode()) * 31 + (body?.contentHashCode() ?: 0)
}

/** 4xx/5xx do NOT throw; the drainer classifies. */
data class SyncResponse(val code: Int, val body: ByteArray) {
    val ok: Boolean get() = code in 200..299

    /** A 4xx that will never succeed on replay; the drainer drops it. */
    val permanentClientError: Boolean get() = code in 400..499 && code != 401 && code != 403 && code != 429

    /** The drainer stands down without dropping. */
    val authError: Boolean get() = code == 401 || code == 403

    override fun equals(other: Any?) = other is SyncResponse && code == other.code && body.contentEquals(other.body)
    override fun hashCode() = code * 31 + body.contentHashCode()
}

/** The drainer treats this as stand down. */
class NoActiveProfileException : IllegalStateException("no active server profile / token")

/** Held whole in memory (≤~9MB); [sha256]=X-SHA256 header, null ⇒ registry-row hash fallback. */
class ModelArtifact(val bytes: ByteArray, val sha256: String?)

/** /v1 client; every call carries `rw` Bearer, runs on io; Tailscale makes TLS moot, http:// ok. */
interface SyncHttpClient {
    suspend fun execute(request: SyncRequest): SyncResponse
    suspend fun health(): HealthDto
    suspend fun ingest(body: IngestDto): IngestAck
    /** `PUT /v1/meals`; idempotent by `client_id`. */
    suspend fun putMeals(meals: List<MealEventDto>): EventBatchAck
    /** `PUT /v1/doses`; idempotent by `client_id`. */
    suspend fun putDoses(doses: List<DoseEventDto>): EventBatchAck
    /** `PUT /v1/basal-schedule`; full-replace, idempotent by slot `client_id`. */
    suspend fun putBasalSchedule(body: BasalScheduleDto): EventBatchAck
    /** `PUT /v1/stats`; idempotent by `window`. */
    suspend fun putStats(body: StatsPushDto): EventBatchAck
    suspend fun postAlert(body: AlertWriteDto): IdAck
    suspend fun getSeries(from: Long?, to: Long?, cursor: Long?, limit: Int?, fields: String?): SeriesPageDto
    /** `GET /v1/meals?from&to`; a null bound is unbounded. */
    suspend fun getMeals(from: Long?, to: Long?): MealsPageDto
    /** `GET /v1/doses?from&to`; a null bound is unbounded. */
    suspend fun getDoses(from: Long?, to: Long?): DosesPageDto
    suspend fun getBasalSchedule(): BasalScheduleDto
    /** `POST /v1/photos`, multipart `ts` (epoch-ms) + `image`, filename carries the extension. */
    suspend fun postPhoto(tsMs: Long, bytes: ByteArray, ext: String): PhotoAck
    /** `GET /v1/models`, unwrapped from its `models` envelope. */
    suspend fun listModels(): List<ModelDto>
    /** Streams the artifact to memory, surfaces `X-SHA256` for the caller's integrity check. */
    suspend fun downloadModel(id: String): ModelArtifact
}

/** Tolerant on read, gap-omitting on write: an absent field must never null a row. */
internal val SyncJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    classDiscriminator = "type"
}

private fun defaultSyncOkHttp(connectTimeoutMs: Long, readTimeoutMs: Long): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

class OkHttpSyncClient(
    private val endpoint: suspend () -> ServerEndpoint?,
    private val dispatchers: T1dmDispatchers,
    private val client: OkHttpClient = defaultSyncOkHttp(10_000, 20_000),
) : SyncHttpClient {

    /** Transport failure throws `IOException` and propagates so the drainer backs off. */
    override suspend fun execute(request: SyncRequest): SyncResponse = withContext(dispatchers.io) {
        val ep = endpoint() ?: throw NoActiveProfileException()
        val builder = Request.Builder()
            .url(ep.baseUrl + request.path)
            .header("Authorization", "Bearer ${ep.token}")
            .header("Accept", "application/json")
        val reqBody = request.body?.toRequestBody(JSON_MEDIA_TYPE)
        builder.method(request.method, reqBody)
        client.newCall(builder.build()).execute().use { resp ->
            SyncResponse(resp.code, resp.body?.bytes() ?: ByteArray(0))
        }
    }

    private suspend inline fun <reified T> get(path: String): T {
        val r = execute(SyncRequest("GET", path, null))
        require(r.ok) { "GET $path -> ${r.code}" }
        return SyncJson.decodeFromString(String(r.body, Charsets.UTF_8))
    }

    private suspend inline fun <reified B, reified T> send(method: String, path: String, body: B): T {
        val bytes = SyncJson.encodeToString(body).toByteArray(Charsets.UTF_8)
        val r = execute(SyncRequest(method, path, bytes))
        require(r.ok) { "$method $path -> ${r.code}" }
        return SyncJson.decodeFromString(String(r.body, Charsets.UTF_8))
    }

    override suspend fun health(): HealthDto = get("/v1/health")

    override suspend fun ingest(body: IngestDto): IngestAck = send("POST", "/v1/ingest", body)

    override suspend fun putMeals(meals: List<MealEventDto>): EventBatchAck =
        send("PUT", "/v1/meals", meals)

    override suspend fun putDoses(doses: List<DoseEventDto>): EventBatchAck =
        send("PUT", "/v1/doses", doses)

    override suspend fun putBasalSchedule(body: BasalScheduleDto): EventBatchAck =
        send("PUT", "/v1/basal-schedule", body)

    override suspend fun putStats(body: StatsPushDto): EventBatchAck =
        send("PUT", "/v1/stats", body)

    override suspend fun postAlert(body: AlertWriteDto): IdAck = send("POST", "/v1/alerts", body)

    override suspend fun getSeries(
        from: Long?,
        to: Long?,
        cursor: Long?,
        limit: Int?,
        fields: String?,
    ): SeriesPageDto {
        val q = buildList {
            from?.let { add("from=$it") }
            to?.let { add("to=$it") }
            cursor?.let { add("cursor=$it") }
            limit?.let { add("limit=$it") }
            fields?.let { add("fields=$it") }
        }.joinToString("&")
        return get("/v1/series" + if (q.isEmpty()) "" else "?$q")
    }

    override suspend fun getMeals(from: Long?, to: Long?): MealsPageDto {
        val q = buildList {
            from?.let { add("from=$it") }
            to?.let { add("to=$it") }
        }.joinToString("&")
        return get("/v1/meals" + if (q.isEmpty()) "" else "?$q")
    }

    override suspend fun getDoses(from: Long?, to: Long?): DosesPageDto {
        val q = buildList {
            from?.let { add("from=$it") }
            to?.let { add("to=$it") }
        }.joinToString("&")
        return get("/v1/doses" + if (q.isEmpty()) "" else "?$q")
    }

    override suspend fun getBasalSchedule(): BasalScheduleDto = get("/v1/basal-schedule")

    override suspend fun listModels(): List<ModelDto> = get<ModelsEnvelope>("/v1/models").models

    /** Direct GET, not JSON [get]: large binary; [id] percent-encoded as one path segment. */
    override suspend fun downloadModel(id: String): ModelArtifact = withContext(dispatchers.io) {
        val ep = endpoint() ?: throw NoActiveProfileException()
        val url = ep.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("v1/models")
            .addPathSegment(id)
            .addPathSegments("download")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${ep.token}")
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0) // ~5-9 MB in memory, acceptable
            require(resp.isSuccessful) { "GET /v1/models/$id/download -> ${resp.code}" }
            ModelArtifact(bytes, resp.header("X-SHA256"))
        }
    }

    /** Direct multipart POST, not JSON outbox — unfit for replay; extension from filename. */
    override suspend fun postPhoto(tsMs: Long, bytes: ByteArray, ext: String): PhotoAck =
        withContext(dispatchers.io) {
            val ep = endpoint() ?: throw NoActiveProfileException()
            val mediaType = "image/${if (ext == "png") "png" else "jpeg"}".toMediaType()
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("ts", tsMs.toString())
                .addFormDataPart("image", "meal.$ext", bytes.toRequestBody(mediaType))
                .build()
            val request = Request.Builder()
                .url("${ep.baseUrl}/v1/photos")
                .header("Authorization", "Bearer ${ep.token}")
                .header("Accept", "application/json")
                .post(body)
                .build()
            client.newCall(request).execute().use { resp ->
                val respBody = resp.body?.bytes() ?: ByteArray(0)
                require(resp.isSuccessful) { "POST /v1/photos -> ${resp.code}" }
                SyncJson.decodeFromString(String(respBody, Charsets.UTF_8))
            }
        }
}
