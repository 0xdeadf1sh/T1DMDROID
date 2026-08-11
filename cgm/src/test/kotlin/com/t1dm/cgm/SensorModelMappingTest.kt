package com.t1dm.cgm

import com.t1dm.core.model.CgmSensorModelId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vendor's advertised-name prefix table (§3.1) — what decides which sensor model a newly met
 * sensor is filed under, and therefore whose history it shares on the BG panel.
 */
class SensorModelMappingTest {

    @Test
    fun `every prefix this vendor recognises resolves to one model`() {
        // The researched finding, pinned: LinX / AiDEX X / Lumi / Smart are brand skins of one
        // MicroTech platform, served by one protocol. Splitting them would break a trace that ought
        // to be continuous — the very defect the sensor model class exists to fix.
        val models = CgmConstants.MODEL_BY_NAME_PREFIX.values.toSet()
        assertEquals(setOf(CgmSensorModelId.AIDEX_X), models)
    }

    @Test
    fun `a replacement sensor of another brand joins the same history`() {
        assertEquals(
            CgmConstants.matchAdvertName("AiDEX X-22222C74D9")?.sensorModelId,
            CgmConstants.matchAdvertName("LinX-9AB31F02C4")?.sensorModelId,
        )
    }

    @Test
    fun `the prefix list is derived from the table, never kept beside it`() {
        // Juggluco keeps a second hand-maintained copy of its equivalent list with one entry missing,
        // and those devices are mishandled as a result. One list, read two ways.
        assertEquals(CgmConstants.MODEL_BY_NAME_PREFIX.keys.toList(), CgmConstants.NAME_PREFIXES)
    }

    @Test
    fun `an unrecognised name resolves to nothing rather than guessing`() {
        assertNull(CgmConstants.matchAdvertName("Dexcom G7-1234"))
        // Gen-1 AiDEX advertises the bare name with no separator; every prefix here requires one.
        assertNull(CgmConstants.matchAdvertName("AiDEX"))
        // A brand with nothing after it identifies no sensor.
        assertNull(CgmConstants.matchAdvertName("LinX-"))
    }

    @Test
    fun `one match yields the brand, the model and the serial together`() {
        val m = CgmConstants.matchAdvertName("AiDEX X-22222C74D9")!!
        assertEquals("AiDEX X", m.brand)
        assertEquals("22222C74D9", m.serial)
        assertEquals(CgmSensorModelId.AIDEX_X, m.sensorModelId)
        assertEquals("LinX", CgmConstants.matchAdvertName("LinX-22222C74D9")!!.brand)
        assertEquals("Lumi", CgmConstants.matchAdvertName("Lumi-0001")!!.brand)
        assertEquals("Smart", CgmConstants.matchAdvertName("Smart-0001")!!.brand)
    }

    @Test
    fun `a discovered sensor is described by what it advertised, not by a hardcoded brand`() {
        val d = AidexXPlugin.descriptorFor("9AB31F02C4", advertName = "LinX-9AB31F02C4")
        assertEquals("LinX-9AB31F02C4", d.advertName)
        assertEquals("LinX 9AB31F02C4", d.displayName)
        // `shortName` is what the BG panel prints, and it must not keep the separator.
        assertEquals("LinX", d.shortName)
        assertEquals(CgmSensorModelId.AIDEX_X, d.sensorModelId)
    }

    @Test
    fun `a sensor met without its advertised name still classifies, and says so by recording none`() {
        // The registry path that rebuilds a descriptor from a stored serial alone.
        val d = AidexXPlugin.descriptorFor("9AB31F02C4")
        assertNull(d.advertName)
        assertEquals(CgmSensorModelId.AIDEX_X, d.sensorModelId)
        assertTrue(d.displayName.endsWith("9AB31F02C4"))
    }
}
