package com.t1dm.app.service

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CgmScanServiceTest {

    @Test
    fun `no Bluetooth scan grant refuses the start`() {
        assertEquals(listOf(Manifest.permission.BLUETOOTH_SCAN), missingCgmGrants { false })
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_SCAN),
            missingCgmGrants { it == Manifest.permission.BLUETOOTH_CONNECT },
        )
    }

    @Test
    fun `the Bluetooth scan grant starts it`() {
        assertTrue(missingCgmGrants { it == Manifest.permission.BLUETOOTH_SCAN }.isEmpty())
    }
}
