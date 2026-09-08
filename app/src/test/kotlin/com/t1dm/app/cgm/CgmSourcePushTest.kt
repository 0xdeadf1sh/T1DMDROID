package com.t1dm.app.cgm

import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** serial is the sensor's printed number; omitting it is no contract change (SPEC/http-api.md). */
class CgmSourcePushTest {

    private val descriptor = CgmSourceDescriptor(
        id = CgmSourceId("vendor:7000000001"),
        vendorId = "vendor",
        sensorModelId = "vendor:model",
        advertName = "Brand7000000001",
        displayName = "Brand7000000001",
        serialSuffix = "7000000001",
        warmupWindowMin = 45,
        passiveOnly = false,
        ordinal = 0,
    )

    @Test
    fun `the serial is never sent`() {
        val dto = cgmSourceDto(descriptor, nowMs = 1_700_000_000_000L)
        assertEquals(descriptor.id.opaque, dto.id)
        assertNull(dto.serial)
        assertEquals(1_700_000_000_000L, dto.updated_at)
    }

    @Test
    fun `the body contains the serial nowhere`() {
        val dto = cgmSourceDto(descriptor, nowMs = 0L)
        val body = listOfNotNull(dto.id, dto.family, dto.model, dto.serial).joinToString("|")
        assertTrue(body, !body.contains("7000000001"))
        assertTrue("the id must still be the opaque label", dto.id.startsWith("s_"))
    }

    @Test
    fun `a serial that is also the advertised name is still withheld`() {
        val serialAsName = descriptor.copy(advertName = "Brand7000000001", displayName = "Brand7000000001")
        val dto = cgmSourceDto(serialAsName, nowMs = 0L)
        val body = listOfNotNull(dto.id, dto.family, dto.model, dto.serial).joinToString("|")
        assertTrue(body, !body.contains("7000000001"))
    }

    @Test
    fun `the vendor and model are disclosed`() {
        val dto = cgmSourceDto(descriptor, nowMs = 0L)
        assertEquals("vendor", dto.family)
        assertEquals("vendor:model", dto.model)
    }
}
