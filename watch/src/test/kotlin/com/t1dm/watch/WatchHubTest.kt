package com.t1dm.watch

import com.t1dm.watch.crypto.InMemoryWatchStores
import com.t1dm.watch.crypto.LoopbackWatchSessionFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchHubTest {

    private val desk = FakePeripheral(byteArrayOf(0x0a, 1, 1, 1, 1, 1, 1, 1), extended = true)
    private val watch = FakePeripheral(byteArrayOf(0x0b, 2, 2, 2, 2, 2, 2, 2), extended = false)
    private val air = FakeAir(desk, watch)
    private val stores = InMemoryWatchStores()
    private val scope = CoroutineScope(SupervisorJob() + testDispatchers.default)
    private val centrals = mutableListOf<FakeCentral>()
    private val hub = WatchHub(
        centralProvider = { FakeCentral(air).also { synchronized(centrals) { centrals += it } } },
        sessionFactory = LoopbackWatchSessionFactory(),
        stores = stores,
        codec = FakeCodec(),
        glanceSource = { testGlance },
        extendedSource = FakeSources,
        lowPower = { false },
        dispatchers = testDispatchers,
        config = WatchLinkConfig(
            enabled = true, autoConnect = false, backoffInitialMs = 50, backoffMaxMs = 100, handshakeTimeoutMs = 500,
            pollMs = 50,
        ),
    )

    private val deskId = "0a01010101010101"
    private val watchId = "0b02020202020202"

    @After fun tearDown() {
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun pairNext(): WatchSecurityState {
        val before = hub.devices.value.size
        hub.beginPairing()
        awaitValue { hub.pairing.value?.takeIf { it.phase == WatchLinkPhase.AWAIT_SAS } }
        hub.confirmSas(null)
        return awaitValue { hub.devices.value.takeIf { it.size == before + 1 }?.last()?.takeIf { it.lastPushMs != null } }
    }

    private fun device(id: String) = hub.devices.value.first { it.deviceId == id }

    @Test fun `pairs two peripherals and pushes each what it takes`() = runBlocking<Unit> {
        hub.start(scope)
        val a = pairNext()
        val b = pairNext()
        assertEquals(deskId, a.deviceId)
        assertEquals("the second pairing skips the paired name", watchId, b.deviceId)
        assertTrue(a.extended)
        assertNull(hub.pairing.value)
        assertEquals("connect pushes display, day history, stats, forecast, glance", listOf(5, 2, 4, 3, 3, 1), desk.kinds())
        assertEquals(listOf(1), watch.kinds())

        desk.records.clear(); watch.records.clear()
        hub.tick(10_000L)
        awaitValue { hub.devices.value.takeIf { ds -> ds.all { it.lastPushMs == 10_000L } } }
        assertEquals("tick: recent history, forecast, glance", listOf(2, 3, 3, 1), desk.kinds())
        assertEquals(listOf(1), watch.kinds())

        desk.records.clear(); watch.records.clear()
        hub.pushReading(10_500L)
        awaitValue { hub.devices.value.takeIf { ds -> ds.all { it.lastPushMs == 10_500L } } }
        assertEquals("reading: recent history, glance", listOf(2, 1), desk.kinds())
        assertEquals(listOf(1), watch.kinds())

        desk.records.clear()
        hub.pushDisplay(11_000L)
        awaitValue { desk.kinds().takeIf { it.isNotEmpty() } }
        assertEquals(listOf(5), desk.kinds())
        assertEquals("a plain watch takes no display", listOf(1), watch.kinds())
        assertEquals(2, stores.devices.load().size)
    }

    @Test fun `ERR_AUTH keeps the keys and the link comes back`() = runBlocking<Unit> {
        hub.start(scope)
        pairNext(); pairNext()
        desk.sendControl(byteArrayOf(0x11, 0x01, 0x00))
        awaitValue { device(deskId).takeIf { it.phase == WatchLinkPhase.ERROR } }
        assertEquals(WatchLinkPhase.LIVE, device(watchId).phase)
        assertTrue("unauthenticated, so the keys stay", stores.pairing(deskId).load()!!.bonded)
        assertEquals(2, stores.devices.load().size)

        desk.records.clear()
        awaitValue { device(deskId).takeIf { it.phase == WatchLinkPhase.LIVE } }
        awaitValue { desk.kinds().takeIf { 1 in it } }

        hub.unpair(deskId)
        assertEquals(watchId, awaitValue { hub.devices.value.takeIf { it.size == 1 } }.single().deviceId)
        assertEquals("the sealed unpair record reached it", 6, desk.kinds().last())
        assertNull(stores.pairing(deskId).load())
    }

    @Test fun `a peripheral that moved its service is reconnected`() = runBlocking<Unit> {
        hub.start(scope)
        pairNext()
        val stale = synchronized(centrals) { centrals.last() }
        stale.moved = true
        awaitValue { synchronized(centrals) { centrals.last() }.takeIf { it !== stale } }
        desk.records.clear()
        awaitValue { desk.kinds().takeIf { 1 in it } }
        assertEquals(WatchLinkPhase.LIVE, device(deskId).phase)
    }

    @Test fun `a stranger at the pairing's name keeps the keys`() = runBlocking<Unit> {
        hub.start(scope)
        pairNext()
        val realId = desk.deviceId
        desk.deviceId = realId.copyOf().also { it[7] = 0x7f }
        synchronized(centrals) { centrals.last() }.drop()
        delay(400)
        val s = hub.devices.value.single()
        assertNotEquals(WatchLinkPhase.LIVE, s.phase)
        assertTrue("keys survive a STATUS mismatch", stores.pairing(deskId).load()!!.bonded)
        assertEquals(null, stores.devices.load().single().address)

        desk.deviceId = realId
        val back = awaitValue { hub.devices.value.single().takeIf { it.phase == WatchLinkPhase.LIVE } }
        assertEquals(deskId, back.deviceId)
    }

    @Test fun `pairing refuses a peripheral whose STATUS names another device`() = runBlocking<Unit> {
        hub.start(scope)
        pairNext()
        val impostor = FakePeripheral(desk.deviceId, extended = true).apply { advertisedName = "T1DM-Watch-0c0c0c0c" }
        air.peripherals.add(1, impostor)
        hub.beginPairing()
        val failed = awaitValue { hub.pairing.value?.takeIf { it.phase == WatchLinkPhase.ERROR } }
        assertTrue(failed.lastError!!, failed.lastError!!.contains("reports T1DM-Watch-0a010101"))
        assertEquals(WatchLinkPhase.LIVE, hub.devices.value.single().phase)
        assertTrue(stores.pairing(deskId).load()!!.bonded)
    }

    @Test fun `each card confirms its own rotation`() = runBlocking<Unit> {
        hub.start(scope)
        pairNext(); pairNext()
        hub.rotate(deskId)
        hub.rotate(watchId)
        awaitValue { hub.devices.value.takeIf { ds -> ds.all { it.phase == WatchLinkPhase.AWAIT_SAS } } }

        hub.confirmSas(watchId)
        awaitValue { device(watchId).takeIf { it.phase == WatchLinkPhase.LIVE } }
        assertEquals(WatchLinkPhase.AWAIT_SAS, device(deskId).phase)
        hub.confirmSas(null)
        delay(200)
        assertEquals("no pairing in progress: nothing confirmed", WatchLinkPhase.AWAIT_SAS, device(deskId).phase)

        hub.confirmSas(deskId)
        awaitValue { device(deskId).takeIf { it.phase == WatchLinkPhase.LIVE } }
        desk.records.clear()
        hub.tick(20_000L)
        assertTrue("the new keys open", 1 in awaitValue { desk.kinds().takeIf { 1 in it } })
    }

    @Test fun `a rotation nobody answers keeps the live keys`() = runBlocking<Unit> {
        hub.start(scope)
        val before = pairNext()
        desk.answersHello = false
        hub.rotate(deskId)
        val after = awaitValue { device(deskId).takeIf { it.lastError?.startsWith("Rotation failed") == true } }
        assertEquals(WatchLinkPhase.LIVE, after.phase)
        assertEquals(before.keyFingerprint, after.keyFingerprint)

        desk.records.clear()
        hub.tick(30_000L)
        assertTrue("the old keys still open", 1 in awaitValue { desk.kinds().takeIf { 1 in it } })
    }

    @Test fun `stored pairings come back after stopForReset and resume`() = runBlocking<Unit> {
        hub.start(scope)
        pairNext()
        assertEquals(1, stores.devices.load().size)
        hub.stopForReset()
        awaitValue { hub.devices.value.takeIf { it.isEmpty() } }
        hub.resumeAfterReset()
        assertEquals(deskId, awaitValue { hub.devices.value.singleOrNull() }.deviceId)
    }

    @Test fun `a new service scope rebuilds the links`() = runBlocking<Unit> {
        hub.start(scope)
        pairNext()
        val second = CoroutineScope(SupervisorJob() + testDispatchers.default)
        try {
            scope.coroutineContext[Job]?.cancel()
            hub.start(second)
            awaitValue { hub.devices.value.singleOrNull()?.takeIf { it.phase == WatchLinkPhase.RECONNECTING } }
            hub.unpair(deskId)
            awaitValue { hub.devices.value.takeIf { it.isEmpty() } }
            assertNull(stores.pairing(deskId).load())
        } finally {
            second.coroutineContext[Job]?.cancel()
        }
    }
}
