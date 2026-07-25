package com.t1dm.app.widget

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.t1dm.alerts.AlarmConfig
import com.t1dm.app.notify.BgGlanceComputer
import com.t1dm.core.design.ThemeIds
import com.t1dm.core.model.AlertBand
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.UnitSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget's fallback render (the boot / reaped-service path). What matters is that a cached tile
 * cannot lie: it re-ages against the wall clock rather than freezing, it keeps the user's units,
 * theme and alarm geometry, and it never carries a forecast claim forward.
 */
class WidgetStateStoreTest {

    private val thresholds = AlertThresholds(urgentLowMgdl = 60, lowMgdl = 80, highMgdl = 170, urgentHighMgdl = 240)

    private fun reading(bgMgdl: Int?, trendTenths: Int?, rxWallMs: Long, rssi: Int? = null) = CgmReading(
        sourceId = CgmSourceId("test"),
        tsMs = rxWallMs - rxWallMs.mod(300_000L),
        bgMgdl = bgMgdl,
        trendTenthsPerMin = trendTenths,
        minFromStart = 600,
        quality = null,
        provenance = ReadingProvenance.MEASURED,
        flag = ReadingFlag.NORMAL,
        tzOffsetMin = 0,
        rxWallMs = rxWallMs,
        rssi = rssi,
    )

    private fun snapshot(latest: CgmReading?, nowMs: Long, unit: UnitSpace = UnitSpace.MmolL) = WidgetSnapshot(
        glance = BgGlanceComputer.compute(
            latest = latest,
            state = InferenceState(),
            thresholds = thresholds,
            lossMin = 25,
            staleMin = STALE_MIN,
            nowMs = nowMs,
        ),
        unit = unit,
        animationsEnabled = false,
        bgAlphaPct = 42,
        iobU = 1.5,
        cobG = 30.0,
        rssi = latest?.rssi,
        clockHour = 13.5,
        clockConf = 0.9,
        gmi = 6.4,
        steps = 4200,
        death = true,
        glyText = "STABLE",
        glyKind = GlyKind.STABLE,
        thresholds = thresholds,
        lossMin = 25,
        themeId = ThemeIds.HELLO_KITTY,
        customThemeJson = null,
    )

    @Test
    fun `nothing written yields no cached render`() {
        assertNull(WidgetStateStore.read(mutablePreferencesOf(), 0L))
    }

    @Test
    fun `cached reading is re-aged against the wall clock, not frozen`() {
        val writeAt = 1_700_000_000_000L
        val rxWallMs = writeAt - 120_000L // 2 min old when written
        val prefs = mutablePreferencesOf()
        WidgetStateStore.write(prefs, snapshot(reading(120, 5, rxWallMs), writeAt), writeAt)

        val readAt = writeAt + 8L * 60_000L
        val cached = requireNotNull(WidgetStateStore.read(prefs, readAt))
        assertEquals(120, cached.glance.bgMgdl)
        assertEquals(5, cached.glance.trendTenths)
        assertEquals(10L * 60_000L, cached.glance.readingAgeMs)
    }

    @Test
    fun `presentation settings survive the round trip`() {
        val nowMs = 1_700_000_000_000L
        val prefs = mutablePreferencesOf()
        WidgetStateStore.write(prefs, snapshot(reading(140, -3, nowMs, rssi = -72), nowMs), nowMs)

        val cached = requireNotNull(WidgetStateStore.read(prefs, nowMs))
        assertEquals(UnitSpace.MmolL, cached.unit)
        assertEquals(42, cached.bgAlphaPct)
        assertFalse(cached.animationsEnabled)
        assertTrue(cached.death)
        assertEquals(ThemeIds.HELLO_KITTY, cached.themeId)
        assertEquals(-72, cached.rssi)
        assertEquals(ThemeIds.HELLO_KITTY to null, WidgetStateStore.themeOf(prefs))
    }

    /** The user's bands, not the boot defaults: 175 is HIGH against 170 but IN_RANGE against 180. */
    @Test
    fun `cached band uses the persisted thresholds`() {
        val nowMs = 1_700_000_000_000L
        val prefs = mutablePreferencesOf()
        WidgetStateStore.write(prefs, snapshot(reading(175, 0, nowMs), nowMs), nowMs)

        val cached = requireNotNull(WidgetStateStore.read(prefs, nowMs))
        assertEquals(AlertBand.HIGH, cached.glance.band)
        assertEquals(AlertBand.IN_RANGE, AlarmConfig.DEFAULT.thresholds.bandFor(175))
    }

    /** The persisted loss window drives signal-loss, so a reaped service cannot silently widen it. */
    @Test
    fun `signal loss is evaluated against the persisted window`() {
        val writeAt = 1_700_000_000_000L
        val prefs = mutablePreferencesOf()
        WidgetStateStore.write(prefs, snapshot(reading(110, 0, writeAt), writeAt), writeAt)

        assertFalse(WidgetStateStore.read(prefs, writeAt + 24L * 60_000L)!!.glance.signalLoss)
        assertTrue(WidgetStateStore.read(prefs, writeAt + 26L * 60_000L)!!.glance.signalLoss)
    }

    /** §3.6: no InferenceState survives the process, so a cached tile must never assert a forecast. */
    @Test
    fun `cached render carries no forecast claim`() {
        val nowMs = 1_700_000_000_000L
        val prefs = mutablePreferencesOf()
        WidgetStateStore.write(prefs, snapshot(reading(90, -20, nowMs), nowMs), nowMs)

        val cached = requireNotNull(WidgetStateStore.read(prefs, nowMs))
        assertFalse(cached.glance.forecastEligible)
        assertTrue(cached.glance.forecastUnavailable)
        assertNull(cached.glance.approaching)
        assertNull(cached.glance.urgent)
        assertEquals(GlyKind.VOID, cached.glyKind)
        assertNull(cached.iobU)
        assertNull(cached.gmi)
        assertNull(cached.steps)
    }

    /** A render with no reading must clear the cached one rather than resurrect it later. */
    @Test
    fun `a readingless render clears the cached value`() {
        val nowMs = 1_700_000_000_000L
        val prefs = mutablePreferencesOf()
        WidgetStateStore.write(prefs, snapshot(reading(120, 0, nowMs), nowMs), nowMs)
        WidgetStateStore.write(prefs, snapshot(null, nowMs), nowMs)

        val cached = requireNotNull(WidgetStateStore.read(prefs, nowMs))
        assertFalse(cached.glance.hasReading)
        assertNull(cached.glance.bgMgdl)
        assertNull(cached.rssi)
    }

    /** A theme retired by a later build must not leave the tile with an unresolvable id. */
    @Test
    fun `a retired persisted theme id is coerced`() {
        val nowMs = 1_700_000_000_000L
        val prefs = mutablePreferencesOf()
        WidgetStateStore.write(
            prefs,
            snapshot(reading(120, 0, nowMs), nowMs).copy(themeId = "windows_xp"),
            nowMs,
        )

        assertEquals(ThemeIds.TRON, WidgetStateStore.read(prefs, nowMs)!!.themeId)
        assertEquals(ThemeIds.TRON, WidgetStateStore.themeOf(prefs).first)
    }

    @Test
    fun `the floor render is honest rather than blank`() {
        val unknown = WidgetStateStore.unknown(1_700_000_000_000L)
        assertFalse(unknown.glance.hasReading)
        assertEquals(UnitSpace.MgDl, unknown.unit)
        assertEquals(ThemeIds.TRON, unknown.themeId)
        assertEquals(GlyKind.VOID, unknown.glyKind)
        assertFalse(unknown.death)
    }
}
