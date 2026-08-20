package com.t1dm.sync

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exact bytes of the one frame the phone sends up the stream.
 *
 * Pinned as TEXT rather than round-tripped, because a round trip cannot catch what goes wrong here.
 * The server drops a frame it cannot decode in silence — no error frame, no close, nothing in a log
 * the phone can see — so a shape that serializes cleanly and does not match the contract produces a
 * console that shows nothing, forever, with both sides reporting success. The specific mistake this
 * guards is a wrapper property: `data class Prediction(val body: PredictionWriteDto)` serializes as
 * `{"type":"prediction","body":{…}}`, which is well-formed JSON, decodes fine on this side, and is
 * not the contract's frame.
 */
class WsClientFrameTest {

    private val dto = PredictionWriteDto(
        made_at = 1_700_000_100_000L,
        model_id = "m1",
        updated_at = 1_700_000_105_000L,
        horizon_steps = 2,
        line = listOf(100.0, 105.0),
        fan = List(7) { listOf(95.0, 96.0) },
        circadian = null,
    )

    @Test
    fun `the prediction frame inlines its fields beside the type discriminant`() {
        val json = SyncJson.encodeToString<WsClientFrame>(dto.toStreamFrame())

        assertEquals(
            """{"type":"prediction","made_at":1700000100000,"model_id":"m1",""" +
                """"updated_at":1700000105000,"horizon_steps":2,"line":[100.0,105.0],""" +
                """"fan":[[95.0,96.0],[95.0,96.0],[95.0,96.0],[95.0,96.0],[95.0,96.0],""" +
                """[95.0,96.0],[95.0,96.0]]}""",
            json,
        )

        // The two failures worth naming outright.
        assertFalse("a wrapper property is not the contract's frame", json.contains("\"body\""))
        assertTrue("the discriminant is `type`", json.startsWith("""{"type":"prediction","""))
    }

    /** A circadian belief rides inline too, and an absent one is omitted rather than nulled — the
     *  server's field is `#[serde(default)]`, so either decodes, and omitting is what
     *  `explicitNulls = false` produces. */
    @Test
    fun `a circadian belief is carried inline`() {
        val json = SyncJson.encodeToString<WsClientFrame>(
            dto.copy(
                circadian = CircadianDto(
                    probs = listOf(0.1, 0.9),
                    predicted_hour = 7.5,
                    resultant_r = 0.8,
                    n_bins = 2,
                    bin_hours = 12.0,
                ),
            ).toStreamFrame(),
        )
        assertTrue(json, json.contains(""""circadian":{"probs":[0.1,0.9],"predicted_hour":7.5"""))
        assertFalse("an absent belief is omitted", SyncJson.encodeToString<WsClientFrame>(dto.toStreamFrame()).contains("circadian"))
    }

    /** The size the Network panel reports is the size of what actually leaves, not of the DTO. */
    @Test
    fun `frameBytes measures the frame and not the payload`() {
        assertEquals(
            SyncJson.encodeToString<WsClientFrame>(dto.toStreamFrame()).toByteArray(Charsets.UTF_8).size,
            dto.frameBytes(),
        )
    }
}
