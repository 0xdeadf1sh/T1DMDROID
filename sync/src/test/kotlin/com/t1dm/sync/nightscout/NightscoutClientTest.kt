package com.t1dm.sync.nightscout

import com.t1dm.sync.SyncRequest
import com.t1dm.sync.TestDispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NightscoutClientTest {

    private lateinit var server: MockWebServer
    private val dispatchers = TestDispatchers()

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() = server.shutdown()

    private fun client(enabled: Boolean = true) = OkHttpNightscoutClient(
        config = {
            if (enabled) NightscoutConfig(server.url("/").toString().trimEnd('/'), "deadbeef") else null
        },
        dispatchers = dispatchers,
    )

    @Test
    fun `sends the api-secret header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        client().execute(SyncRequest("POST", "/api/v1/entries", "[]".toByteArray()))
        val req = server.takeRequest()
        assertEquals("deadbeef", req.getHeader("api-secret"))
        assertEquals(null, req.getHeader("Authorization"))
        assertEquals("/api/v1/entries", req.path)
    }

    @Test(expected = NightscoutDisabledException::class)
    fun `disabled bridge throws its own exception`() = runTest {
        client(enabled = false).execute(SyncRequest("GET", "/api/v1/status.json", null))
    }

    @Test
    fun `probe reports a rejected secret distinctly from an unreachable host`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(client().probe().contains("secret rejected"))

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok","name":"ns","version":"1.2"}"""))
        assertTrue(client().probe().startsWith("ok"))

        assertEquals("off — set a URL and secret", client(enabled = false).probe())
    }

    private val treatment = NsTreatmentDto(
        eventType = NsEventType.BOLUS,
        created_at = "2026-08-18T03:33:20+03:00",
        insulin = 6.5,
        notes = "cid-1",
    )

    private fun treatmentRequest() = SyncRequest(
        "POST",
        "/api/v1/treatments",
        NsJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(NsTreatmentDto.serializer()), listOf(treatment))
            .toByteArray(),
    )

    @Test
    fun `alreadyPosted recognises its own earlier post by client id`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"eventType":"Correction Bolus","created_at":"2026-08-18T03:33:20+03:00","insulin":6.5,"notes":"cid-1"}]""",
            ),
        )
        assertTrue(client().alreadyPosted(treatmentRequest()))
    }

    /** The fallback shape is (type, timestamp, amount). */
    @Test
    fun `alreadyPosted recognises it by shape when notes are dropped`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"eventType":"Correction Bolus","created_at":"2026-08-18T03:33:20+03:00","insulin":6.5}]""",
            ),
        )
        assertTrue(client().alreadyPosted(treatmentRequest()))
    }

    @Test
    fun `alreadyPosted is false for a different dose at the same instant`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"eventType":"Correction Bolus","created_at":"2026-08-18T03:33:20+03:00","insulin":2.0}]""",
            ),
        )
        assertFalse(client().alreadyPosted(treatmentRequest()))
    }

    @Test
    fun `alreadyPosted is false when the readback fails`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertFalse(client().alreadyPosted(treatmentRequest()))
    }

    @Test
    fun `alreadyPosted never speaks for an entries request`() = runTest {
        assertFalse(client().alreadyPosted(SyncRequest("POST", "/api/v1/entries", "[]".toByteArray())))
    }

    @Test
    fun `secret is accepted as either a token or its digest`() {
        val token = "00000000-1111-2222-3333-444444444444"
        val digest = sha1Hex(token)
        assertEquals(40, digest.length)
        assertEquals(digest, normalizeApiSecret(token))
        assertEquals(digest, normalizeApiSecret(digest))
        assertEquals(digest, normalizeApiSecret(digest.uppercase()))
    }
}
