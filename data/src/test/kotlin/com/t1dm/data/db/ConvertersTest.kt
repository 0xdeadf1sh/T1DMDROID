package com.t1dm.data.db

import com.t1dm.core.model.BackendId
import org.junit.Assert.assertEquals
import org.junit.Test

class ConvertersTest {

    private val c = Converters()

    @Test
    fun `every live backend round-trips`() {
        for (bid in BackendId.entries) {
            assertEquals(bid, c.stringToBackendId(c.backendIdToString(bid)))
        }
    }

    /** `prediction.backend` is never pruned, so a row written by a build that had a backend this
     *  one dropped must read back rather than throw the whole cursor. */
    @Test
    fun `a backend this build no longer has reads back as UNKNOWN`() {
        assertEquals(BackendId.UNKNOWN, c.stringToBackendId("EXECUTORCH_VULKAN_FP32"))
        assertEquals(BackendId.UNKNOWN, c.stringToBackendId("EXECUTORCH_VULKAN_FP16"))
        assertEquals(BackendId.UNKNOWN, c.stringToBackendId("LITERT_NPU"))
        assertEquals(BackendId.UNKNOWN, c.stringToBackendId(""))
        assertEquals(null, c.stringToBackendId(null))
    }
}
