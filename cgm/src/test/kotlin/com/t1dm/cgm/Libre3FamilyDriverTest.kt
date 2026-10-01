package com.t1dm.cgm

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** No Android type: no adapter, no sighting; rationing is counted on an empty scan. */
@OptIn(ExperimentalCoroutinesApi::class)
class Libre3FamilyDriverTest {

    @Test
    fun `the address is the identity until provisioning mints a serial`() {
        val descriptor = Libre3FamilyDriver.descriptorFor(ADDRESS)
        assertEquals(Libre3FamilyDriver.sourceIdFor(ADDRESS), descriptor.id)
        assertEquals(Libre3FamilyDriver.VENDOR_ID, descriptor.vendorId)
        assertEquals(Libre3FamilyDriver.MODEL_ID, descriptor.sensorModelId)
        assertEquals(ADDRESS, descriptor.serialSuffix)
        assertEquals(Libre3FamilyDriver.WARMUP_WINDOW_MIN, descriptor.warmupWindowMin)
        assertFalse(descriptor.passiveOnly)
    }

    @Test
    fun `the family offers NFC provisioning and the EU wear rating`() = runTest {
        val driver = driver {}
        assertTrue(driver.facts.supportsProvision)
        assertEquals(Libre3FamilyDriver.RATED_CYCLE_DAYS_EU, driver.facts.ratedCycleDays)
    }

    @Test
    fun `the first pass sweeps, and a sweep that hears nothing doubles the gap`() = runTest {
        var scans = 0
        val driver = driver { scans++ }
        driver.candidates(active = emptySet(), connected = emptySet())
        assertEquals(1, scans)
        advanceTimeBy(Libre3FamilyDriver.DISCOVERY_INTERVAL_MS)
        driver.candidates(active = emptySet(), connected = emptySet())
        assertEquals("the gap doubled after hearing nothing", 1, scans)
        advanceTimeBy(Libre3FamilyDriver.DISCOVERY_INTERVAL_MS)
        driver.candidates(active = emptySet(), connected = emptySet())
        assertEquals(2, scans)
    }

    @Test
    fun `rescan re-arms an immediate sweep`() = runTest {
        var scans = 0
        val driver = driver { scans++ }
        driver.candidates(active = emptySet(), connected = emptySet())
        driver.rescan()
        driver.candidates(active = emptySet(), connected = emptySet())
        assertEquals(2, scans)
    }

    @Test
    fun `no session is built without a pairing stack`() = runTest {
        val driver = driver {}
        assertEquals(null, driver.createSession(Libre3FamilyDriver.descriptorFor(ADDRESS), this))
    }

    @Test
    fun `a provisioned sensor with a pairing stack gets a pairing session`() = runTest {
        val repository = FakeCgmRepository()
        repository.saveSensorSecret(Libre3FamilyDriver.sourceIdFor(ADDRESS), SENSOR.encode())
        val transport = FakeLibre3GattTransport()
        val driver = driver(repository, stack(transport)) {}
        val session = driver.createSession(Libre3FamilyDriver.descriptorFor(ADDRESS), this)
        assertTrue(session is Libre3ConnectedSource)
    }

    @Test
    fun `an unprovisioned sensor gets no session even with a pairing stack`() = runTest {
        val transport = FakeLibre3GattTransport()
        val driver = driver(FakeCgmRepository(), stack(transport)) {}
        assertEquals(null, driver.createSession(Libre3FamilyDriver.descriptorFor(ADDRESS), this))
    }

    @Test
    fun `a pairing stack without the tables dir gets no session`() = runTest {
        val repository = FakeCgmRepository()
        repository.saveSensorSecret(Libre3FamilyDriver.sourceIdFor(ADDRESS), SENSOR.encode())
        val driver = driver(
            repository,
            Libre3FamilyDriver.PairingStack(
                native = FakeLibre3Native(),
                tablesDir = { null }, // §9: not pushed, no pairing
                transportAt = { _, _, _ -> FakeLibre3GattTransport() },
            ),
        ) {}
        assertEquals(null, driver.createSession(Libre3FamilyDriver.descriptorFor(ADDRESS), this))
    }

    @Test
    fun `a sighting is unadoptable until a secret is on record`() = runTest {
        val repository = FakeCgmRepository()
        val driver = driver(repository) {}
        assertFalse(driver.adoptable(ADDRESS))
        repository.saveSensorSecret(Libre3FamilyDriver.sourceIdFor(ADDRESS), byteArrayOf(1))
        assertTrue(driver.adoptable(ADDRESS))
    }

    @Test
    fun `the adoptable check is cached, not re-read every sweep`() = runTest {
        val repository = FakeCgmRepository()
        val driver = driver(repository) {}
        repository.saveSensorSecret(Libre3FamilyDriver.sourceIdFor(ADDRESS), byteArrayOf(1))
        assertTrue(driver.adoptable(ADDRESS))
        repository.clearSensorSecret(Libre3FamilyDriver.sourceIdFor(ADDRESS))
        assertTrue("cached affirmative persists", driver.adoptable(ADDRESS))
    }

    @Test
    fun `a session that yields nothing is held off, doubling to the cap`() = runTest {
        val repository = FakeCgmRepository()
        repository.saveSensorSecret(Libre3FamilyDriver.sourceIdFor(ADDRESS), SENSOR.encode())
        val driver = driver(repository, stack(FakeLibre3GattTransport())) {}
        val descriptor = Libre3FamilyDriver.descriptorFor(ADDRESS)
        for (gapS in listOf(30L, 60L, 120L, 240L, 300L, 300L)) {
            driver.sessionYieldedNothing(descriptor)
            assertNull("held off for $gapS s", driver.createSession(descriptor, this))
            advanceTimeBy(gapS * 1000 - 1)
            assertNull(driver.createSession(descriptor, this))
            advanceTimeBy(1)
            assertNotNull("dialed again after $gapS s", driver.createSession(descriptor, this))
        }
    }

    @Test
    fun `a sighting, sensorReadable, rescan or forget lifts the hold-off`() = runTest {
        val repository = FakeCgmRepository()
        repository.saveSensorSecret(Libre3FamilyDriver.sourceIdFor(ADDRESS), SENSOR.encode())
        val driver = driver(repository, stack(FakeLibre3GattTransport())) {}
        val descriptor = Libre3FamilyDriver.descriptorFor(ADDRESS)
        val lifts = listOf<suspend () -> Unit>(
            { driver.sighted(ADDRESS) },
            { driver.sensorReadable(descriptor) },
            { driver.rescan() },
            { driver.forget(descriptor) },
        )
        for ((i, lift) in lifts.withIndex()) {
            driver.sessionYieldedNothing(descriptor)
            assertTrue(driver.heldOff(ADDRESS))
            lift()
            assertFalse("lift $i", driver.heldOff(ADDRESS))
            assertNotNull(driver.createSession(descriptor, this))
        }
    }

    /** The full §4 state a provisioned sensor carries into a pairing session. */
    private val SENSOR = Libre3SensorState(
        receiverId = 0x684FC53Fu,
        serial = "0T1DM0000",
        bleAddress = ADDRESS,
        blePin = byteArrayOf(0x11, 0x22, 0x33, 0x44),
        activationTimeS = 1_790_000_000L,
        wearDurationMin = 20_160,
        region = Libre3Region.Eu,
        provisionedAtMs = 0L,
        kAuth = null,
    )

    private fun stack(transport: FakeLibre3GattTransport): Libre3FamilyDriver.PairingStack =
        Libre3FamilyDriver.PairingStack(
            native = FakeLibre3Native(),
            tablesDir = { "/data/user/0/com.t1dm.app/files/libre3/tables" },
            transportAt = { _, _, _ -> transport },
        )

    private fun kotlinx.coroutines.test.TestScope.driver(
        repository: FakeCgmRepository = FakeCgmRepository(),
        pairing: Libre3FamilyDriver.PairingStack? = null,
        onScan: () -> Unit,
    ): Libre3FamilyDriver {
        val scope = this
        return Libre3FamilyDriver(
            repository = repository,
            nowMs = { scope.currentTime },
            discover = {
                onScan()
                emptyFlow()
            },
            pairing = pairing,
        )
    }

    private companion object {
        const val ADDRESS = "C0:FF:EE:C0:FF:EE"
    }
}