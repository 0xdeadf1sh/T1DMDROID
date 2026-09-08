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
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

data class StreamConfig(
    val baseReconnectMs: Long = 2_000,
    val maxReconnectMs: Long = 60_000,
    /** A missed pong fails the socket and triggers a reconnect. */
    val pingIntervalMs: Long = 20_000,
)

/** [Reconnected]s cursor is for the Network panel; coordinator uses its own high-water marks. */
sealed interface StreamEvent {
    data class Sample(val patch: SamplePatch) : StreamEvent
    data class Alert(val ts: Long, val kind: String, val payload: JsonElement?) : StreamEvent
    data class Reconnected(val cursor: Long?) : StreamEvent
    object Connected : StreamEvent
    object Disconnected : StreamEvent
}

/** Reconnects internally with backoff. */
interface StreamClient {
    fun events(): Flow<StreamEvent>

    /** Set when a live event is dropped, channel full. Sole producer; coordinator clears it. */
    val desync: AtomicBoolean

    /** Not delivered with no live socket or a full outgoing buffer — the whole failure model. */
    suspend fun sendPrediction(dto: PredictionWriteDto): ForecastFrame
}

/** [bytes] is the size of the text that left the phone, not of the DTO inside it. */
data class ForecastFrame(val bytes: Int, val delivered: Boolean)

/** No read timeout: the socket is long-lived and pings keep it alive. */
private fun defaultStreamOkHttp(config: StreamConfig): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(10_000, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(config.pingIntervalMs, TimeUnit.MILLISECONDS)
        .build()

/** Follows [endpoint], reconnects with jittered backoff. Confined to [T1dmDispatchers.io]. */
class WebSocketStreamClient(
    private val endpoint: suspend () -> ServerEndpoint?,
    private val dispatchers: T1dmDispatchers,
    private val config: StreamConfig = StreamConfig(),
    private val client: OkHttpClient = defaultStreamOkHttp(config),
    override val desync: AtomicBoolean = AtomicBoolean(false),
) : StreamClient {

    /** Written from OkHttp's own dispatcher, read from the caller's — hence `@Volatile`. */
    @Volatile
    private var live: WebSocket? = null

    override suspend fun sendPrediction(dto: PredictionWriteDto): ForecastFrame =
        withContext(dispatchers.io) {
            // Encoded once: the reported size must be the size of the text actually sent.
            val text = SyncJson.encodeToString<WsClientFrame>(dto.toStreamFrame())
            val bytes = text.toByteArray(Charsets.UTF_8).size
            val ws = live ?: return@withContext ForecastFrame(bytes, delivered = false)
            ForecastFrame(bytes, runCatching { ws.send(text) }.getOrDefault(false))
        }

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
                    attempt = 0
                    live = webSocket
                    if (everConnected) trySend(StreamEvent.Reconnected(lastCursor)) else trySend(StreamEvent.Connected)
                    everConnected = true
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    decode(text)?.let { ev ->
                        if (ev is StreamEvent.Sample) lastCursor = ev.patch.ts
                        // A full channel drops the event silently; next connect resyncs in full.
                        if (trySend(ev).isFailure) desync.set(true)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    live = null
                    webSocket.close(NORMAL_CLOSURE, null)
                    closed.complete(Unit)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    live = null
                    closed.complete(Unit)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    live = null
                    Timber.tag(TAG).d(t, "stream dropped; will reconnect")
                    closed.complete(Unit)
                }
            }
            val webSocket = client.newWebSocket(request, listener)
            try {
                closed.await()
            } finally {
                live = null
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
