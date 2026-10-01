package com.t1dm.feature.cgm

import com.t1dm.core.model.CgmSourceStatus
import com.t1dm.core.model.statusWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CgmPanelFormatTest {

    @Test
    fun `every status has terse words, and none of them is an identifier`() {
        assertEquals("idle", statusWord(CgmSourceStatus.Idle))
        assertEquals("connecting", statusWord(CgmSourceStatus.Scanning))
        assertEquals("warming up", statusWord(CgmSourceStatus.Warmup))
        assertEquals("live", statusWord(CgmSourceStatus.Live))
        assertEquals("signal lost", statusWord(CgmSourceStatus.SignalLost))
        for (st in CgmSourceStatus.entries) {
            val word = statusWord(st)
            assertFalse("$st prints its own identifier", word == st.name)
            assertEquals("$st is not sentence case", word.lowercase(), word)
            assertFalse("$st ends in a period", word.endsWith("."))
        }
    }

    /** The only place a decimal point appears; a dropped leading zero reads as a tenfold error. */
    @Test
    fun `hundredths keep both decimal places`() {
        assertEquals("31.04", hundredths(3104))
        assertEquals("30.75", hundredths(3075))
        assertEquals("8.93", hundredths(893))
        assertEquals("0.00", hundredths(0))
        assertEquals("0.07", hundredths(7))
        assertEquals("0.70", hundredths(70))
        assertEquals("1.00", hundredths(100))
    }

    @Test
    fun `a negative hundredths figure keeps its sign and its places`() {
        assertEquals("-35.29", hundredths(-3529))
        assertEquals("-0.05", hundredths(-5))
    }

    @Test
    fun `a duration names every non-zero unit and never renders empty`() {
        assertEquals("9 d 3 h 20 m", fullDuration((9 * 1440 + 3 * 60 + 20) * 60_000L))
        assertEquals("15 d", fullDuration(15 * 1440 * 60_000L))
        assertEquals("2 h", fullDuration(120 * 60_000L))
        assertEquals("0 m", fullDuration(0L))
        // The card says "expired" past the deadline; this only has to not render a negative.
        assertEquals("0 m", fullDuration(-5_000L))
    }
}
