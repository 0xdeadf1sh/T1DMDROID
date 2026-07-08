package com.t1dm.sync

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.data.SamplePatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Reconnect tunables for the WS stream. */
data class StreamConfig(
    val baseReconnectMs: Long = 2_000,
    val maxReconnectMs: Long = 60_000,
    /** OkHttp application-level keepalive; a missed pong fails the socket and triggers reconnect. */
    val pingIntervalMs: Long = 20_000,
)

/**
 * What the app cares about from `/v1/stream` (deliverable 4): [Sample] rows (projected to the wide
 * table via LWW) and [Alert] fan-out, plus the connection-lifecycle markers the catch-up needs.
 * On [Reconnected] the coordinator does a REST catch-up from [cursor] (the last-seen event ts).
 */
sealed interface StreamEvent {
    data class Sample(val patch: SamplePatch) : StreamEvent
    data class Alert(val ts: Long, val kind: String, val payload: JsonElement?) : StreamEvent
    data class Reconnected(val cursor: Long?) : StreamEvent
    object Connected : StreamEvent
    object Disconnected : StreamEvent
}

/** The push-stream seam; a [Flow] the service collects. Reconnects internally with backoff. */
interface StreamClient {
    fun events(): Flow<StreamEvent>
}

/** Default OkHttp client for the stream — no read timeout (long-lived), pings keep it alive. */
private fun defaultStreamOkHttp(config: StreamConfig): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(10_000, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(config.pingIntervalMs, TimeUnit.MILLISECONDS)
        .build()

/**
 * OkHttp-backed RFC 6455 client. It follows the active profile via [endpoint], reconnects with
 * jittered backoff, relies on OkHttp's built-in ping/pong keepalive, and tracks the last-seen event
 * ts so a [StreamEvent.Reconnected] hands the catch-up a cursor. Only `sample` and `alert` are
 * surfaced; the other three event types are decoded-and-ignored. Collection is confined to
 * [T1dmDispatchers.io]; OkHttp delivers callbacks on its own dispatcher and we hop back via the
 * channel.
 */
class WebSocketStreamClient(
    private val endpoint: suspend () -> ServerEndpoint?,
    private val dispatchers: T1dmDispatchers,
    private val config: StreamConfig = StreamConfig(),
    private val client: OkHttpClient = defaultStreamOkHttp(config),
) : StreamClient {

    override fun events(): Flow<StreamEvent> = channelFlow {
        var attempt = 0
        var lastCursor: Long? = null
        var everConnected = false
        while (currentCoroutineContext().isActive) {
            val ep = endpoint()
            if (ep == null) { delay(config.baseReconnectMs); continue }
            val closed = CompletableDeferred<Unit>()
            val request = Request.Builder()
                .url(ep.baseUrl + "/v1/stream?token=${ep.token}")
                .build()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    attempt = 0 // a live connection resets the backoff ladder
                    if (everConnected) trySend(StreamEvent.Reconnected(lastCursor)) else trySend(StreamEvent.Connected)
                    everConnected = true
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    decode(text)?.let { ev ->
                        if (ev is StreamEvent.Sample) lastCursor = ev.patch.ts
                        if (ev is StreamEvent.Alert) lastCursor = ev.ts
                        trySend(ev)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(NORMAL_CLOSURE, null)
                    closed.complete(Unit)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closed.complete(Unit)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Timber.tag(TAG).d(t, "stream dropped; will reconnect")
                    closed.complete(Unit)
                }
            }
            val webSocket = client.newWebSocket(request, listener)
            try {
                closed.await()
            } finally {
                webSocket.cancel()
                trySend(StreamEvent.Disconnected)
            }
            delay(reconnectDelay(attempt++))
        }
        awaitClose { }
    }.flowOn(dispatchers.io)

    private fun decode(text: String): StreamEvent? = runCatching {
        when (val ev = SyncJson.decodeFromString<WsEvent>(text)) {
            is WsEvent.Sample -> StreamEvent.Sample(ev.toPatch())
            is WsEvent.Alert -> StreamEvent.Alert(ev.ts, ev.kind, ev.payload)
            else -> null
        }
    }.getOrNull()

    private fun reconnectDelay(attempt: Int): Long {
        val exp = (config.baseReconnectMs * (1L shl attempt.coerceAtMost(20)))
            .coerceAtMost(config.maxReconnectMs)
        return (exp / 2) + Random.nextLong(exp / 2 + 1)
    }

    private companion object {
        const val TAG = "StreamClient"
        const val NORMAL_CLOSURE = 1000
    }
}
