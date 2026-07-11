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
 * A downloaded model artifact held in memory (≤~9 MB, an acceptable buffer) beside the server's
 * declared content hash from the `X-SHA256` response header. The coordinator verifies the bytes
 * against [sha256] before the artifact is placed where ModelStore can discover it; [sha256] is
 * `null` if the server omitted the header (the registry-row hash is then the fallback source).
 */
class ModelArtifact(val bytes: ByteArray, val sha256: String?)

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
    /** Read the server's daily-cached stats block for [window] (`7d|30d|90d`). [refresh] forces the
     *  server to recompute fresh (`?refresh=1`, T1DMSERVER stats contract) rather than serve the ≤24 h cache. */
    suspend fun getStats(window: String, refresh: Boolean = false): StatsDto
    /** Attach a meal photo: `POST /v1/photos`, multipart `ts` (epoch-ms) + `image` file part whose
     *  filename carries the extension. Returns the server's `{ok,id,sha256}` ack. */
    suspend fun postPhoto(tsMs: Long, bytes: ByteArray, ext: String): PhotoAck
    /** `GET /v1/models` — the served model registry, envelope-unwrapped like [getStats]. */
    suspend fun listModels(): List<ModelDto>
    /** `GET /v1/models/{id}/download` — streams the `application/octet-stream` artifact into memory
     *  and surfaces the `X-SHA256` response header so the caller can verify integrity. A 4xx/5xx
     *  surfaces as a plain failure (`require`). */
    suspend fun downloadModel(id: String): ModelArtifact
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

    override suspend fun getStats(window: String, refresh: Boolean): StatsDto =
        get<StatsEnvelope>("/v1/stats?window=$window" + if (refresh) "&refresh=1" else "").stats

    override suspend fun listModels(): List<ModelDto> = get<ModelsEnvelope>("/v1/models").models

    /**
     * A DIRECT streaming GET (not the JSON [get] helper): the artifact is a large binary. Rides its
     * own call on [T1dmDispatchers.io], carries the `rw` Bearer token, and surfaces `X-SHA256` for
     * the caller's integrity check. [id] is a `.pte` filename with no special chars in practice, but
     * it is percent-encoded as a single path segment via [HttpUrl] for safety. Throws
     * [NoActiveProfileException] with no endpoint; a 4xx/5xx surfaces via [require].
     */
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

    /**
     * A DIRECT multipart POST (not the JSON outbox): a photo is a large binary, unfit for the
     * text-JSON replay queue, so it rides its own call on [T1dmDispatchers.io]. Throws
     * [NoActiveProfileException] with no endpoint; a 4xx/5xx surfaces via [require] so the caller's
     * `runCatching` reports a plain failure. The server derives the extension from the filename.
     */
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
