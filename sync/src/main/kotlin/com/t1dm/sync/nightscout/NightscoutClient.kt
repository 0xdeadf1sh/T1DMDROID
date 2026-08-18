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

/** Thrown when the bridge is off or half-configured. Distinct from `NoActiveProfileException`: that
 *  one stands the whole outbox down, and an unconfigured BRIDGE must never stall T1DMSERVER sync. */
class NightscoutDisabledException : IllegalStateException("nightscout bridge off / unconfigured")

@Serializable
private data class NsStatusDto(val status: String = "", val name: String = "", val version: String = "")

/**
 * The Nightscout-compatible upload client (`/api/v1`).
 *
 * [execute] deliberately mirrors `SyncHttpClient.execute` so `QueueDrainer` can replay an outbox
 * envelope through either destination with one branch and no second replay path. What differs is the
 * credential — an `api-secret` header carrying a SHA-1, not a Bearer token — and the fact that this
 * host is on the public internet rather than the tailnet, so HTTPS is expected here.
 */
interface NightscoutClient {
    suspend fun execute(request: SyncRequest): SyncResponse

    /** One-line probe for the settings screen. Never throws: the string IS the result. */
    suspend fun probe(): String

    /**
     * Whether this exact batch is already in the receiver's copy — consulted ONLY before a retry.
     *
     * `/api/v1` has no idempotency key and this host serves no `/api/v3`, so a POST whose acknowledgement
     * was lost in transit has already been committed and will commit AGAIN on replay: a double-counted
     * bolus. This narrows that window; it does not close it. A `false` means "not recognised", which
     * covers both "never arrived" and "arrived but is not recognisable in the copy read back", and the
     * caller re-posts on either — preferring a possible duplicate in a logbook to a dropped dose.
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
        // Tighter than the T1DMSERVER client's. A bridged row shares the drain pass with the patient's
        // own rows, so every second this waits is a second their ingest waits behind it.
        .connectTimeout(5_000, TimeUnit.MILLISECONDS)
        .readTimeout(8_000, TimeUnit.MILLISECONDS)
        .writeTimeout(8_000, TimeUnit.MILLISECONDS)
        // OkHttp strips only `Authorization` when it follows a redirect to another host, so a 30x from
        // the configured host would forward this credential — a full read/write grant on the logbook —
        // to an arbitrary third party, in the clear if the target is http. Nothing about this API needs
        // to follow redirects, so it does not.
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
     * Read back the treatments around this batch and look for its own.
     *
     * Two independent ways to recognise one, because it is not knowable in advance which fields a
     * given Nightscout-compatible host preserves: the `client_id` this bridge writes into `notes`, and
     * failing that the (event type, timestamp, amount) triple — `created_at` is derived from a
     * grid-snapped event ts, so it is byte-identical on a replay rather than merely close.
     *
     * The window filter is sent as a `find[…]` query AND applied again locally, so a host that ignores
     * the query and answers with its most recent page still yields a correct answer.
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
        // Whether OUR marker survives on this host — tested by looking for a note SHAPED like the
        // `client_id` we write, not merely for a non-empty one. A host may compose its own note text
        // ("Bolus: 6u"), and this one does: taking that as proof the marker survived would demand a
        // marker that can never match and turn the guard into a guarantee of duplicates. Where the
        // marker does survive it is required, because the shape alone cannot separate two same-shaped
        // events logged close together.
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
 * Same event, as far as anything read back can tell.
 *
 * [requireMarker] is the caller's finding about whether this host keeps `notes`. With notes kept, the
 * `client_id` is the only sound test — the shape triple cannot separate two same-shaped events inside
 * one grid slot. With notes dropped, the triple is all there is, and its ambiguity is accepted because
 * the alternative is no guard at all.
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

/**
 * The same amount, treating absent as zero.
 *
 * A host may materialise the field it was not given — this one answers `carbs: 0` to a bolus posted
 * with no carbs at all. Comparing `null` against `0.0` would call the event's own echo a different
 * event, so the shape match would never fire on the path it exists for.
 */
internal fun sameAmount(a: Double?, b: Double?): Boolean = (a ?: 0.0) == (b ?: 0.0)

/** Whether a recovered note is one of OUR `client_id`s rather than a host's own composed text. The
 *  ids are UUIDs, which nothing a host would write for a human resembles. */
internal fun looksLikeClientId(s: String): Boolean =
    s.length == 36 && s.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))

/**
 * The same moment, however the host chose to render it.
 *
 * Compared as parsed instants and NOT as strings. This host demonstrably re-renders what it is given
 * — a reading sent at `+03:00` reads back as the same instant at `+00:00` — so a string comparison
 * would call every replay "not present" and turn the guard into a guarantee of duplicates on exactly
 * the path it exists to protect. Unparseable on either side falls back to equality, which is no worse
 * than the string test it replaces.
 */
internal fun sameInstant(a: String, b: String): Boolean {
    val x = parseIso(a)
    val y = parseIso(b)
    return if (x != null && y != null) x.isEqual(y) else a == b
}

/**
 * The `client_id` [noteWithClientId] embedded, from either shape it writes: bracketed at the end of
 * the user's own note, or standing alone when there was no note. Returning the whole string in the
 * second case is safe — it IS the id — and getting this wrong would silently disable the marker half
 * of [matches] for every event logged without a note, which is most of them.
 */
internal fun clientIdMarker(notes: String?): String? {
    val n = notes?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (!n.endsWith("]")) return n
    return n.substringAfterLast('[', "").dropLast(1).takeIf { it.isNotBlank() }
}

internal fun parseIso(s: String): OffsetDateTime? =
    runCatching { OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME) }.getOrNull()
