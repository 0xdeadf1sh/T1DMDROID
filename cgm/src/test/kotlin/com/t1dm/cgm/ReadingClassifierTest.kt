package com.t1dm.cgm

import com.t1dm.core.model.CgmSourceDescriptor
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

    /** The configured window is the whole verdict on this branch, so the boundary has to be exact.
     *  It is EXCLUSIVE: `minFromStart == warmupWindowMin` is the first NORMAL minute, which is the
     *  same instant the BG panel's warm-up countdown reaches zero (`sensorStart + window`). One off
     *  either way and the chip and the trace contradict each other for a whole five-minute slot. */
    @Test
    fun `the warm-up boundary is exclusive and matches the countdown`() {
        val c = ReadingClassifier(warmupWindowMin = 60)
        val justInside = AidexCodec.decode(AidexCodec.encode(minFromStart = 59, glucose = 100))!!
        val atBoundary = AidexCodec.decode(AidexCodec.encode(minFromStart = 60, glucose = 100))!!
        assertEquals(ReadingFlag.WARMUP, c.classify(justInside))
        assertEquals(ReadingFlag.NORMAL, c.classify(atBoundary))
    }

    /** A zero window disables warm-up outright — no `minFromStart` is below zero, so the very first
     *  minute is already NORMAL and there is no boundary case of its own to get wrong. */
    @Test
    fun `a zero window means no warm-up at all`() {
        val first = AidexCodec.decode(AidexCodec.encode(minFromStart = 0, glucose = 100))!!
        assertEquals(ReadingFlag.NORMAL, ReadingClassifier(warmupWindowMin = 0).classify(first))
    }

    /** The window is retuned in place (the CGM panel's knob) and takes effect on the next advert:
     *  the pipeline holding this classifier is stateful, so it is never rebuilt to carry an edit. */
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

    /** Both directions clamp to the range the panel can produce, so no caller — not the registry, not
     *  a value persisted by a build with wider bounds — can install a window the knob could not. */
    @Test
    fun `a retuned window is clamped to the tunable range`() {
        val range = CgmSourceDescriptor.WARMUP_WINDOW_RANGE
        val c = ReadingClassifier(warmupWindowMin = range.last + 500)
        assertEquals(range.last, c.warmupWindowMin)
        c.warmupWindowMin = -30
        assertEquals(range.first, c.warmupWindowMin)
    }

    /** The value gate still runs first: a reading inside the window that also fails it is INVALID,
     *  never WARMUP — the fail-closed order the window must not disturb. */
    @Test
    fun `an out-of-range value inside the window is INVALID, not WARMUP`() {
        val d = AidexCodec.decode(AidexCodec.encode(minFromStart = 10, glucose = 10))!!
        assertEquals(ReadingFlag.INVALID, ReadingClassifier(warmupWindowMin = 60).classify(d))
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
