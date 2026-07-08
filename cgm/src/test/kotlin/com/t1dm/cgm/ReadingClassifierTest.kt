package com.t1dm.cgm

import com.t1dm.core.model.ReadingFlag
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingClassifierTest {

    private val golden = AidexCodec.decode(AdvertFixtures.GOLDEN_PAYLOAD)!!

    @Test
    fun `golden reading is NORMAL despite carrying status = 1`() {
        assertEquals(1, golden.status) // documents the CGM.md §3.1 empirical finding
        assertEquals(ReadingFlag.NORMAL, ReadingClassifier(warmupWindowMin = 60).classify(golden))
    }

    @Test
    fun `within the warm-up window classifies as WARMUP`() {
        val d = AidexCodec.decode(AidexCodec.encode(minFromStart = 10, glucose = 100))!!
        assertEquals(ReadingFlag.WARMUP, ReadingClassifier(warmupWindowMin = 60).classify(d))
    }

    @Test
    fun `out-of-range and invalid-bit readings are INVALID`() {
        val low = AidexCodec.decode(AidexCodec.encode(minFromStart = 100, glucose = 10))!!
        val notValid = AidexCodec.decode(AidexCodec.encode(minFromStart = 100, glucose = 100, valid = false))!!
        val c = ReadingClassifier()
        assertEquals(ReadingFlag.INVALID, c.classify(low))
        assertEquals(ReadingFlag.INVALID, c.classify(notValid))
    }

    @Test
    fun `status gating is opt-in`() {
        // The golden reading (status = 1) passes by default but is rejected when opted in.
        assertEquals(
            ReadingFlag.INVALID,
            ReadingClassifier(rejectNonNormalStatus = true).classify(golden),
        )
    }
}
