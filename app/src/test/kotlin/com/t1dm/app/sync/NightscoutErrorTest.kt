package com.t1dm.app.sync

import com.t1dm.sync.DrainResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NightscoutErrorTest {

    @Test
    fun `a failed pass records its error`() {
        val result = DrainResult(retried = 2, nightscoutError = "HTTP 503")
        assertEquals("HTTP 503", nextNightscoutError(null, result))
    }

    @Test
    fun `a pass with nothing due keeps the last error`() {
        assertEquals("HTTP 503", nextNightscoutError("HTTP 503", DrainResult(remaining = 2)))
    }

    @Test
    fun `a delivered row clears it`() {
        assertNull(nextNightscoutError("HTTP 503", DrainResult(sent = 1, remaining = 1)))
    }
}
