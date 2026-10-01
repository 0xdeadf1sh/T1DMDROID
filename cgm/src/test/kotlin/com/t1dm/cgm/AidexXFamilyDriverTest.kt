package com.t1dm.cgm

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** No Android type: no adapter, no sighting; rationing is counted on an empty scan. */
@OptIn(ExperimentalCoroutinesApi::class)
class AidexXFamilyDriverTest {

    @Test
    fun `a heard sensor is listed but never adopted`() {
        val listed = AidexXFamilyDriver.listing(paired = emptyMap(), heard = mapOf(SERIAL to NAME))
        assertEquals(1, listed.size)
        assertFalse("only a tap may start reading a sensor nobody paired", listed.single().adoptable)
    }

    @Test
    fun `a paired sensor is adoptable and listed once when also heard`() {
        val listed = AidexXFamilyDriver.listing(paired = mapOf(SERIAL to NAME), heard = mapOf(SERIAL to NAME))
        assertEquals(1, listed.size)
        assertTrue(listed.single().adoptable)
    }

    @Test
    fun `nothing is scanned while no active sensor lacks a link`() = runTest {
        var scans = 0
        val driver = driver(this, FakeCgmRepository()) { scans++ }
        driver.candidates(active = emptySet(), connected = emptySet())
        driver.candidates(active = setOf(ID), connected = setOf(ID))
        assertEquals(0, scans)
    }

    @Test
    fun `a wanted sensor with no handle is swept for, and a sweep that hears nothing doubles the gap`() = runTest {
        var scans = 0
        val driver = driver(this, FakeCgmRepository()) { scans++ }
        driver.want()
        assertEquals(1, scans)
        advanceTimeBy(AidexXFamilyDriver.SWEEP_GAP_MS)
        driver.want()
        assertEquals("the gap doubled after hearing nothing", 1, scans)
        advanceTimeBy(AidexXFamilyDriver.SWEEP_GAP_MS)
        driver.want()
        assertEquals(2, scans)
    }

    @Test
    fun `a stored address stands in for a scan until a session on it gives nothing`() = runTest {
        var scans = 0
        val repo = FakeCgmRepository()
        repo.saveSensorAddress(ID, ADDRESS)
        val driver = driver(this, repo) { scans++ }
        driver.want()
        assertEquals(0, scans)

        driver.sessionYieldedNothing(DESCRIPTOR)
        assertNull(driver.reconnectAddress(SERIAL))
        driver.want()
        assertEquals(1, scans)
    }

    @Test
    fun `a keyed session that drops keeps its address trusted`() = runTest {
        val driver = driver(this, FakeCgmRepository())
        driver.authenticatedOn(SERIAL, ADDRESS)
        driver.sessionYieldedNothing(DESCRIPTOR)
        assertEquals(ADDRESS, driver.reconnectAddress(SERIAL))
    }

    @Test
    fun `the address a key was derived on is stored`() = runTest {
        val repo = FakeCgmRepository()
        driver(this, repo).authenticatedOn(SERIAL, ADDRESS)
        assertEquals(ADDRESS, repo.loadSensorAddress(ID))
    }

    @Test
    fun `a search sweeps once with nothing wanted`() = runTest {
        var scans = 0
        val driver = driver(this, FakeCgmRepository()) { scans++ }
        driver.rescan()
        driver.candidates(active = emptySet(), connected = emptySet())
        driver.candidates(active = emptySet(), connected = emptySet())
        assertEquals(1, scans)
    }

    @Test
    fun `each keyless session doubles the dial hold-off, up to five minutes`() = runTest {
        val driver = driver(this, FakeCgmRepository())
        for (gap in listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L)) {
            driver.sessionYieldedNothing(DESCRIPTOR)
            advanceTimeBy(gap - 1)
            assertTrue("held at ${gap - 1} ms", driver.dialHeldOff(SERIAL))
            advanceTimeBy(1)
            assertFalse("lifted at $gap ms", driver.dialHeldOff(SERIAL))
        }
    }

    @Test
    fun `a search or a reconnect lifts the dial hold-off`() = runTest {
        val driver = driver(this, FakeCgmRepository())
        driver.sessionYieldedNothing(DESCRIPTOR)
        driver.rescan()
        assertFalse(driver.dialHeldOff(SERIAL))

        driver.sessionYieldedNothing(DESCRIPTOR)
        driver.forget(DESCRIPTOR)
        assertFalse(driver.dialHeldOff(SERIAL))
    }

    @Test
    fun `a keyed session restarts the dial hold-off from its floor`() = runTest {
        val driver = driver(this, FakeCgmRepository())
        repeat(3) {
            driver.sessionYieldedNothing(DESCRIPTOR)
            advanceTimeBy(AidexXFamilyDriver.DIAL_HOLD_OFF_MAX_MS)
        }
        driver.authenticatedOn(SERIAL, ADDRESS)
        assertFalse(driver.dialHeldOff(SERIAL))
        driver.sessionYieldedNothing(DESCRIPTOR)
        assertFalse("a keyed session's drop is range, not a bad handle", driver.dialHeldOff(SERIAL))

        driver.sessionYieldedNothing(DESCRIPTOR)
        advanceTimeBy(AidexXFamilyDriver.DIAL_HOLD_OFF_MIN_MS)
        assertFalse(driver.dialHeldOff(SERIAL))
    }

    @Test
    fun `no session is built with nothing to dial`() = runTest {
        assertNull(driver(this, FakeCgmRepository()).createSession(DESCRIPTOR, this))
    }

    private suspend fun AidexXFamilyDriver.want() = candidates(active = setOf(ID), connected = emptySet())

    private fun driver(scope: TestScope, repo: CgmRepository, onScan: () -> Unit = {}) = AidexXFamilyDriver(
        bonded = BondedAidexDevices(null),
        repository = repo,
        session = AidexSessionCodec,
        nowMs = { scope.currentTime },
        transportFactory = { _, _ -> error("no session is built in these tests") },
        discover = {
            onScan()
            emptyFlow()
        },
    )

    private companion object {
        const val SERIAL = "00000T1DM1"
        const val NAME = "LinX-$SERIAL"
        const val ADDRESS = "02:00:00:00:00:01"
        val ID = AidexXFamilyDriver.sourceIdFor(SERIAL)
        val DESCRIPTOR = AidexXConnectedSource.descriptorFor(SERIAL, NAME)
    }
}
