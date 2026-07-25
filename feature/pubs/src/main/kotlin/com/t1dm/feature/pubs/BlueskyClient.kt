package com.t1dm.feature.pubs

import com.t1dm.core.common.T1dmDispatchers
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Tolerant reader for the AppView response: unknown keys and lenient JSON never break a decode. */
private val BlueskyJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** A modest default engine — the AppView is a small, quick JSON GET, so short timeouts suffice. */
private fun defaultBlueskyOkHttp(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

/**
 * The unauthenticated Bluesky AppView client (`public.api.bsky.app`) — no token, no cleartext, one
 * read endpoint. [authorFeed] runs entirely on [T1dmDispatchers.io] (network and decode both) and
 * translates every failure — transport, non-2xx, or malformed body — into a plain-language message
 * on the thrown exception, so nothing above ever has to render a bare status code.
 */
class BlueskyClient(
    private val dispatchers: T1dmDispatchers,
    private val http: OkHttpClient = defaultBlueskyOkHttp(),
    private val baseUrl: String = "https://public.api.bsky.app",
) {
    internal suspend fun authorFeed(
        actor: String,
        limit: Int = 25,
        cursor: String? = null,
    ): AuthorFeedResponse {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("xrpc/app.bsky.feed.getAuthorFeed")
            .addQueryParameter("actor", actor)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("filter", "posts_no_replies")
            .apply { cursor?.let { addQueryParameter("cursor", it) } }
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()

        val (code, raw) = fetch(request)
        if (code !in 200..299) throw IOException(statusMessage(code))

        return withContext(dispatchers.io) {
            try {
                BlueskyJson.decodeFromString<AuthorFeedResponse>(raw)
            } catch (e: SerializationException) {
                throw IOException("Couldn't read Bluesky's response", e)
            }
        }
    }

    /**
     * Enqueue the GET so an abandoned load frees its socket and thread at once: [Call.enqueue] runs on
     * OkHttp's dispatcher (never the main thread), and tying the coroutine's cancellation to
     * [Call.cancel] means a caller that walks away — a tab switch mid-fetch — no longer parks an IO
     * thread inside a blocking `execute()` until the read timeout expires.
     */
    private suspend fun fetch(request: Request): Pair<Int, String> =
        suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(
                        IOException("Couldn't reach Bluesky — check your connection", e),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    runCatching { response.use { it.code to it.body?.string().orEmpty() } }
                        .onSuccess { cont.resume(it) }
                        .onFailure { e ->
                            cont.resumeWithException(
                                IOException("Couldn't reach Bluesky — check your connection", e),
                            )
                        }
                }
            })
        }
}

private fun statusMessage(code: Int): String = when {
    code == 404 -> "Couldn't find that Bluesky feed — the account may have moved or been removed."
    code == 429 -> "Bluesky is rate-limiting — wait a moment"
    code in 500..599 -> "Bluesky is having trouble"
    else -> "Couldn't load the feed from Bluesky (it returned status $code)."
}
