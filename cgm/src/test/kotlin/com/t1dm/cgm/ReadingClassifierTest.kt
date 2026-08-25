package com.t1dm.cgm

import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.ReadingFlag
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingClassifierTest {

    private val golden = AidexCodec.decode(AdvertFixtures.GOLDEN_PAYLOAD)!!

    @Test
    fun `golden reading is NORMAL despite carrying status = 1`() {
        assertEquals(1, golden.status) // CGM.md §3.1
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

    /** Exclusive: the boundary is the instant the BG panel's warm-up countdown reaches zero. */
    @Test
    fun `the warm-up boundary is exclusive and matches the countdown`() {
        val c = ReadingClassifier(warmupWindowMin = 60)
        val justInside = AidexCodec.decode(AidexCodec.encode(minFromStart = 59, glucose = 100))!!
        val atBoundary = AidexCodec.decode(AidexCodec.encode(minFromStart = 60, glucose = 100))!!
        assertEquals(ReadingFlag.WARMUP, c.classify(justInside))
        assertEquals(ReadingFlag.NORMAL, c.classify(atBoundary))
    }

    @Test
    fun `a zero window means no warm-up at all`() {
        val first = AidexCodec.decode(AidexCodec.encode(minFromStart = 0, glucose = 100))!!
        assertEquals(ReadingFlag.NORMAL, ReadingClassifier(warmupWindowMin = 0).classify(first))
    }

    /** The pipeline holds this classifier for its lifetime, so a retune must apply in place. */
    @Test
    fun `a retuned window governs the very next reading`() {
        val c = ReadingClassifier(warmupWindowMin = 60)
        val d = AidexCodec.decode(AidexCodec.encode(minFromStart = 90, glucose = 100))!!
        assertEquals(ReadingFlag.NORMAL, c.classify(d))
        c.warmupWindowMin = 120
        assertEquals(ReadingFlag.WARMUP, c.classify(d))
        c.warmupWindowMin = 0
        assertEquals(ReadingFlag.NORMAL, c.classify(d))
    }

    @Test
    fun `a retuned window is clamped to the tunable range`() {
        val range = CgmSourceDescriptor.WARMUP_WINDOW_RANGE
        val c = ReadingClassifier(warmupWindowMin = range.last + 500)
        assertEquals(range.last, c.warmupWindowMin)
        c.warmupWindowMin = -30
        assertEquals(range.first, c.warmupWindowMin)
    }

    @Test
    fun `an out-of-range value inside the window is INVALID, not WARMUP`() {
        val d = AidexCodec.decode(AidexCodec.encode(minFromStart = 10, glucose = 10))!!
        assertEquals(ReadingFlag.INVALID, ReadingClassifier(warmupWindowMin = 60).classify(d))
    }

    @Test
    fun `status gating is opt-in`() {
        assertEquals(
            ReadingFlag.INVALID,
            ReadingClassifier(rejectNonNormalStatus = true).classify(golden),
        )
    }
}
