package com.t1dm.feature.twin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TwinSignalTest {

    @Test fun `glow is linear in dB and clamped at both ends`() {
        assertEquals(0f, TwinSignal.glow(-110), 0f)
        assertEquals(0f, TwinSignal.glow(-100), 0f)
        assertEquals(0.5f, TwinSignal.glow(-75), 1e-6f)
        assertEquals(1f, TwinSignal.glow(-50), 0f)
        assertEquals(1f, TwinSignal.glow(-30), 0f)
    }

    @Test fun `no link, no reading, or a reading as old as the limit draws no glow`() {
        assertNull(TwinSignal.glowAt(null, 0L, 1L, 10L))
        assertNull(TwinSignal.glowAt(-70, null, 1L, 10L))
        assertNull(TwinSignal.glowAt(-70, 0L, 10L, 10L))
        assertEquals(TwinSignal.glow(-70), TwinSignal.glowAt(-70, 0L, 9L, 10L))
    }

    @Test fun `the median takes the middle of the last three changes`() {
        val m = RssiMedian()
        assertEquals(-70, m.feed("a", -70))
        assertEquals(-90, m.feed("a", -90))
        assertEquals(-70, m.feed("a", -60))
        assertEquals(-60, m.feed("a", -50))
    }

    @Test fun `a repeated value is not a new sample`() {
        val m = RssiMedian()
        m.feed("a", -70)
        m.feed("a", -90)
        assertEquals(-90, m.feed("a", -90))
    }

    @Test fun `a label gains its ordinal only where the model repeats`() {
        val labels = TwinLabels.of(listOf("AiDEX X" to 0, "Libre 3" to 1, "AiDEX X" to 2, "AiDEX X" to null))
        assertEquals(listOf("AiDEX X #0", "Libre 3", "AiDEX X #2", "AiDEX X"), labels)
    }

    @Test fun `a dropped link forgets its history, and links do not mix`() {
        val m = RssiMedian()
        m.feed("a", -90)
        m.feed("b", -40)
        assertNull(m.feed("a", null))
        assertEquals(-60, m.feed("a", -60))
        assertEquals(-40, m.feed("b", -40))
    }
}
