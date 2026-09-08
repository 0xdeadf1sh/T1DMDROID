package com.t1dm.sync

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pinned as TEXT: server silently drops what it can't decode; a wrong shape fails invisibly. */
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

        assertFalse("a wrapper property is not the contract's frame", json.contains("\"body\""))
        assertTrue("the discriminant is `type`", json.startsWith("""{"type":"prediction","""))
    }

    /** Absent belief omitted not nulled: explicitNulls=false; #[serde(default)] decodes either. */
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

    @Test
    fun `frameBytes measures the frame and not the payload`() {
        assertEquals(
            SyncJson.encodeToString<WsClientFrame>(dto.toStreamFrame()).toByteArray(Charsets.UTF_8).size,
            dto.frameBytes(),
        )
    }
}
