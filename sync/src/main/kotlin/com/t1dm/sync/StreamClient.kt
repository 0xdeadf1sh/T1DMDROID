package com.t1dm.sync

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.data.SamplePatch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/** Reconnect tunables for the WS stream. */
data class StreamConfig(
    val baseReconnectMs: Long = 2_000,
    val maxReconnectMs: Long = 60_000,
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

/**
 * Raw-socket RFC 6455 client (no OkHttp). It follows the active profile via [endpoint], reconnects
 * with jittered backoff, answers Pings with Pongs, and tracks the last-seen event ts so a
 * [StreamEvent.Reconnected] hands the catch-up a cursor. Only `sample` and `alert` are surfaced; the
 * other three event types are decoded-and-ignored. All socket work is on [T1dmDispatchers.io].
 */
class WebSocketStreamClient(
    private val endpoint: suspend () -> ServerEndpoint?,
    private val dispatchers: T1dmDispatchers,
    private val config: StreamConfig = StreamConfig(),
) : StreamClient {

    override fun events(): Flow<StreamEvent> = channelFlow {
        var attempt = 0
        var lastCursor: Long? = null
        var everConnected = false
        while (currentCoroutineContext().isActive) {
            val ep = endpoint()
            if (ep == null) { delay(config.baseReconnectMs); continue }
            var socket: Socket? = null
            try {
                socket = connect(ep)
                attempt = 0
                if (everConnected) trySend(StreamEvent.Reconnected(lastCursor)) else trySend(StreamEvent.Connected)
                everConnected = true
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                readLoop@ while (currentCoroutineContext().isActive) {
                    val frame = WsFrames.readFrame(input)
                    when (frame.opcode) {
                        WsFrames.OP_TEXT, WsFrames.OP_CONT -> {
                            val text = String(frame.payload, Charsets.UTF_8)
                            decode(text)?.let { ev ->
                                if (ev is StreamEvent.Sample) lastCursor = ev.patch.ts
                                if (ev is StreamEvent.Alert) lastCursor = ev.ts
                                trySend(ev)
                            }
                        }
                        WsFrames.OP_PING ->
                            output.write(WsFrames.encodeClientFrame(WsFrames.OP_PONG, frame.payload))
                        WsFrames.OP_CLOSE -> break@readLoop
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).d(e, "stream dropped; will reconnect")
            } finally {
                runCatching { socket?.close() }
                trySend(StreamEvent.Disconnected)
            }
            val backoff = reconnectDelay(attempt++)
            delay(backoff)
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

    private fun connect(ep: ServerEndpoint): Socket {
        val uri = URI(ep.baseUrl)
        val secure = uri.scheme.equals("https", ignoreCase = true)
        val port = if (uri.port != -1) uri.port else if (secure) 443 else 80
        val raw = Socket()
        raw.connect(InetSocketAddress(uri.host, port), 10_000)
        val socket = if (secure) {
            (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, uri.host, port, true)
        } else {
            raw
        }
        val key = android.util.Base64.encodeToString(Random.nextBytes(16), android.util.Base64.NO_WRAP)
        val path = "/v1/stream?token=${ep.token}"
        val req = buildString {
            append("GET $path HTTP/1.1\r\n")
            append("Host: ${uri.host}:$port\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $key\r\n")
            append("Sec-WebSocket-Version: 13\r\n\r\n")
        }
        socket.getOutputStream().write(req.toByteArray(Charsets.US_ASCII))
        socket.getOutputStream().flush()
        readHandshakeResponse(socket.getInputStream())
        return socket
    }

    /** Consume the HTTP 101 response headers up to the blank line; throw if not a 101 upgrade. */
    private fun readHandshakeResponse(input: java.io.InputStream) {
        val sb = StringBuilder()
        while (!(sb.length >= 4 && sb.substring(sb.length - 4) == "\r\n\r\n")) {
            val c = input.read()
            if (c < 0) throw java.io.EOFException("handshake truncated")
            sb.append(c.toChar())
        }
        val statusLine = sb.toString().substringBefore("\r\n")
        require("101" in statusLine) { "ws upgrade failed: $statusLine" }
    }

    private fun reconnectDelay(attempt: Int): Long {
        val exp = (config.baseReconnectMs * (1L shl attempt.coerceAtMost(20)))
            .coerceAtMost(config.maxReconnectMs)
        return (exp / 2) + Random.nextLong(exp / 2 + 1)
    }

    private companion object {
        const val TAG = "StreamClient"
    }
}
