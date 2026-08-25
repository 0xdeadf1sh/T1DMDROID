package com.t1dm.sync.nightscout

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.sync.SyncRequest
import com.t1dm.sync.SyncResponse
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Distinct from `NoActiveProfileException`, which stands the whole outbox down: an unconfigured
 *  BRIDGE must never stall T1DMSERVER sync. */
class NightscoutDisabledException : IllegalStateException("nightscout bridge off / unconfigured")

@Serializable
private data class NsStatusDto(val status: String = "", val name: String = "", val version: String = "")

/**
 * [execute] deliberately mirrors `SyncHttpClient.execute` so `QueueDrainer` replays an outbox
 * envelope through either destination with one branch. The credential is an `api-secret` header
 * carrying a SHA-1, and this host is on the public internet, so HTTPS is expected.
 */
interface NightscoutClient {
    suspend fun execute(request: SyncRequest): SyncResponse

    /** Never throws: the string IS the result. */
    suspend fun probe(): String

    /**
     * Consulted ONLY before a retry: `/api/v1` has no idempotency key, so a POST whose ack was lost
     * would commit twice. Narrows that window, does not close it. A `false` covers both "never
     * arrived" and "not recognisable in the copy read back", and the caller re-posts on either.
     */
    suspend fun alreadyPosted(request: SyncRequest): Boolean
}

private val NS_JSON_MEDIA_TYPE = "application/json".toMediaType()

/** How far either side of a treatment's own timestamp its counterpart is looked for. */
private val MATCH_WINDOW: Duration = Duration.ofMinutes(2)

class OkHttpNightscoutClient(
    private val config: suspend () -> NightscoutConfig?,
    private val dispatchers: T1dmDispatchers,
    private val client: OkHttpClient = OkHttpClient.Builder()
        // Tighter than the T1DMSERVER client's: a bridged row shares the drain pass with the patient's own.
        .connectTimeout(5_000, TimeUnit.MILLISECONDS)
        .readTimeout(8_000, TimeUnit.MILLISECONDS)
        .writeTimeout(8_000, TimeUnit.MILLISECONDS)
        // OkHttp strips only `Authorization` across a cross-host redirect, so a 30x would forward this
        // credential to an arbitrary third party. Nothing here needs redirects.
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) : NightscoutClient {

    override suspend fun execute(request: SyncRequest): SyncResponse = withContext(dispatchers.io) {
        val cfg = config() ?: throw NightscoutDisabledException()
        val builder = Request.Builder()
            .url(cfg.baseUrl + request.path)
            .header("api-secret", cfg.secretSha1)
            .header("Accept", "application/json")
        builder.method(request.method, request.body?.toRequestBody(NS_JSON_MEDIA_TYPE))
        client.newCall(builder.build()).execute().use { resp ->
            SyncResponse(resp.code, resp.body?.bytes() ?: ByteArray(0))
        }
    }

    override suspend fun probe(): String = runCatching {
        val r = execute(SyncRequest("GET", "/api/v1/status.json", null))
        when {
            r.ok -> {
                val s = runCatching { NsJson.decodeFromString<NsStatusDto>(String(r.body, Charsets.UTF_8)) }.getOrNull()
                if (s == null) "ok" else "ok — ${s.name} ${s.version}".trimEnd()
            }
            r.code == 401 || r.code == 403 -> "HTTP ${r.code} — secret rejected"
            else -> "HTTP ${r.code}"
        }
    }.getOrElse { if (it is NightscoutDisabledException) "off — set a URL and secret" else "unreachable" }

    /**
     * Two ways to recognise a treatment, since which fields a host preserves is not knowable: the
     * `client_id` written into `notes`, else the (event type, timestamp, amount) triple. The window
     * filter is sent as a `find[…]` query AND applied again locally, in case the host ignores it.
     */
    override suspend fun alreadyPosted(request: SyncRequest): Boolean {
        if (!request.path.startsWith("/api/v1/treatments")) return false
        val body = request.body ?: return false
        val mine = runCatching {
            NsJson.decodeFromString<List<NsTreatmentDto>>(String(body, Charsets.UTF_8))
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return false

        val stamps = mine.mapNotNull { parseIso(it.created_at) }
        if (stamps.size != mine.size) return false
        val from = stamps.min().minus(MATCH_WINDOW)
        val to = stamps.max().plus(MATCH_WINDOW)

        val existing = runCatching { fetchTreatments(from, to) }.getOrNull() ?: return false
        // Tested by looking for a note SHAPED like a `client_id`, not merely a non-empty one: this
        // host composes its own note text ("Bolus: 6u"), and taking that as proof would demand a
        // marker that can never match. Where the marker does survive it is required.
        val markersSurvive = existing.any { clientIdMarker(it.notes)?.let(::looksLikeClientId) == true }
        return mine.all { m -> existing.any { it.matches(m, requireMarker = markersSurvive) } }
    }

    private suspend fun fetchTreatments(from: OffsetDateTime, to: OffsetDateTime): List<NsTreatmentDto> =
        withContext(dispatchers.io) {
            val cfg = config() ?: throw NightscoutDisabledException()
            val url = (cfg.baseUrl + "/api/v1/treatments.json").toHttpUrl().newBuilder()
                .addQueryParameter("find[created_at][\$gte]", from.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .addQueryParameter("find[created_at][\$lte]", to.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .addQueryParameter("count", "200")
                .build()
            val req = Request.Builder()
                .url(url)
                .header("api-secret", cfg.secretSha1)
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                if (!resp.isSuccessful) return@use emptyList()
                val all = NsJson.decodeFromString<List<NsTreatmentDto>>(String(bytes, Charsets.UTF_8))
                all.filter { t ->
                    val at = parseIso(t.created_at) ?: return@filter false
                    !at.isBefore(from) && !at.isAfter(to)
                }
            }
        }
}

/**
 * [requireMarker] is the caller's finding about whether this host keeps `notes`. With notes kept the
 * `client_id` is the only sound test — the triple cannot separate two same-shaped events inside one
 * grid slot. With notes dropped the triple is all there is, and its ambiguity is accepted.
 */
internal fun NsTreatmentDto.matches(other: NsTreatmentDto, requireMarker: Boolean = false): Boolean {
    val marker = clientIdMarker(other.notes)
    if (marker != null && notes?.contains(marker) == true) return true
    if (requireMarker) return false
    return eventType == other.eventType &&
        sameInstant(created_at, other.created_at) &&
        sameAmount(carbs, other.carbs) &&
        sameAmount(insulin, other.insulin)
}

/** Absent is zero: a host may materialise the field it was not given (`carbs: 0` on a bolus), and
 *  `null` against `0.0` would call an event's own echo a different event. */
internal fun sameAmount(a: Double?, b: Double?): Boolean = (a ?: 0.0) == (b ?: 0.0)

/** The ids are UUIDs; nothing a host composes for a human resembles one. */
internal fun looksLikeClientId(s: String): Boolean =
    s.length == 36 && s.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))

/**
 * Parsed instants, NOT strings: this host re-renders what it is given — `+03:00` reads back as the
 * same instant at `+00:00` — so a string comparison would call every replay "not present".
 * Unparseable on either side falls back to equality.
 */
internal fun sameInstant(a: String, b: String): Boolean {
    val x = parseIso(a)
    val y = parseIso(b)
    return if (x != null && y != null) x.isEqual(y) else a == b
}

/** Either shape [noteWithClientId] writes: bracketed at the end of the user's own note, or standing
 *  alone when there was none — in which case the whole string IS the id. */
internal fun clientIdMarker(notes: String?): String? {
    val n = notes?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (!n.endsWith("]")) return n
    return n.substringAfterLast('[', "").dropLast(1).takeIf { it.isNotBlank() }
}

internal fun parseIso(s: String): OffsetDateTime? =
    runCatching { OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME) }.getOrNull()
