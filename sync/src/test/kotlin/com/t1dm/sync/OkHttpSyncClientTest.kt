package com.t1dm.sync

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * OkHttp-backed transport for the REST outbox drain. Exercises the real wire (MockWebServer): the
 * Bearer header rides every request (`/v1/health` included, per the auth change), and 4xx/5xx are
 * surfaced as [SyncResponse] rather than thrown so the drainer can classify them.
 */
class OkHttpSyncClientTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }

    @After fun tearDown() { server.shutdown() }

    private fun client(token: String = "sekret"): OkHttpSyncClient {
        val ep = ServerEndpoint(baseUrl = server.url("").toString().trimEnd('/'), token = token)
        return OkHttpSyncClient(endpoint = { ep }, dispatchers = TestDispatchers())
    }

    @Test
    fun healthSendsBearerToken() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"ok","ws_clients":3}"""))

        val dto = client(token = "sekret").health()

        assertEquals("ok", dto.status)
        assertEquals(3, dto.ws_clients)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/v1/health", recorded.path)
        assertEquals("Bearer sekret", recorded.getHeader("Authorization"))
    }

    @Test
    fun executePostCarriesBodyAndBearerAndDoesNotThrowOn4xx() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("dup"))

        val resp = client().execute(SyncRequest("POST", "/v1/ingest", """{"ts":1}""".toByteArray()))

        assertEquals(409, resp.code)
        assertFalse(resp.ok)
        assertTrue(resp.permanentClientError) // a non-auth 4xx the drainer drops
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/ingest", recorded.path)
        assertEquals("Bearer sekret", recorded.getHeader("Authorization"))
        assertEquals("""{"ts":1}""", recorded.body.readUtf8())
    }

    @Test
    fun noActiveProfileThrows() {
        val client = OkHttpSyncClient(endpoint = { null }, dispatchers = TestDispatchers())
        try {
            runBlocking { client.execute(SyncRequest("GET", "/v1/health", null)) }
            throw AssertionError("expected NoActiveProfileException")
        } catch (_: NoActiveProfileException) {
            // expected — the drainer stands down
        }
    }
}
