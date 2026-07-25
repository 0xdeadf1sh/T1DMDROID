package com.t1dm.app.widget

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.t1dm.app.notify.BgGlanceComputer
import com.t1dm.core.design.ThemeIds
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.UnitSpace
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wall-clock bound on the live pull ([boundedWidgetPull]) and the fallback cascade it feeds. The
 * defect this pins: `provideGlance` used to await the pull unbounded, so a single read that suspended
 * and never resumed parked it short of `provideContent` forever and the host held its loading spinner.
 * What matters here is that BOTH failure modes — a throw AND a stall — collapse to the same `null` the
 * cascade keys on, never propagating, and that the tile then renders the last-known or the honest floor.
 */
class WidgetGlanceTest {

    private val thresholds = AlertThresholds(urgentLowMgdl = 60, lowMgdl = 80, highMgdl = 170, urgentHighMgdl = 240)

    private fun reading(bgMgdl: Int, rxWallMs: Long) = CgmReading(
        sourceId = CgmSourceId("test"),
        tsMs = rxWallMs - rxWallMs.mod(300_000L),
        bgMgdl = bgMgdl,
        trendTenthsPerMin = 5,
        minFromStart = 600,
        quality = null,
        provenance = ReadingProvenance.MEASURED,
        flag = ReadingFlag.NORMAL,
        tzOffsetMin = 0,
        rxWallMs = rxWallMs,
        rssi = -70,
    )

    private fun snapshot(latest: CgmReading?, nowMs: Long) = WidgetSnapshot(
        glance = BgGlanceComputer.compute(
            latest = latest,
            state = InferenceState(),
            thresholds = thresholds,
            lossMin = 25,
            staleMin = STALE_MIN,
            nowMs = nowMs,
        ),
        unit = UnitSpace.MmolL,
        animationsEnabled = true,
        bgAlphaPct = 20,
        iobU = 1.2,
        cobG = 18.0,
        rssi = latest?.rssi,
        clockHour = null,
        clockConf = null,
        gmi = 6.2,
        steps = 3100,
        death = false,
        glyText = "STABLE",
        glyKind = GlyKind.STABLE,
        thresholds = thresholds,
        lossMin = 25,
        themeId = ThemeIds.HELLO_KITTY,
        customThemeJson = null,
    )

    /** The fallback the render commits, mirroring `provideGlance`: live if the pull completed, else the
     *  cached tile re-aged, else the honest floor. */
    private fun resolve(live: WidgetSnapshot?, cachedNow: Long, prefs: androidx.datastore.preferences.core.Preferences?) =
        live ?: prefs?.let { WidgetStateStore.read(it, cachedNow) } ?: WidgetStateStore.unknown(cachedNow)

    @Test
    fun `a completed pull returns its snapshot and fires neither callback`() = runBlocking {
        val nowMs = 1_700_000_000_000L
        val expected = snapshot(reading(120, nowMs), nowMs)
        var timedOut = false
        var errored: Throwable? = null

        val live = boundedWidgetPull(2000L, onTimeout = { timedOut = true }, onError = { errored = it }) { expected }

        assertSame(expected, live)
        assertFalse(timedOut)
        assertNull(errored)
    }

    /** A throw is absorbed to null (never rethrown) and routed to [onError], not [onTimeout]. */
    @Test
    fun `a throwing pull yields null via onError, not propagation`() = runBlocking {
        var timedOut = false
        var errored: Throwable? = null
        val boom = IllegalStateException("cold Room open failed")

        val live = boundedWidgetPull(2000L, onTimeout = { timedOut = true }, onError = { errored = it }) { throw boom }

        assertNull(live)
        assertFalse(timedOut)
        // Not assertSame: coroutine stacktrace recovery hands onError a recovered copy, not the instance.
        assertTrue(errored is IllegalStateException)
        assertEquals("cold Room open failed", errored?.message)
    }

    /** The crux: a read that never resumes is cancelled at the budget and yields null (routed to
     *  [onTimeout]) rather than parking the caller — this is what a `runCatching` alone cannot do. */
    @Test
    fun `a stalled pull is bounded to null via onTimeout`() = runBlocking {
        var timedOut = false
        var errored: Throwable? = null

        val live = boundedWidgetPull(40L, onTimeout = { timedOut = true }, onError = { errored = it }) {
            awaitCancellation() // models a read parked forever (a stuck InvalidationTracker subscribe)
        }

        assertNull(live)
        assertTrue(timedOut)
        assertNull(errored)
    }

    /** End-to-end: a stalled pull with a populated cache renders the last-known reading, re-aged. */
    @Test
    fun `a stalled pull falls back to the last-known tile`() = runBlocking {
        val writeAt = 1_700_000_000_000L
        val prefs = mutablePreferencesOf()
        WidgetStateStore.write(prefs, snapshot(reading(120, writeAt - 120_000L), writeAt), writeAt)

        val readAt = writeAt + 8L * 60_000L
        val live = boundedWidgetPull(40L) { awaitCancellation() }
        val rendered = resolve(live, readAt, prefs)

        assertNull(live)
        assertEquals(120, rendered.glance.bgMgdl)
        assertEquals(10L * 60_000L, rendered.glance.readingAgeMs) // 2 min at write + 8 min elapsed
        assertEquals(UnitSpace.MmolL, rendered.unit)
        assertEquals(ThemeIds.HELLO_KITTY, rendered.themeId)
    }

    /** End-to-end: a stalled pull with NO cache renders the honest floor, never a spinner or a lie. */
    @Test
    fun `a stalled pull with no cache falls back to the floor tile`() = runBlocking {
        val nowMs = 1_700_000_000_000L
        val live = boundedWidgetPull(40L) { awaitCancellation() }
        val rendered = resolve(live, nowMs, prefs = null)

        assertNull(live)
        assertFalse(rendered.glance.hasReading)
        assertEquals(GlyKind.VOID, rendered.glyKind)
        assertEquals(UnitSpace.MgDl, rendered.unit)
        assertEquals(ThemeIds.TRON, rendered.themeId)
    }
}
