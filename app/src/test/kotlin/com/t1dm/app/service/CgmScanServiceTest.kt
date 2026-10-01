package com.t1dm.app.service

import android.Manifest
import com.t1dm.app.notify.BgGlanceComputer
import com.t1dm.app.notify.GlanceReadings
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.UnitSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CgmScanServiceTest {

    @Test
    fun `either Bluetooth grant missing refuses the start`() {
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_CONNECT),
            missingCgmGrants { it == Manifest.permission.BLUETOOTH_SCAN },
        )
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_SCAN),
            missingCgmGrants { it == Manifest.permission.BLUETOOTH_CONNECT },
        )
        assertEquals(2, missingCgmGrants { false }.size)
    }

    @Test
    fun `both Bluetooth grants start it`() {
        assertTrue(missingCgmGrants { true }.isEmpty())
    }

    @Test
    fun `the widget holds still within a minute of age`() {
        assertEquals(widgetSigAt(61_000L), widgetSigAt(119_000L))
        assertNotEquals(widgetSigAt(119_000L), widgetSigAt(120_000L))
    }

    @Test
    fun `the widget repaints when the reading goes stale inside a minute`() {
        assertNotEquals(widgetSigAt(STALE_MS), widgetSigAt(STALE_MS + 1L))
    }

    @Test
    fun `the widget repaints a new reading of the same value`() {
        assertNotEquals(widgetSigAt(30_000L), widgetSigAt(30_000L, reading(RX + 60_000L)))
    }

    private fun widgetSigAt(ageMs: Long, r: CgmReading = reading(RX)): List<Any?> {
        val glance = BgGlanceComputer.compute(
            GlanceReadings.create(listOf(r)), InferenceState(), THRESHOLDS, null,
            lossMin = 20, staleMin = 15, nowMs = r.rxWallMs + ageMs, trend = null,
        )
        return widgetPushSig(glance, r.rxWallMs, UnitSpace.MgDl, 0, null, "tron", null)
    }

    private fun reading(rxWallMs: Long) = CgmReading(
        sourceId = CgmSourceId("aidexx:TEST"),
        tsMs = rxWallMs,
        bgMgdl = 112,
        trendTenthsPerMin = 0,
        minFromStart = 100,
        quality = 100,
        provenance = ReadingProvenance.MEASURED,
        flag = ReadingFlag.NORMAL,
        tzOffsetMin = 0,
        rxWallMs = rxWallMs,
        rssi = -60,
    )

    private companion object {
        const val RX = 1_700_000_000_000L
        const val STALE_MS = 15 * 60_000L
        val THRESHOLDS = AlertThresholds(urgentLowMgdl = 55, lowMgdl = 70, highMgdl = 180, urgentHighMgdl = 250)
    }
}
