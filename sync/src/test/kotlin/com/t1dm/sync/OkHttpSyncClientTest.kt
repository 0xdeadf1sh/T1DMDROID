package com.t1dm.sync

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
    fun listModelsParsesRegistryAndKeepsMetaOpaque() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"models":[
                    {"id":"m.pte","name":"m","ext":"pte","path":"/x/m.pte",
                     "meta":{"engine":"executorch_xnnpack_fp32","artifact":"m.pte"},
                     "sha256":"abc","bytes":42,"discovered_at":7},
                    {"id":"n.json","meta":null,"sha256":"def"}
                ]}""",
            ),
        )

        val models = client().listModels()

        assertEquals(2, models.size)
        assertEquals("m.pte", models[0].id)
        assertEquals("abc", models[0].sha256)
        assertEquals(42L, models[0].bytes)
        assertTrue(models[0].meta is kotlinx.serialization.json.JsonObject) // opaque, unparsed
        assertEquals(null, models[1].meta)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/v1/models", recorded.path)
        assertEquals("Bearer sekret", recorded.getHeader("Authorization"))
    }

    @Test
    fun downloadModelReturnsBytesAndXSha256() = runBlocking {
        val body = okio.Buffer().write(byteArrayOf(1, 2, 3, 4))
        server.enqueue(
            MockResponse()
                .setHeader("X-SHA256", "deadbeef")
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(body),
        )

        val art = client().downloadModel("t1dmai_best.xnnpack.pte")

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), art.bytes)
        assertEquals("deadbeef", art.sha256)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/v1/models/t1dmai_best.xnnpack.pte/download", recorded.path)
        assertEquals("Bearer sekret", recorded.getHeader("Authorization"))
    }

    @Test
    fun downloadModelThrowsOnNotFound() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("unknown"))
        try {
            runBlocking { client().downloadModel("missing.pte") }
            throw AssertionError("expected a require() failure on 404")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("404"))
        }
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
