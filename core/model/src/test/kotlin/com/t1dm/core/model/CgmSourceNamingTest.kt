package com.t1dm.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The name a vendor advertises may be the serial printed on the sensor. */
class CgmSourceNamingTest {

    private fun descriptor(
        display: String = "AiDEX X 22222C74D9",
        serial: String? = "22222C74D9",
        ordinal: Int = CgmSourceDescriptor.UNASSIGNED_ORDINAL,
    ) = CgmSourceDescriptor(
        id = CgmSourceId("aidexx:$serial"),
        vendorId = "aidexx",
        sensorModelId = CgmSensorModelId.AIDEX_X,
        advertName = display,
        displayName = display,
        serialSuffix = serial,
        warmupWindowMin = 60,
        passiveOnly = false,
        ordinal = ordinal,
    )

    @Test
    fun `an ordinal is zero-based and reads as a number the user can match to a card`() {
        assertEquals("CGM #0", descriptor(ordinal = 0).ordinalLabel())
        assertEquals("CGM #1", descriptor(ordinal = 1).ordinalLabel())
        assertEquals("CGM #17", descriptor(ordinal = 17).ordinalLabel())
    }

    /** Storage mints the number; a plugin-built descriptor has none and must not invent one. */
    @Test
    fun `an unnumbered sensor is named by kind rather than given a number`() {
        assertEquals("CGM", descriptor().ordinalLabel())
        assertEquals(CgmSourceDescriptor.UNASSIGNED_ORDINAL, descriptor().ordinal)
        assertTrue("the sentinel must fail a `>= 0` test", CgmSourceDescriptor.UNASSIGNED_ORDINAL < 0)
    }

    @Test
    fun `an incidental name carries no serial while names are hidden`() {
        val d = descriptor(ordinal = 2)
        assertEquals("CGM #2", d.incidentalName(showNames = false))
        assertEquals("AiDEX X", d.incidentalName(showNames = true))
    }

    /** Regression: a name built from the serial with only a brand prefix and no separator would leak it
     *  through the function meant to withhold it. The name below is fabricated and names no vendor. */
    @Test
    fun `hiding names withholds the serial even when the name is built entirely from it`() {
        val d = descriptor(display = "Brand7000000001", serial = "7000000001", ordinal = 0)
        assertEquals("CGM #0", d.incidentalName(showNames = false))
        assertTrue(
            "the hidden label must not contain the serial",
            !d.incidentalName(showNames = false).contains("7000000001"),
        )
        assertEquals("Brand", d.incidentalName(showNames = true))
    }

    /** `shortName` falls back to the whole name when stripping would leave nothing, so only the
     *  hidden branch can be relied on to drop a serial. */
    @Test
    fun `a name that is nothing but the serial still hides`() {
        val d = descriptor(display = "7000000001", serial = "7000000001", ordinal = 3)
        assertEquals("7000000001", d.incidentalName(showNames = true))
        assertEquals("CGM #3", d.incidentalName(showNames = false))
    }
}
