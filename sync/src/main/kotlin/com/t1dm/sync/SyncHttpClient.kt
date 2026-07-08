package com.t1dm.sync

import com.t1dm.core.common.T1dmDispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** A raw `/v1` request the drainer executes verbatim; `body` is UTF-8 JSON or `null` for GETs. */
data class SyncRequest(val method: String, val path: String, val body: ByteArray?) {
    override fun equals(other: Any?) = other is SyncRequest &&
        method == other.method && path == other.path && (body?.contentEquals(other.body ?: ByteArray(0)) ?: (other.body == null))

    override fun hashCode() = (method.hashCode() * 31 + path.hashCode()) * 31 + (body?.contentHashCode() ?: 0)
}

/** An HTTP response with the status already surfaced; 4xx/5xx do NOT throw (the drainer classifies). */
data class SyncResponse(val code: Int, val body: ByteArray) {
    val ok: Boolean get() = code in 200..299

    /** A 4xx that will never succeed on replay (malformed) — the drainer drops it. */
    val permanentClientError: Boolean get() = code in 400..499 && code != 401 && code != 403 && code != 429

    /** Auth failure — the token/profile needs fixing; the drainer stands down without dropping. */
    val authError: Boolean get() = code == 401 || code == 403

    override fun equals(other: Any?) = other is SyncResponse && code == other.code && body.contentEquals(other.body)
    override fun hashCode() = code * 31 + body.contentHashCode()
}

/** Thrown when no active profile/token is configured; the drainer treats this as "stand down". */
class NoActiveProfileException : IllegalStateException("no active server profile / token")

/**
 * The `/v1` client (docs/T1DMSERVER_API.md). [execute] is the generic path the [QueueDrainer] drives
 * with an outbox envelope; the typed helpers are the read/health surface the Network panel and
 * catch-up use. Every call carries the active profile's `rw` Bearer token and runs on
 * [T1dmDispatchers.io]. Tailscale makes transport TLS moot, so plaintext `http://` is expected.
 */
interface SyncHttpClient {
    suspend fun execute(request: SyncRequest): SyncResponse
    suspend fun health(): HealthDto
    suspend fun ingest(body: IngestDto): IngestAck
    suspend fun putPredictions(preds: List<PredictionWriteDto>): PutPredictionsAck
    suspend fun putSeries(name: String, body: SeriesPutDto): WrittenAck
    suspend fun postNote(body: NoteWriteDto): IdAck
    suspend fun postAlert(body: AlertWriteDto): IdAck
    suspend fun getSeries(from: Long?, to: Long?, cursor: Long?, limit: Int?, fields: String?): SeriesPageDto
    suspend fun getStats(window: String): StatsDto
    /** Photo multipart is stubbed this phase (PLAN.private.md Phase 3). */
    suspend fun postPhotoStub(): Nothing
}

/** Shared JSON: tolerant on read, gap-omitting on write (an absent field must never null a row). */
internal val SyncJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    classDiscriminator = "type"
}

/** Default OkHttp client for the outbox drain — a shared connection pool, plaintext-friendly. */
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

    /**
     * OkHttp does NOT throw on 4xx/5xx (the drainer classifies via [SyncResponse]); it throws
     * `IOException` only on transport failure, which propagates so the drainer backs off. Every
     * call — `/v1/health` included — carries the active profile's `rw` Bearer token.
     */
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

    override suspend fun putPredictions(preds: List<PredictionWriteDto>): PutPredictionsAck =
        send("PUT", "/v1/predictions", preds)

    override suspend fun putSeries(name: String, body: SeriesPutDto): WrittenAck =
        send("PUT", "/v1/series/$name", body)

    override suspend fun postNote(body: NoteWriteDto): IdAck = send("POST", "/v1/notes", body)

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

    override suspend fun getStats(window: String): StatsDto =
        get<StatsEnvelope>("/v1/stats?window=$window").stats

    override suspend fun postPhotoStub(): Nothing =
        TODO("photo multipart path is stubbed for Phase 3 (PLAN.private.md)")
}
