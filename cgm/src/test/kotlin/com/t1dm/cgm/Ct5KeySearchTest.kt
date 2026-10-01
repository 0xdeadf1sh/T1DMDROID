package com.t1dm.cgm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The search stands between a wrong key and a wrong reading; every gate is held here. */
class Ct5KeySearchTest {

    private fun sample(
        tempCx100: Int = 3100,
        errorCode: Int = 0,
        ibX100: Int = 0,
    ) = Ct5PushSample(
        glucoseId = 12,
        glucoseMgdl = null,
        trendCode = 0,
        errorCode = errorCode,
        tempCx100 = tempCx100,
        ibX100 = ibX100,
        iwX100 = 841,
        batteryRaw = 1550,
        electrodesMv = listOf(1038, 1038, 996, 642),
    )

    /** Every key decodes to something; only [key] and its complement decode physically. */
    private fun frame(key: Int, physical: Ct5PushSample = sample()) =
        (0..0xFF).associateWith { k ->
            if (k == key || k == (key xor 0xFF)) physical else sample(tempCx100 = 9_000)
        }

    private fun frames(n: Int, key: Int = 0x69, physical: Ct5PushSample = sample()) =
        List(n) { frame(key, physical) }

    @Test
    fun `one key physical on every frame is the answer`() {
        assertEquals(0x69, Ct5KeySearch.recover(frames(Ct5KeySearch.MIN_FRAMES)))
    }

    /** A key and its complement decode identically bar one bit; that's one answer, not two. */
    @Test
    fun `a key and its complement fold to the lower of the two`() {
        assertEquals(0x69, Ct5KeySearch.recover(frames(3, key = 0x96)))
        assertEquals(0x69, Ct5KeySearch.recover(frames(3, key = 0x69)))
    }

    @Test
    fun `too few frames name nothing`() {
        for (n in 0 until Ct5KeySearch.MIN_FRAMES) {
            assertNull("$n frames", Ct5KeySearch.recover(frames(n)))
        }
    }

    @Test
    fun `two surviving pairs name nothing rather than the first`() {
        val both = (0..0xFF).associateWith { k ->
            if (k in setOf(0x69, 0x96, 0x6A, 0x95)) sample() else sample(tempCx100 = 9_000)
        }
        assertNull(Ct5KeySearch.recover(List(3) { both }))
    }

    @Test
    fun `a key that fails on a single frame is dropped`() {
        val good = frames(3)
        val spoiled = good.dropLast(1) + listOf(frame(0x69, sample(tempCx100 = 9_000)))
        assertNull(Ct5KeySearch.recover(spoiled))
    }

    @Test
    fun `a key absent from one frame is dropped`() {
        val missing = frame(0x69).filterKeys { it != 0x69 && it != 0x96 }
        assertNull(Ct5KeySearch.recover(frames(2) + listOf(missing)))
    }

    /** The gate that separates the true key from the one impostor temperature and error admit. */
    @Test
    fun `a non-zero background current is refused`() {
        assertNull(Ct5KeySearch.recover(frames(3, physical = sample(ibX100 = 1285))))
        assertEquals(0x69, Ct5KeySearch.recover(frames(3, physical = sample(ibX100 = 0))))
    }

    @Test
    fun `an error code the read path refuses is refused here`() {
        assertNull(Ct5KeySearch.recover(frames(3, physical = sample(errorCode = 11))))
        for (code in Ct5Constants.ACCEPTED_ERROR_CODES) {
            assertEquals("code $code", 0x69, Ct5KeySearch.recover(frames(3, physical = sample(errorCode = code))))
        }
    }

    @Test
    fun `a temperature outside the vendor's band is refused`() {
        assertNull(Ct5KeySearch.recover(frames(3, physical = sample(tempCx100 = 1199))))
        assertNull(Ct5KeySearch.recover(frames(3, physical = sample(tempCx100 = 4801))))
        assertEquals(0x69, Ct5KeySearch.recover(frames(3, physical = sample(tempCx100 = 1200))))
        assertEquals(0x69, Ct5KeySearch.recover(frames(3, physical = sample(tempCx100 = 4800))))
    }

    /** Skin does not move far over nine minutes; a wrong key that survives the bands wanders. */
    @Test
    fun `a temperature that wanders across the frames is refused`() {
        fun spread(delta: Int) = listOf(
            frame(0x69, sample(tempCx100 = 3000)),
            frame(0x69, sample(tempCx100 = 3000)),
            frame(0x69, sample(tempCx100 = 3000 + delta)),
        )
        assertEquals(0x69, Ct5KeySearch.recover(spread(Ct5KeySearch.MAX_TEMP_SPREAD_CX100)))
        assertNull(Ct5KeySearch.recover(spread(Ct5KeySearch.MAX_TEMP_SPREAD_CX100 + 1)))
    }
}
