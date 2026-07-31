package com.t1dm.sync

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * OkHttp-WebSocket-backed `/v1/stream`. Replaces the hand-rolled RFC 6455 codec tests: it drives the
 * real OkHttp WebSocket against MockWebServer's upgrade, asserting that `sample`/`alert` surface
 * (and unknown types are ignored), that the token rides the handshake query, and that a drop
 * reconnects carrying the last-seen cursor.
 */
class OkHttpStreamClientTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }

    @After fun tearDown() { server.shutdown() }

    private fun streamClient(config: StreamConfig) = WebSocketStreamClient(
        endpoint = { ServerEndpoint(baseUrl = server.url("").toString().trimEnd('/'), token = "tkn") },
        dispatchers = TestDispatchers(),
        config = config,
    )

    /** A one-shot upgrade that emits [messages] on open, then closes the socket. */
    private fun upgrade(vararg messages: String) = MockResponse().withWebSocketUpgrade(
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                messages.forEach { webSocket.send(it) }
                webSocket.close(1000, "bye")
            }
        },
    )

    @Test
    fun surfacesSampleAndAlertAndIgnoresUnknown() = runBlocking {
        server.enqueue(
            upgrade(
                """{"type":"sample","ts":111,"bg":120.0,"updated_at":111}""",
                """{"type":"photo","ts":500}""",     // a known frame this client does not surface
                """{"type":"parsnip","ts":501}""",   // a discriminant no build of this client knows
                """{"type":"alert","ts":222,"kind":"low"}""",
            ),
        )

        // Connected, Sample, Alert, Disconnected — both middle frames are dropped, so four events.
        val events = withTimeout(5_000) {
            streamClient(StreamConfig(baseReconnectMs = 60_000)).events().take(4).toList()
        }

        assertEquals(StreamEvent.Connected, events[0])
        assertTrue(events[1] is StreamEvent.Sample)
        assertEquals(111L, (events[1] as StreamEvent.Sample).patch.ts)
        assertEquals(120, (events[1] as StreamEvent.Sample).patch.bgMgdl)
        assertEquals(StreamEvent.Alert(222, "low", null), events[2])
        assertEquals(StreamEvent.Disconnected, events[3])

        // The token rides the handshake as a query param (server auth for the upgrade).
        assertTrue(server.takeRequest().path!!.contains("token=tkn"))
    }

    @Test
    fun reconnectsCarryingLastSeenCursor() = runBlocking {
        server.enqueue(upgrade("""{"type":"sample","ts":111,"bg":120.0,"updated_at":111}"""))
        server.enqueue(upgrade("""{"type":"alert","ts":999,"kind":"high"}"""))

        // Connected, Sample(111), Disconnected, Reconnected(111) — immediate backoff.
        val events = withTimeout(5_000) {
            streamClient(StreamConfig(baseReconnectMs = 0, maxReconnectMs = 0)).events().take(4).toList()
        }

        assertEquals(StreamEvent.Connected, events[0])
        assertEquals(111L, (events[1] as StreamEvent.Sample).patch.ts)
        assertEquals(StreamEvent.Disconnected, events[2])
        assertEquals(StreamEvent.Reconnected(111L), events[3])
    }
}
